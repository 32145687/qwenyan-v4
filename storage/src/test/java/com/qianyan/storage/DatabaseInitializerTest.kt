package com.qianyan.storage

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.qianyan.storage.db.DatabaseInitializer
import com.qianyan.storage.db.QianyanDb
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * P7.0 DatabaseInitializer 测试（storage）：
 * 驱动无关初始化入口的幂等性与守卫触发器创建。
 * 初始化逻辑与 JVM 专用 QianyanDbFactory 解耦后，Android（AndroidSqliteDriver）将复用同一入口。
 */
class DatabaseInitializerTest {

    @Test
    fun `initializeDatabase is idempotent and produces usable schema`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)

        DatabaseInitializer.initializeDatabase(driver)
        DatabaseInitializer.initializeDatabase(driver) // 幂等：重复调用不抛"表已存在"

        val db = QianyanDb(driver)
        assertTrue(db.novelQueries.getAllNovels().executeAsList().isEmpty(), "建表后 Novel 查询应可用且为空")
    }

    @Test
    fun `initializeDatabase creates the three guard triggers`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        DatabaseInitializer.initializeDatabase(driver)

        val triggerCount = driver.executeQuery(
            null,
            "SELECT count(*) FROM sqlite_master WHERE type='trigger'",
            { cursor ->
                cursor.next()
                QueryResult.Value(cursor.getLong(0) ?: 0L)
            },
            0,
        ).value

        assertEquals(3L, triggerCount, "应创建 2 个 Original 写保护触发器 + 1 个 Variant 基座守卫触发器")
    }

    @Test
    fun `v22 database migrates to latest and relaxes WorkflowHumanGate workflow_id to nullable`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        DatabaseInitializer.initializeDatabase(driver)
        simulateV22Gate(driver)
        assertEquals(22L, userVersion(driver), "前置：模拟库应为 v22")
        assertTrue(columnIsNotNull(driver, "WorkflowHumanGate", "workflow_id"), "前置：v22 的 workflow_id 应为 NOT NULL")
        assertTrue(!columnExists(driver, "CommitHistory", "gate_id"), "前置：v22 的 CommitHistory 应无 gate_id")

        DatabaseInitializer.initializeDatabase(driver) // 触发 v22 → v25（22.sqm + 23.sqm + 24.sqm）

        assertEquals(27L, userVersion(driver), "migration 后 user_version 应为 27")
        assertEquals(QianyanDb.Schema.version, userVersion(driver), "user_version 应与 Schema.version 一致")
        assertTrue(!columnIsNotNull(driver, "WorkflowHumanGate", "workflow_id"), "migration 后 workflow_id 应放宽为可空")
        assertTrue(columnExists(driver, "CommitHistory", "gate_id"), "migration 后 CommitHistory 应含 gate_id（P1-02j）")
        assertEquals(1L, countGates(driver), "既有 Gate 行必须原样保留（重建表不得丢数据）")
        assertEquals("wf-legacy", gateWorkflowId(driver, "gate-legacy"), "既有 Gate 的 workflow_id 必须原样保留")

        // 放宽后：可插入 workflow_id = NULL 的独立 Human Gate（P1-01 的语义目标）
        driver.execute(
            null,
            "INSERT INTO WorkflowHumanGate (gate_id, workflow_id, gate_key, status, decision, created_at) " +
                "VALUES ('gate-standalone', NULL, 'gate-standalone-key', 'PENDING', 'APPROVE', 1)",
            0,
        )
        assertEquals(2L, countGates(driver), "放宽后应可插入 workflow_id = NULL 的独立 Gate")
    }

    @Test
    fun `already migrated database does not re-run the migration`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        DatabaseInitializer.initializeDatabase(driver)
        assertEquals(QianyanDb.Schema.version, userVersion(driver), "全新库版本应为 Schema.version（25）")

        simulateV22Gate(driver)
        DatabaseInitializer.initializeDatabase(driver)
        assertEquals(27L, userVersion(driver))

        // 已 v25：重复初始化不得重新执行已有迁移，版本与数据保持稳定
        DatabaseInitializer.initializeDatabase(driver)
        DatabaseInitializer.initializeDatabase(driver)
        assertEquals(27L, userVersion(driver), "已 v25 不得重复 migration")
        assertEquals(1L, countGates(driver), "重复初始化不得丢数据或重复行")
        assertTrue(!columnIsNotNull(driver, "WorkflowHumanGate", "workflow_id"), "重复初始化后仍应为可空")
    }

    /* ---------------- helpers ---------------- */

    /**
     * 把当前库**真实回退**为 v22 形态（用于验证 v22 → 最新的批量迁移）：
     *  - `WorkflowHumanGate.workflow_id` = NOT NULL（v23 前的形态）+ 一条历史 Gate 行；
     *  - `CommitHistory` **无 gate_id**（真 v22 库由 20.sqm 建出，不含该列）；
     *  - `PRAGMA user_version = 22`。
     */
    private fun simulateV22Gate(driver: JdbcSqliteDriver) {
        // 父行：v22 的 gate.workflow_id → Workflow(workflow_id) 外键必须成立
        driver.execute(null, "INSERT INTO Workflow (workflow_id, novel_id, kind, status, created_at, updated_at) " +
            "VALUES ('wf-legacy', 'novel-legacy', 'CONTINUE', 'RUNNING', 1, 1)", 0)
        driver.execute(null, "CREATE TABLE WorkflowHumanGate_v22_sim (" +
            "gate_id TEXT NOT NULL PRIMARY KEY, workflow_id TEXT NOT NULL, step_id TEXT, draft_id TEXT, " +
            "gate_key TEXT NOT NULL UNIQUE, status TEXT NOT NULL, decision TEXT NOT NULL, created_at INTEGER NOT NULL, " +
            "resolved_at INTEGER, resolved_by TEXT, FOREIGN KEY (workflow_id) REFERENCES Workflow(workflow_id))", 0)
        driver.execute(null, "INSERT INTO WorkflowHumanGate_v22_sim " +
            "(gate_id, workflow_id, step_id, draft_id, gate_key, status, decision, created_at, resolved_at, resolved_by) " +
            "SELECT gate_id, workflow_id, step_id, draft_id, gate_key, status, decision, created_at, resolved_at, resolved_by " +
            "FROM WorkflowHumanGate", 0)
        driver.execute(null, "DROP TABLE WorkflowHumanGate", 0)
        driver.execute(null, "ALTER TABLE WorkflowHumanGate_v22_sim RENAME TO WorkflowHumanGate", 0)
        driver.execute(null, "INSERT INTO WorkflowHumanGate " +
            "(gate_id, workflow_id, step_id, draft_id, gate_key, status, decision, created_at) " +
            "VALUES ('gate-legacy', 'wf-legacy', NULL, NULL, 'gate-legacy-key', 'PENDING', 'APPROVE', 1)", 0)
        // CommitHistory 回退为 v22 形态（无 gate_id）——受 23.sqm 的 ALTER 影响，模拟必须真实摘除该列。
        driver.execute(null, "CREATE TABLE CommitHistory_v22_sim (" +
            "history_id TEXT NOT NULL PRIMARY KEY, commit_id TEXT NOT NULL, project_id TEXT NOT NULL, operation TEXT NOT NULL, " +
            "artifact_id TEXT, novel_id TEXT NOT NULL, chapter_id TEXT NOT NULL, variant_id TEXT, previous_draft_id TEXT, " +
            "resulting_draft_id TEXT NOT NULL, previous_content TEXT NOT NULL, resulting_content TEXT NOT NULL, " +
            "summary TEXT NOT NULL, reverted_history_id TEXT, created_at INTEGER NOT NULL)", 0)
        driver.execute(null, "INSERT INTO CommitHistory_v22_sim " +
            "(history_id, commit_id, project_id, operation, artifact_id, novel_id, chapter_id, variant_id, previous_draft_id, " +
            "resulting_draft_id, previous_content, resulting_content, summary, reverted_history_id, created_at) " +
            "SELECT history_id, commit_id, project_id, operation, artifact_id, novel_id, chapter_id, variant_id, previous_draft_id, " +
            "resulting_draft_id, previous_content, resulting_content, summary, reverted_history_id, created_at FROM CommitHistory", 0)
        driver.execute(null, "DROP TABLE CommitHistory", 0)
        driver.execute(null, "ALTER TABLE CommitHistory_v22_sim RENAME TO CommitHistory", 0)
        driver.execute(null, "CREATE UNIQUE INDEX idx_commit_history_artifact ON CommitHistory(artifact_id)", 0)
        driver.execute(null, "CREATE INDEX idx_commit_history_project ON CommitHistory(project_id, created_at)", 0)
        // P1-02j.2：回退到 v24 之前必须真实摘除 round_no（24.sqm 的 ADD COLUMN 不幂等）
        driver.execute(null, "ALTER TABLE WorkflowStep DROP COLUMN round_no", 0)
        // P2 v27（26.sqm）：Character/CharacterState/Event 新列不幂等，降级前必须真实摘除
        driver.dropWorldModelV27Columns()
        driver.execute(null, "PRAGMA user_version = 22", 0)
    }

    private fun userVersion(driver: JdbcSqliteDriver): Long = driver.executeQuery(
        null, "PRAGMA user_version",
        { cursor -> cursor.next(); QueryResult.Value(cursor.getLong(0) ?: 0L) }, 0,
    ).value

    private fun columnIsNotNull(driver: JdbcSqliteDriver, table: String, column: String): Boolean = driver.executeQuery(
        null, "SELECT 1 FROM pragma_table_info('$table') WHERE name='$column' AND \"notnull\" = 1 LIMIT 1",
        { cursor -> QueryResult.Value(cursor.next().value) }, 0,
    ).value

    private fun columnExists(driver: JdbcSqliteDriver, table: String, column: String): Boolean = driver.executeQuery(
        null, "SELECT 1 FROM pragma_table_info('$table') WHERE name='$column' LIMIT 1",
        { cursor -> QueryResult.Value(cursor.next().value) }, 0,
    ).value

    private fun countGates(driver: JdbcSqliteDriver): Long = driver.executeQuery(
        null, "SELECT count(*) FROM WorkflowHumanGate",
        { cursor -> cursor.next(); QueryResult.Value(cursor.getLong(0) ?: 0L) }, 0,
    ).value

    private fun gateWorkflowId(driver: JdbcSqliteDriver, gateId: String): String? = driver.executeQuery(
        null, "SELECT workflow_id FROM WorkflowHumanGate WHERE gate_id = '$gateId'",
        { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getString(0) else null) }, 0,
    ).value
}
