package com.qianyan.provider.impl.transport

/**
 * HTTP transport 接缝（P9）。
 *
 * 真实网关（DeepSeek / MiMo）经 [LlmHttpClient] 发送 JSON POST；测试注入 fake 实现，
 * 保证普通 Gradle 测试完全不依赖真实网络。实现只负责传输，不负责业务错误映射
 * （HTTP 状态码 → [com.qianyan.provider.ProviderException] 由 OpenAiChatCompletion 统一处理）。
 */
fun interface LlmHttpClient {

    /** 发送 JSON POST，返回状态码与响应体。IO 失败抛出原始异常（由调用方映射）。 */
    fun postJson(url: String, headers: Map<String, String>, body: String): HttpResponse
}

/** 传输层响应（纯数据）。 */
data class HttpResponse(
    val statusCode: Int,
    val body: String,
)
