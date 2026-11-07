package com.qianyan.provider.impl

import com.qianyan.provider.BalanceQuery
import com.qianyan.provider.ProviderException
import com.qianyan.provider.impl.transport.HttpResponse
import com.qianyan.provider.impl.transport.LlmHttpClient
import java.net.http.HttpTimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** A-14 余额查询契约（provider:impl，fake transport，零真实网络；Key 为假值 test-key）。 */
class ProviderBalanceTest {

    private class FakeGetTransport(
        private val response: HttpResponse? = null,
        private val error: Throwable? = null,
    ) : LlmHttpClient {
        var url: String? = null
        var headers: Map<String, String>? = null

        override fun postJson(url: String, headers: Map<String, String>, body: String): HttpResponse =
            throw AssertionError("余额查询不得走 POST")

        override fun getJson(url: String, headers: Map<String, String>): HttpResponse {
            this.url = url
            this.headers = headers
            error?.let { throw it }
            return response ?: throw AssertionError("未配置响应")
        }
    }

    private val deepSeekBody = """
        {
          "is_available": true,
          "balance_infos": [
            {"currency": "CNY", "total_balance": "110.00", "granted_balance": "10.00", "topped_up_balance": "100.00"}
          ]
        }
    """.trimIndent()

    private val miMoBody = """
        {
          "is_available": true,
          "balance_infos": [
            {"currency": "CNY", "total_available": "8.50", "cash_balance": "6.00", "gift_balance": "2.50"}
          ]
        }
    """.trimIndent()

    @Test
    fun `deepseek balance maps fields url and bearer header`() {
        val transport = FakeGetTransport(HttpResponse(200, deepSeekBody))
        val balance = DeepSeekLLMGateway(apiKey = "test-key", client = transport).queryBalance()

        assertTrue(balance.available)
        assertEquals(1, balance.lines.size)
        assertEquals("CNY", balance.lines[0].currency)
        assertEquals("110.00", balance.lines[0].total)
        assertEquals("100.00", balance.lines[0].cash)
        assertEquals("10.00", balance.lines[0].granted)
        assertEquals("https://api.deepseek.com/user/balance", transport.url)
        assertEquals("Bearer test-key", transport.headers?.get("Authorization"))
        assertEquals("application/json", transport.headers?.get("Accept"))
    }

    @Test
    fun `mimo balance maps fields url and api-key header`() {
        val transport = FakeGetTransport(HttpResponse(200, miMoBody))
        val balance = MiMoLLMGateway(apiKey = "test-key", client = transport).queryBalance()

        assertTrue(balance.available)
        assertEquals("8.50", balance.lines[0].total)
        assertEquals("6.00", balance.lines[0].cash)
        assertEquals("2.50", balance.lines[0].granted)
        assertEquals("https://api.xiaomimimo.com/v1/platform/balance", transport.url)
        assertEquals("test-key", transport.headers?.get("api-key"))
        assertEquals("application/json", transport.headers?.get("Accept"))
    }

    @Test
    fun `unauthorized balance query maps to provider unavailable without leaking key`() {
        val transport = FakeGetTransport(HttpResponse(401, """{"error":"unauthorized"}"""))
        val error = assertFailsWith<ProviderException.ProviderUnavailable> {
            DeepSeekLLMGateway(apiKey = "test-key", client = transport).queryBalance()
        }
        assertTrue(error.message!!.contains("鉴权失败"))
        assertFalse(error.message!!.contains("test-key"), "错误文案不得包含 API Key")
    }

    @Test
    fun `timeout balance query maps to timeout`() {
        val transport = FakeGetTransport(error = HttpTimeoutException("read timed out"))
        val error = assertFailsWith<ProviderException.Timeout> {
            MiMoLLMGateway(apiKey = "test-key", client = transport).queryBalance()
        }
        assertFalse(error.message!!.contains("test-key"), "错误文案不得包含 API Key")
    }

    @Test
    fun `mock gateway is not a balance query`() {
        assertFalse(MockLLMGateway() is BalanceQuery, "离线示意不提供余额查询")
        assertIs<BalanceQuery>(DeepSeekLLMGateway(apiKey = "test-key"))
        assertIs<BalanceQuery>(MiMoLLMGateway(apiKey = "test-key"))
    }
}
