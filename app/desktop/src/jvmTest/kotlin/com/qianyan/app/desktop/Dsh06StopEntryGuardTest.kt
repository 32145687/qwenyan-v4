package com.qianyan.app.desktop

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * DSH-06 · 桌面「停止」入口源码守卫（随既有守卫测试口径）。
 *
 * 钉住：运行中显示「停止」→ 点击 cancelCurrentRun() → 置灰「正在停止…」；
 * 运行必须携带取消令牌；终态后清空令牌。
 */
class Dsh06StopEntryGuardTest {

    private val common = File("src/jvmMain/kotlin/com/qianyan/app/desktop/ui/Common.kt").readText()
    private val page = File("src/jvmMain/kotlin/com/qianyan/app/desktop/ui/pages/AgentPage.kt").readText()

    @Test
    fun `stop entry is wired into creation page and launch path`() {
        assertTrue("currentRunCancellation" in common, "状态必须持有当前 run 取消令牌")
        assertTrue("fun cancelCurrentRun" in common, "必须有取消入口")
        assertTrue(
            "container.novelAgent.run(request, cancellation)" in common,
            "运行必须携带取消令牌（NovelAgent 相位边界检查点消费）",
        )
        assertTrue("currentRunCancellation = null" in common, "终态后必须清空令牌（finally）")
        assertTrue("正在停止…" in page, "停止中置灰文案必须存在")
        assertTrue("state.cancelCurrentRun()" in page, "停止按钮必须接 cancelCurrentRun")
        assertTrue("\"停止\"" in page, "停止按钮文案必须存在")
    }
}
