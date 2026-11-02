package com.qianyan.application.usecase.workflow

import com.qianyan.application.di.ApplicationContainer
import com.qianyan.model.ChapterId
import com.qianyan.model.NovelId
import com.qianyan.model.workflow.HumanGateStatus
import com.qianyan.model.workflow.WorkflowStatus
import com.qianyan.model.workflow.WorkflowStepPhase
import com.qianyan.model.workflow.WorkflowStepStatus
import com.qianyan.provider.ChatMessage
import com.qianyan.provider.ChatRole
import com.qianyan.provider.FinishReason
import com.qianyan.provider.LLMGateway
import com.qianyan.provider.ProviderResponse
import com.qianyan.provider.Usage
import com.qianyan.provider.impl.MockLLMGateway
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T-O52-FIX · 批准即收尾（O-52 缺陷回归）。
 *
 * 缺陷复现（T-O52，novel bb86b38b / workflow 8a6ed1c1）：批准后 77ms 内 `WAITING_HUMAN → RUNNING`
 * 且写入 1 条 Commit，但 `CONFIRMATION` 步骤停在 `PENDING`、`pending_gate_id` 未清空、`updated_at` 冻结，
 * workflow 永久 `RUNNING`；被动重绘 / 切页不补齐。
 *
 * 本测试断言修复后的生产语义：`ChapterWorkflowFacade.approve` 在**同一次调用内**完成
 * CONFIRMATION → 建并执行 KNOWLEDGE_UPDATE → `COMPLETED`，且幂等（重复批准 / 恢复不产生第 2 条 Commit、不新建 Gate）。
 */
class ApproveGateCompletesWorkflowTest {

    private fun okGateway(): LLMGateway = MockLLMGateway { req ->
        val sys = req.messages.first { it.role == ChatRole.SYSTEM }.content
        val body = when {
            "StoryPlannerAgent" in sys -> """{"chapterGoal":"揭开秘辛","mainConflict":"c","expectedEvents":["遇险"]}"""
            "StoryWriterAgent" in sys -> """{"content":"$AI_BODY"}"""
            "StoryCriticAgent" in sys -> """{"passed":true}"""
            "KnowledgeUpdateAgent" in sys || "StoryKnowledgeUpdateAgent" in sys ->
                """{"changes":[{"changeId":"k1","operation":"ADD","target":"主角","content":"$KU_FACT"}]}"""
            else -> """{"content":"占位"}"""
        }
        ProviderResponse(
            message = ChatMessage(ChatRole.ASSISTANT, buildJsonObject { put("answer", body) }.toString()),
            usage = Usage(10, 10, 20),
            finishReason = FinishReason.STOP,
        )
    }

    private fun open(): ApplicationContainer = ApplicationContainer.open(analysisGateway = okGateway())

    private fun seed(app: ApplicationContainer): Pair<NovelId, ChapterId> {
        val novelId = app.novels.createOriginal(title = "T-O52-FIX 测试书")
        val chapter = app.chapters.createNextChapter(title = "第一章", novelId = novelId)
        return novelId to chapter.chapterId
    }

    private fun driveToGate(app: ApplicationContainer, novelId: NovelId, chapterId: ChapterId) {
        app.writerGateway.continueWriting(novelId, null, chapterId)
        assertTrue(app.workflowFacade.getChapterProgress(chapterId).waitingForUser, "前置：应停在人工门")
    }

    private fun workflow(app: ApplicationContainer, chapterId: ChapterId) =
        assertNotNull(app.workflowRepository.getWorkflowByActiveChapter(chapterId), "应存在 Workflow")

    private fun projectId(app: ApplicationContainer, novelId: NovelId) =
        app.projects.projectOf(novelId).projectId

    /** 主判定：approve 一次即收尾 —— COMPLETED + CONFIRMATION COMPLETED + KU 存在且 COMPLETED + pendingGateId=null + Commit 恰 1。 */
    @Test
    fun `approve closes confirmation and completes workflow`() {
        val app = open()
        val (novelId, chapterId) = seed(app)
        driveToGate(app, novelId, chapterId)
        val gateId = assertNotNull(workflow(app, chapterId).pendingGateId, "前置：存在待决 Gate")
        assertEquals(HumanGateStatus.PENDING, app.workflowRepository.getGate(gateId)!!.status)

        val progress = app.workflowFacade.approve(chapterId)

        val wf = workflow(app, chapterId)
        assertEquals(WorkflowStatus.COMPLETED, wf.status, "批准后必须收尾到 COMPLETED（不得停留 RUNNING）")
        assertNull(wf.pendingGateId, "收尾后不得残留 pendingGateId")
        assertNull(wf.currentStepId, "收尾后不得残留 currentStepId")
        assertEquals(ChapterPhase.COMPLETED, progress.phase, "用户层投影必须为 COMPLETED")

        val steps = app.workflowRepository.listSteps(wf.workflowId)
        val confirmation = steps.single { it.phase == WorkflowStepPhase.CONFIRMATION }
        assertEquals(WorkflowStepStatus.COMPLETED, confirmation.status, "CONFIRMATION 必须 COMPLETED")
        val ku = steps.single { it.phase == WorkflowStepPhase.KNOWLEDGE_UPDATE }
        assertEquals(WorkflowStepStatus.COMPLETED, ku.status, "KNOWLEDGE_UPDATE 必须被创建并 COMPLETED")

        assertEquals(1, app.commits.history(projectId(app, novelId)).size, "Commit 恰 1 条")
        assertEquals(HumanGateStatus.RESOLVED, app.workflowRepository.getGate(gateId)!!.status)
    }

    /** 幂等：重复 approve（双击第二击 / 重复恢复）+ resume 不产生第 2 条 Commit、不新建 Gate、不重复 KU。 */
    @Test
    fun `repeated approve and resume stay idempotent`() {
        val app = open()
        val (novelId, chapterId) = seed(app)
        driveToGate(app, novelId, chapterId)
        val gateBefore = app.workflowRepository.getGate(assertNotNull(workflow(app, chapterId).pendingGateId))!!

        app.workflowFacade.approve(chapterId)
        val historyAfterFirst = app.commits.history(projectId(app, novelId)).size

        repeat(3) { app.workflowFacade.approve(chapterId) }
        app.workflowFacade.resume(chapterId)

        val wf = workflow(app, chapterId)
        assertEquals(WorkflowStatus.COMPLETED, wf.status, "重复调用不得破坏已完成状态")
        assertNull(wf.pendingGateId)
        assertEquals(historyAfterFirst, app.commits.history(projectId(app, novelId)).size, "重复调用不得新增 Commit")
        assertEquals(1, app.commits.history(projectId(app, novelId)).size)
        assertEquals(
            gateBefore.gateId,
            app.workflowRepository.getGateByKey(gateBefore.gateKey)!!.gateId,
            "Gate 身份不变（gateKey 幂等，不新建 Gate）",
        )
        val steps = app.workflowRepository.listSteps(wf.workflowId)
        assertEquals(1, steps.count { it.phase == WorkflowStepPhase.CONFIRMATION }, "不得新建 CONFIRMATION 轮次")
        assertEquals(1, steps.count { it.phase == WorkflowStepPhase.KNOWLEDGE_UPDATE }, "不得重复创建 KU 步骤")
        assertEquals(1, app.draftRepository.listByChapter(chapterId).size, "不得新增载体 Draft")
        assertEquals(
            1,
            app.storyWorldContextResolver.resolve(novelId).memories.count { it.contains(KU_FACT) },
            "KU 事实只沉淀一次",
        )
    }

    private companion object {
        const val AI_BODY = "AI 待审正文"
        const val KU_FACT = "已突破金丹期"
    }
}
