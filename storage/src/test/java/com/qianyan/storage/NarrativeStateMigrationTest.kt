package com.qianyan.storage

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.qianyan.storage.db.DatabaseInitializer
import com.qianyan.storage.db.QianyanDbFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * P13 LCL-A · v6 → v7 migration 测试。
 *
 * 构建一个**真实 v6 schema**（含 DatabaseInitializer 各分支判定所需表：Novel / NovelVariant / Task /
 * ChapterDraft / Chapter / Foreshadow / Workflow，且**无 NarrativeState**，`PRAGMA user_version = 6`），
 * 经 [QianyanDbFactory.open] / [DatabaseInitializer.initializeDatabase] 升级到 v7，验证：
 *   1) NarrativeState / NarrativeDelta 两表已创建（P13 LCL-A）；
 *   2) `PRAGMA user_version` 同步为 7；
 *   3) 重复执行初始化幂等安全。
 */
class NarrativeStateMigrationTest {

    /** 真实 v6 schema 的最小足集（含 when 分支判定所需全部表；P12.1.1/P12.2 的其余 Story State / Workflow 表载入 6.sqm 时不受影响）。 */
    private val V6_MINIMAL_DDL: List<String> = listOf(
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
        """
        CREATE TABLE Foreshadow (
            foreshadow_id TEXT NOT NULL PRIMARY KEY,
            novel_id      TEXT NOT NULL,
            variant_id    TEXT,
            scope         TEXT NOT NULL,
            chapter_id    TEXT,
            content       TEXT NOT NULL,
            resolved      INTEGER NOT NULL DEFAULT 0,
            created_at    INTEGER NOT NULL,
            FOREIGN KEY (novel_id) REFERENCES Novel(novel_id)
        )
        """,
        """
        CREATE TABLE Workflow (
            workflow_id         TEXT NOT NULL PRIMARY KEY,
            novel_id            TEXT NOT NULL,
            variant_id          TEXT,
            kind                TEXT NOT NULL,
            definition_version  TEXT NOT NULL DEFAULT 'P12.2-v1',
            status              TEXT NOT NULL,
            active_chapter_id   TEXT,
            current_step_id     TEXT,
            pending_gate_id     TEXT,
            created_at          INTEGER NOT NULL,
            updated_at          INTEGER NOT NULL
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
    fun `v6 to v7 migration creates narrative state tables and bumps version`() {
        val file = java.nio.file.Files.createTempFile("qianyan_narrative_v7_test", ".db").toAbsolutePath()
        val url = "jdbc:sqlite:$file"
        try {
            // 1) 构建真实 v6 schema（有 Workflow，无 NarrativeState）
            val v6 = JdbcSqliteDriver(url)
            V6_MINIMAL_DDL.forEach { v6.exec(it) }
            v6.exec("PRAGMA user_version = 6")
            assertTrue(tableExists(v6, "Workflow"), "v6 库应有 Workflow")
            assertTrue(!tableExists(v6, "NarrativeState"), "v6 库不应有 NarrativeState")
            v6.getConnection().close()

            // 2) 重新打开：DatabaseInitializer 检测到旧 v6 库 → 应用 6.sqm 迁移到 v7
            val h = QianyanDbFactory.open(url)
            val driver = h.driver as JdbcSqliteDriver

            // 3) NarrativeState / NarrativeDelta 已创建，版本同步为 7
            assertTrue(tableExists(driver, "NarrativeState"), "migration 后应存在 NarrativeState（P13 LCL-A）")
            assertTrue(tableExists(driver, "NarrativeDelta"), "migration 后应存在 NarrativeDelta（P13 LCL-A）")
            assertEquals(7L, userVersion(driver), "migration 后 user_version 应为 7（P13 LCL-A Schema v7）")

            // 4) 重复执行初始化幂等安全（不报"表已存在"）
            DatabaseInitializer.initializeDatabase(driver)
            DatabaseInitializer.initializeDatabase(driver)
            assertTrue(tableExists(driver, "NarrativeState"), "重复初始化后 NarrativeState 表仍存在")
        } finally {
            java.nio.file.Files.deleteIfExists(file)
        }
    }
}