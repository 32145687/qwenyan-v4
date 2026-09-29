package com.qianyan.storage

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.qianyan.model.ChapterId
import com.qianyan.model.NovelId
import com.qianyan.model.reading.ReadingProgress
import com.qianyan.storage.db.DatabaseInitializer
import com.qianyan.storage.db.QianyanDbFactory
import com.qianyan.storage.repository.SqliteChapterRepository
import com.qianyan.storage.repository.SqliteDraftRepository
import com.qianyan.storage.repository.SqliteNovelRepository
import com.qianyan.storage.repository.SqliteReadingProgressRepository
import kotlinx.datetime.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P20-P4 · v16 → v17 migration 测试（FD-9 additive only）。
 *
 * 构建一个 v16 库骨架（`PRAGMA user_version = 16`，既有检测表齐全、**无 ReadingProgress**），
 * 写入代表性旧数据，再经 [DatabaseInitializer.initializeDatabase] 升级到 v17，验证：
 *   1) 仅新增 ReadingProgress 表（既有表 / 列 / 数据不变）；
 *   2) 旧数据原样保留（经既有 Repository 读回，而非裸 SQL）；
 *   3) `PRAGMA user_version` 同步为 17；
 *   4) 重复初始化幂等安全。
 *
 * 说明：本测试的 v16 骨架只包含 DatabaseInitializer 迁移分支检测所需的表（其 PK 列）与
 * 旧数据验证所需的最小列；16.sqm 只 CREATE ReadingProgress，不触碰其它表，故骨架不影响结论。
 */
class ReadingProgressMigrationTest {

    private val V16_DDL: List<String> = listOf(
        """
        CREATE TABLE Novel (
            novel_id    TEXT NOT NULL PRIMARY KEY,
            project_id  TEXT NOT NULL,
            title       TEXT NOT NULL,
            source      TEXT NOT NULL,
            genre       TEXT NOT NULL DEFAULT '[]',
            synopsis    TEXT NOT NULL DEFAULT '',
            scope       TEXT NOT NULL,
            status      TEXT NOT NULL,
            created_at  INTEGER NOT NULL,
            updated_at  INTEGER NOT NULL
        )
        """,
        """
        CREATE TABLE Task (
            task_id         TEXT NOT NULL PRIMARY KEY,
            type            TEXT NOT NULL,
            status          TEXT NOT NULL,
            progress        REAL NOT NULL DEFAULT 0,
            revision_count  INTEGER NOT NULL DEFAULT 0,
            error           TEXT,
            created_at      INTEGER NOT NULL,
            updated_at      INTEGER NOT NULL
        )
        """,
        "CREATE TABLE Checkpoint (checkpoint_id TEXT NOT NULL PRIMARY KEY, task_id TEXT NOT NULL, revision INTEGER NOT NULL, stage TEXT NOT NULL, snapshot TEXT, created_at INTEGER NOT NULL)",
        """
        CREATE TABLE Chapter (
            chapter_id  TEXT NOT NULL PRIMARY KEY,
            novel_id    TEXT NOT NULL,
            variant_id  TEXT,
            scope       TEXT NOT NULL,
            order_no    INTEGER NOT NULL,
            title       TEXT NOT NULL,
            status      TEXT NOT NULL,
            created_at  INTEGER NOT NULL,
            updated_at  INTEGER NOT NULL
        )
        """,
        """
        CREATE TABLE ChapterDraft (
            draft_id          TEXT NOT NULL PRIMARY KEY,
            novel_id          TEXT NOT NULL,
            variant_id        TEXT,
            scope             TEXT NOT NULL,
            chapter_id        TEXT,
            chapter_plan_id   TEXT,
            previous_draft_id TEXT,
            content           TEXT NOT NULL,
            format            TEXT,
            status            TEXT NOT NULL,
            source_model      TEXT NOT NULL DEFAULT '',
            created_at        INTEGER NOT NULL,
            updated_at        INTEGER NOT NULL
        )
        """,
        // 其余 v16 检测表（本迁移不触碰，仅需存在以使迁移链推进到 v16 → v17 分支）
        "CREATE TABLE Foreshadow (foreshadow_id TEXT NOT NULL PRIMARY KEY, state TEXT)",
        // GUARD_DDL 的 variant_base_must_be_original 触发器引用 NovelVariant，骨架必须包含该表
        "CREATE TABLE NovelVariant (variant_id TEXT NOT NULL PRIMARY KEY, base_novel_id TEXT)",
        "CREATE TABLE Workflow (workflow_id TEXT NOT NULL PRIMARY KEY)",
        "CREATE TABLE NarrativeState (narrative_state_id TEXT NOT NULL PRIMARY KEY)",
        "CREATE TABLE Reveal (reveal_id TEXT NOT NULL PRIMARY KEY)",
        "CREATE TABLE StoryFoundation (novel_id TEXT NOT NULL PRIMARY KEY)",
        "CREATE TABLE AuthorProfile (profile_id TEXT NOT NULL PRIMARY KEY)",
        "CREATE TABLE AuthorCore (core_id TEXT NOT NULL PRIMARY KEY)",
        "CREATE TABLE AuthorCoreEvidenceLink (link_id TEXT NOT NULL PRIMARY KEY, provenance_novel_id TEXT)",
        "CREATE TABLE AuthorObservation (observation_id TEXT NOT NULL PRIMARY KEY)",
        "CREATE TABLE AuthorDnaVersion (dna_id TEXT NOT NULL PRIMARY KEY)",
    )

    private fun JdbcSqliteDriver.exec(sql: String) = execute(null, sql, 0)

    private fun buildV16Database(driver: JdbcSqliteDriver) {
        V16_DDL.forEach { driver.exec(it) }
        driver.exec(
            "INSERT INTO Novel(novel_id, project_id, title, source, genre, synopsis, scope, status, created_at, updated_at) " +
                "VALUES ('rp-novel', 'proj-rp', '旧书v16', 'ORIGINAL_NOVEL', '[\"仙侠\"]', '旧简介', 'ORIGINAL', 'DRAFT', 1000, 1000)",
        )
        driver.exec(
            "INSERT INTO Chapter(chapter_id, novel_id, variant_id, scope, order_no, title, status, created_at, updated_at) " +
                "VALUES ('rp-ch1', 'rp-novel', NULL, 'ORIGINAL', 1, '第一章', 'WRITTEN', 1000, 1000)",
        )
        driver.exec(
            "INSERT INTO ChapterDraft(draft_id, novel_id, variant_id, scope, chapter_id, chapter_plan_id, previous_draft_id, content, format, status, source_model, created_at, updated_at) " +
                "VALUES ('rp-d1', 'rp-novel', NULL, 'ORIGINAL', 'rp-ch1', NULL, NULL, '旧正文', NULL, 'WRITTEN', 'mock-v1', 1000, 1000)",
        )
        driver.exec("PRAGMA user_version = 16")
    }

    private fun tableExists(driver: JdbcSqliteDriver, table: String): Boolean =
        driver.executeQuery(
            null,
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name='$table' LIMIT 1",
            { cursor -> QueryResult.Value(cursor.next().value) },
            0,
        ).value

    private fun userVersion(driver: JdbcSqliteDriver): Long =
        driver.executeQuery(
            null,
            "PRAGMA user_version",
            { cursor -> cursor.next(); QueryResult.Value(cursor.getLong(0) ?: 0L) },
            0,
        ).value

    @Test
    fun `v16 to v17 migration adds reading progress and preserves old data`() {
        val file = java.nio.file.Files.createTempFile("qianyan_mig_v17_test", ".db").toAbsolutePath()
        val url = "jdbc:sqlite:$file"
        try {
            // 1) 真实 v16 骨架 + 旧数据（无 ReadingProgress）
            val v16 = JdbcSqliteDriver(url)
            buildV16Database(v16)
            assertTrue(tableExists(v16, "Novel"), "v16 库应有 Novel")
            assertTrue(!tableExists(v16, "ReadingProgress"), "v16 库不应有 ReadingProgress")
            assertEquals(16L, userVersion(v16))
            v16.getConnection().close()

            // 2) 重新打开：DatabaseInitializer 检测到 v16 → 应用 16.sqm migration 到 v17
            val h = QianyanDbFactory.open(url)
            val driver = h.driver as JdbcSqliteDriver
            try {
                assertTrue(tableExists(driver, "ReadingProgress"), "migration 后应存在 ReadingProgress 表")
                assertEquals(21L, userVersion(driver), "migration 后 user_version 应为 21")

                // 3) 旧数据原样保留（经既有 Repository 读回）
                val novels = SqliteNovelRepository(h.db)
                assertEquals("旧书v16", novels.getNovel(NovelId("rp-novel"))?.title)

                val chapters = SqliteChapterRepository(h.db)
                assertEquals("第一章", chapters.findById(ChapterId("rp-ch1"))?.title)

                val drafts = SqliteDraftRepository(h.db)
                val draft = drafts.getById(com.qianyan.model.DraftId("rp-d1"))
                assertNotNull(draft, "旧 Draft 应保留")
                assertEquals("旧正文", draft.content)
                assertNull(draft.format, "旧 Draft 的 format=null 应保持（P20-P2 legacy 兼容）")

                // 4) 迁移后 ReadingProgress 可正常读写
                val progress = SqliteReadingProgressRepository(h.db)
                assertNull(progress.get(ChapterId("rp-ch1")), "尚无阅读进度 → null（调用方从默认位置开始）")
                progress.save(
                    ReadingProgress(
                        novelId = NovelId("rp-novel"),
                        chapterId = ChapterId("rp-ch1"),
                        position = 7,
                        updatedAt = Clock.System.now(),
                    ),
                )
                assertEquals(7, progress.get(ChapterId("rp-ch1"))?.position)

                // 5) 重复初始化幂等安全
                DatabaseInitializer.initializeDatabase(driver)
                DatabaseInitializer.initializeDatabase(driver)
                assertEquals(21L, userVersion(driver))
                assertTrue(tableExists(driver, "ReadingProgress"))
            } finally {
                driver.getConnection().close()
            }
        } finally {
            java.nio.file.Files.deleteIfExists(file)
        }
    }

    @Test
    fun `fresh database includes reading progress table at v17`() {
        val h = QianyanDbFactory.open(JdbcSqliteDriver.IN_MEMORY)
        val driver = h.driver as JdbcSqliteDriver
        assertTrue(tableExists(driver, "ReadingProgress"), "全新库应直接建出 ReadingProgress 表")
        assertEquals(21L, userVersion(driver), "全新库 user_version 应为 21")
        driver.getConnection().close()
    }
}