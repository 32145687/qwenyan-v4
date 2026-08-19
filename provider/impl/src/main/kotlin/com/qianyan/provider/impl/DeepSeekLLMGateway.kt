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
 * 真实 DeepSeek Provider（P9）。
 *
 * 官方 OpenAI 兼容 API（2026 确认）：base_url=https://api.deepseek.com，endpoint=/chat/completions，
 * 鉴权 `Authorization: Bearer <apiKey>`，模型 ID `deepseek-v4-flash`（DeepSeek-V4-Flash-0731 正式版；
 * 旧 `deepseek-chat`/`deepseek-reasoner` 已于 2026-07-24 退役）。max tokens 字段 `max_tokens`。
 *
 * API Key 为**注入式**：只经构造参数进入，绝不写入日志/异常/仓库/UI。测试注入 fake key。
 */
class DeepSeekLLMGateway(
    private val apiKey: String,
    private val client: LlmHttpClient = JdkLlmHttpClient(),
    private val baseUrl: String = "https://api.deepseek.com",
    private val wireModel: String = ModelProfile.DEEPSEEK_V4_FLASH.id,
) : LLMGateway {

    override fun chat(request: ProviderRequest): ProviderResponse {
        requireConfigured()
        return OpenAiChatCompletion.chat(
            client = client,
            url = "$baseUrl/chat/completions",
            authHeaderName = "Authorization",
            apiKey = "Bearer $apiKey",
            request = request,
            wireModel = wireModel,
            maxTokensParam = "max_tokens",
        )
    }

    /** API Key 缺失是装配错误：调用时类型化拒绝（不打印 key 内容）。 */
    private fun requireConfigured() {
        if (apiKey.isBlank()) {
            throw ProviderException.ProviderUnavailable("DeepSeek API key 未配置")
        }
    }
}
