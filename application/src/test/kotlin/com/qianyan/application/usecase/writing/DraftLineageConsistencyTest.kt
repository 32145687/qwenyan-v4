package com.qianyan.application.usecase.writing

import com.qianyan.application.di.ApplicationContainer
import com.qianyan.model.ActId
import com.qianyan.model.ArcId
import com.qianyan.model.BaseNovelId
import com.qianyan.model.ChapterPlanId
import com.qianyan.model.IntentType
import com.qianyan.model.NovelId
import com.qianyan.model.PlanningScope
import com.qianyan.model.RequestId
import com.qianyan.model.task.TaskType
import com.qianyan.model.VariantScope
import com.qianyan.model.context.TargetKind
import com.qianyan.model.context.TargetRef
import com.qianyan.model.context.UserWritingRequest
import com.qianyan.model.story.ChapterPlan
import com.qianyan.model.writing.Draft
import com.qianyan.model.writing.DraftStatus
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
import kotlin.test.assertNull

/**
 * P12.0.1 P4/P5：Draft lineage 一致（revision 同章节/同 scope）+ Checkpoint 恢复不绕过 scope（reference-only 恢复）。
 */
class DraftLineageConsistencyTest {

    private fun gateway(): MockLLMGateway = MockLLMGateway { req ->
        val agent = req.messages.first { it.role == ChatRole.SYSTEM }.content
        val body = when {
            "StoryPlannerAgent" in agent -> """{"chapterGoal":"救下女主"}"""
            "StoryWriterAgent" in agent -> """{"content":"正文。"}"""
            "StoryCriticAgent" in agent -> """{"passed":true}"""
            "StoryRevisionAgent" in agent -> """{"content":"修订正文。"}"""
            else -> """{"content":"占位"}"""
        }
        ProviderResponse(
            message = ChatMessage(ChatRole.ASSISTANT, buildJsonObject { put("answer", body) }.toString()),
            usage = Usage(10, 10, 20),
            finishReason = FinishReason.STOP,
        )
    }

    private fun request(novelId: NovelId) = UserWritingRequest(
        requestId = RequestId("req-lc"),
        intentType = IntentType.CONTINUE,
        target = TargetRef(TargetKind.CHAPTER, null),
        planningScope = PlanningScope.CHAPTER,
        baseNovelId = BaseNovelId(novelId.value),
    )

    private fun plan(novelId: NovelId) = ChapterPlan(
        chapterPlanId = ChapterPlanId("plan-lc"),
        chapterId = null,
        arcId = ArcId("arc"),
        actId = ActId("act"),
        novelId = novelId,
        scope = VariantScope.ORIGINAL,
        chapterGoal = "救下女主",
    )

    @Test
    fun `revision draft keeps same scope and chapter lineage`() {
        val app = ApplicationContainer.open(analysisGateway = gateway())
        val novelId = app.novels.createOriginal(title = "T")
        val plan = app.taskRunner.executePlanning(app.tasks.create(TaskType.PLANNING), request(novelId))
        val writeTask = app.tasks.create(TaskType.WRITING)
        val a = app.taskRunner.executeWriting(writeTask, request(novelId), plan)
        assertNotNull(a.chapterId)

        val b = app.taskRunner.executeRevision(writeTask, a, com.qianyan.model.spec.ValidationResult(passed = true))
        // 修订稿：同一 novel/variant/chapter + previousDraftId 指向 a
        assertEquals(a.novelId, b.novelId)
        assertEquals(a.variantId, b.variantId)
        assertEquals(a.chapterId, b.chapterId)
        assertEquals(a.draftId, b.previousDraftId)
        assertEquals(DraftStatus.REVISED, b.status)

        // previousDraftId 属于同一 scope（可经 repo 追踪）
        val prev = app.draftRepository.getById(b.previousDraftId!!)
        assertNotNull(prev)
        assertEquals(a.novelId, prev.novelId)
        assertEquals(a.chapterId, prev.chapterId)
    }

    /* P5：Checkpoint 恢复经 draftId（reference-only），恢复的 Draft 属于原章节/作用域 */
    @Test
    fun `checkpoint recovery keeps draft identity and scope`() {
        val app = ApplicationContainer.open(analysisGateway = gateway())
        val novelId = app.novels.createOriginal(title = "T")
        val plan = app.taskRunner.executePlanning(app.tasks.create(TaskType.PLANNING), request(novelId))
        val writeTask = app.tasks.create(TaskType.WRITING)
        val a = app.taskRunner.executeWriting(writeTask, request(novelId), plan)

        val cp = app.tasks.restoreCheckpoint(writeTask)
        // reference-only：snapshot 不含正文
        assertEquals(false, cp.snapshot.toString().contains(a.content))
        val restored = app.writingExecution.draftFrom(cp)
        assertNotNull(restored)
        // 恢复的 Draft 与原 Draft 身份/作用域一致（不绕过 scope）
        assertEquals(a.draftId, restored.draftId)
        assertEquals(a.novelId, restored.novelId)
        assertEquals(a.variantId, restored.variantId)
        assertEquals(a.chapterId, restored.chapterId)
        assertEquals(a.content, restored.content)
    }
}