package com.qianyan.app.desktop

import com.qianyan.application.di.ApplicationContainer
import com.qianyan.model.workspace.InspectorTab
import com.qianyan.provider.impl.MockLLMGateway
import com.qianyan.storage.db.QianyanDbFactory
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
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
        listOf("NavigationRail(state)", "WorkspaceHeader(state)", "ContextInspector(state)").forEach { token ->
            assertTrue(token in main, "三栏结构缺少 '$token'")
        }
        assertTrue("Row(Modifier.weight(1f).fillMaxWidth())" in main, "三栏必须由同一 Row 组合（导航 | 工作区 | Inspector）")
        assertTrue("InspectorTab.entries" in main, "中央工作区头部应提供 Inspector 固定标签入口")
        assertTrue("StatusBar(state)" in main, "必须存在状态栏（LOCKED：章节/字数/待确认/任务/保存/快捷键）")
        assertTrue("TopBar(state)" in main, "必须存在顶部栏（LOCKED 52dp）")
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
            assertTrue(view.context!!.groups.isNotEmpty(), "Inspector 上下文必须来自既有 I6")
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