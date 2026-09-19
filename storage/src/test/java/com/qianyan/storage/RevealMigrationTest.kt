package com.qianyan.storage

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.qianyan.storage.db.DatabaseInitializer
import com.qianyan.storage.db.QianyanDbFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * P13 LCL-D · v8 → v9 migration 测试。
 * 构建真实 v8 schema（Foreshadow 已含 state 列；**无 Reveal 表**；user_version=8），
 * 初始化后：Reveal 表 + scope 索引创建，user_version=9，幂等。
 */
class RevealMigrationTest {

    /** 真实 v8 schema 最小足集（含各 when 分支判定表；Foreshadow 带 v8 的 state 列）。 */
    private val V8_MINIMAL_DDL: List<String> = listOf(
        """CREATE TABLE Novel (novel_id TEXT NOT NULL PRIMARY KEY, project_id TEXT NOT NULL, title TEXT NOT NULL, source TEXT NOT NULL, genre TEXT NOT NULL DEFAULT '[]', synopsis TEXT NOT NULL DEFAULT '', scope TEXT NOT NULL, status TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)""",
        """CREATE TABLE NovelVariant (variant_id TEXT NOT NULL PRIMARY KEY, novel_id TEXT NOT NULL, base_novel_id TEXT NOT NULL, project_id TEXT NOT NULL, name TEXT NOT NULL, status TEXT NOT NULL, blueprint TEXT, scope_spec TEXT, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, FOREIGN KEY (novel_id) REFERENCES Novel(novel_id))""",
        """CREATE TABLE Task (task_id TEXT NOT NULL PRIMARY KEY, type TEXT NOT NULL, status TEXT NOT NULL, progress REAL NOT NULL DEFAULT 0, revision_count INTEGER NOT NULL DEFAULT 0, error TEXT, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)""",
        """CREATE TABLE ChapterDraft (draft_id TEXT NOT NULL PRIMARY KEY, novel_id TEXT NOT NULL, variant_id TEXT, scope TEXT NOT NULL, chapter_id TEXT, chapter_plan_id TEXT, content TEXT NOT NULL, status TEXT NOT NULL, source_model TEXT NOT NULL DEFAULT '', created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)""",
        """CREATE TABLE Chapter (chapter_id TEXT NOT NULL PRIMARY KEY, novel_id TEXT NOT NULL, variant_id TEXT, scope TEXT NOT NULL, order_no INTEGER NOT NULL, title TEXT NOT NULL DEFAULT '', status TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, FOREIGN KEY (novel_id) REFERENCES Novel(novel_id))""",
        // v8 的 Foreshadow：含 state / updated_at / last_transition_reason / payoff_chapter_id（跳过 v7→v8）
        """CREATE TABLE Foreshadow (foreshadow_id TEXT NOT NULL PRIMARY KEY, novel_id TEXT NOT NULL, variant_id TEXT, scope TEXT NOT NULL, chapter_id TEXT, content TEXT NOT NULL, resolved INTEGER NOT NULL DEFAULT 0, state TEXT NOT NULL DEFAULT 'PLANTED', updated_at INTEGER NOT NULL DEFAULT 0, last_transition_reason TEXT, payoff_chapter_id TEXT, created_at INTEGER NOT NULL, FOREIGN KEY (novel_id) REFERENCES Novel(novel_id))""",
        """CREATE TABLE Event (event_id TEXT NOT NULL PRIMARY KEY, novel_id TEXT NOT NULL, variant_id TEXT, scope TEXT NOT NULL, name TEXT NOT NULL, description TEXT NOT NULL DEFAULT '', type TEXT NOT NULL, importance INTEGER NOT NULL DEFAULT 5, when_json TEXT, who TEXT NOT NULL DEFAULT '[]', chapter_id TEXT, status TEXT NOT NULL, created_at INTEGER NOT NULL, FOREIGN KEY (novel_id) REFERENCES Novel(novel_id))""",
        """CREATE TABLE TimelineEntry (timeline_id TEXT NOT NULL PRIMARY KEY, novel_id TEXT NOT NULL, variant_id TEXT, scope TEXT NOT NULL, position TEXT NOT NULL, event_id TEXT, description TEXT NOT NULL DEFAULT '', chapter_id TEXT, FOREIGN KEY (novel_id) REFERENCES Novel(novel_id))""",
        """CREATE TABLE Workflow (workflow_id TEXT NOT NULL PRIMARY KEY, novel_id TEXT NOT NULL, variant_id TEXT, kind TEXT NOT NULL, definition_version TEXT NOT NULL DEFAULT 'P12.2-v1', status TEXT NOT NULL, active_chapter_id TEXT, current_step_id TEXT, pending_gate_id TEXT, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)""",
        """CREATE TABLE NarrativeState (narrative_state_id TEXT NOT NULL PRIMARY KEY, novel_id TEXT NOT NULL, variant_id TEXT, scope TEXT NOT NULL, version INTEGER NOT NULL DEFAULT 0, main_goal TEXT NOT NULL DEFAULT '', current_conflict TEXT, open_threads TEXT NOT NULL DEFAULT '[]', character_stages TEXT NOT NULL DEFAULT '{}', relationship_deltas TEXT NOT NULL DEFAULT '[]', foreshadow_pressures TEXT NOT NULL DEFAULT '[]', current_pacing TEXT, last_chapter_delta TEXT NOT NULL DEFAULT '', updated_at INTEGER NOT NULL, FOREIGN KEY (novel_id) REFERENCES Novel(novel_id))""",
        """CREATE TABLE NarrativeDelta (delta_id TEXT NOT NULL PRIMARY KEY, narrative_state_id TEXT NOT NULL, novel_id TEXT NOT NULL, variant_id TEXT, scope TEXT NOT NULL, chapter_id TEXT, main_goal TEXT, current_conflict TEXT, open_threads TEXT NOT NULL DEFAULT '[]', character_stages TEXT NOT NULL DEFAULT '{}', relationship_deltas TEXT NOT NULL DEFAULT '[]', foreshadow_pressures TEXT NOT NULL DEFAULT '[]', current_pacing TEXT, summary TEXT NOT NULL DEFAULT '', created_at INTEGER NOT NULL, FOREIGN KEY (novel_id) REFERENCES Novel(novel_id))""",
    )

    private fun JdbcSqliteDriver.exec(sql: String) { execute(null, sql, 0) }

    private fun tableExists(driver: JdbcSqliteDriver, table: String): Boolean =
        driver.executeQuery(null, "SELECT 1 FROM sqlite_master WHERE type='table' AND name='$table' LIMIT 1",
            { cursor -> QueryResult.Value(cursor.next().value) }, 0).value

    private fun userVersion(driver: JdbcSqliteDriver): Long =
        driver.executeQuery(null, "PRAGMA user_version",
            { cursor -> cursor.next(); QueryResult.Value(cursor.getLong(0) ?: 0L) }, 0).value

    @Test
    fun `v8 to v9 migration creates reveal table and scope index`() {
        val file = java.nio.file.Files.createTempFile("qianyan_reveal_v9_test", ".db").toAbsolutePath()
        val url = "jdbc:sqlite:$file"
        try {
            val v8 = JdbcSqliteDriver(url)
            V8_MINIMAL_DDL.forEach { v8.exec(it) }
            v8.exec("PRAGMA user_version = 8")
            assertTrue(!tableExists(v8, "Reveal"), "v8 库不应有 Reveal 表")
            v8.getConnection().close()

            val h = QianyanDbFactory.open(url)
            val driver = h.driver as JdbcSqliteDriver
            try {
                DatabaseInitializer.initializeDatabase(driver)

                assertTrue(tableExists(driver, "Reveal"), "migration 后应有 Reveal 表（P13 LCL-D）")
                assertEquals(14L, userVersion(driver), "user_version 应为最新版本 13（P18-B Schema v14）")
                // scope 索引已建
                val idxEvent = driver.executeQuery(null,
                    "SELECT 1 FROM sqlite_master WHERE type='index' AND name='idx_event_scope' LIMIT 1",
                    { c -> QueryResult.Value(c.next().value) }, 0).value
                assertTrue(idxEvent, "migration 后应有 idx_event_scope")

                // 幂等
                DatabaseInitializer.initializeDatabase(driver)
                DatabaseInitializer.initializeDatabase(driver)
            } finally {
                driver.getConnection().close()
            }
        } finally {
            java.nio.file.Files.deleteIfExists(file)
        }
    }
}