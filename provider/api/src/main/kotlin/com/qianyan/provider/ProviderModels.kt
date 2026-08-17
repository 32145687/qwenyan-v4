package com.qianyan.provider

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** LLM 消息角色（P6 provider:api 契约）。 */
@Serializable
enum class ChatRole { SYSTEM, USER, ASSISTANT }

/** 单条对话消息。 */
@Serializable
data class ChatMessage(
    val role: ChatRole,
    val content: String,
)

/** 模型标识。调用方不绑定具体厂商，只按 id 请求。 */
@Serializable
data class ModelProfile(
    val id: String,
    val label: String = id,
) {
    companion object {
        /** P6 阶段仅 Mock：真实 DeepSeek / MiMo id 延后到后续 Provider 落地。 */
        val MOCK = ModelProfile("mock-v1")
    }
}

/** 一次补全请求。 */
@Serializable
data class ProviderRequest(
    val model: ModelProfile,
    val messages: List<ChatMessage>,
    /** 采样温度，null 表示用模型默认。 */
    val temperature: Double? = null,
    /** 最大输出 token 数，null 表示用模型默认。 */
    val maxTokens: Int? = null,
)

/** Token 用量统计（provider:api 建模，供配额/可观测）。 */
@Serializable
data class Usage(
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int,
)

@Serializable
enum class FinishReason { STOP, LENGTH, CONTENT_FILTER }

/** 一次补全的响应：仅交付文本（助手消息）+ 用量 + 终止原因。 */
@Serializable
data class ProviderResponse(
    val message: ChatMessage,
    val usage: Usage,
    val finishReason: FinishReason = FinishReason.STOP,
) {
    /** 助手正文，便捷访问（不含角色）。 */
    val content: String get() = message.content
}

/** 解析失败的兜底结构化载荷（供上层在 MalformedOutput 时看到 raw）。 */
@Serializable
data class ProviderRawPayload(
    val text: String,
    val parsed: JsonObject? = null,
)