package com.qianyan.application.usecase.writing

import com.qianyan.application.di.ApplicationContainer
import com.qianyan.model.ActId
import com.qianyan.model.ArcId
import com.qianyan.model.BaseNovelId
import com.qianyan.model.ChapterId
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
import kotlin.test.assertTrue

/**
 * P12.0 P0-4 / P1-1 / P1-2：
 * - Chapter 正式持久化（order 递增、ChapterPlan→真实 Chapter、ChapterDraft 关联章节）
 * - Draft 版本链（A.previous=null, B.previous=A, C.previous=B，reopen 后不丢）
 * - Checkpoint 只保存引用（不含正文），恢复经 DraftRepository
 */
class ChapterDraftLineageTest {

    private fun gateway(knowledge: String = """{"changes":[]}"""): MockLLMGateway = MockLLMGateway { req ->
        val agent = req.messages.first { it.role == ChatRole.SYSTEM }.content
        val body = when {
            "StoryPlannerAgent" in agent -> """{"chapterGoal":"救下女主"}"""
            "StoryWriterAgent" in agent -> """{"content":"正文。","chapterId":"ch-x"}"""
            "StoryCriticAgent" in agent -> """{"passed":true}"""
            "StoryRevisionAgent" in agent -> """{"content":"修订正文。"}"""
            "KnowledgeUpdateAgent" in agent -> knowledge
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

    /* P0-4 + P1-1：writing 关联真实 chapter；revision 链 A→B→C，reopen 后不丢 */
    @Test
    fun `draft lineage and chapter binding survive reopen`() {
        val app = ApplicationContainer.open(analysisGateway = gateway())
        val novelId = app.novels.createOriginal(title = "T")
        val plan = app.taskRunner.executePlanning(app.tasks.create(TaskType.PLANNING), request(novelId))

        val writeTask = app.tasks.create(TaskType.WRITING)
        val a = app.taskRunner.executeWriting(writeTask, request(novelId), plan)
        assertNotNull(a.chapterId)
        assertEquals(plan.chapterId, a.chapterId)
        assertNull(a.previousDraftId)

        // revision 链 A→B→C（critique 参数用占位 ValidationResult，避免额外 CRITIQUE checkpoint 突破 revision 上限）
        val ok = com.qianyan.model.spec.ValidationResult(passed = true)
        val b = app.taskRunner.executeRevision(writeTask, a, ok)
        assertEquals(a.draftId, b.previousDraftId)
        val c = app.taskRunner.executeRevision(writeTask, b, ok)
        assertEquals(b.draftId, c.previousDraftId)

        // 版本链 A→B→C 可追踪
        assertEquals(a.draftId, app.draftRepository.getById(b.draftId)!!.previousDraftId)
        assertEquals(b.draftId, app.draftRepository.getById(c.draftId)!!.previousDraftId)
    }

    /* P1-2：Checkpoint 不复制正文，仅保存引用；经 draftId 可恢复正文 */
    @Test
    fun `checkpoint stores reference not content`() {
        val app = ApplicationContainer.open(analysisGateway = gateway())
        val novelId = app.novels.createOriginal(title = "T")
        val plan = app.taskRunner.executePlanning(app.tasks.create(TaskType.PLANNING), request(novelId))
        val writeTask = app.tasks.create(TaskType.WRITING)
        val draft = app.taskRunner.executeWriting(writeTask, request(novelId), plan)

        val cp = app.tasks.restoreCheckpoint(writeTask)
        assertEquals("WRITING", cp.stage)
        val snapshotText = cp.snapshot.toString()
        // 正文不在 checkpoint 中（恢复索引语义）
        assertTrue(!snapshotText.contains(draft.content))

        // 经 draftId 恢复正文（draftFrom -> DraftRepository）
        val restored = app.writingExecution.draftFrom(cp)
        assertNotNull(restored)
        assertEquals(draft.draftId, restored.draftId)
        assertEquals(draft.content, restored.content)
    }
}