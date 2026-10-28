package com.qianyan.application.usecase.context

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * AC-06 · 单一 Context 入口边界。
 *
 * 证明：
 *  - `ContextPack` 只由 `ContextEngineUseCases` 构造；
 *  - 生产 context 构建调用只出现在 Agent / Planning / Writing 三个正式入口；
 *  - 无 `assembleLegacy` 生产调用。
 *
 * `ContextEngine.build` 的内部 Variant 重载复用同一 Engine / Source / Selector / 版本管线，
 * 不是第二套 Context 构建链路。
 */
class ContextEntryBoundaryTest {

    private val mainRoot = "src/main/kotlin/com/qianyan/application"

    @Test
    fun `context packs are constructed only by the context engine`() {
        val offenders = mainSources()
            .filter { (file, text) -> file != "usecase/context/ContextEngineUseCases.kt" && hasPackConstruction(text) }
            .map { it.first }
        assertEquals(emptyList(), offenders, "ContextPack 构造必须只存在于 ContextEngineUseCases")
    }

    @Test
    fun `production context builds stay on the three formal entries`() {
        val allowed = setOf(
            "usecase/agent/NovelAgent.kt",
            "usecase/writing/planning/PlanningExecutionUseCase.kt",
            "usecase/writing/WritingExecutionUseCase.kt",
        )
        val offenders = mainSources()
            .filter { (file, text) -> file !in allowed && "contextEngine.build(" in text }
            .map { it.first }
        assertEquals(emptyList(), offenders, "生产 Context 构建只能经三个正式入口调用 ContextEngine")
    }

    @Test
    fun `legacy assembly has no production caller`() {
        val offenders = mainSources().filter { (_, text) -> ".assembleLegacy(" in text }.map { it.first }
        assertEquals(emptyList(), offenders, "正式任务不得调用 legacy assembly")
    }

    @Test
    fun `engine remains the single pack authority`() {
        val engine = File("$mainRoot/usecase/context/ContextEngineUseCases.kt").readText()
        assertTrue("return ContextPack(" in engine, "ContextEngine 必须保留唯一 Pack 构造点")
    }

    private fun hasPackConstruction(text: String): Boolean =
        Regex("""(?:return|=)\s*ContextPack\(""").containsMatchIn(text)

    private fun mainSources(): List<Pair<String, String>> {
        val root = File(mainRoot)
        assertTrue(root.isDirectory, "找不到 main 源码目录: ${root.absolutePath}")
        return root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { file ->
                val relative = file.relativeTo(root).invariantSeparatorsPath
                    .removePrefix("src/main/kotlin/com/qianyan/application/")
                relative to file.readText()
            }
            .toList()
    }
}
