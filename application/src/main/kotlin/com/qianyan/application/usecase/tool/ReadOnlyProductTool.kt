package com.qianyan.application.usecase.tool

import com.qianyan.agent.tool.Tool
import com.qianyan.agent.tool.ToolContext
import com.qianyan.agent.tool.ToolError
import com.qianyan.agent.tool.ToolException
import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.model.ProjectId
import com.qianyan.model.action.AgentActionKind
import com.qianyan.model.agent.ToolName
import com.qianyan.model.tool.ToolRequest
import com.qianyan.model.tool.ToolResult
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

/*
 * I5 · 只读 Product Tool 契约（复用既有 `com.qianyan.agent.tool.Tool`，不造第二套）。
 *
 * 分层（architecture §12）：
 *   Product Tool → Application UseCase（业务能力）→ Repository
 * 严禁直连 SQLite / SQLDelight / Repository / ApplicationContainer（结构守卫测试强制）。
 *
 * 作用域（Project 隔离）：工具从 [ToolContext] 读取项目作用域（由 ProductToolService 注入），
 * 对每个目标对象校验其项目归属；越界一律按"不存在"（NOT_FOUND）处理，不泄漏存在性。
 *
 * 错误边界（复用既有两套类型化错误，不新增第三套）：
 *  - 请求非法（缺参数/类型不符/空 ID）→ [ToolException] / [ToolError.InvalidToolRequest]（P10 既有）；
 *  - 业务失败（不存在/越界/非法操作）→ `ToolResult(success=false)` + 稳定错误码
 *      `NOT_FOUND` / `INVALID_INPUT` / `CONFLICT` / `INTERNAL_ERROR`（由既有 [ApplicationError] 映射）。
 */

/** 只读 Product Tool：除既有 `Tool` 契约外，声明其动作类别（供 I2 ActionPolicy 判断）。 */
interface ReadOnlyProductTool : Tool {
    val actionKind: AgentActionKind
}

/** ToolContext 作用域标签（由 ProductToolService 注入；P10 ToolContext.tags 机制）。 */
internal const val TAG_PROJECT_ID = "projectId"
internal const val TAG_SESSION_ID = "sessionId"

/** 产品工具注册名（= `ToolName` / `ToolCallLog.tool_name` 标识）。 */
object ProductToolNames {
    const val GET_PROJECT = "get_project"
    const val GET_PROJECT_STATE = "get_project_state"
    const val GET_NOVEL = "get_novel"
    const val LIST_CHAPTERS = "list_chapters"
    const val GET_CHAPTER = "get_chapter"
    const val GET_LATEST_DRAFT = "get_latest_draft"
    const val SEARCH_VOCABULARY = "search_vocabulary"
}

/** 工具边界 JSON 编解码器（`internal`：被 `internal inline` 适配器引用）。 */
internal val productToolJson = Json { ignoreUnknownKeys = true }

/** 从执行上下文取项目作用域；缺失/空白 → 既有 `ToolError.InvalidToolRequest`。 */
internal fun toolScopeProjectId(toolName: ToolName, context: ToolContext): ProjectId {
    val raw = context.tags()[TAG_PROJECT_ID]?.trim().orEmpty()
    if (raw.isEmpty()) {
        throw ToolException(ToolError.InvalidToolRequest(toolName, "缺少项目作用域（$TAG_PROJECT_ID）"))
    }
    return ProjectId(raw)
}

/** 类型化输入解析：缺字段 / 类型不符 → 既有 `ToolError.InvalidToolRequest`。 */
internal inline fun <reified I : Any> parseInput(toolName: ToolName, arguments: JsonObject): I = try {
    productToolJson.decodeFromJsonElement(arguments)
} catch (e: SerializationException) {
    throw ToolException(ToolError.InvalidToolRequest(toolName, "参数非法: ${e.message}"), e)
} catch (e: IllegalArgumentException) {
    throw ToolException(ToolError.InvalidToolRequest(toolName, "参数非法: ${e.message}"), e)
}

/** 只读工具执行骨架：作用域 → 类型化输入 → 业务能力 → 类型化输出；业务失败映射为稳定错误码。 */
internal inline fun <reified I : Any, reified O : Any> runReadOnly(
    toolName: ToolName,
    request: ToolRequest,
    context: ToolContext,
    block: (ProjectId, I) -> O,
): ToolResult {
    val scope = toolScopeProjectId(toolName, context)
    val input = parseInput<I>(toolName, request.arguments)
    return try {
        val output = block(scope, input)
        ToolResult(toolName, success = true, output = productToolJson.encodeToJsonElement(output).jsonObject)
    } catch (e: ApplicationException) {
        ToolResult(toolName, success = false, error = applicationErrorCode(e))
    }
}

/** 既有 [ApplicationError] → 稳定错误码（不新增 Error System）。 */
internal fun applicationErrorCode(e: ApplicationException): String {
    val code = when (e.error) {
        is ApplicationError.EntityNotFound -> "NOT_FOUND"
        is ApplicationError.InvalidOperation, is ApplicationError.VariantMismatch -> "INVALID_INPUT"
        is ApplicationError.DraftConfirmationRequired -> "CONFLICT"
        else -> "INTERNAL_ERROR"
    }
    return "$code: ${e.message ?: e.error.toString()}"
}

/** 必填字符串参数：空白 → `ApplicationError.InvalidOperation`（稳定码 INVALID_INPUT）。 */
internal fun requireNotBlank(value: String, field: String): String {
    if (value.isBlank()) {
        throw ApplicationException(ApplicationError.InvalidOperation("$field 不能为空"))
    }
    return value
}

/**
 * 项目归属校验（Project 隔离）：目标对象的项目锚必须等于当前作用域，
 * 否则一律按"不存在"拒绝（不泄漏其他 Project 的存在性）。
 */
internal fun requireSameProject(scope: ProjectId, targetProjectId: ProjectId, what: String) {
    if (scope != targetProjectId) {
        throw ApplicationException(ApplicationError.EntityNotFound("$what 不存在"))
    }
}