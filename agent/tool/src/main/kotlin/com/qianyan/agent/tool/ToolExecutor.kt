package com.qianyan.agent.tool

import com.qianyan.model.tool.ToolParameterSpec
import com.qianyan.model.tool.ToolRequest
import com.qianyan.model.tool.ToolResult
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

/**
 * 工具执行器（P10，架构 §21.3）。
 *
 * 流程：按请求名查找工具 → 校验请求（是否存在 / 必填参数）→ 执行 → 归一为 [ToolResult] 或 [ToolException]。
 *
 * 错误归一（禁止 String.contains 判断）：
 *  - 工具不存在   → [ToolError.ToolNotFound]
 *  - 校验失败     → [ToolError.InvalidToolRequest]
 *  - 执行抛异常   → [ToolError.ToolExecutionFailed]
 * 工具自身返回 `success=false` 的 [ToolResult] 时，作为业务失败原样返回（不抛异常）。
 */
class ToolExecutor(private val registry: ToolRegistry) {

    /** 已注册工具的定义清单（供 Agent 系统提示 / 外部渲染工具列表；注册顺序）。 */
    fun availableTools(): List<com.qianyan.model.tool.ToolDefinition> = registry.all()
        .map { it.definition }
        .sortedBy { it.toolName.value }

    /** 校验 + 执行一次工具调用。 */
    fun execute(request: ToolRequest, context: ToolContext): ToolResult {
        val tool = resolve(request)
        validate(request)
        val result = try {
            tool.execute(request, context)
        } catch (e: ToolException) {
            throw e // 工具已归一，原样透传
        } catch (t: Throwable) {
            throw ToolException(
                ToolError.ToolExecutionFailed(request.toolName, "工具执行抛出未归一异常: ${t.message ?: t::class.simpleName}", t),
            )
        }
        return result
    }

    /** 依据请求查找工具；不存在抛 [ToolError.ToolNotFound]。 */
    private fun resolve(request: ToolRequest): Tool {
        val tool = registry.find(request.toolName)
            ?: throw ToolException(ToolError.ToolNotFound(request.toolName))
        return tool
    }

    /**
     * 请求校验：请求名必须存在；必填参数必须存在（值 null 视为缺省）；拒绝未知参数名。
     */
    private fun validate(request: ToolRequest) {
        val def = registry.find(request.toolName)?.definition
            ?: throw ToolException(ToolError.ToolNotFound(request.toolName))

        val args = request.arguments
        val defined = def.parameters.mapTo(mutableSetOf()) { it.name }

        for (spec: ToolParameterSpec in def.parameters) {
            if (spec.required) {
                val value = args[spec.name]
                if (value.isMissing()) {
                    throw ToolException(
                        ToolError.InvalidToolRequest(request.toolName, "缺少必填参数: ${spec.name}"),
                    )
                }
            }
        }
        // 拒绝未声明的参数
        val provided = args.keys.toMutableSet()
        provided.removeAll(defined)
        if (provided.isNotEmpty()) {
            throw ToolException(
                ToolError.InvalidToolRequest(request.toolName, "未知参数: ${provided.sorted().joinToString(",")}"),
            )
        }
    }

    private fun JsonElement?.isMissing(): Boolean = this == null || this is JsonNull
}