package com.qianyan.app.desktop.di

import com.qianyan.provider.ModelProfile
import com.qianyan.provider.ProviderType
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

/** 一条自定义模型清单条目（纯桌面配置，不是业务事实，不进 SQLite）。 */
data class CustomModel(
    val provider: ProviderType,
    val modelId: String,
    val label: String = modelId,
    val enabled: Boolean = true,
)

/**
 * 自定义模型清单：`%APPDATA%/Qianyan/models.properties`。
 *
 * 与 provider-selection.properties 同族，只存配置；mock-v1 不进入候选（MOCK Provider 本身仍保留）。
 */
class CustomModelCatalog(private val file: Path) {

    /** 读取清单；文件缺失 / 解析失败回落默认条目，未知 Provider 条目跳过。 */
    fun read(): List<CustomModel> {
        if (!file.toFile().isFile) return defaults()
        val properties = Properties()
        return runCatching {
            file.toFile().inputStream().use { properties.load(it) }
            parse(properties)
        }.getOrElse { defaults() }
    }

    /** 某 Provider 的可用候选（启停开关打开，且不含 mock-v1）。 */
    fun candidates(type: ProviderType): List<CustomModel> =
        read().filter { it.provider == type && it.enabled && it.modelId != ModelProfile.MOCK.id }

    fun write(models: List<CustomModel>): Boolean = runCatching {
        val clean = models
            .filterNot { it.modelId == ModelProfile.MOCK.id }
            .distinctBy { it.provider to it.modelId }
        Files.createDirectories(file.toAbsolutePath().parent)
        val properties = Properties().apply {
            setProperty(KEY_COUNT, clean.size.toString())
            clean.forEachIndexed { index, model ->
                val i = index + 1
                setProperty("model.$i.provider", model.provider.name)
                setProperty("model.$i.id", model.modelId)
                setProperty("model.$i.label", model.label)
                setProperty("model.$i.enabled", model.enabled.toString())
            }
        }
        file.toFile().outputStream().use { properties.store(it, "Qianyan Desktop custom model list") }
        true
    }.getOrDefault(false)

    fun add(model: CustomModel): Boolean = write(read() + model)

    fun update(original: CustomModel, updated: CustomModel): Boolean =
        write(read().map { if (it == original) updated else it })

    fun remove(provider: ProviderType, modelId: String): Boolean =
        write(read().filterNot { it.provider == provider && it.modelId == modelId })

    fun setEnabled(provider: ProviderType, modelId: String, enabled: Boolean): Boolean =
        write(read().map { if (it.provider == provider && it.modelId == modelId) it.copy(enabled = enabled) else it })

    private fun parse(properties: Properties): List<CustomModel> {
        val count = properties.getProperty(KEY_COUNT)?.toIntOrNull() ?: return defaults()
        if (count <= 0) return emptyList()
        return (1..count).mapNotNull { i ->
            val provider = properties.getProperty("model.$i.provider")
                ?.let { raw -> ProviderType.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } }
                ?: return@mapNotNull null
            val modelId = properties.getProperty("model.$i.id")?.trim()?.takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            CustomModel(
                provider = provider,
                modelId = modelId,
                label = properties.getProperty("model.$i.label")?.trim()?.takeIf { it.isNotEmpty() } ?: modelId,
                enabled = properties.getProperty("model.$i.enabled")?.toBooleanStrictOrNull() ?: true,
            )
        }.filterNot { it.modelId == ModelProfile.MOCK.id }
    }

    companion object {
        private const val KEY_COUNT = "model.count"

        /** 默认条目：仅真实 Provider；mock-v1 不进入清单。 */
        fun defaults(): List<CustomModel> = listOf(
            CustomModel(ProviderType.DEEPSEEK, "deepseek-v4-flash", "DeepSeek V4 Flash"),
            CustomModel(ProviderType.DEEPSEEK, "deepseek-v4-pro", "DeepSeek V4 Pro"),
            CustomModel(ProviderType.MIMO, ModelProfile.MIMO_V2_6_PRO.id, "MiMo V2.6 Pro"),
            CustomModel(ProviderType.MIMO, ModelProfile.MIMO_V2_6_FLASH.id, "MiMo V2.6 Flash"),
            CustomModel(ProviderType.MIMO, ModelProfile.MIMO_V2_6_PRO_ULTRASPEED.id, "MiMo V2.6 Pro UltraSpeed"),
        )
    }
}
