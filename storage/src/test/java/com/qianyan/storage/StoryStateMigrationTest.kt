package com.qianyan.storage

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.qianyan.storage.db.DatabaseInitializer
import com.qianyan.storage.db.QianyanDbFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * P12.1.1 · v4 → v5 migration 测试。
 *
 * 目标：构建一个**真实 v4 schema**（Chapter 表已在，无 v5 新增的 6 张 Story State 表，
 * `PRAGMA user_version = 4`），经 [QianyanDbFactory.open] / [DatabaseInitializer.initializeDatabase]
 * 升级到 v5，验证：
 *   1) 6 张 Story State 表（Character / CharacterState / WorldRule / Event / TimelineEntry / Foreshadow）已创建；
 *   2) `PRAGMA user_version` 同步为 5；
 *   3) 重复执行初始化幂等安全。
 * 说明：v4 → v5 之前的分支判定基于表存在性，因此只需最小 v4 DDL（含分支判定所需的 Novel /
 * Task / ChapterDraft / Chapter 四表）即可进入 v4→v5 分支并应用 4.sqm。
 */
class StoryStateMigrationTest {

    /** 真实 v4 schema 的最小足集（含 DatabaseInitializer when 分支判定所需全部表）。 */
    private val V4_MINIMAL_DDL: List<String> = listOf(
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
        CREATE TABLE NovelVariant (
            variant_id    TEXT NOT NULL PRIMARY KEY,
            novel_id      TEXT NOT NULL,
            base_novel_id TEXT NOT NULL,
            project_id    TEXT NOT NULL,
            name          TEXT NOT NULL,
            status        TEXT NOT NULL,
            blueprint     TEXT,
            scope_spec    TEXT,
            created_at    INTEGER NOT NULL,
            updated_at    INTEGER NOT NULL,
            FOREIGN KEY (novel_id) REFERENCES Novel(novel_id)
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
        """
        CREATE TABLE ChapterDraft (
            draft_id        TEXT NOT NULL PRIMARY KEY,
            novel_id        TEXT NOT NULL,
            variant_id      TEXT,
            scope           TEXT NOT NULL,
            chapter_id      TEXT,
            chapter_plan_id TEXT,
            content         TEXT NOT NULL,
            status          TEXT NOT NULL,
            source_model    TEXT NOT NULL DEFAULT '',
            created_at      INTEGER NOT NULL,
            updated_at      INTEGER NOT NULL
        )
        """,
        """
        CREATE TABLE Chapter (
            chapter_id  TEXT NOT NULL PRIMARY KEY,
            novel_id    TEXT NOT NULL,
            variant_id  TEXT,
            scope       TEXT NOT NULL,
            order_no    INTEGER NOT NULL,
            title       TEXT NOT NULL DEFAULT '',
            status      TEXT NOT NULL,
            created_at  INTEGER NOT NULL,
            updated_at  INTEGER NOT NULL,
            FOREIGN KEY (novel_id) REFERENCES Novel(novel_id)
        )
        """,
    )

    private fun JdbcSqliteDriver.exec(sql: String) {
        execute(null, sql, 0)
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
            { cursor ->
                cursor.next()
                QueryResult.Value(cursor.getLong(0) ?: 0L)
            },
            0,
        ).value

    @Test
    fun `v4 to v5 migration creates the six story state tables`() {
        val file = java.nio.file.Files.createTempFile("qianyan_story_state_v5_test", ".db").toAbsolutePath()
        val url = "jdbc:sqlite:$file"
        try {
            // 1) 构建真实 v4 schema（有 Chapter，无 v5 新表）
            val v4 = JdbcSqliteDriver(url)
            V4_MINIMAL_DDL.forEach { v4.exec(it) }
            v4.exec("PRAGMA user_version = 4")
            assertTrue(tableExists(v4, "Chapter"), "v4 库应有 Chapter")
            assertTrue(!tableExists(v4, "Foreshadow"), "v4 库不应有 Foreshadow")
            v4.getConnection().close()

            // 2) 重新打开：DatabaseInitializer 检测到旧 v4 库 → 应用 4.sqm 迁移到 v5
            val h = QianyanDbFactory.open(url)
            val driver = h.driver as JdbcSqliteDriver

            // 3) 6 张 Story State 表已创建，版本同步为 5
            listOf("Character", "CharacterState", "WorldRule", "Event", "TimelineEntry", "Foreshadow").forEach { tbl ->
                assertTrue(tableExists(driver, tbl), "migration 后应存在 $tbl 表（P12.1.1 Story State）")
            }
            assertEquals(5L, userVersion(driver), "migration 后 user_version 应为 5（P12.1.1 Schema v5）")

            // 4) 重复执行初始化幂等安全（不报"表已存在"）
            DatabaseInitializer.initializeDatabase(driver)
            DatabaseInitializer.initializeDatabase(driver)
            assertTrue(tableExists(driver, "Foreshadow"), "重复初始化后 Foreshadow 表仍存在")
        } finally {
            java.nio.file.Files.deleteIfExists(file)
        }
    }
}