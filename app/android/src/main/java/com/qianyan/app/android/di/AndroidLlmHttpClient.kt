package com.qianyan.app.android.di

import com.qianyan.provider.TimeoutConfig
import com.qianyan.provider.impl.transport.HttpResponse
import com.qianyan.provider.impl.transport.LlmHttpClient
import java.net.HttpURLConnection
import java.net.URL

/**
 * Android 环境的 [LlmHttpClient] 实现（P12.5-M04/M03 真实 Provider 网络缝）。
 *
 * `provider:impl` 的 JVM 参考实现 [com.qianyan.provider.impl.transport.JdkLlmHttpClient]
 * 依赖 `java.net.http`（JDK 模块），**Android 运行时不可用**；这里用平台级 [HttpURLConnection]
 * 提供等价 JSON POST（连接超时 / 读超时沿用 [TimeoutConfig]）。仅用于 Android 端真实 DeepSeek / MiMo 调用。
 */
class AndroidLlmHttpClient(
    private val timeoutConfig: TimeoutConfig = TimeoutConfig(),
) : LlmHttpClient {

    override fun postJson(url: String, headers: Map<String, String>, body: String): HttpResponse {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = timeoutConfig.connectMillis.toInt()
            conn.readTimeout = timeoutConfig.readMillis.toInt()
            conn.setRequestProperty("Content-Type", "application/json")
            headers.forEach { (name, value) -> conn.setRequestProperty(name, value) }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val status = conn.responseCode
            val stream = if (status >= 400) conn.errorStream else conn.inputStream
            val responseBody = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            return HttpResponse(statusCode = status, body = responseBody)
        } finally {
            conn.disconnect()
        }
    }
}