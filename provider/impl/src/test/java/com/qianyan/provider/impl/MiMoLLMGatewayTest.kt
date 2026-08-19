package com.qianyan.provider.impl

import com.qianyan.provider.ChatMessage
import com.qianyan.provider.ChatRole
import com.qianyan.provider.FinishReason
import com.qianyan.provider.ModelProfile
import com.qianyan.provider.ProviderException
import com.qianyan.provider.ProviderRequest
import com.qianyan.provider.impl.transport.HttpResponse
import java.net.http.HttpTimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** P9 MiMo 网关契约测试（provider:impl，fake transport，无真实网络）。 */
class MiMoLLMGatewayTest {

    private fun request(
        model: ModelProfile = ModelProfile.MIMO_V2_5,
        content: String = "提取世界设定词汇",
        temperature: Double? = 0.0,
        maxTokens: Int? = 1024,
    ) = ProviderRequest(
        model = model,
        messages = listOf(
            ChatMessage(ChatRole.SYSTEM, "你是小说设定分析助手"),
            ChatMessage(ChatRole.USER, content),
        ),
        temperature = temperature,
        maxTokens = maxTokens,
    )

    private val okJson =
        """
        {
          "id": "chatcmpl-mimo-1",
          "object": "chat.completion",
          "choices": [
            {
              "index": 0,
              "message": {"role": "assistant", "content": "星石、气海"},
              "finish_reason": "stop"
            }
          ],
          "usage": {"prompt_tokens": 9, "completion_tokens": 4, "total_tokens": 13}
        }
        """.trimIndent()

    /* 成功链路 */
    @Test
    fun `success maps request to mimo wire request and parses response`() {
        val fake = FakeLlmHttpClient { _, _, _ -> HttpResponse(200, okJson) }
        val gateway = MiMoLLMGateway(apiKey = "test-key", client = fake)

        val out = gateway.chat(request())

        assertEquals("https://api.xiaomimimo.com/v1/chat/completions", fake.lastUrl)
        assertEquals("test-key", fake.lastHeaders["api-key"])
        // wire body：模型 ID / 角色 / 内容 / 温度 / max_completion_tokens / 非流式
        assertTrue(fake.lastBody.contains("\"model\":\"mimo-v2.5-pro\""))
        assertTrue(fake.lastBody.contains("\"role\":\"user\""))
        assertTrue(fake.lastBody.contains("提取世界设定词汇"))
        assertTrue(fake.lastBody.contains("\"temperature\":0.0"))
        assertTrue(fake.lastBody.contains("\"max_completion_tokens\":1024"))
        assertTrue(fake.lastBody.contains("\"stream\":false"))

        assertEquals("星石、气海", out.content)
        assertEquals(FinishReason.STOP, out.finishReason)
        assertEquals(ChatRole.ASSISTANT, out.message.role)
        assertEquals(9, out.usage.promptTokens)
        assertEquals(4, out.usage.completionTokens)
        assertEquals(13, out.usage.totalTokens)
    }

    @Test
    fun `success with default temperature and null max tokens`() {
        val fake = FakeLlmHttpClient { _, _, _ -> HttpResponse(200, okJson) }
        val gateway = MiMoLLMGateway(apiKey = "test-key", client = fake)
        val out = gateway.chat(request(temperature = null, maxTokens = null))
        assertEquals("星石、气海", out.content)
        assertTrue(!fake.lastBody.contains("temperature"))
        assertTrue(!fake.lastBody.contains("max_completion_tokens"))
    }

    /* API Key 缺失 */
    @Test
    fun `missing api key throws provider unavailable`() {
        val gateway = MiMoLLMGateway(apiKey = "", client = FakeLlmHttpClient())
        val ex = assertFailsWith<ProviderException.ProviderUnavailable> { gateway.chat(request()) }
        assertTrue(ex.detail.contains("MiMo"))
    }

    /* 超时 */
    @Test
    fun `transport timeout maps to provider timeout`() {
        val fake = FakeLlmHttpClient { _, _, _ -> throw HttpTimeoutException("read timed out") }
        val gateway = MiMoLLMGateway(apiKey = "test-key", client = fake)
        assertFailsWith<ProviderException.Timeout> { gateway.chat(request()) }
    }

    /* 429 限流 */
    @Test
    fun `http 429 maps to rate limit`() {
        val body = """{"error":{"message":"too many requests","code":"rate_limit_reached"}}"""
        val gateway = MiMoLLMGateway(apiKey = "test-key", client = FakeLlmHttpClient { _, _, _ -> HttpResponse(429, body) })
        val ex = assertFailsWith<ProviderException.RateLimit> { gateway.chat(request()) }
        assertTrue(ex.detail.contains("429"))
    }

    /* 4xx 鉴权失败 */
    @Test
    fun `http 403 maps to provider unavailable`() {
        val body = """{"error":{"message":"forbidden","code":"permission_denied"}}"""
        val gateway = MiMoLLMGateway(apiKey = "test-key", client = FakeLlmHttpClient { _, _, _ -> HttpResponse(403, body) })
        val ex = assertFailsWith<ProviderException.ProviderUnavailable> { gateway.chat(request()) }
        assertTrue(ex.detail.contains("403"))
    }

    /* 5xx 服务不可用 */
    @Test
    fun `http 503 maps to provider unavailable`() {
        val body = """{"error":{"message":"unavailable","code":"server_overloaded"}}"""
        val gateway = MiMoLLMGateway(apiKey = "test-key", client = FakeLlmHttpClient { _, _, _ -> HttpResponse(503, body) })
        val ex = assertFailsWith<ProviderException.ProviderUnavailable> { gateway.chat(request()) }
        assertTrue(ex.detail.contains("503"))
    }

    /* 非法 JSON */
    @Test
    fun `invalid json maps to invalid response`() {
        val gateway = MiMoLLMGateway(apiKey = "test-key", client = FakeLlmHttpClient { _, _, _ -> HttpResponse(200, "<html>") })
        assertFailsWith<ProviderException.InvalidResponse> { gateway.chat(request()) }
    }

    /* 缺少必要字段 */
    @Test
    fun `choice missing message maps to invalid response`() {
        val body = """{"choices":[{"index":0,"finish_reason":"stop"}]}"""
        val gateway = MiMoLLMGateway(apiKey = "test-key", client = FakeLlmHttpClient { _, _, _ -> HttpResponse(200, body) })
        assertFailsWith<ProviderException.InvalidResponse> { gateway.chat(request()) }
    }

    /* Provider 结构化错误：token 超限 */
    @Test
    fun `max tokens exceeded maps to token limit`() {
        val body = """{"error":{"message":"exceeded","code":"max_tokens_exceeded"}}"""
        val gateway = MiMoLLMGateway(apiKey = "test-key", client = FakeLlmHttpClient { _, _, _ -> HttpResponse(400, body) })
        assertIs<ProviderException.TokenLimit>(assertFailsWith<ProviderException> { gateway.chat(request()) })
    }
}
