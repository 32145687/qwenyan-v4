package com.qianyan.application.usecase.workspace

import com.qianyan.application.error.ApplicationException
import com.qianyan.model.ChapterId
import com.qianyan.model.NovelId
import com.qianyan.model.TaskId
import com.qianyan.model.change.ChangeArtifactId
import com.qianyan.model.workspace.WorkspaceErrorCodes
import com.qianyan.model.workspace.WorkspaceObjectKind
import com.qianyan.model.workspace.WorkspaceSelection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * I14 · 当前对象选择（§6 / §7 / §23 / §28：selectProject / selectChapter / selectCharacter / selectTask，
 * Scope 跨 Project 拒绝，Missing Object 明确错误）。
 */
class WorkspaceSelectionTest {

    @Test
    fun `select normalizes every supported object kind`() {
        val f = workspaceFixture()
        val w = seedWorkspaceWorld(f)
        val ws = f.app.workspace

        val project = ws.select(w.projectId, WorkspaceSelection.project(w.projectId))
        assertEquals(WorkspaceObjectKind.PROJECT, project.kind)
        assertEquals(w.novelId, project.novelId, "Project 选择规范化出 novelId")

        val novel = ws.select(w.projectId, WorkspaceSelection.novel(w.projectId, w.novelId))
        assertEquals(w.novelId, novel.novelId)

        val chapter = ws.select(w.projectId, WorkspaceSelection.chapter(w.projectId, w.novelId, w.chapter1))
        assertEquals(w.chapter1, chapter.chapterId)
        assertEquals(w.novelId, chapter.novelId)

        val draft = ws.select(
            w.projectId,
            WorkspaceSelection.draft(w.projectId, w.novelId, w.chapter1, w.draft1),
        )
        assertEquals(w.draft1, draft.draftId)
        assertEquals(w.chapter1, draft.chapterId, "指导出 Draft 所属章节（真实字段，不猜测）")

        val character = ws.select(w.projectId, WorkspaceSelection.character(w.projectId, w.novelId, w.characterId))
        assertEquals(w.characterId, character.characterId)

        val worldRule = ws.select(
            w.projectId,
            WorkspaceSelection(w.projectId, WorkspaceObjectKind.WORLD_RULE, w.novelId, worldRuleId = w.worldRuleId),
        )
        assertEquals(w.worldRuleId, worldRule.worldRuleId)

        val foreshadow = ws.select(
            w.projectId,
            WorkspaceSelection(w.projectId, WorkspaceObjectKind.FORESHADOW, w.novelId, foreshadowId = w.foreshadowId),
        )
        assertEquals(w.foreshadowId, foreshadow.foreshadowId)

        val foundation = ws.select(
            w.projectId,
            WorkspaceSelection(w.projectId, WorkspaceObjectKind.STORY_FOUNDATION, w.novelId),
        )
        assertEquals(WorkspaceObjectKind.STORY_FOUNDATION, foundation.kind)

        val vocabulary = ws.select(
            w.projectId,
            WorkspaceSelection(
                w.projectId, WorkspaceObjectKind.VOCABULARY, w.novelId,
                vocabularyCandidateId = w.vocabularyCandidateId,
            ),
        )
        assertEquals(w.vocabularyCandidateId, vocabulary.vocabularyCandidateId)

        val task = ws.select(w.projectId, WorkspaceSelection.task(w.projectId, w.taskId))
        assertEquals(w.taskId, task.taskId)

        val change = ws.select(w.projectId, WorkspaceSelection.change(w.projectId, w.artifactId))
        assertEquals(w.artifactId, change.changeArtifactId)

        f.close()
    }

    @Test
    fun `missing object fails with a stable workspace code`() {
        val f = workspaceFixture()
        val w = seedWorkspaceWorld(f)
        val ws = f.app.workspace

        val missingChapter = assertFailsWith<ApplicationException> {
            ws.select(w.projectId, WorkspaceSelection.chapter(w.projectId, w.novelId, ChapterId("c-missing")))
        }
        assertTrue(missingChapter.message!!.contains(WorkspaceErrorCodes.OBJECT_NOT_FOUND), missingChapter.message!!)

        val missingTask = assertFailsWith<ApplicationException> {
            ws.select(w.projectId, WorkspaceSelection.task(w.projectId, TaskId("t-missing")))
        }
        assertTrue(missingTask.message!!.contains(WorkspaceErrorCodes.OBJECT_NOT_FOUND), missingTask.message!!)

        // 未提交过的历史不存在 → 明确错误（不伪造）
        val missingHistory = assertFailsWith<ApplicationException> {
            ws.select(
                w.projectId,
                WorkspaceSelection.history(w.projectId, com.qianyan.model.commit.CommitHistoryId("h-missing")),
            )
        }
        assertTrue(missingHistory.message!!.contains(WorkspaceErrorCodes.OBJECT_NOT_FOUND), missingHistory.message!!)

        // 该 Project 尚无该 Artifact
        val missingChange = assertFailsWith<ApplicationException> {
            ws.select(w.projectId, WorkspaceSelection.change(w.projectId, ChangeArtifactId("a-missing")))
        }
        assertTrue(missingChange.message!!.contains(WorkspaceErrorCodes.OBJECT_NOT_FOUND), missingChange.message!!)
        f.close()
    }

    @Test
    fun `incomplete selection fails with a stable code`() {
        val f = workspaceFixture()
        val w = seedWorkspaceWorld(f)

        val incomplete = assertFailsWith<ApplicationException> {
            f.app.workspace.select(w.projectId, WorkspaceSelection(w.projectId, WorkspaceObjectKind.CHAPTER))
        }
        assertTrue(incomplete.message!!.contains(WorkspaceErrorCodes.SELECTION_INCOMPLETE), incomplete.message!!)
        f.close()
    }

    @Test
    fun `cross project selection is rejected`() {
        val f = workspaceFixture()
        val w = seedWorkspaceWorld(f)
        val other = f.app.projects.createProject(title = "另一本书")
        val otherChapter = f.app.chapters.createNextChapter("别卷", other.novelId).chapterId

        // Project B 的章节在 Project A 下不可选（越界 = 不存在，不泄漏存在性）
        val crossChapter = assertFailsWith<ApplicationException> {
            f.app.workspace.select(w.projectId, WorkspaceSelection.chapter(w.projectId, w.novelId, otherChapter))
        }
        assertTrue(crossChapter.message!!.contains(WorkspaceErrorCodes.OBJECT_NOT_FOUND), crossChapter.message!!)

        // 选择自身声明的 Project 与请求的 Project 不一致 → PROJECT_NOT_FOUND
        val foreign = assertFailsWith<ApplicationException> {
            f.app.workspace.select(w.projectId, WorkspaceSelection.project(other.projectId))
        }
        assertTrue(foreign.message!!.contains(WorkspaceErrorCodes.PROJECT_NOT_FOUND), foreign.message!!)

        val unknown = assertFailsWith<ApplicationException> {
            f.app.workspace.select(com.qianyan.model.ProjectId("p-missing"), WorkspaceSelection.project(com.qianyan.model.ProjectId("p-missing")))
        }
        assertTrue(unknown.message!!.contains(WorkspaceErrorCodes.PROJECT_NOT_FOUND), unknown.message!!)
        f.close()
    }

    @Test
    fun `candidates cover every object with a real source`() {
        val f = workspaceFixture()
        val w = seedWorkspaceWorld(f)

        val candidates = f.app.workspace.candidates(w.projectId)

        assertTrue(candidates.all { it.selection.projectId == w.projectId }, "候选必须全部属于本 Project")
        val details = candidates.map { it.detail }.toSet()
        listOf("项目", "章节", "正文", "故事基础", "词汇", "人物", "世界规则", "伏笔", "任务", "修改")
            .forEach { assertTrue(it in details, "候选缺少 '$it'：$details") }
        assertTrue(
            candidates.none { it.detail == "时间线" },
            "没有稳定来源的对象不产生伪造候选（TimelineEntry 未播种 ⇒ 无候选）",
        )
        assertTrue(
            candidates.any { it.selection.chapterId == w.chapter1 && it.label.contains("第 1 章") },
            "章节候选带稳定位次标签",
        )
        f.close()
    }

    @Test
    fun `candidates for an empty project only contain the project itself`() {
        val f = workspaceFixture()
        val bare = f.app.projects.createProject(title = "空书")

        val candidates = f.app.workspace.candidates(bare.projectId)

        assertEquals(1, candidates.size)
        assertEquals("项目", candidates.single().detail)
        assertTrue(NovelId(bare.novelId.value) == candidates.single().selection.novelId)
        f.close()
    }
}