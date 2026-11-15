package com.qianyan.app.desktop

import com.qianyan.app.desktop.adapter.FileProviderCredentialStore
import com.qianyan.app.desktop.di.DesktopGraph
import com.qianyan.app.desktop.ui.CreationMessageRole
import com.qianyan.app.desktop.ui.DesktopAppState
import com.qianyan.model.session.ConversationRole
import com.qianyan.provider.impl.transport.JdkLlmHttpClient
import com.qianyan.provider.impl.transport.LlmHttpClient
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * DSH-1（T-CONV-F）· 对话记忆回灌：
 *
 *  - 对话消息落库（USER / ASSISTANT / ERROR）后，applyNovelSwitch 打开作品时从库回灌到创作页；
 *  - 切换作品 → 对话按作品隔离（不串线）；
 *  - 提案仍内存态（不在本测试范围，铁律不变：Memory ≠ 事实源）。
 */
class ConversationPersistenceTest {

    private fun graphAt(dir: Path = Files.createTempDirectory("qianyan-conv")): DesktopGraph {
        val ctor = DesktopGraph::class.java.getDeclaredConstructor(
            Path::class.java,
            FileProviderCredentialStore::class.java,
            LlmHttpClient::class.java,
        )
        ctor.isAccessible = true
        return ctor.newInstance(dir, FileProviderCredentialStore(dir), JdkLlmHttpClient())
    }

    @Test
    fun `conversation history is restored on novel open`() {
        val graph = graphAt()
        val state = DesktopAppState(graph)
        val novelId = state.container.novels.createOriginal("对话记忆验证书")
        val pid = graph.container.projects.openProject(novelId).projectId
        state.container.conversations.append(pid, novelId, ConversationRole.USER, "你好千言")
        state.container.conversations.append(pid, novelId, ConversationRole.ASSISTANT, "你好，作者是谁？")

        state.applyNovelSwitch(graph.container.novels.getNovel(novelId)!!)

        assertEquals(2, state.creationMessages.size, "历史对话应回灌")
        assertEquals(CreationMessageRole.USER, state.creationMessages[0].role)
        assertEquals("你好千言", state.creationMessages[0].text)
        assertEquals(CreationMessageRole.ASSISTANT, state.creationMessages[1].role)
        assertEquals("你好，作者是谁？", state.creationMessages[1].text)
    }

    @Test
    fun `novels do not leak conversations into each other`() {
        val graph = graphAt()
        val state = DesktopAppState(graph)
        val novelA = state.container.novels.createOriginal("书 A")
        val novelB = state.container.novels.createOriginal("书 B")
        val pidA = graph.container.projects.openProject(novelA).projectId
        state.container.conversations.append(pidA, novelA, ConversationRole.USER, "书 A 的问题")

        state.applyNovelSwitch(graph.container.novels.getNovel(novelB)!!)
        assertEquals(0, state.creationMessages.size, "书 B 不得看到书 A 的对话")

        state.applyNovelSwitch(graph.container.novels.getNovel(novelA)!!)
        assertEquals(1, state.creationMessages.size, "切回书 A 应恢复其对话")
        assertEquals("书 A 的问题", state.creationMessages[0].text)
    }

    /* ---------- 源码守卫（随既有守卫测试口径） ---------- */

    @Test
    fun `persist and restore wiring exists in Common_kt`() {
        val text = Files.readString(Path.of("src/jvmMain/kotlin/com/qianyan/app/desktop/ui/Common.kt"))
        assertEquals(1, Regex("private fun persistConversation").findAll(text).count(), "落库入口必须唯一")
        assertEquals(
            2,
            Regex("persistConversation\\(CreationMessageRole\\.ERROR").findAll(text).count(),
            "两条失败路径（Agent 异常 / IO 异常）都必须落 ERROR 记忆",
        )
        assertTrue(
            "container.conversations.append" in text,
            "落库必须经 Application 层用例（UI 只调 Application，铁律⑤）",
        )
        assertTrue(
            "creationMessages = if (novel == null)" in text && "listByNovel(novel.novelId)" in text,
            "打开 / 切换作品必须从库回灌对话（applyNovelSwitch 唯一重置入口）",
        )
    }
}
