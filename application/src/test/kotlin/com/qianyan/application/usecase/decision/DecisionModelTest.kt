package com.qianyan.application.usecase.decision

import com.qianyan.model.AuthorPreferenceId
import com.qianyan.model.author.AuthorContext
import com.qianyan.model.author.AuthorContext.AuthorCoreLite
import com.qianyan.model.author.AuthorContext.AuthorPreferenceLite
import com.qianyan.model.author.AuthorCoreScope
import com.qianyan.model.author.AuthorDnaDimension
import com.qianyan.model.author.AuthorDnaLite
import com.qianyan.model.author.Confidence
import com.qianyan.model.author.PreferenceDimension
import com.qianyan.model.author.PreferenceScope
import com.qianyan.model.decision.DecisionOutcome
import com.qianyan.model.decision.DecisionPolicy
import com.qianyan.model.decision.DecisionPolicySource
import com.qianyan.model.decision.DecisionType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * P19 · DecisionModel 测试（application；纯函数，无 DB / Provider / LLM / 时间 / 随机）。
 * 覆盖：Determinism / Precedence / No-conflict / Conflict / Fallback / Legal combination /
 *       Invalid combination / Unified contract / Boundary / Neutrality。
 */
class DecisionModelTest {

    // ---- helpers：构造最小 AuthorContext ----

    private fun pref(
        dimension: PreferenceDimension,
        statement: String,
    ) = AuthorPreferenceLite(
        preferenceId = AuthorPreferenceId("p"),
        scope = PreferenceScope.GLOBAL,
        dimension = dimension,
        statement = statement,
        confidence = Confidence.HALF,
        revocable = true,
    )

    private fun core(statement: String) = AuthorCoreLite(
        patternKey = "core:foundation",
        statement = statement,
        confidence = Confidence.HALF,
        scope = AuthorCoreScope.GLOBAL,
    )

    private fun dna(dimension: AuthorDnaDimension, value: String) = AuthorDnaLite(
        featureKey = "k",
        dimension = dimension,
        value = value,
        statement = value,
        confidence = Confidence.HALF,
        sourceRef = null,
    )

    private fun ctx(preferences: List<AuthorPreferenceLite> = emptyList(), cores: List<AuthorCoreLite> = emptyList(), dnas: List<AuthorDnaLite> = emptyList()) =
        AuthorContext(preferences = preferences, cores = cores, dna = dnas)

    // ---- 1. Determinism ----

    @Test
    fun `same input produces identical output on repeat`() {
        val c = ctx(
            preferences = listOf(pref(PreferenceDimension.STORY_DIRECTION, "作者偏好渐进揭示 progressive reveal")),
            dnas = listOf(dna(AuthorDnaDimension.NARRATIVE, "progressive")),
        )
        val a = DecisionModel.decide(c, DecisionType.STORY_DIRECTION, DecisionType.WRITING_STYLE)
        val b = DecisionModel.decide(c, DecisionType.STORY_DIRECTION, DecisionType.WRITING_STYLE)
        val d = DecisionModel.decide(c, DecisionType.STORY_DIRECTION, DecisionType.WRITING_STYLE)
        assertEquals(a, b)
        assertEquals(a, d)
    }

    // ---- 2. Precedence：Preference > Core > DNA ----

    @Test
    fun `preference wins over core and dna for story direction`() {
        val c = ctx(
            preferences = listOf(pref(PreferenceDimension.STORY_DIRECTION, "progressive 渐进揭示")),
            cores = listOf(core("direct 直接呈现")),
            dnas = listOf(dna(AuthorDnaDimension.NARRATIVE, "direct")),
        )
        val p = DecisionModel.evaluate(c, DecisionType.STORY_DIRECTION)
        assertEquals(DecisionOutcome.PROGRESSIVE_REVEAL, p.outcome)
        assertEquals(DecisionPolicySource.RULE_MATRIX, p.source)
    }

    @Test
    fun `core wins over dna when preference has no clear direction`() {
        val c = ctx(
            preferences = listOf(pref(PreferenceDimension.STORY_DIRECTION, "作者有故事方向偏好（未指明）")),
            cores = listOf(core("direct 直接呈现")),
            dnas = listOf(dna(AuthorDnaDimension.NARRATIVE, "progressive")),
        )
        val p = DecisionModel.evaluate(c, DecisionType.STORY_DIRECTION)
        assertEquals(DecisionOutcome.DIRECT_PRESENTATION, p.outcome)
        assertEquals(DecisionPolicySource.RULE_MATRIX, p.source)
    }

    // ---- 3. No conflict：同向 → RULE_MATRIX 同结果 ----

    @Test
    fun `aligned signals produce same outcome via rule matrix`() {
        val c = ctx(
            preferences = listOf(pref(PreferenceDimension.STORY_DIRECTION, "progressive 渐进揭示")),
            cores = listOf(core("progressive 渐进揭示")),
            dnas = listOf(dna(AuthorDnaDimension.NARRATIVE, "progressive")),
        )
        val p = DecisionModel.evaluate(c, DecisionType.STORY_DIRECTION)
        assertEquals(DecisionOutcome.PROGRESSIVE_REVEAL, p.outcome)
        assertEquals(DecisionPolicySource.RULE_MATRIX, p.source)
    }

    // ---- 4. Conflict per type ----

    @Test
    fun `story direction conflict resolved by preference`() {
        val c = ctx(
            preferences = listOf(pref(PreferenceDimension.STORY_DIRECTION, "direct 直接呈现")),
            cores = listOf(core("progressive 渐进揭示")),
            dnas = listOf(dna(AuthorDnaDimension.NARRATIVE, "progressive")),
        )
        assertEquals(DecisionOutcome.DIRECT_PRESENTATION, DecisionModel.evaluate(c, DecisionType.STORY_DIRECTION).outcome)
    }

    @Test
    fun `writing style conflict resolved by preference`() {
        val c = ctx(
            preferences = listOf(pref(PreferenceDimension.VOICE, "short 短句为主")),
            cores = listOf(core("long 长句为主")),
            dnas = listOf(dna(AuthorDnaDimension.SENTENCE, "long")),
        )
        assertEquals(DecisionOutcome.SHORT_DOMINANT, DecisionModel.evaluate(c, DecisionType.WRITING_STYLE).outcome)
    }

    // ---- 5. Fallback：NEUTRAL + DETERMINISTIC_DEFAULT ----

    @Test
    fun `story direction falls back to neutral`() {
        val p = DecisionModel.evaluate(ctx(), DecisionType.STORY_DIRECTION)
        assertEquals(DecisionOutcome.NEUTRAL, p.outcome)
        assertEquals(DecisionPolicySource.DETERMINISTIC_DEFAULT, p.source)
    }

    @Test
    fun `writing style falls back to neutral`() {
        val p = DecisionModel.evaluate(ctx(), DecisionType.WRITING_STYLE)
        assertEquals(DecisionOutcome.NEUTRAL, p.outcome)
        assertEquals(DecisionPolicySource.DETERMINISTIC_DEFAULT, p.source)
    }

    // ---- 6. Legal combination ----

    @Test
    fun `legal combinations only`() {
        val c = ctx(
            preferences = listOf(
                pref(PreferenceDimension.STORY_DIRECTION, "progressive"),
                pref(PreferenceDimension.VOICE, "short"),
            ),
            dnas = listOf(
                dna(AuthorDnaDimension.NARRATIVE, "direct"),
                dna(AuthorDnaDimension.SENTENCE, "long"),
            ),
        )
        val policies = DecisionModel.decide(c, DecisionType.STORY_DIRECTION, DecisionType.WRITING_STYLE)
        policies.forEach { p ->
            assertTrue(legal(p), "非法组合: $p")
        }
    }

    private fun legal(p: DecisionPolicy): Boolean = when (p.decisionType) {
        DecisionType.STORY_DIRECTION -> p.outcome == DecisionOutcome.PROGRESSIVE_REVEAL ||
            p.outcome == DecisionOutcome.DIRECT_PRESENTATION || p.outcome == DecisionOutcome.NEUTRAL
        DecisionType.WRITING_STYLE -> p.outcome == DecisionOutcome.SHORT_DOMINANT ||
            p.outcome == DecisionOutcome.LONG_DOMINANT || p.outcome == DecisionOutcome.NEUTRAL
    }

    // ---- 7. Invalid combination：Rule Matrix 不产生非法组合 ----

    @Test
    fun `rule matrix never produces cross-type outcome`() {
        val c = ctx(
            preferences = listOf(
                pref(PreferenceDimension.STORY_DIRECTION, "progressive 渐进揭示"),
                pref(PreferenceDimension.VOICE, "short 短句"),
            ),
            cores = listOf(core("direct")),
            dnas = listOf(
                dna(AuthorDnaDimension.NARRATIVE, "progressive"),
                dna(AuthorDnaDimension.SENTENCE, "long"),
            ),
        )
        val policies = DecisionModel.decide(c, DecisionType.STORY_DIRECTION, DecisionType.WRITING_STYLE)
        val story = policies.first { it.decisionType == DecisionType.STORY_DIRECTION }
        val style = policies.first { it.decisionType == DecisionType.WRITING_STYLE }
        assertEquals(DecisionOutcome.PROGRESSIVE_REVEAL, story.outcome)
        assertEquals(DecisionOutcome.SHORT_DOMINANT, style.outcome)
    }

    // ---- 8. Unified contract ----

    @Test
    fun `both decision types return same DecisionPolicy type`() {
        val c = ctx(
            preferences = listOf(pref(PreferenceDimension.STORY_DIRECTION, "progressive"), pref(PreferenceDimension.TONE, "long")),
        )
        val policies = DecisionModel.decide(c, DecisionType.STORY_DIRECTION, DecisionType.WRITING_STYLE)
        assertEquals(2, policies.size)
        assertTrue(policies.all { it is DecisionPolicy })
        assertEquals(setOf(DecisionType.STORY_DIRECTION, DecisionType.WRITING_STYLE), policies.map { it.decisionType }.toSet())
    }

    // ---- 9. Boundary：无 Repository / Storage / Provider / LLM / Random / Clock 依赖 ----
    // 由纯函数签名 + 无任何外部依赖类型导入保证；此处验证决策不随时间/输入之外的任何状态变化。

    @Test
    fun `decision is independent of external state`() {
        val c = ctx(preferences = listOf(pref(PreferenceDimension.STORY_DIRECTION, "direct")))
        val before = DecisionModel.evaluate(c, DecisionType.STORY_DIRECTION)
        val after = DecisionModel.evaluate(c, DecisionType.STORY_DIRECTION)
        assertEquals(before, after)
    }

    // ---- 10. Neutrality ----

    @Test
    fun `neutral outcome carries no author preference`() {
        val p = DecisionModel.evaluate(ctx(), DecisionType.WRITING_STYLE)
        assertEquals(DecisionOutcome.NEUTRAL, p.outcome)
        // NEUTRAL 不等于任何具体倾向值
        assertTrue(p.outcome != DecisionOutcome.SHORT_DOMINANT && p.outcome != DecisionOutcome.LONG_DOMINANT)
        assertTrue(p.outcome != DecisionOutcome.PROGRESSIVE_REVEAL && p.outcome != DecisionOutcome.DIRECT_PRESENTATION)
    }

    // ---- 附加：UseCase / Gateway seam ----

    @Test
    fun `use case decide is public entry`() {
        val uc = DecisionModelUseCases()
        val c = ctx(preferences = listOf(pref(PreferenceDimension.VOICE, "short 短句")))
        val out = uc.decide(c, DecisionType.WRITING_STYLE)
        assertEquals(1, out.size)
        assertEquals(DecisionOutcome.SHORT_DOMINANT, out.first().outcome)
    }

    @Test
    fun `facade delegates`() {
        val gateway: DecisionModelGateway = DecisionModelFacade(DecisionModelUseCases())
        val c = ctx()
        assertEquals(
            listOf(DecisionModel.evaluate(c, DecisionType.STORY_DIRECTION)),
            gateway.decide(c, DecisionType.STORY_DIRECTION),
        )
    }
}
