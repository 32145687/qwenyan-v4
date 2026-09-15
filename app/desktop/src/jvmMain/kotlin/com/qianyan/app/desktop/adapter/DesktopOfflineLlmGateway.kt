package com.qianyan.app.desktop.adapter

import com.qianyan.provider.ChatMessage
import com.qianyan.provider.ChatRole
import com.qianyan.provider.FinishReason
import com.qianyan.provider.LLMGateway
import com.qianyan.provider.ProviderRequest
import com.qianyan.provider.ProviderResponse
import com.qianyan.provider.Usage

/**
 * 桌面离线 Mock 网关（Desktop Adapter）。
 *
 * 背景：qwenyan-v4 自带的 `MockLLMGateway` 默认响应只覆盖 P6 的词汇分析（返回 vocabulary JSON），
 * 而创作链路的四个 Agent 期望各自的结构（Plan / Draft / Critique / KnowledgeUpdate）——
 * 因此用默认 Mock 走到 PLANNING 阶段会得到 `InvalidPlanningOutput`。这属于上游
 * 「Mock 输出仅覆盖 Analysis」的既有边界，不是 PC 侧缺陷，也不应通过改共享核心解决。
 *
 * 本类用官方 `MockLLMGateway(responseFor = ...)` 的注入 seam 在 **PC 侧**补齐确定性响应，
 * 使桌面端离线也能走通 Planning → Writing → Critique → KnowledgeUpdate 全链路。
 *
 * 协议要点（对齐 AgentRuntime 实现）：
 *  - system prompt 形如：`你是 <AgentName>。请严格按以下 JSON 协议单步作答：…`
 *    → 以 AgentName 作为可靠分派依据；
 *  - AgentRuntime 要求 LLM 返回 `{"answer":"<正文>"}`，answer 再交各 Parser 解析
 *    → 本类把内层 JSON 作为字符串放进 answer（转义后包裹）。
 *
 * 明确边界：
 *  - 生成内容是**示意稿**（deterministic placeholder），不是真实 AI 创作；
 *  - 结构 ID / 持久化 / Workflow / 人工门 / Checkpoint 全部走真实实现；
 *  - 配置真实 Provider（DeepSeek / MiMo）后本类不参与，走真实网关。
 */
class DesktopOfflineLlmGateway : LLMGateway {

    override fun chat(request: ProviderRequest): ProviderResponse {
        val prompt = request.messages.joinToString("\n") { it.content }
        val text = when {
            prompt.contains("StoryPlannerAgent") -> wrap(PLAN_JSON)
            prompt.contains("StoryCriticAgent") -> wrap(CRITIQUE_JSON)
            prompt.contains("KnowledgeUpdateAgent") -> wrap(KNOWLEDGE_UPDATE_JSON)
            prompt.contains("StoryRevisionAgent") -> wrap(DRAFT_JSON)
            prompt.contains("StoryWriterAgent") -> wrap(DRAFT_JSON)
            // 其余（含 Analysis：p6 词汇分析）保持与自带 MockLLMGateway 一致的行为
            else -> VOCABULARY_JSON
        }
        val promptTokens = request.messages.sumOf { it.content.length } / 4 + request.messages.size
        return ProviderResponse(
            message = ChatMessage(ChatRole.ASSISTANT, text),
            usage = Usage(promptTokens = promptTokens, completionTokens = text.length / 4, totalTokens = promptTokens + text.length / 4),
            finishReason = FinishReason.STOP,
        )
    }

    /** 把内层 JSON 作为字符串放入 AgentRuntime 要求的 `{"answer": ...}` 外壳。 */
    private fun wrap(inner: String): String {
        val escaped = inner
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
        return "{\"answer\":\"$escaped\"}"
    }

    companion object {
        /** Planner 期望：ChapterPlanParser.PlanDto（chapterGoal 必填）。 */
        private const val PLAN_JSON: String =
            "{\"chapterGoal\":\"让主角在北境第一次被重新命名，并给「灯」一次轻推\"," +
                "\"mainConflict\":\"同行者的隐瞒 vs 主角的信任\"," +
                "\"characterGoals\":{\"主角\":\"确认自己为何而来\"}," +
                "\"expectedEvents\":[\"出关\",\"黑水驿的旗换了\",\"城楼灯亮\"]," +
                "\"emotionalDirection\":\"克制 · 渐紧\"," +
                "\"endingHook\":\"没有人注意到，城楼上有一盏灯比昨夜亮了一点。\"," +
                "\"constraints\":[\"不引入新主要人物\"],\"forbiddenEvents\":[]}"

        /** Writer 期望：DraftParser.DraftDto（content 必填）。 */
        private const val DRAFT_JSON: String =
            "{\"content\":\"雪落进雁回关的时候，他想起师父说过的一句话：北境的风，会把人的名字吹散。\\n\\n" +
                "那时他不信。山上的雪是安静的，落在檐角，落在经幡，落在师父的肩头。而关外的雪是横着的，带着刀刃一样的风声。\\n\\n" +
                "「出关三十里就是黑水驿。」同行的人勒住马，「过了黑水驿，就没有人再叫你公子了。」\\n\\n" +
                "他没有立刻答话。他伸手接住一片雪，看它在掌心化成水——师父的遗言里，也有这样一句话的形状。\\n\\n" +
                "「那就让他们重新认识我。」他说。\\n\\n" +
                "马队出关，铃铛声在风里断断续续。没有人注意到，城楼上有一盏灯，比昨夜亮了一点。\"}"

        /** Critic 期望：CritiqueParser.CritiqueDto（passed 必填）。 */
        private const val CRITIQUE_JSON: String = "{\"passed\":true,\"issues\":[]}"

        /** KnowledgeUpdater 期望：KnowledgeUpdateParser.KnowledgeUpdateDto（changes 必填）。 */
        private const val KNOWLEDGE_UPDATE_JSON: String = "{\"changes\":[]}"

        /** Analysis（P6 词汇）—与自带 MockLLMGateway 的 DEFAULT_VOCABULARY_JSON 等价。 */
        private const val VOCABULARY_JSON: String =
            "{\"vocabulary\":[{\"canonical\":\"灵石\",\"type\":\"WORLD_TERM\",\"aliases\":[]}," +
                "{\"canonical\":\"丹田\",\"type\":\"REALM\",\"aliases\":[\"气海\"]}]}"
    }
}
