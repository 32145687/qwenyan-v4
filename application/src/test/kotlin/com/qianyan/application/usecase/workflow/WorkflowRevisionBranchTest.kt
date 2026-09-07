package com.qianyan.application.usecase.workflow

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.qianyan.application.di.ApplicationContainer
import com.qianyan.model.ChapterId
import com.qianyan.model.DraftId
import com.qianyan.model.NovelId
import com.qianyan.model.workflow.AttemptErrorCategory
import com.qianyan.model.workflow.Workflow
import com.qianyan.model.workflow.WorkflowAttemptStatus
import com.qianyan.model.workflow.WorkflowId
import com.qianyan.model.workflow.WorkflowKind
import com.qianyan.model.workflow.WorkflowStatus
import com.qianyan.model.workflow.WorkflowStep
import com.qianyan.model.workflow.WorkflowStepId
import com.qianyan.model.workflow.WorkflowStepPhase
import com.qianyan.model.workflow.WorkflowStepStatus
import com.qianyan.model.writing.Draft
import com.qianyan.model.writing.DraftStatus
import com.qianyan.provider.ChatMessage
import com.qianyan.provider.ChatRole
import com.qianyan.provider.FinishReason
import com.qianyan.provider.LLMGateway
import com.qianyan.provider.ProviderException
import com.qianyan.provider.ProviderRequest
import com.qianyan.provider.ProviderResponse
import com.qianyan.provider.Usage
import com.qianyan.storage.db.QianyanDbFactory
import com.qianyan.storage.db.QianyanDbHandle
import kotlinx.datetime.Clock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import kotlin.io.path.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P12.2 · Revision Branch（R1–R7）。
 * 验证 CRITIQUE → REVISION → CRITIQUE → FINALIZE 分支、revisionCount ≤3、Draft lineage、
 * Retry≠Revision、crash recovery（file-backed DB）。
 * 全部 Mock LLM，无网络。
 */
class WorkflowRevisionBranchTest {

    /** 可编程 gateway：按注入回调控制各 Agent 产物 / 失败。revisionN 为修订调用序号（持久字段，跨调用递增）。 */
    private class ScriptedGateway(
        private val criticPass: () -> Boolean,
        private val onCriticCall: () -> Unit = {},
        private val onRevisionFail: (Int) -> ProviderException? = { null },
        private val onRevisionCall: () -> Unit = {},
        private val revisionContent: (Int) -> String = { "修订版$it" },
        private val writerContent: String = "正文A",
        private val knowledgeContent: String = """{"changes":[{"changeId":"k1","operation":"ADD","target":"主角","content":"已突破金丹期"}]}""",
        private val throwOnWriter: Boolean = false,
        private val throwOnCritic: Boolean = false,
        private val throwOnRevision: Boolean = false,
    ) : LLMGateway {
        private var revisionN = 0

        override fun chat(request: ProviderRequest): ProviderResponse {
            val system = request.messages.first { it.role == ChatRole.SYSTEM }.content
            val body = when {
                "StoryWriterAgent" in system -> {
                    if (throwOnWriter) throw ProviderException.Timeout("writer should not be called")
                    """{"content":"$writerContent"}"""
                }
                "StoryCriticAgent" in system -> {
                    if (throwOnCritic) throw ProviderException.Timeout("critic should not be called")
                    onCriticCall()
                    if (criticPass()) """{"passed":true}""" else """{"passed":false,"issues":[{"field":"正文","severity":"ERROR","message":"需要修改"}]}"""
                }
                "StoryRevisionAgent" in system -> {
                    if (throwOnRevision) throw ProviderException.Timeout("revision should not be called")
                    onRevisionCall()
                    revisionN++
                    onRevisionFail(revisionN)?.let { throw it }
                    """{"content":"${revisionContent(revisionN)}"}"""
                }
                "KnowledgeUpdateAgent" in system -> knowledgeContent
                else -> """{"chapterGoal":"g"}"""
            }
            return ProviderResponse(
                message = ChatMessage(ChatRole.ASSISTANT, buildJsonObject { put("answer", body) }.toString()),
                usage = Usage(1, 1, 2), finishReason = FinishReason.STOP,
            )
        }
    }

    private fun createWorkflow(app: ApplicationContainer, novelId: NovelId, chapterId: ChapterId, wfId: String): WorkflowId {
        val id = WorkflowId(wfId)
        app.workflowRepository.createWorkflow(
            Workflow(id, novelId, null, WorkflowKind.WRITE_NOVEL, status = WorkflowStatus.CREATED, activeChapterId = chapterId, createdAt = Clock.System.now(), updatedAt = Clock.System.now()),
        )
        return id
    }

    private fun open(url: String, handles: MutableList<QianyanDbHandle>, gateway: LLMGateway): ApplicationContainer {
        val handle = QianyanDbFactory.open(url)
        handles += handle
        return ApplicationContainer.fromDriver(handle.driver, analysisGateway = gateway)
    }

    private fun closeAll(handles: MutableList<QianyanDbHandle>) {
        handles.forEach { (it.driver as JdbcSqliteDriver?)?.getConnection()?.close() }
        handles.clear()
    }

    /** R1 — Critique PASS：Planning→Writing→Critique(PASS)→Finalize。REVISION 不执行，revisionCount==0。 */
    @Test
    fun `R1 critique pass goes straight to finalize`() {
        var criticCalls = 0
        var revisionCalls = 0
        val app = ApplicationContainer.open(analysisGateway = ScriptedGateway(
            criticPass = { true }, onCriticCall = { criticCalls++ }, onRevisionCall = { revisionCalls++ },
        ))
        val novelId = app.novels.createOriginal(title = "T")
        val ch = app.chapters.createNextChapter(title = "章", novelId = novelId)
        val wfId = createWorkflow(app, novelId, ch.chapterId, "W-R1")

        val r = app.workflowOrchestrator.runForward(wfId)
        assertEquals(WorkflowStatus.WAITING_HUMAN, r.status)

        val steps = app.workflowRepository.listSteps(wfId)
        assertEquals(0, steps.count { it.phase == WorkflowStepPhase.REVISION }, "PASS 不应进入 REVISION")
        assertEquals(0, revisionCalls)
        assertEquals(1, criticCalls)
        assertTrue(steps.any { it.phase == WorkflowStepPhase.FINALIZE && it.status == WorkflowStepStatus.COMPLETED })
        assertEquals(DraftStatus.FINAL, app.draftRepository.latestByChapter(ch.chapterId)!!.status)
    }

    /** R2 — Critique NEED_REVISION 一次：A→REVISION→B→CRITIQUE(PASS)→FINALIZE。B.previousDraftId==A，revisionCount==1。 */
    @Test
    fun `R2 one revision then pass`() {
        var criticCalls = 0
        val app = ApplicationContainer.open(analysisGateway = ScriptedGateway(
            criticPass = { criticCalls++ > 0 }, // 第1次 false，其后 true
            revisionContent = { "修订版B" },
        ))
        val novelId = app.novels.createOriginal(title = "T")
        val ch = app.chapters.createNextChapter(title = "章", novelId = novelId)
        val wfId = createWorkflow(app, novelId, ch.chapterId, "W-R2")

        val r = app.workflowOrchestrator.runForward(wfId)
        assertEquals(WorkflowStatus.WAITING_HUMAN, r.status)

        val drafts = app.draftRepository.listByChapter(ch.chapterId)
        assertEquals(2, drafts.size)
        assertEquals(DraftStatus.WRITTEN, drafts[0].status) // 原 Draft A 未被覆盖
        assertEquals(drafts[0].draftId, drafts[1].previousDraftId) // B.previousDraftId == A.id

        val steps = app.workflowRepository.listSteps(wfId)
        val revSteps = steps.filter { it.phase == WorkflowStepPhase.REVISION }
        assertEquals(1, revSteps.size)
        assertEquals(WorkflowStepStatus.COMPLETED, revSteps[0].status)

        val critR1 = steps.first { it.phase == WorkflowStepPhase.CRITIQUE && it.logicalStepKey.contains(":R1") }
        assertEquals(WorkflowStepStatus.COMPLETED, critR1.status)
        val finalized = app.draftRepository.latestByChapter(ch.chapterId)!!
        assertEquals(DraftStatus.FINAL, finalized.status)
        assertEquals(drafts[1].draftId, finalized.draftId) // FINALIZE 用 Draft B
    }

    /** R3 — 多次修订：A→REV1→B→REV2→C→CRITIQUE(PASS)→FINALIZE。lineage A→B→C，revisionCount==2。 */
    @Test
    fun `R3 two revisions keep lineage`() {
        var criticCalls = 0
        val app = ApplicationContainer.open(analysisGateway = ScriptedGateway(
            criticPass = { criticCalls++ >= 2 }, // false,false,true
            revisionContent = { if (it == 1) "修订版B" else "修订版C" },
        ))
        val novelId = app.novels.createOriginal(title = "T")
        val ch = app.chapters.createNextChapter(title = "章", novelId = novelId)
        val wfId = createWorkflow(app, novelId, ch.chapterId, "W-R3")

        val r = app.workflowOrchestrator.runForward(wfId)
        assertEquals(WorkflowStatus.WAITING_HUMAN, r.status)

        val drafts = app.draftRepository.listByChapter(ch.chapterId)
        assertEquals(3, drafts.size) // A,B,C
        assertEquals("正文A", drafts[0].content)
        assertEquals("修订版B", drafts[1].content)
        assertEquals("修订版C", drafts[2].content)
        assertNull(drafts[0].previousDraftId)
        assertEquals(drafts[0].draftId, drafts[1].previousDraftId) // A→B
        assertEquals(drafts[1].draftId, drafts[2].previousDraftId) // B→C
        assertEquals(2, app.workflowRepository.listSteps(wfId).count { it.phase == WorkflowStepPhase.REVISION })
        assertEquals(DraftStatus.FINAL, app.draftRepository.latestByChapter(ch.chapterId)!!.status)
    }

    /** R4 — Revision 上限：3 次修订后再 NEED_REVISION → 无第 4 次修订 / 不无限循环，Workflow 进入 FAILED（明确终止）。 */
    @Test
    fun `R4 revision limit terminates workflow`() {
        var revisionCalls = 0
        val app = ApplicationContainer.open(analysisGateway = ScriptedGateway(
            criticPass = { false }, // 永远 NEED_REVISION
            revisionContent = { "修订版$it" },
            onRevisionCall = { revisionCalls++ },
        ))
        val novelId = app.novels.createOriginal(title = "T")
        val ch = app.chapters.createNextChapter(title = "章", novelId = novelId)
        val wfId = createWorkflow(app, novelId, ch.chapterId, "W-R4")

        val r = app.workflowOrchestrator.runForward(wfId)
        assertEquals(WorkflowStatus.FAILED, r.status)
        assertEquals(WorkflowStatus.FAILED, app.workflowRepository.getWorkflow(wfId)!!.status)

        assertEquals(3, revisionCalls) // 恰好 3 次修订，无第 4 次
        assertEquals(3, app.workflowRepository.listSteps(wfId).count { it.phase == WorkflowStepPhase.REVISION })

        // 再次 runForward 不无限循环、不新增修订
        revisionCalls = 0
        val r2 = app.workflowOrchestrator.runForward(wfId)
        assertEquals(WorkflowStatus.FAILED, r2.status)
        assertEquals(0, revisionCalls)
        assertEquals(3, app.workflowRepository.listSteps(wfId).count { it.phase == WorkflowStepPhase.REVISION })
    }

    /** R5 — Retry ≠ Revision：Revision Task attempt1=RETRYABLE 失败，attempt2 成功。attemptNo==2，revisionCount==1，仅一个修订 Draft。 */
    @Test
    fun `R5 retry does not increment revision count`() {
        var criticN = 0
        var revisionCalls = 0
        val gateway = ScriptedGateway(
            criticPass = { criticN++ > 0 }, // 第一次 false（触发修订），其后 true
            onRevisionFail = { n -> if (n == 1) ProviderException.Timeout("revision transient") else null },
            onRevisionCall = { revisionCalls++ },
            revisionContent = { "修订版B" },
        )
        val app = ApplicationContainer.open(analysisGateway = gateway)
        val novelId = app.novels.createOriginal(title = "T")
        val ch = app.chapters.createNextChapter(title = "章", novelId = novelId)
        val wfId = createWorkflow(app, novelId, ch.chapterId, "W-R5")

        val r = app.workflowOrchestrator.runForward(wfId)
        assertEquals(WorkflowStatus.WAITING_HUMAN, r.status)

        val step = app.workflowRepository.listSteps(wfId).first { it.phase == WorkflowStepPhase.REVISION }
        val attempts = app.workflowRepository.listAttempts(step.stepId)
        assertEquals(2, attempts.size)
        assertEquals(WorkflowAttemptStatus.FAILED, attempts[0].status)
        assertEquals(AttemptErrorCategory.RETRYABLE, attempts[0].errorCategory)
        assertEquals(WorkflowAttemptStatus.COMPLETED, attempts[1].status)
        assertEquals(2, step.currentAttemptNo) // attemptNo == 2
        assertEquals(1, app.workflowRepository.listSteps(wfId).count { it.phase == WorkflowStepPhase.REVISION }) // revisionCount == 1
        assertEquals(2, app.draftRepository.listByChapter(ch.chapterId).size) // A + B，仅一个修订 Draft
    }

    /** R6 — Crash Recovery：Critique NEED_REVISION → Revision SUCCESS → Workflow rebuild。Draft B 被恢复，Revision 不重复执行。 */
    @Test
    fun `R6 crash recovery after revision success`() {
        val tmp = Files.createTempFile("qianyan-r6", ".db").toString()
        val handles = mutableListOf<QianyanDbHandle>()
        try {
            var criticN = 0
            val phase1 = ScriptedGateway(
                criticPass = { false },
                onCriticCall = { criticN++; if (criticN >= 2) throw ProviderException.Timeout("crash at critique B") },
                revisionContent = { "修订版B" },
            )
            var app = open("jdbc:sqlite:$tmp", handles, phase1)
            val novelId = app.novels.createOriginal(title = "T")
            val ch = app.chapters.createNextChapter(title = "章", novelId = novelId)
            val wfId = createWorkflow(app, novelId, ch.chapterId, "W-R6")
            try { app.workflowOrchestrator.runForward(wfId) } catch (_: Throwable) { /* crash */ }

            assertEquals(2, app.draftRepository.listByChapter(ch.chapterId).size) // A,B 已落库
            val revStepBefore = app.workflowRepository.listSteps(wfId).first { it.phase == WorkflowStepPhase.REVISION }
            assertEquals(WorkflowStepStatus.COMPLETED, revStepBefore.status)
            // 已进入 Endpoint：Workflow 不再是 CREATED 初始态（CRITIQUE:R1 之前）
            closeAll(handles)

            var revisionCalls = 0
            val phase2 = ScriptedGateway(criticPass = { true }, onRevisionCall = { revisionCalls++ }, throwOnWriter = true)
            app = open("jdbc:sqlite:$tmp", handles, phase2)
            val r = app.workflowOrchestrator.runForward(wfId)
            assertEquals(WorkflowStatus.WAITING_HUMAN, r.status)

            assertEquals(0, revisionCalls, "恢复绝不重调 Revision")
            val drafts = app.draftRepository.listByChapter(ch.chapterId)
            assertEquals(2, drafts.size, "恢复不产生第三个 Draft")
            assertEquals(drafts[0].draftId, drafts[1].previousDraftId)
            assertEquals(revStepBefore.stepId, app.workflowRepository.listSteps(wfId).first { it.phase == WorkflowStepPhase.REVISION }.stepId)
            assertEquals(DraftStatus.FINAL, app.draftRepository.latestByChapter(ch.chapterId)!!.status)
        } finally {
            closeAll(handles)
            Files.deleteIfExists(Path(tmp))
        }
    }

    /** R7 — Recovery after Critique PASS：已落库 CRITIQUE:R1 COMPLETED(PASS)，转 FINALIZE 而非重做 Revision/Critique/Writing。 */
    @Test
    fun `R7 recovery after critique pass continues to finalize`() {
        val tmp = Files.createTempFile("qianyan-r7", ".db").toString()
        val handles = mutableListOf<QianyanDbHandle>()
        try {
            val novelId = NovelId("n-r7"); val ch = ChapterId("c-r7"); val wfId = WorkflowId("W-R7")
            val app0 = open("jdbc:sqlite:$tmp", handles, ScriptedGateway(criticPass = { true }))
            app0.workflowRepository.createWorkflow(
                Workflow(wfId, novelId, null, WorkflowKind.WRITE_NOVEL, status = WorkflowStatus.CREATED, activeChapterId = ch, createdAt = Clock.System.now(), updatedAt = Clock.System.now()),
            )
            val draftA = draft(novelId, ch, "dA", DraftStatus.WRITTEN, null)
            val draftB = draft(novelId, ch, "dB", DraftStatus.REVISED, draftA.draftId)
            app0.draftRepository.save(draftA); app0.draftRepository.save(draftB)
            app0.workflowRepository.createStep(step(wfId, ch, "plan", WorkflowStepPhase.PLANNING, "PLANJSON:{}"))
            app0.workflowRepository.createStep(step(wfId, ch, "write", WorkflowStepPhase.WRITING, draftA.draftId.value))
            app0.workflowRepository.createStep(step(wfId, ch, "crit0", WorkflowStepPhase.CRITIQUE, "CRITIQUE:t0:${draftA.draftId.value}:REVISION", "W-R7:c-r7:CRITIQUE"))
            app0.workflowRepository.createStep(step(wfId, ch, "rev1", WorkflowStepPhase.REVISION, draftB.draftId.value, "W-R7:c-r7:REVISION:R1"))
            // CRITIQUE:R1 已 COMPLETED(PASS)，但 FINALIZE 尚未创建（crash 窗口）
            app0.workflowRepository.createStep(step(wfId, ch, "crit1", WorkflowStepPhase.CRITIQUE, "CRITIQUE:t1:${draftB.draftId.value}:PASS", "W-R7:c-r7:CRITIQUE:R1"))
            closeAll(handles)

            var revisionCalls = 0
            val phase2 = ScriptedGateway(criticPass = { true }, onRevisionCall = { revisionCalls++ }, throwOnWriter = true, throwOnCritic = true)
            val app = open("jdbc:sqlite:$tmp", handles, phase2)
            val r = app.workflowOrchestrator.runForward(wfId)
            assertEquals(WorkflowStatus.WAITING_HUMAN, r.status)

            val steps = app.workflowRepository.listSteps(wfId)
            assertEquals(0, revisionCalls, "恢复不重做 Revision")
            val finalize = steps.first { it.phase == WorkflowStepPhase.FINALIZE }
            assertEquals(WorkflowStepStatus.COMPLETED, finalize.status)
            assertEquals(2, app.draftRepository.listByChapter(ch).size, "恢复不产生新 Draft")
            assertEquals(DraftStatus.FINAL, app.draftRepository.latestByChapter(ch)!!.status)
        } finally {
            closeAll(handles)
            Files.deleteIfExists(Path(tmp))
        }
    }

    private fun step(wfId: WorkflowId, ch: ChapterId, id: String, phase: WorkflowStepPhase, refNote: String, key: String = ""): WorkflowStep {
        val actualKey = if (key.isBlank()) "${wfId.value}:${ch.value}:${phase.name}" else key
        return WorkflowStep(
            stepId = WorkflowStepId("s-$id"), workflowId = wfId, chapterId = ch, phase = phase,
            logicalStepKey = actualKey, status = WorkflowStepStatus.COMPLETED, resultReference = refNote,
            createdAt = Clock.System.now(), completedAt = Clock.System.now(),
        )
    }

    private fun draft(novelId: NovelId, ch: ChapterId, id: String, status: DraftStatus, prev: DraftId?): Draft = Draft(
        draftId = DraftId(id), novelId = novelId, chapterId = ch, content = "正文-$id",
        status = status, previousDraftId = prev, createdAt = Clock.System.now(), updatedAt = Clock.System.now(),
    )
}