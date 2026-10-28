package com.qianyan.app.desktop.di

import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

/**
 * 桌面 UI 偏好（非业务状态，不含凭证）。
 *
 * 主题等显示偏好落盘到应用数据目录，重启后恢复；读取失败/文件损坏时回退为空偏好。
 */
class DesktopUiPreferences(private val file: Path) {

    fun readThemeMode(): String? = runCatching {
        val props = Properties()
        if (Files.exists(file)) Files.newInputStream(file).use { props.load(it) }
        props.getProperty("theme")?.takeIf { it.isNotBlank() }
    }.getOrNull()

    fun writeThemeMode(mode: String): Boolean = runCatching {
        val props = Properties()
        if (Files.exists(file)) Files.newInputStream(file).use { props.load(it) }
        props.setProperty("theme", mode)
        file.parent?.let { Files.createDirectories(it) }
        Files.newOutputStream(file).use { props.store(it, "Qianyan desktop UI preferences") }
        true
    }.getOrDefault(false)
}
