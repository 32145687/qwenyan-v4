package com.qianyan.application.usecase.tool

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase 5 Gate · 无旁路结构守卫。
 *
 * 生产代码中：
 *  - 非空 ToolRegistry 只由 `AgentToolBridge`（桥接 ProductToolService）或 `ProductToolService` 构造；
 *  - `ToolExecutor` 只允许两种形态：空 Registry（无工具 Agent）或桥接 Registry（Planner）；
 *  - `AgentRuntime` 不依赖 ProductToolService（层方向正确）。
 */
class ToolBypassGuardTest {

    private val roots = listOf(
        File("src/main/kotlin"),
        File("../agent/runtime/src/main/kotlin"),
        File("../agent/tool/src/main/kotlin"),
    )

    @Test
    fun `non empty tool registries are only built by service or bridge`() {
        val offenders = productionFiles()
            .filter { (file, text) ->
                "ToolRegistry().also" in text &&
                    !file.endsWith("usecase/tool/AgentToolBridge.kt") &&
                    !file.endsWith("usecase/tool/ProductToolService.kt")
            }
            .map { it.first }
        assertEquals(emptyList(), offenders, "非空 ToolRegistry 只能由 ProductToolService / AgentToolBridge 构造")
    }

    @Test
    fun `tool executors are empty or bridge backed`() {
        val offenders = mutableListOf<String>()
        productionFiles().forEach { (file, text) ->
            text.lineSequence().forEachIndexed { index, line ->
                if ("ToolExecutor(" !in line) return@forEachIndexed
                val allowed = when {
                    line.contains("class ToolExecutor(") -> true // 类型定义，不是构造调用
                    file.endsWith("usecase/tool/ProductToolService.kt") ->
                        line.contains("ToolExecutor(registry)")
                    file.endsWith("usecase/writing/planning/PlannerAgent.kt") ->
                        line.contains("ToolExecutor(ToolRegistry())") ||
                            line.contains("ToolExecutor(bridge.runtimeRegistry(")
                    file.contains("src/main/kotlin/") ->
                        line.contains("ToolExecutor(ToolRegistry())")
                    else -> false
                }
                if (!allowed) offenders += "$file:${index + 1}: ${line.trim()}"
            }
        }
        assertEquals(emptyList(), offenders, "生产 ToolExecutor 只能是空 Registry 或 AgentToolBridge 桥接 Registry")
    }

    @Test
    fun `agent runtime does not depend on the application tool service`() {
        val runtime = productionFiles().first { it.first.endsWith("agent/runtime/AgentRuntime.kt") }.second
        assertTrue("ProductToolService" !in runtime, "AgentRuntime 不得反向依赖 Application 调度层")
        assertTrue("toolExecutor.execute(" in runtime, "AgentRuntime 必须经 ToolExecutor 执行桥接工具")
    }

    private fun productionFiles(): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        roots.filter { it.isDirectory }.forEach { root ->
            root.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .forEach { file -> out += file.invariantSeparatorsPath to file.readText() }
        }
        assertTrue(out.isNotEmpty(), "找不到生产源码")
        return out
    }
}
