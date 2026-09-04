package com.qianyan.provider

/**
 * Provider 运行时配置与凭证抽象（P12.1.5）。
 *
 * 目标：把"代码构造时注入 API Key"推进为"用户/上层配置 Provider → Application/DI → LLMGateway"，
 * 同时保证 **API Key 永不进入 Domain / 业务层**：
 *  - [ProviderConfiguration] 只描述 provider 类型 / 模型 / endpoint，**不携带 API Key**；
 *  - API Key 属于 secure runtime credential，经 [ProviderCredentialStore] 单独持有，由 [ProviderAssembler] 在组装 Provider client 时读取。
 *
 * 本文件全部位于 provider:api 契约层（纯 JVM），不依赖 Android Keystore / Context / SharedPreferences / SQLDelight。
 */

/** 支持的 Provider 类型。 */
enum class ProviderType { MOCK, DEEPSEEK, MIMO }

/**
 * Provider 运行配置（应用级，不绑定 novel/variant/chapter/task）。
 *
 * 注意：这里**没有** apiKey 字段 —— API Key 只属于 [ProviderCredentialStore]，从不出现在本对象或任何领域模型中。
 *
 * @param model 模型标识；null 时由 [ProviderAssembler] 使用该 Provider 的默认模型。
 * @param baseUrl 端点覆盖；null 时用该 Provider 官方默认 endpoint。
 */
data class ProviderConfiguration(
    val provider: ProviderType,
    val model: ModelProfile? = null,
    val baseUrl: String? = null,
)

/**
 * Provider 凭证安全存储抽象（secure runtime credential）。
 *
 * 实现可注入：Android Keystore-backed adapter（见 app:android，P12.1.5 seam）、内存 store 等。
 * :provider / :application 只依赖本抽象，**不直接依赖** Android Keystore / Context / SharedPreferences / DataStore / SQLDelight。
 */
interface ProviderCredentialStore {

    /** 读取某 Provider 的 API Key；未配置返回 null。 */
    fun getApiKey(provider: ProviderType): String?

    /** 设置某 Provider 的 API Key。 */
    fun setApiKey(provider: ProviderType, apiKey: String)

    /** 清除某 Provider 的 API Key。 */
    fun clearApiKey(provider: ProviderType)
}

/** 最简单的运行时凭证持有（内存 Map）。仅供 JVM / 测试 / 桌面；非持久化、非安全存储。 */
class InMemoryProviderCredentialStore : ProviderCredentialStore {
    private val keys = java.util.concurrent.ConcurrentHashMap<ProviderType, String>()

    override fun getApiKey(provider: ProviderType): String? = keys[provider]

    override fun setApiKey(provider: ProviderType, apiKey: String) {
        keys[provider] = apiKey
    }

    override fun clearApiKey(provider: ProviderType) {
        keys.remove(provider)
    }
}

/**
 * Provider 组装器：配置 → 具体 [LLMGateway] client。
 *
 * 职责：读 [ProviderCredentialStore] 取 credential → 构造运行时 Provider client（DeepSeek / MiMo / Mock）。
 * 每次 [assemble] 都从当前 store 读取最新 credential（不缓存旧 key），更新 credential 后重建即为确定的刷新机制。
 */
interface ProviderAssembler {

    /**
     * 按 [config] 组装运行时 [LLMGateway]。
     * 需要 credential 的 Provider 缺少 API Key → 抛 [ProviderException.CredentialMissing]（配置期快速失败，不做真实网络请求）。
     */
    fun assemble(config: ProviderConfiguration): LLMGateway

    /** 配置最小校验（provider 有效 / 需要 credential 的 Provider 是否已配置）；不调用真实 LLM。返回问题列表，空 = 合法。 */
    fun validate(config: ProviderConfiguration): List<String>
}