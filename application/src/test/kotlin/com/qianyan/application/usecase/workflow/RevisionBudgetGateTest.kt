package com.qianyan.application.usecase.workflow

import com.qianyan.application.di.ApplicationContainer
import com.qianyan.application.usecase.writing.WritingSnapshot
import com.qianyan.model.ChapterId
import com.qianyan.model.NovelId
import com.qianyan.model.workingdraft.WorkingDraftTarget
import com.qianyan.model.workflow.HumanGateStatus
import com.qianyan.model.workflow.Workflow
import com.qianyan.model.workflow.WorkflowId
import com.qianyan.model.workflow.WorkflowKind
import com.qianyan.model.workflow.WorkflowStatus
import com.qianyan.model.workflow.WorkflowStep
import com.qianyan.model.workflow.WorkflowStepId
import com.qianyan.model.workflow.WorkflowStepPhase
import com.qianyan.model.workflow.WorkflowStepStatus
import com.qianyan.provider.LLMGateway
import com.qianyan.provider.ProviderRequest
import com.qianyan.provider.ProviderResponse
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T-O83-F · 修订预算耗尽 ⇒ 开人工门（不再整链 FAILED）。
 *
 * 沿真实 Repository + Orchestrator 路径：构造"已用满/未用满预算 + 一个待处理 CRITIQUE(REVISION)"的
 * 进行中 Workflow，由真实 `runForward` 走 `createSuccessor(CRITIQUE)` 的预算分支。
 * 全程不调用 LLM（[ForbiddenGateway]）。
 */
class RevisionBudgetGateTest {

    private class ForbiddenGateway : LLMGateway {
        override fun chat(request: ProviderRequest): ProviderResponse = error("本测试不得调用 LLM")
    }

    /* ---------- 1. 预算耗尽：转人工门，而非 FAILED ---------- */

    @Test
    fun `revision budget exhausted opens confirmation gate`() {
        val fixture = crafted(usedRevisions = 3)
        val app = fixture.app
        val wfId = fixture.wfId
        val ch = fixture.chapterId

        val run = app.workflowOrchestrator.runForward(wfId)
        assertEquals(WorkflowStatus.WAITING_HUMAN, run.status, "预算耗尽应停在人工门")

        val wf = app.workflowRepository.getWorkflow(wfId)!!
        assertEquals(WorkflowStatus.WAITING_HUMAN, wf.status, "预算耗尽不再终止为 FAILED")
        val gateId = assertNotNull(wf.pendingGateId, "必须挂上待确认 Gate")
        assertEquals(HumanGateStatus.PENDING, assertNotNull(app.workflowRepository.getGate(gateId)).status)

        val steps = app.workflowRepository.listSteps(wfId)
        assertEquals(3, steps.count { it.phase == WorkflowStepPhase.REVISION }, "耗尽后不得再建第 4 个 REVISION")
        assertEquals(
            WorkflowStepStatus.COMPLETED,
            steps.last { it.phase == WorkflowStepPhase.FINALIZE }.status,
            "FINALIZE 应已完成（预算耗尽后直接成稿）",
        )
        assertEquals(
            WorkflowStepStatus.PENDING,
            steps.last { it.phase == WorkflowStepPhase.CONFIRMATION }.status,
            "CONFIRMATION 在 Gate 待决期间保持 PENDING（批准/拒绝后才收敛），已建 Gate 见上",
        )

        val progress = app.workflowFacade.getChapterProgress(ch)
        assertEquals(ChapterPhase.WAITING_CONFIRMATION, progress.phase, "投影应停在待确认")
        assertNull(progress.failureMessage, "预算耗尽不是失败 ⇒ 不得投影失败原因")
    }

    /* ---------- 2. 预算未耗尽：仍建 REVISION（防 off-by-one） ---------- */

    @Test
    fun `revision below budget still routes to revision`() {
        val fixture = crafted(usedRevisions = 1)
        val app = fixture.app
        val wfId = fixture.wfId
        val ch = fixture.chapterId
        val keysBefore = app.workflowRepository.listSteps(wfId).map { it.logicalStepKey }.toSet()

        // REVISION 执行会真的调用 LLM（本测试禁用）⇒ 允许失败，只验证"后继选择"。
        runCatching { app.workflowOrchestrator.runForward(wfId) }

        val steps = app.workflowRepository.listSteps(wfId)
        val created = steps.filter { it.phase == WorkflowStepPhase.REVISION && it.logicalStepKey !in keysBefore }
        assertEquals(1, created.size, "预算未耗尽 ⇒ 仍应新建 1 个 REVISION Step")
        assertEquals(
            "${wfId.value}:${ch.value}:REVISION:R2",
            created.single().logicalStepKey,
            "跨轮累计序号：第 2 个修订（不是把第 1 轮就送去人工门）",
        )
        assertEquals(0, created.single().roundNo, "新 REVISION 属于当前创作轮次")
        assertTrue(steps.none { it.phase == WorkflowStepPhase.FINALIZE }, "预算未耗尽不得提前 FINALIZE")
        assertNull(app.workflowRepository.getWorkflow(wfId)!!.pendingGateId, "预算未耗尽不得开人工门")
    }

    /* ---------- fixture ---------- */

    private class Fixture(
        val app: ApplicationContainer,
        val wfId: WorkflowId,
        val chapterId: ChapterId,
    )

    /**
     * 构造：真实 Novel/Chapter/WorkingDraft（供 FINALIZE 解析工作产物）+ RUNNING Workflow +
     * `usedRevisions` 个已完成 REVISION Step（round 0，携带 WORKING 引用）+ 一个待处理 CRITIQUE(REVISION)。
     */
    private fun crafted(usedRevisions: Int): Fixture {
        val app = ApplicationContainer.open(analysisGateway = ForbiddenGateway())
        val novelId = app.novels.createOriginal(title = "T-O83 预算")
        val ch = app.chapters.createNextChapter(title = "第一章", novelId = novelId).chapterId
        val projectId = app.projects.openProject(novelId).projectId
        val working = app.workingDrafts.create(
            projectId = projectId,
            target = WorkingDraftTarget(novelId, ch, null),
            content = "修订后的候选",
        )
        val ref = WritingSnapshot.WORKING_REF_PREFIX + working.workingDraftId.value

        val wfId = WorkflowId("wf-o83-${usedRevisions}")
        val now = Clock.System.now()
        app.workflowRepository.createWorkflow(
            Workflow(
                workflowId = wfId, novelId = novelId, variantId = null, kind = WorkflowKind.WRITE_NOVEL,
                status = WorkflowStatus.RUNNING, activeChapterId = ch, createdAt = now, updatedAt = now,
            ),
        )
        repeat(usedRevisions) { i ->
            app.workflowRepository.createStep(
                step(
                    wfId, ch, WorkflowStepPhase.REVISION,
                    "${wfId.value}:${ch.value}:REVISION:R${i + 1}",
                    WorkflowStepStatus.COMPLETED, roundNo = 0, ref = ref, at = now.plusMillis(i.toLong()),
                ),
            )
        }
        app.workflowRepository.createStep(
            step(
                wfId, ch, WorkflowStepPhase.CRITIQUE,
                "${wfId.value}:${ch.value}:CRITIQUE",
                WorkflowStepStatus.PENDING, roundNo = 0,
                ref = "CRITIQUE:task-o83:WORKING:wd-o83:REVISION", at = now.plusMillis(100),
            ),
        )
        return Fixture(app, wfId, ch)
    }

    private fun step(
        wfId: WorkflowId,
        ch: ChapterId,
        phase: WorkflowStepPhase,
        key: String,
        status: WorkflowStepStatus,
        roundNo: Int?,
        ref: String?,
        at: Instant,
    ) = WorkflowStep(
        stepId = WorkflowStepId(java.util.UUID.randomUUID().toString()),
        workflowId = wfId, chapterId = ch, phase = phase, logicalStepKey = key,
        status = status, resultReference = ref, roundNo = roundNo, createdAt = at,
    )

    private fun Instant.plusMillis(ms: Long) = Instant.fromEpochMilliseconds(toEpochMilliseconds() + ms)
}
