package com.qianyan.storage

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.qianyan.storage.db.DatabaseInitializer
import com.qianyan.storage.db.QianyanDbFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * P14-F.2 · v9 → v10 migration 测试。
 * 构建真实 v9 schema（含 Reveal 表 + scope 索引；**无 StoryFoundation / FoundationOverride**；user_version=9），
 * 写入代表性 v9 数据（Novel / NarrativeState / Reveal），初始化后验证：
 *   1) StoryFoundation / FoundationOverride 两表已创建；
 *   2) `PRAGMA user_version` 同步为 10；
 *   3) 既有 v9 数据（Novel / NarrativeState / Reveal）仍可读取，迁移未触碰既有表语义；
 *   4) 重复执行初始化幂等安全。
 */
class StoryFoundationMigrationTest {

    /** 真实 v9 schema 最小足集：v8 全量表 + Reveal 表 + scope 索引（P13 LCL-D），无 P14-F.2 两表。 */
    private val V9_MINIMAL_DDL: List<String> = v9MinimalDdl()

    private fun JdbcSqliteDriver.exec(sql: String) { execute(null, sql, 0) }

    private fun tableExists(driver: JdbcSqliteDriver, table: String): Boolean =
        driver.executeQuery(null, "SELECT 1 FROM sqlite_master WHERE type='table' AND name='$table' LIMIT 1",
            { cursor -> QueryResult.Value(cursor.next().value) }, 0).value

    private fun userVersion(driver: JdbcSqliteDriver): Long =
        driver.executeQuery(null, "PRAGMA user_version",
            { cursor -> cursor.next(); QueryResult.Value(cursor.getLong(0) ?: 0L) }, 0).value

    /** 读取某表某列的值（验证既有 v9 数据迁移后仍可读）。 */
    private fun scalar(driver: JdbcSqliteDriver, sql: String): String? =
        driver.executeQuery(null, sql,
            { cursor ->
                cursor.next()
                QueryResult.Value(cursor.getString(0))
            }, 0).value

    @Test
    fun `v9 to v10 migration adds foundation tables and preserves v9 data`() {
        val file = java.nio.file.Files.createTempFile("qianyan_foundation_v10_test", ".db").toAbsolutePath()
        val url = "jdbc:sqlite:$file"
        try {
            // 1) 构建真实 v9 schema，写入代表性 v9 数据
            val v9 = JdbcSqliteDriver(url)
            V9_MINIMAL_DDL.forEach { v9.exec(it) }
            v9.exec("PRAGMA user_version = 9")
            v9.exec(
                "INSERT INTO Novel(novel_id, project_id, title, source, genre, synopsis, scope, status, created_at, updated_at) " +
                    "VALUES ('n-legacy', 'p-legacy', '旧书', 'ORIGINAL_NOVEL', '[\"romance\"]', '', 'ORIGINAL', 'DRAFT', 1730000000000, 1730000000000)",
            )
            v9.exec(
                "INSERT INTO NarrativeState(narrative_state_id, novel_id, variant_id, scope, version, open_threads, character_stages, relationship_deltas, foreshadow_pressures, updated_at) " +
                    "VALUES ('ns-legacy', 'n-legacy', NULL, 'ORIGINAL', 3, '[]', '{}', '[]', '[]', 1730000001000)",
            )
            v9.exec(
                "INSERT INTO Reveal(reveal_id, novel_id, variant_id, scope, information_id, occurred_at, created_at) " +
                    "VALUES ('r-legacy', 'n-legacy', NULL, 'ORIGINAL', 'info-1', 1730000002000, 1730000003000)",
            )
            assertTrue(!tableExists(v9, "StoryFoundation"), "v9 库不应有 StoryFoundation")
            assertTrue(!tableExists(v9, "FoundationOverride"), "v9 库不应有 FoundationOverride")
            v9.getConnection().close()

            // 2) 重新打开：DatabaseInitializer 检测旧 v9 库 → 应用 9.sqm 迁移到 v10
            val h = QianyanDbFactory.open(url)
            val driver = h.driver as JdbcSqliteDriver
            try {
                // 3) 两新表已创建，版本同步为 10
                assertTrue(tableExists(driver, "StoryFoundation"), "migration 后应存在 StoryFoundation（P14-F.2）")
                assertTrue(tableExists(driver, "FoundationOverride"), "migration 后应存在 FoundationOverride（P14-F.2）")
                assertEquals(13L, userVersion(driver), "migration 后 user_version 应为最新版本 13（P18-A Schema v13）")

                // 4) 既有 v9 数据仍可读取，迁移未触碰既有表语义
                assertEquals("旧书", scalar(driver, "SELECT title FROM Novel WHERE novel_id='n-legacy'"), "Novel 旧数据应保持")
                assertEquals("n-legacy", scalar(driver, "SELECT novel_id FROM NarrativeState WHERE narrative_state_id='ns-legacy'"), "NarrativeState 旧数据应保持")
                assertEquals("info-1", scalar(driver, "SELECT information_id FROM Reveal WHERE reveal_id='r-legacy'"), "Reveal 旧数据应保持")

                // 5) 幂等
                DatabaseInitializer.initializeDatabase(driver)
                DatabaseInitializer.initializeDatabase(driver)
                assertTrue(tableExists(driver, "StoryFoundation"), "重复初始化后 StoryFoundation 表仍存在")
            } finally {
                driver.getConnection().close()
            }
        } finally {
            java.nio.file.Files.deleteIfExists(file)
        }
    }
}

/** 真实 v9 schema 最小足集（P13 LCL-D）：v8 全量表 + Reveal + scope 索引；由 v9→v10 / v10→v11 迁移测试共享。 */
internal fun v9MinimalDdl(): List<String> = listOf(
    """CREATE TABLE Novel (novel_id TEXT NOT NULL PRIMARY KEY, project_id TEXT NOT NULL, title TEXT NOT NULL, source TEXT NOT NULL, genre TEXT NOT NULL DEFAULT '[]', synopsis TEXT NOT NULL DEFAULT '', scope TEXT NOT NULL, status TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)""",
    """CREATE TABLE NovelVariant (variant_id TEXT NOT NULL PRIMARY KEY, novel_id TEXT NOT NULL, base_novel_id TEXT NOT NULL, project_id TEXT NOT NULL, name TEXT NOT NULL, status TEXT NOT NULL, blueprint TEXT, scope_spec TEXT, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, FOREIGN KEY (novel_id) REFERENCES Novel(novel_id))""",
    """CREATE TABLE Task (task_id TEXT NOT NULL PRIMARY KEY, type TEXT NOT NULL, status TEXT NOT NULL, progress REAL NOT NULL DEFAULT 0, revision_count INTEGER NOT NULL DEFAULT 0, error TEXT, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)""",
    """CREATE TABLE ChapterDraft (draft_id TEXT NOT NULL PRIMARY KEY, novel_id TEXT NOT NULL, variant_id TEXT, scope TEXT NOT NULL, chapter_id TEXT, chapter_plan_id TEXT, content TEXT NOT NULL, status TEXT NOT NULL, source_model TEXT NOT NULL DEFAULT '', created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)""",
    """CREATE TABLE Chapter (chapter_id TEXT NOT NULL PRIMARY KEY, novel_id TEXT NOT NULL, variant_id TEXT, scope TEXT NOT NULL, order_no INTEGER NOT NULL, title TEXT NOT NULL DEFAULT '', status TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, FOREIGN KEY (novel_id) REFERENCES Novel(novel_id))""",
    """CREATE TABLE Foreshadow (foreshadow_id TEXT NOT NULL PRIMARY KEY, novel_id TEXT NOT NULL, variant_id TEXT, scope TEXT NOT NULL, chapter_id TEXT, content TEXT NOT NULL, resolved INTEGER NOT NULL DEFAULT 0, state TEXT NOT NULL DEFAULT 'PLANTED', updated_at INTEGER NOT NULL DEFAULT 0, last_transition_reason TEXT, payoff_chapter_id TEXT, created_at INTEGER NOT NULL, FOREIGN KEY (novel_id) REFERENCES Novel(novel_id))""",
    """CREATE TABLE Event (event_id TEXT NOT NULL PRIMARY KEY, novel_id TEXT NOT NULL, variant_id TEXT, scope TEXT NOT NULL, name TEXT NOT NULL, description TEXT NOT NULL DEFAULT '', type TEXT NOT NULL, importance INTEGER NOT NULL DEFAULT 5, when_json TEXT, who TEXT NOT NULL DEFAULT '[]', chapter_id TEXT, status TEXT NOT NULL, created_at INTEGER NOT NULL, FOREIGN KEY (novel_id) REFERENCES Novel(novel_id))""",
    """CREATE TABLE TimelineEntry (timeline_id TEXT NOT NULL PRIMARY KEY, novel_id TEXT NOT NULL, variant_id TEXT, scope TEXT NOT NULL, position TEXT NOT NULL, event_id TEXT, description TEXT NOT NULL DEFAULT '', chapter_id TEXT, FOREIGN KEY (novel_id) REFERENCES Novel(novel_id))""",
    """CREATE TABLE Workflow (workflow_id TEXT NOT NULL PRIMARY KEY, novel_id TEXT NOT NULL, variant_id TEXT, kind TEXT NOT NULL, definition_version TEXT NOT NULL DEFAULT 'P12.2-v1', status TEXT NOT NULL, active_chapter_id TEXT, current_step_id TEXT, pending_gate_id TEXT, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)""",
    """CREATE TABLE NarrativeState (narrative_state_id TEXT NOT NULL PRIMARY KEY, novel_id TEXT NOT NULL, variant_id TEXT, scope TEXT NOT NULL, version INTEGER NOT NULL DEFAULT 0, main_goal TEXT NOT NULL DEFAULT '', current_conflict TEXT, open_threads TEXT NOT NULL DEFAULT '[]', character_stages TEXT NOT NULL DEFAULT '{}', relationship_deltas TEXT NOT NULL DEFAULT '[]', foreshadow_pressures TEXT NOT NULL DEFAULT '[]', current_pacing TEXT, last_chapter_delta TEXT NOT NULL DEFAULT '', updated_at INTEGER NOT NULL, FOREIGN KEY (novel_id) REFERENCES Novel(novel_id))""",
    """CREATE TABLE NarrativeDelta (delta_id TEXT NOT NULL PRIMARY KEY, narrative_state_id TEXT NOT NULL, novel_id TEXT NOT NULL, variant_id TEXT, scope TEXT NOT NULL, chapter_id TEXT, main_goal TEXT, current_conflict TEXT, open_threads TEXT NOT NULL DEFAULT '[]', character_stages TEXT NOT NULL DEFAULT '{}', relationship_deltas TEXT NOT NULL DEFAULT '[]', foreshadow_pressures TEXT NOT NULL DEFAULT '[]', current_pacing TEXT, summary TEXT NOT NULL DEFAULT '', created_at INTEGER NOT NULL, FOREIGN KEY (novel_id) REFERENCES Novel(novel_id))""",
    """CREATE TABLE Reveal (reveal_id TEXT NOT NULL PRIMARY KEY, novel_id TEXT NOT NULL, variant_id TEXT, scope TEXT NOT NULL, chapter_id TEXT, information_id TEXT NOT NULL, occurred_at INTEGER NOT NULL, reason TEXT, created_at INTEGER NOT NULL, FOREIGN KEY (novel_id) REFERENCES Novel(novel_id))""",
    """CREATE INDEX IF NOT EXISTS idx_reveal_scope ON Reveal(novel_id, variant_id, occurred_at, reveal_id)""",
    """CREATE INDEX IF NOT EXISTS idx_event_scope ON Event(novel_id, variant_id)""",
    """CREATE INDEX IF NOT EXISTS idx_foreshadow_scope ON Foreshadow(novel_id, variant_id)""",
    """CREATE INDEX IF NOT EXISTS idx_timeline_scope ON TimelineEntry(novel_id, variant_id)""",
)