package com.qianyan.app.android.security

import com.qianyan.provider.ProviderCredentialStore
import com.qianyan.provider.ProviderType

/**
 * Android 安全凭证存储 seam（P12.1.5）。
 *
 * 目标架构：
 * ```
 * Application → ProviderCredentialStore(:provider:api) → Android secure implementation(app:android)
 * ```
 *
 * 安全策略：
 *  - API Key 的 Android 持久化**必须**走 Android Keystore-backed 方案；
 *  - 禁止明文写入 SQLite / 普通 SharedPreferences / 普通文件 / DataStore 明文值。
 *
 * 状态说明：**secure persistence = NOT IMPLEMENTED**。
 * 本阶段仅建立接口与 DI seam，不伪装成已安全存储；真实 Keystore-backed implementation 属后续阶段
 * （当前无法在普通 JVM 单元测试中运行 Android Keystore）。
 *
 * 注意：本类尚未被 wired（P12.1.6/7 再接入 UI 与 DI），任何调用都会类型化失败而非伪造安全。
 */
class AndroidKeystoreProviderCredentialStore : ProviderCredentialStore {

    /** secure persistence = NOT IMPLEMENTED：读取即失败，绝不返回伪造的"安全"凭证。 */
    override fun getApiKey(provider: ProviderType): String =
        throw NotImplementedError("Android Keystore secure persistence: NOT IMPLEMENTED (P12.1.5 seam)")

    /** secure persistence = NOT IMPLEMENTED. */
    override fun setApiKey(provider: ProviderType, apiKey: String) {
        throw NotImplementedError("Android Keystore secure persistence: NOT IMPLEMENTED (P12.1.5 seam)")
    }

    /** secure persistence = NOT IMPLEMENTED. */
    override fun clearApiKey(provider: ProviderType) {
        throw NotImplementedError("Android Keystore secure persistence: NOT IMPLEMENTED (P12.1.5 seam)")
    }
}