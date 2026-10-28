package com.qianyan.application.usecase.writing.planning

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase 4B.1 · J-4：无 Pack 的 `PlanningContextAssembly.assemble(...)` 仅作兼容例外。
 *
 * 守卫范围：`application/src/main` 中**不得**对无 Pack 重载发起直接调用；
 * 限制：静态扫描只能覆盖其覆盖范围内的文本调用，不能证明所有运行时路径绝不调用。
 */
class NoPackAssemblyGuardTest {

    private val receivers = listOf(
        "planningContexts",
        "planningContextAssembly",
        "assembly",
        "contextAssembly",
    )

    @Test
    fun `main sources never call the no pack assemble overload`() {
        val root = mainSourceRoot()
        val offenders = mutableListOf<String>()
        root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                val text = file.readText()
                receivers.forEach { receiver ->
                    var index = text.indexOf("$receiver.assemble(")
                    while (index >= 0) {
                        val openParen = index + receiver.length + ".assemble".length
                        val arguments = balancedArguments(text, openParen)
                        if (!arguments.contains("pack", ignoreCase = true)) {
                            offenders += "${file.relativeTo(root).invariantSeparatorsPath}: $receiver.assemble(${arguments.take(100)})"
                        }
                        index = text.indexOf("$receiver.assemble(", index + 1)
                    }
                }
            }
        assertEquals(
            emptyList(),
            offenders,
            "application/src/main 不得调用无 Pack 的 assemble 重载（仅允许 assemble(request, contextPack, ...) 或 assembleLegacy）",
        )
    }

    @Test
    fun `main sources never call the internal legacy assembly`() {
        val root = mainSourceRoot()
        val offenders = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { ".assembleLegacy(" in it.readText() }
            .map { it.relativeTo(root).invariantSeparatorsPath }
            .toList()
        assertEquals(
            emptyList(),
            offenders,
            "application/src/main 不得调用 assembleLegacy；正式任务必须经 ContextEngine.build + assemble(request, pack)",
        )
    }

    @Test
    fun `compatibility overload stays deprecated`() {
        val assembly = mainSourceRoot().resolve(
            "com/qianyan/application/usecase/writing/planning/PlanningContextAssembly.kt",
        )
        assertTrue(assembly.isFile, "PlanningContextAssembly 源文件必须存在: ${assembly.absolutePath}")
        val text = assembly.readText()
        assertTrue(text.contains("@Deprecated("), "无 Pack 重载必须标记 @Deprecated")
        assertTrue(
            text.contains("fun assemble(request: UserWritingRequest): PlanningContext") ||
                text.contains("fun assemble(\n        request: UserWritingRequest,\n        continuationReference: ContinuationReference?,"),
            "无 Pack 重载签名必须保留",
        )
    }

    private fun mainSourceRoot(): File {
        val userDir = File(System.getProperty("user.dir"))
        val candidates = listOf(
            File(userDir, "src/main/kotlin"),
            File(userDir, "application/src/main/kotlin"),
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error("无法定位 application/src/main/kotlin（user.dir=$userDir）")
    }

    private fun balancedArguments(text: String, openParen: Int): String {
        var depth = 0
        for (index in openParen until text.length) {
            when (text[index]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return text.substring(openParen + 1, index)
                }
            }
        }
        return text.substring(openParen + 1)
    }
}
