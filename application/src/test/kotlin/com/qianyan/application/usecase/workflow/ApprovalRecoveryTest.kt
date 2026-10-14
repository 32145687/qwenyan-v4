package com.qianyan.application.usecase.workflow

import com.qianyan.application.di.ApplicationContainer
import com.qianyan.application.error.ApplicationException
import com.qianyan.model.ChapterId
import com.qianyan.model.NovelId
import com.qianyan.model.commit.CommitApproval
import com.qianyan.model.commit.CommitErrorCodes
import com.qianyan.model.commit.CommitResult
import com.qianyan.model.workflow.HumanDecision
import com.qianyan.model.workflow.HumanGateStatus
import com.qianyan.model.workflow.WorkflowStatus
import com.qianyan.model.workingdraft.WorkingDraftStatus
import com.qianyan.model.writing.DraftStatus
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P1-02f · 批准路径的**幂等收敛**与崩溃恢复（故障注入，确定性；无 sleep / 无线程 / 无随机）。
 *
 * 覆盖 P1-02e 定位的两个窗口：
 *  - **A** Gate 已 `RESOLVED + APPROVED` 但 Commit 未执行（中断在 `resolveGatePending → commit` 之间）；
 *  - **B** Commit 已成功但载体 `CONFIRMED` / Workflow `RUNNING` 未写入（中断在 commit 之后）。
 *
 * 故障构造方式：用**真实** Commit / 真实 Repository 写入构造"中断后的持久化状态"，然后调用**真实**生产恢复入口
 * （`ChapterWorkflowFacade.approve` / `WorkflowOrchestrator.approveGate`）验证收敛与幂等；不依赖时序。
 */
class ApprovalRecoveryTest {

    /** 可计数的 LLM 网关：验证恢复过程绝不重复调用 Writer / Critique / Revision（§二 硬约束）。 */
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

    /* ---------------- 测试 A：Gate 已批准，但 Commit 尚未执行 ---------------- */

    @Test
    fun `approved gate without commit is completed on retry`() {
        val (app, gw) = open()
        val (novelId, chapterId) = seed(app)
        driveToGate(app, novelId, chapterId)
        val gateId = markGateApproved(app, chapterId) // 构造：中断发生在 resolveGatePending 与 commit 之间

        // 前置事实：Gate 已批准、Canonical 未变、无 CommitHistory
        assertNull(app.canonicalRead.committedContent(chapterId), "前置：尚未提交")
        assertTrue(app.commits.history(projectId(app, novelId)).isEmpty(), "前置：无历史")
        assertEquals(HumanGateStatus.RESOLVED, app.workflowRepository.getGate(gateId)!!.status)
        val llmBefore = gw.snapshot()

        // 重试（真实生产入口）
        app.workflowFacade.approve(chapterId)

        assertEquals(AI_BODY, app.canonicalRead.committedContent(chapterId), "重试必须完成提交")
        assertEquals(1, app.commits.history(projectId(app, novelId)).size, "只新增一条 CommitHistory")
        assertNotEquals(
            WorkflowStatus.WAITING_HUMAN,
            workflow(app, chapterId).status,
            "批准后不得停留在 WAITING_HUMAN（否则永久挂起）",
        )
        assertEquals(llmBefore, gw.snapshot(), "恢复不得重复调用 LLM")

        // 可继续推进至 COMPLETED
        assertEquals(ChapterPhase.COMPLETED, app.workflowFacade.advance(chapterId).phase)
        assertEquals(1, app.commits.history(projectId(app, novelId)).size, "推进不产生第二条 Commit")
        assertTrue(
            app.storyWorldContextResolver.resolve(novelId).memories.any { it.contains("已突破金丹期") },
            "KU 在恢复后的 Canonical 上正常执行",
        )
    }

    /* ---------------- 测试 B：Commit 已成功，但状态更新之前中断 ---------------- */

    @Test
    fun `commit without the later status updates is repaired on retry`() {
        val (app, gw) = open()
        val (novelId, chapterId) = seed(app)
        driveToGate(app, novelId, chapterId)
        val gateId = markGateApproved(app, chapterId)
        val working = workingProducts(app, novelId, chapterId).single()

        // 构造：真实 Commit 已发生（经既有 I9/I10 流程），但载体 CONFIRMED / Workflow RUNNING 均未写入
        val result = commitDirectly(app, working.workingDraftId, gateId)
        val carrierId = result.resultingDraftId
        assertEquals(DraftStatus.WRITTEN, app.draftRepository.getById(carrierId)!!.status, "前置：载体未 CONFIRMED")
        assertEquals(WorkflowStatus.WAITING_HUMAN, workflow(app, chapterId).status, "前置：Workflow 仍 WAITING_HUMAN")
        assertEquals(1, app.commits.history(projectId(app, novelId)).size, "前置：已有 1 条 Commit")
        assertEquals(WorkingDraftStatus.DISCARDED, app.workingDrafts.get(working.workingDraftId).status, "前置：工作产物已收尾")
        val canonicalBefore = app.canonicalRead.committedContent(chapterId)
        val llmBefore = gw.snapshot()

        // 恢复（真实生产入口）
        app.workflowFacade.approve(chapterId)

        assertEquals(
            DraftStatus.CONFIRMED,
            app.draftRepository.getById(carrierId)!!.status,
            "必须补齐 Canonical 载体的 CONFIRMED（KNOWLEDGE_UPDATE 门槛）",
        )
        assertEquals(canonicalBefore, app.canonicalRead.committedContent(chapterId), "Canonical 正文不变")
        assertEquals(1, app.commits.history(projectId(app, novelId)).size, "恢复不得产生第二条 Commit")
        assertNotEquals(WorkflowStatus.WAITING_HUMAN, workflow(app, chapterId).status, "必须恢复推进")
        assertEquals(llmBefore, gw.snapshot(), "恢复不得重复调用 LLM")

        assertEquals(ChapterPhase.COMPLETED, app.workflowFacade.advance(chapterId).phase, "能推进至 COMPLETED")
        assertTrue(
            app.storyWorldContextResolver.resolve(novelId).memories.any { it.contains("已突破金丹期") },
            "KU 正常执行",
        )

        // 再次重复恢复：不得产生额外 Commit / 不得破坏已完成状态
        app.workflowFacade.approve(chapterId)
        app.workflowOrchestrator.approveGate(gateId)
        assertEquals(1, app.commits.history(projectId(app, novelId)).size, "重复恢复不产生额外 Commit")
        assertEquals(canonicalBefore, app.canonicalRead.committedContent(chapterId))
        assertEquals(WorkflowStatus.COMPLETED, workflow(app, chapterId).status, "已完成状态不被破坏")
        assertEquals(1, app.draftRepository.listByChapter(chapterId).size, "不新增载体 Draft")
    }

    /* ---------------- 测试 C：对已收敛的批准重复执行恢复 ---------------- */

    @Test
    fun `repeated recovery after a normal approval is idempotent`() {
        val (app, gw) = open()
        val (novelId, chapterId) = seed(app)
        driveToGate(app, novelId, chapterId)
        val gateId = assertNotNull(workflow(app, chapterId).pendingGateId)
        app.workflowFacade.approve(chapterId) // 正常批准
        val historyAfterApprove = app.commits.history(projectId(app, novelId)).size
        val canonicalAfterApprove = app.canonicalRead.committedContent(chapterId)
        val gateBefore = app.workflowRepository.getGate(gateId)!!
        val llmBefore = gw.snapshot()

        repeat(3) {
            app.workflowFacade.approve(chapterId)
            app.workflowOrchestrator.approveGate(gateId)
        }

        assertEquals(historyAfterApprove, app.commits.history(projectId(app, novelId)).size, "不重复 Commit")
        assertEquals(canonicalAfterApprove, app.canonicalRead.committedContent(chapterId), "Canonical 不变")
        assertEquals(1, app.draftRepository.listByChapter(chapterId).size, "不新增 Draft")
        assertEquals(gateBefore.gateId, app.workflowRepository.getGate(gateId)!!.gateId, "不新建 Gate（gateKey 幂等）")
        assertEquals(gateBefore.status, app.workflowRepository.getGate(gateId)!!.status, "Gate 决议不被改写")
        assertEquals(llmBefore, gw.snapshot(), "不重复调用 LLM")
    }

    /* ---------------- 测试 D：未批准的 Gate 不进入批准恢复 ---------------- */

    @Test
    fun `rejected gate never enters approval recovery`() {
        val (app, gw) = open()
        val (novelId, chapterId) = seed(app)
        driveToGate(app, novelId, chapterId)
        val gateId = assertNotNull(workflow(app, chapterId).pendingGateId)
        app.workflowService.rejectGate(gateId, HumanDecision.REJECTED)
        val llmBefore = gw.snapshot()

        app.workflowOrchestrator.approveGate(gateId)
        app.workflowOrchestrator.approveGate(gateId)

        assertNull(app.canonicalRead.committedContent(chapterId), "REJECTED 不得产生 Canonical")
        assertTrue(app.commits.history(projectId(app, novelId)).isEmpty(), "REJECTED 不得写 CommitHistory")
        assertEquals(0, app.draftRepository.listByChapter(chapterId).size, "REJECTED 不得写章节 Draft")
        val gate = app.workflowRepository.getGate(gateId)!!
        assertEquals(HumanGateStatus.RESOLVED, gate.status)
        assertEquals(HumanDecision.REJECTED, gate.decision, "决议不得被改写为 APPROVED")
        assertEquals(llmBefore, gw.snapshot(), "不得调用 LLM")
    }

    @Test
    fun `pending gate takes the normal approval path instead of recovery`() {
        val (app, _) = open()
        val (novelId, chapterId) = seed(app)
        driveToGate(app, novelId, chapterId)
        assertEquals(HumanGateStatus.PENDING, app.workflowRepository.getGate(workflow(app, chapterId).pendingGateId!!)!!.status)

        app.workflowFacade.approve(chapterId) // PENDING → 正常批准（不得被"已批准免提交"逻辑短路）

        assertEquals(AI_BODY, app.canonicalRead.committedContent(chapterId), "PENDING 批准必须真正提交")
        assertEquals(1, app.commits.history(projectId(app, novelId)).size)
        assertEquals(1, app.draftRepository.listByChapter(chapterId).size)
    }

    /* ---------------- P1-02i-fix：失效工作产物不得留下"虚假批准" ---------------- */

    /**
     * 工作产物已 `DISCARDED`（不可提交）：批准必须**类型化失败**，且 **Gate 保持 PENDING** ——
     * 不得先写成 `RESOLVED + APPROVED` 再失败（P1-02i 定位的"虚假批准"缺陷）。
     */
    @Test
    fun `pending gate with a discarded work product leaves no phantom approval`() {
        val (app, gw) = open()
        val (novelId, chapterId) = seed(app)
        driveToGate(app, novelId, chapterId)
        val gateId = assertNotNull(workflow(app, chapterId).pendingGateId)
        val working = workingProducts(app, novelId, chapterId).single()
        app.workingDrafts.discard(working.workingDraftId) // 工作产物不可提交
        val llmBefore = gw.snapshot()

        val error = assertFailsWith<ApplicationException> { app.workflowFacade.approve(chapterId) }
        assertTrue(
            error.message.orEmpty().contains(CommitErrorCodes.DISCARDED_WORKING_DRAFT),
            "应为类型化拒绝：${error.message}",
        )

        val gate = app.workflowRepository.getGate(gateId)!!
        assertEquals(HumanGateStatus.PENDING, gate.status, "失败不得留下已批准记录（虚假批准）")
        assertEquals(HumanDecision.PENDING, gate.decision, "决议不得被改写")
        assertNull(app.canonicalRead.committedContent(chapterId), "Canonical 不变")
        assertTrue(app.commits.history(projectId(app, novelId)).isEmpty(), "不得产生 CommitHistory")
        assertEquals(0, app.draftRepository.listByChapter(chapterId).size, "不得产生章节 Draft")
        assertEquals(llmBefore, gw.snapshot(), "不得触发 LLM")
    }

    /**
     * 跨重启 + 工作产物失效（内存工作区丢失）：批准必须**类型化失败**且 **Gate 保持 PENDING**；
     * 只读/恢复路径不得改写状态、不得伪造正文、不得提交。
     */
    @Test
    fun `restart with a lost work product leaves no phantom approval and no fabrication`() {
        val tmp = java.nio.file.Files.createTempFile("qianyan-appr-i-fix", ".db").toString()
        val handles = mutableListOf<com.qianyan.storage.db.QianyanDbHandle>()
        try {
            val gw = CountingGateway()
            var app = openFile("jdbc:sqlite:$tmp", handles, gw)
            val (novelId, chapterId) = seed(app)
            driveToGate(app, novelId, chapterId)
            val gateId = assertNotNull(workflow(app, chapterId).pendingGateId)
            closeAll(handles) // 进程退出：内存工作产物随之丢失

            app = openFile("jdbc:sqlite:$tmp", handles, gw)
            val llmBefore = gw.snapshot()

            // 1) 批准：类型化失败（引用失效），且不得留下已批准记录
            val error = assertFailsWith<ApplicationException> { app.workflowFacade.approve(chapterId) }
            assertTrue(
                error.message.orEmpty().contains("工作产物"),
                "应为「工作产物引用失效」的明确错误：${error.message}",
            )
            val gate = app.workflowRepository.getGate(gateId)!!
            assertEquals(HumanGateStatus.PENDING, gate.status, "不得留下虚假批准记录")
            assertEquals(HumanDecision.PENDING, gate.decision)
            assertNull(app.canonicalRead.committedContent(chapterId), "不得伪造 Canonical 正文")
            assertTrue(app.commits.history(projectId(app, novelId)).isEmpty(), "不得产生 CommitHistory")
            assertEquals(0, app.draftRepository.listByChapter(chapterId).size, "不得伪造章节 Draft")

            // 2) 只读 / 恢复路径不得隐式开新一轮或改写状态
            repeat(2) { app.workflowFacade.getChapterProgress(chapterId) }
            app.workflowFacade.resume(chapterId)
            app.workflowFacade.advance(chapterId)
            assertEquals(HumanGateStatus.PENDING, app.workflowRepository.getGate(gateId)!!.status, "恢复不得改写 Gate")
            assertEquals(
                1,
                app.workflowRepository.listSteps(workflow(app, chapterId).workflowId)
                    .count { it.phase == com.qianyan.model.workflow.WorkflowStepPhase.CONFIRMATION },
                "不得自动开启新一轮（新 CONFIRMATION Step）",
            )
            assertNull(app.canonicalRead.committedContent(chapterId))
            assertTrue(app.commits.history(projectId(app, novelId)).isEmpty())
            assertEquals(llmBefore, gw.snapshot(), "恢复失败不得重跑 LLM")
        } finally {
            closeAll(handles)
            java.nio.file.Files.deleteIfExists(java.nio.file.Path.of(tmp))
        }
    }

    /**
     * 跨重启 + Commit 已成功（**Gate↔Commit 关联已持久化**）+ 工作产物失效（内存丢失）：
     * 恢复必须**依据持久关联**收敛 —— 复用正式提交、不重复 Commit、补齐载体 `CONFIRMED`、脱离 `WAITING_HUMAN`；
     * 不伪造正文、不重跑 LLM。（P1-02j B1：关联取代了此前"无法从持久事实判定 ⇒ 明确失败"的行为。）
     */
    @Test
    fun `restart after a successful commit converges via the persistent gate link without re-committing`() {
        val tmp = java.nio.file.Files.createTempFile("qianyan-appr-i-fix2", ".db").toString()
        val handles = mutableListOf<com.qianyan.storage.db.QianyanDbHandle>()
        try {
            val gw = CountingGateway()
            var app = openFile("jdbc:sqlite:$tmp", handles, gw)
            val (novelId, chapterId) = seed(app)
            driveToGate(app, novelId, chapterId)
            val gateId = markGateApproved(app, chapterId)
            val working = workingProducts(app, novelId, chapterId).single()
            val result = commitDirectly(app, working.workingDraftId, gateId) // 构造"提交已成功、后续状态未完成"
            val carrierId = result.resultingDraftId
            assertEquals(DraftStatus.WRITTEN, app.draftRepository.getById(carrierId)!!.status)
            assertEquals(gateId, app.commits.committedByGate(gateId)?.gateId, "P1-02j B1：CommitHistory 应持久关联该 Gate")
            closeAll(handles) // 进程退出：工作产物丢失

            app = openFile("jdbc:sqlite:$tmp", handles, gw)
            val llmBefore = gw.snapshot()
            val canonicalBefore = app.canonicalRead.committedContent(chapterId)
            assertEquals(AI_BODY, canonicalBefore, "已提交正文必须仍可经 CanonicalRead 读取（不得误判为丢失）")

            // 依据持久 Gate↔Commit 关联收敛（不重复提交 / 不伪造）
            app.workflowFacade.approve(chapterId)

            assertEquals(AI_BODY, app.canonicalRead.committedContent(chapterId), "Canonical 不变")
            assertEquals(1, app.commits.history(projectId(app, novelId)).size, "不得重复 Commit")
            assertEquals(
                DraftStatus.CONFIRMED,
                app.draftRepository.getById(carrierId)!!.status,
                "应据关联补齐载体 CONFIRMED（KNOWLEDGE_UPDATE 门槛）",
            )
            assertNotEquals(WorkflowStatus.WAITING_HUMAN, workflow(app, chapterId).status, "必须脱离 WAITING_HUMAN")
            assertEquals(1, app.draftRepository.listByChapter(chapterId).size, "不得新增 Draft")
            assertEquals(llmBefore, gw.snapshot(), "不得触发 LLM")

            // 幂等：重复恢复不改变结果
            app.workflowFacade.approve(chapterId)
            app.workflowOrchestrator.approveGate(gateId)
            assertEquals(1, app.commits.history(projectId(app, novelId)).size, "重复恢复不产生额外 Commit")
            assertEquals(DraftStatus.CONFIRMED, app.draftRepository.getById(carrierId)!!.status)
            assertEquals(llmBefore, gw.snapshot(), "重复恢复不得触发 LLM")
        } finally {
            closeAll(handles)
            java.nio.file.Files.deleteIfExists(java.nio.file.Path.of(tmp))
        }
    }

    /**
     * 跨重启 + Gate 已批准 + 工作产物失效 + **无任何 Gate↔Commit 关联**：
     * 无法从持久事实判定是否已提交 ⇒ 必须**明确失败**（不伪造正文、不重复提交、不重跑 LLM）；
     * 且失败**不得改写 Gate 决议**（APPROVED 保持真实）。
     */
    @Test
    fun `approved gate with a lost product and no commit link fails explicitly`() {
        val tmp = java.nio.file.Files.createTempFile("qianyan-appr-nolink", ".db").toString()
        val handles = mutableListOf<com.qianyan.storage.db.QianyanDbHandle>()
        try {
            val gw = CountingGateway()
            var app = openFile("jdbc:sqlite:$tmp", handles, gw)
            val (novelId, chapterId) = seed(app)
            driveToGate(app, novelId, chapterId)
            val gateId = markGateApproved(app, chapterId) // 批准已记录，但未提交
            closeAll(handles) // 进程退出：工作产物丢失（无关联、无提交事实）

            app = openFile("jdbc:sqlite:$tmp", handles, gw)
            val llmBefore = gw.snapshot()

            val error = assertFailsWith<ApplicationException> { app.workflowFacade.approve(chapterId) }
            assertTrue(
                error.message.orEmpty().contains("无 Gate↔Commit 关联"),
                "应为「无 Gate↔Commit 关联，无法判定」的明确错误：${error.message}",
            )
            assertNull(app.canonicalRead.committedContent(chapterId), "不得伪造 Canonical 正文")
            assertTrue(app.commits.history(projectId(app, novelId)).isEmpty(), "不得产生 CommitHistory")
            assertEquals(0, app.draftRepository.listByChapter(chapterId).size, "不得伪造章节 Draft")
            val gate = app.workflowRepository.getGate(gateId)!!
            assertEquals(HumanGateStatus.RESOLVED, gate.status, "失败不得改写 Gate 状态")
            assertEquals(HumanDecision.APPROVED, gate.decision, "APPROVED 决议保持真实")
            assertEquals(llmBefore, gw.snapshot(), "不得触发 LLM")
        } finally {
            closeAll(handles)
            java.nio.file.Files.deleteIfExists(java.nio.file.Path.of(tmp))
        }
    }

    /* ---------------- helpers ---------------- */

    private fun open(): Pair<ApplicationContainer, CountingGateway> {
        val gw = CountingGateway()
        return ApplicationContainer.open(analysisGateway = gw) to gw
    }

    /** 文件库容器（跨"进程重启"用：新容器 = 空内存工作区，同库保留持久化事实）。 */
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

    /** 构造"Gate 已 RESOLVED + APPROVED"（模拟 resolve 已完成、后续步骤被中断）。 */
    private fun markGateApproved(app: ApplicationContainer, chapterId: ChapterId): com.qianyan.model.workflow.WorkflowHumanGateId {
        val gateId = assertNotNull(workflow(app, chapterId).pendingGateId)
        val gate = app.workflowRepository.getGate(gateId)!!
        app.workflowRepository.updateGate(
            gate.copy(
                status = HumanGateStatus.RESOLVED,
                decision = HumanDecision.APPROVED,
                resolvedAt = Clock.System.now(),
                resolvedBy = "user",
            ),
        )
        return gateId
    }

    /** 构造"正式 Commit 已发生"的持久化事实：WorkingDraft → validate → Change Artifact → CommitUseCases.commit。 */
    private fun commitDirectly(
        app: ApplicationContainer,
        workingDraftId: com.qianyan.model.workingdraft.WorkingDraftId,
        gateId: com.qianyan.model.workflow.WorkflowHumanGateId,
    ): CommitResult {
        app.workingDrafts.validate(workingDraftId)
        val artifact = app.changes.prepare(workingDraftId)
        return app.commits.commit(artifact.projectId, artifact.artifactId, CommitApproval(gateId))
    }

    private fun workflow(app: ApplicationContainer, chapterId: ChapterId) =
        assertNotNull(app.workflowRepository.getWorkflowByActiveChapter(chapterId), "应存在 Workflow")

    private fun projectId(app: ApplicationContainer, novelId: NovelId) =
        app.projects.projectOf(novelId).projectId

    private fun workingProducts(app: ApplicationContainer, novelId: NovelId, chapterId: ChapterId) =
        app.workingDrafts.list(projectId(app, novelId)).filter { it.target.chapterId == chapterId && !it.status.isTerminal }

    private companion object {
        const val AI_BODY = "AI 待审正文"
        const val REVISED_BODY = "AI 修订正文"
    }
}
