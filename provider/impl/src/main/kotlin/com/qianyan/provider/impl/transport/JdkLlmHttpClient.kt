package com.qianyan.provider.impl.transport

import com.qianyan.provider.TimeoutConfig
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers
import java.time.Duration

/**
 * JDK 17 `java.net.http.HttpClient` 实现（P9）。
 *
 * 零第三方 HTTP 依赖，符合最小架构原则。连接超时用 [TimeoutConfig.connectMillis]，
 * 请求读超时用 [TimeoutConfig.readMillis]；超时/IO 异常原样上抛，
 * 由调用方（OpenAiChatCompletion）统一映射为 [com.qianyan.provider.ProviderException]。
 */
class JdkLlmHttpClient(
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(TimeoutConfig().connectMillis))
        .build(),
    private val timeoutConfig: TimeoutConfig = TimeoutConfig(),
) : LlmHttpClient {

    override fun postJson(url: String, headers: Map<String, String>, body: String): HttpResponse {
        val builder = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofMillis(timeoutConfig.readMillis))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
        headers.forEach { (name, value) -> builder.header(name, value) }
        val response = client.send(builder.build(), BodyHandlers.ofString())
        return HttpResponse(response.statusCode(), response.body())
    }
}
