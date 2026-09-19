package com.qianyan.storage

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.qianyan.storage.db.DatabaseInitializer
import com.qianyan.storage.db.QianyanDbFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * P13 LCL-C · v7 → v8 migration 测试。
 * 构建真实 v7 schema（Foreshadow **无 state 列**、有 resolved；含 branch 判定的 NarrativeState 等表），
 * 初始化后：state 列新增；旧 `resolved=1→RESOLVED`、`resolved=0→PLANTED`；updated_at 回填 created_at；幂等。
 */
class ForeshadowStateMigrationTest {

    /** 真实 v7 schema 的最小足集（含 when 分支判定所需表 + 待加列的 Foreshadow）。 */
    private val V7_MINIMAL_DDL: List<String> = listOf(
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
        // v7 的 Foreshadow：无 state / updated_at / last_transition_reason / payoff_chapter_id 列
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
        """CREATE TABLE Event (event_id TEXT NOT NULL PRIMARY KEY, novel_id TEXT NOT NULL, variant_id TEXT, scope TEXT NOT NULL, name TEXT NOT NULL, description TEXT NOT NULL DEFAULT '', type TEXT NOT NULL, importance INTEGER NOT NULL DEFAULT 5, when_json TEXT, who TEXT NOT NULL DEFAULT '[]', chapter_id TEXT, status TEXT NOT NULL, created_at INTEGER NOT NULL, FOREIGN KEY (novel_id) REFERENCES Novel(novel_id))""",
        """CREATE TABLE TimelineEntry (timeline_id TEXT NOT NULL PRIMARY KEY, novel_id TEXT NOT NULL, variant_id TEXT, scope TEXT NOT NULL, position TEXT NOT NULL, event_id TEXT, description TEXT NOT NULL DEFAULT '', chapter_id TEXT, FOREIGN KEY (novel_id) REFERENCES Novel(novel_id))""",
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
        // v7 已含 LCL-A NarrativeState / NarrativeDelta（使分支跳过 v6→v7）
        """
        CREATE TABLE NarrativeState (
            narrative_state_id   TEXT NOT NULL PRIMARY KEY,
            novel_id             TEXT NOT NULL,
            variant_id           TEXT,
            scope                TEXT NOT NULL,
            version              INTEGER NOT NULL DEFAULT 0,
            main_goal            TEXT NOT NULL DEFAULT '',
            current_conflict     TEXT,
            open_threads         TEXT NOT NULL DEFAULT '[]',
            character_stages     TEXT NOT NULL DEFAULT '{}',
            relationship_deltas  TEXT NOT NULL DEFAULT '[]',
            foreshadow_pressures TEXT NOT NULL DEFAULT '[]',
            current_pacing       TEXT,
            last_chapter_delta   TEXT NOT NULL DEFAULT '',
            updated_at           INTEGER NOT NULL,
            FOREIGN KEY (novel_id) REFERENCES Novel(novel_id)
        )
        """,
        """
        CREATE TABLE NarrativeDelta (
            delta_id             TEXT NOT NULL PRIMARY KEY,
            narrative_state_id   TEXT NOT NULL,
            novel_id             TEXT NOT NULL,
            variant_id           TEXT,
            scope                TEXT NOT NULL,
            chapter_id           TEXT,
            main_goal            TEXT,
            current_conflict     TEXT,
            open_threads         TEXT NOT NULL DEFAULT '[]',
            character_stages     TEXT NOT NULL DEFAULT '{}',
            relationship_deltas  TEXT NOT NULL DEFAULT '[]',
            foreshadow_pressures TEXT NOT NULL DEFAULT '[]',
            current_pacing       TEXT,
            summary              TEXT NOT NULL DEFAULT '',
            created_at           INTEGER NOT NULL,
            FOREIGN KEY (novel_id) REFERENCES Novel(novel_id)
        )
        """,
    )

    private fun JdbcSqliteDriver.exec(sql: String) {
        execute(null, sql, 0)
    }

    private fun columnExists(driver: JdbcSqliteDriver, table: String, column: String): Boolean =
        driver.executeQuery(
            null,
            "SELECT 1 FROM pragma_table_info('$table') WHERE name='$column' LIMIT 1",
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

    private fun queryState(driver: JdbcSqliteDriver, id: String): String =
        driver.executeQuery(
            null,
            "SELECT state FROM Foreshadow WHERE foreshadow_id='$id'",
            { cursor -> cursor.next(); QueryResult.Value(cursor.getString(0) ?: "") },
            0,
        ).value

    @Test
    fun `v7 to v8 migration adds state column and backfills from resolved`() {
        val file = java.nio.file.Files.createTempFile("qianyan_foreshadow_v8_test", ".db").toAbsolutePath()
        val url = "jdbc:sqlite:$file"
        try {
            val v7 = JdbcSqliteDriver(url)
            V7_MINIMAL_DDL.forEach { v7.exec(it) }
            v7.exec("INSERT INTO Novel VALUES('n1','p','T','ORIGINAL_NOVEL','[]','','ORIGINAL','DRAFT',1,1)")
            v7.exec("INSERT INTO Foreshadow VALUES('f-r','n1',NULL,'ORIGINAL',NULL,'paid',1,111)")
            v7.exec("INSERT INTO Foreshadow VALUES('f-p','n1',NULL,'ORIGINAL',NULL,'planted',0,222)")
            v7.exec("PRAGMA user_version = 7")
            assertTrue(!columnExists(v7, "Foreshadow", "state"), "v7 库 Foreshadow 不应有 state 列")
            v7.getConnection().close()

            val h = QianyanDbFactory.open(url)
            val driver = h.driver as JdbcSqliteDriver
            try {
                DatabaseInitializer.initializeDatabase(driver)

                assertTrue(columnExists(driver, "Foreshadow", "state"), "migration 后应有 state 列")
                assertTrue(columnExists(driver, "Foreshadow", "payoff_chapter_id"), "migration 后应有 payoff_chapter_id 列")
                assertEquals(14L, userVersion(driver), "user_version 应为最新版本 13（P18-B Schema v14）")
                assertEquals("RESOLVED", queryState(driver, "f-r"), "resolved=1 → RESOLVED")
                assertEquals("PLANTED", queryState(driver, "f-p"), "resolved=0 → PLANTED")

                // 幂等：重复初始化不报错
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