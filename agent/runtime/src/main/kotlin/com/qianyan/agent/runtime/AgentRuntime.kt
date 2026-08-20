package com.qianyan.agent.runtime

import com.qianyan.agent.tool.ToolContext
import com.qianyan.agent.tool.ToolExecutor
import com.qianyan.agent.tool.ToolException
import com.qianyan.model.agent.AgentContract
import com.qianyan.model.agent.AgentState
import com.qianyan.model.tool.ToolRequest
import com.qianyan.model.tool.ToolResult
import com.qianyan.provider.ChatMessage
import com.qianyan.provider.ChatRole
import com.qianyan.provider.LLMGateway
import com.qianyan.provider.ModelProfile
import com.qianyan.provider.ProviderRequest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 同步 Agent Runtime（P10，架构 §20）。
 *
 * 最小执行循环（LLM → 判定 → Tool → ToolResult → LLM → … → Final）：
 * ```
 * IDLE
 *  ↓
 * RUNNING ──解析到 Tool──▶ (调用 Tool → ToolResult → 追加上下文 → RUNNING)
 *  ↓
 * COMPLETED  /  FAILED
 * ```
 *
 * 职责边界：
 *  - 只依赖 [LLMGateway]（:provider:api 契约），不接触 DeepSeek / MiMo / HTTP / API Key；
 *  - Tool 调用经 [ToolExecutor]（:agent:tool），不直连 Repository / SQLite / Android；
 *  - 同步执行，无 Coroutine / Flow / Worker；
 *  - 最大步数 [maxSteps] 保护，防止无限 Agent Loop（超限抛 [AgentException.MaxStepsExceeded]）；
 *  - Provider 错误保持为 [com.qianyan.provider.ProviderException]，Tool 错误保持为 [ToolException]，
 *    均透传、不重包、不吞。
 *
 * 明确范围外（P10 不做）：Writing/Planning/Critique/Revision Agent、Novel Workflow、HITL、
 * KnowledgeUpdate、完整小说创作 Pipeline —— 全部 DEFER 到 P11+。
 */
class AgentRuntime(
    private val gateway: LLMGateway,
    private val toolExecutor: ToolExecutor,
    private val model: ModelProfile = ModelProfile.MOCK,
    private val maxSteps: Int = DEFAULT_MAX_STEPS,
) {

    /** 执行一次 Agent 任务（同步）。 */
    fun run(agent: AgentContract, input: String): AgentResult {
        val ctx = AgentExecutionContext(agent, input)
        ctx.state = AgentState.RUNNING

        val messages = mutableListOf(
            ChatMessage(ChatRole.SYSTEM, systemPrompt(agent)),
            ChatMessage(ChatRole.USER, input),
        )

        while (ctx.steps < maxSteps) {
            val response = gateway.chat(ProviderRequest(model = model, messages = messages, temperature = 0.0))
            ctx.steps += 1

            when (val step = AgentResponseParser.parse(response.message.content)) {
                is AgentStep.Final -> {
                    ctx.state = AgentState.COMPLETED
                    return AgentResult(
                        agentId = ctx.agentId,
                        state = AgentState.COMPLETED,
                        answer = step.answer,
                        steps = ctx.steps,
                        toolCalls = ctx.toolCalls.toList(),
                    )
                }
                is AgentStep.Tool -> ctx.toolCalls.add(step.request)
            }

            // 追加模型建议 + 实际工具结果，供下一轮 LLM 观察
            messages.add(ChatMessage(ChatRole.ASSISTANT, response.message.content))
            val lastTool = ctx.toolCalls.last()
            val toolResult = runCatching { toolExecutor.execute(lastTool, ToolContext()) }
            messages.add(
                ChatMessage(
                    ChatRole.USER,
                    renderToolObservation(lastTool, toolResult),
                ),
            )
        }

        // 超出最大步数仍无最终回答 → 防循环
        ctx.state = AgentState.FAILED
        throw AgentException.MaxStepsExceeded(maxSteps)
    }

    /** 把工具结果渲染为模型可读的观察文本（错误也类型化渲染，不吞）。 */
    private fun renderToolObservation(tool: ToolRequest, result: Result<ToolResult>): String {
        val payload = result.fold(
            onSuccess = { r ->
                buildJsonObject {
                    put("tool", tool.toolName.value)
                    put("success", r.success)
                    if (r.error != null) {
                        put("error", r.error)
                    } else {
                        put("output", r.output)
                    }
                }
            },
            onFailure = { e ->
                // ToolException 携带类型化 ToolError；其它为未知异常，仅保留类名（不吞、不触碰敏感信息）。
                buildJsonObject {
                    put("tool", tool.toolName.value)
                    put("success", false)
                    put("error", when (e) {
                        is ToolException -> e.error.toString()
                        else -> e::class.simpleName ?: "unknown"
                    })
                }
            },
        )
        return "ToolResult: $payload"
    }

    /** 构造系统提示：说明已注册工具 + 输出协议。 */
    private fun systemPrompt(agent: AgentContract): String {
        val tools = toolExecutor.availableTools()
            .filter { agent.allowedTools.isEmpty() || agent.allowedTools.contains(it.toolName) }
        return buildString {
            appendLine("你是 ${agent.name}。请严格按以下 JSON 协议单步作答：")
            appendLine("- 需要调用工具时返回 {\"tool\":\"<名称>\",\"arguments\":{...}}")
            appendLine("- 已得到答案时返回 {\"answer\":\"<正文>\"}")
            if (tools.isNotEmpty()) {
                appendLine("可用工具：")
                tools.forEach { t ->
                    val params = t.parameters.joinToString { p -> "${p.name}${if (p.required) "*" else ""}" }
                    appendLine("  - ${t.toolName.value}: ${t.description}（参数: $params）")
                }
            }
        }.trimEnd()
    }

    companion object {
        /** P10 默认最大执行步数。 */
        const val DEFAULT_MAX_STEPS = 10
    }
}