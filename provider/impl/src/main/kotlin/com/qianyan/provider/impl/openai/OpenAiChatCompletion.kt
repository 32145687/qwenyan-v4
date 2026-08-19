package com.qianyan.provider.impl.openai

import com.qianyan.provider.ChatMessage
import com.qianyan.provider.ChatRole
import com.qianyan.provider.FinishReason
import com.qianyan.provider.ProviderException
import com.qianyan.provider.ProviderRequest
import com.qianyan.provider.ProviderResponse
import com.qianyan.provider.Usage
import com.qianyan.provider.impl.transport.HttpResponse
import com.qianyan.provider.impl.transport.LlmHttpClient
import java.io.IOException
import java.net.http.HttpTimeoutException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * DeepSeek / MiMo 共用的 OpenAI 兼容 Chat Completions 客户端（P9，仅 provider:impl 内部）。
 *
 * 职责：
 *  - 把 [ProviderRequest] 构造为 OpenAI 格式 JSON 请求（model/messages/temperature/max_tokens/stream）；
 *  - 调用 [LlmHttpClient] 传输，IO/超时/中断统一映射为 [ProviderException]；
 *  - 非 2xx 按 HTTP 状态码 + 结构化 error.code 映射（429→RateLimit、5xx/4xx→ProviderUnavailable、
 *    context_length_exceeded→TokenLimit）；
 *  - 2xx 解析为标准 chat.completion JSON → [ProviderResponse]（缺必要字段 → InvalidResponse）。
 *
 * 错误分类只使用结构化字段（状态码 / error.code），禁止对自由文本 message 做子串匹配。
 */
internal object OpenAiChatCompletion {

    private val Json = Json { ignoreUnknownKeys = true }

    /** 执行一次补全。wireModel 为 provider 侧实际模型 ID；maxTokensParam 为各 API 的 max tokens 字段名。 */
    fun chat(
        client: LlmHttpClient,
        url: String,
        authHeaderName: String,
        apiKey: String,
        request: ProviderRequest,
        wireModel: String,
        maxTokensParam: String,
    ): ProviderResponse {
        val body = buildJsonObject {
            put("model", wireModel)
            putJsonArray("messages") {
                request.messages.forEach { m ->
                    addJsonObject {
                        put("role", m.role.wireValue())
                        put("content", m.content)
                    }
                }
            }
            request.temperature?.let { put("temperature", it) }
            request.maxTokens?.let { put(maxTokensParam, it) }
            put("stream", false)
        }

        val response = try {
            client.postJson(url, mapOf(authHeaderName to apiKey), body.toString())
        } catch (e: HttpTimeoutException) {
            throw ProviderException.Timeout("${request.model.label} 请求超时", e)
        } catch (e: IOException) {
            throw ProviderException.ProviderUnavailable("${request.model.label} 传输错误: ${e.message}", e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw ProviderException.ProviderUnavailable("${request.model.label} 请求被中断", e)
        }

        if (response.statusCode !in 200..299) throw mapHttpError(request, response)
        return parseResponse(request, response.body)
    }

    /** HTTP 错误映射：结构化状态码 + error.code，不用 message 子串。 */
    private fun mapHttpError(request: ProviderRequest, response: HttpResponse): ProviderException {
        val errCode = errorCode(response.body)
        if (errCode == "context_length_exceeded" || errCode == "max_tokens_exceeded") {
            return ProviderException.TokenLimit("${request.model.label} token 超限: code=$errCode")
        }
        val detail = "${request.model.label} HTTP ${response.statusCode}: ${truncate(response.body)}"
        return when (response.statusCode) {
            429 -> ProviderException.RateLimit(detail)
            401, 403 -> ProviderException.ProviderUnavailable("$detail（鉴权失败）")
            in 500..599 -> ProviderException.ProviderUnavailable(detail)
            else -> ProviderException.ProviderUnavailable(detail)
        }
    }

    /** 2xx 响应解析：缺必要字段（choices/message）→ InvalidResponse；usage 缺失时以 0 兜底。 */
    private fun parseResponse(request: ProviderRequest, body: String): ProviderResponse {
        val root = try {
            Json.parseToJsonElement(body) as? JsonObject
        } catch (e: Exception) {
            throw ProviderException.InvalidResponse("${request.model.label} 返回非法 JSON", e)
        } ?: throw ProviderException.InvalidResponse("${request.model.label} 响应不是 JSON 对象")

        val choices = root["choices"] as? JsonArray
            ?: throw ProviderException.InvalidResponse("${request.model.label} 响应缺少 choices 字段")
        if (choices.isEmpty()) throw ProviderException.InvalidResponse("${request.model.label} 响应 choices 为空")
        val choice = choices[0] as? JsonObject
            ?: throw ProviderException.InvalidResponse("${request.model.label} choice 条目非法")
        val messageObj = choice["message"] as? JsonObject
            ?: throw ProviderException.InvalidResponse("${request.model.label} choice 缺少 message 字段")

        val content = (messageObj["content"] as? JsonPrimitive)?.contentOrNull ?: ""
        val finish = (choice["finish_reason"] as? JsonPrimitive)?.contentOrNull
        val usage = (root["usage"] as? JsonObject).let { u ->
            Usage(
                promptTokens = u?.get("prompt_tokens")?.jsonPrimitive?.intOrNull ?: 0,
                completionTokens = u?.get("completion_tokens")?.jsonPrimitive?.intOrNull ?: 0,
                totalTokens = u?.get("total_tokens")?.jsonPrimitive?.intOrNull ?: 0,
            )
        }
        return ProviderResponse(
            message = ChatMessage(ChatRole.ASSISTANT, content),
            usage = usage,
            finishReason = when (finish) {
                "length" -> FinishReason.LENGTH
                "content_filter" -> FinishReason.CONTENT_FILTER
                else -> FinishReason.STOP
            },
        )
    }

    /** 从错误响应体提取结构化 error.code（OpenAI 兼容），失败返回 null。 */
    private fun errorCode(body: String): String? = try {
        val root = Json.parseToJsonElement(body) as? JsonObject
        val error = root?.get("error") as? JsonObject
        val code = error?.get("code") as? JsonPrimitive
        code?.contentOrNull
    } catch (e: Exception) {
        null
    }

    private fun truncate(body: String): String =
        if (body.length <= 200) body else body.take(200) + "..."
}

private fun ChatRole.wireValue(): String = when (this) {
    ChatRole.SYSTEM -> "system"
    ChatRole.USER -> "user"
    ChatRole.ASSISTANT -> "assistant"
}
