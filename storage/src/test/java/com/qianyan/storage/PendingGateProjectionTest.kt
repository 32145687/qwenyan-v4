package com.qianyan.storage

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.qianyan.model.ChapterId
import com.qianyan.model.ProjectId
import com.qianyan.storage.db.QianyanDbFactory
import com.qianyan.storage.repository.SqliteWorkflowRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * O-50：Project 内待确认 Gate 的持久化投影（只读查询，不改表）。
 *
 * 归属规则：`gate.workflow_id → Workflow.novel_id` 优先，`gate.draft_id → ChapterDraft.novel_id` 兜底；
 * 两者都解析不到 Project 的 Gate 不计入任何 Project。
 */
class PendingGateProjectionTest {

    private fun repo(): Pair<SqliteWorkflowRepository, JdbcSqliteDriver> {
        val handle = QianyanDbFactory.open(JdbcSqliteDriver.IN_MEMORY)
        return SqliteWorkflowRepository(handle.db) to (handle.driver as JdbcSqliteDriver)
    }

    private fun JdbcSqliteDriver.exec(sql: String) = execute(null, sql, 0)

    private fun JdbcSqliteDriver.novel(novelId: String, projectId: String) = exec(
        "INSERT INTO Novel(novel_id, project_id, title, source, genre, synopsis, scope, status, created_at, updated_at) " +
            "VALUES ('$novelId', '$projectId', '书', 'ORIGINAL_NOVEL', '[]', '', 'ORIGINAL', 'DRAFT', 1, 1)",
    )

    private fun JdbcSqliteDriver.chapter(chapterId: String, novelId: String, order: Int) = exec(
        "INSERT INTO Chapter(chapter_id, novel_id, variant_id, scope, order_no, title, status, created_at, updated_at) " +
            "VALUES ('$chapterId', '$novelId', NULL, 'ORIGINAL', $order, '章', 'PLANNED', 1, 1)",
    )

    private fun JdbcSqliteDriver.draft(draftId: String, novelId: String, chapterId: String) = exec(
        "INSERT INTO ChapterDraft(draft_id, novel_id, variant_id, scope, chapter_id, chapter_plan_id, previous_draft_id, " +
            "content, format, status, source_model, created_at, updated_at) " +
            "VALUES ('$draftId', '$novelId', NULL, 'ORIGINAL', '$chapterId', NULL, NULL, '正文', NULL, 'WRITTEN', '', 1, 1)",
    )

    private fun JdbcSqliteDriver.workflow(workflowId: String, novelId: String, chapterId: String) = exec(
        "INSERT INTO Workflow(workflow_id, novel_id, variant_id, kind, definition_version, status, active_chapter_id, " +
            "current_step_id, pending_gate_id, created_at, updated_at) " +
            "VALUES ('$workflowId', '$novelId', NULL, 'WRITE_NOVEL', 'P12.2-v1', 'WAITING_HUMAN', '$chapterId', NULL, NULL, 1, 1)",
    )

    private fun JdbcSqliteDriver.step(stepId: String, workflowId: String, chapterId: String, phase: String) = exec(
        "INSERT INTO WorkflowStep(step_id, workflow_id, chapter_id, phase, logical_step_key, status, current_attempt_no, " +
            "current_task_id, result_reference, round_no, created_at, completed_at) " +
            "VALUES ('$stepId', '$workflowId', '$chapterId', '$phase', '$stepId', 'PENDING', NULL, NULL, NULL, NULL, 1, NULL)",
    )

    private fun JdbcSqliteDriver.gate(
        gateId: String,
        workflowId: String?,
        stepId: String?,
        draftId: String?,
        status: String,
    ) = exec(
        "INSERT INTO WorkflowHumanGate(gate_id, workflow_id, step_id, draft_id, gate_key, status, decision, " +
            "created_at, resolved_at, resolved_by) " +
            "VALUES ('$gateId', ${workflowId?.let { "'$it'" } ?: "NULL"}, ${stepId?.let { "'$it'" } ?: "NULL"}, " +
            "${draftId?.let { "'$it'" } ?: "NULL"}, 'key-$gateId', '$status', 'PENDING', 1, NULL, NULL)",
    )

    @Test
    fun `count attributes by workflow and draft, excludes resolved and other projects`() {
        val (repo, driver) = repo()
        driver.novel("n1", "p1")
        driver.novel("n2", "p2")
        driver.chapter("c1", "n1", 1)
        driver.chapter("c2", "n1", 2)
        driver.chapter("c3", "n2", 1)
        driver.draft("d1", "n1", "c2")

        // ① workflow 关联（PENDING）
        driver.workflow("w1", "n1", "c1")
        driver.step("s1", "w1", "c1", "CONFIRMATION")
        driver.gate("g1", "w1", "s1", null, "PENDING")

        // ② standalone Draft 关联（PENDING）
        driver.gate("g2", null, null, "d1", "PENDING")

        // ③ RESOLVED 排除
        driver.gate("g3", "w1", "s1", null, "RESOLVED")

        // ④ 其它 Project 排除
        driver.workflow("w2", "n2", "c3")
        driver.gate("g4", "w2", null, null, "PENDING")

        assertEquals(2L, repo.pendingGateCount(ProjectId("p1")), "p1 只计 workflow + draft 两条 PENDING")
        assertEquals(1L, repo.pendingGateCount(ProjectId("p2")), "p2 只计自己的 PENDING")
        assertEquals(0L, repo.pendingGateCount(ProjectId("p3")), "无 Gate 的 Project 为 0")

        val list = repo.listPendingGates(ProjectId("p1"))
        assertEquals(2, list.size, "Inspector 明细必须与计数同源")
        assertEquals(repo.pendingGateCount(ProjectId("p1")), list.size.toLong(), "count 与 list 必须一致")
        assertTrue(list.any { it.chapterId == ChapterId("c1") && it.phase == "CONFIRMATION" }, "workflow 关联应带章节/阶段")
        assertTrue(list.any { it.chapterId == ChapterId("c2") && it.phase == null }, "standalone Draft 关联应带章节")

        driver.getConnection().close()
    }
}
