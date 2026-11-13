package com.qianyan.app.desktop

import com.qianyan.app.desktop.adapter.FileProviderCredentialStore
import com.qianyan.app.desktop.di.DesktopGraph
import com.qianyan.app.desktop.ui.DesktopAppState
import com.qianyan.app.desktop.ui.pages.shouldRefreshAfterBusy
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T-O98-F2（O-98 / O-101 / O-102 / O-104）· 可观察刷新 + 计数刷新 + 红条清理 + 测试隔离。
 */
class RefreshAndCleanupTest {

    private fun graphAt(dir: Path): DesktopGraph {
        val ctor = DesktopGraph::class.java.getDeclaredConstructor(
            Path::class.java,
            FileProviderCredentialStore::class.java,
            LlmHttpClient::class.java,
        )
        ctor.isAccessible = true
        return ctor.newInstance(dir, FileProviderCredentialStore(dir), JdkLlmHttpClient())
    }

    /** (a) O-98：effectiveModel 是可观察状态，切换模型后值变化；模型页必须以它刷新。 */
    @Test
    fun `effective model is observable and model page refreshes on it`() {
        val dir = Files.createTempDirectory("qianyan-o98-observe")
        val graph = graphAt(dir)
        graph.credentialStore.setApiKey(ProviderType.DEEPSEEK, "test-key")
        val state = DesktopAppState(graph)
        state.pendingGateCount = 0

        assertEquals("mock-v1", graph.effectiveModel.value, "前置：初始生效模型")
        assertTrue(state.chooseProviderModel(ProviderType.DEEPSEEK, modelId = "deepseek-v4-pro"))
        assertEquals("deepseek-v4-pro", graph.effectiveModel.value, "切换后 effectiveModel 必须变化")

        val source = File("src/jvmMain/kotlin/com/qianyan/app/desktop/ui/settings/ModelSettings.kt").readText()
        assertTrue("effectiveModel.collectAsState()" in source, "模型页必须订阅可观察的 effectiveModel")
        assertTrue("remember(provider, revision, effectiveModel)" in source, "remember key 必须含 effectiveModel")
        assertFalse(
            "remember(provider, revision, state.graph.modelId())" in source,
            "不得再以不可观察的 modelId() 作为唯一刷新依据",
        )
    }

    /** (b) O-101：仅 busy 由 true 变 false 时需要刷新计数。 */
    @Test
    fun `busy ending triggers a pending count refresh`() {
        assertTrue(shouldRefreshAfterBusy(previous = true, next = false))
        assertFalse(shouldRefreshAfterBusy(previous = false, next = true))
        assertFalse(shouldRefreshAfterBusy(previous = true, next = true))
        assertFalse(shouldRefreshAfterBusy(previous = false, next = false))

        val main = File("src/jvmMain/kotlin/com/qianyan/app/desktop/Main.kt").readText()
        assertTrue(
            "if (shouldRefreshAfterBusy(wasWriterBusy, state.writerBusy)) state.gateRefreshKey++" in main,
            "busy 结束只刷新待确认计数（独立 gateRefreshKey；不再重建写作页，见 T-O106-F）",
        )
    }

    /** (c) O-102：关闭设置时清空 providerMessage，两条关闭路径都调用。 */
    @Test
    fun `closing settings clears the stale provider message`() {
        val dir = Files.createTempDirectory("qianyan-o102-clear")
        val graph = graphAt(dir)
        val state = DesktopAppState(graph)
        graph.providerMessage.value = "当前作品有待确认的候选：请先批准或拒绝，再切换 Provider"

        clearProviderMessageOnSettingsClose(state)
        assertNull(graph.providerMessage.value, "关闭设置后红条必须清空")

        val main = File("src/jvmMain/kotlin/com/qianyan/app/desktop/Main.kt").readText()
        val calls = Regex("clearProviderMessageOnSettingsClose\\(state\\)").findAll(main).count()
        assertTrue(calls >= 2, "背景点击与关闭按钮两条路径都必须调用，实际 $calls 次")
    }

    /** (d) O-104：崩溃日志写入 fileOverride 指定的临时文件，不碰真实 appdata。 */
    @Test
    fun `crash log respects the test override and leaves the real log untouched`() {
        val realLog = Path.of(System.getenv("APPDATA"), "Qianyan", "logs", "qianyan-crash.log").toFile()
        val realLengthBefore = if (realLog.exists()) realLog.length() else -1L
        val realModifiedBefore = if (realLog.exists()) realLog.lastModified() else -1L

        val dir = Files.createTempDirectory("qianyan-o104-isolate")
        val override = dir.resolve("logs").resolve("qianyan-crash.log")
        DesktopCrashLog.fileOverride = override
        try {
            val graph = graphAt(dir)
            // 有 Key 才会走到「装配成功 → persist 失败」分支，从而触发 DesktopCrashLog.record。
            graph.credentialStore.setApiKey(ProviderType.DEEPSEEK, "test-key")
            Files.createDirectory(dir.resolve("provider-selection.properties"))
            assertFalse(graph.saveProviderConfig(ProviderType.DEEPSEEK), "落盘失败必须返回 false")
            assertTrue(Files.exists(override), "失败日志必须写入临时日志文件")
            assertTrue("saveProviderConfig" in Files.readString(override), "临时日志应含失败上下文")
        } finally {
            DesktopCrashLog.fileOverride = null
        }

        val realLengthAfter = if (realLog.exists()) realLog.length() else -1L
        val realModifiedAfter = if (realLog.exists()) realLog.lastModified() else -1L
        assertEquals(realLengthBefore, realLengthAfter, "真实崩溃日志不得被本用例追加")
        assertEquals(realModifiedBefore, realModifiedAfter, "真实崩溃日志 mtime 不得变化")
    }
}
