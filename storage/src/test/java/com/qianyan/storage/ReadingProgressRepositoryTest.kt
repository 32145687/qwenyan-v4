package com.qianyan.storage

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.qianyan.model.ChapterId
import com.qianyan.model.NovelId
import com.qianyan.model.reading.ReadingProgress
import com.qianyan.storage.db.QianyanDbFactory
import com.qianyan.storage.repository.SqliteNovelRepository
import com.qianyan.storage.repository.SqliteReadingProgressRepository
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P20-P4 · ReadingProgress 仓储测试（FD-9：一章一条，只保存阅读位置）。
 */
class ReadingProgressRepositoryTest {

    private fun progress(chapterId: String, position: Int, at: Instant = Clock.System.now()) = ReadingProgress(
        novelId = NovelId("n-rp"),
        chapterId = ChapterId(chapterId),
        position = position,
        updatedAt = at,
    )

    /** 建一个含 Novel（满足 FK）的内存库 + 仓储。 */
    private fun repo(): Pair<SqliteReadingProgressRepository, JdbcSqliteDriver> {
        val h = QianyanDbFactory.open(JdbcSqliteDriver.IN_MEMORY)
        val driver = h.driver as JdbcSqliteDriver
        driver.execute(
            null,
            "INSERT INTO Novel(novel_id, project_id, title, source, genre, synopsis, scope, status, created_at, updated_at) " +
                "VALUES ('n-rp', 'p', '书', 'ORIGINAL_NOVEL', '[]', '', 'ORIGINAL', 'DRAFT', 1, 1)",
            0,
        )
        return SqliteReadingProgressRepository(h.db) to driver
    }

    @Test
    fun `save then get round trips position`() {
        val (repo, driver) = repo()
        val at = Instant.fromEpochMilliseconds(1_700_000_000_000)
        repo.save(progress("c1", 5, at))

        val read = repo.get(ChapterId("c1"))
        assertEquals(5, read?.position)
        assertEquals(NovelId("n-rp"), read?.novelId)
        assertEquals(at, read?.updatedAt)
        driver.getConnection().close()
    }

    @Test
    fun `missing progress returns null`() {
        val (repo, driver) = repo()
        assertNull(repo.get(ChapterId("ghost")), "无记录 → null（调用方从默认位置开始）")
        driver.getConnection().close()
    }

    @Test
    fun `save is upsert per chapter and chapters are isolated`() {
        val (repo, driver) = repo()
        repo.save(progress("c1", 1))
        repo.save(progress("c1", 9))
        repo.save(progress("c2", 3))

        assertEquals(9, repo.get(ChapterId("c1"))?.position, "同章节覆盖，不产生第二行")
        assertEquals(3, repo.get(ChapterId("c2"))?.position, "章节间阅读位置相互独立")
        val rows = driver.executeQuery(
            null,
            "SELECT COUNT(*) FROM ReadingProgress",
            { cursor -> cursor.next(); app.cash.sqldelight.db.QueryResult.Value(cursor.getLong(0) ?: 0L) },
            0,
        ).value
        assertTrue(rows == 2L, "应有 2 行（每章一行），实际 $rows")
        driver.getConnection().close()
    }
}