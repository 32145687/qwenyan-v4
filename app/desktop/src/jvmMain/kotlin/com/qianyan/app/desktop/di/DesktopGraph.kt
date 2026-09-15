package com.qianyan.app.desktop.di

import com.qianyan.application.di.ApplicationContainer
import com.qianyan.app.desktop.adapter.DesktopProviderAssembler
import com.qianyan.app.desktop.adapter.FileProviderCredentialStore
import com.qianyan.model.BaseNovelId
import com.qianyan.model.NovelId
import com.qianyan.model.core.VariantContext
import com.qianyan.provider.ProviderAssembler
import com.qianyan.provider.ProviderConfiguration
import com.qianyan.provider.ProviderCredentialStore
import com.qianyan.provider.ProviderType
import com.qianyan.provider.impl.DefaultProviderAssembler
import com.qianyan.storage.db.QianyanDbFactory
import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Properties

/**
 * PC 桌面装配根（Desktop Adapter，P12.5 Android 装配链的桌面镜像）。
 *
 * 职责：
 *  1. JVM SQLite 初始化：`QianyanDbFactory.open("jdbc:sqlite:<appDir>/qianyan.db")`
 *  2. PC 凭证存储：[FileProviderCredentialStore]（应用目录本地文件，明文——桌面单用户场景 MVP 取舍）
 *  3. Provider 装配：`ProviderConfiguration + ProviderCredentialStore → DefaultProviderAssembler → LLMGateway`
 *     → `ApplicationContainer.fromDriver(driver, assembler, configuration)`（与 Android QianyanApplication 同链路）
 *  4. Provider 切换：装配成功才提交（缺 API Key → 拒绝切换并给出提示，不崩溃）
 *
 * 约束：只通过 [ApplicationContainer] 访问能力；不实例化 Sqlite 仓储；不实现业务逻辑。
 * qwenyan-v4 核心模块（:application / :storage / :provider 等）在本侧零修改。
 */
class DesktopGraph private constructor(
    val appDir: Path,
    val credentialStore: FileProviderCredentialStore,
) {
    /** 当前生效容器（Provider 切换时整体重建；数据在同一个 SQLite 文件中，不丢）。 */
    @Volatile
    var container: ApplicationContainer
        private set

    private var currentHandle: com.qianyan.storage.db.QianyanDbHandle? = null

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

    init {
        assembler = DesktopProviderAssembler(DefaultProviderAssembler(credentialStore))
        val stored = readStored()
        _modelId.value = stored["model"]
        _baseUrl.value = stored["baseUrl"]
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
        return ApplicationContainer.fromDriver(handle.driver, assembler, configuration)
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

    // ---- 书架视图偏好（PC 侧本地；不动数据库）----

    private val shelfFile: Path get() = appDir.resolve("shelf.properties")

    private val _hidden = MutableStateFlow(readHidden())

    /** 已从书架隐藏的作品 id。 */
    fun hiddenNovelIds(): Set<String> = _hidden.value

    fun hideNovel(novelId: String) {
        _hidden.value = _hidden.value + novelId
        persistHidden()
    }

    fun unhideNovel(novelId: String) {
        _hidden.value = _hidden.value - novelId
        persistHidden()
    }

    /**
     * 作品删除（含删除前自动备份）。driver 会随 Provider 切换重建，故每次取新实例。
     * 删除语义：物理删除作品及其全部关联数据（DatabaseInitializer 已移除 Original 删除保护；
     * Original 的**改写**保护仍然保留）。
     */
    fun deletion(): com.qianyan.app.desktop.adapter.DesktopNovelDeletion =
        com.qianyan.app.desktop.adapter.DesktopNovelDeletion(
            db = (currentHandle ?: error("数据库尚未初始化")).db,
            driver = (currentHandle ?: error("数据库尚未初始化")).driver,
            appDir = appDir,
        )

    private fun readHidden(): Set<String> = runCatching {
        val p = Properties()
        if (shelfFile.toFile().exists()) shelfFile.toFile().inputStream().use { p.load(it) }
        (p.getProperty("hidden") ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }.getOrDefault(emptySet())

    private fun persistHidden() {
        val p = Properties().apply { setProperty("hidden", _hidden.value.joinToString(",")) }
        shelfFile.toFile().outputStream().use { p.store(it, "Qianyan Desktop shelf view preference") }
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

/** Original 小说上下文便捷构造（scope=ORIGINAL、variantId=null）。 */
fun originalContext(novelId: NovelId): VariantContext = VariantContext(baseNovelId = BaseNovelId(novelId.value))
