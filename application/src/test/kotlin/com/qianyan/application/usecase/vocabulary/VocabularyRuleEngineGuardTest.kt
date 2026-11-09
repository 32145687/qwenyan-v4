package com.qianyan.application.usecase.vocabulary

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T-O37-A · 源码级守卫：
 *  1. [VocabularyRuleEngine] 是纯函数（无仓储 / LLM / Draft / Commit / Change 依赖）；
 *  2. 继续写与改写两条生产路径调用**同一个**引擎，禁止各自复制一份替换实现。
 *
 * 说明：引擎语义由 [VocabularyRuleEngineTest] 断言；这里防止后续接线把实现就地复制或注入 IO。
 */
class VocabularyRuleEngineGuardTest {

    private fun source(relative: String): String {
        val candidates = listOf(
            File(relative),
            File("application/$relative"),
            File("../$relative"),
        )
        return candidates.firstOrNull { it.isFile }?.readText()
            ?: error("找不到源码：$relative（cwd=${File(".").absolutePath}）")
    }

    private fun importsOf(text: String): List<String> =
        text.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("import ") }
            .map { it.removePrefix("import ").substringBefore(" as ") }
            .toList()

    @Test
    fun `engine has no repository llm draft commit or change dependency`() {
        val text = source("src/main/kotlin/com/qianyan/application/usecase/vocabulary/VocabularyRuleEngine.kt")
        val imports = importsOf(text)
        assertEquals(5, imports.size, "引擎只应依赖模型层：$imports")
        assertTrue(imports.all { it.startsWith("com.qianyan.model.vocabulary.") }, "非法依赖：$imports")
        assertTrue(imports.none { "repository" in it.lowercase() || "provider" in it.lowercase() })
        assertTrue(imports.none { "draft" in it.lowercase() || "commit" in it.lowercase() || "change" in it.lowercase() })
        // 纯对象：无构造参数（不得注入仓储 / LLM）
        assertTrue(text.contains("object VocabularyRuleEngine {"))
    }

    @Test
    fun `both ai paths call the single shared engine`() {
        val continuePath = source("src/main/kotlin/com/qianyan/application/usecase/writing/WritingExecutionUseCase.kt")
        val rewritePath = source("src/main/kotlin/com/qianyan/application/usecase/writing/WriterFacade.kt")
        listOf("继续写" to continuePath, "改写" to rewritePath).forEach { (label, text) ->
            assertEquals(
                1,
                Regex("""VocabularyRuleEngine\.apply\(""").findAll(text).count(),
                "$label 路径必须且只能调用一次共享引擎",
            )
            assertTrue(
                Regex("""vocabularyRules\.activeFor\(""").findAll(text).count() == 1,
                "$label 路径必须经 VocabularyRuleUseCases 取规则",
            )
            assertTrue(
                "object VocabularyRuleEngine" !in text,
                "$label 路径不得复制引擎实现",
            )
            assertTrue(
                "VocabularyRuleSkipReason" !in text,
                "$label 路径不得复制跳过判定",
            )
        }
    }

    @Test
    fun `write page renders rule skips and never touches repositories`() {
        val text = source("../app/desktop/src/jvmMain/kotlin/com/qianyan/app/desktop/ui/pages/WritePage.kt")
        assertTrue("条规则未执行" in text, "候选审查区必须渲染未执行规则提示")
        assertTrue("proposal.ruleSkips" in text, "跳过提示必须来自待审提案（而非另建数据源）")
        assertTrue("ruleSkipSummary" in text, "跳过提示按原因汇总")
        assertTrue(
            Regex("repository", RegexOption.IGNORE_CASE).findAll(text).none(),
            "桌面审查区不得直连 repository",
        )
    }
}
