package com.qianyan.app.desktop.di

import com.qianyan.application.di.ApplicationContainer
import com.qianyan.app.desktop.adapter.DesktopProviderAssembler
import com.qianyan.app.desktop.adapter.FileProviderCredentialStore
import com.qianyan.provider.ProviderAssembler
import com.qianyan.provider.ProviderConfiguration
import com.qianyan.provider.ProviderType
import com.qianyan.provider.impl.DefaultProviderAssembler
import com.qianyan.runtime.dsh.DshRuntimeClient
import com.qianyan.storage.db.QianyanDbFactory
import com.qianyan.storage.db.QianyanDbHandle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Properties

/**
 * PC 桌面装配根（Desktop Adapter；P12.5 Android 装配链的桌面镜像）。
 *
 * 装配链（P20-PC1 收口）：
 * ```
 * DesktopGraph
 *   → QianyanDbFactory.open("jdbc:sqlite:%APPDATA%/Qianyan/qianyan.db")   // SQLite JDBC
 *   → DatabaseInitializer（schema v1 → v17，含 ReadingProgress）
 *   → FileProviderCredentialStore（PC 凭证，明文落盘）
 *   → DesktopProviderAssembler(DefaultProviderAssembler)
 *   → ApplicationContainer.fromDriver(driver, assembler, configuration)   // 与 Android 同一契约
 * ```
 *
 * 约束：只通过 [ApplicationContainer] 暴露的 UseCase 访问能力；不实现业务逻辑，不改共享核心。
 * Provider 切换会整体重建容器（数据在同一个 SQLite 文件中，不丢）。
 */
class DesktopGraph private constructor(
    val appDir: Path,
    val credentialStore: FileProviderCredentialStore,
) {
    /** 当前生效容器（Provider 切换时整体重建；数据在同一个 SQLite 文件中，不丢）。 */
    @Volatile
    var container: ApplicationContainer
        private set

    private var currentHandle: QianyanDbHandle? = null

    private val selectionFile: Path = appDir.resolve("provider-selection.properties")

    private val _provider = MutableStateFlow(readSelection())
    val provider = _provider.asStateFlow()

    /** 当前模型配置（可被用户覆盖；null = 用 Provider 默认模型）。 */
    private val _modelId = MutableStateFlow<String?>(null)
    private val _baseUrl = MutableStateFlow<String?>(null)

    /** 当前生效的模型 id（用户设置优先，否则该 Provider 的默认模型）。 */
    fun modelId(): String = _modelId.value ?: defaultModelId(_provider.value)

    /** 当前生效的端点（空 = 用 Provider 官方默认 endpoint）。 */
    fun baseUrl(): String = _baseUrl.value ?: ""

    /** Provider 切换失败等信息（UI 顶部提示条消费）。 */
    val providerMessage = MutableStateFlow<String?>(null)

    private lateinit var assembler: ProviderAssembler
    private lateinit var configuration: ProviderConfiguration

    /**
     * I1 · 外部 Agent Runtime Adapter（**组合根唯一构造点**）。
     *
     * 与 Provider 同构：UI 不得 new Adapter，Application 只见 `:runtime:api` 契约；
     * 这里构造具体实现（DSH Adapter）并按契约注入容器。构造本身**不启动进程**（懒启动）。
     */
    private val runtimeGateway = DshRuntimeClient()

    init {
        assembler = DesktopProviderAssembler(DefaultProviderAssembler(credentialStore))
        val stored = readStored()
        // 空串 = 未设置（null 语义 = 用该 Provider 的默认模型 / 官方默认 endpoint），不能赋成 ""。
        _modelId.value = stored["model"]?.takeIf { it.isNotBlank() }
        _baseUrl.value = stored["baseUrl"]?.takeIf { it.isNotBlank() }
        configuration = buildConfiguration(_provider.value)
        container = build(_provider.value)
    }

    /** 按 Provider + 用户模型/端点设置构造配置（Key 不经此对象）。 */
    private fun buildConfiguration(type: ProviderType): ProviderConfiguration {
        val model = (_modelId.value ?: defaultModelId(type)).takeIf { it.isNotBlank() }
        val url = _baseUrl.value?.trim()?.takeIf { it.isNotBlank() }
        return ProviderConfiguration(
            provider = type,
            model = model?.let { com.qianyan.provider.ModelProfile(id = it, label = it) },
            baseUrl = url,
        )
    }

    private fun build(type: ProviderType): ApplicationContainer {
        val dbPath = appDir.resolve("qianyan.db")
        val url = "jdbc:sqlite:${dbPath.toAbsolutePath().normalize()}"
        val handle = QianyanDbFactory.open(url)
        currentHandle?.driver?.close()
        currentHandle = handle
        return ApplicationContainer.fromDriver(handle.driver, assembler, configuration, runtimeGateway = runtimeGateway)
    }

    private fun readStored(): Map<String, String> = runCatching {
        val p = Properties()
        if (selectionFile.toFile().exists()) selectionFile.toFile().inputStream().use { p.load(it) }
        mapOf(
            "selected" to (p.getProperty("selected") ?: ProviderType.MOCK.name),
            "model" to (p.getProperty("model") ?: ""),
            "baseUrl" to (p.getProperty("baseUrl") ?: ""),
        )
    }.getOrDefault(mapOf("selected" to ProviderType.MOCK.name, "model" to "", "baseUrl" to ""))

    private fun readSelection(): ProviderType =
        runCatching { ProviderType.valueOf(readStored()["selected"] ?: ProviderType.MOCK.name) }
            .getOrDefault(ProviderType.MOCK)

    private fun persist(type: ProviderType) {
        val p = Properties().apply {
            setProperty("selected", type.name)
            _modelId.value?.takeIf { it.isNotBlank() }?.let { setProperty("model", it) }
            _baseUrl.value?.takeIf { it.isNotBlank() }?.let { setProperty("baseUrl", it) }
        }
        selectionFile.toFile().outputStream().use { p.store(it, "Qianyan Desktop provider selection") }
    }

    /** 各 Provider 的官方默认模型 id（仅作预填，用户可改成任意模型）。 */
    fun defaultModelId(type: ProviderType): String = when (type) {
        ProviderType.MOCK -> "mock-v1"
        ProviderType.DEEPSEEK -> "deepseek-v4-flash"
        ProviderType.MIMO -> "mimo-v2.5-pro"
    }

    /** 各 Provider 的官方默认 endpoint（仅作说明；留空即用此默认）。 */
    fun defaultBaseUrl(type: ProviderType): String = when (type) {
        ProviderType.MOCK -> ""
        ProviderType.DEEPSEEK -> "https://api.deepseek.com"
        ProviderType.MIMO -> "https://api.xiaomimimo.com/v1"
    }

    /**
     * 切换 Provider（可选覆盖模型 id 与端点）。
     * 真实 Provider 缺 API Key → 装配失败 → 拒绝切换并给出提示（与 Android P12.5-M04 行为一致）。
     */
    fun saveProviderConfig(
        type: ProviderType,
        apiKey: String = "",
        modelId: String? = null,
        baseUrl: String? = null,
    ): Boolean {
        if (apiKey.isNotBlank()) credentialStore.setApiKey(type, apiKey.trim())
        if (modelId != null) _modelId.value = modelId.trim().ifBlank { null }
        if (baseUrl != null) _baseUrl.value = baseUrl.trim().ifBlank { null }

        val cfg = buildConfiguration(type)
        val result = runCatching { assembler.assemble(cfg) }
        return result.fold(
            onSuccess = {
                persist(type)
                _provider.value = type
                configuration = cfg
                container = build(type)
                providerMessage.value = null
                true
            },
            onFailure = {
                providerMessage.value = "无法切换到 ${type.name}：${it.message ?: "凭证缺失"}（当前仍使用 ${_provider.value.name}）"
                false
            },
        )
    }

    fun selectProvider(type: ProviderType) {
        saveProviderConfig(type)
    }

    // ---- Provider 设置（PC 设置页消费；API Key 只经 ProviderCredentialStore，不进领域模型）----

    /** 读取已保存的 API Key（用于设置页回显是否已配置；不显示明文）。 */
    fun isConfigured(type: ProviderType): Boolean =
        !credentialStore.getApiKey(type).isNullOrBlank()

    /** 保存 API Key 并尝试切换到该 Provider（装配失败则保留原 Provider 并给出提示）。 */
    fun saveApiKeyAndSelect(type: ProviderType, apiKey: String): Boolean {
        if (apiKey.isNotBlank()) credentialStore.setApiKey(type, apiKey.trim())
        val before = _provider.value
        selectProvider(type)
        return _provider.value == type || (type == before)
    }

    /** 清除某 Provider 的 API Key。 */
    fun clearApiKey(type: ProviderType) {
        credentialStore.clearApiKey(type)
        if (type != ProviderType.MOCK) providerMessage.value = "${type.name} 的 API Key 已清除"
    }

    /** 配置校验（确定性、不发网络请求）：返回问题列表，空 = 合法。 */
    fun validate(type: ProviderType): List<String> = assembler.validate(ProviderConfiguration(type))

    /** 设置页需展示的 Provider 说明。 */
    fun describe(type: ProviderType): String = when (type) {
        ProviderType.MOCK -> "离线示意稿 · 不需要 API Key。用于无网络 / 无凭证时走通完整创作流程（Task / Workflow / 持久化仍为真实实现）。"
        ProviderType.DEEPSEEK -> "DeepSeek 真实网关 · 需要 API Key。模型 ID 可自由填写（默认 deepseek-v4-flash）。"
        ProviderType.MIMO -> "MiMo 真实网关 · 需要 API Key。模型 ID 可自由填写（默认 mimo-v2.5-pro）。"
    }

    companion object {
        /** 应用数据目录：%APPDATA%/Qianyan（回退 ~/.qianyan）。 */
        fun appDataDir(): Path {
            val base = System.getenv("APPDATA") ?: System.getProperty("user.home")
            return Paths.get(base, if (System.getenv("APPDATA") != null) "Qianyan" else ".qianyan").apply {
                Files.createDirectories(this)
            }
        }

        fun create(): DesktopGraph = DesktopGraph(appDataDir(), FileProviderCredentialStore(appDataDir()))
    }
}