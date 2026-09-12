package com.qianyan.app.android.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.qianyan.provider.ProviderCredentialStore
import com.qianyan.provider.ProviderType
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Android 安全凭证存储（P12.5-M02：基于 Android Keystore 的 AES/GCM 加密存储）。
 *
 * 目标架构：
 * ```
 * Application → ProviderCredentialStore(:provider:api) → Android secure implementation(app:android)
 * ```
 *
 * 安全策略（**禁止明文持久化**）：
 *  - API Key 用 Android Keystore（[KEYSTORE_PROVIDER]）中的 AES 密钥加密后，以「iv:ciphertext」的
 *    Base64 编码写入本应用私有 [android.content.SharedPreferences]；
 *  - Keystore 主密钥不落盘（受 Android 系统保护），且不经过任何 SQLite / 普通文件 / DataStore 明文；
 *  - `getApiKey` 对解密失败/条目损坏视作未配置（返回 null），并清除损坏条目，避免保留无意义密文；
 *  - Keystore AES/GCM 需要 API 23+，本项目 minSdk 24 满足，无需额外第三方库。
 *
 * 约束：本类位于 app:android（Android 安全实现），:provider / :application 只依赖 `ProviderCredentialStore`
 * 抽象，不直接触碰 Keystore / SharedPreferences。
 */
class AndroidKeystoreProviderCredentialStore(context: Context) : ProviderCredentialStore {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val keyStore: KeyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }

    override fun getApiKey(provider: ProviderType): String? {
        val blob = prefs.getString(prefsKey(provider), null) ?: return null
        return try {
            decrypt(blob)
        } catch (_: Exception) {
            // 无法解密（主密钥失效 / 密文被篡改）→ 按未配置处理，并清除损坏条目。
            prefs.edit().remove(prefsKey(provider)).apply()
            null
        }
    }

    override fun setApiKey(provider: ProviderType, apiKey: String) {
        require(apiKey.isNotBlank()) { "API Key 不能为空" }
        val blob = encrypt(obtainOrCreateKey(), apiKey)
        prefs.edit().putString(prefsKey(provider), blob).apply()
    }

    override fun clearApiKey(provider: ProviderType) {
        prefs.edit().remove(prefsKey(provider)).apply()
    }

    /** 加密：AES/GCM 生成随机 IV，返回 `Base64(iv):Base64(ciphertext)`。 */
    private fun encrypt(key: SecretKey, plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val ciphertext = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(ciphertext, Base64.NO_WRAP)
    }

    /** 解密上面 [encrypt] 产生的 blob；失败抛异常（由调用方兜底）。 */
    private fun decrypt(blob: String): String {
        val sep = blob.indexOf(':')
        require(sep > 0)
        val iv = Base64.decode(blob.substring(0, sep), Base64.NO_WRAP)
        val ciphertext = Base64.decode(blob.substring(sep + 1), Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, obtainOrCreateKey(), GCMParameterSpec(TAG_LENGTH_BITS, iv))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    /** 获取（或创建）Keystore 中的 AES 主密钥。 */
    private fun obtainOrCreateKey(): SecretKey {
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }

    private fun prefsKey(provider: ProviderType): String = "api_key.${provider.name}"

    private companion object {
        const val PREFS_NAME = "qianyan_provider_credentials"
        const val KEY_ALIAS = "qianyan_provider_key"
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_LENGTH_BITS = 128
    }
}