package com.qianyan.provider.impl

import com.qianyan.provider.LLMGateway
import com.qianyan.provider.ModelProfile
import com.qianyan.provider.ProviderAssembler
import com.qianyan.provider.ProviderConfiguration
import com.qianyan.provider.ProviderCredentialStore
import com.qianyan.provider.ProviderException
import com.qianyan.provider.ProviderType
import com.qianyan.provider.impl.transport.JdkLlmHttpClient
import com.qianyan.provider.impl.transport.LlmHttpClient

/**
 * 默认 Provider 组装器（P12.1.5）。
 *
 * 把 [ProviderConfiguration] + [ProviderCredentialStore] 组装为具体 [LLMGateway]：
 *  - MOCK → [MockLLMGateway]（**不要求 API Key**，测试/本地无需真实凭证）；
 *  - DEEPSEEK → [DeepSeekLLMGateway]（要求 store 中已配置 API Key）；
 *  - MIMO → [MiMoLLMGateway]（要求 store 中已配置 API Key）。
 *
 * 每次 [assemble] 都从 [ProviderCredentialStore] 实时读取最新 credential（不缓存旧 key），
 * 因此修改 credential 后重新 assemble 即为确定性的重建/刷新机制，旧 client 不再被复用。
 * client 为 HTTP transport 接缝（默认 JDK 实现；测试注入 fake，普通测试不依赖真实网络）。
 */
class DefaultProviderAssembler(
    private val credentials: ProviderCredentialStore,
    private val client: LlmHttpClient = JdkLlmHttpClient(),
) : ProviderAssembler {

    override fun assemble(config: ProviderConfiguration): LLMGateway = when (config.provider) {
        ProviderType.MOCK -> MockLLMGateway()

        ProviderType.DEEPSEEK -> {
            val apiKey = requireCredential(ProviderType.DEEPSEEK)
            DeepSeekLLMGateway(
                apiKey = apiKey,
                client = client,
                baseUrl = config.baseUrl ?: DEEPSEEK_BASE_URL,
                wireModel = config.model?.id ?: ModelProfile.DEEPSEEK_V4_FLASH.id,
            )
        }

        ProviderType.MIMO -> {
            val apiKey = requireCredential(ProviderType.MIMO)
            MiMoLLMGateway(
                apiKey = apiKey,
                client = client,
                baseUrl = config.baseUrl ?: MIMO_BASE_URL,
                wireModel = config.model?.id ?: ModelProfile.MIMO_V2_5.id,
            )
        }
    }

    override fun validate(config: ProviderConfiguration): List<String> = buildList {
        when (config.provider) {
            ProviderType.MOCK -> Unit
            ProviderType.DEEPSEEK, ProviderType.MIMO ->
                if (credentials.getApiKey(config.provider).isNullOrBlank()) {
                    add("${config.provider} API key 未配置（credential missing）")
                }
        }
    }

    /** 需要 credential 的 Provider 缺 key → 配置期快速失败（不进入 HTTP；detail 不含 key 内容）。 */
    private fun requireCredential(provider: ProviderType): String =
        credentials.getApiKey(provider)?.takeIf { it.isNotBlank() }
            ?: throw ProviderException.CredentialMissing("${provider} API key 未配置")

    private companion object {
        const val DEEPSEEK_BASE_URL = "https://api.deepseek.com"
        const val MIMO_BASE_URL = "https://api.xiaomimimo.com/v1"
    }
}