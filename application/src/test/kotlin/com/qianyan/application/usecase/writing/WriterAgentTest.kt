package com.qianyan.application.usecase.writing

import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.application.error.ErrorMapper
import com.qianyan.application.usecase.writing.planning.PlanningContext
import com.qianyan.model.ActId
import com.qianyan.model.ArcId
import com.qianyan.model.BaseNovelId
import com.qianyan.model.IntentType
import com.qianyan.model.NovelId
import com.qianyan.model.PlanningScope
import com.qianyan.model.RequestId
import com.qianyan.model.VariantScope
import com.qianyan.model.context.TargetKind
import com.qianyan.model.context.TargetRef
import com.qianyan.model.context.UserWritingRequest
import com.qianyan.model.story.ChapterPlan
import com.qianyan.provider.ChatMessage
import com.qianyan.provider.ChatRole
import com.qianyan.provider.FinishReason
import com.qianyan.provider.ModelProfile
import com.qianyan.provider.ProviderException
import com.qianyan.provider.ProviderRequest
import com.qianyan.provider.ProviderResponse
import com.qianyan.provider.Usage
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/**
 * P11.3 Writer Agent 单元测试。
 *
 * 用注入的 Mock LLM（以 `{"answer":"<正文>"}` 协议返回受控输出）验证 Writer：
 *  - 合法正文 → Draft（结构 ID 来自 [ChapterPlan]，正文来自 LLM）；
 *  - 非法 JSON / 缺 content / 类型错误 / 空输出 → 类型化 [ApplicationError.InvalidWritingOutput]；
 *  - Provider / Agent 失败 → 类型化错误（ProviderUnavailable / WritingFailed）。
 * 全程无网络，不触碰 Storage / Provider 具体实现。
 */
class WriterAgentTest {

    private fun gateway(content: String): MockGateway = MockGateway { ProviderResponse(
        message = ChatMessage(ChatRole.ASSISTANT, content),
        usage = Usage(10, 10, 20),
        finishReason = FinishReason.STOP,
    ) }

    private fun failingGateway(e: ProviderException): MockGateway = MockGateway { throw e }

    private fun writer(gateway: MockGateway) = WriterAgent(gateway, ErrorMapper, ModelProfile.MOCK)

    /** 把 DraftDto 正文按真实协议 `{"answer":"<json 字符串>"}` 封装后交给 Writer。 */
    private fun writerWithAnswer(draftJson: String): WriterAgent =
        writer(gateway(buildJsonObject { put("answer", draftJson) }.toString()))

    private fun context(): PlanningContext {
        val request = UserWritingRequest(
            requestId = RequestId("req-w"),
            intentType = IntentType.CONTINUE,
            target = TargetRef(TargetKind.CHAPTER, null),
            planningScope = PlanningScope.CHAPTER,
            baseNovelId = BaseNovelId("novel-1"),
        )
        return PlanningContext(
            request = request,
            novelId = NovelId("novel-1"),
            scope = VariantScope.ORIGINAL,
            novelTitle = "测试小说",
            novelGenre = listOf("玄幻"),
            memories = listOf("主角已突破筑基期"),
            vocabulary = listOf(com.qianyan.application.usecase.writing.planning.VocabularyLite("灵石", listOf("灵晶"), "星石")),
        )
    }

    private fun plan(): ChapterPlan = ChapterPlan(
        chapterPlanId = com.qianyan.model.ChapterPlanId("plan-1"),
        chapterId = com.qianyan.model.ChapterId("ch-1"),
        arcId = ArcId("arc-1"),
        actId = ActId("act-1"),
        novelId = NovelId("novel-1"),
        scope = VariantScope.ORIGINAL,
        chapterGoal = "救下女主",
        expectedEvents = listOf("发现古碑"),
        emotionalDirection = "紧张",
        endingHook = "门被敲响",
        constraints = listOf("不剧透"),
        forbiddenEvents = listOf("主角死亡"),
    )

    /* 合法正文：Mock LLM 返回有效 Draft JSON → 结构 ID 来自 ChapterPlan，正文来自 LLM */
    @Test
    fun `valid draft output maps to draft`() {
        val draft = writerWithAnswer("{\"content\":\"女主转身推开大门，雷劫从天而降。\"}").write(context(), plan())

        assertEquals("novel-1", draft.novelId.value)
        assertEquals("plan-1", draft.planId?.value)
        assertEquals("ch-1", draft.chapterId?.value)
        assertEquals(VariantScope.ORIGINAL, draft.scope)
        assertEquals("女主转身推开大门，雷劫从天而降。", draft.content)
        assertEquals(com.qianyan.model.writing.DraftStatus.WRITTEN, draft.status)
        assertEquals(ModelProfile.MOCK.id, draft.sourceModel)
    }

    /* 非法 JSON → InvalidWritingOutput */
    @Test
    fun `illegal json output fails with typed error`() {
        val ex = assertFailsWith<ApplicationException> {
            writerWithAnswer("这不是正文 JSON").write(context(), plan())
        }
        assertIs<ApplicationError.InvalidWritingOutput>(ex.error)
    }

    /* 缺 content：JSON 合法但无 content → InvalidWritingOutput */
    @Test
    fun `missing content fails with typed error`() {
        val ex = assertFailsWith<ApplicationException> {
            writerWithAnswer("{\"other\":1}").write(context(), plan())
        }
        assertIs<ApplicationError.InvalidWritingOutput>(ex.error)
    }

    /* content 类型错误 → InvalidWritingOutput */
    @Test
    fun `wrong content type fails with typed error`() {
        val ex = assertFailsWith<ApplicationException> {
            writerWithAnswer("{\"content\":42}").write(context(), plan())
        }
        assertIs<ApplicationError.InvalidWritingOutput>(ex.error)
    }

    /* 空 answer / 非 Final 协议 → InvalidWritingOutput */
    @Test
    fun `empty or non final answer fails with typed error`() {
        val ex = assertFailsWith<ApplicationException> {
            writerWithAnswer("").write(context(), plan())
        }
        assertIs<ApplicationError.InvalidWritingOutput>(ex.error)

        val noAnswer = assertFailsWith<ApplicationException> {
            writer(gateway("{\"vocabulary\":[]}")).write(context(), plan())
        }
        assertIs<ApplicationError.InvalidWritingOutput>(noAnswer.error)
    }

    /* Provider 故障（超时）→ 类型化 ProviderUnavailable，不泄漏底层 */
    @Test
    fun `provider failure fails with typed error`() {
        val ex = assertFailsWith<ApplicationException> {
            writer(failingGateway(ProviderException.Timeout("connect timeout"))).write(context(), plan())
        }
        assertIs<ApplicationError.ProviderUnavailable>(ex.error)
    }

    /* 正文为空串 → InvalidWritingOutput（拒绝"成功但无正文"） */
    @Test
    fun `blank content fails with typed error`() {
        val ex = assertFailsWith<ApplicationException> {
            writerWithAnswer("{\"content\":\"\"}").write(context(), plan())
        }
        assertIs<ApplicationError.InvalidWritingOutput>(ex.error)
    }

    /** 注入的 fake LLM：对每次请求返回固定正文 / 抛 Provider 异常。 */
    private class MockGateway(private val handler: (ProviderRequest) -> ProviderResponse) : com.qianyan.provider.LLMGateway {
        override fun chat(request: ProviderRequest): ProviderResponse = handler(request)
    }
}