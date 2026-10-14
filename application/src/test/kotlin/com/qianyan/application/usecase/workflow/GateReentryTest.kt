package com.qianyan.application.usecase.workflow

import com.qianyan.application.di.ApplicationContainer
import com.qianyan.application.error.ApplicationException
import com.qianyan.model.ChapterId
import com.qianyan.model.NovelId
import com.qianyan.model.commit.CommitErrorCodes
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
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P1-02g · 被拒绝 Gate 的重新进入语义（确定性；无 sleep / 无线程 / 无随机）。
 *
 * 领域决策（依据架构 §23C 与既有模型）：
 *  - **拒绝只针对"当前这一轮提案/Gate"**，不构成该章节未来所有尝试的永久拒绝；
 *  - 旧 Gate 行**不被改写、不被删除**（保留 `RESOLVED + REJECTED` 审计事实）；
 *  - 重新进入**不新建 Workflow**（章节 ↔ Workflow 保持一一对应，复用既有 PLANNING/WRITING 结果）；
 *    而是在**同一 Workflow 上开启新的决议轮次**（新 CONFIRMATION Step + 新 Gate）；
 *  - 重新进入**不重新生成内容**（不触发 Writer / Critique / Revision）——被拒绝的是"是否现在提交"，
 *    不是"内容作废"；因此新轮次复用既有工作产物；
 *  - 重新进入**只能由显式的新作者请求触发**（`ChapterWorkflowGateway.advance` = 显式推进/创作请求）；
 *    **只读查询与恢复路径**（`getChapterProgress` / `resume`）绝不创建新 Gate。
 */
class GateReentryTest {

    /** 计数网关：验证拒绝 / 重复恢复 / 重新进入路径均不额外调用 LLM。 */
    private class CountingGateway : LLMGateway {
        var plannerCalls = 0
        var writerCalls = 0
        var criticCalls = 0
        var revisionCalls = 0
        var kuCalls = 0

        fun snapshot(): List<Int> = listOf(plannerCalls, writerCalls, criticCalls, revisionCalls, kuCalls)

        override fun chat(request: ProviderRequest): ProviderResponse {
            val sys = request.messages.first { it.role == ChatRole.SYSTEM }.content
            val body = when {
                "StoryPlannerAgent" in sys -> { plannerCalls++; """{"chapterGoal":"推进主线"}""" }
                "StoryWriterAgent" in sys -> { writerCalls++; """{"content":"$AI_BODY"}""" }
                "StoryCriticAgent" in sys -> { criticCalls++; """{"passed":true}""" }
                "StoryRevisionAgent" in sys -> { revisionCalls++; """{"content":"$REVISED_BODY"}""" }
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

    /* ---------------- 1. 拒绝不改变 Canonical ---------------- */

    @Test
    fun `rejected gate leaves canonical unchanged`() {
        val (app, gw) = open()
        val (novelId, chapterId) = seed(app)
        driveToGate(app, novelId, chapterId)
        val gateId = reject(app, chapterId)
        val llmBefore = gw.snapshot()

        assertNull(app.canonicalRead.committedContent(chapterId), "拒绝不产生 Canonical")
        assertTrue(app.commits.history(projectId(app, novelId)).isEmpty(), "拒绝不写 CommitHistory")
        assertEquals(0, app.draftRepository.listByChapter(chapterId).size, "拒绝不写章节 Draft")
        assertEquals(HumanGateStatus.RESOLVED, app.workflowRepository.getGate(gateId)!!.status)
        assertEquals(HumanDecision.REJECTED, app.workflowRepository.getGate(gateId)!!.decision)
        assertEquals(llmBefore, gw.snapshot())
    }

    /* ---------------- 3/4. 显式请求开启新决议轮次 ---------------- */

    @Test
    fun `explicit author request reopens a new gate round without regenerating content`() {
        val (app, gw) = open()
        val (novelId, chapterId) = seed(app)
        driveToGate(app, novelId, chapterId)
        val oldGateId = reject(app, chapterId)
        val workingBefore = workingProducts(app, novelId, chapterId).single()
        val llmBefore = gw.snapshot()

        // 显式的新作者请求（advance = 显式推进/创作请求；生产入口 WriterGateway.continueWriting 亦走此路径）
        val progress = app.workflowFacade.advance(chapterId)

        assertTrue(progress.waitingForUser, "重新进入后应再次停在人工门")
        val newGateId = assertNotNull(
            app.workflowRepository.getWorkflowByActiveChapter(chapterId)?.pendingGateId,
            "应存在新的待决 Gate",
        )
        assertNotEquals(oldGateId, newGateId, "新流程不得复用旧 REJECTED Gate 作为当前待决 Gate")
        val newGate = app.workflowRepository.getGate(newGateId)!!
        assertEquals(HumanGateStatus.PENDING, newGate.status)
        assertEquals(HumanDecision.PENDING, newGate.decision)
        assertNull(newGate.draftId, "工作产物 Gate 仍不绑 DraftId")
        // 旧 Gate 保留原决议（不被改写）
        val oldGate = app.workflowRepository.getGate(oldGateId)!!
        assertEquals(HumanGateStatus.RESOLVED, oldGate.status)
        assertEquals(HumanDecision.REJECTED, oldGate.decision, "旧 Gate 决议不得被新流程改写")
        // 复用同一工作产物（未重新生成内容），且不调用 LLM
        assertEquals(workingBefore.workingDraftId, workingProducts(app, novelId, chapterId).single().workingDraftId)
        assertEquals(llmBefore, gw.snapshot(), "重新进入不得触发 LLM")
        // 仍只有一条 CommitHistory（拒绝期间无提交）
        assertTrue(app.commits.history(projectId(app, novelId)).isEmpty())
    }

    /* ---------------- 5. 重复显式请求幂等 ---------------- */

    @Test
    fun `repeated explicit requests do not duplicate workflows or gates`() {
        val (app, gw) = open()
        val (novelId, chapterId) = seed(app)
        driveToGate(app, novelId, chapterId)
        reject(app, chapterId)
        val wfId = workflow(app, chapterId).workflowId

        app.workflowFacade.advance(chapterId)
        val gateAfterFirst = assertNotNull(workflow(app, chapterId).pendingGateId)
        val stepsAfterFirst = app.workflowRepository.listSteps(wfId).count { it.phase == WorkflowStepPhase.CONFIRMATION }
        val llmAfterFirst = gw.snapshot()

        repeat(3) { app.workflowFacade.advance(chapterId) }

        assertEquals(wfId, workflow(app, chapterId).workflowId, "不得新建 Workflow")
        assertEquals(gateAfterFirst, workflow(app, chapterId).pendingGateId, "重复请求复用同一新 Gate（幂等）")
        assertEquals(
            stepsAfterFirst,
            app.workflowRepository.listSteps(wfId).count { it.phase == WorkflowStepPhase.CONFIRMATION },
            "不得累积新的 CONFIRMATION 轮次",
        )
        assertEquals(llmAfterFirst, gw.snapshot(), "重复请求不得触发 LLM")
        assertEquals(app.workflowRepository.getGate(gateAfterFirst)!!.status, HumanGateStatus.PENDING)
    }

    /* ---------------- 9. 只读查询 / 恢复路径绝不重开 Gate ---------------- */

    @Test
    fun `read and recovery paths never reopen the gate`() {
        val (app, gw) = open()
        val (novelId, chapterId) = seed(app)
        driveToGate(app, novelId, chapterId)
        val gateId = reject(app, chapterId)
        val wfBefore = workflow(app, chapterId)
        val stepsBefore = app.workflowRepository.listSteps(wfBefore.workflowId).size
        val llmBefore = gw.snapshot()

        repeat(2) { app.workflowFacade.getChapterProgress(chapterId) } // UI 查询（只读）
        app.workflowFacade.resume(chapterId)                            // 恢复路径
        app.workflowRepository.getWorkflowByActiveChapter(chapterId)    // 直接读取

        assertEquals(gateId, workflow(app, chapterId).pendingGateId, "只读/恢复不得改写 pendingGateId")
        assertEquals(
            HumanDecision.REJECTED,
            app.workflowRepository.getGate(gateId)!!.decision,
            "只读/恢复不得改写 Gate 决议",
        )
        assertEquals(stepsBefore, app.workflowRepository.listSteps(wfBefore.workflowId).size, "不得创建新 Step")
        assertEquals(WorkflowStatus.WAITING_HUMAN, workflow(app, chapterId).status, "不得自动推进")
        assertEquals(llmBefore, gw.snapshot(), "只读/恢复不得触发 LLM")
        assertNull(app.canonicalRead.committedContent(chapterId))
    }

    /* ---------------- 7. 新轮次的批准仍走既有全链路 ---------------- */

    @Test
    fun `approving the reopened gate still goes through change artifact policy and commit`() {
        val (app, gw) = open()
        val (novelId, chapterId) = seed(app)
        driveToGate(app, novelId, chapterId)
        val oldGateId = reject(app, chapterId)
        app.workflowFacade.advance(chapterId)
        val newGateId = assertNotNull(workflow(app, chapterId).pendingGateId)

        app.workflowFacade.approve(chapterId)

        assertEquals(AI_BODY, app.canonicalRead.committedContent(chapterId), "新轮次批准产生 Canonical")
        assertEquals(1, app.commits.history(projectId(app, novelId)).size, "只产生一条 Commit")
        assertEquals(HumanDecision.REJECTED, app.workflowRepository.getGate(oldGateId)!!.decision, "旧 Gate 决议仍不被改写")
        assertEquals(1, app.draftRepository.listByChapter(chapterId).size, "只产生一个 Canonical 载体")
        // 走完后续阶段
        assertEquals(ChapterPhase.COMPLETED, app.workflowFacade.advance(chapterId).phase)
        assertTrue(app.storyWorldContextResolver.resolve(novelId).memories.any { it.contains("已突破金丹期") })
        assertEquals(newGateId, app.workflowRepository.getGate(newGateId)!!.gateId)
    }

    /* ---------------- 6. 旧工作产物不可提交时明确失败 ---------------- */

    @Test
    fun `discarded old work product cannot be committed through the reopened gate`() {
        val (app, gw) = open()
        val (novelId, chapterId) = seed(app)
        driveToGate(app, novelId, chapterId)
        reject(app, chapterId)
        val working = workingProducts(app, novelId, chapterId).single()
        app.workingDrafts.discard(working.workingDraftId) // 旧工作产物已不可用
        val llmBefore = gw.snapshot()

        app.workflowFacade.advance(chapterId) // 新轮次（Gate 对象仍是新实例）
        val error = assertFailsWith<ApplicationException> { app.workflowFacade.approve(chapterId) }

        // 已丢弃的工作产物不可提交：`CommitUseCases.validateForCommit` 的终态 Working Draft 检查先于 Artifact 状态检查，
        // 因此确定性失败码为 DISCARDED_WORKING_DRAFT（同为类型化拒绝；关键是"不得误用旧内容提交"）。
        assertTrue(
            error.message.orEmpty().contains(CommitErrorCodes.DISCARDED_WORKING_DRAFT),
            "不可用的旧工作产物不得被提交（应类型化拒绝）：${error.message}",
        )
        assertNull(app.canonicalRead.committedContent(chapterId), "不得误用旧内容写入 Canonical")
        assertTrue(app.commits.history(projectId(app, novelId)).isEmpty(), "不得写 CommitHistory")
        assertEquals(llmBefore, gw.snapshot(), "不得触发 LLM")
    }

    /* ---------------- helpers ---------------- */

    private fun open(): Pair<ApplicationContainer, CountingGateway> {
        val gw = CountingGateway()
        return ApplicationContainer.open(analysisGateway = gw) to gw
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

    /** 拒绝当前待决 Gate（既有 WorkflowService 决议入口）。 */
    private fun reject(app: ApplicationContainer, chapterId: ChapterId): com.qianyan.model.workflow.WorkflowHumanGateId {
        val gateId = assertNotNull(workflow(app, chapterId).pendingGateId)
        app.workflowService.rejectGate(gateId, HumanDecision.REJECTED)
        return gateId
    }

    private fun workflow(app: ApplicationContainer, chapterId: ChapterId) =
        assertNotNull(app.workflowRepository.getWorkflowByActiveChapter(chapterId), "应存在 Workflow")

    private fun projectId(app: ApplicationContainer, novelId: NovelId) = app.projects.projectOf(novelId).projectId

    private fun workingProducts(app: ApplicationContainer, novelId: NovelId, chapterId: ChapterId) =
        app.workingDrafts.list(projectId(app, novelId)).filter { it.target.chapterId == chapterId }

    private companion object {
        const val AI_BODY = "AI 待审正文"
        const val REVISED_BODY = "AI 修订正文"
    }
}
