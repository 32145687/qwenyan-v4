package com.qianyan.app.desktop.di

import com.qianyan.application.di.ApplicationContainer
import com.qianyan.app.desktop.adapter.DesktopProviderAssembler
import com.qianyan.app.desktop.adapter.FileProviderCredentialStore
import com.qianyan.provider.ProviderAssembler
import com.qianyan.provider.BalanceQuery
import com.qianyan.provider.ProviderConfiguration
import com.qianyan.provider.ProviderType
import com.qianyan.provider.ReasoningEffort
import com.qianyan.provider.UsageSink
import com.qianyan.provider.impl.DefaultProviderAssembler
import com.qianyan.provider.impl.UsageRecordingAssembler
import com.qianyan.provider.impl.transport.JdkLlmHttpClient
import com.qianyan.provider.impl.transport.LlmHttpClient
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
class DesktopGraph @JvmOverloads private constructor(
    val appDir: Path,
    val credentialStore: FileProviderCredentialStore,
    private val httpClient: LlmHttpClient = JdkLlmHttpClient(),
) {
    /** 当前生效容器（Provider 切换时整体重建；数据在同一个 SQLite 文件中，不丢）。 */
    @Volatile
    var container: ApplicationContainer
        private set

    private var currentHandle: QianyanDbHandle? = null

    private val selectionFile: Path = appDir.resolve("provider-selection.properties")
    private val uiPreferences = DesktopUiPreferences(appDir.resolve("ui-preferences.properties"))

    /** 自定义模型清单（与 provider-selection 同族配置，不进 SQLite）。 */
    val modelCatalog = CustomModelCatalog(appDir.resolve("models.properties"))

    /** 本地用量账本（旁路统计；`<appDir>/usage.json`，不进 SQLite）。 */
    val usageLedger = UsageLedger(appDir.resolve("usage.json"))

    private val _customModelsRevision = MutableStateFlow(0)

    /** 清单变更版本：UI 收集它以在增删改后刷新。 */
    val customModelsRevision = _customModelsRevision.asStateFlow()

    private val _provider = MutableStateFlow(readSelection())
    val provider = _provider.asStateFlow()

    /** 当前模型配置（可被用户覆盖；null = 用 Provider 默认模型）。 */
    private val _modelId = MutableStateFlow<String?>(null)
    private val _baseUrl = MutableStateFlow<String?>(null)

    /** 当前思考强度（读取失败回落 HIGH；null 仅表示请求不带参数，桌面配置始终有值）。 */
    private val _reasoningEffort = MutableStateFlow(readEffort())
    val effectiveReasoningEffort = _reasoningEffort.asStateFlow()

    fun reasoningEffort(): ReasoningEffort = _reasoningEffort.value

    /** 当前生效的模型 id（用户设置优先，否则该 Provider 的默认模型）。 */
    fun modelId(): String = _modelId.value ?: defaultModelId(_provider.value)

    /** Compose 可观察的当前生效模型 id；Provider / 模型配置变化时更新。 */
    private val _effectiveModel = MutableStateFlow("")
    val effectiveModel = _effectiveModel.asStateFlow()

    private fun refreshEffectiveModel() {
        _effectiveModel.value = modelId()
    }

    /** 当前生效的端点（空 = 用 Provider 官方默认 endpoint）。 */
    fun baseUrl(): String = _baseUrl.value ?: ""

    /** Provider 切换失败等信息（UI 顶部提示条消费）。 */
    val providerMessage = MutableStateFlow<String?>(null)

    private lateinit var assembler: ProviderAssembler
    private lateinit var configuration: ProviderConfiguration

    init {
        assembler = DesktopProviderAssembler(DefaultProviderAssembler(credentialStore, httpClient))
        val stored = readStored()
        // 空串 = 未设置（null 语义 = 用该 Provider 的默认模型 / 官方默认 endpoint），不能赋成 ""。
        _modelId.value = stored["model"]?.takeIf { it.isNotBlank() }
        _baseUrl.value = stored["baseUrl"]?.takeIf { it.isNotBlank() }
        _reasoningEffort.value = parseEffort(stored["effort"])
        configuration = buildConfiguration(_provider.value)
        container = build(_provider.value)
        refreshEffectiveModel()
    }

    /** 按 Provider + 用户模型/端点设置构造配置（Key 不经此对象）。 */
    private fun buildConfiguration(type: ProviderType): ProviderConfiguration {
        val model = (_modelId.value ?: defaultModelId(type)).takeIf { it.isNotBlank() }
        val url = _baseUrl.value?.trim()?.takeIf { it.isNotBlank() }
        return ProviderConfiguration(
            provider = type,
            model = model?.let { com.qianyan.provider.ModelProfile(id = it, label = it) },
            baseUrl = url,
            // 离线示意不携带任何思考参数；真实 Provider 用用户选择（默认 high）。
            reasoningEffort = if (type == ProviderType.MOCK) null else _reasoningEffort.value,
        )
    }

    private fun build(type: ProviderType): ApplicationContainer {
        val dbPath = appDir.resolve("qianyan.db")
        val url = "jdbc:sqlite:${dbPath.toAbsolutePath().normalize()}"
        val handle = QianyanDbFactory.open(url)
        currentHandle?.driver?.close()
        currentHandle = handle
        // 记账装饰器包在装配口内侧：所有 chat 成功路径同一处记录，失败路径不记（A-14）。
        val recording = UsageRecordingAssembler(
            delegate = assembler,
            sink = UsageSink { provider, modelId, usage -> usageLedger.record(provider, modelId, usage) },
            modelId = configuration.model?.id ?: modelId(),
        )
        return ApplicationContainer.fromDriver(handle.driver, recording, configuration)
    }

    /**
     * 余额查询接缝：MOCK 与未配置 Key 的 Provider 返回 null（不构造请求、不触网）。
     *
     * 返回的是按当前配置新装配的网关；调用方负责把网络阻塞放到后台线程。
     */
    fun balanceQuery(type: ProviderType): BalanceQuery? {
        if (type == ProviderType.MOCK || !isConfigured(type)) return null
        return runCatching { assembler.assemble(buildConfiguration(type)) as? BalanceQuery }.getOrNull()
    }

    private fun readStored(): Map<String, String> = runCatching {
        val p = Properties()
        if (selectionFile.toFile().exists()) selectionFile.toFile().inputStream().use { p.load(it) }
        mapOf(
            "selected" to (p.getProperty("selected") ?: ProviderType.MOCK.name),
            "model" to (p.getProperty("model") ?: ""),
            "baseUrl" to (p.getProperty("baseUrl") ?: ""),
            "effort" to (p.getProperty("effort") ?: ""),
        )
    }.getOrDefault(mapOf("selected" to ProviderType.MOCK.name, "model" to "", "baseUrl" to "", "effort" to ""))

    private fun parseEffort(raw: String?): ReasoningEffort =
        raw?.takeIf { it.isNotBlank() }
            ?.let { name -> ReasoningEffort.entries.firstOrNull { it.name == name } }
            ?: ReasoningEffort.HIGH

    private fun readEffort(): ReasoningEffort = parseEffort(readStored()["effort"])

    private fun readSelection(): ProviderType =
        runCatching { ProviderType.valueOf(readStored()["selected"] ?: ProviderType.MOCK.name) }
            .getOrDefault(ProviderType.MOCK)

    private fun persist(type: ProviderType) {
        val p = Properties().apply {
            setProperty("selected", type.name)
            _modelId.value?.takeIf { it.isNotBlank() }?.let { setProperty("model", it) }
            _baseUrl.value?.takeIf { it.isNotBlank() }?.let { setProperty("baseUrl", it) }
            setProperty("effort", _reasoningEffort.value.name)
        }
        selectionFile.toFile().outputStream().use { p.store(it, "Qianyan Desktop provider selection") }
    }

    /** 各 Provider 的官方默认模型 id（仅作预填，用户可改成任意模型）。 */
    fun defaultModelId(type: ProviderType): String = when (type) {
        ProviderType.MOCK -> "mock-v1"
        ProviderType.DEEPSEEK -> "deepseek-v4-flash"
        ProviderType.MIMO -> "mimo-v2.6-pro"
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
        reasoningEffort: ReasoningEffort? = null,
    ): Boolean {
        val previousModel = _modelId.value
        val previousBaseUrl = _baseUrl.value
        val previousEffort = _reasoningEffort.value
        if (apiKey.isNotBlank()) credentialStore.setApiKey(type, apiKey.trim())
        if (modelId != null) _modelId.value = modelId.trim().ifBlank { null }
        if (baseUrl != null) _baseUrl.value = baseUrl.trim().ifBlank { null }
        if (reasoningEffort != null) _reasoningEffort.value = reasoningEffort

        val cfg = buildConfiguration(type)
        val result = runCatching { assembler.assemble(cfg) }
        return result.fold(
            onSuccess = {
                persist(type)
                _provider.value = type
                configuration = cfg
                container = build(type)
                refreshEffectiveModel()
                providerMessage.value = null
                true
            },
            onFailure = {
                // 切换失败不得留下半改的模型/端点/强度，否则 UI 会显示一个并未生效的配置。
                _modelId.value = previousModel
                _baseUrl.value = previousBaseUrl
                _reasoningEffort.value = previousEffort
                refreshEffectiveModel()
                providerMessage.value = "无法切换到 ${type.name}：${it.message ?: "凭证缺失"}（当前仍使用 ${_provider.value.name}）"
                false
            },
        )
    }

    fun selectProvider(type: ProviderType) {
        saveProviderConfig(type)
    }

    // ---- Provider 设置（PC 设置页消费；API Key 只经 ProviderCredentialStore，不进领域模型）----

    /** 读取/保存主题偏好（纯显示偏好；文件落在应用数据目录）。 */
    fun readThemeMode(): String? = uiPreferences.readThemeMode()

    fun saveThemeMode(mode: String): Boolean = uiPreferences.writeThemeMode(mode)

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
        ProviderType.MIMO -> "MiMo 真实网关 · 需要 API Key。模型 ID 可自由填写（默认 mimo-v2.6-pro）。"
    }

    // ---- 自定义模型清单（UI 只经这些方法读写，避免两份表）----

    fun customModels(): List<CustomModel> = modelCatalog.read()

    fun customModels(type: ProviderType): List<CustomModel> = modelCatalog.candidates(type)

    fun addCustomModel(model: CustomModel): Boolean = modelCatalog.add(model).also { if (it) bumpCustomModels() }

    fun updateCustomModel(original: CustomModel, updated: CustomModel): Boolean =
        modelCatalog.update(original, updated).also { if (it) bumpCustomModels() }

    fun removeCustomModel(provider: ProviderType, modelId: String): Boolean =
        modelCatalog.remove(provider, modelId).also { if (it) bumpCustomModels() }

    fun setCustomModelEnabled(provider: ProviderType, modelId: String, enabled: Boolean): Boolean =
        modelCatalog.setEnabled(provider, modelId, enabled).also { if (it) bumpCustomModels() }

    private fun bumpCustomModels() {
        _customModelsRevision.value += 1
    }

    /** 当前生效的 Provider 配置（测试与设置页只读投影）。 */
    internal fun currentConfiguration(): ProviderConfiguration = configuration

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