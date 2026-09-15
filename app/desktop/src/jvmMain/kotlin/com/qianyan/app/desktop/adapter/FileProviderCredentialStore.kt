package com.qianyan.app.desktop.adapter

import com.qianyan.provider.ProviderCredentialStore
import com.qianyan.provider.ProviderType
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

/**
 * PC 凭证存储（Desktop Adapter）：以本地文件持有各 Provider 的 API Key。
 *
 * 对应 Android 的 AndroidKeystoreProviderCredentialStore（P12.5-M02）桌面侧等价物。
 * MVP 取舍：单用户桌面场景，明文落盘于应用数据目录（%APPDATA%/Qianyan/credentials.properties），
 * 文件权限交给 OS 用户隔离；升级为 DPAPI/Keystore 加密属后续 P20 加固项。
 */
class FileProviderCredentialStore(appDir: Path) : ProviderCredentialStore {

    private val file: Path = appDir.resolve("credentials.properties")

    private fun load(): Properties = Properties().apply {
        if (Files.exists(file)) file.toFile().inputStream().use { load(it) }
    }

    private fun store(p: Properties) {
        file.toFile().outputStream().use { p.store(it, "Qianyan Desktop provider credentials (local only)") }
    }

    override fun getApiKey(provider: ProviderType): String? = load().getProperty(provider.name)

    override fun setApiKey(provider: ProviderType, apiKey: String) {
        val p = load().apply { setProperty(provider.name, apiKey) }
        store(p)
    }

    override fun clearApiKey(provider: ProviderType) {
        val p = load().apply { remove(provider.name) }
        store(p)
    }
}
