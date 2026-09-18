package com.qianyan.application.usecase.author

import com.qianyan.model.NovelId
import com.qianyan.model.TaskId
import com.qianyan.model.task.Task as DomainTask
import com.qianyan.model.task.TaskStatus
import com.qianyan.model.task.TaskType
import com.qianyan.model.workflow.Workflow as DomainWorkflow
import com.qianyan.model.workflow.WorkflowHumanGate as DomainGate
import com.qianyan.model.workflow.WorkflowHumanGateId
import com.qianyan.model.workflow.WorkflowId
import com.qianyan.model.workflow.HumanDecision
import com.qianyan.model.workflow.HumanGateStatus
import com.qianyan.model.workflow.WorkflowKind
import com.qianyan.model.workflow.WorkflowStatus
import com.qianyan.storage.db.QianyanDbFactory
import com.qianyan.storage.repository.SqliteTaskRepository
import com.qianyan.storage.repository.SqliteWorkflowRepository
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.datetime.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * P16 AIL-1 · P15FoundationEvidenceSource 测试：只读把 P15 Foundation 决策信号映射为 AuthorEvidence。
 * 覆盖：ADOPT（revision 1）、REJECT、无 P15 时为空。**不修改 P15**。
 */
class P15FoundationEvidenceSourceTest {

    private val now = Clock.System.now()

    private fun source(): P15FoundationEvidenceSource {
        val db = QianyanDbFactory.open(JdbcSqliteDriver.IN_MEMORY).db
        return P15FoundationEvidenceSource(
            workflowRepository = SqliteWorkflowRepository(db),
            taskRepository = SqliteTaskRepository(db),
        )
    }

    private fun seedAdoptThenReject(): Triple<P15FoundationEvidenceSource, com.qianyan.storage.repository.WorkflowRepository, com.qianyan.storage.repository.TaskRepository> {
        val db = QianyanDbFactory.open(JdbcSqliteDriver.IN_MEMORY).db
        val wfRepo = SqliteWorkflowRepository(db)
        val taskRepo = SqliteTaskRepository(db)
        val src = P15FoundationEvidenceSource(wfRepo, taskRepo)

        // Foundation 工作流（id 约定 = foundation-<novelId>）
        val workflow = DomainWorkflow(
            workflowId = WorkflowId("foundation-n1"),
            novelId = NovelId("n1"),
            variantId = null,
            kind = WorkflowKind.WRITE_NOVEL,
            definitionVersion = "P12.2-v1",
            status = WorkflowStatus.RUNNING,
            activeChapterId = null,
            currentStepId = null,
            pendingGateId = null,
            createdAt = now,
            updatedAt = now,
        )
        wfRepo.createWorkflow(workflow)

        // Task（revisionCount=2 表示两个 Proposal Revision）+ 两个 resolve 的 Gate
        taskRepo.create(DomainTask(
            taskId = TaskId("foundation-foundation-n1"),
            type = TaskType.PLANNING,
            status = TaskStatus.COMPLETED,
            progress = 1f,
            revisionCount = 2,
            createdAt = now,
            updatedAt = now,
        ))
        wfRepo.createGate(DomainGate(
            gateId = WorkflowHumanGateId("g1"),
            workflowId = workflow.workflowId,
            gateKey = "FOUNDATION:foundation-n1:1",
            status = HumanGateStatus.RESOLVED,
            decision = HumanDecision.APPROVED,
            createdAt = now,
            resolvedAt = now,
        ))
        wfRepo.createGate(DomainGate(
            gateId = WorkflowHumanGateId("g2"),
            workflowId = workflow.workflowId,
            gateKey = "FOUNDATION:foundation-n1:2",
            status = HumanGateStatus.RESOLVED,
            decision = HumanDecision.REJECTED,
            createdAt = now,
            resolvedAt = now,
        ))
        return Triple(src, wfRepo, taskRepo)
    }

    @Test
    fun `maps approved revision1 to ADOPT and rejected to REJECT`() {
        val (src, _, _) = seedAdoptThenReject()
        val signals = src.foundationSignals(NovelId("n1"))
        val types = signals.map { it.type }.toSet()
        assertTrue(com.qianyan.model.author.AuthorEvidenceType.ADOPT in types, "revision1 APPROVED → ADOPT")
        assertTrue(com.qianyan.model.author.AuthorEvidenceType.REJECT in types, "REJECTED → REJECT")
        assertEquals("p15:foundation:gate", signals.first().source)
    }

    @Test
    fun `no foundation workflow yields empty`() {
        val src = source()
        assertTrue(src.foundationSignals(NovelId("n-none")).isEmpty())
    }
}