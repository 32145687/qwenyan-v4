package com.qianyan.storage

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.qianyan.model.ChapterId
import com.qianyan.model.NovelId
import com.qianyan.model.workflow.Workflow
import com.qianyan.model.workflow.WorkflowId
import com.qianyan.model.workflow.WorkflowKind
import com.qianyan.model.workflow.WorkflowStatus
import com.qianyan.model.workflow.WorkflowStep
import com.qianyan.model.workflow.WorkflowStepId
import com.qianyan.model.workflow.WorkflowStepPhase
import com.qianyan.model.workflow.WorkflowStepStatus
import com.qianyan.storage.db.DatabaseInitializer
import com.qianyan.storage.db.QianyanDb
import com.qianyan.storage.db.QianyanDbFactory
import com.qianyan.storage.repository.SqliteWorkflowRepository
import kotlinx.datetime.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P1-02j.2 · Schema v25（24.sqm：v24 → v25）迁移测试。
 *
 * WorkflowStep 新增**可空** `round_no` 列（创作轮次编号）。
 * 验证：全新库含该列；roundNo 可写入/读回；v24 库升级成功；历史旧行升级后 `round_no = NULL`（不回填）；重复初始化幂等。
 */
class WorkflowStepRoundNoMigrationTest {

    @Test
    fun `fresh database stores and reads back round_no`() {
        val file = java.nio.file.Files.createTempFile("qianyan_roundno_fresh", ".db").toAbsolutePath()
        try {
            val h = QianyanDbFactory.open("jdbc:sqlite:$file")
            val driver = h.driver as JdbcSqliteDriver
            try {
                assertTrue(columnExists(driver, "WorkflowStep", "round_no"), "全新库应含 round_no")
                assertEquals(28L, userVersion(driver), "全新库版本应为 28")

                val repo = SqliteWorkflowRepository(QianyanDb(driver))
                val wfId = WorkflowId("wf-round")
                repo.createWorkflow(
                    Workflow(
                        workflowId = wfId, novelId = NovelId("n-round"), kind = WorkflowKind.WRITE_NOVEL,
                        status = WorkflowStatus.RUNNING, createdAt = Clock.System.now(), updatedAt = Clock.System.now(),
                    ),
                )
                repo.createStep(step(wfId, "s-r0", WorkflowStepPhase.WRITING, "k-r0", roundNo = 0))
                repo.createStep(step(wfId, "s-r3", WorkflowStepPhase.WRITING, "k-r3", roundNo = 3))

                assertEquals(0, repo.getStep(WorkflowStepId("s-r0"))?.roundNo, "roundNo=0 应原样读回")
                assertEquals(3, repo.getStep(WorkflowStepId("s-r3"))?.roundNo, "roundNo=3 应原样读回")
            } finally {
                driver.getConnection().close()
            }
        } finally {
            java.nio.file.Files.deleteIfExists(file)
        }
    }

    @Test
    fun `v24 database adds round_no and keeps legacy rows as NULL`() {
        val file = java.nio.file.Files.createTempFile("qianyan_roundno_v24", ".db").toAbsolutePath()
        val url = "jdbc:sqlite:$file"
        try {
            // 1) 建到 v25，写入一条历史 Step 行
            val first = QianyanDbFactory.open(url)
            val d1 = first.driver as JdbcSqliteDriver
            val repo1 = SqliteWorkflowRepository(QianyanDb(d1))
            val wfId = WorkflowId("wf-legacy")
            repo1.createWorkflow(
                Workflow(
                    workflowId = wfId, novelId = NovelId("n-legacy"), kind = WorkflowKind.WRITE_NOVEL,
                    status = WorkflowStatus.RUNNING, createdAt = Clock.System.now(), updatedAt = Clock.System.now(),
                ),
            )
            repo1.createStep(step(wfId, "s-legacy", WorkflowStepPhase.WRITING, "k-legacy", roundNo = 0))

            // 2) 回退为 v24 形态：摘除 round_no 并保留行；同时摘除 v26 的 CommitHistory 格式列（25.sqm 的 ADD COLUMN 不幂等）
            d1.execute(null, "ALTER TABLE WorkflowStep DROP COLUMN round_no", 0)
            d1.execute(null, "ALTER TABLE CommitHistory DROP COLUMN previous_format", 0)
            d1.execute(null, "ALTER TABLE CommitHistory DROP COLUMN resulting_format", 0)
            // P2 v27（26.sqm）：Character/CharacterState/Event 新列不幂等，降级前必须真实摘除
            d1.dropWorldModelV27Columns()
            // P3 v28（27.sqm）：CandidateFact 由迁移建出，降级前必须真实摘除（CREATE TABLE 不幂等）
            d1.execute(null, "DROP TABLE IF EXISTS CandidateFact", 0)
            d1.execute(null, "PRAGMA user_version = 24", 0)
            assertEquals(24L, userVersion(d1), "前置：模拟库应为 v24")
            assertTrue(!columnExists(d1, "WorkflowStep", "round_no"), "前置：v24 形态无 round_no")
            assertTrue(!columnExists(d1, "CommitHistory", "previous_format"), "前置：v24 形态无 previous_format")
            d1.getConnection().close()

            // 3) 经同一入口重开 → 24.sqm 加列
            val second = QianyanDbFactory.open(url)
            val d2 = second.driver as JdbcSqliteDriver
            try {
                assertTrue(columnExists(d2, "WorkflowStep", "round_no"), "迁移后应含 round_no")
                assertTrue(columnExists(d2, "CommitHistory", "previous_format"), "迁移后应含 previous_format")
                assertTrue(columnExists(d2, "CommitHistory", "resulting_format"), "迁移后应含 resulting_format")
                assertEquals(28L, userVersion(d2), "旧 v24 库应迁移到最新版本")

                // 历史旧行保留，且 round_no = NULL（不回填、不猜测）
                val row = QianyanDb(d2).workflowQueries.getStepById("s-legacy").executeAsOne()
                assertEquals("k-legacy", row.logical_step_key, "历史行内容必须原样保留")
                assertNull(row.round_no, "历史旧行 round_no 必须为 NULL")

                // 4) 重复初始化幂等
                DatabaseInitializer.initializeDatabase(d2)
                DatabaseInitializer.initializeDatabase(d2)
                assertEquals(28L, userVersion(d2))
                assertNull(QianyanDb(d2).workflowQueries.getStepById("s-legacy").executeAsOne().round_no)
            } finally {
                d2.getConnection().close()
            }
        } finally {
            java.nio.file.Files.deleteIfExists(file)
        }
    }

    /* ---------------- helpers ---------------- */

    private fun step(wfId: WorkflowId, id: String, phase: WorkflowStepPhase, key: String, roundNo: Int?) =
        WorkflowStep(
            stepId = WorkflowStepId(id), workflowId = wfId, chapterId = ChapterId("ch-x"),
            phase = phase, logicalStepKey = key, status = WorkflowStepStatus.PENDING,
            roundNo = roundNo, createdAt = Clock.System.now(),
        )

    private fun columnExists(driver: JdbcSqliteDriver, table: String, column: String): Boolean =
        driver.executeQuery(
            null, "SELECT 1 FROM pragma_table_info('$table') WHERE name='$column' LIMIT 1",
            { cursor -> QueryResult.Value(cursor.next().value) }, 0,
        ).value

    private fun userVersion(driver: JdbcSqliteDriver): Long =
        driver.executeQuery(
            null, "PRAGMA user_version",
            { cursor -> cursor.next(); QueryResult.Value(cursor.getLong(0) ?: 0L) }, 0,
        ).value
}
