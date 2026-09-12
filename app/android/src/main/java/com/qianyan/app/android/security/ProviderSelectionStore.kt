package com.qianyan.app.android.security

import android.content.Context
import com.qianyan.provider.ProviderType

/**
 * 当前选中 Provider 的持久化（非机密选择项）。
 *
 * 注意：这里只存「选了哪个 Provider」这一非机密配置；API Key 本身一律由
 * [AndroidKeystoreProviderCredentialStore]（Android Keystore 加密）持有，永不明文。
 */
object ProviderSelectionStore {
    private const val PREFS_NAME = "qianyan_provider_selection"
    private const val KEY = "provider"

    /** 读取当前选中 Provider；无记录 / 非法值回退 [ProviderType.MOCK]。 */
    fun read(context: Context): ProviderType {
        val name = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY, null)
        return runCatching { ProviderType.valueOf(name ?: "") }.getOrDefault(ProviderType.MOCK)
    }

    /** 写入当前选中 Provider（普通 SharedPreferences 即可，非机密）。 */
    fun write(context: Context, provider: ProviderType) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY, provider.name).apply()
    }
}