package com.qianyan.app.desktop

import com.qianyan.app.desktop.adapter.FileProviderCredentialStore
import com.qianyan.app.desktop.di.CustomModel
import com.qianyan.app.desktop.di.DesktopGraph
import com.qianyan.app.desktop.ui.settings.currentModelSection
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
 * T-O97-F（缺陷 O-97/O-98/O-99）· 热切换装配 + 当前模型刷新 + 状态栏任务文案。
 */
class ProviderSwitchAndStatusTest {

    private fun graphAt(dir: Path): DesktopGraph {
        val ctor = DesktopGraph::class.java.getDeclaredConstructor(
            Path::class.java,
            FileProviderCredentialStore::class.java,
            LlmHttpClient::class.java,
        )
        ctor.isAccessible = true
        return ctor.newInstance(dir, FileProviderCredentialStore(dir), JdkLlmHttpClient())
    }

    /** (a) O-97：重建容器必须使用本次配置，而不是切换前的旧字段。 */
    @Test
    fun `hot switch rebuilds container with the new configuration`() {
        val dir = Files.createTempDirectory("qianyan-o97-switch")
        val graph = graphAt(dir)
        graph.credentialStore.setApiKey(ProviderType.DEEPSEEK, "test-key")
        assertEquals(ProviderType.MOCK, graph.lastBuiltConfiguration?.provider, "前置：初始容器为 MOCK")

        assertTrue(graph.saveProviderConfig(ProviderType.DEEPSEEK, reasoningEffort = null))
        assertEquals(ProviderType.DEEPSEEK, graph.provider.value)
        assertEquals(
            ProviderType.DEEPSEEK,
            graph.lastBuiltConfiguration?.provider,
            "新容器必须按本次配置装配（旧实现会停在 MOCK）",
        )
        assertEquals("deepseek-v4-flash", graph.lastBuiltConfiguration?.model?.id)
    }

    /** (b) O-97 失败回滚：落盘失败时返回 false、Provider/模型不变、提示可读。 */
    @Test
    fun `persist failure keeps provider and model unchanged`() {
        val dir = Files.createTempDirectory("qianyan-o97-rollback")
        // O-104：失败分支会写崩溃日志，必须重定向到临时文件，不能污染真实 %APPDATA%。
        DesktopCrashLog.fileOverride = dir.resolve("logs").resolve("qianyan-crash.log")
        try {
            val graph = graphAt(dir)
            graph.credentialStore.setApiKey(ProviderType.DEEPSEEK, "test-key")
            Files.createDirectory(dir.resolve("provider-selection.properties"))

            val providerBefore = graph.provider.value
            val modelBefore = graph.modelId()
            assertFalse(graph.saveProviderConfig(ProviderType.DEEPSEEK))
            assertEquals(providerBefore, graph.provider.value, "失败不得切换 Provider")
            assertEquals(modelBefore, graph.modelId(), "失败不得留下半改模型")
            val message = graph.providerMessage.value
            assertNotNull(message)
            assertTrue("保存失败" in message, "必须给出可读失败提示：$message")
        } finally {
            DesktopCrashLog.fileOverride = null
        }
    }

    /** (c) O-98：投影随 currentModelId 变化，且模型页 remember key 包含当前模型读取。 */
    @Test
    fun `current model projection follows the active model and page refreshes on it`() {
        val flash = currentModelSection(
            provider = ProviderType.DEEPSEEK,
            currentModelId = "deepseek-v4-flash",
            candidates = listOf(
                CustomModel(ProviderType.DEEPSEEK, "deepseek-v4-flash", "DeepSeek V4 Flash"),
                CustomModel(ProviderType.DEEPSEEK, "deepseek-v4-pro", "DeepSeek V4 Pro"),
            ),
            defaultModelId = "deepseek-v4-flash",
        )
        val pro = currentModelSection(
            provider = ProviderType.DEEPSEEK,
            currentModelId = "deepseek-v4-pro",
            candidates = listOf(
                CustomModel(ProviderType.DEEPSEEK, "deepseek-v4-flash", "DeepSeek V4 Flash"),
                CustomModel(ProviderType.DEEPSEEK, "deepseek-v4-pro", "DeepSeek V4 Pro"),
            ),
            defaultModelId = "deepseek-v4-flash",
        )
        assertEquals("deepseek-v4-flash", flash.currentModelId)
        assertEquals("deepseek-v4-pro", pro.currentModelId, "切换当前模型后投影必须重算")
        assertEquals("DeepSeek V4 Pro", pro.currentLabel)

        val source = File("src/jvmMain/kotlin/com/qianyan/app/desktop/ui/settings/ModelSettings.kt").readText()
        // O-98（20 号重修）：刷新依据改为可观察的 effectiveModel，17 号的 modelId() key 无效。
        assertTrue(
            "remember(provider, revision, effectiveModel)" in source,
            "当前模型小节的缓存 key 必须基于可观察的 effectiveModel，否则切换后不刷新",
        )
    }

    /** (d) O-99：状态栏任务文案三态，且确实被 StatusBar 调用。 */
    @Test
    fun `status bar task label covers rejected round and busy fallback`() {
        assertEquals("任务 上一轮已拒绝", statusTaskLabel("上一轮已拒绝", busy = false))
        assertEquals("任务运行中", statusTaskLabel("空闲", busy = true))
        assertNull(statusTaskLabel("空闲", busy = false))

        val source = File("src/jvmMain/kotlin/com/qianyan/app/desktop/Main.kt").readText()
        assertTrue("statusTaskLabel(s.task, state.busy)" in source, "StatusBar 必须调用统一的任务文案函数")
    }
}
