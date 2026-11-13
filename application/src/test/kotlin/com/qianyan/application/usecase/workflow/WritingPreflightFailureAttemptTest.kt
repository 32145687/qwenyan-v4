package com.qianyan.application.usecase.workflow

import com.qianyan.application.usecase.agent.FIXED_INSTANT
import com.qianyan.application.usecase.agent.NovelAgentFixture
import com.qianyan.application.usecase.agent.novelAgentFixture
import com.qianyan.application.usecase.agent.seedNovelWorld
import com.qianyan.model.ChapterId
import com.qianyan.model.workflow.AttemptErrorCategory
import com.qianyan.model.workflow.Workflow
import com.qianyan.model.workflow.WorkflowAttemptStatus
import com.qianyan.model.workflow.WorkflowContinuation
import com.qianyan.model.workflow.WorkflowId
import com.qianyan.model.workflow.WorkflowKind
import com.qianyan.model.workflow.WorkflowStatus
import com.qianyan.model.workflow.WorkflowStep
import com.qianyan.model.workflow.WorkflowStepId
import com.qianyan.model.workflow.WorkflowStepPhase
import com.qianyan.model.workflow.WorkflowStepStatus
import com.qianyan.storage.repository.UniqueConflictException
import com.qianyan.storage.repository.WorkflowRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * O-25（A-33 D9/D10）：`executeWriting` 的前置读取（plan seam / 续篇来源）失败路径。
 *
 * 语义：前置读取失败必须落到该 attempt（FAILED + 原始文本 + NonRetryable），
 * 不得让异常穿出 runForward 而把 attempt 永久留在 RUNNING。
 */
class WritingPreflightFailureAttemptTest {

    @Test
    fun `continuation query storage failure marks attempt failed and non retryable`() {
        val f = novelAgentFixture()
        try {
            val w = seedNovelWorld(f)

            // 1) 先真实跑一轮，取得合法的 PLAN seam（PLANJSON2 引用），供后续 WRITING 复用。
            val seedWf = WorkflowId("W-O25-SEED")
            createWorkflow(f, w.novelId, seedWf, w.chapterId)
            f.app.workflowOrchestrator.runForward(seedWf)
            val planRef = assertNotNull(
                f.app.workflows.listSteps(seedWf)
                    .first { it.phase == WorkflowStepPhase.PLANNING }
                    .resultReference,
            )

            // 2) 同一章节上新建 Workflow：PLANNING 已完成（复用 planRef）+ WRITING 待执行。
            val wfId = WorkflowId("W-O25-STORAGE")
            createWorkflow(f, w.novelId, wfId, w.chapterId)
            f.app.workflows.createStep(
                WorkflowStep(
                    stepId = WorkflowStepId("step-$wfId-planning"),
                    workflowId = wfId,
                    chapterId = w.chapterId,
                    phase = WorkflowStepPhase.PLANNING,
                    logicalStepKey = "$wfId:${w.chapterId.value}:PLANNING",
                    status = WorkflowStepStatus.COMPLETED,
                    resultReference = planRef,
                    createdAt = FIXED_INSTANT,
                    completedAt = FIXED_INSTANT,
                ),
            )
            val writingStep = WorkflowStep(
                stepId = WorkflowStepId("step-$wfId-writing"),
                workflowId = wfId,
                chapterId = w.chapterId,
                phase = WorkflowStepPhase.WRITING,
                logicalStepKey = "$wfId:${w.chapterId.value}:WRITING",
                status = WorkflowStepStatus.RUNNING,
                createdAt = FIXED_INSTANT,
            )
            f.app.workflows.createStep(writingStep)

            // 3) 仅续篇查询抛存储异常；其余读取走真实仓储。
            val repo = ThrowingContinuationRepository(f.app.workflowRepository)
            val r = orchestratorWith(f, repo).runForward(wfId)

            assertEquals(WorkflowStatus.FAILED, r.status)
            assertTrue(r.failed)
            val attempts = f.app.workflowRepository.listAttempts(writingStep.stepId)
            assertEquals(1, attempts.size, "NonRetryable 不得自动重试")
            assertEquals(WorkflowAttemptStatus.FAILED, attempts.single().status)
            assertEquals(AttemptErrorCategory.NON_RETRYABLE, attempts.single().errorCategory)
            assertTrue(
                attempts.single().errorMessage.orEmpty().contains("boom-continuation"),
                "失败 attempt 必须保留原始错误文本：${attempts.single().errorMessage}",
            )
        } finally {
            f.close()
        }
    }

    @Test
    fun `missing plan seam marks attempt failed and non retryable`() {
        val f = novelAgentFixture()
        try {
            val w = seedNovelWorld(f)
            val wfId = WorkflowId("W-O25-NOPLAN")
            createWorkflow(f, w.novelId, wfId, w.chapterId)
            val writingStep = WorkflowStep(
                stepId = WorkflowStepId("step-$wfId-writing"),
                workflowId = wfId,
                chapterId = w.chapterId,
                phase = WorkflowStepPhase.WRITING,
                logicalStepKey = "$wfId:${w.chapterId.value}:WRITING",
                status = WorkflowStepStatus.RUNNING,
                createdAt = FIXED_INSTANT,
            )
            f.app.workflows.createStep(writingStep)

            val r = f.app.workflowOrchestrator.runForward(wfId)

            assertEquals(WorkflowStatus.FAILED, r.status)
            assertTrue(r.failed)
            val attempts = f.app.workflowRepository.listAttempts(writingStep.stepId)
            assertEquals(1, attempts.size, "NonRetryable 不得自动重试")
            assertEquals(WorkflowAttemptStatus.FAILED, attempts.single().status)
            assertEquals(AttemptErrorCategory.NON_RETRYABLE, attempts.single().errorCategory)
            assertTrue(
                attempts.single().errorMessage.orEmpty().contains("缺少已完成 PLANNING 步骤结果"),
                "失败 attempt 必须携带 plan seam 缺失原因：${attempts.single().errorMessage}",
            )
        } finally {
            f.close()
        }
    }

    /* ---------------- fixture ---------------- */

    private fun createWorkflow(
        f: NovelAgentFixture,
        novelId: com.qianyan.model.NovelId,
        workflowId: WorkflowId,
        chapterId: ChapterId,
    ) {
        f.app.workflows.createWorkflow(
            Workflow(
                workflowId = workflowId,
                novelId = novelId,
                kind = WorkflowKind.WRITE_NOVEL,
                status = WorkflowStatus.CREATED,
                activeChapterId = chapterId,
                createdAt = FIXED_INSTANT,
                updatedAt = FIXED_INSTANT,
            ),
        )
    }

    private fun orchestratorWith(f: NovelAgentFixture, repo: WorkflowRepository): WorkflowOrchestrator =
        WorkflowOrchestrator(
            workflowRepository = repo,
            taskManager = f.app.tasks,
            writing = f.app.writingExecution,
            planning = f.app.planning,
            critique = f.app.critique,
            revision = f.app.revision,
            knowledgeUpdate = f.app.knowledgeUpdate,
            draftRepository = f.app.draftRepository,
            chapterRepository = f.app.chapterRepository,
            commits = f.app.commits,
            workingDrafts = f.app.workingDrafts,
            changes = f.app.changes,
            canonicalRead = f.app.canonicalRead,
            errorMapper = f.app.errorMapper,
        )

    /** 委托仓储：仅让续篇查询失败（其余语义不变）。 */
    private class ThrowingContinuationRepository(
        private val delegate: WorkflowRepository,
    ) : WorkflowRepository by delegate {
        override fun getContinuationByTargetChapter(targetChapterId: ChapterId): WorkflowContinuation? =
            throw UniqueConflictException("boom-continuation")
    }
}
