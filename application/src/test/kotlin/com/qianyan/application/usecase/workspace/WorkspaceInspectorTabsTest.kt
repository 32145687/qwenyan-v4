package com.qianyan.application.usecase.workspace

import com.qianyan.model.change.ChangeArtifactStatus
import com.qianyan.model.change.DiffLineKind
import com.qianyan.model.log.AgentLogStatus
import com.qianyan.model.workspace.InspectorTab
import com.qianyan.model.workspace.InspectorTaskContextState
import com.qianyan.model.workspace.WorkspaceSelection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * I14 · Inspector 三个固定标签 + 复用证据（§8 / §11 / §12 / §23 / §28）。
 *
 * 证明：Inspector 永远是 CONTEXT / ACTIVITY / CHANGES 三个标签；
 * Activity 来自 I4，Change / History 来自 I9 / I10 —— 不是第二套模型。
 */
class WorkspaceInspectorTabsTest {

    @Test
    fun `inspector always exposes exactly three fixed tabs`() {
        assertEquals(
            listOf(InspectorTab.CONTEXT, InspectorTab.ACTIVITY, InspectorTab.CHANGES),
            InspectorTab.values().toList(),
            "Inspector 永远只有 上下文 / 活动 / 修改 三个标签（§8）",
        )
    }

    @Test
    fun `task selection surfaces activity through the existing session link`() {
        val f = workspaceFixture()
        val w = seedWorkspaceWorld(f)

        val view = f.app.workspace.inspector(w.projectId, WorkspaceSelection.task(w.projectId, w.taskId))

        assertEquals(1, view.activities.size, "Task → AgentSession（既有链路）→ Activity")
        val activity = view.activities.single()
        assertEquals(w.sessionId, activity.sessionId)
        assertEquals("Qianyan 修改了第 1 章", activity.title)
        assertEquals(AgentLogStatus.RUNNING, activity.status)
        assertEquals(1, activity.toolCalls.size, "Tool 调用事实来自既有 I4 ToolCallLog")
        assertEquals("read_file", activity.toolCalls.single().toolName)
        f.close()
    }

    @Test
    fun `chapter selection keeps project level activity and chapter scoped changes`() {
        val f = workspaceFixture()
        val w = seedWorkspaceWorld(f)

        val view = f.app.workspace.inspector(
            w.projectId,
            WorkspaceSelection.chapter(w.projectId, w.novelId, w.chapter1),
        )

        assertEquals(1, view.activities.size, "非 TASK 选择展示项目级活动（无对象级稳定链路时不猜测）")
        assertEquals(1, view.changes.size, "章节选择按既有 target.chapterId 过滤")
        val change = view.changes.single()
        assertEquals(w.artifactId, change.artifactId)
        assertEquals(ChangeArtifactStatus.READY, change.status)
        assertTrue(change.validationPassed, "校验结果来自 I9 ValidationResult")
        assertTrue(change.addedLines > 0, "Diff 统计来自既有 I9 TextDiff")
        assertTrue(
            change.diffLines.any { it.kind == DiffLineKind.ADDED },
            "Diff 行来自既有 I9 TextDiff hunks：${change.diffLines.map { it.kind }}",
        )
        assertTrue(view.history.isEmpty(), "尚无提交历史 ⇒ 空态而非异常")
        assertFalse(view.hasChangeData.not(), "有 Change ⇒ hasChangeData 为真")
        f.close()
    }

    @Test
    fun `changes tab also lists committed history once it exists`() {
        val f = workspaceFixture()
        val w = seedWorkspaceWorld(f)

        // 经既有 I10 提交流程产生历史（唯一 Canonical 写入路径，I14 不参与写入）
        val commit = f.app.commits.commit(w.projectId, w.artifactId, approvedGate(f, w))
        assertEquals(com.qianyan.model.commit.CommitOperation.COMMIT, commit.historyEntry.operation)

        val view = f.app.workspace.inspector(
            w.projectId,
            WorkspaceSelection.chapter(w.projectId, w.novelId, w.chapter1),
        )

        assertEquals(1, view.history.size)
        val entry = view.history.single()
        assertEquals(commit.historyEntry.historyId, entry.historyId)
        assertEquals(w.chapter1, entry.chapterId)
        assertTrue(entry.summary.isNotBlank())
        f.close()
    }

    @Test
    fun `empty data yields empty states instead of failures`() {
        val f = workspaceFixture()
        val bare = f.app.projects.createProject(title = "空书")

        val view = f.app.workspace.inspector(bare.projectId, WorkspaceSelection.project(bare.projectId))

        assertTrue(view.activities.isEmpty())
        assertTrue(view.changes.isEmpty())
        assertTrue(view.history.isEmpty())
        assertFalse(view.hasChangeData)
        assertEquals("空书", view.title)
        assertEquals("项目", view.detail)
        f.close()
    }

    @Test
    fun `reading the inspector never writes project state or canonical data`() {
        val f = workspaceFixture()
        val w = seedWorkspaceWorld(f)
        val tables = listOf(
            "ProjectState", "Chapter", "ChapterDraft", "StoryFoundation",
            "CommitHistory", "Novel", "Vocabulary", "Character", "WorldRule", "Foreshadow",
        )
        val before = tables.associateWith { countRows(f.handle, it) }
        val stateBefore = f.app.projects.state(w.projectId)
        val chapterBefore = f.app.chapters.findById(w.chapter1)
        val draftBefore = f.app.draftRepository.getById(w.draft1)

        // 反复读取 Inspector（含切换"标签"对应的三个数据面）不得产生任何持久化副作用
        listOf(
            WorkspaceSelection.project(w.projectId),
            WorkspaceSelection.chapter(w.projectId, w.novelId, w.chapter1),
            WorkspaceSelection.character(w.projectId, w.novelId, w.characterId),
            WorkspaceSelection.task(w.projectId, w.taskId),
            WorkspaceSelection.change(w.projectId, w.artifactId),
        ).forEach { selection ->
            f.app.workspace.inspector(w.projectId, selection)
            f.app.workspace.activities(w.projectId, selection)
            f.app.workspace.changes(w.projectId, selection)
            f.app.workspace.history(w.projectId, selection)
        }

        tables.forEach { table -> assertEquals(before[table], countRows(f.handle, table), "$table 行数不得变化") }
        assertEquals(stateBefore, f.app.projects.state(w.projectId), "ProjectState 不得被 Workspace 读取路径修改")
        assertEquals(chapterBefore, f.app.chapters.findById(w.chapter1), "Chapter 不得被修改")
        assertEquals(draftBefore, f.app.draftRepository.getById(w.draft1), "ChapterDraft 不得被修改")
        f.close()
    }

    @Test
    fun `project selection aggregates the whole project without fabricating object scoping`() {
        val f = workspaceFixture()
        val w = seedWorkspaceWorld(f)

        val view = f.app.workspace.inspector(w.projectId, WorkspaceSelection.project(w.projectId))

        assertEquals(1, view.activities.size)
        assertEquals(1, view.changes.size)
        assertTrue(view.history.isEmpty())
        assertEquals(InspectorTaskContextState.NO_TASK, view.taskContext?.state)
        assertNull(view.context, "4B.1a 不提供重建 Pack")
        assertTrue(view.currentFacts.isNotEmpty(), "当前事实独立只读展示")
        assertEquals("长夜行舟", view.title)
        f.close()
    }
}