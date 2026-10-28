package com.qianyan.application.usecase.context

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** AC-16：Task 级 Context/Pack 不得依赖 LCL `ChapterContextPack` 的版本权威。 */
class TargetContextAuthorityTest {

    @Test
    fun `target context packages do not reference the lcl chapter pack`() {
        val roots = listOf(
            "src/main/kotlin/com/qianyan/application/usecase/context",
            "src/main/kotlin/com/qianyan/application/usecase/agent",
        )
        val code = buildString {
            roots.forEach { root ->
                File(root).walkTopDown()
                    .filter { it.isFile && it.extension == "kt" }
                    .forEach { appendLine(it.readText()) }
            }
        }

        assertTrue("ChapterContextPack" !in code, "Task Context / Agent 不得依赖 LCL ChapterContextPack")
        assertTrue("chapterContextPack" !in code, "Task Context / Agent 不得引用 LCL 编译入口")
    }
}
