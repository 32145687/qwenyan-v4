package com.qianyan.application.usecase.writing

import com.qianyan.application.di.ApplicationContainer
import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.model.ActId
import com.qianyan.model.ArcId
import com.qianyan.model.BaseNovelId
import com.qianyan.model.ChapterId
import com.qianyan.model.ChapterPlanId
import com.qianyan.model.IntentType
import com.qianyan.model.NovelId
import com.qianyan.model.PlanningScope
import com.qianyan.model.RequestId
import com.qianyan.model.TaskId
import com.qianyan.model.VariantScope
import com.qianyan.model.context.TargetKind
import com.qianyan.model.context.TargetRef
import com.qianyan.model.context.UserWritingRequest
import com.qianyan.model.story.ChapterPlan
import com.qianyan.model.task.TaskStatus
import com.qianyan.model.task.TaskType
import com.qianyan.model.workingdraft.WorkingDraftStatus
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
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * P11.3 Writing Task 生命周期测试（经写作生产入口 `executeIntoWorkingDraft`）。
 *
 * 验证：
 *  - PENDING → RUNNING → Writer(AgentRuntime → Mock LLM) → WorkingDraft
 *      → WRITING Checkpoint（工作引用，非 draftId）→ COMPLETED；
 *  - 写作失败（非法输出）→ FAILED，类型化 [ApplicationError.InvalidWritingOutput]；
 *  - 非 WRITING Task → InvalidOperation；Task 不存在 → TaskNotFound；
 *  - AI 写作**不写** ChapterDraft（Phase 1 直写边界）。
 * 全程 Mock LLM，无网络。
 */
class WritingExecutionTest {

    private fun validDraftJson() = "{\"content\":\"女主推开古碑，雷劫降临。\"}"

    /** LLM 返回 `{"answer":"<DraftDto 正文>"}`，即 AgentRuntime Final 协议承载的 Writer 原文。 */
    private fun container(draftJson: String = validDraftJson(), throwing: Boolean = false): ApplicationContainer {
        val content = buildJsonObject { put("answer", draftJson) }.toString()
        val gateway = MockLLMGateway { _ ->
            if (throwing) throw com.qianyan.provider.ProviderException.Timeout("mock timeout")
            ProviderResponse(
                message = ChatMessage(ChatRole.ASSISTANT, content),
                usage = Usage(10, 10, 20),
                finishReason = FinishReason.STOP,
            )
        }
        return ApplicationContainer.open(analysisGateway = gateway)
    }

    private fun request(novelId: NovelId) = UserWritingRequest(
        requestId = RequestId("req-write"),
        intentType = IntentType.CONTINUE,
        target = TargetRef(TargetKind.CHAPTER, null),
        planningScope = PlanningScope.CHAPTER,
        baseNovelId = BaseNovelId(novelId.value),
    )

    private fun plan(app: ApplicationContainer, novelId: NovelId): ChapterPlan {
        val chapterId = app.chapters.createNextChapter("第一章", novelId).chapterId
        return ChapterPlan(
            chapterPlanId = ChapterPlanId("plan-1"),
            chapterId = chapterId,
            arcId = ArcId("arc-1"),
            actId = ActId("act-1"),
            novelId = novelId,
            scope = VariantScope.ORIGINAL,
            chapterGoal = "救下女主并揭开秘辛",
            expectedEvents = listOf("发现古碑"),
            endingHook = "门被敲响",
        )
    }

    /* 成功：PENDING → RUNNING → WorkingDraft → WRITING Checkpoint → COMPLETED，工作引用可恢复 */
    @Test
    fun `writing task completes and persists working draft with checkpoint`() {
        val app = container()
        val novelId = app.novels.createOriginal(title = "测试仙侠")
        val id = app.tasks.create(TaskType.WRITING)

        val working = app.writingExecution.executeIntoWorkingDraft(id, request(novelId), plan(app, novelId))

        assertEquals(TaskStatus.COMPLETED, app.tasks.findById(id).status)
        assertEquals(1f, app.tasks.findById(id).progress)
        assertEquals(1, app.tasks.findById(id).revisionCount)
        assertEquals(WorkingDraftStatus.WORKING, working.status)
        assertEquals("女主推开古碑，雷劫降临。", working.content)

        // AI 写作不落 ChapterDraft（Phase 1：AI 产物只进 WorkingDraft）
        assertEquals(0, app.draftRepository.listByNovel(novelId).size)

        // Checkpoint(WRITING) 保存类型化工作引用，可经 WorkingDraft 恢复
        val cp = app.tasks.restoreCheckpoint(id)
        assertEquals("WRITING", cp.stage)
        val restored = app.writingExecution.workingDraftFrom(cp)
        assertNotNull(restored)
        assertEquals(working.workingDraftId, restored.workingDraftId)
        assertEquals(working.content, restored.content)
    }

    /* 失败：非法输出 → InvalidWritingOutput → FAILED，失败原因记录、无可用工作产物 */
    @Test
    fun `writing failure moves task to failed with typed error`() {
        val app = container(draftJson = "{\"content\":123}")
        val novelId = app.novels.createOriginal(title = "测试")
        val id = app.tasks.create(TaskType.WRITING)

        val ex = assertFailsWith<ApplicationException> {
            app.writingExecution.executeIntoWorkingDraft(id, request(novelId), plan(app, novelId))
        }
        assertIs<ApplicationError.InvalidWritingOutput>(ex.error)

        val failed = app.tasks.findById(id)
        assertEquals(TaskStatus.FAILED, failed.status)
        assertNotNull(failed.error)
        assertTrue(failed.error!!.isNotBlank())
    }

    /* LLM/Provider 故障：超时 → ProviderUnavailable → FAILED（类型化，不伪装工作产物） */
    @Test
    fun `llm failure moves task to failed with typed error`() {
        val app = container(throwing = true)
        val novelId = app.novels.createOriginal(title = "测试")
        val id = app.tasks.create(TaskType.WRITING)

        val ex = assertFailsWith<ApplicationException> {
            app.writingExecution.executeIntoWorkingDraft(id, request(novelId), plan(app, novelId))
        }
        assertIs<ApplicationError.ProviderUnavailable>(ex.error)

        val failed = app.tasks.findById(id)
        assertEquals(TaskStatus.FAILED, failed.status)
        assertNotNull(failed.error)
    }

    /* 非 WRITING Task → InvalidOperation（类型化拒绝，不误执行） */
    @Test
    fun `non writing task rejected by writing entry`() {
        val app = container()
        val novelId = app.novels.createOriginal(title = "测试")
        val id = app.tasks.create(TaskType.PLANNING)
        val ex = assertFailsWith<ApplicationException> {
            app.writingExecution.executeIntoWorkingDraft(id, request(novelId), plan(app, novelId))
        }
        assertIs<ApplicationError.InvalidOperation>(ex.error)
        assertEquals(TaskStatus.PENDING, app.tasks.findById(id).status)
    }

    /* 缺失 Task → TaskNotFound */
    @Test
    fun `missing task throws TaskNotFound`() {
        val app = container()
        val ex = assertFailsWith<ApplicationException> {
            app.writingExecution.executeIntoWorkingDraft(TaskId("ghost"), request(NovelId("x")), unboundPlan(NovelId("x")))
        }
        assertIs<ApplicationError.TaskNotFound>(ex.error)
    }

    /* 失败路径不产生可解码工作产物（绝不伪造草稿） */
    @Test
    fun `failed writing leaves no decodable working snapshot`() {
        val app = container(draftJson = "不是 JSON")
        val novelId = app.novels.createOriginal(title = "测试")
        val id = app.tasks.create(TaskType.WRITING)

        assertFailsWith<ApplicationException> {
            app.writingExecution.executeIntoWorkingDraft(id, request(novelId), plan(app, novelId))
        }
        val cp = app.tasks.findCheckpoints(id).lastOrNull()
        if (cp != null) {
            assertEquals(null, app.writingExecution.workingDraftFrom(cp))
            assertEquals(0, app.draftRepository.listByNovel(novelId).size)
        }
    }

    private fun unboundPlan(novelId: NovelId) = ChapterPlan(
        chapterPlanId = ChapterPlanId("plan-ghost"),
        chapterId = ChapterId("ch-ghost"),
        arcId = ArcId("arc-1"),
        actId = ActId("act-1"),
        novelId = novelId,
        scope = VariantScope.ORIGINAL,
        chapterGoal = "目标",
    )
}
