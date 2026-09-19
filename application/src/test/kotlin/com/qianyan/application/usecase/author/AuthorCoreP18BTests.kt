package com.qianyan.application.usecase.author

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.qianyan.application.error.ApplicationException
import com.qianyan.application.error.ErrorMapper
import com.qianyan.model.AuthorCoreCandidateId
import com.qianyan.model.AuthorCoreId
import com.qianyan.model.AuthorEvidenceId
import com.qianyan.model.NovelId
import com.qianyan.model.author.AuthorCoreCandidate
import com.qianyan.model.author.AuthorCoreScope
import com.qianyan.model.author.AuthorCoreStatus
import com.qianyan.model.author.AuthorEvidence
import com.qianyan.model.author.AuthorEvidenceType
import com.qianyan.model.author.AuthorObservationSource
import com.qianyan.model.author.Confidence
import com.qianyan.storage.db.QianyanDbFactory
import com.qianyan.storage.repository.AuthorCoreRepository
import com.qianyan.storage.repository.SqliteAuthorCoreRepository
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * P18-B · DEC-P18B-001/003/005 测试：
 *  - source×type 双维权重（同 type 不同 source 权重不同；source/type 独立两维）
 *  - consistency / contradiction 真正进入 confidence（非 count-based）
 *  - Global 多书门槛（distinctNovelCount 不足 → 阻止晋升；达到 → 允许）
 *  - 幂等（重复 evidence 不膨胀多书计数 / 不膨胀 confidence）
 *  - Stable Core 收到反向证据 → 不就地修改；确认后才 fork+supersede（P17 M-1 保持）
 */
class AuthorCoreP18BTests {

    private val now: Instant = Instant.fromEpochSeconds(1790000000, 0)

    private fun shell(seed: Double = 0.0) = AuthorCoreCandidate(
        candidateId = AuthorCoreCandidateId("c-${seed}"),
        patternKey = "core:foundation",
        scope = AuthorCoreScope.GLOBAL,
        novelId = null,
        recency = now,
        confidence = Confidence.HALF,
        status = AuthorCoreStatus.CANDIDATE,
        createdAt = now,
        updatedAt = now,
        weightedScore = seed,
    )

    private fun evidence(type: AuthorEvidenceType, source: String) = AuthorEvidence(
        evidenceId = AuthorEvidenceId("e-${type.name}-${source}"),
        novelId = NovelId("n-probe"),
        type = type,
        source = source,
        observedAt = now,
    )

    // ---------------- DEC-P18B-001：source × type 双维权重 ----------------

    @Test
    fun `same type different source yields different effective weight`() {
        val fromFoundation = AuthorCoreAggregator.aggregate(shell(), evidence(AuthorEvidenceType.ADOPT, "observation:foundation"), now)
        val fromRevision = AuthorCoreAggregator.aggregate(shell(), evidence(AuthorEvidenceType.ADOPT, "observation:revision"), now)
        // FOUNDATION(1.0) > REVISION(0.6)：同一 type、不同 source，有效权重不同
        assertTrue(fromFoundation.weightedScore > fromRevision.weightedScore, "Foundation 来源应比 Revision 来源贡献更高")
        assertTrue(fromFoundation.confidence.value > fromRevision.confidence.value)
    }

    @Test
    fun `source and type remain independent dimensions`() {
        // source 维度存在且与 type 正交（SOURCE_WEIGHT 键不为空，且不复用 type 枚举）
        assertEquals(5, AuthorCoreAggregator.SOURCE_WEIGHT.size)
        assertEquals(5, AuthorCoreAggregator.TYPE_WEIGHT.size)
        // 同 source 下，ADOPT(1.0) > REJECT(-1.0) → 方向由 type 决定
        val adopt = AuthorCoreAggregator.aggregate(shell(), evidence(AuthorEvidenceType.ADOPT, "observation:writing"), now)
        val reject = AuthorCoreAggregator.aggregate(shell(), evidence(AuthorEvidenceType.REJECT, "observation:writing"), now)
        assertTrue(adopt.weightedScore > reject.weightedScore)
        assertTrue(reject.weightedScore < 0)
    }

    // ---------------- DEC-P18B-003：consistency / contradiction 进入 confidence ----------------

    @Test
    fun `consistent positive evidence outranks mixed contradictory evidence`() {
        var c1 = shell()
        c1 = AuthorCoreAggregator.aggregate(c1, evidence(AuthorEvidenceType.ADOPT, "observation:foundation"), now)
        c1 = AuthorCoreAggregator.aggregate(c1, evidence(AuthorEvidenceType.ADOPT, "observation:foundation"), now)

        var mixed = shell()
        mixed = AuthorCoreAggregator.aggregate(mixed, evidence(AuthorEvidenceType.ADOPT, "observation:foundation"), now)
        mixed = AuthorCoreAggregator.aggregate(mixed, evidence(AuthorEvidenceType.REJECT, "observation:foundation"), now)

        // 同向一致（consistency=1、无 contradiction）应显著高于混杂（consistency=0.5、contradiction≥1）
        assertTrue(c1.confidence.value > mixed.confidence.value, "一致同向 confidence 应高于矛盾混杂")
        assertEquals(1.0, c1.consistency)
        assertTrue(mixed.contradictionCount >= 1, "反向证据应增加 contradiction")
        assertEquals(0.5, mixed.consistency)
    }

    @Test
    fun `unknown source defaults to neutral weight`() {
        val neutral = AuthorCoreAggregator.aggregate(shell(), evidence(AuthorEvidenceType.ADOPT, "author-core"), now)
        assertEquals(AuthorCoreAggregator.DEFAULT_SOURCE_WEIGHT, AuthorCoreAggregator.sourceWeightOf("author-core"))
        assertTrue(neutral.weightedScore > 0)
    }

    // ---------------- DEC-P18B-005：Global 多书门槛 + 幂等 ----------------

    private fun setup(): Pair<AuthorCoreUseCases, AuthorCoreRepository> {
        val db = QianyanDbFactory.open(JdbcSqliteDriver.IN_MEMORY).db
        val repo = SqliteAuthorCoreRepository(db)
        return AuthorCoreUseCases(repo, FakeSource, ErrorMapper) to repo
    }

    private object FakeSource : FoundationEvidenceSource {
        override fun foundationSignals(novelId: com.qianyan.model.NovelId): List<AuthorEvidence> = emptyList()
    }

    @Test
    fun `global promotion blocked when provenance is single novel`() {
        val (uc, repo) = setup()
        val n1 = NovelId("n1")
        uc.recordCoreEvidence(type = AuthorEvidenceType.ADOPT, evidenceId = AuthorEvidenceId("g-a1"), provenanceNovelId = n1)
        uc.recordCoreEvidence(type = AuthorEvidenceType.ADOPT, evidenceId = AuthorEvidenceId("g-a2"), provenanceNovelId = n1)
        assertEquals(1L, repo.distinctProvenanceNovelCount("core:foundation"), "单一归属小说 distinct=1")
        assertFailsWith<ApplicationException> {
            uc.confirmCoreCandidate(uc.viewCandidates().first().candidateId)
        }
        assertTrue(uc.viewCores().isEmpty(), "单一 Novel 不得晋升 Global 稳定 Core")
    }

    @Test
    fun `global promotion zero provenance is blocked - no bypass`() {
        val (uc, repo) = setup()
        // 无来源（legacy / provenance 空）的 GLOBAL 证据 → distinct=0，不得绕过 multi-Novel 门槛（DEC-P18B-005）
        uc.recordCoreEvidence(type = AuthorEvidenceType.ADOPT, evidenceId = AuthorEvidenceId("z-1"))
        uc.recordCoreEvidence(type = AuthorEvidenceType.ADOPT, evidenceId = AuthorEvidenceId("z-2"))
        assertEquals(0L, repo.distinctProvenanceNovelCount("core:foundation"), "无来源 distinct=0")
        assertFailsWith<ApplicationException> {
            uc.confirmCoreCandidate(uc.viewCandidates().first().candidateId)
        }
        assertTrue(uc.viewCores().isEmpty(), "零来源 GLOBAL 不得晋升稳定 Core")
    }

    @Test
    fun `global promotion allowed once evidence spans two novels`() {
        val (uc, repo) = setup()
        val n1 = NovelId("n1"); val n2 = NovelId("n2")
        repeat(2) { i -> uc.recordCoreEvidence(type = AuthorEvidenceType.ADOPT, evidenceId = AuthorEvidenceId("a$i"), provenanceNovelId = n1) }
        repeat(2) { i -> uc.recordCoreEvidence(type = AuthorEvidenceType.ADOPT, evidenceId = AuthorEvidenceId("b$i"), provenanceNovelId = n2) }
        assertEquals(2L, repo.distinctProvenanceNovelCount("core:foundation"))
        val core = uc.confirmCoreCandidate(uc.viewCandidates().first().candidateId)
        assertEquals(AuthorCoreStatus.STABLE, core.status)
    }

    @Test
    fun `duplicate evidence does not inflate multi-novel count or confidence`() {
        val (uc, repo) = setup()
        val n1 = NovelId("n1")
        uc.recordCoreEvidence(type = AuthorEvidenceType.ADOPT, evidenceId = AuthorEvidenceId("dup"), provenanceNovelId = n1)
        // 重复提交被证据幂等拦截
        assertEquals(false, uc.recordCoreEvidence(type = AuthorEvidenceType.ADOPT, evidenceId = AuthorEvidenceId("dup"), provenanceNovelId = n1))
        assertEquals(1L, repo.distinctProvenanceNovelCount("core:foundation"), "重复证据不新增来源计数")
        val cand = uc.viewCandidates().first()
        assertEquals(1, cand.observationCount, "重复证据不膨胀 observation")
        // 仍因单一 Novel 被阻止
        assertFailsWith<ApplicationException> { uc.confirmCoreCandidate(cand.candidateId) }
    }

    @Test
    fun `novel scope promotion not gated by multi-novel guard`() {
        val (uc, _) = setup()
        val n1 = NovelId("n1")
        repeat(2) { i -> uc.recordCoreEvidence(type = AuthorEvidenceType.ADOPT, evidenceId = AuthorEvidenceId("n$i"), scope = AuthorCoreScope.NOVEL, novelId = n1) }
        val core = uc.confirmCoreCandidate(uc.viewCandidates().first().candidateId)
        assertEquals(AuthorCoreStatus.STABLE, core.status, "NOVEL 可单书确认晋升")
    }

    // ---------------- DEC-P18B-004：Stable Core 反向证据不就地修改 ----------------

    @Test
    fun `reverse evidence leaves stable core untouched until confirmation`() {
        val (uc, repo) = setup()
        val n1 = NovelId("n1")
        // 先形成 Global 稳定 Core（需跨两书来源）
        val n2 = NovelId("n2")
        repeat(2) { i -> uc.recordCoreEvidence(type = AuthorEvidenceType.ADOPT, evidenceId = AuthorEvidenceId("pa$i"), provenanceNovelId = n1) }
        repeat(2) { i -> uc.recordCoreEvidence(type = AuthorEvidenceType.ADOPT, evidenceId = AuthorEvidenceId("pb$i"), provenanceNovelId = n2) }
        val coreA = uc.confirmCoreCandidate(uc.viewCandidates().first().candidateId)
        assertEquals(AuthorCoreStatus.STABLE, coreA.status)

        // 反向证据（跨两书，走同候选）→ 旧 Core 不就地修改，仍 STABLE
        val before = repo.getAuthorCore(coreA.coreId)!!
        uc.recordCoreEvidence(type = AuthorEvidenceType.REJECT, evidenceId = AuthorEvidenceId("r1"), provenanceNovelId = n1)
        uc.recordCoreEvidence(type = AuthorEvidenceType.REJECT, evidenceId = AuthorEvidenceId("r2"), provenanceNovelId = n2)
        val after = repo.getAuthorCore(coreA.coreId)!!
        assertEquals(AuthorCoreStatus.STABLE, after.status, "反向证据不得就地降级/改写旧 Core")
        assertEquals(AuthorCoreId(coreA.coreId.value), AuthorCoreId(after.coreId.value))
        // 候选侧出现反证，待确认
        val cand = uc.viewCandidates().first()
        assertTrue(cand.contradictionCount >= 1)

        // 确认后 fork 新 Core + 旧 Core SUPERSEDED
        val coreB = uc.confirmCoreCandidate(cand.candidateId)
        val old = repo.getAuthorCore(coreA.coreId)!!
        assertEquals(AuthorCoreStatus.SUPERSEDED, old.status)
        assertEquals(coreB.coreId, old.supersededBy)
        assertTrue(coreB.coreId != coreA.coreId)
    }
}