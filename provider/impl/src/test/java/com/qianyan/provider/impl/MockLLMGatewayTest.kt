package com.qianyan.provider.impl

import com.qianyan.provider.ChatMessage
import com.qianyan.provider.ChatRole
import com.qianyan.provider.FinishReason
import com.qianyan.provider.ModelProfile
import com.qianyan.provider.ProviderException
import com.qianyan.provider.ProviderRequest
import com.qianyan.provider.Usage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** P6.2 Mock Provider 契约测试（provider:impl）。 */
class MockLLMGatewayTest {

    private fun request(content: String = "第一章 正文") = ProviderRequest(
        model = ModelProfile.MOCK,
        messages = listOf(
            ChatMessage(ChatRole.SYSTEM, "提取世界设定词汇"),
            ChatMessage(ChatRole.USER, content),
        ),
    )

    @Test
    fun `mock returns deterministic response for same request`() {
        val g = MockLLMGateway()
        val a = g.chat(request("第一章 正文"))
        val b = g.chat(request("第一章 正文"))
        assertEquals(a, b)
        assertEquals(FinishReason.STOP, a.finishReason)
        assertEquals(ChatRole.ASSISTANT, a.message.role)
        assertEquals(a.usage.totalTokens, a.usage.promptTokens + a.usage.completionTokens)
    }

    @Test
    fun `mock returns stably parseable vocabulary json`() {
        val g = MockLLMGateway()
        val out = g.chat(request())
        // 可被 Application 端 JSON 解析（不含 Python 注释块等毒化前缀）
        assertEquals(MockOutput.DEFAULT_VOCABULARY_JSON, out.content)
    }

    @Test
    fun `injected responseFor can simulate provider failure`() {
        val g = MockLLMGateway { throw ProviderException.RateLimit("limit") }
        val ex = assertFailsWith<ProviderException.RateLimit> { g.chat(request()) }
        assertEquals("limit", ex.detail)
    }

    @Test
    fun `usage reflects deterministic token estimate`() {
        val g = MockLLMGateway()
        val out = g.chat(request("正文较长" + "词".repeat(100)))
        assert(out.usage.promptTokens > 0) { "prompt tokens 应随输入长度增加而 >0" }
        assert(out.usage.totalTokens == out.usage.promptTokens + out.usage.completionTokens)
        assert(out.usage.completionTokens == 32)
    }
}