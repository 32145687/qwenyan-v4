package com.qianyan.storage

import com.qianyan.model.AuthorCoreCandidateId
import com.qianyan.model.AuthorCoreEvidenceLinkId
import com.qianyan.model.AuthorCoreId
import com.qianyan.model.AuthorCorePatternId
import com.qianyan.model.AuthorEvidenceId
import com.qianyan.model.NovelId
import com.qianyan.model.author.AuthorCore
import com.qianyan.model.author.AuthorCoreCandidate
import com.qianyan.model.author.AuthorCoreEvidenceLink
import com.qianyan.model.author.AuthorCorePattern
import com.qianyan.model.author.AuthorCoreScope
import com.qianyan.model.author.AuthorCoreStatus
import com.qianyan.model.author.Confidence
import com.qianyan.storage.db.QianyanDbFactory
import com.qianyan.storage.db.QianyanDbHandle
import com.qianyan.storage.repository.AuthorCoreRepository
import com.qianyan.storage.repository.SqliteAuthorCoreRepository
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P17 · Author Core 仓储测试（独立 boundary）：Core/Pattern/Candidate/EvidenceLink 往返、learning 开关、wipe、幂等链。
 */
class AuthorCoreRepositoryTest {

    private val now: Instant = Instant.fromEpochSeconds(1790000000, 0)

    private fun handle(): QianyanDbHandle = QianyanDbFactory.open(JdbcSqliteDriver.IN_MEMORY)

    private fun repo(db: com.qianyan.storage.db.QianyanDb): AuthorCoreRepository = SqliteAuthorCoreRepository(db)

    private fun core() = AuthorCore(
        coreId = AuthorCoreId("c-1"),
        scope = AuthorCoreScope.GLOBAL,
        status = AuthorCoreStatus.STABLE,
        confirmed = true,
        confidence = Confidence(0.8),
        corePatternKey = "core:foundation",
        createdAt = now,
        updatedAt = now,
    )

    @Test
    fun `core and pattern round-trip`() {
        val h = handle()
        val r = repo(h.db)
        r.upsertAuthorCore(core())
        r.upsertAuthorCorePattern(
            AuthorCorePattern(
                patternId = AuthorCorePatternId("p-1"),
                patternKey = "core:foundation",
                statement = "逐步揭示",
                condition = null,
                scope = AuthorCoreScope.GLOBAL,
                status = AuthorCoreStatus.STABLE,
                createdAt = now,
                updatedAt = now,
            ),
        )
        val read = r.getAuthorCore(AuthorCoreId("c-1"))!!
        assertTrue(read.confirmed)
        assertEquals(AuthorCoreStatus.STABLE, read.status)
        assertEquals("逐步揭示", r.getAuthorCorePatternsByKey("core:foundation").first().statement)
    }

    @Test
    fun `candidate round-trips aggregation fields`() {
        val h = handle()
        val r = repo(h.db)
        val c = AuthorCoreCandidate(
            candidateId = AuthorCoreCandidateId("cd-1"),
            patternKey = "core:foundation",
            scope = AuthorCoreScope.NOVEL,
            novelId = NovelId("n1"),
            statement = "…",
            positiveEvidence = 3,
            negativeEvidence = 1,
            observationCount = 4,
            weightedScore = 2.4,
            consistency = 0.75,
            recency = now,
            contradictionCount = 1,
            confidence = Confidence(0.6),
            status = AuthorCoreStatus.CANDIDATE,
            createdAt = now,
            updatedAt = now,
        )
        r.upsertAuthorCoreCandidate(c)
        val read = r.getAuthorCoreCandidate(AuthorCoreCandidateId("cd-1"))!!
        assertEquals(4, read.observationCount)
        assertEquals(1, read.contradictionCount)
        assertEquals(3, read.positiveEvidence)
        assertEquals(1, r.listCandidates(novelId = NovelId("n1")).size)
    }

    @Test
    fun `evidence link is idempotent per pattern`() {
        val h = handle()
        val r = repo(h.db)
        val link = AuthorCoreEvidenceLink(AuthorCoreEvidenceLinkId("l-1"), "core:foundation", AuthorEvidenceId("e1"), now)
        r.linkEvidence(link)
        r.linkEvidence(link)
        assertTrue(r.evidenceLinkExists("core:foundation", AuthorEvidenceId("e1")))
        assertEquals(1, r.listEvidenceLinks("core:foundation").size, "同一 (patternKey,evidenceId) 只入链一次")
    }

    @Test
    fun `learning toggle persists`() {
        val h = handle()
        val r = repo(h.db)
        assertFalse(r.isLearningPaused())
        r.setLearningPaused(true)
        assertTrue(r.isLearningPaused())
        r.setLearningPaused(false)
        assertFalse(r.isLearningPaused())
    }

    @Test
    fun `wipe clears core tables`() {
        val h = handle()
        val r = repo(h.db)
        r.upsertAuthorCore(core())
        r.linkEvidence(AuthorCoreEvidenceLink(AuthorCoreEvidenceLinkId("l-1"), "core:foundation", AuthorEvidenceId("e1"), now))
        r.wipeLearningResults()
        assertNull(r.getAuthorCore(AuthorCoreId("c-1")))
        assertTrue(r.listEvidenceLinks("core:foundation").isEmpty())
        assertFalse(r.isLearningPaused())
    }

    @Test
    fun `version chain survives reopen round-trip`() {
        val file = java.nio.file.Files.createTempFile("qianyan_core_ver", ".db").toAbsolutePath()
        val url = "jdbc:sqlite:$file"
        try {
            val h1 = QianyanDbFactory.open(url)
            val r1 = repo(h1.db)
            val p1 = core().copy(coreId = AuthorCoreId("v1"), version = 1L, status = com.qianyan.model.author.AuthorCoreStatus.STABLE, patternId = com.qianyan.model.AuthorCorePatternId("pat1"))
            r1.upsertAuthorCorePattern(
                com.qianyan.model.author.AuthorCorePattern(
                    patternId = com.qianyan.model.AuthorCorePatternId("pat1"),
                    patternKey = "core:foundation",
                    statement = "V1 原始",
                    scope = com.qianyan.model.author.AuthorCoreScope.GLOBAL,
                    status = com.qianyan.model.author.AuthorCoreStatus.STABLE,
                    version = 1L,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            r1.upsertAuthorCore(p1)
            val p2 = p1.copy(coreId = AuthorCoreId("v2"), version = 2L, status = com.qianyan.model.author.AuthorCoreStatus.STABLE, patternId = com.qianyan.model.AuthorCorePatternId("pat2"), supersededBy = null)
            r1.upsertAuthorCorePattern(
                com.qianyan.model.author.AuthorCorePattern(
                    patternId = com.qianyan.model.AuthorCorePatternId("pat2"),
                    patternKey = "core:foundation",
                    statement = "V2 新",
                    scope = com.qianyan.model.author.AuthorCoreScope.GLOBAL,
                    status = com.qianyan.model.author.AuthorCoreStatus.STABLE,
                    version = 2L,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            r1.upsertAuthorCore(p2)
            // 旧 Core supersede → 指向 V2
            r1.setCoreStatus(AuthorCoreId("v1"), com.qianyan.model.author.AuthorCoreStatus.SUPERSEDED, supersededBy = AuthorCoreId("v2"), revokedAt = null)
            assertEquals(2, r1.listCores().size)
            (h1.driver as JdbcSqliteDriver?)?.getConnection()?.close()

            // 重开同一文件 → V1 + V2 均可读，旧内容保留
            val h2 = QianyanDbFactory.open(url)
            val r2 = repo(h2.db)
            val v1 = r2.getAuthorCore(AuthorCoreId("v1"))!!
            val v2 = r2.getAuthorCore(AuthorCoreId("v2"))!!
            assertEquals(com.qianyan.model.author.AuthorCoreStatus.SUPERSEDED, v1.status)
            assertEquals(AuthorCoreId("v2"), v1.supersededBy)
            assertEquals(com.qianyan.model.author.AuthorCoreStatus.STABLE, v2.status)
            assertEquals("V1 原始", r2.getAuthorCorePattern(com.qianyan.model.AuthorCorePatternId("pat1"))!!.statement)
            assertEquals("V2 新", r2.getAuthorCorePattern(v2.patternId!!)!!.statement)
            assertEquals(2, r2.listCores().size)
            (h2.driver as JdbcSqliteDriver?)?.getConnection()?.close()
        } finally {
            java.nio.file.Files.deleteIfExists(file)
        }
    }
}