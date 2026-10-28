package com.qianyan.application.usecase.writing

import com.qianyan.application.di.ApplicationContainer
import com.qianyan.model.BaseNovelId
import com.qianyan.model.IntentType
import com.qianyan.model.NovelId
import com.qianyan.model.PlanningScope
import com.qianyan.model.RequestId
import com.qianyan.model.VariantScope
import com.qianyan.model.context.TargetKind
import com.qianyan.model.context.TargetRef
import com.qianyan.model.context.UserWritingRequest
import com.qianyan.model.task.TaskType
import com.qianyan.provider.ChatMessage
import com.qianyan.provider.ChatRole
import com.qianyan.provider.FinishReason
import com.qianyan.provider.ProviderResponse
import com.qianyan.provider.Usage
import com.qianyan.provider.impl.MockLLMGateway
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * P12.0 P0-4 / P1-1 / P1-2（Phase 1 更新）：
 * - Chapter 正式持久化（order 递增、ChapterPlan→真实 Chapter 绑定）
 * - WRITING Checkpoint 只保存**类型化工作引用**（不含正文），恢复经 WorkingDraft
 *
 * 旧 `Draft` 版本链（A→B→C）属被删除的 AI 直写能力，不再在此验证。
 */
class ChapterDraftLineageTest {

    private fun gateway(): MockLLMGateway = MockLLMGateway { req ->
        val agent = req.messages.first { it.role == ChatRole.SYSTEM }.content
        val body = when {
            "StoryPlannerAgent" in agent -> """{"chapterGoal":"救下女主"}"""
            "StoryWriterAgent" in agent -> """{"content":"正文。"}"""
            else -> """{"content":"占位"}"""
        }
        ProviderResponse(
            message = ChatMessage(ChatRole.ASSISTANT, buildJsonObject { put("answer", body) }.toString()),
            usage = Usage(10, 10, 20),
            finishReason = FinishReason.STOP,
        )
    }

    private fun request(novelId: NovelId) = UserWritingRequest(
        requestId = RequestId("req-h"),
        intentType = IntentType.CONTINUE,
        target = TargetRef(TargetKind.CHAPTER, null),
        planningScope = PlanningScope.CHAPTER,
        baseNovelId = BaseNovelId(novelId.value),
        scope = VariantScope.ORIGINAL,
    )

    /* P0-4：连续两次 Planning → Chapter order 1,2；plan.chapterId 非空且真实存在 */
    @Test
    fun `planning creates persisted chapters with increasing order`() {
        val app = ApplicationContainer.open(analysisGateway = gateway())
        val novelId = app.novels.createOriginal(title = "T")
        val p1 = app.taskRunner.executePlanning(app.tasks.create(TaskType.PLANNING), request(novelId))
        val p2 = app.taskRunner.executePlanning(app.tasks.create(TaskType.PLANNING), request(novelId))

        assertNotNull(p1.chapterId)
        assertNotNull(p2.chapterId)
        assertTrue(p1.chapterId != p2.chapterId)
        val chapters = app.chapterRepository.listByNovel(novelId, null)
        assertEquals(listOf(1, 2), chapters.map { it.order })
        // Chapter 可经 id 读回
        assertNotNull(app.chapterRepository.findById(p2.chapterId!!))
    }

    /* P1-02c+d：Checkpoint 不复制正文，仅保存工作引用；经 WorkingDraft 恢复正文 */
    @Test
    fun `checkpoint stores working reference not content`() {
        val app = ApplicationContainer.open(analysisGateway = gateway())
        val novelId = app.novels.createOriginal(title = "T")
        val plan = app.taskRunner.executePlanning(app.tasks.create(TaskType.PLANNING), request(novelId))
        val writeTask = app.tasks.create(TaskType.WRITING)
        val working = app.writingExecution.executeIntoWorkingDraft(writeTask, request(novelId), plan)

        val cp = app.tasks.restoreCheckpoint(writeTask)
        assertEquals("WRITING", cp.stage)
        assertTrue(!cp.snapshot.toString().contains(working.content))

        val restored = app.writingExecution.workingDraftFrom(cp)
        assertNotNull(restored)
        assertEquals(working.workingDraftId, restored.workingDraftId)
        assertEquals(working.content, restored.content)
        // AI 写作不落 ChapterDraft
        assertEquals(0, app.draftRepository.listByNovel(novelId).size)
    }
}
