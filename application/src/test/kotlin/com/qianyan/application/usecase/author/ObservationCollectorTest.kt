package com.qianyan.application.usecase.author

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.qianyan.application.error.ErrorMapper
import com.qianyan.model.AuthorObservationId
import com.qianyan.model.NovelId
import com.qianyan.model.author.AuthorCoreScope
import com.qianyan.model.author.AuthorEvidenceType
import com.qianyan.model.author.AuthorObservation
import com.qianyan.model.author.AuthorObservationSource
import com.qianyan.storage.db.QianyanDbFactory
import com.qianyan.storage.repository.AuthorCoreRepository
import com.qianyan.storage.repository.AuthorObservationRepository
import com.qianyan.storage.repository.SqliteAuthorCoreRepository
import com.qianyan.storage.repository.SqliteAuthorObservationRepository
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * P18-A · ObservationCollector 测试：Observation→Evidence→Candidate；deterministic 幂等；
 * 不自动晋升 Stable Core（须 User Confirmation）；CONTEXT 不聚合；决策类型复用 5 类 EvidenceType。
 */
class ObservationCollectorTest {

    private val now: Instant = Instant.fromEpochSeconds(1790000000, 0)

    private class Harness(
        val collector: ObservationCollector,
        val coreUseCases: AuthorCoreUseCases,
        val coreRepo: AuthorCoreRepository,
        val obsRepo: AuthorObservationRepository,
    )

    private fun setup(): Harness {
        val db = QianyanDbFactory.open(JdbcSqliteDriver.IN_MEMORY).db
        val coreRepo = SqliteAuthorCoreRepository(db)
        val obsRepo: AuthorObservationRepository = SqliteAuthorObservationRepository(db)
        val coreUseCases = AuthorCoreUseCases(coreRepo, FakeSource, ErrorMapper)
        val collector = ObservationCollector(obsRepo, coreUseCases)
        return Harness(collector, coreUseCases, coreRepo, obsRepo)
    }

    private object FakeSource : FoundationEvidenceSource {
        override fun foundationSignals(novelId: com.qianyan.model.NovelId): List<com.qianyan.model.author.AuthorEvidence> = emptyList()
    }

    private fun obs(id: String, type: AuthorEvidenceType = AuthorEvidenceType.ADOPT, scope: AuthorCoreScope = AuthorCoreScope.GLOBAL): AuthorObservation =
        AuthorObservation(
            observationId = AuthorObservationId(id),
            scope = scope,
            decisionType = type,
            novelId = if (scope == AuthorCoreScope.NOVEL) NovelId("n1") else null,
            source = AuthorObservationSource.WRITING,
            metadata = emptyMap(),
            occurredAt = now,
        )

    @Test
    fun `observation flows to evidence and candidate but not stable core`() {
        val h = setup()
        val result = h.collector.record(obs("o1"))
        assertEquals(true, result.newObservation)
        assertEquals(1, result.appliedEvidence)
        // 观测持久化
        assertTrue(h.obsRepo.exists(AuthorObservationId("o1")))
        assertEquals(1L, h.obsRepo.count())
        // 产生 Candidate（≥1 观察）
        val cand = h.coreUseCases.viewCandidates().first()
        assertTrue(cand.observationCount >= 1)
        // 未确认 → 无长期 Core
        assertTrue(h.coreUseCases.viewCores().isEmpty(), "未确认不自动晋升 Stable Core")
    }

    @Test
    fun `duplicate observation is idempotent - no confidence inflation`() {
        val h = setup()
        h.collector.record(obs("a1"))
        h.collector.record(obs("a2"))
        val before = h.coreUseCases.viewCandidates().first().confidence

        val dup = h.collector.record(obs("a1"))
        assertEquals(false, dup.newObservation, "重复观测不是新观测")
        assertEquals(0, dup.appliedEvidence, "重复观测不重复聚合")
        assertEquals(2L, h.obsRepo.count(), "重复观测不新增 Observation 行（仍是 a1,a2 两条）")
        val after = h.coreUseCases.viewCandidates().first().confidence
        assertEquals(before.value, after.value, 0.0, "重复观测不得推高 confidence")
        assertEquals(before, after, "候选保持一致")
    }

    @Test
    fun `different decision types aggregate without evidence type inflation`() {
        val h = setup()
        // 五种决策语义均映射到既有 5 类 EvidenceType（不复用/不新增长期枚举）
        val types = listOf(
            AuthorEvidenceType.ADOPT,
            AuthorEvidenceType.MODIFY,
            AuthorEvidenceType.REJECT,
            AuthorEvidenceType.PARTIAL_REWRITE,
            AuthorEvidenceType.ADOPT_THEN_REVISE,
        )
        types.forEachIndexed { i, t ->
            val r = h.collector.record(obs("t$i", type = t))
            assertEquals(true, r.newObservation)
            assertEquals(1, r.appliedEvidence, "$t 应派生 1 条证据")
        }
        assertEquals(5L, h.obsRepo.count())
        assertEquals(1, h.coreUseCases.viewCandidates().size, "全部归入同一长期候选流水")
    }

    @Test
    fun `confirm is the only path from candidate to stable core`() {
        val h = setup()
        h.collector.record(obs("c1"))
        h.collector.record(obs("c2"))
        val cand = h.coreUseCases.viewCandidates().first()
        assertTrue(h.coreUseCases.viewCores().isEmpty(), "确认前无长期 Core")
        // 确保达到最小观察门槛后确认
        h.coreUseCases.confirmCoreCandidate(cand.candidateId)
        assertEquals(1, h.coreUseCases.viewCores().size, "确认后晋升 Stable Core")
    }

    @Test
    fun `context scope persists observation but does not aggregate to learning`() {
        val h = setup()
        h.collector.record(obs("ctx1", scope = AuthorCoreScope.CONTEXT))
        // 观测被持久化（历史真实），但不参与长期学习
        assertTrue(h.obsRepo.exists(AuthorObservationId("ctx1")))
        assertEquals(0, h.coreUseCases.viewCandidates().size, "CONTEXT 观测不产生候选/不固化")
    }

    @Test
    fun `paused learning stores observation but blocks aggregation`() {
        val h = setup()
        h.coreUseCases.pauseCoreLearning()
        val r = h.collector.record(obs("p1"))
        assertEquals(true, r.newObservation, "暂停期间观测仍被持久化（历史真实）")
        assertEquals(0, r.appliedEvidence, "暂停期间不聚合")
        assertTrue(h.obsRepo.exists(AuthorObservationId("p1")))
        assertTrue(h.coreUseCases.viewCandidates().isEmpty())
    }

    @Test
    fun `novel scope observation aggregates to novel candidate`() {
        val h = setup()
        h.collector.record(obs("nv1", scope = AuthorCoreScope.NOVEL))
        h.collector.record(obs("nv2", scope = AuthorCoreScope.NOVEL))
        val cand = h.coreUseCases.viewCandidates().first()
        assertEquals(AuthorCoreScope.NOVEL, cand.scope)
        assertEquals(NovelId("n1"), cand.novelId)
        assertFalse(h.coreUseCases.viewCores().isNotEmpty(), "未确认前无长期 Core")
    }
}