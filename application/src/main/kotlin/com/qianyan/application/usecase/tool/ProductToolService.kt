package com.qianyan.application.usecase.tool

import com.qianyan.agent.tool.Tool
import com.qianyan.agent.tool.ToolContext
import com.qianyan.agent.tool.ToolError
import com.qianyan.agent.tool.ToolException
import com.qianyan.agent.tool.ToolExecutor
import com.qianyan.agent.tool.ToolRegistry
import com.qianyan.application.usecase.action.ActionPolicyUseCases
import com.qianyan.application.usecase.log.ActivityUseCases
import com.qianyan.application.usecase.log.ToolCallLogUseCases
import com.qianyan.model.ActivityId
import com.qianyan.model.ToolCallId
import com.qianyan.model.action.ActionDecision
import com.qianyan.model.action.AgentAction
import com.qianyan.model.agent.ToolName
import com.qianyan.model.tool.ToolDefinition
import com.qianyan.model.tool.ToolRequest
import com.qianyan.model.tool.ToolResult

/*
 * I5 · Product Tool 调度层（唯一执行入口）。
 *
 * 职责链（architecture §12）：
 *   invoke(activityId, request)
 *     → 既有 Activity（作用域锚：projectId / sessionId）
 *     → 既有 ToolRegistry / ToolExecutor（查找 + 必填校验 + 执行归一）
 *     → I2 ActionPolicy 判定（Allowed 才执行；Denied / NeedsHuman 一律不执行）
 *     → I4 ToolCallLog 记录（start → succeed / fail）
 *
 * 硬边界：
 *  - **不造第二套 Contract / Executor / Error**：注册用既有 `ToolRegistry`，执行用既有 `ToolExecutor`，
 *    请求非法/工具未知沿用既有 `ToolError` / `ToolException`；
 *  - **不改写生命周期**：不创建/改写 AgentSession / Workflow / Task，只写"调用事实记录"（I4 语义）；
 *  - **只读**：本阶段注册的 Product Tool 全部为只读动作（READ / SEARCH），无可写能力（I6+ 才引入）。
 */
class ProductToolService(
    tools: List<ReadOnlyProductTool>,
    private val activities: ActivityUseCases,
    private val toolCallLogs: ToolCallLogUseCases,
    private val actionPolicy: ActionPolicyUseCases,
) {

    /** 工具表（含动作类别，供 ActionPolicy 判定）。 */
    private val byName: Map<ToolName, ReadOnlyProductTool> = tools.associateBy { it.definition.toolName }

    /** 既有注册表（查找 / 清单顺序；同名覆盖语义沿用 P10）。 */
    private val registry: ToolRegistry = ToolRegistry().also { reg -> tools.forEach { reg.register(it) } }

    /** 既有执行器（必填参数校验 + 异常归一；不重复实现）。 */
    private val executor: ToolExecutor = ToolExecutor(registry)

    /** 已注册工具定义清单（按名字排序，确定性）。 */
    fun availableTools(): List<ToolDefinition> = executor.availableTools()

    /** 按注册名查找（不存在返回 null，与 [ToolRegistry.find] 语义一致）。 */
    fun find(toolName: ToolName): Tool? = registry.find(toolName)

    /**
     * 执行一次 Product Tool 调用。
     *
     *  - 无 Activity → 无作用域锚：类型化拒绝（[com.qianyan.application.error.ApplicationError.EntityNotFound]）；
     *  - 工具未注册 → 既有 [ToolError.ToolNotFound]；
     *  - 动作未放行（Denied / NeedsHuman）→ 不执行工具，返回 `success=false` 并留痕（审计）；
     *  - 请求非法（必填缺失 / 未知参数）→ 既有 [ToolError.InvalidToolRequest]，先留痕再抛出；
     *  - 业务失败（不存在 / 越界）→ `ToolResult(success=false)` + 稳定错误码，留痕后原样返回。
     */
    fun invoke(activityId: ActivityId, request: ToolRequest): ToolResult {
        val activity = activities.get(activityId)
        val tool = byName[request.toolName]
            ?: throw ToolException(ToolError.ToolNotFound(request.toolName))

        val log = toolCallLogs.start(
            activityId = activityId,
            toolName = request.toolName,
            inputSummary = request.arguments.toString(),
        )
        val context = ToolContext()
            .tag(TAG_PROJECT_ID, activity.projectId.value)
            .tag(TAG_SESSION_ID, activity.sessionId.value)

        return when (val decision = actionPolicy.evaluate(AgentAction(tool.actionKind))) {
            is ActionDecision.Allowed -> executeAllowed(request, context, log.toolCallId)
            is ActionDecision.Denied -> recordBlocked(request.toolName, log.toolCallId, "FORBIDDEN", decision.reason)
            is ActionDecision.NeedsHuman ->
                recordBlocked(request.toolName, log.toolCallId, "NEEDS_HUMAN", decision.reason)
        }
    }

    /** 放行路径：执行 → 成功/失败均落到既有 ToolCallLog（不吞异常）。 */
    private fun executeAllowed(request: ToolRequest, context: ToolContext, toolCallId: ToolCallId): ToolResult {
        val result = try {
            executor.execute(request, context)
        } catch (e: ToolException) {
            toolCallLogs.fail(toolCallId, "${toolErrorCode(e.error)}: ${e.message ?: e.error}")
            throw e
        }
        if (result.success) {
            toolCallLogs.succeed(toolCallId, outputSummary = result.output.toString())
        } else {
            toolCallLogs.fail(toolCallId, result.error ?: "工具执行失败")
        }
        return result
    }

    /** 未放行路径：工具**不执行**，但调用事实仍留痕（既有 ToolCallLog，FAILED）。 */
    private fun recordBlocked(
        toolName: ToolName,
        toolCallId: ToolCallId,
        prefix: String,
        reason: String,
    ): ToolResult {
        val error = "$prefix: $reason"
        toolCallLogs.fail(toolCallId, error)
        return ToolResult(toolName, success = false, error = error)
    }

    /** 既有 [ToolError] → 稳定错误码（不新增错误系统）。 */
    private fun toolErrorCode(error: ToolError): String = when (error) {
        is ToolError.ToolNotFound -> "TOOL_NOT_FOUND"
        is ToolError.InvalidToolRequest -> "INVALID_INPUT"
        is ToolError.ToolExecutionFailed -> "INTERNAL_ERROR"
    }
}