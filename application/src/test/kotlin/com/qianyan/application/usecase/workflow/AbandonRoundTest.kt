package com.qianyan.application.usecase.workflow

import com.qianyan.application.di.ApplicationContainer
import com.qianyan.application.error.ApplicationException
import com.qianyan.model.ChapterId
import com.qianyan.model.NovelId
import com.qianyan.model.workflow.HumanDecision
import com.qianyan.model.workflow.HumanGateStatus
import com.qianyan.model.workflow.WorkflowStatus
import com.qianyan.model.workflow.WorkflowStepPhase
import com.qianyan.model.workflow.WorkflowStepStatus
import com.qianyan.provider.ChatMessage
import com.qianyan.provider.ChatRole
import com.qianyan.provider.FinishReason
import com.qianyan.provider.LLMGateway
import com.qianyan.provider.ProviderRequest
import com.qianyan.provider.ProviderResponse
import com.qianyan.provider.Usage
import kotlinx.datetime.Clock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * P1-02j A1 · **失效轮次的显式作废**（`abandonRound`）。
 *
 * 场景：Gate 已被**拒绝**，但工作产物已**失效**（跨"进程重启"后内存工作区丢失）——该轮无合法前进出口。
 * 作废语义（不伪造 / 不重跑 LLM / 不改写历史）：关闭失效轮次（CONFIRMATION → SKIPPED，旧 Gate/Step/Attempt 保留），
 * 在同一 Workflow 上开启**新一轮**（新 `logical_step_key`、新 Gate），脱离语义不符的 `WAITING_HUMAN`。
 *
 * 跨重启用**文件库 + 新容器**（= 空内存工作区 + 保留持久化事实）构造，确定性；无 sleep / 无线程 / 无随机。
 */
class AbandonRoundTest {

    private class CountingGateway : LLMGateway {
        var plannerCalls = 0
        var writerCalls = 0
        var criticCalls = 0
        var revisionCalls = 0

        fun snapshot(): List<Int> = listOf(plannerCalls, writerCalls, criticCalls, revisionCalls)

        override fun chat(request: ProviderRequest): ProviderResponse {
            val sys = request.messages.first { it.role == ChatRole.SYSTEM }.content
            val body = when {
                "StoryPlannerAgent" in sys -> { plannerCalls++; """{"chapterGoal":"推进主线"}""" }
                "StoryWriterAgent" in sys -> { writerCalls++; """{"content":"$AI_BODY"}""" }
                "StoryCriticAgent" in sys -> { criticCalls++; """{"passed":true}""" }
                "StoryRevisionAgent" in sys -> { revisionCalls++; """{"content":"$REVISED_BODY"}""" }
                "KnowledgeUpdateAgent" in sys || "StoryKnowledgeUpdateAgent" in sys ->
                    """{"changes":[{"changeId":"k1","operation":"ADD","target":"主角","content":"已突破金丹期"}]}"""
                else -> """{"chapterGoal":"推进主线"}"""
            }
            return ProviderResponse(
                message = ChatMessage(ChatRole.ASSISTANT, buildJsonObject { put("answer", body) }.toString()),
                usage = Usage(10, 10, 20),
                finishReason = FinishReason.STOP,
            )
        }
    }

    /* ---------------- 主路径：作废失效轮次 + 开启新一轮 ---------------- */

    @Test
    fun `abandoning a void rejected round preserves history opens a new round and runs no llm`() {
        withRestartedVoidRejectedRound { app, gw, novelId, chapterId, gateId ->
            val wf = workflow(app, chapterId)
            val oldSteps = app.workflowRepository.listSteps(wf.workflowId)
            val oldConfirmation = oldSteps.single { it.phase == WorkflowStepPhase.CONFIRMATION }
            val llmBefore = gw.snapshot()

            val outcome = app.workflowOrchestrator.abandonRound(gateId)

            // 1) 作废本身**绝不**调用 LLM（生成由后续显式 advance 触发）
            assertEquals(llmBefore, gw.snapshot(), "作废不得重跑 LLM")
            assertTrue(outcome is AbandonRoundOutcome.Abandoned, "应返回已开启新一轮")
            val round = (outcome as AbandonRoundOutcome.Abandoned).round
            assertTrue(round >= 1)

            // 2) 旧 Gate 决议不被改写
            val gateAfter = app.workflowRepository.getGate(gateId)!!
            assertEquals(HumanGateStatus.RESOLVED, gateAfter.status)
            assertEquals(HumanDecision.REJECTED, gateAfter.decision, "旧 Gate 必须保持 REJECTED")

            // 3) 旧 Step / Attempt 历史保留（不删除、不重建）；被拒轮次的 CONFIRMATION 关闭为 SKIPPED
            val newSteps = app.workflowRepository.listSteps(wf.workflowId)
            val oldIds = oldSteps.map { it.stepId }.toSet()
            assertTrue(newSteps.map { it.stepId }.containsAll(oldIds), "旧 Step 不得被删除")
            assertEquals(
                WorkflowStepStatus.SKIPPED,
                newSteps.single { it.stepId == oldConfirmation.stepId }.status,
                "失效轮次的 CONFIRMATION 应关闭为 SKIPPED（保留原身份）",
            )

            // 6) 脱离语义不符的 WAITING_HUMAN
            val wfAfter = workflow(app, chapterId)
            assertEquals(WorkflowStatus.RUNNING, wfAfter.status, "应脱离 WAITING_HUMAN")
            assertEquals(null, wfAfter.pendingGateId, "应清空 pendingGateId")

            // 4) 新一轮使用全新 logical_step_key（`:R<round>`），不复用旧 WRITING / CONFIRMATION 身份
            val oldWritingKey = oldSteps.first { it.phase == WorkflowStepPhase.WRITING }.logicalStepKey
            val newWritings = newSteps.filter { it.phase == WorkflowStepPhase.WRITING }
            assertTrue(
                newWritings.any { it.logicalStepKey.endsWith(":WRITING:W$round") },
                "新一轮应使用新的 WRITING key（期望 :WRITING:W$round；P1-02j.2-G 固定阶段用 `:W<n>`）：$newWritings",
            )
            assertTrue(
                newWritings.none { it.logicalStepKey == oldWritingKey && it.stepId !in oldIds },
                "旧 WRITING key 不得被新一轮复用",
            )
            // 新一轮只起 WRITING；其 CRITIQUE/FINALIZE/CONFIRMATION 由 createSuccessor 按 roundNo 动态建立
            // （由 `advancing after abandon...` 断言新 Gate 与 key 唯一性覆盖）。
        }
    }

    /**
     * 作废后由**显式 advance** 驱动新一轮 → 重新生成并到达**新的**人工门（新 Gate ≠ 旧 Gate），旧 Gate 保持 REJECTED。
     * （证明新一轮的 WRITING→CRITIQUE→FINALIZE→CONFIRMATION 链可真正推进，且不复用旧 Gate。）
     */
    @Test
    fun `advancing after abandon regenerates and reaches a fresh gate distinct from the rejected one`() {
        withRestartedVoidRejectedRound { app, gw, novelId, chapterId, gateId ->
            app.workflowOrchestrator.abandonRound(gateId)
            val writerBefore = gw.writerCalls

            val progress = app.workflowFacade.advance(chapterId) // 显式请求 ⇒ 驱动新一轮（重新生成）

            assertTrue(gw.writerCalls > writerBefore, "新一轮应由显式 advance 触发重新生成")
            assertTrue(progress.waitingForUser, "新一轮应到达新的人工门")
            val newGateId = assertNotNull(workflow(app, chapterId).pendingGateId)
            assertNotEquals(gateId, newGateId, "新一轮不得复用旧 Gate")
            // 旧 Gate 保持 REJECTED
            assertEquals(HumanDecision.REJECTED, app.workflowRepository.getGate(gateId)!!.decision)
            assertEquals(HumanGateStatus.PENDING, app.workflowRepository.getGate(newGateId)!!.status, "新 Gate 应为 PENDING")
        }
    }

    /* ---------------- 前置：非法作废一律拒绝 ---------------- */

    @Test
    fun `pending gate is never abandoned`() {
        val (app, gw) = open()
        val (novelId, chapterId) = seed(app)
        driveToGate(app, novelId, chapterId)
        val gateId = assertNotNull(workflow(app, chapterId).pendingGateId)
        val llmBefore = gw.snapshot()

        val error = assertFailsWith<ApplicationException> { app.workflowOrchestrator.abandonRound(gateId) }
        assertTrue(error.message.orEmpty().contains("非 RESOLVED+REJECTED"), "PENDING Gate 不得作废：${error.message}")

        // 状态零改动
        val gate = app.workflowRepository.getGate(gateId)!!
        assertEquals(HumanGateStatus.PENDING, gate.status)
        assertEquals(HumanDecision.PENDING, gate.decision)
        assertEquals(WorkflowStatus.WAITING_HUMAN, workflow(app, chapterId).status)
        assertEquals(llmBefore, gw.snapshot(), "不得触发 LLM")
    }

    @Test
    fun `approved gate is never abandoned and its decision is not rewritten`() {
        val (app, gw) = open()
        val (novelId, chapterId) = seed(app)
        driveToGate(app, novelId, chapterId)
        val gateId = assertNotNull(workflow(app, chapterId).pendingGateId)
        // 构造"已批准"（记录已 RESOLVED+APPROVED，尚未提交）
        markGateApproved(app, gateId)

        val error = assertFailsWith<ApplicationException> { app.workflowOrchestrator.abandonRound(gateId) }
        assertTrue(error.message.orEmpty().contains("已批准"), "APPROVED Gate 不得作废：${error.message}")

        val gate = app.workflowRepository.getGate(gateId)!!
        assertEquals(HumanGateStatus.RESOLVED, gate.status)
        assertEquals(HumanDecision.APPROVED, gate.decision, "APPROVED 决议不得被作废改写")
        // 未产生草稿 / 提交
        assertEquals(0, app.draftRepository.listByChapter(chapterId).size)
    }

    /* ---------------- 只读路径绝不自动作废 ---------------- */

    @Test
    fun `read only paths never auto abandon a void rejected round`() {
        withRestartedVoidRejectedRound { app, gw, novelId, chapterId, gateId ->
            val wfId = workflow(app, chapterId).workflowId
            val confirmationsBefore = app.workflowRepository.listSteps(wfId)
                .count { it.phase == WorkflowStepPhase.CONFIRMATION }
            val writingsBefore = app.workflowRepository.listSteps(wfId).count { it.phase == WorkflowStepPhase.WRITING }
            val llmBefore = gw.snapshot()

            // 普通读取 / 恢复：不得开新一轮、不得改 Gate、不得作废
            repeat(2) { app.workflowFacade.getChapterProgress(chapterId) }
            app.workflowFacade.resume(chapterId)

            val gate = app.workflowRepository.getGate(gateId)!!
            assertEquals(HumanGateStatus.RESOLVED, gate.status)
            assertEquals(HumanDecision.REJECTED, gate.decision, "只读/恢复不得改写被拒决议")
            assertEquals(
                confirmationsBefore,
                app.workflowRepository.listSteps(wfId).count { it.phase == WorkflowStepPhase.CONFIRMATION },
                "只读/恢复不得自动开启新一轮（新 CONFIRMATION）",
            )
            assertEquals(
                writingsBefore,
                app.workflowRepository.listSteps(wfId).count { it.phase == WorkflowStepPhase.WRITING },
                "只读/恢复不得自动生成新一轮 WRITING",
            )
            assertEquals(llmBefore, gw.snapshot(), "只读/恢复不得触发 LLM")
        }
    }

    /* ---------------- helpers ---------------- */

    /**
     * 构造"已被拒绝 + 工作产物失效"的持久状态：驱动到人工门 → 拒绝 → 关闭旧容器、以同库新容器重开
     * （内存工作区丢失）。回调内 `app` 已是重启后的容器。
     */
    private fun withRestartedVoidRejectedRound(
        block: (app: ApplicationContainer, gw: CountingGateway, novelId: NovelId, chapterId: ChapterId, gateId: com.qianyan.model.workflow.WorkflowHumanGateId) -> Unit,
    ) {
        val tmp = java.nio.file.Files.createTempFile("qianyan-abandon", ".db").toString()
        val handles = mutableListOf<com.qianyan.storage.db.QianyanDbHandle>()
        try {
            val gw = CountingGateway()
            var app = openFile("jdbc:sqlite:$tmp", handles, gw)
            val (novelId, chapterId) = seed(app)
            driveToGate(app, novelId, chapterId)
            val gateId = assertNotNull(workflow(app, chapterId).pendingGateId)
            app.workflowService.rejectGate(gateId, HumanDecision.REJECTED)
            assertEquals(HumanDecision.REJECTED, app.workflowRepository.getGate(gateId)!!.decision)
            closeAll(handles) // 进程退出：内存工作产物丢失 → 该轮失效

            app = openFile("jdbc:sqlite:$tmp", handles, gw)
            block(app, gw, novelId, chapterId, gateId)
        } finally {
            closeAll(handles)
            java.nio.file.Files.deleteIfExists(java.nio.file.Path.of(tmp))
        }
    }

    private fun open(): Pair<ApplicationContainer, CountingGateway> {
        val gw = CountingGateway()
        return ApplicationContainer.open(analysisGateway = gw) to gw
    }

    private fun openFile(
        url: String,
        handles: MutableList<com.qianyan.storage.db.QianyanDbHandle>,
        gateway: LLMGateway,
    ): ApplicationContainer {
        val h = com.qianyan.storage.db.QianyanDbFactory.open(url)
        handles += h
        return ApplicationContainer.fromDriver(h.driver, analysisGateway = gateway)
    }

    private fun closeAll(handles: MutableList<com.qianyan.storage.db.QianyanDbHandle>) {
        handles.forEach { h ->
            (h.driver as? app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver)?.getConnection()?.close()
        }
        handles.clear()
    }

    private fun seed(app: ApplicationContainer): Pair<NovelId, ChapterId> {
        val novelId = app.novels.createOriginal(title = "测试书")
        val chapter = app.chapters.createNextChapter(title = "第一章", novelId = novelId)
        return novelId to chapter.chapterId
    }

    private fun driveToGate(app: ApplicationContainer, novelId: NovelId, chapterId: ChapterId) {
        app.writerGateway.continueWriting(novelId, null, chapterId)
        assertTrue(app.workflowFacade.getChapterProgress(chapterId).waitingForUser, "前置：应停在人工门")
    }

    private fun markGateApproved(app: ApplicationContainer, gateId: com.qianyan.model.workflow.WorkflowHumanGateId) {
        val gate = app.workflowRepository.getGate(gateId)!!
        app.workflowRepository.updateGate(
            gate.copy(
                status = HumanGateStatus.RESOLVED,
                decision = HumanDecision.APPROVED,
                resolvedAt = Clock.System.now(),
                resolvedBy = "user",
            ),
        )
    }

    private fun workflow(app: ApplicationContainer, chapterId: ChapterId) =
        assertNotNull(app.workflowRepository.getWorkflowByActiveChapter(chapterId), "应存在 Workflow")

    private companion object {
        const val AI_BODY = "AI 待审正文"
        const val REVISED_BODY = "AI 修订正文"
    }
}
