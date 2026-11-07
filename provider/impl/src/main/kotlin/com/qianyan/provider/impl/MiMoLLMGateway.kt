package com.qianyan.provider.impl

import com.qianyan.provider.LLMGateway
import com.qianyan.provider.ModelProfile
import com.qianyan.provider.ProviderException
import com.qianyan.provider.ProviderRequest
import com.qianyan.provider.ProviderResponse
import com.qianyan.provider.ReasoningEffort
import com.qianyan.provider.impl.openai.OpenAiChatCompletion
import com.qianyan.provider.impl.openai.ThinkingEncoding
import com.qianyan.provider.impl.transport.JdkLlmHttpClient
import com.qianyan.provider.impl.transport.LlmHttpClient

/**
 * 真实 MiMo Provider（P9）。
 *
 * 官方 Xiaomi MiMo API 开放平台（2026 确认）：base_url=https://api.xiaomimimo.com/v1，
 * endpoint=/chat/completions，v2.6 系列官方模型 ID `mimo-v2.6-pro`（另有 -flash / -pro-ultraspeed），
 * 鉴权 `api-key: <apiKey>`（官方文档 curl 示例用 `api-key` header）。max tokens 字段 `max_completion_tokens`。
 *
 * 思考强度依据 MiMo 契约编码为 `"thinking": {"type": "enabled" | "disabled"}`（开关而非档位）：
 * NONE → disabled，LOW/HIGH/MAX → enabled；未指定时不发送该字段。默认 enabled。
 * **开启思考时服务端忽略显式 temperature / top_p（强制 1.0 / 0.95）**，思维链与最终回复共用 token 预算。
 *
 * API Key 为**注入式**：只经构造参数进入，绝不写入日志/异常/仓库/UI。测试注入 fake key。
 */
class MiMoLLMGateway(
    private val apiKey: String,
    private val client: LlmHttpClient = JdkLlmHttpClient(),
    private val baseUrl: String = "https://api.xiaomimimo.com/v1",
    private val wireModel: String = ModelProfile.MIMO_V2_6_PRO.id,
    private val reasoningEffort: ReasoningEffort? = null,
) : LLMGateway {

    override fun chat(request: ProviderRequest): ProviderResponse {
        requireConfigured()
        // 思考强度由配置注入；请求自身显式指定时优先（3 个生产构造点无需改动）。
        val merged = request.copy(reasoningEffort = request.reasoningEffort ?: reasoningEffort)
        return OpenAiChatCompletion.chat(
            client = client,
            url = "$baseUrl/chat/completions",
            authHeaderName = "api-key",
            apiKey = apiKey,
            request = merged,
            wireModel = wireModel,
            maxTokensParam = "max_completion_tokens",
            thinkingEncoding = ThinkingEncoding.THINKING_TYPE_FIELD,
        )
    }

    /** API Key 缺失是装配错误：调用时类型化拒绝（不打印 key 内容，不进入 HTTP）。 */
    private fun requireConfigured() {
        if (apiKey.isBlank()) {
            throw ProviderException.CredentialMissing("MiMo API key 未配置")
        }
    }
}
