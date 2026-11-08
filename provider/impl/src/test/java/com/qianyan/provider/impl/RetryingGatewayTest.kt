package com.qianyan.provider.impl

import com.qianyan.provider.ChatMessage
import com.qianyan.provider.ChatRole
import com.qianyan.provider.LLMGateway
import com.qianyan.provider.ModelProfile
import com.qianyan.provider.ProviderException
import com.qianyan.provider.ProviderRequest
import com.qianyan.provider.ProviderResponse
import com.qianyan.provider.RetryConfig
import com.qianyan.provider.Usage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** L-2 有界重试网关（sleep 注入空实现，不真睡、零网络）。 */
class RetryingGatewayTest {

    private val request = ProviderRequest(
        model = ModelProfile("test-model"),
        messages = listOf(ChatMessage(ChatRole.USER, "hi")),
    )

    private fun ok(): ProviderResponse = ProviderResponse(
        message = ChatMessage(ChatRole.ASSISTANT, "ok"),
        usage = Usage(1, 1, 2),
    )

    private class FakeGateway(private val block: (Int) -> ProviderResponse) : LLMGateway {
        var calls = 0
        override fun chat(request: ProviderRequest): ProviderResponse = block(++calls)
    }

    @Test
    fun `429 then 200 retries once and succeeds`() {
        val fake = FakeGateway { n -> if (n == 1) throw ProviderException.RateLimit("429") else ok() }
        val delays = mutableListOf<Long>()

        val out = RetryingGateway(fake, RetryConfig(maxRetries = 2, backoffMillis = 500)) { delays += it }.chat(request)

        assertEquals("ok", out.content)
        assertEquals(2, fake.calls, "限流后应只重试 1 次")
        assertEquals(listOf(500L), delays, "线性退避：第 1 次重试等待 backoff×1")
    }

    @Test
    fun `three timeouts exhaust retries and rethrow timeout`() {
        val fake = FakeGateway { throw ProviderException.Timeout("connect timeout") }
        val delays = mutableListOf<Long>()

        assertFailsWith<ProviderException.Timeout> {
            RetryingGateway(fake, RetryConfig(maxRetries = 2, backoffMillis = 500)) { delays += it }.chat(request)
        }

        assertEquals(3, fake.calls, "总尝试 = 1 + maxRetries")
        assertEquals(listOf(500L, 1000L), delays, "线性退避：500ms、1000ms")
    }

    @Test
    fun `token limit is not retried`() {
        val fake = FakeGateway { throw ProviderException.TokenLimit("too long") }
        val delays = mutableListOf<Long>()

        assertFailsWith<ProviderException.TokenLimit> {
            RetryingGateway(fake, RetryConfig(maxRetries = 2, backoffMillis = 500)) { delays += it }.chat(request)
        }

        assertEquals(1, fake.calls, "token 超限重试同一请求无意义，只尝试 1 次")
        assertTrue(delays.isEmpty())
    }

    @Test
    fun `credential and contract errors are not retried`() {
        listOf(
            ProviderException.CredentialMissing("no key"),
            ProviderException.InvalidResponse("bad json"),
            ProviderException.MalformedOutput("bad shape"),
        ).forEach { boom ->
            val fake = FakeGateway { throw boom }
            assertFailsWith<ProviderException> {
                RetryingGateway(fake, RetryConfig(maxRetries = 2, backoffMillis = 0)) { }.chat(request)
            }
            assertEquals(1, fake.calls, "${boom::class.simpleName} 不得重试")
        }
    }

    @Test
    fun `authentication failure is not retried and never sleeps`() {
        val fake = FakeGateway { throw ProviderException.AuthenticationFailed("HTTP 401") }
        val delays = mutableListOf<Long>()

        assertFailsWith<ProviderException.AuthenticationFailed> {
            RetryingGateway(fake, RetryConfig(maxRetries = 2, backoffMillis = 500)) { delays += it }.chat(request)
        }

        assertEquals(1, fake.calls, "鉴权失败只尝试 1 次（O-69）")
        assertTrue(delays.isEmpty(), "鉴权失败不得进入退避等待")
    }

    @Test
    fun `provider unavailable is retried`() {
        val fake = FakeGateway { throw ProviderException.ProviderUnavailable("503") }

        assertFailsWith<ProviderException.ProviderUnavailable> {
            RetryingGateway(fake, RetryConfig(maxRetries = 2, backoffMillis = 0)) { }.chat(request)
        }

        assertEquals(3, fake.calls)
    }

    @Test
    fun `interrupted thread stops retrying immediately`() {
        val fake = FakeGateway { throw ProviderException.Timeout("t") }
        Thread.currentThread().interrupt()
        try {
            assertFailsWith<ProviderException.Timeout> {
                RetryingGateway(fake, RetryConfig(maxRetries = 2, backoffMillis = 0)) { }.chat(request)
            }
            assertEquals(1, fake.calls, "线程已中断时不得继续重试")
        } finally {
            Thread.interrupted()
        }
    }
}
