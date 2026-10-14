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
 * P1-02j.2-F · 修订预算补测（二）：**至少三轮连续作废**（真实状态机驱动）。
 *
 * 每轮 critic 脚本化为"前 3 次 REVISION、第 4 次 PASS"⇒ 每轮用满轮内预算 3 并到达人工门；
 * 拒绝 → 关容器重开（工作产物失效）→ `abandonRound` → `advance` 驱动新一轮。共 3 轮。
 *
 * 覆盖：roundNo 严格递增 / 每轮预算独立 / 旧 Step·Gate·Attempt 保留 / key 全局唯一 / 不提前 FAILED / 最终 COMPLETED。
 */
class ConsecutiveAbandonRoundsTest {

    /** critic 决策按**队列**消费（每轮 F,F,F,T）；writer/revision 内容按调用次数区分轮次。 */
    private class ScriptedGateway : LLMGateway {
        val criticQueue = ArrayDeque<Boolean>()
        var plannerCalls = 0
        var writerCalls = 0
        var criticCalls = 0
        var revisionCalls = 0
        var kuCalls = 0

        override fun chat(request: ProviderRequest): ProviderResponse {
            val sys = request.messages.first { it.role == ChatRole.SYSTEM }.content
            val body = when {
                "StoryPlannerAgent" in sys -> { plannerCalls++; """{"chapterGoal":"推进主线"}""" }
                "StoryWriterAgent" in sys -> { writerCalls++; """{"content":"第${writerCalls}轮 正文"}""" }
                "StoryCriticAgent" in sys -> {
                    criticCalls++
                    val passed = criticQueue.removeFirst()
                    """{"passed":$passed}"""
                }
                "StoryRevisionAgent" in sys -> { revisionCalls++; """{"content":"修订正文-$revisionCalls"}""" }
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

    /**
     * 三轮连续作废（缺陷验收测试）。
     *
     * 该测试最初在 P1-02j.2-F 复现了真实缺陷：第二个 `abandonRound` 后第三个创作轮次停滞 ——
     * 轮 1 的固定阶段 key `CRITIQUE:R7` 与轮 2 的 revision 后继 critique key `CRITIQUE:R7`
     * （REVISION 跨轮累计序号）**命名空间重叠**，`createStepIfMissingKey` 静默 no-op，轮 2 缺 CRITIQUE Step。
     *
     * 修复（P1-02j.2-G）：后续创作/决议轮的**固定阶段**后缀改用独立命名空间 `:W<n>`（`phaseKey`），
     * 与 revision 序号 `:R<n>` 严格分离 ⇒ 不再冲突。本测试现为**通过**的验收测试。
     */
    @Test
    fun `three consecutive abandon rounds keep budgets independent and numbering strictly increasing`() {
        val tmp = java.nio.file.Files.createTempFile("qianyan-3rounds", ".db").toString()
        val url = "jdbc:sqlite:$tmp"
        val handles = mutableListOf<com.qianyan.storage.db.QianyanDbHandle>()
        try {
            val gw = ScriptedGateway()
            // 3 轮 × (F,F,F,T)
            repeat(3) {
                gw.criticQueue.addAll(listOf(false, false, false, true))
            }
            val app0 = openFile(url, handles, gw)
            val novelId = app0.novels.createOriginal(title = "三轮作废测试书")
            val chapter = app0.chapters.createNextChapter(title = "第一章", novelId = novelId)
            val chapterId = chapter.chapterId

            // --- 轮 0 ---
            app0.writerGateway.continueWriting(novelId, null, chapterId)
            assertTrue(app0.workflowFacade.getChapterProgress(chapterId).waitingForUser, "轮 0 应停在人工门")
            assertEquals(3, gw.revisionCalls, "轮 0 用满 3 次修订")
            val wf0 = assertNotNull(workflowId(app0, chapterId))
            val r0Steps = app0.workflowRepository.listSteps(wf0).map { it.stepId }.toSet()
            val gate0 = assertNotNull(app0.workflowRepository.getWorkflow(wf0)!!.pendingGateId)
            val gate0Key = app0.workflowRepository.getGate(gate0)!!.gateKey

            var app = reopenAfterReject(app0, chapterId, gate0, handles, gw, url)
            val out1 = app.workflowOrchestrator.abandonRound(gate0)
            val round1 = (out1 as AbandonRoundOutcome.Abandoned).round
            app.workflowFacade.advance(chapterId)
            assertTrue(app.workflowFacade.getChapterProgress(chapterId).waitingForUser, "轮 1 应停在人工门")
            assertEquals(6, gw.revisionCalls, "轮 1 应再用满 3 次修订（累计 6）")
            val gate1 = assertNotNull(app.workflowRepository.getWorkflow(wf0)!!.pendingGateId)

            app = reopenAfterReject(app, chapterId, gate1, handles, gw, url)
            val out2 = app.workflowOrchestrator.abandonRound(gate1)
            val round2 = (out2 as AbandonRoundOutcome.Abandoned).round
            app.workflowFacade.advance(chapterId)
            assertTrue(app.workflowFacade.getChapterProgress(chapterId).waitingForUser, "轮 2 应停在人工门")
            assertEquals(9, gw.revisionCalls, "轮 2 应再用满 3 次修订（累计 9）")
            val gate2 = assertNotNull(app.workflowRepository.getWorkflow(wf0)!!.pendingGateId)

            // --- 断言：编号严格递增；每轮预算独立；旧历史保留；key 唯一 ---
            assertTrue(round1 in 1 until round2, "roundNo 应严格递增：0 < $round1 < $round2")
            val writings = app.workflowRepository.listSteps(wf0).filter { it.phase == WorkflowStepPhase.WRITING }
            assertEquals(3, writings.size, "应有 3 个 WRITING Step（每轮一个）")
            assertEquals(listOf(0, round1, round2), writings.map { it.roundNo ?: 0 }.sorted(), "三代 WRITING 的 roundNo 应为 0 < g1 < g2")

            val revisions = app.workflowRepository.listSteps(wf0).filter { it.phase == WorkflowStepPhase.REVISION }
            assertEquals(9, revisions.size, "3 轮 × 3 = 9 个 REVISION Step")
            assertEquals(3, revisions.count { (it.roundNo ?: 0) == 0 }, "轮 0 恰好 3 个修订")
            assertEquals(3, revisions.count { (it.roundNo ?: 0) == round1 }, "轮 1 恰好 3 个修订")
            assertEquals(3, revisions.count { (it.roundNo ?: 0) == round2 }, "轮 2 恰好 3 个修订")

            val allKeys = app.workflowRepository.listSteps(wf0).map { it.logicalStepKey }
            assertEquals(allKeys.size, allKeys.toSet().size, "全部 logicalStepKey 必须唯一")
            assertTrue(app.workflowRepository.listSteps(wf0).map { it.stepId }.containsAll(r0Steps), "轮 0 的历史 Step 必须保留")

            // 旧 Gate 保持 REJECTED；三轮各自不同 Gate；不因历史预算提前 FAILED
            assertNotEquals(gate0, gate1); assertNotEquals(gate1, gate2); assertNotEquals(gate0, gate2)
            assertEquals(HumanDecision.REJECTED, app.workflowRepository.getGate(gate0)!!.decision)
            assertEquals(HumanDecision.REJECTED, app.workflowRepository.getGate(gate1)!!.decision)
            assertEquals(HumanGateStatus.PENDING, app.workflowRepository.getGate(gate2)!!.status, "轮 2 的 Gate 应待决（未被提前判定 FAILED）")
            assertEquals(gate0Key, app.workflowRepository.getGate(gate0)!!.gateKey, "旧 Gate 的 gateKey 不得被改写")
            assertEquals(WorkflowStatus.WAITING_HUMAN, app.workflowRepository.getWorkflow(wf0)!!.status)

            // --- 批准轮 2 → 唯一 Commit → Canonical 来自轮 2 → COMPLETED ---
            val projectId = app.projects.projectOf(novelId).projectId
            app.workflowFacade.approve(chapterId)
            assertEquals("修订正文-9", app.canonicalRead.committedContent(chapterId), "Canonical 应来自轮 2 的最终产物")
            assertEquals(1, app.commits.history(projectId).size, "只应发生一次 Commit")
            assertEquals(gate2, app.commits.committedByGate(gate2)?.gateId, "轮 2 的 Gate 应关联该唯一提交")
            assertNull(app.commits.committedByGate(gate0), "轮 0 的 Gate 不得关联提交")
            assertNull(app.commits.committedByGate(gate1), "轮 1 的 Gate 不得关联提交")
            assertEquals(ChapterPhase.COMPLETED, app.workflowFacade.advance(chapterId).phase, "最终应 COMPLETED")

            // LLM 调用总量：planner1 / writer3 / critic12 / revision9 / ku1
            assertEquals(1, gw.plannerCalls)
            assertEquals(3, gw.writerCalls)
            assertEquals(12, gw.criticCalls)
            assertEquals(9, gw.revisionCalls)
            assertEquals(1, gw.kuCalls)
        } finally {
            closeAll(handles)
            java.nio.file.Files.deleteIfExists(java.nio.file.Path.of(tmp))
        }
    }

    /* ---------------- helpers ---------------- */

    /** 拒绝当前 Gate → 关容器 → 以同库新容器重开（工作产物失效），返回新容器。 */
    private fun reopenAfterReject(
        app: ApplicationContainer,
        chapterId: ChapterId,
        gateId: com.qianyan.model.workflow.WorkflowHumanGateId,
        handles: MutableList<com.qianyan.storage.db.QianyanDbHandle>,
        gateway: LLMGateway,
        url: String,
    ): ApplicationContainer {
        app.workflowService.rejectGate(gateId, HumanDecision.REJECTED)
        assertEquals(HumanDecision.REJECTED, app.workflowRepository.getGate(gateId)!!.decision, "前置：Gate 应已拒绝")
        closeAll(handles)
        return openFile(url, handles, gateway)
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

    private fun workflowId(app: ApplicationContainer, chapterId: ChapterId) =
        app.workflowRepository.getWorkflowByActiveChapter(chapterId)?.workflowId
}
