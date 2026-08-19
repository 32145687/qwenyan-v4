package com.qianyan.provider.impl

import com.qianyan.provider.impl.transport.HttpResponse
import com.qianyan.provider.impl.transport.LlmHttpClient

/**
 * P9 测试用 fake transport：可记录最近一次请求并可编程响应，普通测试不依赖真实网络。
 * 置于 provider:impl 测试源集，供 DeepSeek / MiMo 网关契约测试复用。
 */
class FakeLlmHttpClient(
    private val responder: (url: String, headers: Map<String, String>, body: String) -> HttpResponse =
        { _, _, _ -> HttpResponse(200, "{}") },
) : LlmHttpClient {

    var lastUrl: String = ""
    var lastHeaders: Map<String, String> = emptyMap()
    var lastBody: String = ""

    override fun postJson(url: String, headers: Map<String, String>, body: String): HttpResponse {
        lastUrl = url
        lastHeaders = headers
        lastBody = body
        return responder(url, headers, body)
    }
}
