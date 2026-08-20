package com.qianyan.model.tool

import com.qianyan.model.agent.ToolName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/*
 * Tool 领域模型（P10，架构 §21）。
 * 仅建立工具契约的领域承载；注册 / 查找 / 校验 / 执行行为在 :agent:tool。
 * 参数用结构化 JsonObject（避免 Map<String, Any> 充当领域模型）。
 */

/** 工具参数定义（最小；无复杂 Schema Framework）。 */
@Serializable
data class ToolParameterSpec(
    val name: String,
    val description: String = "",
    val required: Boolean = false,
)

/** 工具声明：注册名 + 描述 + 参数定义 + 能力指向。 */
@Serializable
data class ToolDefinition(
    val toolName: ToolName,
    val description: String = "",
    val parameters: List<ToolParameterSpec> = emptyList(),
)

/** 一次工具调用请求。arguments 用结构化 JsonObject。 */
@Serializable
data class ToolRequest(
    val toolName: ToolName,
    val arguments: JsonObject = JsonObject(emptyMap()),
)

/** 工具执行结果。success=false 时 error 提供业务可读原因。 */
@Serializable
data class ToolResult(
    val toolName: ToolName,
    val success: Boolean,
    val output: JsonObject = JsonObject(emptyMap()),
    val error: String? = null,
)