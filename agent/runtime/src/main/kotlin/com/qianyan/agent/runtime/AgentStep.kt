package com.qianyan.agent.runtime

import com.qianyan.model.agent.ToolName
import com.qianyan.model.tool.ToolRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Agent 单步执行结果的领域协议（P10）。
 *
 * 模型根据系统提示返回结构化 JSON，指示本步是"最终回答 final"还是"调用工具 tool"：
 * ```
 * {"tool":"<ToolName>","arguments":{...}}   → Tool
 * {"answer":"<text>"}                        → Final
 * ```
 * 无法按 JSON 解析时（模型直接输出正文），兜底视为 [Final]，避免死循环。
 */
sealed interface AgentStep {

    /** 已给出最终回答。 */
    data class Final(val answer: String) : AgentStep

    /** 请求调用某个工具。 */
    data class Tool(val request: ToolRequest) : AgentStep
}

/** 把一次模型输出解析为 [AgentStep]（确定性；不经 String.contains 判断错误类型）。 */
object AgentResponseParser {

    /**
     * 解析模型正文：
     *  - 合法 JSON 对象含 `tool` → [AgentStep.Tool]；
     *  - 合法 JSON 对象含 `answer` → [AgentStep.Final]；
     *  - 其余（含纯文本）→ [AgentStep.Final]（内容为原样正文）。
     */
    fun parse(content: String): AgentStep {
        val root = parseJsonObject(content)
        if (root == null) {
            return AgentStep.Final(content)
        }
        root["tool"]?.jsonPrimitive?.contentOrNull?.let { name ->
            val args = root["arguments"]?.let { runCatching { it.jsonObject }.getOrNull() } ?: buildJsonObject { }
            return AgentStep.Tool(ToolRequest(toolName = ToolName(name), arguments = args))
        }
        root["answer"]?.jsonPrimitive?.contentOrNull?.let { answer ->
            return AgentStep.Final(answer)
        }
        return AgentStep.Final(content)
    }

    /** 尽力解析为 JsonObject；失败返回 null。 */
    private val json = Json { ignoreUnknownKeys = true }

    private fun parseJsonObject(content: String): JsonObject? {
        if (content.isBlank()) return null
        return runCatching { json.parseToJsonElement(content).jsonObject }.getOrNull()
    }
}