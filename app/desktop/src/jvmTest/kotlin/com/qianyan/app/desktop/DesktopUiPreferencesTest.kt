package com.qianyan.app.desktop

import com.qianyan.app.desktop.di.DesktopUiPreferences
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopUiPreferencesTest {

    @Test
    fun `theme mode persists across instances`() {
        val file = Files.createTempFile("qianyan-ui-preferences", ".properties")
        try {
            val first = DesktopUiPreferences(file)
            assertNull(first.readThemeMode(), "空文件应回退为未设置")
            assertTrue(first.writeThemeMode("DARK"), "主题偏好应写入成功")
            val second = DesktopUiPreferences(file)
            assertEquals("DARK", second.readThemeMode(), "新实例应读回已保存的主题")
        } finally {
            Files.deleteIfExists(file)
        }
    }
}
