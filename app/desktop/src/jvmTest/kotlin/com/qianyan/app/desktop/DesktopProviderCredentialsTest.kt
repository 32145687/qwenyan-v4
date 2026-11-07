package com.qianyan.app.desktop

import com.qianyan.app.desktop.adapter.FileProviderCredentialStore
import com.qianyan.app.desktop.di.DesktopGraph
import com.qianyan.app.desktop.ui.DesktopAppState
import com.qianyan.app.desktop.ui.settings.saveProviderFromSettings
import com.qianyan.provider.ProviderType
import com.qianyan.provider.ReasoningEffort
import com.qianyan.provider.impl.transport.JdkLlmHttpClient
import com.qianyan.provider.impl.transport.LlmHttpClient
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T-M1C（O-63）· 设置 › AI Provider 的 Key 录入链路。
 *
 * 全部使用临时 appDir 与假 Key，无真实网络；断言里不出现任何真实凭证。
 */
class DesktopProviderCredentialsTest {

    private fun graphAt(
        dir: Path = Files.createTempDirectory("qianyan-credentials"),
        client: LlmHttpClient = JdkLlmHttpClient(),
    ): DesktopGraph {
        val ctor = DesktopGraph::class.java.getDeclaredConstructor(
            Path::class.java,
            FileProviderCredentialStore::class.java,
            LlmHttpClient::class.java,
        )
        ctor.isAccessible = true
        return ctor.newInstance(dir, FileProviderCredentialStore(dir), client)
    }

    private fun selection(dir: Path): Properties = Properties().apply {
        dir.resolve("provider-selection.properties").toFile().inputStream().use { load(it) }
    }

    @Test
    fun `settings page saves a key for an unconfigured provider`() {
        val dir = Files.createTempDirectory("qianyan-credentials-save")
        val graph = graphAt(dir)
        val state = DesktopAppState(graph)
        assertFalse(graph.isConfigured(ProviderType.DEEPSEEK), "初始必须未配置")

        assertTrue(saveProviderFromSettings(state, ProviderType.DEEPSEEK, "test-key", ""))

        assertTrue(graph.isConfigured(ProviderType.DEEPSEEK), "保存后必须已配置")
        assertEquals(ProviderType.DEEPSEEK, graph.provider.value, "保存即切换到所选 Provider")
        assertTrue(dir.resolve("credentials.properties").toFile().isFile, "Key 落在 credentials.properties")
        assertNull(
            selection(dir).getProperty("apiKey"),
            "Key 不得写入 provider-selection.properties",
        )
    }

    @Test
    fun `saving a new key keeps selected model and effort`() {
        val dir = Files.createTempDirectory("qianyan-credentials-keep")
        val graph = graphAt(dir)
        graph.credentialStore.setApiKey(ProviderType.DEEPSEEK, "test-key")
        assertTrue(graph.saveProviderConfig(ProviderType.DEEPSEEK, modelId = "deepseek-v4-pro"))
        assertTrue(graph.saveProviderConfig(ProviderType.DEEPSEEK, reasoningEffort = ReasoningEffort.LOW))
        val before = selection(dir)
        val selectedBefore = before.getProperty("selected")
        val modelBefore = before.getProperty("model")
        val effortBefore = before.getProperty("effort")

        val state = DesktopAppState(graph)
        assertTrue(saveProviderFromSettings(state, ProviderType.DEEPSEEK, "test-key-2", ""))

        val after = selection(dir)
        assertEquals(selectedBefore, after.getProperty("selected"), "selected 不得被改写")
        assertEquals(modelBefore, after.getProperty("model"), "model 不得被改写")
        assertEquals(effortBefore, after.getProperty("effort"), "effort 不得被改写")
        assertEquals("LOW", after.getProperty("effort"), "留空的思考档位应保持 LOW")
    }

    @Test
    fun `mock never stores a key`() {
        val dir = Files.createTempDirectory("qianyan-credentials-mock")
        val graph = graphAt(dir)
        val state = DesktopAppState(graph)

        assertTrue(saveProviderFromSettings(state, ProviderType.MOCK, "test-key", ""))

        assertFalse(graph.isConfigured(ProviderType.MOCK), "MOCK 不收 Key")
        assertEquals(ProviderType.MOCK, graph.provider.value)
    }

    @Test
    fun `clear key is reachable from the settings page`() {
        val settings = File("src/jvmMain/kotlin/com/qianyan/app/desktop/ui/settings/SettingsWindow.kt").readText()
        assertTrue("graph.clearApiKey" in settings, "设置页必须提供清除 Key 的入口")
        assertTrue("\"清除 Key\"" in settings, "清除按钮文案必须存在")

        val dir = Files.createTempDirectory("qianyan-credentials-clear")
        val graph = graphAt(dir)
        graph.credentialStore.setApiKey(ProviderType.DEEPSEEK, "test-key")
        assertTrue(graph.isConfigured(ProviderType.DEEPSEEK))
        graph.clearApiKey(ProviderType.DEEPSEEK)
        assertFalse(graph.isConfigured(ProviderType.DEEPSEEK), "清除后必须回到未配置")
    }
}
