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

    /**
     * 发送 GET，返回状态码与响应体（余额等只读端点用）。
     *
     * 默认实现直接拒绝：只有具备 GET 能力的真实 transport 才覆盖它，
     * 既有的 POST-only fake / SAM 实现零改动。Accept 头由调用方传入，客户端不写死。
     */
    fun getJson(url: String, headers: Map<String, String>): HttpResponse =
        throw UnsupportedOperationException("当前 HTTP transport 不支持 GET 请求")
}

/** 传输层响应（纯数据）。 */
data class HttpResponse(
    val statusCode: Int,
    val body: String,
)
