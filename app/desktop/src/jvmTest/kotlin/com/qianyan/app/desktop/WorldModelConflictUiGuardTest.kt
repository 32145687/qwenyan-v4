package com.qianyan.app.desktop

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T-O06 · 世界观冲突 UI 守卫（源码级）。
 *
 * 钉住：叙事账本顶部按 CONFLICT 渲染 + 无冲突隐藏 + 两个动作按钮；检查器只给"去处理"入口；
 * 状态栏**不得**读取冲突门（O-50 计数口径不变）。
 */
class WorldModelConflictUiGuardTest {

    private fun mainRoot(): File {
        val userDir = File(System.getProperty("user.dir"))
        return listOf(
            File(userDir, "src/jvmMain/kotlin"),
            File(userDir, "app/desktop/src/jvmMain/kotlin"),
        ).firstOrNull { it.isDirectory } ?: error("无法定位 desktop jvmMain")
    }

    private fun source(relative: String): String {
        val file = mainRoot().resolve(relative)
        assertTrue(file.isFile, "源文件必须存在: ${file.absolutePath}")
        return file.readText()
    }

    @Test
    fun `narrative ledger renders conflicts and hides the section when empty`() {
        val tabs = source("com/qianyan/app/desktop/ui/pages/StoryExtraTabs.kt")
        assertTrue(tabs.contains("世界观冲突"), "叙事账本必须渲染「世界观冲突」区块")
        assertTrue(tabs.contains("listForNovel"), "必须按作品读取冲突（listForNovel）")
        assertTrue(tabs.contains("worldModelConflicts.resolve("), "必须调用冲突裁决入口")
        assertTrue(tabs.contains("采纳为独立事实"), "必须提供「采纳为独立事实」")
        assertTrue(tabs.contains("拒绝"), "必须提供「拒绝」")
        assertTrue(
            tabs.contains("if (novelId != null && conflicts.isNotEmpty())"),
            "无冲突时必须整节不渲染（不给空壳/假按钮）",
        )
    }

    @Test
    fun `inspector only links to the ledger and status bar ignores conflict gates`() {
        val inspector = source("com/qianyan/app/desktop/ui/workspace/ContextInspector.kt")
        assertTrue(inspector.contains("世界观冲突"), "检查器「待审批」末尾必须有冲突提示行")
        assertTrue(inspector.contains("pendingCountForNovel"), "提示行必须读取冲突计数")
        assertTrue(
            inspector.contains("openWorkspace(com.qianyan.app.desktop.ui.Workspace.STORY, 6)"),
            "提示行必须跳到「故事 › 叙事账本」（index 6）",
        )
        val main = source("com/qianyan/app/desktop/Main.kt")
        assertFalse(
            main.contains("worldModelConflicts") || main.contains("pendingCountForNovel"),
            "状态栏「待确认」不得读取冲突门（O-50 口径不变）",
        )
    }
}
