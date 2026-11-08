package com.qianyan.app.desktop

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T-O07-A · 伏笔账本 UI 守卫（源码级）。
 *
 * 钉住：作者入口走 `foreshadowAuthoring`（UseCase），不直连仓储做写；终态动作禁用并给原因；
 * 兑现必须经由章节选择（DropdownMenu）。
 */
class ForeshadowLedgerUiGuardTest {

    private val page = File(
        System.getProperty("user.dir"),
        "src/jvmMain/kotlin/com/qianyan/app/desktop/ui/pages/StoryWorldPages.kt",
    ).let {
        if (it.isFile) it else File("app/desktop/src/jvmMain/kotlin/com/qianyan/app/desktop/ui/pages/StoryWorldPages.kt")
    }

    private val text: String get() = page.readText()

    @Test
    fun `authoring entry goes through the use case and offers lifecycle actions`() {
        assertTrue(page.isFile, "StoryWorldPages.kt 必须存在: ${page.absolutePath}")
        assertTrue(text.contains("foreshadowAuthoring.create("), "必须经 UseCase 创建伏笔")
        assertTrue(text.contains("foreshadowAuthoring.transition("), "必须经 UseCase 推进生命周期")
        assertTrue(text.contains("埋下伏笔"), "必须有创建入口")
        assertTrue(text.contains("推进为进行中") && text.contains("兑现…") && text.contains("放弃"), "三种动作必须齐备")
        assertTrue(text.contains("已兑现，不可再迁移"), "终态必须给出不可迁移原因")
        assertTrue(text.contains("已放弃，不可再迁移"), "放弃终态同样给出原因")
        assertTrue(text.contains("DropdownMenu"), "兑现必须选择章节（不允许无章节落库）")
        assertFalse(text.contains("storyState.transitionForeshadowState"), "UI 不得直连仓储做生命周期写")
    }
}
