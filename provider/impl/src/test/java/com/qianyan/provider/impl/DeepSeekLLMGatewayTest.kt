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

/** P9 DeepSeek 网关契约测试（provider:impl，fake transport，无真实网络）。 */
class DeepSeekLLMGatewayTest {

    private fun request(
        model: ModelProfile = ModelProfile.DEEPSEEK_V4_FLASH,
        content: String = "提取世界设定词汇",
        temperature: Double? = 0.0,
        maxTokens: Int? = 512,
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
          "id": "chatcmpl-deepseek-1",
          "object": "chat.completion",
          "choices": [
            {
              "index": 0,
              "message": {"role": "assistant", "content": "灵石、丹田"},
              "finish_reason": "stop"
            }
          ],
          "usage": {"prompt_tokens": 12, "completion_tokens": 6, "total_tokens": 18}
        }
        """.trimIndent()

    /* 成功链路 */
    @Test
    fun `success maps request to deepseek wire request and parses response`() {
        val fake = FakeLlmHttpClient { _, _, _ -> HttpResponse(200, okJson) }
        val gateway = DeepSeekLLMGateway(apiKey = "test-key", client = fake)

        val out = gateway.chat(request())

        assertEquals("https://api.deepseek.com/chat/completions", fake.lastUrl)
        assertEquals("Bearer test-key", fake.lastHeaders["Authorization"])
        // wire body：模型 ID / 角色 / 内容 / 温度 / max_tokens / 非流式
        assertTrue(fake.lastBody.contains("\"model\":\"deepseek-v4-flash\""))
        assertTrue(fake.lastBody.contains("\"role\":\"system\""))
        assertTrue(fake.lastBody.contains("你是小说设定分析助手"))
        assertTrue(fake.lastBody.contains("\"temperature\":0.0"))
        assertTrue(fake.lastBody.contains("\"max_tokens\":512"))
        assertTrue(fake.lastBody.contains("\"stream\":false"))

        assertEquals("灵石、丹田", out.content)
        assertEquals(FinishReason.STOP, out.finishReason)
        assertEquals(ChatRole.ASSISTANT, out.message.role)
        assertEquals(12, out.usage.promptTokens)
        assertEquals(6, out.usage.completionTokens)
        assertEquals(18, out.usage.totalTokens)
    }

    @Test
    fun `success maps length finish reason`() {
        val body = okJson.replace("\"finish_reason\": \"stop\"", "\"finish_reason\": \"length\"")
        val gateway = DeepSeekLLMGateway(apiKey = "test-key", client = FakeLlmHttpClient { _, _, _ -> HttpResponse(200, body) })
        val out = gateway.chat(request(maxTokens = null))
        assertEquals(FinishReason.LENGTH, out.finishReason)
    }

    /* API Key 缺失 */
    @Test
    fun `missing api key throws provider unavailable`() {
        val gateway = DeepSeekLLMGateway(apiKey = "   ", client = FakeLlmHttpClient())
        val ex = assertFailsWith<ProviderException.ProviderUnavailable> { gateway.chat(request()) }
        assertTrue(ex.detail.contains("DeepSeek"))
    }

    /* 超时 */
    @Test
    fun `transport timeout maps to provider timeout`() {
        val fake = FakeLlmHttpClient { _, _, _ -> throw HttpTimeoutException("connect timed out") }
        val gateway = DeepSeekLLMGateway(apiKey = "test-key", client = fake)
        assertFailsWith<ProviderException.Timeout> { gateway.chat(request()) }
    }

    /* 429 限流 */
    @Test
    fun `http 429 maps to rate limit`() {
        val body = """{"error":{"message":"rate limit","code":"rate_limit_exceeded"}}"""
        val gateway = DeepSeekLLMGateway(apiKey = "test-key", client = FakeLlmHttpClient { _, _, _ -> HttpResponse(429, body) })
        val ex = assertFailsWith<ProviderException.RateLimit> { gateway.chat(request()) }
        assertTrue(ex.detail.contains("429"))
    }

    /* 4xx 鉴权失败 */
    @Test
    fun `http 401 maps to provider unavailable`() {
        val body = """{"error":{"message":"invalid api key","code":"invalid_api_key"}}"""
        val gateway = DeepSeekLLMGateway(apiKey = "test-key", client = FakeLlmHttpClient { _, _, _ -> HttpResponse(401, body) })
        val ex = assertFailsWith<ProviderException.ProviderUnavailable> { gateway.chat(request()) }
        assertTrue(ex.detail.contains("401"))
    }

    /* 5xx 服务不可用 */
    @Test
    fun `http 500 maps to provider unavailable`() {
        val body = """{"error":{"message":"internal error","code":"server_error"}}"""
        val gateway = DeepSeekLLMGateway(apiKey = "test-key", client = FakeLlmHttpClient { _, _, _ -> HttpResponse(500, body) })
        val ex = assertFailsWith<ProviderException.ProviderUnavailable> { gateway.chat(request()) }
        assertTrue(ex.detail.contains("500"))
    }

    /* 非法 JSON */
    @Test
    fun `invalid json maps to invalid response`() {
        val gateway = DeepSeekLLMGateway(apiKey = "test-key", client = FakeLlmHttpClient { _, _, _ -> HttpResponse(200, "not-json{{{") })
        assertFailsWith<ProviderException.InvalidResponse> { gateway.chat(request()) }
    }

    /* 缺少必要字段 */
    @Test
    fun `missing choices field maps to invalid response`() {
        val gateway = DeepSeekLLMGateway(apiKey = "test-key", client = FakeLlmHttpClient { _, _, _ -> HttpResponse(200, """{"id":"x"}""") })
        assertFailsWith<ProviderException.InvalidResponse> { gateway.chat(request()) }
    }

    @Test
    fun `empty choices maps to invalid response`() {
        val body = """{"choices":[],"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}"""
        val gateway = DeepSeekLLMGateway(apiKey = "test-key", client = FakeLlmHttpClient { _, _, _ -> HttpResponse(200, body) })
        assertFailsWith<ProviderException.InvalidResponse> { gateway.chat(request()) }
    }

    /* Provider 结构化错误：token 超限 */
    @Test
    fun `context length exceeded maps to token limit`() {
        val body = """{"error":{"message":"context too long","code":"context_length_exceeded"}}"""
        val gateway = DeepSeekLLMGateway(apiKey = "test-key", client = FakeLlmHttpClient { _, _, _ -> HttpResponse(400, body) })
        assertIs<ProviderException.TokenLimit>(assertFailsWith<ProviderException> { gateway.chat(request()) })
    }
}
