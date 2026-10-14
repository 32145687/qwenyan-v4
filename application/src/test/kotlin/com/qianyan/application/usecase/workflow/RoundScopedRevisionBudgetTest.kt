package com.qianyan.application.usecase.workflow

import com.qianyan.application.di.ApplicationContainer
import com.qianyan.model.ChapterId
import com.qianyan.model.NovelId
import com.qianyan.model.workflow.HumanDecision
import com.qianyan.model.workflow.HumanGateStatus
import com.qianyan.model.workflow.WorkflowStatus
import com.qianyan.model.workflow.WorkflowStepPhase
import com.qianyan.provider.ChatMessage
import com.qianyan.provider.ChatRole
import com.qianyan.provider.FinishReason
import com.qianyan.provider.LLMGateway
import com.qianyan.provider.ProviderRequest
import com.qianyan.provider.ProviderResponse
import com.qianyan.provider.Usage
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P1-02j.2 · **轮次级修订预算**（Round-Scoped Revision Budget）与统一轮次编号。
 *
 * 语义：每创作轮次独立预算（≤ [MAX_REVISIONS]=3）；`roundNo` 承载创作轮次（`null`≡0）；
 * 仅 `abandonRound`（执行新一轮 WRITING）推进创作轮次；`reopenRejectedRound`（重开同一轮 Gate）**不**推进。
 *
 * 网关按调用次数**脚本化**：轮 0 critic 第 1–3 次判 REVISION、第 4 次 PASS（用满预算 3）；
 * 轮 g critic 第 1 次判 REVISION、第 2 次 PASS（证明新轮预算**从 0 起**独立）。跨重启用文件库 + 新容器。
 */
class RoundScopedRevisionBudgetTest {

    private class ScriptedGateway : LLMGateway {
        var plannerCalls = 0
        var writerCalls = 0
        var criticCalls = 0
        var revisionCalls = 0
        var kuCalls = 0

        override fun chat(request: ProviderRequest): ProviderResponse {
            val sys = request.messages.first { it.role == ChatRole.SYSTEM }.content
            val body = when {
                "StoryPlannerAgent" in sys -> { plannerCalls++; """{"chapterGoal":"推进主线"}""" }
                // 轮 0 第 1 次 writer → AI_BODY；其后（新一轮）→ NEW_BODY（区分 Canonical 来源）
                "StoryWriterAgent" in sys -> { writerCalls++; """{"content":"${if (writerCalls == 1) AI_BODY else NEW_BODY}"}""" }
                // 轮 0：1-3 REVISION、4 PASS；轮 g：5 REVISION、6 PASS
                "StoryCriticAgent" in sys -> { criticCalls++; """{"passed":${criticCalls == 4 || criticCalls == 6}}""" }
                "StoryRevisionAgent" in sys -> { revisionCalls++; """{"content":"$REVISED_BODY-$revisionCalls"}""" }
                "KnowledgeUpdateAgent" in sys || "StoryKnowledgeUpdateAgent" in sys -> {
                    kuCalls++
                    """{"changes":[{"changeId":"k1","operation":"ADD","target":"主角","content":"已突破金丹期"}]}"""
                }
                else -> """{"chapterGoal":"推进主线"}"""
            }
            return ProviderResponse(
                message = ChatMessage(ChatRole.ASSISTANT, buildJsonObject { put("answer", body) }.toString()),
                usage = Usage(10, 10, 20),
                finishReason = FinishReason.STOP,
            )
        }
    }

    /* ---------------- 端到端：首轮用满预算 → 作废 → 新轮独立预算 → 完成 ---------------- */

    @Test
    fun `new round gets an independent revision budget and completes end to end`() {
        val tmp = java.nio.file.Files.createTempFile("qianyan-roundbudget", ".db").toString()
        val handles = mutableListOf<com.qianyan.storage.db.QianyanDbHandle>()
        try {
            val gw = ScriptedGateway()
            var app = openFile("jdbc:sqlite:$tmp", handles, gw)
            val (novelId, chapterId) = seed(app)

            // --- 轮 0：3 次 REVISION 后用满预算、PASS 到达人工门 ---
            app.writerGateway.continueWriting(novelId, null, chapterId)
            assertEquals(3, gw.revisionCalls, "轮 0 应恰好用 3 次修订")
            assertTrue(app.workflowFacade.getChapterProgress(chapterId).waitingForUser, "轮 0 应停在人工门")
            val wfId = workflow(app, chapterId).workflowId
            val oldSteps = app.workflowRepository.listSteps(wfId)
            assertEquals(3, oldSteps.count { it.phase == WorkflowStepPhase.REVISION }, "轮 0 应有 3 个 REVISION Step")
            assertTrue(
                oldSteps.filter { it.phase == WorkflowStepPhase.REVISION }.all { (it.roundNo ?: 0) == 0 },
                "轮 0 的 REVISION Step 创作轮次应为 0",
            )
            val oldGateId = assertNotNull(workflow(app, chapterId).pendingGateId)

            // 用户拒绝 → 工作产物失效（重启）
            app.workflowService.rejectGate(oldGateId, HumanDecision.REJECTED)
            closeAll(handles)
            app = openFile("jdbc:sqlite:$tmp", handles, gw)

            // --- 作废失效轮次（不调用 LLM）→ 新一轮 ---
            val writerBefore = gw.writerCalls
            val outcome = app.workflowOrchestrator.abandonRound(oldGateId)
            assertEquals(writerBefore, gw.writerCalls, "abandonRound 本身不得调用 LLM")
            val round = (outcome as AbandonRoundOutcome.Abandoned).round
            assertTrue(round >= 1, "新一轮编号应 > 0")

            // --- 驱动新一轮：首次 CRITIQUE 要求 REVISION（新轮预算从 0 起，不被旧轮耗尽拖垮）---
            app.workflowFacade.advance(chapterId)
            assertTrue(app.workflowFacade.getChapterProgress(chapterId).waitingForUser, "新一轮应到达新的人工门")
            assertEquals(4, gw.revisionCalls, "新一轮首次 REVISION 必须被允许（轮内预算 0 → 1）")
            assertEquals(2, gw.writerCalls, "新一轮应重新生成（且仅一次）")
            val newGateId = assertNotNull(workflow(app, chapterId).pendingGateId)
            assertNotEquals(oldGateId, newGateId, "新一轮不得复用旧 Gate")
            assertEquals(HumanGateStatus.PENDING, app.workflowRepository.getGate(newGateId)!!.status)

            // --- 新轮 Step：不同 key、roundNo=round、REVISION 仅 1 个 ---
            val stepsAfterNewRound = app.workflowRepository.listSteps(wfId)
            val newRoundRevisions = stepsAfterNewRound.filter { it.phase == WorkflowStepPhase.REVISION && (it.roundNo ?: 0) == round }
            assertEquals(1, newRoundRevisions.size, "新一轮应有 1 个 REVISION Step（其独立预算内的首次）")
            assertEquals(
                newRoundRevisions.size + oldSteps.count { it.phase == WorkflowStepPhase.REVISION },
                stepsAfterNewRound.count { it.phase == WorkflowStepPhase.REVISION },
                "新旧轮 REVISION 共同存在（历史不被删除）",
            )
            val allKeys = stepsAfterNewRound.map { it.logicalStepKey }
            assertEquals(allKeys.size, allKeys.toSet().size, "所有 logicalStepKey 必须唯一（新旧轮不冲突）")
            assertTrue(stepsAfterNewRound.map { it.stepId }.containsAll(oldSteps.map { it.stepId }), "旧 Step 历史必须保留")

            // --- 批准新 Gate → 唯一 Commit → Canonical 来自新轮产物 → COMPLETED ---
            app.workflowFacade.approve(chapterId)
            // 新轮的最终产物 = 新轮唯一那次修订（revision #4）的结果；轮 0 的最终产物是 revision #3。
            assertEquals("$REVISED_BODY-4", app.canonicalRead.committedContent(chapterId), "Canonical 正文须来自新一轮最终获批产物")
            assertNotEquals("$REVISED_BODY-3", app.canonicalRead.committedContent(chapterId), "不得是轮 0 的产物")
            assertEquals(1, app.commits.history(projectId(app, novelId)).size, "只应发生一次 Commit")
            assertEquals(newGateId, app.commits.committedByGate(newGateId)?.gateId, "新 Gate 应关联该唯一提交")
            assertNull(app.commits.committedByGate(oldGateId), "旧（被拒）Gate 不得关联任何提交")
            assertEquals(HumanDecision.REJECTED, app.workflowRepository.getGate(oldGateId)!!.decision, "旧 Gate 决议须保留 REJECTED")
            assertEquals(HumanGateStatus.RESOLVED, app.workflowRepository.getGate(oldGateId)!!.status)

            assertEquals(ChapterPhase.COMPLETED, app.workflowFacade.advance(chapterId).phase, "最终应到达 COMPLETED")
            // 无意外 LLM：恰为 轮0(planner1/writer1/critic4/revision3) + 轮g(writer1/critic2/revision1) + KU1
            assertEquals(2, gw.writerCalls)
            assertEquals(6, gw.criticCalls)
            assertEquals(4, gw.revisionCalls)
            assertEquals(1, gw.kuCalls)
        } finally {
            closeAll(handles)
            java.nio.file.Files.deleteIfExists(java.nio.file.Path.of(tmp))
        }
    }

    /* ---------------- 统一编号：reopen 不改变创作轮次且键不冲突 ---------------- */

    @Test
    fun `reopen uses the unified suffix allocator without changing the creative round`() {
        val (app, _) = open()
        val (novelId, chapterId) = seed(app)
        app.writerGateway.continueWriting(novelId, null, chapterId)
        val wfId = workflow(app, chapterId).workflowId
        val initialConfirmationKey = app.workflowRepository.listSteps(wfId)
            .single { it.phase == WorkflowStepPhase.CONFIRMATION }.logicalStepKey

        // 连续两次显式重开（P1-02g）：同一创作轮次的 Gate 决议轮
        val keys = mutableListOf<String>()
        repeat(2) {
            val gateId = assertNotNull(workflow(app, chapterId).pendingGateId)
            app.workflowService.rejectGate(gateId, HumanDecision.REJECTED)
            app.workflowFacade.advance(chapterId) // allowReentryAfterReject = true
            val confKeys = app.workflowRepository.listSteps(wfId)
                .filter { it.phase == WorkflowStepPhase.CONFIRMATION }.map { it.logicalStepKey }
            keys += confKeys
        }

        val allConfirmationKeys = app.workflowRepository.listSteps(wfId)
            .filter { it.phase == WorkflowStepPhase.CONFIRMATION }
        val keyList = allConfirmationKeys.map { it.logicalStepKey }
        assertEquals(keyList.size, keyList.toSet().size, "重开产生的 CONFIRMATION key 必须互不冲突：$keyList")
        assertTrue(keyList.contains(initialConfirmationKey), "初始 CONFIRMATION key 应保留")
        assertTrue(keyList.size >= 3, "应有 初始 + 两次重开 的 CONFIRMATION Step")
        // 重开**不**改变创作轮次：所有 CONFIRMATION 仍属轮 0
        assertTrue(allConfirmationKeys.all { (it.roundNo ?: 0) == 0 }, "重开不得推进创作轮次")
        // 重开不重新生成内容
        assertNull(app.canonicalRead.committedContent(chapterId))
    }

    /* ---------------- helpers ---------------- */

    private fun open(): Pair<ApplicationContainer, ScriptedGateway> {
        val gw = ScriptedGateway()
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

    private fun workflow(app: ApplicationContainer, chapterId: ChapterId) =
        assertNotNull(app.workflowRepository.getWorkflowByActiveChapter(chapterId), "应存在 Workflow")

    private fun projectId(app: ApplicationContainer, novelId: NovelId) = app.projects.projectOf(novelId).projectId

    private companion object {
        const val AI_BODY = "轮0 正文"
        const val NEW_BODY = "新轮 正文"
        const val REVISED_BODY = "修订正文"
    }
}
