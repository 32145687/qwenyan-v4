package com.qianyan.agent.tool

import com.qianyan.model.tool.ToolDefinition
import com.qianyan.model.tool.ToolRequest
import com.qianyan.model.tool.ToolResult

/**
 * 工具契约（P10，架构 §21.1）。
 *
 * 一个可被 Agent 调用的能力：
 *  - [definition] 声明注册名、参数定义（供校验 / LLM 选择工具）；
 *  - [execute] 验证请求并执行，返回结构化 [ToolResult]。
 *
 * 职责边界：Tool 只操作 Application / Engine / Repository Contract，不得直连
 * SQLDelight / SQLite / Android / API Key / 具体 Provider。失败经 [ToolError] 类型化归一。
 */
interface Tool {
    val definition: ToolDefinition

    /** 执行一次工具调用。严禁把未归一异常直接抛出；应转为 [ToolException] 或 `ToolResult(success=false)`。 */
    fun execute(request: ToolRequest, context: ToolContext): ToolResult
}