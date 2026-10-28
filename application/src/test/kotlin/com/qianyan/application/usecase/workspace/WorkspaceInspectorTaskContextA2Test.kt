package com.qianyan.application.usecase.workspace

import com.qianyan.application.usecase.agent.novelAgentFixture
import com.qianyan.application.usecase.agent.request
import com.qianyan.application.usecase.agent.seedNovelWorld
import com.qianyan.model.DraftId
import com.qianyan.model.novelagent.NovelAgentRequest
import com.qianyan.model.task.TaskType
import com.qianyan.model.context.ContextSourceKind
import com.qianyan.model.workspace.InspectorTaskContextState
import com.qianyan.model.workspace.WorkspaceSelection
import com.qianyan.model.writing.Draft
import com.qianyan.model.writing.DraftFormat
import com.qianyan.model.writing.DraftStatus
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A2 · Inspector 读取任务实际发布的 ContextPack。
 *
 * 证明：
 *  - Agent 任务结束后 Store 中保存的是同一 Pack；
 *  - Inspector 显示同一 packVersion/itemId，而不是重新构建；
 *  - 任务后修改 Repository 不改变已发布快照；
 *  - 无 Pack / 跨 Project / 非当前章节时不伪造 `PACK_AVAILABLE`。
 */
class WorkspaceInspectorTaskContextA2Test {

    private val fixed = Instant.parse("2026-01-01T00:00:00Z")

    @Test
    fun `agent task publishes the pack and inspector shows the same frozen snapshot`() {
        val f = novelAgentFixture()
        val w = seedNovelWorld(f)

        val result = f.app.novelAgent.run(request(w, "继续写第一章"))
        val sessionId = assertNotNull(result.sessionId)
        val stored = assertNotNull(f.app.contextPackStore.find(sessionId, w.projectId))
        assertEquals(result.contextPackId, stored.pack.packId)
        assertEquals(result.contextPackVersion, stored.pack.packVersion)

        val selection = WorkspaceSelection.chapter(w.projectId, w.novelId, w.chapterId)
        val view = f.app.workspace.inspector(w.projectId, selection, sessionId)
        val taskContext = assertNotNull(view.taskContext)
        assertEquals(InspectorTaskContextState.PACK_AVAILABLE, taskContext.state)
        assertEquals(sessionId, taskContext.sessionId)
        assertEquals(result.contextPackVersion, assertNotNull(view.context).packVersion)
        assertEquals(
            stored.pack.items.map { it.itemId }.toSet(),
            assertNotNull(view.context).groups.flatMap { it.entries }.map { it.itemId }.toSet(),
        )

        // 任务结束后修改 Canonical 正文：已发布 Pack 必须保持冻结。
        f.app.draftRepository.save(
            Draft(
                draftId = DraftId("draft-after-task"),
                novelId = w.novelId,
                chapterId = w.chapterId,
                content = "任务结束后修改的正文",
                format = DraftFormat.CONTROLLED_MARKDOWN,
                status = DraftStatus.REVISED,
                createdAt = fixed,
                updatedAt = fixed,
            ),
        )
        val after = f.app.workspace.inspector(w.projectId, selection, sessionId)
        assertEquals(view.context?.packVersion, after.context?.packVersion)
        val draftPreview = assertNotNull(after.context)
            .groups.first { it.source == ContextSourceKind.DRAFT }
            .entries.single()
            .preview
        assertTrue(draftPreview.contains("第一章旧正文"), "Inspector 必须显示任务时的快照，而不是最新 Repository 内容: $draftPreview")

        f.close()
    }

    @Test
    fun `task selection resolves its session and shows the actual pack`() {
        val f = novelAgentFixture()
        val w = seedNovelWorld(f)
        val taskId = f.app.tasks.create(TaskType.WRITING)

        val result = f.app.novelAgent.run(
            NovelAgentRequest(
                projectId = w.projectId,
                userIntent = "继续写第一章",
                taskId = taskId,
                activeChapterId = w.chapterId,
            ),
        )
        val sessionId = assertNotNull(result.sessionId)
        assertNotNull(f.app.contextPackStore.find(sessionId, w.projectId))

        val view = f.app.workspace.inspector(
            w.projectId,
            WorkspaceSelection.task(w.projectId, taskId, w.novelId),
        )
        val taskContext = assertNotNull(view.taskContext)
        assertEquals(InspectorTaskContextState.PACK_AVAILABLE, taskContext.state)
        assertEquals(taskId, taskContext.taskId)
        assertEquals(sessionId, taskContext.sessionId)
        assertEquals(result.contextPackVersion, assertNotNull(view.context).packVersion)

        f.close()
    }

    @Test
    fun `running task with a published pack shows the current pack`() {
        val f = novelAgentFixture()
        val w = seedNovelWorld(f)
        val taskId = f.app.tasks.create(TaskType.WRITING)
        val result = f.app.novelAgent.run(
            NovelAgentRequest(
                projectId = w.projectId,
                userIntent = "继续写第一章",
                taskId = taskId,
                activeChapterId = w.chapterId,
            ),
        )
        f.app.tasks.start(taskId)

        val view = f.app.workspace.inspector(
            w.projectId,
            WorkspaceSelection.task(w.projectId, taskId, w.novelId),
        )
        assertEquals(InspectorTaskContextState.PACK_AVAILABLE, assertNotNull(view.taskContext).state)
        assertEquals(result.contextPackVersion, assertNotNull(view.context).packVersion)

        f.close()
    }

    @Test
    fun `session without published pack shows unavailable and foreign session is not visible`() {
        val f = novelAgentFixture()
        val w = seedNovelWorld(f)
        val selection = WorkspaceSelection.chapter(w.projectId, w.novelId, w.chapterId)

        val sameProjectSession = f.app.agentSessions.startSession(w.novelId).sessionId
        val unavailable = f.app.workspace.inspector(w.projectId, selection, sameProjectSession)
        assertEquals(InspectorTaskContextState.PACK_UNAVAILABLE, assertNotNull(unavailable.taskContext).state)
        assertNull(unavailable.context)

        val otherNovel = f.app.novels.createOriginal(title = "另一本书")
        val otherSession = f.app.agentSessions.startSession(otherNovel).sessionId
        val foreign = f.app.workspace.inspector(w.projectId, selection, otherSession)
        assertEquals(InspectorTaskContextState.NO_TASK, assertNotNull(foreign.taskContext).state)
        assertNull(foreign.context)

        f.close()
    }

    @Test
    fun `pack from another chapter is not shown as the selected chapter context`() {
        val f = novelAgentFixture()
        val w = seedNovelWorld(f)
        val result = f.app.novelAgent.run(request(w, "继续写第一章"))
        val sessionId = assertNotNull(result.sessionId)

        val otherChapter = WorkspaceSelection.chapter(w.projectId, w.novelId, w.secondChapterId)
        val view = f.app.workspace.inspector(w.projectId, otherChapter, sessionId)

        assertEquals(InspectorTaskContextState.NO_TASK, assertNotNull(view.taskContext).state)
        assertNull(view.context)
        f.close()
    }
}
