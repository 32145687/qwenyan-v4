package com.qianyan.app.desktop

import com.qianyan.app.desktop.adapter.FileProviderCredentialStore
import com.qianyan.app.desktop.di.DesktopGraph
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
 * DSH-03 · 「新会话」治理：
 *
 *  - 只清空当前显示 + 新会话视图标识，**不删库**；
 *  - 重开作品仍按 novelId 回灌全部历史；
 *  - 入口在创作页且接到状态方法（源码守卫）。
 */
class ConversationNewSessionTest {

    private fun graphAt(dir: Path = Files.createTempDirectory("qianyan-conv-new")): DesktopGraph {
        val ctor = DesktopGraph::class.java.getDeclaredConstructor(
            Path::class.java,
            FileProviderCredentialStore::class.java,
            LlmHttpClient::class.java,
        )
        ctor.isAccessible = true
        return ctor.newInstance(dir, FileProviderCredentialStore(dir), JdkLlmHttpClient())
    }

    @Test
    fun `new session clears display without deleting persisted rows`() {
        val graph = graphAt()
        val state = DesktopAppState(graph)
        val novelId = state.container.novels.createOriginal("新会话验证书")
        val pid = graph.container.projects.openProject(novelId).projectId
        state.container.conversations.append(pid, novelId, ConversationRole.USER, "第一轮")
        state.container.conversations.append(pid, novelId, ConversationRole.ASSISTANT, "第一轮回复")
        state.applyNovelSwitch(graph.container.novels.getNovel(novelId)!!)
        assertEquals(2, state.creationMessages.size, "打开作品应回灌历史")

        val keyBefore = state.conversationViewKey
        state.startNewConversation()

        assertEquals(0, state.creationMessages.size, "新会话只清空当前显示")
        assertEquals(keyBefore + 1, state.conversationViewKey, "必须生成新的会话视图标识")
        assertEquals(2L, state.container.conversations.countByNovel(novelId), "不得删除库行")

        state.applyNovelSwitch(graph.container.novels.getNovel(novelId)!!)
        assertEquals(2, state.creationMessages.size, "重开作品仍须回灌全部历史")
        assertEquals("第一轮", state.creationMessages[0].text)
        assertEquals("第一轮回复", state.creationMessages[1].text)
    }

    @Test
    fun `agent page exposes new session entry wired to state`() {
        val page = Files.readString(
            Path.of("src/jvmMain/kotlin/com/qianyan/app/desktop/ui/pages/AgentPage.kt"),
        )
        assertTrue("\"新会话\"" in page, "创作页必须提供「新会话」入口")
        assertTrue("startNewConversation()" in page, "入口必须调用状态方法")

        val common = Files.readString(Path.of("src/jvmMain/kotlin/com/qianyan/app/desktop/ui/Common.kt"))
        assertTrue("fun startNewConversation()" in common, "新会话语义必须在 DesktopAppState")
        assertTrue("conversationViewKey" in common, "新会话视图标识必须存在")
    }
}
