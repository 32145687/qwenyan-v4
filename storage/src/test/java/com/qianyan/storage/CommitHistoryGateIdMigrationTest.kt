package com.qianyan.storage

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.qianyan.storage.db.DatabaseInitializer
import com.qianyan.storage.db.QianyanDb
import com.qianyan.storage.db.QianyanDbFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P1-02j B1 · Schema v24（23.sqm：v23 → v24）迁移测试。
 *
 * CommitHistory 新增**可空** `gate_id` 列 + 反查索引（Gate ↔ Commit 持久关联）。
 * 验证：全新库含该列；v23 库升级成功；历史旧行升级后 `gate_id = NULL`（不回填）；重复初始化幂等。
 */
class CommitHistoryGateIdMigrationTest {

    @Test
    fun `fresh database includes commit history gate_id column at v25`() {
        val file = java.nio.file.Files.createTempFile("qianyan_gateid_fresh", ".db").toAbsolutePath()
        try {
            val h = QianyanDbFactory.open("jdbc:sqlite:$file")
            val driver = h.driver as JdbcSqliteDriver
            try {
                assertTrue(columnExists(driver, "CommitHistory", "gate_id"), "全新库应含 gate_id")
                assertTrue(indexExists(driver, "idx_commit_history_gate"), "全新库应含 idx_commit_history_gate")
                assertEquals(28L, userVersion(driver), "全新库版本应为 28")
                assertEquals(QianyanDb.Schema.version, userVersion(driver))
            } finally {
                driver.getConnection().close()
            }
        } finally {
            java.nio.file.Files.deleteIfExists(file)
        }
    }

    @Test
    fun `v23 database adds gate_id and keeps legacy rows as NULL`() {
        val file = java.nio.file.Files.createTempFile("qianyan_gateid_v23", ".db").toAbsolutePath()
        val url = "jdbc:sqlite:$file"
        try {
            // 1) 建到 v24，写入一条历史提交行（不含 gate_id 的旧形态数据）
            val first = QianyanDbFactory.open(url)
            val d1 = first.driver as JdbcSqliteDriver
            d1.execute(
                null,
                "INSERT INTO CommitHistory(history_id, commit_id, project_id, operation, artifact_id, novel_id, " +
                    "chapter_id, variant_id, previous_draft_id, resulting_draft_id, previous_content, resulting_content, " +
                    "summary, reverted_history_id, created_at) " +
                    "VALUES ('h-legacy','c-legacy','p-legacy','COMMIT',NULL,'n-legacy','ch-legacy',NULL,NULL," +
                    "'d-legacy','','正文','历史提交',NULL,1)",
                0,
            )
            // 2) 回退为 **v23 形态**：重建 CommitHistory（无 gate_id）并保留行；user_version = 23
            downgradeToV23(d1)
            assertEquals(23L, userVersion(d1), "前置：模拟库应为 v23")
            assertTrue(!columnExists(d1, "CommitHistory", "gate_id"), "前置：v23 形态无 gate_id")
            d1.getConnection().close()

            // 3) 经同一入口重开 → 23.sqm 执行加列
            val second = QianyanDbFactory.open(url)
            val d2 = second.driver as JdbcSqliteDriver
            try {
                assertTrue(columnExists(d2, "CommitHistory", "gate_id"), "迁移后应含 gate_id")
                assertTrue(indexExists(d2, "idx_commit_history_gate"), "迁移后应含 idx_commit_history_gate")
                assertEquals(28L, userVersion(d2), "旧 v23 库应迁移到 v25")

                // 历史旧行保留，且 gate_id = NULL（不回填、不猜测）
                val row = QianyanDb(d2).commitHistoryQueries.getCommitHistoryById("h-legacy").executeAsOne()
                assertEquals("正文", row.resulting_content, "历史行内容必须原样保留")
                assertNull(row.gate_id, "历史旧行 gate_id 必须为 NULL")

                // 4) 重复初始化幂等
                DatabaseInitializer.initializeDatabase(d2)
                DatabaseInitializer.initializeDatabase(d2)
                assertEquals(28L, userVersion(d2))
                assertNull(QianyanDb(d2).commitHistoryQueries.getCommitHistoryById("h-legacy").executeAsOne().gate_id)
            } finally {
                d2.getConnection().close()
            }
        } finally {
            java.nio.file.Files.deleteIfExists(file)
        }
    }

    /* ---------------- helpers ---------------- */

    /** 把当前库真实回退为 v23 形态：CommitHistory 重建为**无 gate_id**（行保留），并重置 user_version = 23。 */
    private fun downgradeToV23(driver: JdbcSqliteDriver) {
        driver.execute(null, "CREATE TABLE CommitHistory_v23_sim (" +
            "history_id TEXT NOT NULL PRIMARY KEY, commit_id TEXT NOT NULL, project_id TEXT NOT NULL, operation TEXT NOT NULL, " +
            "artifact_id TEXT, novel_id TEXT NOT NULL, chapter_id TEXT NOT NULL, variant_id TEXT, previous_draft_id TEXT, " +
            "resulting_draft_id TEXT NOT NULL, previous_content TEXT NOT NULL, resulting_content TEXT NOT NULL, " +
            "summary TEXT NOT NULL, reverted_history_id TEXT, created_at INTEGER NOT NULL)", 0)
        driver.execute(null, "INSERT INTO CommitHistory_v23_sim " +
            "(history_id, commit_id, project_id, operation, artifact_id, novel_id, chapter_id, variant_id, previous_draft_id, " +
            "resulting_draft_id, previous_content, resulting_content, summary, reverted_history_id, created_at) " +
            "SELECT history_id, commit_id, project_id, operation, artifact_id, novel_id, chapter_id, variant_id, previous_draft_id, " +
            "resulting_draft_id, previous_content, resulting_content, summary, reverted_history_id, created_at FROM CommitHistory", 0)
        driver.execute(null, "DROP TABLE CommitHistory", 0)
        driver.execute(null, "ALTER TABLE CommitHistory_v23_sim RENAME TO CommitHistory", 0)
        driver.execute(null, "CREATE UNIQUE INDEX idx_commit_history_artifact ON CommitHistory(artifact_id)", 0)
        driver.execute(null, "CREATE INDEX idx_commit_history_project ON CommitHistory(project_id, created_at)", 0)
        // P1-02j.2：回退到 v24 之前必须真实摘除 round_no（24.sqm 的 ADD COLUMN 不幂等）
        driver.execute(null, "ALTER TABLE WorkflowStep DROP COLUMN round_no", 0)
        // P2 v27（26.sqm）：Character/CharacterState/Event 新列不幂等，降级前必须真实摘除
        driver.dropWorldModelV27Columns()
        // P3 v28（27.sqm）：CandidateFact 由迁移建出，降级前必须真实摘除（CREATE TABLE 不幂等）
        driver.execute(null, "DROP TABLE IF EXISTS CandidateFact", 0)
        driver.execute(null, "PRAGMA user_version = 23", 0)
    }

    private fun columnExists(driver: JdbcSqliteDriver, table: String, column: String): Boolean =
        driver.executeQuery(
            null, "SELECT 1 FROM pragma_table_info('$table') WHERE name='$column' LIMIT 1",
            { cursor -> QueryResult.Value(cursor.next().value) }, 0,
        ).value

    private fun indexExists(driver: JdbcSqliteDriver, name: String): Boolean =
        driver.executeQuery(
            null, "SELECT 1 FROM sqlite_master WHERE type='index' AND name='$name' LIMIT 1",
            { cursor -> QueryResult.Value(cursor.next().value) }, 0,
        ).value

    private fun userVersion(driver: JdbcSqliteDriver): Long =
        driver.executeQuery(
            null, "PRAGMA user_version",
            { cursor -> cursor.next(); QueryResult.Value(cursor.getLong(0) ?: 0L) }, 0,
        ).value
}
