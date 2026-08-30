package com.qianyan.application.usecase.writing

import com.qianyan.agent.runtime.AgentException
import com.qianyan.agent.runtime.AgentRuntime
import com.qianyan.agent.tool.ToolException
import com.qianyan.agent.tool.ToolExecutor
import com.qianyan.agent.tool.ToolRegistry
import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.application.error.ErrorMapper
import com.qianyan.application.usecase.writing.planning.PlanningContext
import com.qianyan.model.AgentId
import com.qianyan.model.agent.AgentContract
import com.qianyan.model.story.ChapterPlan
import com.qianyan.model.writing.Draft
import com.qianyan.provider.LLMGateway
import com.qianyan.provider.ModelProfile
import com.qianyan.provider.ProviderException
import kotlinx.datetime.Clock

/**
 * Writer Agent（P11.3）。
 *
 * 职责：把 [PlanningContext] + [ChapterPlan] 经 P10 的 [AgentRuntime] → [LLMGateway] 交给
 * Writer LLM，并把其最终输出解析为 [Draft]。
 *
 * 依赖约束（架构硬约束）：
 *  - 只依赖 :provider:api 的 [LLMGateway] 抽象，**禁止** DeepSeek/MiMo/HTTP/API Key/Storage/SQLite；
 *  - 复用 P10 [AgentRuntime]，不重写 Agent loop；P11.3 不要求 writing Tool，allowedTools 为空，
 *    [ToolExecutor] 用空注册（不新增 Tool）；
 *  - 输入复用 [PlanningContext]（不创建第二套 WritingContext），结构信息（novelId / chapterId /
 *    planId / variant）由 [ChapterPlan] 提供；
 *  - Writer 默认使用 Mock 模型（[ModelProfile.MOCK]），保持测试确定性；
 *  - 输出解析经 [DraftParser]，非法/空/缺 content/类型错误 → 类型化错误（不经 String.contains）。
 */
class WriterAgent(
    private val gateway: LLMGateway,
    private val errorMapper: ErrorMapper,
    private val model: ModelProfile = ModelProfile.MOCK,
) {

    /**
     * 执行一次 Writer 运行（同步，无网络除非装配方注入真实 Provider）。
     * 返回按 [ChapterPlan] 结构装配的 [Draft]；失败抛类型化 [ApplicationException]。
     */
    fun write(context: PlanningContext, plan: ChapterPlan): Draft {
        val structure = DraftStructure(
            draftId = java.util.UUID.randomUUID().toString(),
            novelId = plan.novelId,
            variantId = plan.variantId,
            scope = plan.scope,
            chapterId = plan.chapterId,
            planId = plan.chapterPlanId,
            sourceModel = model.id,
            now = Clock.System.now(),
        )
        return try {
            val result = runtime.run(WRITER_AGENT, renderInput(context, plan))
            val raw = result.answer
                ?: throw WritingException.InvalidOutput("writer returned no answer")
            DraftParser.parse(raw, structure)
        } catch (e: ApplicationException) {
            throw e
        } catch (e: WritingException) {
            throw errorMapper.map(e)
        } catch (e: ProviderException) {
            throw when (e) {
                is ProviderException.InvalidResponse,
                is ProviderException.MalformedOutput,
                -> ApplicationException(ApplicationError.InvalidWritingOutput(e.message ?: "writer output malformed"))
                else -> errorMapper.map(e)
            }
        } catch (e: AgentException) {
            throw ApplicationException(ApplicationError.WritingFailed(e.message ?: "agent runtime failed"))
        } catch (e: ToolException) {
            throw ApplicationException(ApplicationError.WritingFailed(e.message ?: "tool failed"))
        }
    }

    /** 构造供 Writer LLM 使用的输入文本（复用 PlanningContext 投影 + ChapterPlan 创作意图）。 */
    private fun renderInput(context: PlanningContext, plan: ChapterPlan): String = buildString {
        appendLine("【创作请求】")
        appendLine("intent: ${context.request.intentType}")
        appendLine("target: ${context.request.target.kind}${context.request.target.id?.let { "(${it.value})" } ?: ""}")
        if (context.request.rawText.isNotBlank()) appendLine("rawText: ${context.request.rawText}")
        if (context.request.styleHints.isNotEmpty()) appendLine("styleHints: ${context.request.styleHints}")

        appendLine("【小说背景】${context.novelTitle}")
        if (context.novelGenre.isNotEmpty()) appendLine("genre: ${context.novelGenre}")
        if (context.novelSynopsis.isNotBlank()) appendLine("synopsis: ${context.novelSynopsis}")
        if (context.variantName.isNotBlank()) appendLine("variant: ${context.variantName}")
        if (context.variantDirective.isNotBlank()) appendLine("variantDirective: ${context.variantDirective}")

        appendLine("【本章规划】")
        if (plan.chapterGoal.isNotBlank()) appendLine("chapterGoal: ${plan.chapterGoal}")
        if (plan.expectedEvents.isNotEmpty()) appendLine("expectedEvents: ${plan.expectedEvents}")
        if (plan.emotionalDirection.isNotBlank()) appendLine("emotionalDirection: ${plan.emotionalDirection}")
        if (plan.endingHook.isNotBlank()) appendLine("endingHook: ${plan.endingHook}")
        if (plan.constraints.isNotEmpty()) appendLine("constraints: ${plan.constraints}")
        if (plan.forbiddenEvents.isNotEmpty()) appendLine("forbiddenEvents: ${plan.forbiddenEvents}")

        if (context.memories.isNotEmpty()) {
            appendLine("【相关记忆】")
            context.memories.forEach { appendLine("- $it") }
        }
        if (context.vocabulary.isNotEmpty()) {
            appendLine("【词库】")
            context.vocabulary.forEach { v ->
                val repl = if (v.replacement != null) " -> ${v.replacement}" else ""
                appendLine("- ${v.canonical}$repl")
            }
        }
    }.trimEnd()

    private val runtime: AgentRuntime = AgentRuntime(
        gateway = gateway,
        toolExecutor = ToolExecutor(ToolRegistry()),
        model = model,
    )

    private companion object {
        val WRITER_AGENT: AgentContract = AgentContract(
            agentId = AgentId("story-writer"),
            name = "StoryWriterAgent",
            capabilities = listOf(),
            allowedTools = emptyList(),
        )
    }
}