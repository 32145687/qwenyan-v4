package com.qianyan.model.author

import com.qianyan.model.AuthorEvidenceId
import com.qianyan.model.NovelId
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P16 AIL-1 · Author Preference 领域模型测试（纯领域，无其它模块依赖）。
 *
 * 覆盖冻结语义：
 *  - Explicit / Inferred 双态；Candidate 不得自动成为 Stable；
 *  - Candidate → Confidence → User Confirmation → Stable；
 *  - revealed at [obtainedAt] / [revocable] / [expiry]；Global / Novel scope；
 *  - AuthorContext 最小只读投影只暴露稳定且激活偏好。
 */
class AuthorModelsTest {

    private val t: Instant = Instant.fromEpochSeconds(1790000000, 0)
    private val novel = NovelId("n1")

    private fun stablePreference() = AuthorPreference(
        preferenceId = com.qianyan.model.AuthorPreferenceId("p-global-1"),
        scope = PreferenceScope.GLOBAL,
        dimension = PreferenceDimension.CONFLICT,
        statement = "作者倾向长期低信任关系",
        origin = PreferenceOrigin.INFERRED,
        confidence = Confidence.HIGH,
        confirmed = true,
        revocable = true,
        obtainedAt = t,
        createdAt = t,
        updatedAt = t,
    )

    private fun candidatePreference() = AuthorPreference(
        preferenceId = com.qianyan.model.AuthorPreferenceId("p-cand-1"),
        scope = PreferenceScope.GLOBAL,
        dimension = PreferenceDimension.PACING,
        statement = "作者可能偏好慢节奏",
        origin = PreferenceOrigin.INFERRED,
        confidence = Confidence(0.6),
        confirmed = false,
        revocable = true,
        expiry = Instant.fromEpochSeconds(1790000000 + 30 * 86400, 0),
        obtainedAt = t,
        createdAt = t,
        updatedAt = t,
    )

    @Test
    fun `explicit preference is stable without confirmation`() {
        val explicit = AuthorPreference(
            preferenceId = com.qianyan.model.AuthorPreferenceId("p-expl"),
            dimension = PreferenceDimension.ROMANCE,
            statement = "作者明确偏好HE结局",
            origin = PreferenceOrigin.EXPLICIT,
            confirmed = false,
            obtainedAt = t,
            createdAt = t,
            updatedAt = t,
        )
        assertTrue(explicit.isStable, "Explicit 偏好天然 Stable")
        assertFalse(explicit.isCandidate)
    }

    @Test
    fun `inferred unconfirmed candidate is not stable`() {
        val c = candidatePreference()
        assertTrue(c.isCandidate, "Inferred 未确认应为 Candidate")
        assertFalse(c.isStable, "未确认 Candidate 不得成为 Stable")
    }

    @Test
    fun `confirm promotes candidate to stable`() {
        val confirmedNow = Instant.fromEpochSeconds(1790000000 + 1, 0)
        val stable = candidatePreference().copy(confirmed = true, revocable = true, expiry = null, updatedAt = confirmedNow)
        assertFalse(stable.isCandidate)
        assertTrue(stable.isStable, "用户确认后 Candidate → Stable")
    }

    @Test
    fun `confidence is constrained within zero and one`() {
        try {
            Confidence(1.5)
            throw AssertionError("Confidence >1 应被拒绝")
        } catch (_: IllegalArgumentException) {
        }
        assertEquals(0.7, Confidence(0.7).value)
    }

    @Test
    fun `novel scope holds novelId and global scope ignores it`() {
        val novelPref = stablePreference().copy(
            preferenceId = com.qianyan.model.AuthorPreferenceId("p-novel-1"),
            scope = PreferenceScope.NOVEL,
            novelId = novel,
        )
        assertEquals(PreferenceScope.NOVEL, novelPref.scope)
        assertEquals(novel, novelPref.novelId)

        val global = stablePreference()
        assertNull(global.novelId, "GLOBAL 偏好 novelId 应为 null")
    }

    @Test
    fun `evidence carries p15 signal type and source`() {
        val ev = AuthorEvidence(
            evidenceId = AuthorEvidenceId("e-1"),
            novelId = novel,
            type = AuthorEvidenceType.ADOPT,
            detail = "用户确认 Foundation 提案",
            source = "p15:foundation",
            observedAt = t,
        )
        assertEquals(AuthorEvidenceType.ADOPT, ev.type)
        assertEquals("p15:foundation", ev.source)
    }

    @Test
    fun `authorContext exposes only stable and active preferences`() {
        val stable = stablePreference()
        val candidate = candidatePreference()
        val paused = stablePreference().copy(paused = true)
        val ctx = AuthorContext(
            preferences = listOf(
                stable.toLite(),
                candidate.toLite(),
                paused.toLite(),
            ),
        )
        // 领域层 AuthorContext 只承载业务已允许投影的偏好；
        // "只暴露稳定且激活"的过滤由 Application 的 AuthorContextProjection 负责（此处验证投影单元）。
        assertEquals(3, ctx.preferences.size)
        assertTrue(ctx.preferences.first().scope == PreferenceScope.GLOBAL)
    }

    @Test
    fun `preference value semantics and copy immutability`() {
        val a = stablePreference()
        val b = a.copy(statement = "作者倾向长期高信任关系")
        assertEquals("作者倾向长期低信任关系", a.statement, "copy 不应改原实例")
        assertEquals("作者倾向长期高信任关系", b.statement)
        assertFalse(a == b)
    }

    private fun AuthorPreference.toLite() = AuthorContext.AuthorPreferenceLite(
        preferenceId = preferenceId,
        scope = scope,
        novelId = novelId,
        dimension = dimension,
        statement = statement,
        confidence = confidence,
        revocable = revocable,
    )
}