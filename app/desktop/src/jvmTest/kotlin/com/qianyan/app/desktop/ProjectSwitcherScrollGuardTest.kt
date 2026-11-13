package com.qianyan.app.desktop

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * T-O80-F · 「切换作品」下拉可用性守卫（源码级）。
 *
 * 缺陷：列表按 `created_at` 升序渲染且无滚动容器 ⇒ 作品数超过窗口高度后，末行被裁切、滚轮无效，
 * 最新作品永远无法点开。守卫：列表区必须可滚动（`verticalScroll`）、最新作品置顶（`asReversed`）。
 */
class ProjectSwitcherScrollGuardTest {

    private fun mainRoot(): File {
        val userDir = File(System.getProperty("user.dir"))
        val candidates = listOf(
            File(userDir, "src/jvmMain/kotlin"),
            File(userDir, "app/desktop/src/jvmMain/kotlin"),
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error("无法定位 desktop jvmMain（user.dir=$userDir）")
    }

    @Test
    fun `project switcher list is scrollable and newest first`() {
        val main = mainRoot().walkTopDown().single { it.isFile && it.name == "Main.kt" }.readText()
        val start = main.indexOf("private fun ProjectSwitcher(")
        assertTrue(start >= 0, "未找到 ProjectSwitcher")
        val end = main.indexOf("\n@Composable", start + 1).let { if (it < 0) main.length else it }
        val section = main.substring(start, end)
        assertTrue(section.contains("verticalScroll(rememberScrollState())"), "列表区必须可滚动（否则末行被裁切且滚轮无效）")
        assertTrue(section.contains("heightIn(max = 360.dp)"), "列表区必须有最大高度，避免撑破下拉")
        assertTrue(section.contains("asReversed()"), "最新作品必须置顶（NOVEL 排序仍为 created_at 升序）")
    }
}
