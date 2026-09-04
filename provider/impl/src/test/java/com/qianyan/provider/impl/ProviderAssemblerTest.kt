package com.qianyan.provider.impl

import com.qianyan.provider.ChatMessage
import com.qianyan.provider.ChatRole
import com.qianyan.provider.InMemoryProviderCredentialStore
import com.qianyan.provider.ModelProfile
import com.qianyan.provider.ProviderConfiguration
import com.qianyan.provider.ProviderException
import com.qianyan.provider.ProviderRequest
import com.qianyan.provider.ProviderType
import com.qianyan.provider.impl.transport.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P12.1.5 ProviderAssembler / ProviderConfiguration / ProviderCredentialStore 测试（T1–T12 + security）。
 * 全程 fake transport，无真实网络 / 真实 API Key。
 */
class ProviderAssemblerTest {

    private val secret = "TEST_SECRET_KEY_ABC"

    private val okJson =
        """{"choices":[{"message":{"role":"assistant","content":"hi"},"finish_reason":"stop"}],
           "usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}"""

    private fun request(model: ModelProfile = ModelProfile.DEEPSEEK_V4_FLASH) = ProviderRequest(
        model = model,
        messages = listOf(ChatMessage(ChatRole.USER, "ping")),
        temperature = 0.0,
    )

    /* T1 — Mock Provider 不需要 API Key */
    @Test
    fun `t1 mock provider works without api key`() {
        val store = InMemoryProviderCredentialStore() // 空
        val fake = FakeLlmHttpClient()
        val assembler = DefaultProviderAssembler(store, fake)

        val gateway = assembler.assemble(ProviderConfiguration(provider = ProviderType.MOCK))

        assertIs<MockLLMGateway>(gateway)
        // validate：Mock 无任何配置问题
        assertTrue(DefaultProviderAssembler(store, fake).validate(ProviderConfiguration(ProviderType.MOCK)).isEmpty())
    }

    /* T2 — DeepSeek 缺少 API Key → ProviderException.CredentialMissing */
    @Test
    fun `t2 deepseek without key throws credential missing`() {
        val assembler = DefaultProviderAssembler(InMemoryProviderCredentialStore(), FakeLlmHttpClient())
        val ex = assertFailsWith<ProviderException.CredentialMissing> {
            assembler.assemble(ProviderConfiguration(provider = ProviderType.DEEPSEEK))
        }
        assertTrue(!ex.detail.contains(secret))
        // validate 非空（有配置问题）
        assertTrue(assembler.validate(ProviderConfiguration(ProviderType.DEEPSEEK)).isNotEmpty())
    }

    /* T3 — MiMo 缺少 API Key → ProviderException.CredentialMissing */
    @Test
    fun `t3 mimo without key throws credential missing`() {
        val assembler = DefaultProviderAssembler(InMemoryProviderCredentialStore(), FakeLlmHttpClient())
        val ex = assertFailsWith<ProviderException.CredentialMissing> {
            assembler.assemble(ProviderConfiguration(provider = ProviderType.MIMO))
        }
        assertTrue(!ex.detail.contains(secret))
    }

    /* T4 — DeepSeek 配置可正确构造 runtime provider */
    @Test
    fun `t4 deepseek assembles runtime provider`() {
        val store = InMemoryProviderCredentialStore()
        store.setApiKey(ProviderType.DEEPSEEK, secret)
        val fake = FakeLlmHttpClient { _, _, _ -> HttpResponse(200, okJson) }
        val gateway = DefaultProviderAssembler(store, fake).assemble(ProviderConfiguration(provider = ProviderType.DEEPSEEK))

        assertIs<DeepSeekLLMGateway>(gateway)
        gateway.chat(request())
        assertEquals("https://api.deepseek.com/chat/completions", fake.lastUrl)
        assertEquals("Bearer $secret", fake.lastHeaders["Authorization"])
    }

    /* T5 — MiMo 配置可正确构造 runtime provider */
    @Test
    fun `t5 mimo assembles runtime provider`() {
        val store = InMemoryProviderCredentialStore()
        store.setApiKey(ProviderType.MIMO, secret)
        val fake = FakeLlmHttpClient { _, _, _ -> HttpResponse(200, okJson) }
        val gateway = DefaultProviderAssembler(store, fake).assemble(ProviderConfiguration(provider = ProviderType.MIMO))

        assertIs<MiMoLLMGateway>(gateway)
        gateway.chat(request(ModelProfile.MIMO_V2_5))
        assertEquals("https://api.xiaomimimo.com/v1/chat/completions", fake.lastUrl)
        assertEquals(secret, fake.lastHeaders["api-key"])
    }

    /* T6 — API Key 不进入 Configuration（Domain/配置对象无 apiKey 字段） */
    @Test
    fun `t6 api key is not part of configuration object`() {
        // ProviderConfiguration 是传给 assembler 的唯一"配置"对象，data class toString 会列出全部字段名——
        // 断言其完全不出现 apiKey / secret，即结构上无法携带凭证。
        val config = ProviderConfiguration(provider = ProviderType.DEEPSEEK, model = ModelProfile.DEEPSEEK_V4_FLASH)
        assertTrue(!config.toString().contains("apiKey", ignoreCase = true))
        assertTrue(!config.toString().contains(secret))
    }

    /* T7/T8/T9 — 需在 application 层验证（Task/Checkpoint/Memory/Story State 不落入 API Key），见 ProviderDiTest */

    /* T10 — API Key 不进入（provider 侧）错误文本 */
    @Test
    fun `t10 api key not in exception messages`() {
        val store = InMemoryProviderCredentialStore()
        store.setApiKey(ProviderType.DEEPSEEK, secret)
        val fake = FakeLlmHttpClient { _, _, _ -> HttpResponse(500, """{"error":{}}""") }
        val gateway = DefaultProviderAssembler(store, fake).assemble(ProviderConfiguration(ProviderType.DEEPSEEK))
        val ex = assertFailsWith<ProviderException> { gateway.chat(request()) }
        assertTrue(!(ex.message ?: "").contains(secret))
        // 缺凭证错误也不含任何 key（store 里根本不该有 key 内容回显）
        val missingEx = assertFailsWith<ProviderException.CredentialMissing> {
            DefaultProviderAssembler(InMemoryProviderCredentialStore(), FakeLlmHttpClient())
                .assemble(ProviderConfiguration(ProviderType.MIMO))
        }
        assertTrue(!(missingEx.message ?: "").contains(secret))
    }

    /* T11 — 修改 credential 后重新 assemble，runtime provider 使用新 credential（不缓存旧 key） */
    @Test
    fun `t11 credential change rebuilds provider with new key`() {
        val store = InMemoryProviderCredentialStore()
        val fake = FakeLlmHttpClient { _, _, _ -> HttpResponse(200, okJson) }
        val assembler = DefaultProviderAssembler(store, fake)

        store.setApiKey(ProviderType.DEEPSEEK, "OLD_KEY")
        val g1 = assembler.assemble(ProviderConfiguration(ProviderType.DEEPSEEK))
        g1.chat(request())
        assertEquals("Bearer OLD_KEY", fake.lastHeaders["Authorization"])

        // 更新 credential → 重建 → 新 provider client 使用新 key（旧 client 不再被复用）
        store.setApiKey(ProviderType.DEEPSEEK, "NEW_KEY")
        val g2 = assembler.assemble(ProviderConfiguration(ProviderType.DEEPSEEK))
        assertTrue(g2 !== g1)
        g2.chat(request())
        assertEquals("Bearer NEW_KEY", fake.lastHeaders["Authorization"])
    }

    /* T12 — Provider configuration 与 Novel/Variant 无关（无任何 scope 字段） */
    @Test
    fun `t12 provider config is not scoped to novel or variant`() {
        val config = ProviderConfiguration(ProviderType.DEEPSEEK)
        val repr = config.toString()
        assertTrue(!repr.contains("novel", ignoreCase = true))
        assertTrue(!repr.contains("variant", ignoreCase = true))
        assertTrue(!repr.contains("chapter", ignoreCase = true))
        assertTrue(!repr.contains("task", ignoreCase = true))
        assertTrue(!repr.contains("apiKey", ignoreCase = true))
        assertNull(config.model) // 模型可选，且不含 scope
    }
}