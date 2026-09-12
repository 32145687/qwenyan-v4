package com.qianyan.storage

import com.qianyan.model.DraftId
import com.qianyan.model.NovelId
import com.qianyan.model.ProjectId
import com.qianyan.model.ProjectSource
import com.qianyan.model.ProjectStatus
import com.qianyan.model.VariantId
import com.qianyan.model.VariantScope
import com.qianyan.model.core.Novel
import com.qianyan.model.workflow.HumanDecision
import com.qianyan.model.workflow.HumanGateStatus
import com.qianyan.model.workflow.Workflow
import com.qianyan.model.workflow.WorkflowContinuation
import com.qianyan.model.workflow.WorkflowContinuationId
import com.qianyan.model.workflow.WorkflowHumanGate
import com.qianyan.model.workflow.WorkflowHumanGateId
import com.qianyan.model.workflow.WorkflowId
import com.qianyan.model.workflow.WorkflowKind
import com.qianyan.model.workflow.WorkflowStatus
import com.qianyan.model.workflow.WorkflowStep
import com.qianyan.model.workflow.WorkflowStepAttempt
import com.qianyan.model.workflow.WorkflowStepAttemptId
import com.qianyan.model.workflow.WorkflowStepId
import com.qianyan.model.workflow.WorkflowStepPhase
import com.qianyan.model.workflow.WorkflowStepStatus
import com.qianyan.storage.db.QianyanDbFactory
import com.qianyan.storage.repository.SqliteNovelRepository
import com.qianyan.storage.repository.SqliteWorkflowRepository
import kotlinx.datetime.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * P12.2 · Durable Workflow 持久化基础（Schema v6）验证。
 * 全新库经 DatabaseInitializer 建出 v6 schema；验证 WorkflowRepository 的
 * Workflow/Step/Attempt/Gate/Continuation 五表 CRUD + 唯一约束 + TD2 查询。
 */
class WorkflowPersistenceTest {

    private fun newRepo(out: MutableList<app.cash.sqldelight.db.SqlDriver> = mutableListOf()): SqliteWorkflowRepository {
        val handle = QianyanDbFactory.open() // 默认内存库（JdbcSqliteDriver.IN_MEMORY）
        out += handle.driver
        return SqliteWorkflowRepository(handle.db)
    }

    /* Workflow CRUD + 五表可建可读（含 Gate/Continuation 唯一约束） */
    @Test
    fun `workflow tables persist and round-trip`() {
        val repo = newRepo()
        val novelId = NovelId("n1")
        val wfId = WorkflowId("W1")
        val now = Clock.System.now()

        val wf = Workflow(
            workflowId = wfId, novelId = novelId, variantId = VariantId("v1"), kind = WorkflowKind.WRITE_NOVEL,
            status = WorkflowStatus.CREATED, activeChapterId = null, createdAt = now, updatedAt = now,
        )
        repo.createWorkflow(wf)
        val got = repo.getWorkflow(wfId)!!
        assertEquals(wfId, got.workflowId)
        assertEquals(WorkflowStatus.CREATED, got.status)

        // Step + attempt + gate + continuation
        val stepId = WorkflowStepId("S1")
        repo.createStep(
            WorkflowStep(
                stepId = stepId, workflowId = wfId, chapterId = com.qianyan.model.ChapterId("C1"),
                phase = WorkflowStepPhase.WRITING, logicalStepKey = "W1:C1:WRITING",
                status = WorkflowStepStatus.PENDING, createdAt = now,
            ),
        )
        assertEquals(stepId, repo.getStepByKey(wfId, "W1:C1:WRITING")?.stepId)

        repo.createAttempt(
            WorkflowStepAttempt(
                attemptId = WorkflowStepAttemptId("A1"), stepId = stepId, attemptNo = 1,
                status = com.qianyan.model.workflow.WorkflowAttemptStatus.RUNNING, startedAt = now,
            ),
        )
        assertEquals(1, repo.listAttempts(stepId).size)

        val gate = WorkflowHumanGate(
            gateId = WorkflowHumanGateId("G1"), workflowId = wfId, stepId = stepId,
            draftId = DraftId("D1"), gateKey = "W1:C1:WRITING:D1",
            status = HumanGateStatus.PENDING, decision = HumanDecision.PENDING, createdAt = now,
        )
        repo.createGate(gate)
        assertEquals(HumanDecision.PENDING, repo.getGateByKey("W1:C1:WRITING:D1")!!.decision)

        repo.createContinuation(
            WorkflowContinuation(
                continuationId = WorkflowContinuationId("K1"), workflowId = wfId,
                sourceDraftId = DraftId("D1"), targetChapterId = com.qianyan.model.ChapterId("C2"), createdAt = now,
            ),
        )
        assertNotNull(repo.getContinuation(DraftId("D1"), com.qianyan.model.ChapterId("C2")))
    }

    /* Scope 隔离：不同 Novel 的 Workflow 互不可见（按 id 读取隔离）。 */
    @Test
    fun `workflow scope isolation by id`() {
        val repo = newRepo()
        val now = Clock.System.now()
        repo.createWorkflow(
            Workflow(WorkflowId("WA"), NovelId("NA"), null, WorkflowKind.WRITE_NOVEL, status = WorkflowStatus.CREATED, createdAt = now, updatedAt = now),
        )
        repo.createWorkflow(
            Workflow(WorkflowId("WB"), NovelId("NB"), null, WorkflowKind.WRITE_NOVEL, status = WorkflowStatus.CREATED, createdAt = now, updatedAt = now),
        )
        assertNotNull(repo.getWorkflow(WorkflowId("WA")))
        assertEquals(NovelId("NB"), repo.getWorkflow(WorkflowId("WB"))!!.novelId)
        assertNull(repo.getWorkflow(WorkflowId("ghost")))
    }

    /* TD2：DraftRepository 章节作用域查询。 */
    @Test
    fun `draft chapter scoped queries`() {
        val handle = QianyanDbFactory.open()
        val db = handle.db
        val now = Clock.System.now()
        // P12.4-M01 外键：ChapterDraft.novel_id → Novel，须先建父 Novel。
        SqliteNovelRepository(db).createOriginal(
            Novel(
                novelId = NovelId("n1"), projectId = ProjectId("proj-n1"), title = "T",
                source = ProjectSource.ORIGINAL_NOVEL, scope = VariantScope.ORIGINAL,
                status = ProjectStatus.DRAFT, createdAt = now, updatedAt = now,
            ),
        )
        val repo = com.qianyan.storage.repository.SqliteDraftRepository(db)
        val novelId = NovelId("n1")
        val ch1 = com.qianyan.model.ChapterId("c1")
        repo.save(
            com.qianyan.model.writing.Draft(
                draftId = com.qianyan.model.DraftId("d1"), novelId = novelId, chapterId = ch1,
                content = "A", status = com.qianyan.model.writing.DraftStatus.WRITTEN,
                createdAt = now, updatedAt = now,
            ),
        )
        repo.save(
            com.qianyan.model.writing.Draft(
                draftId = com.qianyan.model.DraftId("d2"), novelId = novelId, chapterId = ch1,
                content = "B", status = com.qianyan.model.writing.DraftStatus.WRITTEN,
                createdAt = now, updatedAt = now,
            ),
        )
        assertEquals(2, repo.listByChapter(ch1).size)
        assertEquals("d2", repo.latestByChapter(ch1)!!.draftId.value)
        assertEquals(0, repo.listByChapter(com.qianyan.model.ChapterId("c9")).size)
    }
}