package com.qianyan.app.desktop

import com.qianyan.app.desktop.adapter.FileProviderCredentialStore
import com.qianyan.app.desktop.di.CustomModel
import com.qianyan.app.desktop.di.DesktopGraph
import com.qianyan.app.desktop.ui.DesktopAppState
import com.qianyan.app.desktop.ui.workspace.effectiveChoice
import com.qianyan.app.desktop.ui.workspace.reasoningChoices
import com.qianyan.app.desktop.ui.workspace.reasoningNote
import com.qianyan.provider.ChatMessage
import com.qianyan.provider.ChatRole
import com.qianyan.provider.ModelProfile
import com.qianyan.provider.ProviderRequest
import com.qianyan.provider.ProviderType
import com.qianyan.provider.ReasoningEffort
import com.qianyan.provider.impl.DefaultProviderAssembler
import com.qianyan.provider.impl.transport.HttpResponse
import com.qianyan.provider.impl.transport.JdkLlmHttpClient
import com.qianyan.provider.impl.transport.LlmHttpClient
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * M-1 · 桌面三级模型选择（真实 DesktopGraph + 临时 appDir，fake transport，无真实网络）。
 *
 * 守住：模型/强度选择经 saveProviderConfig 触发容器重建；busy 与未配置 Key 的拒绝不落半改状态；
 * 第三级控件按厂商分形态；清单候选不含 mock-v1。
 */
class DesktopModelSelectionTest {

    private val okJson =
        """{"choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}],
           "usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}"""

    private fun graphAt(
        dir: Path = Files.createTempDirectory("qianyan-model-selection"),
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

    private fun ping(modelId: String) = ProviderRequest(
        model = ModelProfile(id = modelId),
        messages = listOf(ChatMessage(ChatRole.USER, "ping")),
    )

    @Test
    fun `selecting model and effort rebuilds container and next request carries effort`() {
        var body: String? = null
        val client = LlmHttpClient { _, _, sent ->
            body = sent
            HttpResponse(200, okJson)
        }
        val graph = graphAt(client = client)
        graph.credentialStore.setApiKey(ProviderType.DEEPSEEK, "test-key")
        val before = graph.container

        assertTrue(
            graph.saveProviderConfig(
                ProviderType.DEEPSEEK,
                modelId = "deepseek-v4-pro",
                reasoningEffort = ReasoningEffort.LOW,
            ),
        )

        assertNotSame(before, graph.container, "切换模型必须重建容器")
        assertEquals("deepseek-v4-pro", graph.modelId())
        assertEquals("deepseek-v4-pro", graph.effectiveModel.value)
        assertEquals("deepseek-v4-pro", graph.currentConfiguration().model?.id)
        assertEquals(ReasoningEffort.LOW, graph.currentConfiguration().reasoningEffort)

        // 下一次请求：用同一份生效配置经同一装配链组装并真实发出 fake HTTP
        DefaultProviderAssembler(graph.credentialStore, client)
            .assemble(graph.currentConfiguration())
            .chat(ping("deepseek-v4-pro"))
        assertTrue(body!!.contains("\"model\":\"deepseek-v4-pro\""), body)
        assertTrue(body!!.contains("\"reasoning_effort\":\"low\""), body)
    }

    @Test
    fun `busy selection is rejected without changing state or file`() {
        val graph = graphAt()
        graph.credentialStore.setApiKey(ProviderType.DEEPSEEK, "test-key")
        assertTrue(graph.saveProviderConfig(ProviderType.DEEPSEEK, modelId = "deepseek-v4-pro", reasoningEffort = ReasoningEffort.LOW))
        val state = DesktopAppState(graph)
        state.busy = true

        val selection = graph.appDir.resolve("provider-selection.properties").toFile()
        val fileBefore = selection.readText()
        val providerBefore = graph.provider.value
        val modelBefore = graph.modelId()
        val effortBefore = graph.reasoningEffort()
        val containerBefore = graph.container

        assertFalse(state.chooseProviderModel(ProviderType.DEEPSEEK, modelId = "deepseek-v4-pro", reasoningEffort = ReasoningEffort.MAX))

        assertEquals(providerBefore, graph.provider.value)
        assertEquals(modelBefore, graph.modelId())
        assertEquals(effortBefore, graph.reasoningEffort())
        assertSame(containerBefore, graph.container, "被拒绝时不得重建容器")
        assertEquals(fileBefore, selection.readText(), "被拒绝时不得改配置文件")
        assertEquals("任务进行中，暂时不能切换模型", state.toast)
    }

    @Test
    fun `unconfigured provider opens settings and keeps current provider`() {
        val graph = graphAt()
        val state = DesktopAppState(graph)
        val before = graph.provider.value

        assertFalse(state.chooseProviderModel(ProviderType.MIMO, modelId = "mimo-v2.6-pro"))

        assertEquals(before, graph.provider.value, "未配置 Key 时当前 Provider 不得变化")
        assertTrue(state.settingsOpen, "必须打开设置窗口")
        assertEquals("provider", state.settingsSection, "未配置 Key 必须定位到 AI Provider 分区（Key 录入处）")
        assertEquals(ProviderType.MIMO, state.settingsProvider, "O-64：设置页必须预选用户刚点的厂商")
        assertTrue(state.toast?.contains("API Key") == true, state.toast)
    }

    @Test
    fun `effort persists across restart and bad value falls back to high`() {
        val dir = Files.createTempDirectory("qianyan-effort")
        val first = graphAt(dir)
        first.credentialStore.setApiKey(ProviderType.DEEPSEEK, "test-key")
        assertTrue(first.saveProviderConfig(ProviderType.DEEPSEEK, modelId = "deepseek-v4-pro", reasoningEffort = ReasoningEffort.MAX))
        assertEquals(ReasoningEffort.MAX, graphAt(dir).reasoningEffort())

        val file = dir.resolve("provider-selection.properties").toFile()
        val properties = Properties().apply { file.inputStream().use { load(it) } }
        properties.setProperty("effort", "ultra")
        file.outputStream().use { properties.store(it, null) }
        assertEquals(ReasoningEffort.HIGH, graphAt(dir).reasoningEffort(), "无法识别的 effort 必须回落 high")
    }

    @Test
    fun `reasoning third level shape differs by provider`() {
        assertEquals(listOf("none", "low", "high", "max"), reasoningChoices(ProviderType.DEEPSEEK).map { it.label })
        assertEquals(
            listOf(ReasoningEffort.NONE, ReasoningEffort.LOW, ReasoningEffort.HIGH, ReasoningEffort.MAX),
            reasoningChoices(ProviderType.DEEPSEEK).map { it.effort },
        )
        assertEquals(listOf("关", "开"), reasoningChoices(ProviderType.MIMO).map { it.label })
        assertTrue(reasoningChoices(ProviderType.MOCK).isEmpty(), "离线示意不提供思考档位")
        assertEquals("离线示意模型不提供思考能力，不会发送思考参数", reasoningNote(ProviderType.MOCK))
        assertNull(reasoningNote(ProviderType.DEEPSEEK))
        assertEquals(ReasoningEffort.HIGH, effectiveChoice(ProviderType.MIMO, ReasoningEffort.MAX))
        assertEquals(ReasoningEffort.NONE, effectiveChoice(ProviderType.MIMO, ReasoningEffort.NONE))
    }

    @Test
    fun `model candidates exclude mock and follow the catalog`() {
        val graph = graphAt()
        assertEquals(listOf("deepseek-v4-flash", "deepseek-v4-pro"), graph.customModels(ProviderType.DEEPSEEK).map { it.modelId })
        assertTrue(graph.customModels(ProviderType.MOCK).isEmpty())
        assertTrue(graph.customModels().none { it.modelId == "mock-v1" }, "清单不得显示 mock-v1")

        assertTrue(graph.addCustomModel(CustomModel(ProviderType.MIMO, "mimo-v2.6-pro-x", "MiMo X")))
        assertTrue(graph.customModels(ProviderType.MIMO).any { it.modelId == "mimo-v2.6-pro-x" })
        assertEquals(1, graph.customModelsRevision.value, "清单变更必须 bump 版本供 UI 刷新")
    }
}
