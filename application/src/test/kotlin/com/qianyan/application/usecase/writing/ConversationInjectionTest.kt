package com.qianyan.application.usecase.writing

import com.qianyan.application.error.ErrorMapper
import com.qianyan.application.usecase.session.ConversationPromptSection
import com.qianyan.application.usecase.writing.planning.PlanningContext
import com.qianyan.application.usecase.writing.planning.PlannerAgent
import com.qianyan.model.ActId
import com.qianyan.model.ArcId
import com.qianyan.model.BaseNovelId
import com.qianyan.model.ChapterPlanId
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
import com.qianyan.provider.ProviderRequest
import com.qianyan.provider.ProviderResponse
import com.qianyan.provider.Usage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * DSH-02 · 对话上下文注入路径测试（Writer / Planner）。
 *
 * 用 RecordingGateway 捕获真实送入 LLM 的 USER prompt，断言：
 *  - 非 null conversation 只出现一次，且位于写作/规划正文段之前；
 *  - Planner 的 correction 仍位于输入最末尾；
 *  - 默认 null 时 prompt 不含对话段（既有行为不变）。
 */
class ConversationInjectionTest {

    private fun recordingGateway(answer: String): RecordingGateway =
        RecordingGateway { ProviderResponse(
            message = ChatMessage(ChatRole.ASSISTANT, "{\"answer\":${jsonString(answer)}}"),
            usage = Usage(10, 10, 20),
            finishReason = FinishReason.STOP,
        ) }

    private fun jsonString(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    private fun context(): PlanningContext {
        val request = UserWritingRequest(
            requestId = RequestId("req-ctx"),
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
        )
    }

    private fun plan(): ChapterPlan = ChapterPlan(
        chapterPlanId = ChapterPlanId("plan-1"),
        chapterId = com.qianyan.model.ChapterId("ch-1"),
        arcId = ArcId("arc-1"),
        actId = ActId("act-1"),
        novelId = NovelId("novel-1"),
        scope = VariantScope.ORIGINAL,
        chapterGoal = "救下女主",
    )

    private val section = ConversationPromptSection.TITLE + "\n[用户] 上一轮：主角在雨夜出发\n[千言] 已记录，继续推进"

    /* Writer：conversation 只出现一次，且位于【本章规划】之前 */
    @Test
    fun `writer injects conversation before chapter plan section`() {
        val gateway = recordingGateway("{\"content\":\"正文\"}")
        val writer = WriterAgent(gateway, ErrorMapper, ModelProfile.MOCK)

        writer.write(context(), plan(), emptyList(), conversation = section)

        val input = gateway.lastUserPrompt()
        assertTrue(input.contains(ConversationPromptSection.TITLE), "prompt 应包含对话段")
        assertEquals(1, input.split(ConversationPromptSection.TITLE).size - 1, "对话段只能出现一次")
        assertTrue(
            input.indexOf(ConversationPromptSection.TITLE) < input.indexOf("【本章规划】"),
            "对话段必须位于【本章规划】之前",
        )
        assertTrue(input.contains("[用户] 上一轮：主角在雨夜出发"))
    }

    /* Writer：默认 null → 不含对话段（既有行为不变） */
    @Test
    fun `writer without conversation keeps prompt unchanged`() {
        val gateway = recordingGateway("{\"content\":\"正文\"}")
        val writer = WriterAgent(gateway, ErrorMapper, ModelProfile.MOCK)

        writer.write(context(), plan(), emptyList())

        val input = gateway.lastUserPrompt()
        assertFalse(input.contains(ConversationPromptSection.TITLE))
    }

    /* Planner：conversation 位于【输出契约】前，correction 仍在最末尾 */
    @Test
    fun `planner injects conversation before output contract and keeps correction last`() {
        val gateway = recordingGateway("{\"chapterGoal\":\"救下女主\"}")
        val planner = PlannerAgent(gateway, ErrorMapper, ModelProfile.MOCK)

        planner.plan(context(), emptyList(), correction = "请重试", conversation = section)

        val input = gateway.lastUserPrompt()
        assertTrue(input.contains(ConversationPromptSection.TITLE), "prompt 应包含对话段")
        assertEquals(1, input.split(ConversationPromptSection.TITLE).size - 1, "对话段只能出现一次")
        assertTrue(
            input.indexOf(ConversationPromptSection.TITLE) < input.indexOf("【输出契约（必须遵守）】"),
            "对话段必须位于【输出契约】之前",
        )
        assertEquals("【修正】请重试", input.lines().last { it.isNotBlank() })
    }

    /* Planner：默认 null → 不含对话段 */
    @Test
    fun `planner without conversation keeps prompt unchanged`() {
        val gateway = recordingGateway("{\"chapterGoal\":\"救下女主\"}")
        val planner = PlannerAgent(gateway, ErrorMapper, ModelProfile.MOCK)

        planner.plan(context())

        assertFalse(gateway.lastUserPrompt().contains(ConversationPromptSection.TITLE))
    }

    /** 捕获每次请求的 USER prompt（AgentRuntime 以 SYSTEM + USER 两条消息发送）。 */
    private class RecordingGateway(private val handler: (ProviderRequest) -> ProviderResponse) :
        com.qianyan.provider.LLMGateway {
        private val prompts = mutableListOf<String>()

        override fun chat(request: ProviderRequest): ProviderResponse {
            prompts += request.messages.last { it.role == ChatRole.USER }.content
            return handler(request)
        }

        fun lastUserPrompt(): String = prompts.last()
    }
}
