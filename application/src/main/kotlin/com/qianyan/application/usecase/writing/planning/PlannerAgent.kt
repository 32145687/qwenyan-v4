package com.qianyan.application.usecase.writing.planning

import com.qianyan.agent.runtime.AgentException
import com.qianyan.agent.runtime.AgentRuntime
import com.qianyan.agent.tool.ToolException
import com.qianyan.agent.tool.ToolExecutor
import com.qianyan.agent.tool.ToolRegistry
import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.application.error.ErrorMapper
import com.qianyan.model.AgentId
import com.qianyan.model.agent.AgentContract
import com.qianyan.model.story.ChapterPlan
import com.qianyan.provider.LLMGateway
import com.qianyan.provider.ModelProfile
import com.qianyan.provider.ProviderException

/**
 * Planner Agent（P11.2）。
 *
 * 职责：把 [PlanningContext] 经 P10 的 [AgentRuntime] → [LLMGateway] 交给 Planner LLM，
 * 并把其最终输出解析为 [ChapterPlan]。
 *
 * 依赖约束（架构硬约束）：
 *  - 只依赖 :provider:api 的 [LLMGateway] 抽象，**禁止** DeepSeek/MiMo/HTTP/API Key/Storage；
 *  - 复用 P10 [AgentRuntime]，不重写 Agent loop；Agent 无工具（allowedTools 空），
 *    [ToolExecutor] 用空注册（Planning 阶段不需要业务 Tool，P11.3 再注册 writing:write 等）。
 *  - 输出解析经 [ChapterPlanParser]，非法/空/类型错误 → 类型化错误（不经 String.contains）。
 */
class PlannerAgent(
    private val gateway: LLMGateway,
    private val errorMapper: ErrorMapper,
    private val model: ModelProfile = ModelProfile.MOCK,
) {

    /** 执行了一次 Planner 运行（同步，无网络除非装配方注入真实 Provider）。 */
    fun plan(context: PlanningContext): ChapterPlan {
        val structure = ChapterPlanStructure(
            novelId = context.novelId,
            arcId = DEFAULT_ARC,
            actId = DEFAULT_ACT,
            variantId = context.variantId,
            scope = context.scope,
            chapterPlanId = java.util.UUID.randomUUID().toString(),
        )
        return try {
            val result = runtime.run(PLANNER_AGENT, renderInput(context))
            val raw = result.answer
                ?: throw PlanningException.InvalidOutput("planner returned no answer")
            ChapterPlanParser.parse(raw, structure)
        } catch (e: ApplicationException) {
            throw e
        } catch (e: PlanningException) {
            throw errorMapper.map(e)
        } catch (e: ProviderException) {
            throw when (e) {
                is ProviderException.InvalidResponse,
                is ProviderException.MalformedOutput,
                -> ApplicationException(ApplicationError.InvalidPlanningOutput(e.message ?: "planner output malformed"))
                else -> errorMapper.map(e)
            }
        } catch (e: AgentException) {
            throw ApplicationException(ApplicationError.PlanningFailed(e.message ?: "agent runtime failed"))
        } catch (e: ToolException) {
            throw ApplicationException(ApplicationError.PlanningFailed(e.message ?: "tool failed"))
        }
    }

    /** 构造供 Planner LLM 使用的输入文本。 */
    private fun renderInput(context: PlanningContext): String = buildString {
        appendLine("【创作请求】")
        appendLine("intent: ${context.request.intentType}")
        appendLine("target: ${context.request.target.kind}${context.request.target.id?.let { "(${it.value})" } ?: ""}")
        appendLine("scope: ${context.request.planningScope}")
        if (context.request.rawText.isNotBlank()) appendLine("rawText: ${context.request.rawText}")
        if (context.request.constraints.isNotEmpty()) appendLine("constraints: ${context.request.constraints}")
        if (context.request.styleHints.isNotEmpty()) appendLine("styleHints: ${context.request.styleHints}")

        appendLine("【小说背景】${context.novelTitle}")
        if (context.novelGenre.isNotEmpty()) appendLine("genre: ${context.novelGenre}")
        if (context.novelSynopsis.isNotBlank()) appendLine("synopsis: ${context.novelSynopsis}")
        if (context.variantName.isNotBlank()) appendLine("variant: ${context.variantName}")
        if (context.variantDirective.isNotBlank()) appendLine("variantDirective: ${context.variantDirective}")

        if (context.characters.isNotEmpty()) {
            appendLine("【相关人物】")
            context.characters.forEach { c ->
                appendLine("- ${c.name}(${c.characterId.value}): ${c.goals}")
            }
        }
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
        val PLANNER_AGENT: AgentContract = AgentContract(
            agentId = AgentId("story-planner"),
            name = "StoryPlannerAgent",
            capabilities = listOf(),
            allowedTools = emptyList(),
        )
        val DEFAULT_ARC: com.qianyan.model.ArcId = com.qianyan.model.ArcId("default-arc")
        val DEFAULT_ACT: com.qianyan.model.ActId = com.qianyan.model.ActId("default-act")
    }
}