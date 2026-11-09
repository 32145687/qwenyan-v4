package com.qianyan.application.usecase.vocabulary

import com.qianyan.model.NovelId
import com.qianyan.model.VariantId
import com.qianyan.model.VocabularyId
import com.qianyan.model.VocabularyRuleId
import com.qianyan.model.vocabulary.VocabularyRule
import com.qianyan.model.vocabulary.VocabularyRuleSkipReason
import com.qianyan.model.vocabulary.VocabularyScopeLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * T-O37-A · VocabularyRule 确定性执行引擎。
 *
 * 覆盖：无规则原样 / 命中计数与位置 / 最长匹配 / 作用域优先 / 非重叠 / 不级联 /
 * 词边界（ASCII）/ 中文子串 / 大小写敏感 / 六类跳过原因 / 二次执行幂等。
 */
class VocabularyRuleEngineTest {

    private val novel = NovelId("n1")
    private val variant = VariantId("v1")

    private fun rule(
        id: String,
        from: String,
        to: String,
        scope: VocabularyScopeLevel = VocabularyScopeLevel.NOVEL,
        enabled: Boolean = true,
        deterministicOnly: Boolean = true,
        variantId: VariantId? = null,
    ) = VocabularyRule(
        ruleId = VocabularyRuleId(id),
        vocabularyId = VocabularyId("vocab"),
        novelId = novel,
        variantId = variantId,
        scopeLevel = scope,
        from = from,
        to = to,
        enabled = enabled,
        deterministicOnly = deterministicOnly,
    )

    @Test
    fun `no rules returns the same string instance`() {
        val content = "灵石与星石"
        val result = VocabularyRuleEngine.apply(content, emptyList())
        assertSame(content, result.content, "无规则必须原样返回同一字符串")
        assertTrue(result.hits.isEmpty())
        assertTrue(result.skipped.isEmpty())
    }

    @Test
    fun `single rule records count and first index`() {
        val result = VocabularyRuleEngine.apply("灵石、灵石、灵石", listOf(rule("r1", "灵石", "玄石")))
        assertEquals("玄石、玄石、玄石", result.content)
        val hit = result.hits.single()
        assertEquals(3, hit.count)
        assertEquals(0, hit.firstIndex)
        assertEquals("灵石", hit.from)
        assertEquals("玄石", hit.to)
    }

    @Test
    fun `longest from wins`() {
        val result = VocabularyRuleEngine.apply(
            "黑水驿",
            listOf(rule("short", "黑水", "A"), rule("long", "黑水驿", "B")),
        )
        assertEquals("B", result.content)
        assertEquals(VocabularyRuleId("long"), result.hits.single().ruleId)
    }

    @Test
    fun `variant scope is applied before novel for the same from`() {
        val result = VocabularyRuleEngine.apply(
            "灵石",
            listOf(
                rule("novel", "灵石", "N"),
                rule("variant", "灵石", "V", VocabularyScopeLevel.VARIANT, variantId = variant),
            ),
        )
        assertEquals("V", result.content)
        assertEquals(VocabularyRuleId("variant"), result.hits.single().ruleId)
    }

    @Test
    fun `matches are non overlapping and replacement output is not rescanned`() {
        // 若级联，需要一条 from 命中替换结果（这里 "b"）的规则——那种组合已被 CHAIN_RISK 拦截，
        // 见 chain risk 用例。因此：单遍扫描 + 跳过 from.length ⇒ 不重叠、不回扫。
        val result = VocabularyRuleEngine.apply("啦啦啦", listOf(rule("r1", "啦啦", "哇")))
        assertEquals("哇啦", result.content)
        assertEquals(1, result.hits.single().count)
    }

    @Test
    fun `ascii word boundary prevents in-word replacement but allows standalone`() {
        val result = VocabularyRuleEngine.apply(
            "mane man mane",
            listOf(rule("r1", "man", "person")),
        )
        assertEquals("mane person mane", result.content)
        assertEquals(1, result.hits.single().count)
        assertEquals(5, result.hits.single().firstIndex)
    }

    @Test
    fun `cjk substring replacement has no boundary requirement`() {
        val result = VocabularyRuleEngine.apply("他摸了摸旧木箱", listOf(rule("r1", "木箱", "匣子")))
        assertEquals("他摸了摸旧匣子", result.content)
    }

    @Test
    fun `matching is case sensitive`() {
        val result = VocabularyRuleEngine.apply("Mimo mimo", listOf(rule("r1", "Mimo", "MiMo")))
        assertEquals("MiMo mimo", result.content)
        assertEquals(1, result.hits.single().count)
    }

    @Test
    fun `unsupported scopes are skipped out of scope`() {
        val result = VocabularyRuleEngine.apply(
            "灵石",
            listOf(
                rule("g", "灵石", "A", VocabularyScopeLevel.GLOBAL),
                rule("t", "灵石", "B", VocabularyScopeLevel.TASK),
            ),
        )
        assertEquals("灵石", result.content)
        assertTrue(result.hits.isEmpty())
        assertEquals(
            listOf(VocabularyRuleSkipReason.OUT_OF_SCOPE, VocabularyRuleSkipReason.OUT_OF_SCOPE),
            result.skipped.map { it.reason },
        )
    }

    @Test
    fun `all non chain skip reasons are reported deterministically`() {
        val result = VocabularyRuleEngine.apply(
            "灵石",
            listOf(
                rule("disabled", "灵石", "A", enabled = false),
                rule("nondet", "灵石", "A", deterministicOnly = false),
                rule("empty", "   ", "A"),
                rule("noop", "灵石", "灵石"),
                rule("nonidem", "灵石", "灵石堆"),
            ),
        )
        assertEquals("灵石", result.content, "全部被跳过 ⇒ 原样")
        val reasons = result.skipped.associate { it.ruleId.value to it.reason }
        assertEquals(VocabularyRuleSkipReason.DISABLED, reasons["disabled"])
        assertEquals(VocabularyRuleSkipReason.NON_DETERMINISTIC, reasons["nondet"])
        assertEquals(VocabularyRuleSkipReason.EMPTY_FROM, reasons["empty"])
        assertEquals(VocabularyRuleSkipReason.NO_OP, reasons["noop"])
        assertEquals(VocabularyRuleSkipReason.NON_IDEMPOTENT, reasons["nonidem"])
        assertEquals(5, result.skipped.size)
    }

    @Test
    fun `active rules still apply when others are skipped`() {
        val result = VocabularyRuleEngine.apply(
            "灵石",
            listOf(
                rule("disabled", "灵石", "A", enabled = false),
                rule("live", "灵石", "玄石"),
            ),
        )
        assertEquals("玄石", result.content)
        assertEquals(VocabularyRuleId("live"), result.hits.single().ruleId)
        assertEquals(VocabularyRuleSkipReason.DISABLED, result.skipped.single().reason)
    }

    @Test
    fun `chain risk is reported when replacement contains another active from`() {
        val result = VocabularyRuleEngine.apply(
            "灵石",
            listOf(
                rule("a", "灵石", "玄石"),
                rule("b", "玄石", "真石"),
            ),
        )
        val reasons = result.skipped.associate { it.ruleId.value to it.reason }
        assertEquals(VocabularyRuleSkipReason.CHAIN_RISK, reasons["a"], "a 的 to 命中 b 的 from")
        assertTrue("b" !in reasons, "b 本身无链风险")
        assertEquals("灵石", result.content)
    }

    @Test
    fun `second application is idempotent`() {
        val rules = listOf(rule("r1", "灵石", "玄石"))
        val first = VocabularyRuleEngine.apply("灵石与灵石", rules)
        assertEquals("玄石与玄石", first.content)
        val second = VocabularyRuleEngine.apply(first.content, rules)
        assertEquals(first.content, second.content)
        assertTrue(second.hits.isEmpty(), "替换后不再命中 ⇒ 幂等")
    }
}
