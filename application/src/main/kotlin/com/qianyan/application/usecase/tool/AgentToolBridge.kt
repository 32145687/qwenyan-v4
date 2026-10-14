package com.qianyan.application.usecase.tool

import com.qianyan.agent.tool.Tool
import com.qianyan.agent.tool.ToolContext
import com.qianyan.agent.tool.ToolException
import com.qianyan.agent.tool.ToolRegistry
import com.qianyan.application.error.ApplicationException
import com.qianyan.model.ActivityId
import com.qianyan.model.agent.ToolName
import com.qianyan.model.tool.ToolDefinition
import com.qianyan.model.tool.ToolRequest
import com.qianyan.model.tool.ToolResult

/**
 * AgentRuntime ↔ ProductToolService 桥（Agent ↔ Tool 断链的最小修复；只拼装既有件，不新建第二套 Runtime / Registry / 协议）。
 *
 * 断链：写作 Agent 的 [com.qianyan.agent.runtime.AgentRuntime] 持有空 `ToolRegistry` ⇒
 * 模型看不到任何工具声明、自发的工具调用永远 ToolNotFound（只能靠 maxSteps 兜底）。
 *
 * 修复方式：
 *  - 把 [ProductToolService] 的既有只读 Product Tool 以**同名同 definition** 包装注册进 Agent 的既有
 *    `ToolRegistry`；
 *  - 包装器的 execute **原样转调** [ProductToolService.invoke] ⇒ ActionPolicy 判定、ToolCallLog 审计、
 *    Project/Session 作用域锚全部复用既有调度链（绝不绕过调度层直连裸 `ToolExecutor`）；
 *  - [runtimeRegistry] 严格校验 `allowed ⊆ 服务侧已注册`：声明与可执行集合不一致即抛出（装配错误 fail-fast），
 *    配合 `AgentContract.allowedTools` 使用同一清单 ⇒ systemPrompt 声明 == 实际可执行。
 *
 * 作用域：activityId 由调用方（NovelAgent 编排相位）提供，每次运行重建注册表；工具内部再按
 * Activity.projectId 做归属校验，跨项目读取按"不存在"拒绝（不泄漏存在性）。
 */
class AgentToolBridge(private val productTools: ProductToolService) {

    /**
     * 为一次 Agent 运行构建可执行注册表。
     * @throws IllegalArgumentException [allowed] 中存在服务侧未注册的工具名（声明不可执行 = 装配错误）。
     */
    fun runtimeRegistry(activityId: ActivityId, allowed: List<ToolName>): ToolRegistry {
        val available = productTools.availableTools().associateBy { it.toolName }
        val missing = allowed.filter { it !in available }
        require(missing.isEmpty()) {
            "Agent 工具声明与 ProductToolService 注册不一致，缺失: ${missing.joinToString(", ") { it.value }}"
        }
        return ToolRegistry().also { registry ->
            allowed.forEach { name ->
                registry.register(BridgedTool(productTools, activityId, assertNotNull(available[name])))
            }
        }
    }

    private fun assertNotNull(definition: ToolDefinition?): ToolDefinition =
        requireNotNull(definition) { "工具定义缺失" }

    /** 委托包装：同名同 definition；执行经既有调度层（权限 + 审计 + 作用域）。 */
    private class BridgedTool(
        private val productTools: ProductToolService,
        private val activityId: ActivityId,
        override val definition: ToolDefinition,
    ) : Tool {

        override fun execute(request: ToolRequest, context: ToolContext): ToolResult = try {
            productTools.invoke(activityId, request)
        } catch (e: ToolException) {
            // 已归一（TOOL_NOT_FOUND / INVALID_INPUT / INTERNAL_ERROR）：原样透传，由 AgentRuntime 渲染为失败观察
            throw e
        } catch (e: ApplicationException) {
            // 调度层的业务拒绝（如 Activity 锚缺失）：按 Tool 契约归一为失败结果，不吞、不伪装成功
            ToolResult(definition.toolName, success = false, error = e.error.toString())
        }
    }
}
