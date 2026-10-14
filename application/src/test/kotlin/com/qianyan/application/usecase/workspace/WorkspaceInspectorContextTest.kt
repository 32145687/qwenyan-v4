package com.qianyan.application.usecase.workspace

import com.qianyan.model.IntentType
import com.qianyan.model.context.ContextBudget
import com.qianyan.model.context.ContextSourceKind
import com.qianyan.model.workspace.WorkspaceObjectKind
import com.qianyan.model.workspace.WorkspaceSelection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * I14 · Context Tab（§3 / §9 / §10 / §16 / §23）：Inspector 的 Context **必须**来自既有 I6。
 */
class WorkspaceInspectorContextTest {

    @Test
    fun `project context comes from the existing context engine`() {
        val f = workspaceFixture()
        val w = seedWorkspaceWorld(f)

        val view = f.app.workspace.context(w.projectId, WorkspaceSelection.project(w.projectId))

        assertEquals(w.projectId, view.selection.projectId)
        assertEquals("长夜行舟", view.novelTitle)
        assertEquals(IntentType.PLAN, view.purpose)
        // packId 形态来自既有 I6 ContextEngineUseCases（证明没有第二套 Context Builder）
        assertTrue(view.packId.startsWith("ctx-"), "Context 必须来自 I6 ContextEngine：${view.packId}")
        assertEquals(ContextBudget.DEFAULT_MAX_TOKENS, view.budget.budgetTokens)
        assertEquals("approx-chars-per-4", view.budget.estimator)
        assertTrue(view.budget.usedRatio in 0f..1f)
        assertTrue(view.groups.isNotEmpty(), "Project Context 应有真实来源条目")
        assertTrue(view.groups.any { it.source == ContextSourceKind.NOVEL }, "应包含作品来源：${view.groups.map { it.source }}")
        f.close()
    }

    @Test
    fun `chapter context is focused on that chapter and reuses chapter and draft sources`() {
        val f = workspaceFixture()
        val w = seedWorkspaceWorld(f)

        val view = f.app.workspace.context(
            w.projectId,
            WorkspaceSelection.chapter(w.projectId, w.novelId, w.chapter1),
        )

        assertEquals(IntentType.REWRITE, view.purpose)
        assertTrue(view.focusLabel.contains("第 1 章"), view.focusLabel)
        assertTrue(view.packId.contains(w.chapter1.value), "焦点章节进入 I6 ContextRequest：${view.packId}")
        assertTrue(view.groups.any { it.source == ContextSourceKind.CHAPTER })
        assertTrue(view.groups.any { it.source == ContextSourceKind.DRAFT })
        assertTrue(view.notices.isEmpty(), "章节焦点是 I6 原生能力，不应有额外说明：${view.notices}")
        f.close()
    }

    @Test
    fun `object level context states its boundary instead of fabricating data`() {
        val f = workspaceFixture()
        val w = seedWorkspaceWorld(f)

        val character = f.app.workspace.context(
            w.projectId,
            WorkspaceSelection.character(w.projectId, w.novelId, w.characterId),
        )
        assertEquals(IntentType.ANALYZE, character.purpose)
        assertTrue(
            character.notices.any { it.contains("对象级 Context 尚无专用来源") },
            "必须如实说明对象级 Context 的边界（不伪造）：${character.notices}",
        )

        val change = f.app.workspace.context(w.projectId, WorkspaceSelection.change(w.projectId, w.artifactId))
        assertTrue(change.notices.any { it.contains("修改 / 历史不参与 Context 选择") }, change.notices.toString())
        f.close()
    }

    @Test
    fun `task context carries the task reference into the existing context request`() {
        val f = workspaceFixture()
        val w = seedWorkspaceWorld(f)

        val view = f.app.workspace.inspector(w.projectId, WorkspaceSelection.task(w.projectId, w.taskId))

        val task = view.task
        assertNotNull(task, "TASK 选择必须给出任务投影")
        assertEquals(w.taskId, task.taskId)
        assertEquals("BACKGROUND", task.taskType)
        assertEquals("PENDING", task.taskStatus, "Task 状态仍以既有 Task 为准")
        assertEquals("QUEUED", task.queueStatus, "队列状态仍以既有 I13 为准")
        assertEquals(IntentType.CUSTOM, view.context!!.purpose)
        assertTrue(view.context!!.notices.any { it.contains("任务级 Context 复用 I6") }, view.context!!.notices.toString())
        f.close()
    }

    @Test
    fun `context projection is deterministic for the same selection`() {
        val f = workspaceFixture()
        val w = seedWorkspaceWorld(f)
        val selection = WorkspaceSelection.chapter(w.projectId, w.novelId, w.chapter1)

        val first = f.app.workspace.context(w.projectId, selection)
        val second = f.app.workspace.context(w.projectId, selection)

        assertEquals(first.packId, second.packId)
        assertEquals(first.packVersion, second.packVersion)
        assertEquals(first.groups.map { it.source }, second.groups.map { it.source })
        assertEquals(
            first.groups.flatMap { g -> g.entries.map { it.itemId } },
            second.groups.flatMap { g -> g.entries.map { it.itemId } },
        )
        f.close()
    }

    @Test
    fun `empty project still yields a valid context view without exception`() {
        val f = workspaceFixture()
        val bare = f.app.projects.createProject(title = "空书")

        val view = f.app.workspace.context(bare.projectId, WorkspaceSelection.project(bare.projectId))

        assertEquals(ContextBudget.DEFAULT_MAX_TOKENS, view.budget.budgetTokens)
        assertTrue(view.groups.all { it.entries.isNotEmpty() }, "分组不得包含空组")
        assertEquals("空书", view.novelTitle)
        f.close()
    }

    @Test
    fun `preview is truncated for display but never becomes a second context model`() {
        val f = workspaceFixture()
        val w = seedWorkspaceWorld(f)

        val view = f.app.workspace.context(
            w.projectId,
            WorkspaceSelection.chapter(w.projectId, w.novelId, w.chapter1),
        )

        // 条目只做展示截断；pack 内容本身不变（此处断言投影不超出上限，且条目仍带真实来源版本）
        view.groups.flatMap { it.entries }.forEach { entry ->
            assertTrue(entry.preview.length <= WorkspaceProjections.PREVIEW_LIMIT + 1, "预览仅展示截断：${entry.preview.length}")
            assertTrue(entry.sourceVersion.isNotBlank(), "条目必须带来源版本（可追踪）")
        }
        assertTrue(
            view.groups.flatMap { it.entries }.any { it.source == ContextSourceKind.DRAFT && it.preview.isNotBlank() },
            "正文条目应带真实预览",
        )
        assertEquals(
            WorkspaceObjectKind.CHAPTER,
            view.selection.kind,
        )
        f.close()
    }
}