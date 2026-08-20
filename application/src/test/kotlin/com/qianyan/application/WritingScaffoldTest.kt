package com.qianyan.application

import com.qianyan.application.di.ApplicationContainer
import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.model.ArcId
import com.qianyan.model.ActId
import com.qianyan.model.ChapterPlanId
import com.qianyan.model.DraftId
import com.qianyan.model.IntentType
import com.qianyan.model.NovelId
import com.qianyan.model.PlanningScope
import com.qianyan.model.RequestId
import com.qianyan.model.VariantId
import com.qianyan.model.context.TargetKind
import com.qianyan.model.context.TargetRef
import com.qianyan.model.context.UserWritingRequest
import com.qianyan.model.spec.ValidationResult
import com.qianyan.model.story.ChapterPlan
import com.qianyan.model.writing.Draft
import com.qianyan.provider.impl.MockLLMGateway
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/**
 * P11.1 Scaffold 验证：
 *  - 写作 Use Case 可从容器正确装配；
 *  - plan/write/critique/revise 为诚实骨架：调入抛 [ApplicationError.WritingScaffoldNotImplemented]，
 *    不返回假数据、不伪装真实创作能力；
 *  - 后处理 seam 默认直通，本阶段即真实生效，且不特判模型。
 */
class WritingScaffoldTest {

    private fun app(): ApplicationContainer = ApplicationContainer.open(analysisGateway = MockLLMGateway())

    private val now = Instant.fromEpochMilliseconds(1_700_000_000_000L)

    private fun request(): UserWritingRequest = UserWritingRequest(
        requestId = RequestId("req-1"),
        intentType = IntentType.CONTINUE,
        target = TargetRef(kind = TargetKind.CHAPTER, id = null),
        planningScope = PlanningScope.CHAPTER,
        rawText = "续写下一章",
    )

    private fun plan(): ChapterPlan = ChapterPlan(
        chapterPlanId = ChapterPlanId("p1"),
        arcId = ArcId("arc-1"),
        actId = ActId("act-1"),
        novelId = NovelId("n1"),
    )

    private fun draft(): Draft = Draft(
        draftId = DraftId("d1"),
        novelId = NovelId("n1"),
        variantId = VariantId("v1"),
        content = "草稿正文",
        sourceModel = "deepseek-v4-flash",
        createdAt = now,
        updatedAt = now,
    )

    @Test
    fun `container assembles writing use case scaffold`() {
        // 可从完整装配的容器取到写作骨架（编译器即证明类型可闭包，行为测试见下）
        val writing = app().writing
        // 后处理 seam 默认直通时可从 Use Case 触达
        assertEquals(draft(), writing.postProcessDraft(draft()))
    }

    @Test
    fun `plan is an honest scaffold and throws typed not-implemented`() {
        val ex = assertFailsWith<ApplicationException> { app().writing.plan(request()) }
        assertIs<ApplicationError.WritingScaffoldNotImplemented>(ex.error)
    }

    @Test
    fun `write is an honest scaffold and throws typed not-implemented`() {
        val ex = assertFailsWith<ApplicationException> { app().writing.write(request(), plan()) }
        assertIs<ApplicationError.WritingScaffoldNotImplemented>(ex.error)
    }

    @Test
    fun `critique is an honest scaffold and throws typed not-implemented`() {
        val ex = assertFailsWith<ApplicationException> { app().writing.critique(draft()) }
        assertIs<ApplicationError.WritingScaffoldNotImplemented>(ex.error)
    }

    @Test
    fun `revise is an honest scaffold and throws typed not-implemented`() {
        val ex = assertFailsWith<ApplicationException> { app().writing.revise(draft(), ValidationResult(passed = false)) }
        assertIs<ApplicationError.WritingScaffoldNotImplemented>(ex.error)
    }

    @Test
    fun `post-processor seam is live and passthrough by default`() {
        val d = draft()
        // 默认直通：返回同一 Draft，不改写、不特判模型（MiMo 专用后处理属 P11.5）
        assertEquals(d, app().writing.postProcessDraft(d))
    }
}