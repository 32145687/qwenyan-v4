package com.qianyan.app.android

import android.app.Application
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.qianyan.application.di.ApplicationContainer
import com.qianyan.app.android.di.AndroidLlmHttpClient
import com.qianyan.app.android.security.AndroidKeystoreProviderCredentialStore
import com.qianyan.app.android.security.ProviderSelectionStore
import com.qianyan.provider.LLMGateway
import com.qianyan.provider.ProviderConfiguration
import com.qianyan.provider.ProviderType
import com.qianyan.provider.impl.DefaultProviderAssembler
import com.qianyan.storage.db.DatabaseInitializer
import com.qianyan.storage.db.QianyanDb
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Android 应用级 DI 根（P7.3 + P12.5 Provider 接入）。
 *
 * P12.5-M04（真实 Provider 接线 seam）：装配链不再硬编码 MockLLMGateway，改为
 * `ProviderConfiguration + ProviderCredentialStore` → [DefaultProviderAssembler] → [LLMGateway] → ApplicationContainer。
 *  - 当前选中 Provider 持久化在 [ProviderSelectionStore]（非机密，默认 MOCK）；
 *  - API Key 安全保存在 [AndroidKeystoreProviderCredentialStore]（Android Keystore AES/GCM），永不明文；
 *  - 切换到真实 Provider 但尚未配置 Key → 拒绝切换并返回 false，避免启动即 ProviderException.CredentialMissing 崩溃；
 *  - Mock 无需 Key，测试 / 本地仍可离线运行（M04：不要求真实 API Key 测试）。
 *
 * 装配链：
 * ```
 * QianyanApplication
 *       │  AndroidSqliteDriver（context.filesDir/qianyan.db）
 *       │  DatabaseInitializer.initializeDatabase(driver)   // 幂等建表 + 守卫触发器
 *       │  AndroidKeystoreProviderCredentialStore           // M02：Keystore 加密存储 API Key
 *       │  DefaultProviderAssembler → LLMGateway（MOCK / DEEPSEEK / MIMO）
 *       ▼
 * ApplicationContainer.fromDriver(driver, providerAssembler, configuration)
 *       ▼
 * Application UseCases
 * ```
 *
 * 约束：
 *  - 只通过 `ApplicationContainer`（:application 组合根）访问能力；
 *  - 不直接实例化 Sqlite 仓储、不执行 SQL、不调用 QianyanDbFactory.open（JDBC 专用）；
 *  - 不在此实现业务逻辑；容器只创建一次，供后续 Activity / ViewModel 使用。
 */
class QianyanApplication : Application() {

    /** 应用级 ApplicationContainer（只创建一次，外部只读；Provider 变更时重建）。 */
    lateinit var container: ApplicationContainer
        private set

    /** 安全凭证存储（P12.5-M02）：读写各 Provider 的 API Key，Android Keystore 加密落盘。 */
    lateinit var credentialStore: AndroidKeystoreProviderCredentialStore
        private set

    private lateinit var driver: AndroidSqliteDriver

    private val _provider = MutableStateFlow(ProviderType.MOCK)

    /** 当前选中 Provider（设置页消费；写操作统一走 [setProvider]）。 */
    val provider: StateFlow<ProviderType> get() = _provider.asStateFlow()

    override fun onCreate() {
        super.onCreate()

        credentialStore = AndroidKeystoreProviderCredentialStore(this)
        _provider.value = ProviderSelectionStore.read(this)

        driver = AndroidSqliteDriver(
            schema = QianyanDb.Schema,
            context = this,
            name = DB_NAME,
        )
        // 幂等初始化：仅首次建表，后续启动跳过。
        DatabaseInitializer.initializeDatabase(driver)

        rebuild()
    }

    /** 切换到某 Provider；真实 Provider 需先配置 API Key（否则返回 false，保持原选择不变）。 */
    fun setProvider(provider: ProviderType): Boolean {
        if (provider != ProviderType.MOCK && credentialStore.getApiKey(provider).isNullOrBlank()) return false
        ProviderSelectionStore.write(this, provider)
        _provider.value = provider
        rebuild()
        return true
    }

    /** 保存某 Provider 的 API Key（合规 Keystore 写入）。若为当前选中 Provider，重建容器使网关生效。 */
    fun saveApiKey(provider: ProviderType, apiKey: String) {
        credentialStore.setApiKey(provider, apiKey)
        if (provider == _provider.value) rebuild()
    }

    /** 为指定 Provider 组装真实网关；真实 Provider 缺 Key 时抛 ProviderException.CredentialMissing。 */
    fun assembleGateway(provider: ProviderType): LLMGateway =
        DefaultProviderAssembler(credentialStore, AndroidLlmHttpClient()).assemble(ProviderConfiguration(provider))

    /** 按当前选中 Provider 重建容器（每次 assemble 从 store 实时读最新 credential）。 */
    private fun rebuild() {
        val assembler = DefaultProviderAssembler(credentialStore, AndroidLlmHttpClient())
        val config = ProviderConfiguration(_provider.value)
        container = ApplicationContainer.fromDriver(driver, assembler, config)
    }

    private companion object {
        /** 应用私有目录下的数据库文件名（context.filesDir）。 */
        const val DB_NAME: String = "qianyan.db"
    }
}