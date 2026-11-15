package com.qianyan.app.desktop

import com.qianyan.app.desktop.adapter.FileProviderCredentialStore
import com.qianyan.app.desktop.di.DesktopGraph
import com.qianyan.app.desktop.ui.CreationMessageRole
import com.qianyan.app.desktop.ui.DesktopAppState
import com.qianyan.app.desktop.ui.openNovel
import com.qianyan.app.desktop.ui.pages.shouldShowUnselectedChapterHint
import com.qianyan.provider.impl.transport.JdkLlmHttpClient
import com.qianyan.provider.impl.transport.LlmHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.nio.file.Files
import java.nio.file.Path

/**
 * T-O113-F（第三阶段清单 CS-F1 / 裁决 A-38）· 冷启动解锁。
 *
 * 1) 无选中章节时 composer 任务照常下达（activeChapterId=null 透传；
 *    需要章节的任务由 NovelAgent 以 MISSING_FOCUS_CHAPTER 显式拒绝——判定权不复制进 UI）；
 * 2) 「创建第一章」复用既有 createNextChapter，创建后自动选中（幂等：已有章节只选中不重建）；
 * 3) 未选章节常驻提示 + 三步引导 + 侧栏「新建」入口（源码守卫，随既有守卫测试口径）。
 */
class ColdStartUnblockedTest {

    private fun graphAt(dir: Path = Files.createTempDirectory("qianyan-o113")): DesktopGraph {
        val ctor = DesktopGraph::class.java.getDeclaredConstructor(
            Path::class.java,
            FileProviderCredentialStore::class.java,
            LlmHttpClient::class.java,
        )
        ctor.isAccessible = true
        return ctor.newInstance(dir, FileProviderCredentialStore(dir), JdkLlmHttpClient())
    }

    /** 轮询直到条件成立（DesktopAppState 动作经 Dispatchers.Main + IO 异步交付）。 */
    private fun waitUntil(timeoutMs: Long = 30_000, cond: () -> Boolean): Unit = runBlocking {
        withTimeout(timeoutMs) {
            while (!cond()) {
                withContext(Dispatchers.IO) { delay(50) }
            }
        }
    }

    private fun openWork(state: DesktopAppState, novelId: com.qianyan.model.NovelId) {
        openNovel(state, novelId)
        waitUntil { state.novel != null }
    }

    /* ---------- (a) 无选中章节：任务放行 ---------- */

    @Test
    fun `agent task without selected chapter is accepted and forwarded`() {
        val graph = graphAt()
        val state = DesktopAppState(graph)
        val novelId = state.container.novels.createOriginal("O113 探针")
        openWork(state, novelId)
        assertEquals(null, state.workspace.selection, "前置：刚打开作品未选章节")

        val accepted = state.launchAgentTask("冷启动探针任务")
        assertTrue(accepted, "A-38：无选中章节的任务必须放行（不再被 UI 一刀切阻断）")
        val last = state.creationMessages.last()
        assertEquals(CreationMessageRole.USER, last.role)
        assertEquals("冷启动探针任务", last.text)
        assertFalse(
            state.creationMessages.any { it.role == CreationMessageRole.ERROR && "章节目录" in it.text },
            "不得再出现旧阻断文案",
        )
        waitUntil { !state.busy }
    }

    /* ---------- (b) 创建第一章：自动选中 + 幂等 ---------- */

    @Test
    fun `create first chapter selects it, toasts and is idempotent`() {
        val graph = graphAt()
        val state = DesktopAppState(graph)
        val novelId = state.container.novels.createOriginal("O113 空作品")
        openWork(state, novelId)

        state.launchCreateFirstChapter()
        waitUntil { state.workspace.selection != null }
        val chapters = state.container.chapters.listByNovel(novelId, null)
        assertEquals(1, chapters.size, "第一章只建一次")
        assertEquals("第一章", chapters.first().title)
        assertEquals(chapters.first().chapterId, state.workspace.selection?.chapterId)
        assertEquals("已创建第一章", state.toast)

        state.launchCreateFirstChapter()
        waitUntil { !state.busy }
        assertEquals(1, state.container.chapters.listByNovel(novelId, null).size, "重复调用不得建第二 chapter")
    }

    /* ---------- (c) 源码守卫：Common.kt ---------- */

    @Test
    fun `launchAgentTask no longer blocks on missing chapter and first chapter action exists`() {
        val text = Files.readString(Path.of("src/jvmMain/kotlin/com/qianyan/app/desktop/ui/Common.kt"))
        assertFalse(
            "请先在左侧章节目录中选择要操作的章节" in text,
            "旧阻断 toast 必须移除（A-38）",
        )
        assertTrue("fun launchCreateFirstChapter" in text, "缺「创建第一章」动作")
        assertTrue("createNextChapter(title = \"第一章\"" in text, "建章必须复用既有 createNextChapter")
        assertTrue("workspace.select(WorkspaceSelection.chapter(" in text, "创建后必须自动选中")
    }

    /* ---------- (d) 源码守卫：AgentPage + Main 侧栏 ---------- */

    @Test
    fun `agent page shows unselected hint with three step guide and sidebar entry`() {
        val agent = Files.readString(Path.of("src/jvmMain/kotlin/com/qianyan/app/desktop/ui/pages/AgentPage.kt"))
        assertTrue("未选章节：" in agent, "缺常驻轻提示")
        assertTrue("创建第一章" in agent, "缺「创建第一章」入口")
        assertTrue("shouldShowUnselectedChapterHint" in agent, "提示可见性必须走纯函数")
        assertTrue("selection?.chapterId != null" in agent, "可见性必须按「是否聚焦章节」判定（selection 可能是 Project 级默认落点）")
        assertTrue("大纲 / 人物 / 世界观" in agent, "缺三步引导第①步锚点")
        assertTrue("故事 · 章节" in agent, "缺三步引导第②步锚点")
        val main = Files.readString(Path.of("src/jvmMain/kotlin/com/qianyan/app/desktop/Main.kt"))
        assertTrue("launchCreateFirstChapter" in main, "侧栏空态缺「新建」入口")
    }

    /* ---------- (e) 提示可见性纯函数 ---------- */

    @Test
    fun `unselected chapter hint visibility truth table`() {
        assertTrue(shouldShowUnselectedChapterHint(novelOpen = true, hasChapterSelection = false))
        assertFalse(shouldShowUnselectedChapterHint(novelOpen = true, hasChapterSelection = true))
        assertFalse(shouldShowUnselectedChapterHint(novelOpen = false, hasChapterSelection = false))
    }
}
