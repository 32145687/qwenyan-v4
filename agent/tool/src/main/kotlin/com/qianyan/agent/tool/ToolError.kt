package com.qianyan.agent.tool

import com.qianyan.model.agent.ToolName

/**
 * Tool 层类型化错误（P10）。
 *
 * 职责边界：
 *  - 禁止经 String / contains 判断错误类型；调用方（AgentRuntime / Application / 测试）按类型捕获；
 *  - 只表达"工具系统内部"错误；领域工具执行失败由 [ToolResult.success=false] 表达，
 *    而 Registry 未找到 / 请求非法 / 执行抛异常，则由 Tool 层归一为这些子类型。
 */
sealed interface ToolError {

    /** Registry 中不存在该工具。 */
    data class ToolNotFound(val toolName: ToolName) : ToolError

    /** 请求非法：缺少必填参数 / 参数类型不符 / 参数名不在定义中。 */
    data class InvalidToolRequest(val toolName: ToolName, val detail: String) : ToolError

    /** 工具执行时抛了未归一异常。 */
    data class ToolExecutionFailed(val toolName: ToolName, val detail: String, val cause: Throwable? = null) : ToolError
}

/** Tool 层运行时异常载体；携带唯一类型化错误 [error]。 */
class ToolException(
    val error: ToolError,
    cause: Throwable? = null,
) : Exception(error.toString(), cause)