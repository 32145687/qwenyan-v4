package com.qianyan.provider.impl

import com.qianyan.provider.LLMGateway
import com.qianyan.provider.ModelProfile
import com.qianyan.provider.ProviderException
import com.qianyan.provider.ProviderRequest
import com.qianyan.provider.ProviderResponse
import com.qianyan.provider.impl.openai.OpenAiChatCompletion
import com.qianyan.provider.impl.transport.JdkLlmHttpClient
import com.qianyan.provider.impl.transport.LlmHttpClient

/**
 * 真实 MiMo Provider（P9）。
 *
 * 官方 Xiaomi MiMo API 开放平台（2026 确认）：base_url=https://api.xiaomimimo.com/v1，
 * endpoint=/chat/completions，v2.5 系列官方模型 ID `mimo-v2.5-pro`，鉴权 `api-key: <apiKey>`
 * （官方文档 curl 示例用 `api-key` header）。max tokens 字段 `max_completion_tokens`。
 *
 * API Key 为**注入式**：只经构造参数进入，绝不写入日志/异常/仓库/UI。测试注入 fake key。
 */
class MiMoLLMGateway(
    private val apiKey: String,
    private val client: LlmHttpClient = JdkLlmHttpClient(),
    private val baseUrl: String = "https://api.xiaomimimo.com/v1",
    private val wireModel: String = ModelProfile.MIMO_V2_5.id,
) : LLMGateway {

    override fun chat(request: ProviderRequest): ProviderResponse {
        requireConfigured()
        return OpenAiChatCompletion.chat(
            client = client,
            url = "$baseUrl/chat/completions",
            authHeaderName = "api-key",
            apiKey = apiKey,
            request = request,
            wireModel = wireModel,
            maxTokensParam = "max_completion_tokens",
        )
    }

    /** API Key 缺失是装配错误：调用时类型化拒绝（不打印 key 内容）。 */
    private fun requireConfigured() {
        if (apiKey.isBlank()) {
            throw ProviderException.ProviderUnavailable("MiMo API key 未配置")
        }
    }
}
