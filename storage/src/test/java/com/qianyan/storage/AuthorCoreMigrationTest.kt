package com.qianyan.storage

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.qianyan.storage.db.DatabaseInitializer
import com.qianyan.storage.db.QianyanDbFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * P17 · v11 → v12 migration 测试（Author Core 独立表族）。
 * 构建真实 v11 schema（上游全量表 + StoryFoundation/FoundationOverride + P16 Author 三表；无 AuthorCore；user_version=11），
 * 写入代表性 v11 数据，初始化后验证：AuthorCore 表族已建 / user_version=12 / 旧数据保留 / 幂等。
 */
class AuthorCoreMigrationTest {

    private val V11_MINIMAL_DDL: List<String> = listOf(
        """CREATE TABLE "Novel" (novel_id TEXT NOT NULL PRIMARY KEY, project_id TEXT NOT NULL, title TEXT NOT NULL, source TEXT NOT NULL, genre TEXT NOT NULL DEFAULT '[]', synopsis TEXT NOT NULL DEFAULT '', scope TEXT NOT NULL, status TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)""",
        """CREATE TABLE NovelVariant (variant_id TEXT NOT NULL PRIMARY KEY, novel_id TEXT NOT NULL, base_novel_id TEXT NOT NULL, project_id TEXT NOT NULL, name TEXT NOT NULL, status TEXT NOT NULL, blueprint TEXT, scope_spec TEXT, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, FOREIGN KEY (novel_id) REFERENCES "Novel"(novel_id))""",
        """CREATE TABLE Task (task_id TEXT NOT NULL PRIMARY KEY, type TEXT NOT NULL, status TEXT NOT NULL, progress REAL NOT NULL DEFAULT 0, revision_count INTEGER NOT NULL DEFAULT 0, error TEXT, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)""",
        """CREATE TABLE ChapterDraft (draft_id TEXT NOT NULL PRIMARY KEY, novel_id TEXT NOT NULL, variant_id TEXT, scope TEXT NOT NULL, chapter_id TEXT, chapter_plan_id TEXT, content TEXT NOT NULL, status TEXT NOT NULL, source_model TEXT NOT NULL DEFAULT '', created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)""",
        """CREATE TABLE Chapter (chapter_id TEXT NOT NULL PRIMARY KEY, novel_id TEXT NOT NULL, variant_id TEXT, scope TEXT NOT NULL, order_no INTEGER NOT NULL, title TEXT NOT NULL DEFAULT '', status TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, FOREIGN KEY (novel_id) REFERENCES "Novel"(novel_id))""",
        """CREATE TABLE Foreshadow (foreshadow_id TEXT NOT NULL PRIMARY KEY, novel_id TEXT NOT NULL, variant_id TEXT, scope TEXT NOT NULL, chapter_id TEXT, content TEXT NOT NULL, resolved INTEGER NOT NULL DEFAULT 0, state TEXT NOT NULL DEFAULT 'PLANTED', updated_at INTEGER NOT NULL DEFAULT 0, last_transition_reason TEXT, payoff_chapter_id TEXT, created_at INTEGER NOT NULL, FOREIGN KEY (novel_id) REFERENCES "Novel"(novel_id))""",
        """CREATE TABLE Workflow (workflow_id TEXT NOT NULL PRIMARY KEY, novel_id TEXT NOT NULL, variant_id TEXT, kind TEXT NOT NULL, definition_version TEXT NOT NULL DEFAULT 'P12.2-v1', status TEXT NOT NULL, active_chapter_id TEXT, current_step_id TEXT, pending_gate_id TEXT, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)""",
        """CREATE TABLE NarrativeState (narrative_state_id TEXT NOT NULL PRIMARY KEY, novel_id TEXT NOT NULL, variant_id TEXT, scope TEXT NOT NULL, version INTEGER NOT NULL DEFAULT 0, main_goal TEXT NOT NULL DEFAULT '', current_conflict TEXT, open_threads TEXT NOT NULL DEFAULT '[]', character_stages TEXT NOT NULL DEFAULT '{}', relationship_deltas TEXT NOT NULL DEFAULT '[]', foreshadow_pressures TEXT NOT NULL DEFAULT '[]', current_pacing TEXT, last_chapter_delta TEXT NOT NULL DEFAULT '', updated_at INTEGER NOT NULL, FOREIGN KEY (novel_id) REFERENCES "Novel"(novel_id))""",
        """CREATE TABLE Reveal (reveal_id TEXT NOT NULL PRIMARY KEY, novel_id TEXT NOT NULL, variant_id TEXT, scope TEXT NOT NULL, chapter_id TEXT, information_id TEXT NOT NULL, occurred_at INTEGER NOT NULL, reason TEXT, created_at INTEGER NOT NULL, FOREIGN KEY (novel_id) REFERENCES "Novel"(novel_id))""",
        """CREATE TABLE StoryFoundation (novel_id TEXT NOT NULL PRIMARY KEY, base_novel_id TEXT NOT NULL, scope TEXT NOT NULL, version INTEGER NOT NULL DEFAULT 0, genre TEXT NOT NULL DEFAULT '[]', direction TEXT NOT NULL DEFAULT '{}', audience TEXT NOT NULL DEFAULT '{}', policy TEXT NOT NULL DEFAULT '{}', created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, FOREIGN KEY (novel_id) REFERENCES "Novel"(novel_id))""",
        """CREATE TABLE AuthorProfile (profile_id TEXT NOT NULL PRIMARY KEY, display_name TEXT NOT NULL DEFAULT '', created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)""",
        """CREATE TABLE AuthorPreference (preference_id TEXT NOT NULL PRIMARY KEY, scope TEXT NOT NULL, novel_id TEXT, dimension TEXT NOT NULL, statement TEXT NOT NULL DEFAULT '', origin TEXT NOT NULL, confidence REAL NOT NULL, confirmed INTEGER NOT NULL DEFAULT 0, revocable INTEGER NOT NULL DEFAULT 0, paused INTEGER NOT NULL DEFAULT 0, obtained_at INTEGER NOT NULL, expiry INTEGER, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)""",
        """CREATE TABLE AuthorEvidence (evidence_id TEXT NOT NULL PRIMARY KEY, novel_id TEXT NOT NULL, type TEXT NOT NULL, detail TEXT NOT NULL DEFAULT '', source TEXT NOT NULL DEFAULT '', observed_at INTEGER NOT NULL)""",
    )

    private fun JdbcSqliteDriver.exec(sql: String) { execute(null, sql, 0) }

    private fun tableExists(driver: JdbcSqliteDriver, table: String): Boolean =
        driver.executeQuery(null, "SELECT 1 FROM sqlite_master WHERE type='table' AND name='$table' LIMIT 1",
            { cursor -> QueryResult.Value(cursor.next().value) }, 0).value

    private fun userVersion(driver: JdbcSqliteDriver): Long =
        driver.executeQuery(null, "PRAGMA user_version",
            { cursor -> cursor.next(); QueryResult.Value(cursor.getLong(0) ?: 0L) }, 0).value

    private fun scalar(driver: JdbcSqliteDriver, sql: String): String? =
        driver.executeQuery(null, sql,
            { cursor -> cursor.next(); QueryResult.Value(cursor.getString(0)) }, 0).value

    @Test
    fun `v11 to v12 migration adds author core tables and preserves data`() {
        val file = java.nio.file.Files.createTempFile("qianyan_core_v12_test", ".db").toAbsolutePath()
        val url = "jdbc:sqlite:$file"
        try {
            val v11 = JdbcSqliteDriver(url)
            V11_MINIMAL_DDL.forEach { v11.exec(it) }
            v11.exec("PRAGMA user_version = 11")
            v11.exec(
                "INSERT INTO Novel(novel_id, project_id, title, source, genre, synopsis, scope, status, created_at, updated_at) " +
                    "VALUES ('n-legacy', 'p-legacy', '旧书', 'ORIGINAL_NOVEL', '[\"romance\"]', '', 'ORIGINAL', 'DRAFT', 1730000000000, 1730000000000)",
            )
            v11.exec(
                "INSERT INTO AuthorPreference(preference_id, scope, novel_id, dimension, statement, origin, confidence, confirmed, revocable, paused, obtained_at, created_at, updated_at) " +
                    "VALUES ('p-legacy', 'GLOBAL', NULL, 'CONFLICT', '偏好调查', 'EXPLICIT', 0.9, 0, 0, 0, 1730000001000, 1730000001000, 1730000001000)",
            )
            assertFalse(tableExists(v11, "AuthorCore"), "v11 库不应有 AuthorCore")
            v11.getConnection().close()

            val h = QianyanDbFactory.open(url)
            val driver = h.driver as JdbcSqliteDriver
            try {
                listOf("AuthorCore", "AuthorCorePattern", "AuthorCoreCandidate", "AuthorCoreEvidenceLink", "AuthorCoreLearning").forEach {
                    assertTrue(tableExists(driver, it), "migration 后应存在 $it（P17）")
                }
                assertEquals(12L, userVersion(driver), "migration 后 user_version 应为 12（P17 Schema v12）")
                assertEquals("旧书", scalar(driver, "SELECT title FROM Novel WHERE novel_id='n-legacy'"))
                assertEquals("偏好调查", scalar(driver, "SELECT statement FROM AuthorPreference WHERE preference_id='p-legacy'"))
                DatabaseInitializer.initializeDatabase(driver)
                DatabaseInitializer.initializeDatabase(driver)
                assertTrue(tableExists(driver, "AuthorCore"), "重复初始化幂等")
            } finally {
                driver.getConnection().close()
            }
        } finally {
            java.nio.file.Files.deleteIfExists(file)
        }
    }
}