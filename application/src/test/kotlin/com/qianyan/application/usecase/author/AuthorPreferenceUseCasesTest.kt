package com.qianyan.application.usecase.author

import com.qianyan.application.error.ErrorMapper
import com.qianyan.model.AuthorEvidenceId
import com.qianyan.model.AuthorPreferenceId
import com.qianyan.model.NovelId
import com.qianyan.model.author.AuthorEvidence
import com.qianyan.model.author.AuthorEvidenceType
import com.qianyan.model.author.PreferenceDimension
import com.qianyan.model.author.PreferenceOrigin
import com.qianyan.model.author.PreferenceScope
import com.qianyan.storage.db.QianyanDbFactory
import com.qianyan.storage.repository.SqliteAuthorPreferenceRepository
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * P16 AIL-1 · Author Preference Use Cases 测试（真实 in-memory SQLite + 真实仓储 + Fake P15 信号源）。
 * 覆盖冻结语义：Evidence→Candidate→Confidence→User Confirmation→Stable；未确认 Candidate 不入 AuthorContext；
 * 拒绝即衰减失效；暂停/恢复；Novel Override 最小合并。
 */
class AuthorPreferenceUseCasesTest {

    private val now: Instant = Instant.fromEpochSeconds(1790000000, 0)

    private class FakeSource(var signals: () -> List<AuthorEvidence> = { emptyList() }) : FoundationEvidenceSource {
        override fun foundationSignals(novelId: NovelId): List<AuthorEvidence> = signals()
    }

    private fun setup(signals: List<AuthorEvidence>): Pair<AuthorPreferenceUseCases, com.qianyan.storage.repository.AuthorPreferenceRepository> {
        val db = QianyanDbFactory.open(JdbcSqliteDriver.IN_MEMORY).db
        val repo = SqliteAuthorPreferenceRepository(db)
        val projection = AuthorContextProjection(repo, null, ErrorMapper)
        val uc = AuthorPreferenceUseCases(repo, projection, FakeSource { signals }, ErrorMapper)
        return uc to repo
    }

    private fun adoptSignal(novelId: NovelId) = AuthorEvidence(
        evidenceId = AuthorEvidenceId("e-adopt"),
        novelId = novelId,
        type = AuthorEvidenceType.ADOPT,
        detail = "确认 Foundation",
        source = "p15:foundation:gate",
        observedAt = now,
    )

    @Test
    fun `getOrCreateAuthorProfile is idempotent`() {
        val (uc, _) = setup(emptyList())
        val a = uc.getOrCreateAuthorProfile("作者")
        val b = uc.getOrCreateAuthorProfile("作者")
        assertEquals(a.profileId, b.profileId, "重复调用应返回同一 Profile")
    }

    @Test
    fun `explicit preference is stable and visible in context`() {
        val (uc, _) = setup(emptyList())
        val pref = uc.addExplicitPreference(
            dimension = PreferenceDimension.ROMANCE,
            statement = "作者明确偏好HE结局",
            scope = PreferenceScope.GLOBAL,
        )
        assertTrue(pref.isStable)
        assertFalse(pref.isCandidate)
        val ctx = uc.buildAuthorContext(null)
        assertEquals(listOf("作者明确偏好HE结局"), ctx.preferences.map { it.statement })
    }

    @Test
    fun `p15 evidence creates candidate that is not stable and hidden from context`() {
        val (uc, _) = setup(listOf(adoptSignal(NovelId("n1"))))
        val collected = uc.collectFoundationEvidence(NovelId("n1"))
        assertEquals(1, collected)

        val candidates = uc.listCandidates()
        assertEquals(1, candidates.size, "应产生一个未确认 Candidate")
        assertTrue(candidates.first().isCandidate)
        assertFalse(candidates.first().isStable)

        // 未确认 Candidate 不得进入 AuthorContext
        assertTrue(uc.buildAuthorContext(NovelId("n1")).isEmpty, "未确认 Candidate 不得进入 AuthorContext")
    }

    @Test
    fun `confirm candidate promotes to stable and visible in context`() {
        val (uc, _) = setup(listOf(adoptSignal(NovelId("n1"))))
        uc.collectFoundationEvidence(NovelId("n1"))
        val cand = uc.listCandidates().first()

        val stable = uc.confirmPreference(cand.preferenceId)
        assertTrue(stable.isStable, "用户确认后 Candidate → Stable")

        val ctx = uc.buildAuthorContext(NovelId("n1"))
        assertEquals(1, ctx.preferences.size, "已确认偏好进入 AuthorContext")
        assertEquals(PreferenceOrigin.INFERRED, stable.origin)
        assertTrue(ctx.preferences.first().revocable, "确认的学习偏好仍可撤销")
    }

    @Test
    fun `reject candidate decays it and hides from context`() {
        val (uc, _) = setup(listOf(adoptSignal(NovelId("n1"))))
        uc.collectFoundationEvidence(NovelId("n1"))
        val cand = uc.listCandidates().first()
        val rejected = uc.rejectPreference(cand.preferenceId)
        assertNotNull(rejected.expiry, "拒绝后应设置 expiry 使其衰减/失效")
        assertTrue(uc.buildAuthorContext(NovelId("n1")).isEmpty, "被否定的 Candidate 不应进入 AuthorContext")
    }

    @Test
    fun `pause hides and resume restores in context`() {
        val (uc, _) = setup(emptyList())
        val pref = uc.addExplicitPreference(PreferenceDimension.TONE, "作者偏好冷峻语调")
        assertTrue(uc.buildAuthorContext(null).preferences.any { it.preferenceId == pref.preferenceId })

        uc.pausePreference(pref.preferenceId)
        assertTrue(uc.buildAuthorContext(null).isEmpty, "暂停后不进 AuthorContext")

        uc.resumePreference(pref.preferenceId)
        assertTrue(uc.buildAuthorContext(null).preferences.any { it.preferenceId == pref.preferenceId }, "恢复后重新进入 AuthorContext")
    }

    @Test
    fun `negative evidence expires existing candidate`() {
        val (uc, _) = setup(listOf(adoptSignal(NovelId("n1"))))
        uc.collectFoundationEvidence(NovelId("n1"))
        val cand = uc.listCandidates().first()

        // 收集 REJECT 信号 → 既有候选应被否定（过期）
        uc.recordEvidence(AuthorEvidenceType.REJECT, "用户拒绝", "p15:foundation:gate", NovelId("n1"))
        assertTrue(uc.listCandidates().isEmpty(), "负向信号应使既有 Candidate 过期/否定")
        assertTrue(uc.buildAuthorContext(NovelId("n1")).isEmpty)
    }

    @Test
    fun `repeated collect is idempotent for candidate strength and evidence count`() {
        val novel = NovelId("n9")
        // 一批固定、确定性 Evidence ID 的正向信号
        val signals = (1..3).map { i ->
            AuthorEvidence(
                evidenceId = AuthorEvidenceId("sig-$i"),
                novelId = novel,
                type = AuthorEvidenceType.ADOPT,
                detail = "确认 #$i",
                source = "p15:foundation:gate",
                observedAt = now,
            )
        }
        val (uc, repo) = setup(signals)

        // 第一次：3 signals → 3 applied
        assertEquals(3, uc.collectFoundationEvidence(novel), "首次应新增应用 3 条")
        val conf1 = uc.listCandidates().first().confidence.value

        // 第二次相同信号：0 applied，confidence 不变，Evidence 数不增
        assertEquals(0, uc.collectFoundationEvidence(novel), "第二次应为 0 新增")
        assertEquals(conf1, uc.listCandidates().first().confidence.value, "候选置信度必须不变")
        assertEquals(3, repo.listEvidence(novel).size, "Evidence 数量不得增加")

        // 第三次仍不变
        assertEquals(0, uc.collectFoundationEvidence(novel), "第三次应为 0 新增")
        assertEquals(conf1, uc.listCandidates().first().confidence.value, "第三次 confidence 仍不变")
        assertEquals(3, repo.listEvidence(novel).size, "第三次 Evidence 数量仍不变")

        // 新 Evidence 仍能继续累加 confidence
        uc.recordEvidence(AuthorEvidenceType.ADOPT, "新增", "p15:foundation:gate", novel)
        uc.recordEvidence(AuthorEvidenceType.ADOPT, "新增", "p15:foundation:gate", novel)
        uc.recordEvidence(AuthorEvidenceType.ADOPT, "新增", "p15:foundation:gate", novel)
        val conf2 = uc.listCandidates().first().confidence.value
        assertTrue(conf2 > conf1, "不同新 Evidence 仍应累计 confidence")

        // confidence 上限 0.9 有效（conf1≈0.7 + 3×0.1 → 撞顶 0.9）
        assertEquals(0.9, uc.listCandidates().first().confidence.value, 1e-9, "confidence 不得超过 0.9")

        // Confirm 仍是 Inferred → Stable 的唯一晋升路径
        val current = uc.listCandidates().first()
        assertTrue(uc.buildAuthorContext(novel).isEmpty, "未确认 Candidate 不入 AuthorContext")
        assertTrue(uc.confirmPreference(current.preferenceId).isStable, "确认后晋升 Stable")
        assertEquals(1, uc.buildAuthorContext(novel).preferences.size, "确认后进入 AuthorContext")
    }

    @Test
    fun `collect returns newly applied rather than scanned total`() {
        val novel = NovelId("n9")
        val signals = (1..2).map { i ->
            AuthorEvidence(
                evidenceId = AuthorEvidenceId("c-$i"),
                novelId = novel,
                type = AuthorEvidenceType.MODIFY,
                detail = "modify$i",
                source = "p15:foundation:modify",
                observedAt = now,
            )
        }
        val (uc, _) = setup(signals)
        assertEquals(2, uc.collectFoundationEvidence(novel), "首次扫描 2 条 → 2 新增")
        assertEquals(0, uc.collectFoundationEvidence(novel), "再次同一批 → 0 新增")
    }
}