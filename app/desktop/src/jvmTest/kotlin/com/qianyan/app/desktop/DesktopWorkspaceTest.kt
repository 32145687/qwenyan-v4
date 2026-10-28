package com.qianyan.app.desktop

import com.qianyan.app.desktop.ui.Workspace
import com.qianyan.application.di.ApplicationContainer
import com.qianyan.model.workspace.InspectorTab
import com.qianyan.model.workspace.InspectorTaskContextState
import com.qianyan.provider.impl.MockLLMGateway
import com.qianyan.storage.db.QianyanDbFactory
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * I14 · Desktop 三栏 Workspace / Context Inspector 守卫（§13 / §14 / §24 / §28）。
 *
 * 结构守卫：UI 不得直连 Repository / SQLDelight / SQLite / Workflow / Context Engine / LLM；
 * 三栏结构（导航 + 中央工作区 + Inspector）与固定三标签必须真实存在；
 * 并验证 Desktop 经既有 ApplicationContainer 可真正驱动 Workspace Facade。
 */
class DesktopWorkspaceTest {

    private val workspaceUiDir = "src/jvmMain/kotlin/com/qianyan/app/desktop/ui/workspace"
    private val mainFile = "src/jvmMain/kotlin/com/qianyan/app/desktop/Main.kt"

    @Test
    fun `workspace ui never touches repositories storage workflows or llm`() {
        val code = sourceOf(workspaceUiDir)
        listOf(
            "Repository", "SqlDriver", "QianyanDb", "Sqlite", "SQLite", "SQLDelight", "PRAGMA",
            "WorkflowOrchestrator", "WorkflowService", "NovelAgent", "TaskQueueUseCases",
            "ContextEngineUseCases", "ContextSelector", "LLMGateway", "AgentRuntime", "ProviderAssembler",
            "WorkingDraft", "CommitUseCases", "ActionPolicy",
        ).forEach { token -> assertTrue(token !in code, "Workspace UI 不得出现 '$token'（必须经 Application 能力）") }

        // 复用证据：UI 只经既有 I14 Facade 读取只读投影
        listOf(
            "workspace.candidates", "workspace.inspector", "WorkspaceSelection",
            "WorkspaceInspectorView", "WorkspaceObjectCandidate", "InspectorTab",
        ).forEach { token -> assertTrue(token in code, "Workspace UI 必须经既有 Facade 读取：'$token'") }
    }

    @Test
    fun `three column shell is composed from navigation center workspace and inspector`() {
        val main = File(mainFile).readText()
        listOf("LeftPanel(state", "WorkspaceContent(state)", "ContextInspector(state").forEach { token ->
            assertTrue(token in main, "三栏结构缺少 '$token'")
        }
        assertTrue("Row(Modifier.weight(1f).fillMaxWidth())" in main, "三栏必须由同一 Row 组合（导航 | 工作区 | Inspector）")
        assertTrue("Workspace.entries" in main, "左栏一级导航必须来自五个固定工作区")
        assertTrue("Workspace.CREATE" in main && "Workspace.WRITE" in main && "Workspace.STORY" in main &&
            "Workspace.MATERIALS" in main && "Workspace.HISTORY" in main, "五个一级工作区必须全部接线")
        assertTrue("StatusBar(state)" in main, "必须存在状态栏（保存 / 任务 / 待确认变更）")
        assertTrue("TopBar(state, window, onClose)" in main, "必须存在顶部栏（作品 / 项目切换 / 命令面板 / 设置）")
        // I14 是 PC-first：Android 模块不得出现 I14 Workspace UI（不实现、不修改 Android）
        val androidDir = File("../../app/android/src")
        if (androidDir.isDirectory) {
            val androidCode = androidDir.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .joinToString("\n") { it.readText() }
            listOf("ContextInspector", "WorkspaceSelection", "WorkspaceObjectKind", "InspectorTab")
                .forEach { token -> assertTrue(token !in androidCode, "Android 不得被 I14 改动：'$token'") }
        }
    }

    @Test
    fun `first level navigation is fixed to five workspaces`() {
        assertEquals(
            listOf("创作", "正文", "故事", "资料", "历史"),
            Workspace.entries.map { it.title },
        )
        assertEquals(listOf(1, 2, 3, 4, 5), Workspace.entries.map { it.shortcut })
    }

    @Test
    fun `creation draft survives workspace switches`() {
        val common = File("src/jvmMain/kotlin/com/qianyan/app/desktop/ui/Common.kt").readText()
        val agent = File("src/jvmMain/kotlin/com/qianyan/app/desktop/ui/pages/AgentPage.kt").readText()
        assertTrue("var creationDraft by mutableStateOf" in common, "草稿必须提升到 DesktopAppState")
        assertTrue("creationDraft = \"\"" in common, "切换作品时必须清空旧作品草稿")
        assertTrue("state.creationDraft" in agent, "创作页必须读写 App 级草稿")
        assertFalse("remember(state.novel?.novelId" in agent, "不得再把草稿放在页面内 remember")
    }

    @Test
    fun `model id is reactive and provider hint is current`() {
        val graph = File("src/jvmMain/kotlin/com/qianyan/app/desktop/di/DesktopGraph.kt").readText()
        val main = File(mainFile).readText()
        val composer = File("$workspaceUiDir/TaskComposer.kt").readText()
        val settings = File("src/jvmMain/kotlin/com/qianyan/app/desktop/ui/settings/SettingsWindow.kt").readText()
        val providerSettings = File("src/jvmMain/kotlin/com/qianyan/app/desktop/ui/settings/ProviderSettings.kt").readText()

        assertTrue("val effectiveModel" in graph, "模型 ID 必须暴露为可观察状态")
        assertTrue("effectiveModel.collectAsState()" in main, "左栏必须收集模型 ID")
        assertTrue("effectiveModel.collectAsState()" in composer, "模型菜单必须收集模型 ID")
        assertTrue("effectiveModel.collectAsState()" in settings, "设置页必须收集模型 ID")
        assertFalse("deepseek-reasoner" in providerSettings, "不得提示已退役模型")
    }

    @Test
    fun `navigation stays fixed and top bar has a compact mode`() {
        val main = File(mainFile).readText()
        val navIndex = main.indexOf("Workspace.entries.forEach")
        val scrollIndex = main.indexOf("verticalScroll(rememberScrollState())", navIndex)
        assertTrue(navIndex >= 0 && scrollIndex > navIndex, "一级导航必须在滚动容器之前")
        assertTrue("maxWidth < 900.dp" in main, "顶栏必须有窄窗紧凑模式")
        assertTrue("ProjectSwitcher(state, compact)" in main, "顶栏必须把紧凑模式传给项目切换")
    }

    @Test
    fun `theme changes are persisted through the setter`() {
        val settings = File("src/jvmMain/kotlin/com/qianyan/app/desktop/ui/settings/SettingsWindow.kt").readText()
        assertTrue("state.updateThemeMode" in settings, "主题必须经统一 setter 持久化")
        assertFalse("state.themeMode = " in settings, "不得直接赋值内存主题，绕过持久化")
        assertTrue("QianyanTheme.isDark" in settings, "关闭跟随系统时应保留当前外观")
    }

    @Test
    fun `read failures are surfaced instead of empty`() {
        val main = File(mainFile).readText()
        listOf("作品列表读取失败", "章节目录读取失败", "读取失败").forEach { text ->
            assertTrue(text in main, "读取失败必须可见：$text")
        }
        assertFalse("getOrDefault(emptyList())" in main, "不得把读取失败吞成空列表")
        assertFalse("getOrDefault(0)" in main, "不得把读取失败吞成 0")
    }

    @Test
    fun `create novel failure releases the dialog`() {
        val common = File("src/jvmMain/kotlin/com/qianyan/app/desktop/ui/Common.kt").readText()
        val agent = File("src/jvmMain/kotlin/com/qianyan/app/desktop/ui/pages/AgentPage.kt").readText()
        assertTrue("onFailure: ((Exception) -> Unit)? = null" in common, "io 必须提供失败回调")
        assertTrue("onFailure?.invoke(e)" in common, "io 失败时必须回调")
        assertTrue("onFailure = { working = false }" in agent, "新建作品失败后必须解除 working")
    }

    @Test
    fun `window title follows the work and startup is centered`() {
        val main = File(mainFile).readText()
        assertTrue("state.novel?.title?.takeIf" in main, "原生标题应显示当前作品，而不是品牌")
        assertTrue("?: \"未打开作品\"" in main, "无作品时应使用中性标题")
        assertTrue("WindowPosition(Alignment.Center)" in main, "启动窗口应居中，避免落到屏幕外")
        assertTrue("defaultTransform.scaleX" in main && "window.setLocation(x, y)" in main, "窗口显示后必须按 DPI 缩放再次居中，防止落到屏外")
        assertFalse("title = \" \"" in main, "不得再使用空白窗口标题")
    }

    @Test
    fun `txt import failures are handled in one place`() {
        val helper = File("src/jvmMain/kotlin/com/qianyan/app/desktop/ui/TxtImport.kt")
        assertTrue(helper.isFile, "找不到 TxtImport.kt: ${helper.absolutePath}")
        val helperCode = helper.readText()
        assertTrue("try" in helperCode && "catch (e: Exception)" in helperCode, "统一入口必须捕获文件异常")
        assertTrue("file.readBytes()" in helperCode, "统一入口负责读取文件")

        listOf(
            "ui/workspace/TaskComposer.kt",
            "ui/pages/AgentPage.kt",
            "ui/workspace/CommandPalette.kt",
            "ui/settings/SettingsWindow.kt",
            "Main.kt",
        ).forEach { relative ->
            val code = File("src/jvmMain/kotlin/com/qianyan/app/desktop/$relative").readText()
            assertTrue("importTxtFromPicker()" in code, "$relative 必须复用统一导入入口")
            assertFalse("file.readBytes()" in code, "$relative 不得自行读取文件")
        }
    }

    @Test
    fun `composer sends only on ctrl or meta enter`() {
        val composer = File("$workspaceUiDir/TaskComposer.kt").readText()
        val agent = File("src/jvmMain/kotlin/com/qianyan/app/desktop/ui/pages/AgentPage.kt").readText()
        assertTrue("isCtrlPressed" in composer && "isMetaPressed" in composer, "发送必须使用 Ctrl/⌘+Enter")
        assertFalse("isShiftPressed" in composer, "不得再用 Enter/Shift+Enter 作为发送语义")
        assertTrue("Enter 换行" in agent, "必须提示普通 Enter 只换行")
    }

    @Test
    fun `thinking control does not advertise unimplemented levels`() {
        val composer = File("$workspaceUiDir/TaskComposer.kt")
        assertTrue(composer.isFile, "找不到 TaskComposer.kt: ${composer.absolutePath}")
        val code = composer.readText()
        assertTrue("思考能力" in code, "入口必须标注为思考能力")
        assertTrue("不会发送思考参数" in code, "必须向用户说明不会发送思考参数")
        listOf("\"低\"", "\"标准\"", "\"高\"", "\"极高\"", "\"自动\"").forEach { level ->
            assertFalse(level in code, "不得在思考菜单中展示未落地的档位：$level")
        }
    }

    @Test
    fun `inspector keeps exactly three fixed tabs with the product labels`() {
        assertEquals(
            listOf(InspectorTab.CONTEXT, InspectorTab.ACTIVITY, InspectorTab.CHANGES),
            InspectorTab.values().toList(),
        )
        val inspector = File("$workspaceUiDir/ContextInspector.kt").readText()
        listOf("上下文", "活动", "修改").forEach { label ->
            assertTrue(label in inspector, "Inspector 缺少固定标签 '$label'")
        }
        // 不按页面派生不同 Inspector（§8）
        listOf("ChapterInspector", "CharacterInspector", "StoryInspector", "WorldInspector", "AiInspector")
            .forEach { token -> assertTrue(token !in inspector, "不得按页面派生 Inspector：'$token'") }
    }

    @Test
    fun `desktop drives the workspace facade through the existing application container`() {
        // 与桌面装配一致：经既有 QianyanDbFactory 打开本地库（测试用临时文件库）
        val dir = java.nio.file.Files.createTempDirectory("qianyan-i14-workspace")
        val handle: com.qianyan.storage.db.QianyanDbHandle =
            QianyanDbFactory.open("jdbc:sqlite:${dir.resolve("qianyan.db").toAbsolutePath()}")
        try {
            val app = ApplicationContainer.fromDriver(handle.driver, MockLLMGateway())
            val novelId = app.novels.createOriginal(title = "桌面工作区")
            val chapter = app.chapters.createNextChapter("第一章 · 雨夜", novelId)
            app.projects.selectChapter(novelId, chapter.chapterId)
            val projectId = app.projects.projectOf(novelId).projectId

            val candidates = app.workspace.candidates(projectId)
            assertTrue(candidates.isNotEmpty(), "三栏 Workspace 的当前对象候选不得为空")

            val chapterCandidate = candidates.first { it.selection.chapterId == chapter.chapterId }
            val view = app.workspace.inspector(projectId, chapterCandidate.selection)

            assertEquals(chapter.chapterId, view.selection.chapterId)
            assertTrue(view.title.contains("第 1 章"), view.title)
            assertEquals("章节", view.detail)
            assertEquals(InspectorTaskContextState.NO_TASK, view.taskContext?.state)
            assertNull(view.context, "4B.1a 不提供重建 Pack")
            assertTrue(view.currentFacts.any { it.label == "章节" }, "Inspector 应展示当前事实")
            assertTrue(view.activities.isEmpty() && view.changes.isEmpty(), "无数据时为空态而非异常")
        } finally {
            handle.driver.close()
        }
    }

    private fun sourceOf(relativeDir: String): String {
        val dir = File(relativeDir)
        assertTrue(dir.isDirectory, "找不到源码目录：${dir.absolutePath}")
        return dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.joinToString("\n") { file ->
            file.readText().lines()
                .filterNot { line ->
                    val t = line.trimStart()
                    t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
                }
                .joinToString("\n")
        }
    }
}