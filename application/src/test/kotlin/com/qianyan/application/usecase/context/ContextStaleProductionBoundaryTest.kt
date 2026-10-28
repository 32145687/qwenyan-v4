package com.qianyan.application.usecase.context

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * AC-15 · `isStale` 的当前契约边界。
 *
 * 语义（4A D4 冻结）：`isStale(pack)` = 用 `pack.request` 在当前底层数据下重编并比对版本。
 * 当前架构没有 Pack 复用/缓存，因此没有生产消费点；本测试防止“为验收虚构消费”。
 */
class ContextStaleProductionBoundaryTest {

    private val mainRoot = "src/main/kotlin/com/qianyan/application"

    @Test
    fun `isStale has no production caller`() {
        val offenders = mainSources()
            .filter { (file, text) -> file != "usecase/context/ContextEngineUseCases.kt" && ".isStale(" in text }
            .map { it.first }
        assertEquals(emptyList(), offenders, "当前架构不得虚构 isStale 生产消费点")
    }

    @Test
    fun `store and inspector never rebuild or judge staleness`() {
        val store = File("$mainRoot/usecase/context/ContextPackStore.kt").readText()
        val workspace = File("$mainRoot/usecase/workspace/WorkspaceUseCases.kt").readText()
        assertEquals(emptyList(), listOf(store, workspace).filter { "isStale" in it })
        assertTrue("contextEngine.build" !in workspace, "Inspector 不得触发 Context 构建")
    }

    @Test
    fun `engine keeps the frozen recompile comparison`() {
        val engine = File("$mainRoot/usecase/context/ContextEngineUseCases.kt").readText()
        assertTrue("packVersion != pack.packVersion" in engine, "isStale 必须保持重编比对语义")
    }

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
