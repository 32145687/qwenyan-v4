package com.qianyan.storage

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.qianyan.model.AuthorObservationId
import com.qianyan.model.NovelId
import com.qianyan.model.author.AuthorCoreScope
import com.qianyan.model.author.AuthorEvidenceType
import com.qianyan.model.author.AuthorObservation
import com.qianyan.model.author.AuthorObservationSource
import com.qianyan.storage.db.QianyanDb
import com.qianyan.storage.db.QianyanDbFactory
import com.qianyan.storage.db.QianyanDbHandle
import com.qianyan.storage.repository.AuthorObservationRepository
import com.qianyan.storage.repository.SqliteAuthorObservationRepository
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * P18-A · AuthorObservationRepository（独立 Storage Boundary）测试：
 * create/get/exists/count/all；deterministic ID 幂等（同 id 重复 upsert 不新增）；重开文件恢复。
 */
class AuthorObservationRepositoryTest {

    private val now: Instant = Instant.fromEpochSeconds(1790000000, 0)

    private fun handle(): QianyanDbHandle = QianyanDbFactory.open(JdbcSqliteDriver.IN_MEMORY)

    private fun repo(db: QianyanDb): AuthorObservationRepository = SqliteAuthorObservationRepository(db)

    private fun obs(id: String): AuthorObservation = AuthorObservation(
        observationId = AuthorObservationId(id),
        scope = AuthorCoreScope.GLOBAL,
        decisionType = AuthorEvidenceType.ADOPT,
        novelId = null,
        source = AuthorObservationSource.WRITING,
        metadata = mapOf("phase" to "writing"),
        occurredAt = now,
    )

    @Test
    fun `create and read back deterministic observation`() {
        val h = handle()
        val r = repo(h.db)
        r.upsert(obs("o-1"))
        assertTrue(r.exists(AuthorObservationId("o-1")))
        assertEquals(1L, r.count())
        val got = r.getById(AuthorObservationId("o-1"))
        assertNotNull(got)
        assertEquals(AuthorObservationSource.WRITING, got.source)
        assertEquals(AuthorEvidenceType.ADOPT, got.decisionType)
        assertEquals(AuthorCoreScope.GLOBAL, got.scope)
        assertEquals(mapOf("phase" to "writing"), got.metadata)
        assertEquals(1, r.all().size)
    }

    @Test
    fun `duplicate upsert with same deterministic id does not add rows`() {
        val h = handle()
        val r = repo(h.db)
        r.upsert(obs("o-dup"))
        r.upsert(obs("o-dup"))
        assertEquals(1L, r.count(), "同 deterministic observationId 重复 upsert 不产生新行")
        r.upsert(obs("o-2"))
        assertEquals(2L, r.count())
    }

    @Test
    fun `delete removes observation`() {
        val h = handle()
        val r = repo(h.db)
        r.upsert(obs("o-del"))
        r.delete(AuthorObservationId("o-del"))
        assertFalse(r.exists(AuthorObservationId("o-del")))
        assertEquals(0L, r.count())
    }

    @Test
    fun `observation survives reopen round-trip`() {
        val file = java.nio.file.Files.createTempFile("qianyan_obs_rt", ".db").toAbsolutePath()
        val url = "jdbc:sqlite:$file"
        try {
            val h1 = QianyanDbFactory.open(url)
            repo(h1.db).upsert(
                AuthorObservation(
                    observationId = AuthorObservationId("o-rt"),
                    scope = AuthorCoreScope.NOVEL,
                    decisionType = AuthorEvidenceType.PARTIAL_REWRITE,
                    novelId = NovelId("n1"),
                    source = AuthorObservationSource.REVISION,
                    metadata = emptyMap(),
                    occurredAt = now,
                ),
            )
            (h1.driver as JdbcSqliteDriver?)?.getConnection()?.close()

            val h2 = QianyanDbFactory.open(url)
            val r2 = repo(h2.db)
            assertEquals(1L, r2.count())
            val got = r2.getById(AuthorObservationId("o-rt"))!!
            assertEquals(AuthorCoreScope.NOVEL, got.scope)
            assertEquals(NovelId("n1"), got.novelId)
            assertEquals(AuthorObservationSource.REVISION, got.source)
            assertEquals(AuthorEvidenceType.PARTIAL_REWRITE, got.decisionType)
            (h2.driver as JdbcSqliteDriver?)?.getConnection()?.close()
        } finally {
            java.nio.file.Files.deleteIfExists(file)
        }
    }
}