package com.qianyan.application.usecase.author

import com.qianyan.application.error.ErrorMapper
import com.qianyan.model.AuthorCoreCandidateId
import com.qianyan.model.AuthorEvidenceId
import com.qianyan.model.NovelId
import com.qianyan.model.author.AuthorCoreScope
import com.qianyan.model.author.AuthorCoreStatus
import com.qianyan.model.author.AuthorEvidence
import com.qianyan.model.author.AuthorEvidenceType
import com.qianyan.storage.db.QianyanDbFactory
import com.qianyan.storage.repository.AuthorCoreRepository
import com.qianyan.storage.repository.SqliteAuthorCoreRepository
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * P17 · AuthorCoreUseCases 测试：Evidence→Candidate→聚合→Confirm→Core；minObservation；幂等；反证/supersede；
 * reject；pause/resume；reset；Global/Novel scope。
 */
class AuthorCoreUseCasesTest {

    private val now: Instant = Instant.fromEpochSeconds(1790000000, 0)

    private fun setup(): Pair<AuthorCoreUseCases, AuthorCoreRepository> {
        val db = QianyanDbFactory.open(JdbcSqliteDriver.IN_MEMORY).db
        val repo = SqliteAuthorCoreRepository(db)
        val uc = AuthorCoreUseCases(repo, FakeSource, ErrorMapper)
        return uc to repo
    }

    private object FakeSource : FoundationEvidenceSource {
        override fun foundationSignals(novelId: com.qianyan.model.NovelId): List<AuthorEvidence> = emptyList()
    }

    @Test
    fun `two observations then confirm forms stable core`() {
        val (uc, repo) = setup()
        assertTrue(uc.recordCoreEvidence(type = AuthorEvidenceType.ADOPT, evidenceId = AuthorEvidenceId("a-1"), provenanceNovelId = NovelId("n1")))
        assertTrue(uc.recordCoreEvidence(type = AuthorEvidenceType.ADOPT, evidenceId = AuthorEvidenceId("a-2"), provenanceNovelId = NovelId("n2")))
        val cand = uc.viewCandidates().first()
        assertTrue(cand.observationCount >= 2)
        assertTrue(uc.viewCores().isEmpty(), "确认前无长期 Core")
        val core = uc.confirmCoreCandidate(cand.candidateId)
        assertEquals(AuthorCoreStatus.STABLE, core.status)
        assertTrue(uc.viewCores().any { it.coreId == core.coreId })
        assertTrue(uc.viewCandidates().isEmpty(), "确认后候选不再待确认")
        assertTrue(repo.listEvidenceLinks(cand.patternKey).size == 2, "EvidenceLink 记录形成原因")
    }

    @Test
    fun `single observation cannot become core without minObservation`() {
        val (uc, _) = setup()
        assertTrue(uc.recordCoreEvidence(type = AuthorEvidenceType.ADOPT, evidenceId = AuthorEvidenceId("s-1")))
        val cand = uc.viewCandidates().first()
        assertFailsWith<com.qianyan.application.error.ApplicationException> {
            uc.confirmCoreCandidate(cand.candidateId)
        }
    }

    @Test
    fun `duplicate evidence is idempotent once-only`() {
        val (uc, repo) = setup()
        assertTrue(uc.recordCoreEvidence(type = AuthorEvidenceType.ADOPT, evidenceId = AuthorEvidenceId("dup-1")))
        assertEquals(false, uc.recordCoreEvidence(type = AuthorEvidenceType.ADOPT, evidenceId = AuthorEvidenceId("dup-1")), "同一 Evidence ID 只应用一次")
        assertEquals(false, uc.recordCoreEvidence(type = AuthorEvidenceType.ADOPT, evidenceId = AuthorEvidenceId("dup-1")))
        val cand = uc.viewCandidates().first()
        assertEquals(1, cand.observationCount, "重复 Evidence 不重复增加 observation")
        assertEquals(1, cand.positiveEvidence)
    }

    @Test
    fun `global direction flip supersedes old core awaiting re-confirmation`() {
        val (uc, _) = setup()
        // 形成正向 Core（跨两本来源，满足 Global 多书门槛）
        repeat(2) { i -> uc.recordCoreEvidence(type = AuthorEvidenceType.ADOPT, evidenceId = AuthorEvidenceId("p-$i"), provenanceNovelId = NovelId(if (i % 2 == 0) "n1" else "n2")) }
        val core = uc.confirmCoreCandidate(uc.viewCandidates().first().candidateId)

        // 反向证据进入 → 同一候选方向翻转
        uc.recordCoreEvidence(type = AuthorEvidenceType.REJECT, evidenceId = AuthorEvidenceId("n-1"), provenanceNovelId = NovelId("n1"))
        uc.recordCoreEvidence(type = AuthorEvidenceType.REJECT, evidenceId = AuthorEvidenceId("n-2"), provenanceNovelId = NovelId("n2"))
        val flippedCand = uc.viewCandidates().first()
        assertTrue(flippedCand.contradictionCount >= 1)
        // 旧 Core 仍是 STABLE，直到用户重新确认翻转后的候选
        assertTrue(uc.viewCores().any { it.coreId == core.coreId }, "未重新确认前旧 Core 保留")
        val newCore = uc.confirmCoreCandidate(flippedCand.candidateId)
        val old = uc.viewCore(core.coreId)!!
        assertEquals(AuthorCoreStatus.SUPERSEDED, old.status, "旧 Core 被 supersede")
        assertEquals(newCore.coreId, old.supersededBy)
        assertTrue(uc.viewCores().any { it.coreId == newCore.coreId }, "新方向 Core 经确认晋升 STABLE")
    }

    @Test
    fun `reject candidate revokes it`() {
        val (uc, _) = setup()
        uc.recordCoreEvidence(type = AuthorEvidenceType.ADOPT, evidenceId = AuthorEvidenceId("r-1"))
        uc.recordCoreEvidence(type = AuthorEvidenceType.ADOPT, evidenceId = AuthorEvidenceId("r-2"))
        val cand = uc.viewCandidates().first()
        uc.rejectCoreCandidate(cand.candidateId)
        assertTrue(uc.viewCandidates().isEmpty(), "被拒绝候选不进入待确认")
        // Evidence 历史仍在
        assertTrue(uc.recordCoreEvidence(type = AuthorEvidenceType.ADOPT, evidenceId = AuthorEvidenceId("r-3")))
    }

    @Test
    fun `pause blocks collection promotion and projection hides cores but resume restores`() {
        val (uc, _) = setup()
        uc.recordCoreEvidence(type = AuthorEvidenceType.ADOPT, evidenceId = AuthorEvidenceId("pz-1"))
        uc.recordCoreEvidence(type = AuthorEvidenceType.ADOPT, evidenceId = AuthorEvidenceId("pz-2"))
        val cand = uc.viewCandidates().first()

        uc.pauseCoreLearning()
        assertTrue(uc.isLearningPaused())
        assertFailsWith<com.qianyan.application.error.ApplicationException> { uc.confirmCoreCandidate(cand.candidateId) }
        assertEquals(false, uc.recordCoreEvidence(type = AuthorEvidenceType.ADOPT, evidenceId = AuthorEvidenceId("pz-3")), "暂停时停止新采集")

        uc.resumeCoreLearning()
        assertTrue(uc.recordCoreEvidence(type = AuthorEvidenceType.ADOPT, evidenceId = AuthorEvidenceId("pz-3")), "恢复后再采集")
    }

    @Test
    fun `reset wipes core results but not preference evidence`() {
        val (uc, repo) = setup()
        uc.recordCoreEvidence(type = AuthorEvidenceType.ADOPT, evidenceId = AuthorEvidenceId("rs-1"), provenanceNovelId = NovelId("n1"))
        uc.recordCoreEvidence(type = AuthorEvidenceType.ADOPT, evidenceId = AuthorEvidenceId("rs-2"), provenanceNovelId = NovelId("n2"))
        uc.confirmCoreCandidate(uc.viewCandidates().first().candidateId)
        assertTrue(uc.viewCores().isNotEmpty())

        uc.resetCore()
        assertTrue(uc.viewCores().isEmpty())
        assertTrue(uc.viewCandidates().isEmpty())
        assertTrue(repo.listEvidenceLinks("core:foundation").isEmpty())
    }

    @Test
    fun `novel scoped candidate aggregates independently of global`() {
        val (uc, _) = setup()
        val novel = NovelId("n1")
        // Novel scope 候选
        uc.recordCoreEvidence(type = AuthorEvidenceType.ADOPT, evidenceId = AuthorEvidenceId("nv-1"), scope = AuthorCoreScope.NOVEL, novelId = novel)
        uc.recordCoreEvidence(type = AuthorEvidenceType.ADOPT, evidenceId = AuthorEvidenceId("nv-2"), scope = AuthorCoreScope.NOVEL, novelId = novel)
        val novelCand = uc.viewCandidates().first()
        assertEquals(AuthorCoreScope.NOVEL, novelCand.scope)
        assertEquals(novel, novelCand.novelId)
        // Global 候选独立
        uc.recordCoreEvidence(type = AuthorEvidenceType.ADOPT, evidenceId = AuthorEvidenceId("g-1"))
        assertEquals(2, uc.viewCandidates().size, "Novel 与 Global 候选分离")
    }

    // ---------------- M-1：版本历史（modify / flip 必须保留旧内容） ----------------

    private fun repoCore(repo: AuthorCoreRepository, id: com.qianyan.model.AuthorCoreId) = repo.getAuthorCore(id)!!
    private fun repoPattern(repo: AuthorCoreRepository, core: com.qianyan.model.author.AuthorCore) =
        repo.getAuthorCorePattern(core.patternId!!)!!

    @Test
    fun `modify creates new revision and preserves old content`() {
        val (uc, repo) = setup()
        uc.recordCoreEvidence(type = AuthorEvidenceType.ADOPT, evidenceId = AuthorEvidenceId("m-1"), provenanceNovelId = NovelId("n1"))
        uc.recordCoreEvidence(type = AuthorEvidenceType.ADOPT, evidenceId = AuthorEvidenceId("m-2"), provenanceNovelId = NovelId("n2"))
        val v1 = uc.confirmCoreCandidate(uc.viewCandidates().first().candidateId)
        val oldStatement = repoPattern(repo, v1).statement

        val v2 = uc.modifyCore(v1.coreId, statement = "偏好克制但高张力叙事")

        // 旧 Core：SUPERSEDED，statement/version 保留
        val old = repoCore(repo, v1.coreId)
        assertEquals(AuthorCoreStatus.SUPERSEDED, old.status)
        assertEquals(1L, old.version)
        assertEquals(oldStatement, repoPattern(repo, old).statement, "旧 Pattern 内容必须保留")
        assertEquals(v2.coreId, old.supersededBy, "旧 Core 可溯源到新 Core")

        // 新 Core：STABLE，新内容，新 version
        assertEquals(AuthorCoreStatus.STABLE, v2.status)
        assertEquals(2L, v2.version)
        assertEquals("偏好克制但高张力叙事", repoPattern(repo, v2).statement)
        assertTrue(v2.coreId != v1.coreId, "新 Core 应为独立 ID")
        assertTrue(v2.patternId != v1.patternId, "新版本必须是新 Pattern ID")

        // 历史可查询：两个版本同时存在
        val patterns = repo.getAuthorCorePatternsByKey("core:foundation")
        assertEquals(2, patterns.size, "修改后应同时保留 V1 与 V2")
        assertEquals(2, patterns.map { it.version }.toSet().size)
    }

    @Test
    fun `global direction flip preserves history`() {
        val (uc, repo) = setup()
        repeat(2) { i -> uc.recordCoreEvidence(type = AuthorEvidenceType.ADOPT, evidenceId = AuthorEvidenceId("p$i"), provenanceNovelId = NovelId(if (i % 2 == 0) "n1" else "n2")) }
        val a = uc.confirmCoreCandidate(uc.viewCandidates().first().candidateId)
        val aStatement = repoPattern(repo, a).statement

        // 反向证据（两例，跨两本来源）
        uc.recordCoreEvidence(type = AuthorEvidenceType.REJECT, evidenceId = AuthorEvidenceId("n1"), provenanceNovelId = NovelId("n1"))
        uc.recordCoreEvidence(type = AuthorEvidenceType.REJECT, evidenceId = AuthorEvidenceId("n2"), provenanceNovelId = NovelId("n2"))
        val b = uc.confirmCoreCandidate(uc.viewCandidates().first().candidateId)

        val oldA = repoCore(repo, a.coreId)
        assertEquals(AuthorCoreStatus.SUPERSEDED, oldA.status, "旧 Global Core 保留为 SUPERSEDED")
        assertEquals(b.coreId, oldA.supersededBy, "旧 Core 可溯源到新 Core")
        assertEquals(aStatement, repoPattern(repo, oldA).statement, "旧方向内容必须保留（非原地反转）")

        assertEquals(AuthorCoreStatus.STABLE, b.status)
        assertTrue(b.coreId != a.coreId, "新 Core 为独立 ID")
        assertTrue(b.patternId != a.patternId, "新 Pattern 为新 ID")
    }

    @Test
    fun `evidence link history preserved across revision`() {
        val (uc, repo) = setup()
        repeat(2) { i -> uc.recordCoreEvidence(type = AuthorEvidenceType.ADOPT, evidenceId = AuthorEvidenceId("p$i"), provenanceNovelId = NovelId(if (i % 2 == 0) "n1" else "n2")) }
        val a = uc.confirmCoreCandidate(uc.viewCandidates().first().candidateId)
        val aPattern = repoPattern(repo, a)
        val aRefs = aPattern.evidenceRefs.map { it.value }.toSet()

        uc.recordCoreEvidence(type = AuthorEvidenceType.REJECT, evidenceId = AuthorEvidenceId("n1"), provenanceNovelId = NovelId("n1"))
        uc.recordCoreEvidence(type = AuthorEvidenceType.REJECT, evidenceId = AuthorEvidenceId("n2"), provenanceNovelId = NovelId("n2"))
        val b = uc.confirmCoreCandidate(uc.viewCandidates().first().candidateId)
        val bRefs = repoPattern(repo, b).evidenceRefs.map { it.value }.toSet()

        // 旧版本证据链保留
        assertEquals(setOf("p0", "p1"), aRefs, "旧版本 EvidenceLink 不得因改造丢失")
        // 新版本证据链在本阶段累计（新链接加入）
        assertTrue(bRefs.containsAll(aRefs), "新版本应包含旧版本引用")
        assertTrue(bRefs.contains("n1") && bRefs.contains("n2"), "反向证据进入新版本证据链")
        // EvidenceLink 表不被删除
        assertTrue(repo.listEvidenceLinks("core:foundation").isNotEmpty())
    }
}