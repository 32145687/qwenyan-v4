package com.qianyan.application.usecase.workflow

import com.qianyan.application.di.ApplicationContainer
import com.qianyan.application.error.ApplicationException
import com.qianyan.model.ChapterId
import com.qianyan.model.NovelId
import com.qianyan.model.workflow.HumanDecision
import com.qianyan.model.workflow.HumanGateStatus
import com.qianyan.model.workflow.Workflow
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
import com.qianyan.provider.LLMGateway
import com.qianyan.provider.ProviderRequest
import com.qianyan.provider.ProviderResponse
import kotlinx.datetime.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P1-02j.2-F · 修订预算补测（一）：**NULL / 0 统计等价** 与 **workflow 级后缀**。
 *
 * 沿**真实 Repository + Orchestrator**路径：用真实 `workflowRepository` 构造"进行中"的 Workflow 状态
 * （已存在的 REVISION Step + 一个待处理、已写 resultReference 的 CRITIQUE Step），
 * 再由真实 `WorkflowOrchestrator.runForward` 走 `createSuccessor(CRITIQUE)` 的预算判定分支。
 *
 * 全程**不调用 LLM**（用 [ForbiddenGateway] 断言）。历史数据保留真实语义：`roundNo=null` 在库中仍是 NULL。
 */
class RevisionBudgetNullEquivalenceTest {

    /** 任何 LLM 调用都视为测试失败（本文件为纯状态机/仓储路径）。 */
    private class ForbiddenGateway : LLMGateway {
        override fun chat(request: ProviderRequest): ProviderResponse = error("本测试不得调用 LLM")
    }

    /* ---------------- 1. NULL 与 0 等价：均按 round 0 计数 ---------------- */

    @Test
    fun `legacy NULL revision steps count as round 0 and block the cap`() {
        withCraftedBudget(roundNos = listOf(null, null, null), critiqueRoundNo = 0) { app, wfId, ch ->
            assertEquals(3, revisions(app, wfId).size, "前置：应有 3 个 REVISION Step")
            assertTrue(revisions(app, wfId).all { it.roundNo == null }, "前置：历史行 round_no 在库中必须仍是 NULL")

            app.workflowOrchestrator.runForward(wfId)

            assertEquals(3, revisions(app, wfId).size, "round 0 已有 3 个（NULL）修订 ⇒ 第 4 次被预算阻止，不新增")
            assertEquals(WorkflowStatus.FAILED, app.workflowRepository.getWorkflow(wfId)!!.status, "达到轮内上限 ⇒ FAILED")
        }
    }

    @Test
    fun `roundNo zero revision steps count as round 0 and block the cap`() {
        withCraftedBudget(roundNos = listOf(0, 0, 0), critiqueRoundNo = 0) { app, wfId, _ ->
            app.workflowOrchestrator.runForward(wfId)
            assertEquals(3, revisions(app, wfId).size, "round 0 已有 3 个（0）修订 ⇒ 第 4 次被阻止")
            assertEquals(WorkflowStatus.FAILED, app.workflowRepository.getWorkflow(wfId)!!.status)
        }
    }

    @Test
    fun `NULL and zero mix counts together in round 0`() {
        withCraftedBudget(roundNos = listOf(null, 0, null), critiqueRoundNo = 0) { app, wfId, _ ->
            assertEquals(3, revisions(app, wfId).size)
            app.workflowOrchestrator.runForward(wfId)
            assertEquals(3, revisions(app, wfId).size, "NULL 与 0 混合后合计 3 ⇒ 第 4 次被阻止")
            assertEquals(WorkflowStatus.FAILED, app.workflowRepository.getWorkflow(wfId)!!.status)
        }
    }

    @Test
    fun `a single round-0 revision does not yet block a new revision`() {
        withCraftedBudget(roundNos = listOf(null), critiqueRoundNo = 0) { app, wfId, _ ->
            app.workflowOrchestrator.runForward(wfId)
            assertEquals(2, revisions(app, wfId).size, "round 0 仅 1 个（NULL）修订 ⇒ 允许再建 1 个 REVISION Step")
        }
    }

    /* ---------------- 2. 轮次隔离：round 1 预算不受 round 0 历史影响 ---------------- */

    @Test
    fun `round 1 budget is independent of round 0 history`() {
        // 3 个 round 0 修订（含 NULL）+ 一个 round=1 的 CRITIQUE ⇒ round 1 预算从 0 起
        withCraftedBudget(roundNos = listOf(null, 0, null), critiqueRoundNo = 1) { app, wfId, _ ->
            app.workflowOrchestrator.runForward(wfId)
            assertEquals(4, revisions(app, wfId).size, "round 1 预算独立 ⇒ 允许其首次 REVISION（round 0 的 3 个不计入）")
            assertEquals(
                1,
                revisions(app, wfId).count { (it.roundNo ?: 0) == 1 },
                "新 REVISION Step 应属于 round 1",
            )
        }
    }

    /* ---------------- 3. Attempt 不重复计入 REVISION 次数 ---------------- */

    @Test
    fun `multiple attempts on one revision step do not inflate the revision count`() {
        withCraftedBudget(roundNos = listOf(0), critiqueRoundNo = 0, attemptsPerRevision = 3) { app, wfId, _ ->
            val step = revisions(app, wfId).single()
            assertEquals(3, app.workflowRepository.listAttempts(step.stepId).size, "前置：该 REVISION Step 有 3 个 Attempt")

            app.workflowOrchestrator.runForward(wfId)

            assertEquals(3, app.workflowRepository.listAttempts(step.stepId).size, "原 REVISION Step 的 Attempt 不受影响")
            // 关键：预算按 **Step** 计（1），不因 3 个 Attempt 变成 3 ⇒ 允许再建 1 个 REVISION
            assertEquals(2, revisions(app, wfId).size, "3 个 Attempt 不改变 REVISION 计数（仍为 1 ⇒ 允许第 2 个）")
        }
    }

    /* ---------------- 4. 章节隔离：不同章节预算互不影响 ---------------- */

    @Test
    fun `revision budget is chapter-scoped`() {
        val app = ApplicationContainer.open(analysisGateway = ForbiddenGateway())
        val wfId = WorkflowId("wf-chapter-scope")
        val chA = ChapterId("ch-A")
        val chB = ChapterId("ch-B")
        val now = Clock.System.now()
        app.workflowRepository.createWorkflow(
            Workflow(
                workflowId = wfId, novelId = NovelId("n-scope"), kind = WorkflowKind.WRITE_NOVEL,
                status = WorkflowStatus.RUNNING, activeChapterId = chB, createdAt = now, updatedAt = now,
            ),
        )
        // chA 已用满 3 个 round 0 修订
        repeat(3) { i ->
            app.workflowRepository.createStep(step(wfId, chA, WorkflowStepPhase.REVISION, "${wfId.value}:${chA.value}:REVISION:R${i + 1}", WorkflowStepStatus.COMPLETED, roundNo = 0, ref = null, at = now))
        }
        // chB 的一个 round 0 CRITIQUE 要求 REVISION
        app.workflowRepository.createStep(critique(wfId, chB, roundNo = 0, at = now.plusMillis(1)))

        app.workflowOrchestrator.runForward(wfId)

        val newRev = revisions(app, wfId).filter { it.chapterId == chB }
        assertEquals(1, newRev.size, "chB 预算从 0 起（不受 chA 的 3 个 round 0 修订影响）")
        assertEquals(3, revisions(app, wfId).count { it.chapterId == chA }, "chA 的历史修订不被改动")
    }

    /* ---------------- 5. workflow 级后缀：其他章节的较大后缀会抬高本节的新轮后缀 ---------------- */

    @Test
    fun `workflow level suffix allocator raises a chapter suffix but keeps keys unique and budget chapter-scoped`() {
        val app = ApplicationContainer.open(analysisGateway = ForbiddenGateway())
        val wfId = WorkflowId("wf-suffix-scope")
        val chA = ChapterId("ch-A")
        val chB = ChapterId("ch-B")
        val now = Clock.System.now()
        val gateId = WorkflowHumanGateId("gate-suffix-scope")
        // Workflow 先建（WorkflowStep/WorkflowHumanGate 外键均指向 Workflow）；pending_gate_id 无外键，可先置。
        app.workflowRepository.createWorkflow(
            Workflow(
                workflowId = wfId, novelId = NovelId("n-suffix"), kind = WorkflowKind.WRITE_NOVEL,
                status = WorkflowStatus.WAITING_HUMAN, activeChapterId = chB, pendingGateId = gateId,
                createdAt = now, updatedAt = now,
            ),
        )
        // chA 已有一个 `:R7` 后缀（抬高 workflow 级 maxSuffix）
        app.workflowRepository.createStep(step(wfId, chA, WorkflowStepPhase.FINALIZE, "${wfId.value}:${chA.value}:FINALIZE:R7", WorkflowStepStatus.COMPLETED, roundNo = 7, ref = null, at = now))
        // chB：一个被拒的 CONFIRMATION Step
        val confB = step(wfId, chB, WorkflowStepPhase.CONFIRMATION, "${wfId.value}:${chB.value}:CONFIRMATION", WorkflowStepStatus.PENDING, roundNo = 0, ref = null, at = now.plusMillis(1))
        app.workflowRepository.createStep(confB)
        app.workflowRepository.createGate(
            WorkflowHumanGate(
                gateId = gateId, workflowId = wfId, stepId = confB.stepId, draftId = null,
                gateKey = "${wfId.value}:${confB.logicalStepKey}:WORKING:x", status = HumanGateStatus.RESOLVED,
                decision = HumanDecision.REJECTED, createdAt = now, resolvedAt = now, resolvedBy = "user",
            ),
        )
        val keysBefore = app.workflowRepository.listSteps(wfId).map { it.logicalStepKey }.toSet()

        // 显式作者请求 → reopenRejectedRound：为 chB 建新 CONFIRMATION（后缀由 workflow 级分配器给出）。
        // 之后 CONFIRMATION 执行时无可用工作产物（本测试未构造）→ 预期抛出类型化错误，但重开已发生。
        runCatching { app.workflowOrchestrator.runForward(wfId, allowReentryAfterReject = true) }

        val newConfirmations = app.workflowRepository.listSteps(wfId)
            .filter { it.chapterId == chB && it.phase == WorkflowStepPhase.CONFIRMATION && it.logicalStepKey !in keysBefore }
        assertEquals(1, newConfirmations.size, "重开应新增 1 个 chB CONFIRMATION Step")
        val newKey = newConfirmations.single().logicalStepKey
        val suffix = newKey.substringAfterLast(":W").toIntOrNull()
        assertNotNull(suffix, "新 CONFIRMATION key 应带 `:W<n>` 后缀（P1-02j.2-G）：$newKey")
        assertTrue(suffix > 7, "workflow 级分配器：chA 的 R7 会抬高 chB 的新后缀（实际 W$suffix > 7）")
        assertEquals(0, newConfirmations.single().roundNo, "重开不推进创作轮次（roundNo 仍为 0）")

        // 键全局唯一；chA 历史 step 不被改动
        val allKeys = app.workflowRepository.listSteps(wfId).map { it.logicalStepKey }
        assertEquals(allKeys.size, allKeys.toSet().size, "所有 logicalStepKey 必须唯一")
        assertTrue(app.workflowRepository.listSteps(wfId).any { it.logicalStepKey.endsWith("FINALIZE:R7") }, "chA 历史 Step 保留")
    }

    /* ---------------- helpers ---------------- */

    private fun revisions(app: ApplicationContainer, wfId: WorkflowId) =
        app.workflowRepository.listSteps(wfId).filter { it.phase == WorkflowStepPhase.REVISION }

    private fun step(
        wfId: WorkflowId,
        ch: ChapterId,
        phase: WorkflowStepPhase,
        key: String,
        status: WorkflowStepStatus,
        roundNo: Int?,
        ref: String?,
        at: kotlinx.datetime.Instant,
    ) = WorkflowStep(
        stepId = WorkflowStepId(java.util.UUID.randomUUID().toString()),
        workflowId = wfId, chapterId = ch, phase = phase, logicalStepKey = key,
        status = status, resultReference = ref, roundNo = roundNo, createdAt = at,
    )

    /** 一个"已写 resultReference、尚待完成"的 CRITIQUE Step（decision=REVISION），供 createSuccessor 判预算。 */
    private fun critique(wfId: WorkflowId, ch: ChapterId, roundNo: Int, at: kotlinx.datetime.Instant) =
        step(
            wfId, ch, WorkflowStepPhase.CRITIQUE, "${wfId.value}:${ch.value}:CRITIQUE",
            WorkflowStepStatus.PENDING, roundNo, "CRITIQUE:task-x:WORKING:wd-x:REVISION", at,
        )

    /**
     * 构造 Workflow（RUNNING，activeChapterId=ch）+ 若干既有 REVISION Step（指定 roundNo，可为 NULL）+
     * 一个待处理 CRITIQUE（要求 REVISION）。回调内运行真实 `runForward`。
     */
    private fun withCraftedBudget(
        roundNos: List<Int?>,
        critiqueRoundNo: Int,
        attemptsPerRevision: Int = 0,
        block: (app: ApplicationContainer, wfId: WorkflowId, ch: ChapterId) -> Unit,
    ) {
        val app = ApplicationContainer.open(analysisGateway = ForbiddenGateway())
        val wfId = WorkflowId("wf-crafted-${java.util.UUID.randomUUID()}")
        val ch = ChapterId("ch-crafted")
        val now = Clock.System.now()
        app.workflowRepository.createWorkflow(
            Workflow(
                workflowId = wfId, novelId = NovelId("n-crafted"), kind = WorkflowKind.WRITE_NOVEL,
                status = WorkflowStatus.RUNNING, activeChapterId = ch, createdAt = now, updatedAt = now,
            ),
        )
        roundNos.forEachIndexed { i, rn ->
            val rev = step(wfId, ch, WorkflowStepPhase.REVISION, "${wfId.value}:${ch.value}:REVISION:R${i + 1}", WorkflowStepStatus.COMPLETED, rn, null, now.plusMillis(i.toLong()))
            app.workflowRepository.createStep(rev)
            repeat(attemptsPerRevision) { a ->
                app.workflowRepository.createAttempt(
                    WorkflowStepAttempt(
                        attemptId = WorkflowStepAttemptId("${rev.stepId.value}-a$a"), stepId = rev.stepId,
                        attemptNo = a + 1, status = com.qianyan.model.workflow.WorkflowAttemptStatus.FAILED,
                        startedAt = now.plusMillis(a.toLong()),
                    ),
                )
            }
        }
        app.workflowRepository.createStep(critique(wfId, ch, critiqueRoundNo, now.plusMillis(100)))
        block(app, wfId, ch)
    }

    private fun kotlinx.datetime.Instant.plusMillis(ms: Long) = kotlinx.datetime.Instant.fromEpochMilliseconds(toEpochMilliseconds() + ms)
}
