package com.qianyan.app.desktop

import com.qianyan.app.desktop.adapter.FileProviderCredentialStore
import com.qianyan.app.desktop.di.DesktopGraph
import com.qianyan.app.desktop.ui.DesktopAppState
import com.qianyan.app.desktop.ui.pendingGateBlocksProviderSwitch
import com.qianyan.app.desktop.ui.settings.saveProviderFromSettings
import com.qianyan.provider.ProviderType
import com.qianyan.provider.impl.transport.JdkLlmHttpClient
import com.qianyan.provider.impl.transport.LlmHttpClient
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * T-O100-F（裁决 A-27 = a）· 有待确认人工门时禁止切换 Provider / 模型。
 *
 * 目的：在入口挡住「切换重建容器 → 内存候选丢失」，而不是事后提示候选已失效。
 */
class PendingGateProviderSwitchTest {

    private fun graphAt(dir: Path): DesktopGraph {
        val ctor = DesktopGraph::class.java.getDeclaredConstructor(
            Path::class.java,
            FileProviderCredentialStore::class.java,
            LlmHttpClient::class.java,
        )
        ctor.isAccessible = true
        return ctor.newInstance(dir, FileProviderCredentialStore(dir), JdkLlmHttpClient())
    }

    /** (a) 纯函数三态。 */
    @Test
    fun `pending gate predicate is true only when count is positive`() {
        assertFalse(pendingGateBlocksProviderSwitch(0))
        assertTrue(pendingGateBlocksProviderSwitch(1))
        assertTrue(pendingGateBlocksProviderSwitch(3))
    }

    /** (b) AI Provider 保存入口被拒。 */
    @Test
    fun `pending gate rejects provider save before any state change`() {
        val dir = Files.createTempDirectory("qianyan-o100-save")
        val graph = graphAt(dir)
        graph.credentialStore.setApiKey(ProviderType.DEEPSEEK, "test-key")
        val state = DesktopAppState(graph)
        state.pendingGateCount = 1
        val providerBefore = graph.provider.value
        val modelBefore = graph.modelId()

        assertFalse(saveProviderFromSettings(state, ProviderType.DEEPSEEK, "", ""))
        assertEquals(providerBefore, graph.provider.value, "拒绝时不得切换 Provider")
        assertEquals(modelBefore, graph.modelId(), "拒绝时不得改动模型")
        val message = graph.providerMessage.value
        assertNotNull(message)
        assertTrue("请先批准或拒绝" in message, "提示必须可读：$message")
        assertTrue("当前作品有待确认的候选" in message, "提示文案须逐字命中：$message")
    }

    /** (c) 模型切换入口被拒。 */
    @Test
    fun `pending gate rejects chooseProviderModel`() {
        val dir = Files.createTempDirectory("qianyan-o100-model")
        val graph = graphAt(dir)
        graph.credentialStore.setApiKey(ProviderType.DEEPSEEK, "test-key")
        val state = DesktopAppState(graph)
        state.pendingGateCount = 1
        val providerBefore = graph.provider.value
        val modelBefore = graph.modelId()

        assertFalse(state.chooseProviderModel(ProviderType.DEEPSEEK, modelId = "deepseek-v4-pro"))
        assertEquals(providerBefore, graph.provider.value)
        assertEquals(modelBefore, graph.modelId())
        assertTrue("请先批准或拒绝" in (state.toast ?: ""), "toast 须可读：${state.toast}")
    }

    /** (d) 无待确认时同一入口成功（守卫只挡有待确认时）。 */
    @Test
    fun `no pending gate keeps provider switching working`() {
        val dir = Files.createTempDirectory("qianyan-o100-idle")
        val graph = graphAt(dir)
        graph.credentialStore.setApiKey(ProviderType.DEEPSEEK, "test-key")
        val state = DesktopAppState(graph)
        state.pendingGateCount = 0

        assertTrue(saveProviderFromSettings(state, ProviderType.DEEPSEEK, "", ""))
        assertEquals(ProviderType.DEEPSEEK, graph.provider.value)
        assertTrue(state.chooseProviderModel(ProviderType.DEEPSEEK, modelId = "deepseek-v4-pro"))
        assertEquals("deepseek-v4-pro", graph.modelId())
    }

    /** (e) 源码守卫：Main.kt 同步真实计数，两处入口都调用共用判定。 */
    @Test
    fun `main syncs the count and both entries share the guard`() {
        val main = File("src/jvmMain/kotlin/com/qianyan/app/desktop/Main.kt").readText()
        assertTrue("state.pendingGateCount =" in main, "Main.kt 必须把真实 PENDING 门数写入可见计数")

        val common = File("src/jvmMain/kotlin/com/qianyan/app/desktop/ui/Common.kt").readText()
        assertTrue("pendingGateBlocksProviderSwitch(pendingGateCount)" in common, "模型入口必须复用共用判定")

        val settings = File("src/jvmMain/kotlin/com/qianyan/app/desktop/ui/settings/SettingsWindow.kt").readText()
        assertTrue("pendingGateBlocksProviderSwitch(state.pendingGateCount)" in settings, "Provider 入口必须复用共用判定")
    }
}
