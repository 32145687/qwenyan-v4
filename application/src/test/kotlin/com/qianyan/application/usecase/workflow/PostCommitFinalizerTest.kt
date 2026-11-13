package com.qianyan.application.usecase.workflow

import com.qianyan.application.di.ApplicationContainer
import com.qianyan.model.ChapterId
import com.qianyan.model.NovelId
import com.qianyan.model.task.TaskStatus
import com.qianyan.model.task.TaskType
import com.qianyan.model.workflow.HumanGateStatus
import com.qianyan.model.workflow.WorkflowStatus
import com.qianyan.model.workflow.WorkflowStepPhase
import com.qianyan.provider.ChatMessage
import com.qianyan.provider.ChatRole
import com.qianyan.provider.FinishReason
import com.qianyan.provider.LLMGateway
import com.qianyan.provider.ProviderResponse
import com.qianyan.provider.Usage
import com.qianyan.provider.impl.MockLLMGateway
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T-O108-F2（A-32）· 批准快路径 + WMU/KU 后台化。
 *
 * 断言：
 *  1. `approveDecision` 只做「决议 + Canonical 提交 + 投递 BACKGROUND 任务」，**不推进** runForward；
 *  2. 后台 finalizer 按 WMU → runForward 顺序执行，成功后 Workflow COMPLETED、Task COMPLETED；
 *  3. 幂等：重复批准只 1 条 Commit、同 Gate 只有 1 个未完成任务；
 *  4. 失败可见：WMU / KU 失败都落 Task FAILED，正文仍为已提交版本；
 *  5. 启动恢复：`resumeOnStartup` 幂等，不重复 LLM。
 */
class PostCommitFinalizerTest {

    private val wmuCalls = AtomicInteger()
    private val kuCalls = AtomicInteger()
    private val failWorldModel = AtomicInteger(0)
    private val failKnowledgeUpdate = AtomicInteger(0)

    private fun gateway(): LLMGateway = MockLLMGateway { req ->
        val sys = req.messages.firstOrNull { it.role == ChatRole.SYSTEM }?.content.orEmpty()
        val user = req.messages.firstOrNull { it.role == ChatRole.USER }?.content.orEmpty()
        val isWorldModel = "提取候选世界事实" in user
        val isKnowledgeUpdate = "KnowledgeUpdateAgent" in sys
        if (isWorldModel) {
            wmuCalls.incrementAndGet()
            if (failWorldModel.get() > 0) error("WMU boom")
        }
        if (isKnowledgeUpdate) {
            kuCalls.incrementAndGet()
            if (failKnowledgeUpdate.get() > 0) error("KU boom")
        }
        val body = when {
            "StoryPlannerAgent" in sys -> """{"chapterGoal":"揭开秘辛","mainConflict":"c","expectedEvents":["遇险"]}"""
            "StoryWriterAgent" in sys -> """{"content":"$AI_BODY"}"""
            "StoryCriticAgent" in sys -> """{"passed":true}"""
            isKnowledgeUpdate -> """{"changes":[{"changeId":"k1","operation":"ADD","target":"主角","content":"$KU_FACT"}]}"""
            isWorldModel -> """{"facts":[{"factId":"f1","kind":"EVENT","operation":"ADD","name":"雨夜奇遇","summary":"主角在雨夜遇到异样","excerpt":"雪是她提灯走进这条巷子时落下来的","reason":"正文"}]}"""
            else -> """{"content":"占位"}"""
        }
        ProviderResponse(
            message = ChatMessage(ChatRole.ASSISTANT, buildJsonObject { put("answer", body) }.toString()),
            usage = Usage(10, 10, 20),
            finishReason = FinishReason.STOP,
        )
    }

    private fun open(): ApplicationContainer = ApplicationContainer.open(analysisGateway = gateway())

    private fun seed(app: ApplicationContainer): Pair<NovelId, ChapterId> {
        val novelId = app.novels.createOriginal(title = "T-O108-F2 测试书")
        val chapter = app.chapters.createNextChapter(title = "第一章", novelId = novelId)
        return novelId to chapter.chapterId
    }

    private fun driveToGate(app: ApplicationContainer, novelId: NovelId, chapterId: ChapterId) {
        wmuCalls.set(0) // 只统计批准后的后台调用
        app.writerGateway.continueWriting(novelId, null, chapterId)
        assertTrue(app.workflowFacade.getChapterProgress(chapterId).waitingForUser, "前置：应停在人工门")
    }

    private fun workflow(app: ApplicationContainer, chapterId: ChapterId) =
        assertNotNull(app.workflowRepository.getWorkflowByActiveChapter(chapterId), "应存在 Workflow")

    private fun projectId(app: ApplicationContainer, novelId: NovelId) = app.projects.projectOf(novelId).projectId

    /** 后台任务（按 POST_COMMIT checkpoint 定位；只取分配给本步骤的 BACKGROUND）。 */
    private fun backgroundTasks(app: ApplicationContainer) =
        app.taskRepository.findCheckpointsByStage(PostCommitFinalizer.STAGE, 200)
            .map { it.taskId }
            .distinct()
            .mapNotNull { app.taskRepository.findById(it) }

    // ---------- 1. 快路径 ----------

    @Test
    fun `approve decision commits and enqueues without advancing workflow`() {
        val app = open()
        val (novelId, chapterId) = seed(app)
        driveToGate(app, novelId, chapterId)
        val gateId = assertNotNull(workflow(app, chapterId).pendingGateId)

        val progress = app.workflowFacade.approveDecision(chapterId)

        val wf = workflow(app, chapterId)
        assertEquals(WorkflowStatus.RUNNING, wf.status, "快路径不得推进到 COMPLETED")
        assertEquals(HumanGateStatus.RESOLVED, app.workflowRepository.getGate(gateId)!!.status, "Gate 必须已决议")
        assertEquals(1, app.commits.history(projectId(app, novelId)).size, "Canonical 提交必须在快路径完成")
        assertEquals(0, wmuCalls.get(), "快路径不得调用 WMU")
        assertEquals(0, kuCalls.get(), "快路径不得调用 KU")

        val tasks = backgroundTasks(app)
        assertEquals(1, tasks.size, "必须投递 1 个 BACKGROUND 任务")
        assertEquals(TaskStatus.PENDING, tasks.single().status)
        assertEquals(TaskType.BACKGROUND, tasks.single().type)

        assertNull(
            app.workflowRepository.listSteps(wf.workflowId).firstOrNull { it.phase == WorkflowStepPhase.KNOWLEDGE_UPDATE },
            "快路径不得创建 KU 步骤",
        )
        assertEquals(ChapterPhase.WAITING_CONFIRMATION, progress.phase, "决议后仍停在收尾步骤（投影为等待确认）")
    }

    // ---------- 2. 后台执行顺序与终态 ----------

    @Test
    fun `finalizer runs world model before forwarding and completes workflow`() {
        val app = open()
        val (novelId, chapterId) = seed(app)
        driveToGate(app, novelId, chapterId)
        app.workflowFacade.approveDecision(chapterId)

        val ran = app.postCommitFinalizer.resumeOnStartup()

        assertEquals(1, ran, "应有 1 个待恢复任务")
        assertEquals(1, wmuCalls.get(), "后台必须先跑 WMU")
        assertEquals(1, kuCalls.get(), "随后跑 KU")
        val wf = workflow(app, chapterId)
        assertEquals(WorkflowStatus.COMPLETED, wf.status)
        assertNull(wf.pendingGateId)
        assertEquals(TaskStatus.COMPLETED, backgroundTasks(app).single().status)
        assertEquals(
            WorkflowStepPhase.KNOWLEDGE_UPDATE,
            app.workflowRepository.listSteps(wf.workflowId).last { it.status == com.qianyan.model.workflow.WorkflowStepStatus.COMPLETED }.phase,
            "末个已完成步骤应为 KU",
        )
    }

    // ---------- 3. 幂等投递 ----------

    @Test
    fun `enqueue twice reuses one pending task`() {
        val app = open()
        val (novelId, chapterId) = seed(app)
        driveToGate(app, novelId, chapterId)
        app.workflowFacade.approveDecision(chapterId)

        app.workflowFacade.ensurePostCommitJob(chapterId)
        app.workflowFacade.ensurePostCommitJob(chapterId)

        assertEquals(1, backgroundTasks(app).size, "同一 Gate 重复投递只能有 1 个未完成任务")
    }

    // ---------- 4. 重复批准 ----------

    @Test
    fun `repeated approve decision commits once`() {
        val app = open()
        val (novelId, chapterId) = seed(app)
        driveToGate(app, novelId, chapterId)

        app.workflowFacade.approveDecision(chapterId)
        app.workflowFacade.approveDecision(chapterId)

        assertEquals(1, app.commits.history(projectId(app, novelId)).size, "重复批准只允许 1 条 Commit")
        assertEquals(1, backgroundTasks(app).size, "重复批准只允许 1 个后台任务")
        assertEquals(0, wmuCalls.get())
    }

    // ---------- 5. WMU 失败 ----------

    @Test
    fun `world model failure fails task and keeps committed body`() {
        val app = open()
        val (novelId, chapterId) = seed(app)
        driveToGate(app, novelId, chapterId)
        app.workflowFacade.approveDecision(chapterId)
        failWorldModel.set(1)

        app.postCommitFinalizer.resumeOnStartup()

        val task = backgroundTasks(app).single()
        assertEquals(TaskStatus.FAILED, task.status, "WMU 失败必须落 Task FAILED")
        assertNotNull(task.error, "失败原因必须可见")
        assertEquals(WorkflowStatus.RUNNING, workflow(app, chapterId).status, "WMU 失败不得推进 Workflow")
        val committed = app.commits.history(projectId(app, novelId))
        assertEquals(1, committed.size, "正文必须已在快路径提交（CommitHistory 已落库）")
        assertNotNull(committed.single().resultingContent, "提交内容必须可读")
    }

    // ---------- 6. KU 失败 ----------

    @Test
    fun `knowledge update failure is visible and retryable`() {
        val app = open()
        val (novelId, chapterId) = seed(app)
        driveToGate(app, novelId, chapterId)
        app.workflowFacade.approveDecision(chapterId)
        failKnowledgeUpdate.set(1)

        app.postCommitFinalizer.resumeOnStartup()

        val task = backgroundTasks(app).single()
        assertEquals(TaskStatus.FAILED, task.status, "KU 失败必须落 Task FAILED")
        assertNotNull(task.error)
        // 现状：KU 抛错不会被 runForward 收敛为 Workflow.FAILED（Step 保持待执行）——正因如此重试可以续跑同一步。
        assertEquals(WorkflowStatus.RUNNING, workflow(app, chapterId).status, "KU 失败后 Workflow 仍 RUNNING（可重试）")
        assertEquals(
            PostCommitStage.FAILED,
            app.workflowFacade.postCommitState(chapterId),
            "UI 必须能投影出「后台更新失败」",
        )
        // 重试：新建任务并执行 → 清掉故障后应真正完成（不是伪装成功）。
        failKnowledgeUpdate.set(0)
        app.workflowFacade.retryPostCommit(chapterId)
        assertEquals(2, backgroundTasks(app).size, "重试必须新建一个后台任务（不复用 FAILED）")
        assertEquals(WorkflowStatus.COMPLETED, workflow(app, chapterId).status, "重试成功应完成 Workflow")
        assertEquals(PostCommitStage.NONE, app.workflowFacade.postCommitState(chapterId), "完成后不再显示后台更新中/失败")
    }

    // ---------- 7. 启动恢复幂等 ----------

    @Test
    fun `resume on startup is idempotent and does not repeat llm`() {
        val app = open()
        val (novelId, chapterId) = seed(app)
        driveToGate(app, novelId, chapterId)
        app.workflowFacade.approveDecision(chapterId)

        assertEquals(1, app.postCommitFinalizer.resumeOnStartup())
        val wmuAfter = wmuCalls.get()
        val kuAfter = kuCalls.get()
        assertEquals(0, app.postCommitFinalizer.resumeOnStartup(), "已完成任务不得被再次扫描")
        assertEquals(wmuAfter, wmuCalls.get(), "恢复不得重复 WMU")
        assertEquals(kuAfter, kuCalls.get(), "恢复不得重复 KU")
        assertEquals(WorkflowStatus.COMPLETED, workflow(app, chapterId).status)
    }

    // ---------- 8. 完成后不再投递 ----------

    @Test
    fun `ensure post commit job is a no op after completion`() {
        val app = open()
        val (novelId, chapterId) = seed(app)
        driveToGate(app, novelId, chapterId)
        app.workflowFacade.approveDecision(chapterId)
        app.postCommitFinalizer.resumeOnStartup()
        val before = backgroundTasks(app).size

        app.workflowFacade.ensurePostCommitJob(chapterId)

        assertEquals(before, backgroundTasks(app).size, "已完成工作流不得再投递任务")
        assertFalse(app.workflowFacade.postCommitState(chapterId) == PostCommitStage.PENDING)
    }

    private companion object {
        const val AI_BODY = "雪是她提灯走进这条巷子时落下来的，很碎，落在灯笼上没有声音。她把灯压低，光只照见脚下三尺青石。"
        const val KU_FACT = "主角在雨夜提灯行走"
    }
}
