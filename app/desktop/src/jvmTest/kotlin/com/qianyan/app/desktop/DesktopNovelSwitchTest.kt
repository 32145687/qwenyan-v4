package com.qianyan.app.desktop

import com.qianyan.app.desktop.adapter.FileProviderCredentialStore
import com.qianyan.app.desktop.di.DesktopGraph
import com.qianyan.app.desktop.ui.DesktopAppState
import com.qianyan.app.desktop.ui.openNovel
import com.qianyan.model.NovelId
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T-O45 · 作品切换（openNovel）回归测试。
 *
 * 背景：作品行点击曾「弹层关闭但作品不打开、无任何提示」。根因是 `application` 根作用域
 * 未 remember `DesktopGraph` / `DesktopAppState`——打开作品写入 `state.novel` 触发根重组合，
 * 每次都重建全新 state（novel 归零），业务实际成功而 UI 被重置。
 *
 * 本测试用**真实临时 SQLite 库**（DesktopGraph 指向临时 appDir）驱动 `openNovel`：
 *  1. 成功路径：state.novel 的标题 / projectId 正确，ProjectState 已建立，toast 显式；
 *  2. 失败路径：作品不存在时 state.novel 不变，且必须有显式 toast（不得静默）；
 *  3. 结构守卫：根作用域必须 remember graph/state，openNovel 不得回到静默 null 分支。
 */
class DesktopNovelSwitchTest {

    /** DesktopGraph 的构造器是 private（只暴露 create() 指向 %APPDATA%），测试经反射指向临时目录，避免污染真实库。 */
    private fun tempGraph(): DesktopGraph {
        val dir: Path = Files.createTempDirectory("qianyan-desktop-novel-switch")
        val ctor = DesktopGraph::class.java.getDeclaredConstructor(Path::class.java, FileProviderCredentialStore::class.java)
        ctor.isAccessible = true
        return ctor.newInstance(dir, FileProviderCredentialStore(dir))
    }

    private fun await(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(20)
        }
        throw AssertionError("等待超时（${timeoutMs}ms）")
    }

    @Test
    fun `opening an existing novel applies state and shows opened toast`() {
        val state = DesktopAppState(tempGraph())
        val id = state.container.novels.createOriginal(
            title = "切换测试作品",
            genre = listOf("东方幻想"),
            synopsis = "T-O45 回归",
        )
        val projectId = assertNotNull(state.container.novels.getNovel(id)).projectId

        openNovel(state, id)
        await { state.novel?.novelId == id }

        assertEquals("切换测试作品", state.novel?.title, "state.novel 必须是刚打开的作品")
        assertEquals(projectId, state.novel?.projectId, "state.novel 的 projectId 必须来自真实库")
        assertTrue(state.toast?.contains("已打开") == true, "打开成功必须有明确提示，实际=${state.toast}")
        assertNotNull(state.container.projects.state(projectId), "打开作品必须建立 ProjectState")
        await { !state.busy }
        assertFalse(state.busy, "打开完成后 busy 必须复位")
    }

    @Test
    fun `opening a missing novel keeps state empty and shows an explicit toast`() {
        val state = DesktopAppState(tempGraph())
        val missing = NovelId(UUID.randomUUID().toString())

        openNovel(state, missing)
        await { state.toast != null }

        assertNull(state.novel, "打开失败不得改变当前作品")
        val toast = state.toast ?: ""
        assertTrue(
            toast.startsWith("出错了：") || toast.contains("不存在") || toast.contains("打开作品失败"),
            "失败必须有显式提示（禁止静默），实际=$toast",
        )
        await { !state.busy }
        assertFalse(state.busy, "失败后 busy 必须复位")
    }

    @Test
    fun `app root remembers graph and state and open failure is never silent`() {
        val main = File("src/jvmMain/kotlin/com/qianyan/app/desktop/Main.kt").readText()
        assertTrue(
            "remember { DesktopGraph.create() }" in main,
            "DesktopGraph 必须在根作用域 remember（否则重组合会重建 DB 连接与状态）",
        )
        assertTrue(
            "remember { DesktopAppState(graph) }" in main,
            "DesktopAppState 必须在根作用域 remember（否则打开作品后 UI 被全新 state 重置）",
        )
        val common = File("src/jvmMain/kotlin/com/qianyan/app/desktop/ui/Common.kt").readText()
        assertTrue("打开作品失败" in common, "getNovel 为 null 时必须有显式失败提示")
        assertFalse(
            "novel?.let { state.toast(" in common,
            "不得回到「null 时静默应用空作品」的旧写法",
        )
    }
}
