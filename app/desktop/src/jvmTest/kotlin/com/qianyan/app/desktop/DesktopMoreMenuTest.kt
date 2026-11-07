package com.qianyan.app.desktop

import com.qianyan.app.desktop.adapter.FileProviderCredentialStore
import com.qianyan.app.desktop.di.DesktopGraph
import com.qianyan.app.desktop.ui.DesktopAppState
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
 * T-M1B2 · 界面收口守卫（A-13）：顶栏齿轮直达设置、左栏页脚删除、旧 Provider 弹窗运行时不可达。
 *
 * 页脚删除是需求变更（A-13 ①）：其"已配置 / 未配置 / MOCK 离线示意"诚实标记迁移到 composer 模型按钮区，
 * 因此本文件对页脚的断言方向由"仍在"反转为"零残留"，强度不降反升（另加标记迁移断言）。
 */
class DesktopMoreMenuTest {

    private val main = File("src/jvmMain/kotlin/com/qianyan/app/desktop/Main.kt").readText()
    private val palette = File("src/jvmMain/kotlin/com/qianyan/app/desktop/ui/workspace/CommandPalette.kt").readText()
    private val composer = File("src/jvmMain/kotlin/com/qianyan/app/desktop/ui/workspace/TaskComposer.kt").readText()
    private val settings = File("src/jvmMain/kotlin/com/qianyan/app/desktop/ui/settings/SettingsWindow.kt").readText()
    private val stateSource = File("src/jvmMain/kotlin/com/qianyan/app/desktop/ui/Common.kt").readText()

    /** 取 [startMarker] 到 [endMarker] 之间的源码段（用于把断言限定在单个可组合函数内）。 */
    private fun section(source: String, startMarker: String, endMarker: String): String {
        val from = source.indexOf(startMarker)
        val to = source.indexOf(endMarker, from)
        assertTrue(from >= 0 && to > from, "未找到代码段：$startMarker … $endMarker")
        return source.substring(from, to)
    }

    private fun graphAt(
        dir: Path = Files.createTempDirectory("qianyan-more-menu"),
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

    @Test
    fun `top bar settings is a gear button without a secondary menu`() {
        val button = section(main, "private fun SettingsButton", "/* ---------------- 项目切换 ---------------- */")
        assertTrue("state.settingsOpen = true" in button, "齿轮必须直达设置窗口")
        assertTrue("hoverBackground" in button && ".size(28.dp)" in button, "hover/尺寸沿用顶栏按钮规格")
        assertTrue("Canvas(" in button && "repeat(8)" in button, "齿轮必须是既有 Canvas 线条画法，不引新依赖")
        listOf("DropdownMenu", "DropdownMenuItem", "MoreMenu").forEach { token ->
            assertFalse(token in button, "齿轮不再有二级菜单：不得出现 '$token'")
        }
        assertFalse("\"···\"" in main, "旧的「···」入口必须消失")
        assertTrue("SettingsButton(state)" in main, "顶栏必须组合齿轮按钮")
    }

    @Test
    fun `provider footer is removed while the honesty marker moves to the composer`() {
        // A-13 ①：页脚删除（断言方向已按需求变更反转）
        assertFalse("ProviderFooter" in main, "左栏页脚必须删除")
        assertFalse("AI PROVIDER" in main, "页脚文案不得残留")
        // 状态栏仍不重复播报 Provider / 模型，快捷键提示保留
        val bar = section(main, "private fun StatusBar", "private fun StatusText")
        assertFalse("provider.name" in bar, "状态栏不得显示 Provider 名")
        assertFalse("effectiveModel" in bar, "状态栏不得显示模型 ID")
        assertTrue("+K 命令" in bar && "+, 设置" in bar, "状态栏右侧快捷键提示必须保留")

        // 诚实标记迁移：composer 模型按钮区仍区分 可用 / 未配置 / 离线示意，MOCK 不点绿点
        val marker = section(composer, "private fun ProviderMarker", "private fun ModelMenu")
        assertTrue("\"离线示意\"" in marker && "\"可用\"" in marker && "\"未配置\"" in marker, "三态文案必须保留")
        assertTrue(
            "provider != ProviderType.MOCK && state.graph.isConfigured(provider)" in marker,
            "绿点判定必须保持「已配置且不是 MOCK」",
        )
        assertTrue(
            "if (configured) palette.add else palette.text3.copy(alpha = 0.5f)" in marker,
            "MOCK / 未配置不得显示成在线绿点",
        )
        assertTrue("state.graph.isConfigured" in composer, "标记必须来自真实 Key 状态")
        assertTrue("ProviderMarker(state)" in composer, "标记必须组合在 composer 内")
    }

    @Test
    fun `legacy provider dialog is fully removed`() {
        // T-M1C（O-63）：能力已迁入设置页，旧链整体删除（需求变更，方向反转）
        val mainDir = File("src/jvmMain")
        val offenders = mainDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .mapNotNull { file ->
                val text = file.readText()
                val hits = listOf("providerSettingsOpen", "ProviderSettingsDialog").filter { it in text }
                if (hits.isEmpty()) null else "${file.name}: $hits"
            }
            .toList()
        assertTrue(offenders.isEmpty(), "src/jvmMain 不得残留旧弹窗痕迹，实际：$offenders")
        assertFalse(
            File("src/jvmMain/kotlin/com/qianyan/app/desktop/ui/settings/ProviderSettings.kt").exists(),
            "旧弹窗文件本体必须删除",
        )
        assertFalse("ProviderSettings" in main, "主壳不得再引用旧弹窗")

        // 现存的配置入口全部指向设置窗口的明确分区
        assertTrue("\"模型与 Provider\"" in palette, "命令面板标签保持「模型与 Provider」")
        assertFalse("AI Provider 设置" in palette, "旧标签不得残留")
        assertTrue("state.openSettingsSection(SETTINGS_PROVIDER_SECTION)" in palette, "命令面板必须定位到 AI Provider 分区")
        val chooser = section(stateSource, "fun chooseProviderModel", "fun launchWork")
        assertTrue("openSettingsSection(SETTINGS_PROVIDER_SECTION)" in chooser, "未配置 Key 兜底必须落在 AI Provider 分区")
        assertEquals(
            3,
            Regex("state\\.openSettingsSection\\(").findAll(composer).count(),
            "composer 三处入口（未配置 / 清单为空 / 模型管理）都要走统一入口",
        )
        assertTrue("state.openSettingsSection(SETTINGS_PROVIDER_SECTION)" in composer, "未配置厂商要落在 AI Provider")
        assertEquals(
            2,
            Regex("state\\.openSettingsSection\\(SETTINGS_MODELS_SECTION\\)").findAll(composer).count(),
            "清单为空与模型管理要落在模型分区",
        )
    }

    @Test
    fun `settings window honours and consumes the requested section`() {
        assertTrue("SettingsItem(\"models\", \"模型\")" in settings, "设置导航必须有 models 分区")
        assertTrue("\"models\" -> ModelSettings(state)" in settings, "models 分区必须渲染模型页")
        assertTrue("val requested = state.settingsSection" in settings, "初始分区必须取自状态")
        assertTrue(
            "LaunchedEffect(Unit) { state.settingsSection = null }" in settings,
            "定位分区必须一次性消费，否则下次普通打开仍会跳页",
        )
        assertTrue("firstOrNull { it.key == requested }?.key" in settings, "未知分区必须回落默认首页")
    }

    @Test
    fun `focus mode stays reachable after leaving the more menu`() {
        assertTrue("\"专注模式\"" in palette, "命令面板必须仍提供专注模式")
        assertTrue("PanelToggle.FOCUS" in palette, "命令面板必须真的切换专注模式")
        assertTrue("\"视图\", \"折叠 / 展开左侧导航\"" in palette && "\"视图\", \"折叠 / 展开检查器\"" in palette)
    }

    @Test
    fun `openSettingsSection locates the requested section and refuses while busy`() {
        val state = DesktopAppState(graphAt())

        state.openSettingsSection("provider")
        assertTrue(state.settingsOpen, "必须打开设置窗口")
        assertEquals("provider", state.settingsSection, "必须定位到请求的分区")

        state.settingsOpen = false
        state.settingsSection = null
        state.openSettingsSection("models")
        assertEquals("models", state.settingsSection, "分区必须按入参定位")

        state.settingsOpen = false
        state.settingsSection = null
        state.busy = true
        state.openSettingsSection("provider")
        assertFalse(state.settingsOpen, "任务进行中不得打开设置")
        assertNull(state.settingsSection, "被拒绝时不得留下定位残留")
        assertEquals("任务进行中，请等待完成后再修改模型配置", state.toast)
    }

    /** O-62 回归：不改档位直接保存，不得把 effort 写成 NONE。 */
    @Test
    fun `saving without touching effort keeps the stored value`() {
        val dir = Files.createTempDirectory("qianyan-effort-keep")
        val graph = graphAt(dir)
        graph.credentialStore.setApiKey(ProviderType.DEEPSEEK, "test-key")
        assertEquals(ReasoningEffort.HIGH, graph.reasoningEffort(), "空配置的 effort 初值必须是 HIGH")

        // 与设置窗口保存同一调用形态：不传 reasoningEffort
        assertTrue(graph.saveProviderConfig(ProviderType.DEEPSEEK, modelId = "deepseek-v4-flash"))

        val properties = Properties().apply {
            dir.resolve("provider-selection.properties").toFile().inputStream().use { load(it) }
        }
        assertEquals("HIGH", properties.getProperty("effort"), "未改档位保存不得改写 effort")
        assertEquals(ReasoningEffort.HIGH, graphAt(dir).reasoningEffort(), "重启后仍为 HIGH")
    }
}
