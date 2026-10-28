package com.qianyan.application.usecase.context

import com.qianyan.model.context.ContextSourceKind
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * AC-11 / AC-19 · Memory 的 Phase 4 边界。
 *
 * 4A D3 冻结：Memory 是独立事实源，当前经 Resolver 进入 prompt；
 * 不是索引类型、不是 ContextPack 来源。因此 AC-11/19 条件性不适用。
 */
class MemoryContextBoundaryTest {

    @Test
    fun `memory is not a context source kind`() {
        assertFalse(
            ContextSourceKind.entries.any { it.name == "MEMORY" },
            "不得新增 MEMORY 来源类型",
        )
    }

    @Test
    fun `context package does not read memory repositories`() {
        val forbidden = listOf("MemoryRepository", "MemoryUseCases", "MemorySource")
        val offenders = mainSources().filter { (_, text) -> forbidden.any { it in text } }.map { it.first }
        assertEquals(emptyList(), offenders, "Context 包不得直接读取 Memory 事实源")
    }

    @Test
    fun `existing resolver path stays outside the context engine`() {
        val resolver = File("$mainRoot/usecase/writing/context/StoryWorldContextResolver.kt")
        assertTrue(resolver.isFile, "Memory 的既有 Resolver 路径必须保留")
        val planning = File("$mainRoot/usecase/writing/planning/PlanningContextAssembly.kt").readText()
        assertTrue("worldContextResolver" in planning, "Planning 仍必须经 Resolver 取 Memory")
    }

    private val mainRoot = "src/main/kotlin/com/qianyan/application"

    private fun mainSources(): List<Pair<String, String>> {
        val root = File("$mainRoot/usecase/context")
        assertTrue(root.isDirectory, "找不到 context 源码目录: ${root.absolutePath}")
        return root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { it.relativeTo(root).invariantSeparatorsPath to it.readText() }
            .toList()
    }
}
