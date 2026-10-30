package com.qianyan.app.desktop

import com.qianyan.app.desktop.adapter.FileProviderCredentialStore
import com.qianyan.app.desktop.di.DesktopGraph
import com.qianyan.app.desktop.ui.DesktopAppState
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * T-O46 · Toast 层回归。
 *
 * 背景：三条真实路径（无章节发送 / 打开作品 / 窄窗面板折叠）曾出现「业务正常但无可见提示」。
 * Step 0 实测：三条路径都命中了 toast() 且现有层可见；真正的隐患是**连续触发同一文案时，
 * 旧计时器会提前清掉新消息**（清除条件只比较文案，无法区分新旧）。
 *
 * 本测试守住两条：toast() 必须立即写入 UI 状态；旧计时器不得清除刚触发的新 toast。
 */
class DesktopToastTest {

    /** DesktopGraph 的构造器是 private（create() 指向 %APPDATA%），测试经反射指向临时目录，避免污染真实库。 */
    private fun tempGraph(): DesktopGraph {
        val dir: Path = Files.createTempDirectory("qianyan-desktop-toast")
        val ctor = DesktopGraph::class.java.getDeclaredConstructor(Path::class.java, FileProviderCredentialStore::class.java)
        ctor.isAccessible = true
        return ctor.newInstance(dir, FileProviderCredentialStore(dir))
    }

    private fun await(timeoutMs: Long = 8_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(20)
        }
        throw AssertionError("等待超时（${timeoutMs}ms）")
    }

    @Test
    fun `toast writes UI state immediately and clears itself after the visible window`() {
        val state = DesktopAppState(tempGraph())

        state.toast("提示一")

        assertEquals("提示一", state.toast, "toast() 必须立即写入 UI 状态")
        Thread.sleep(800)
        assertEquals("提示一", state.toast, "3.2s 展示窗口内不得提前消失")
        await { state.toast == null }
        assertNull(state.toast, "展示窗口结束后必须自动清除")
    }

    @Test
    fun `a stale timer must not clear a newer toast with the same text`() {
        val state = DesktopAppState(tempGraph())

        state.toast("重复提示")
        Thread.sleep(2_800)
        // 旧计时器在 3.2s 触发；再次触发同一文案必须重新计时，而不是被旧任务清掉。
        state.toast("重复提示")
        Thread.sleep(800)

        assertNotNull(state.toast, "旧计时器不得清除刚刚再次触发的同文案 toast")
    }
}
