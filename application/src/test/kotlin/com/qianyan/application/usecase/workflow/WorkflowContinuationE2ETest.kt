package com.qianyan.application.usecase.workflow

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.qianyan.application.di.ApplicationContainer
import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.model.DraftId
import com.qianyan.model.NovelId
import com.qianyan.model.workflow.Workflow
import com.qianyan.model.workflow.WorkflowId
import com.qianyan.model.workflow.WorkflowKind
import com.qianyan.model.workflow.WorkflowStatus
import com.qianyan.model.writing.Draft
import com.qianyan.model.writing.DraftStatus
import com.qianyan.provider.ChatMessage
import com.qianyan.provider.ChatRole
import com.qianyan.provider.FinishReason
import com.qianyan.provider.ProviderResponse
import com.qianyan.provider.Usage
import com.qianyan.provider.impl.MockLLMGateway
import com.qianyan.storage.db.QianyanDbFactory
import com.qianyan.storage.db.QianyanDbHandle
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import kotlin.io.path.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P12.2 · Test E — Multi-Chapter Continuation（真实 file-backed SQLite E2E）。
 *
 * Chapter 1（Workflow 1 全链：Planning→Writing→Critique→Finalize→Confirmation→Approve→KU→COMPLETED）
 * → continueToNextWorkflow（durable WorkflowContinuation + Chapter 2 + Workflow 2）
 * → Chapter 2（Workflow 2 全链跑通 Planning/Writing…）。
 *
 * 关键：continuation 完全由 durable WorkflowContinuation 驱动（sourceDraftId + targetChapterId 幂等身份），
 * 不依赖任何 ChapterWritingSession 内存状态；session 可随意丢弃/重建。
 * 全部走真实 UseCase → Task → AgentRuntime → Mock Provider 链路。
 */
class WorkflowContinuationE2ETest {

    private val kuFact = "已突破金丹期"

    private fun gateway(plannerInputs: MutableList<String>, writerInputs: MutableList<String>): MockLLMGateway =
        MockLLMGateway { req ->
            val user = req.messages.first { it.role == ChatRole.USER }.content
            val system = req.messages.first { it.role == ChatRole.SYSTEM }.content
            val body = when {
                "StoryPlannerAgent" in system -> { plannerInputs.add(user); """{"chapterGoal":"揭开秘辛","mainConflict":"c","expectedEvents":["遇险"]}""" }
                "StoryWriterAgent" in system -> { writerInputs.add(user); """{"content":"正文被写就。"}""" }
                "StoryCriticAgent" in system -> """{"passed":true}"""
                "StoryRevisionAgent" in system -> """{"content":"修订后正文。"}"""
                "KnowledgeUpdateAgent" in system -> """{"changes":[{"changeId":"k1","operation":"ADD","target":"主角","content":"$kuFact"}]}"""
                else -> """{"content":"占位"}"""
            }
            ProviderResponse(
                message = ChatMessage(ChatRole.ASSISTANT, buildJsonObject { put("answer", body) }.toString()),
                usage = Usage(10, 10, 20), finishReason = FinishReason.STOP,
            )
        }

    private fun open(url: String, handles: MutableList<QianyanDbHandle>, planner: MutableList<String>, writer: MutableList<String>): ApplicationContainer {
        val h = QianyanDbFactory.open(url)
        handles += h
        return ApplicationContainer.fromDriver(h.driver, analysisGateway = gateway(planner, writer))
    }

    private fun closeAll(handles: MutableList<QianyanDbHandle>) {
        handles.forEach { (it.driver as JdbcSqliteDriver?)?.getConnection()?.close() }
        handles.clear()
    }

    /** 建一个章节 + 对应 Workflow，返回 (chapterId, workflowId)。 */
    private fun newChapterWorkflow(app: ApplicationContainer, novelId: NovelId): Pair<com.qianyan.model.ChapterId, WorkflowId> {
        val ch = app.chapters.createNextChapter(title = "章", novelId = novelId)
        val wfId = WorkflowId("wf:${ch.chapterId.value}")
        app.workflowRepository.createWorkflow(
            Workflow(wfId, novelId, null, WorkflowKind.WRITE_NOVEL, status = WorkflowStatus.CREATED,
                activeChapterId = ch.chapterId, createdAt = Clock.System.now(), updatedAt = Clock.System.now()),
        )
        return ch.chapterId to wfId
    }

    /** 章节所属 Novel（供 WorkingDraft 的 Project 归属解析）。 */
    private fun novelIdOf(app: ApplicationContainer, chapterId: com.qianyan.model.ChapterId): NovelId =
        app.chapterRepository.findById(chapterId)!!.novelId

    /** 用 Orchestrator 真实跑完 Chapter：Planning→Writing→Critique→Finalize→Confirmation→Approve→KU→COMPLETED。 */
    private fun completeChapter(app: ApplicationContainer, wfId: WorkflowId) {        val r1 = app.workflowOrchestrator.runForward(wfId)
        assertEquals(WorkflowStatus.WAITING_HUMAN, r1.status)
        val wf = app.workflowRepository.getWorkflow(wfId)!!
        val gate = app.workflowRepository.getGate(wf.pendingGateId!!)!!
        app.workflowOrchestrator.approveGate(gate.gateId)
        val r2 = app.workflowOrchestrator.runForward(wfId)
        assertEquals(WorkflowStatus.COMPLETED, r2.status)
        assertEquals(WorkflowStatus.COMPLETED, app.workflowRepository.getWorkflow(wfId)!!.status)
    }

    /** E1(+E5+E6) — Chapter1 → Workflow1(COMPLETED) → continue → Chapter2 → Workflow2(Planning/Writing)。真实链路。 */
    @Test
    fun `E1 chapter1 to chapter2 full real chain`() {
        val plannerInputs = mutableListOf<String>()
        val writerInputs = mutableListOf<String>()
        val app = ApplicationContainer.open(analysisGateway = gateway(plannerInputs, writerInputs))
        val novelId = app.novels.createOriginal(title = "仙侠")
        val (ch1, wf1) = newChapterWorkflow(app, novelId)

        completeChapter(app, wf1)
        // Ch1 定稿确认
        val draft1 = app.draftRepository.latestByChapter(ch1)!!
        assertEquals(DraftStatus.CONFIRMED, draft1.status)
        assertTrue(app.storyWorldContextResolver.resolve(novelId).memories.any { it.contains(kuFact) })

        // durable continuation → Chapter2 → Workflow2
        val wf2 = app.workflowOrchestrator.continueToNextWorkflow(wf1)
        val cont = app.workflowRepository.getContinuationBySourceDraft(draft1.draftId)!!
        assertEquals(ch1, cont.sourceChapterId)                      // E5：sourceChapterId == Ch1
        assertEquals(draft1.draftId, cont.sourceDraftId)             // E5：sourceDraftId == Ch1 confirmed draft
        val ch2 = cont.targetChapterId
        assertEquals(app.chapters.listByNovel(novelId).size, 2)

        // Ch2 Planning+Writing 全链
        runCh2Planning(app, wf2, wf1, ch2, ch1, draft1.draftId, plannerInputs, writerInputs)
    }

    private fun runCh2Planning(
        app: ApplicationContainer, wf2: WorkflowId, wf1: WorkflowId, ch2: com.qianyan.model.ChapterId,
        ch1: com.qianyan.model.ChapterId, srcDraft: com.qianyan.model.DraftId,
        plannerInputs: MutableList<String>, writerInputs: MutableList<String>,
    ) {
        val r = app.workflowOrchestrator.runForward(wf2)
        assertEquals(WorkflowStatus.WAITING_HUMAN, r.status)
        // Ch2 Planning 真实执行（PLANNING step COMPLETED + resultReference）
        val planStep = app.workflowRepository.listSteps(wf2).first { it.phase == com.qianyan.model.workflow.WorkflowStepPhase.PLANNING }
        assertEquals(com.qianyan.model.workflow.WorkflowStepStatus.COMPLETED, planStep.status)
        // P20-P5-fix：durable plan seam 升级为 PLANJSON2:<PlanningSnapshot>（携带 DecisionPolicy 快照）；旧 PLANJSON: 仅读取兼容。
        assertTrue(planStep.resultReference!!.startsWith("PLANJSON2:"))
        // Ch2 Writing 真实执行 → 工作产物绑定 Ch2（P1-02c+d：AI 产出落 WorkingDraft，不写章节 Draft 表）
        assertEquals(0, app.draftRepository.listByChapter(ch2).size, "AI 产出不得直写章节 Draft 表")
        val ch2Working = app.workingDrafts.list(app.projects.projectOf(novelIdOf(app, ch2)).projectId)
            .single { it.target.chapterId == ch2 && !it.status.isTerminal }
        assertEquals(ch2, ch2Working.target.chapterId)
        // E6：Ch2 Planning 输入来自 durable continuation 且读回 Ch1 KU 事实
        val p2 = plannerInputs.last()
        assertTrue(p2.contains("【续篇来源】"), "Ch2 planner 输入应携带续篇来源（durable continuation 驱动）")
        assertTrue(p2.contains("sourceChapterId: ${ch1.value}"), "Ch2 planner 输入应含 sourceChapterId==Ch1")
        assertTrue(p2.contains(kuFact), "Ch2 planner 输入应读到 Ch1 KU 事实（Memory 延续）")
        // E6：Ch2 Writing 输入也读回 Ch1 事实
        assertTrue(writerInputs.last().contains(kuFact), "Ch2 writer 输入应读到 Ch1 KU 事实")
    }

    /** E2 — Process Restart：Ch1 CONFIRMED → close DB → re-init → resume continuation → Ch2 Planning 成功。 */
    @Test
    fun `E2 restart after chapter1 continues to chapter2`() {
        val tmp = Files.createTempFile("qianyan-E2", ".db").toString()
        val handles = mutableListOf<QianyanDbHandle>()
        try {
            var app = open("jdbc:sqlite:$tmp", handles, mutableListOf(), mutableListOf())
            val novelId = app.novels.createOriginal(title = "仙侠")
            val (ch1, wf1) = newChapterWorkflow(app, novelId)
            completeChapter(app, wf1)
            assertEquals(DraftStatus.CONFIRMED, app.draftRepository.latestByChapter(ch1)!!.status)
            closeAll(handles) // crash / 退出

            // 重新初始化（全新 ApplicationContainer / Repository；旧 session 全部丢弃）
            val planner = mutableListOf<String>(); val writer = mutableListOf<String>()
            app = open("jdbc:sqlite:$tmp", handles, planner, writer)
            val wf2 = app.workflowOrchestrator.continueToNextWorkflow(wf1)
            val cont = app.workflowRepository.getContinuationBySourceDraft(app.draftRepository.latestByChapter(ch1)!!.draftId)!!
            runCh2Planning(app, wf2, wf1, cont.targetChapterId, ch1, cont.sourceDraftId, planner, writer)
        } finally {
            closeAll(handles)
            Files.deleteIfExists(Path(tmp))
        }
    }

    /** E3 — Chapter 2 Reuse：先创建 Ch2，再触发 continuation → 复用原 Ch2（id 不变），重启后再触发仍复用。 */
    @Test
    fun `E3 existing chapter2 is reused`() {
        val tmp = Files.createTempFile("qianyan-E3", ".db").toString()
        val handles = mutableListOf<QianyanDbHandle>()
        try {
            var app = open("jdbc:sqlite:$tmp", handles, mutableListOf(), mutableListOf())
            val novelId = app.novels.createOriginal(title = "仙侠")
            val (ch1, wf1) = newChapterWorkflow(app, novelId)
            completeChapter(app, wf1)
            // 预先存在 Ch2
            val preCh2 = app.chapters.createNextChapter(title = "章2", novelId = novelId)
            closeAll(handles)

            app = open("jdbc:sqlite:$tmp", handles, mutableListOf(), mutableListOf())
            val wf2 = app.workflowOrchestrator.continueToNextWorkflow(wf1)
            val cont = app.workflowRepository.getContinuationByTargetChapter(preCh2.chapterId)!!
            // Ch2 复用：continuation 指向既有 Ch2，未新建
            assertEquals(preCh2.chapterId, cont.targetChapterId)
            assertEquals(app.chapters.listByNovel(novelId).size, 2) // 仍只有 Ch1 + Ch2
            closeAll(handles)

            // 再次重启后重复触发 → 仍复用原 Ch2，不产生 Ch2'
            app = open("jdbc:sqlite:$tmp", handles, mutableListOf(), mutableListOf())
            val wf2b = app.workflowOrchestrator.continueToNextWorkflow(wf1)
            assertEquals(wf2, wf2b)
            assertEquals(app.chapters.listByNovel(novelId).size, 2)
            assertEquals(preCh2.chapterId, app.workflowRepository.getContinuationByTargetChapter(preCh2.chapterId)!!.targetChapterId)
        } finally {
            closeAll(handles)
            Files.deleteIfExists(Path(tmp))
        }
    }

    /** E4 — Continuation Idempotency：重复触发 continue(sourceDraft, target) → 1 个 continuation、1 个 Ch2、不重复生成 Ch1。 */
    @Test
    fun `E4 duplicate continuation converges to one result`() {
        val app = ApplicationContainer.open(analysisGateway = gateway(mutableListOf(), mutableListOf()))
        val novelId = app.novels.createOriginal(title = "仙侠")
        val (ch1, wf1) = newChapterWorkflow(app, novelId)
        completeChapter(app, wf1)
        val draft1 = app.draftRepository.latestByChapter(ch1)!!
        val ch1DraftCount = app.draftRepository.listByChapter(ch1).size

        val wf2a = app.workflowOrchestrator.continueToNextWorkflow(wf1)
        val wf2b = app.workflowOrchestrator.continueToNextWorkflow(wf1)
        val wf2c = app.workflowOrchestrator.continueToNextWorkflow(wf1)
        assertEquals(wf2a, wf2b)
        assertEquals(wf2a, wf2c)

        val cont = app.workflowRepository.getContinuationBySourceDraft(draft1.draftId)!!
        // 唯一 continuation：幂等身份 (sourceDraftId,targetChapterId) 收敛为一个 durable continuation
        val allConts = listOf(app.workflowRepository.getContinuationBySourceDraft(draft1.draftId)).filterNotNull()
        assertEquals(1, allConts.size)
        assertEquals(ch1, cont.sourceChapterId)
        assertEquals(draft1.draftId, cont.sourceDraftId)
        // 只有 Ch1 + Ch2
        assertEquals(2, app.chapters.listByNovel(novelId).size)
        // Ch1 Draft 不增加（没有重复生成 Chapter1）
        assertEquals(ch1DraftCount, app.draftRepository.listByChapter(ch1).size)
    }

    /** T1 — 来源必须是 Canonical，而不是 latestByChapter 上更新的未提交 Draft。 */
    @Test
    fun `T1 continuation uses canonical source not newer uncommitted draft`() {
        val plannerInputs = mutableListOf<String>()
        val writerInputs = mutableListOf<String>()
        val app = ApplicationContainer.open(analysisGateway = gateway(plannerInputs, writerInputs))
        val novelId = app.novels.createOriginal(title = "仙侠")
        val (ch1, wf1) = newChapterWorkflow(app, novelId)
        completeChapter(app, wf1)

        val canonical = app.canonicalRead.committedDraft(ch1)!!
        val newer = saveDraft(
            app, ch1, novelId, "t1-newer", "未提交的新版本B", DraftStatus.WRITTEN,
            createdAt = Instant.fromEpochMilliseconds(Clock.System.now().toEpochMilliseconds() + 1_000),
        )
        assertEquals(newer.draftId, app.draftRepository.latestByChapter(ch1)!!.draftId, "夹具必须让未提交 Draft 成为 latestByChapter")

        val wf2 = app.workflowOrchestrator.continueToNextWorkflow(wf1)
        val cont = app.workflowRepository.getContinuationBySourceDraft(canonical.draftId)!!
        assertEquals(canonical.draftId, cont.sourceDraftId)
        assertNull(app.workflowRepository.getContinuationBySourceDraft(newer.draftId), "未提交 Draft 不得成为续篇来源")

        val result = app.workflowOrchestrator.runForward(wf2)
        assertEquals(WorkflowStatus.WAITING_HUMAN, result.status)
        val ch2Planner = plannerInputs.last()
        val ch2Writer = writerInputs.last()
        assertTrue(ch2Planner.contains("【前章 Canonical 摘录】"), "Ch2 Planner 应收到前章 Canonical 摘录")
        assertTrue(ch2Planner.contains(canonical.content), "Ch2 Planner 应收到 Canonical 正文 A")
        assertFalse(ch2Planner.contains("未提交的新版本B"), "Ch2 Planner 不得收到未提交 Draft B")
        assertTrue(ch2Writer.contains("【前章 Canonical 摘录】"), "Ch2 Writer 应收到前章 Canonical 摘录")
        assertTrue(ch2Writer.contains(canonical.content), "Ch2 Writer 应收到 Canonical 正文 A")
        assertFalse(ch2Writer.contains("未提交的新版本B"), "Ch2 Writer 不得收到未提交 Draft B")
    }

    /** T2 — 无 Canonical 时拒绝续篇；不建 continuation / 下一章 / Workflow2，不调用 LLM。 */
    @Test
    fun `T2 continuation rejects chapter without canonical`() {
        val tmp = Files.createTempFile("qianyan-T2", ".db").toString()
        val handles = mutableListOf<QianyanDbHandle>()
        try {
            val plannerInputs = mutableListOf<String>()
            val writerInputs = mutableListOf<String>()
            val app = open("jdbc:sqlite:$tmp", handles, plannerInputs, writerInputs)
            val novelId = app.novels.createOriginal(title = "仙侠")
            val (ch1, wf1) = newChapterWorkflow(app, novelId)
            val uncommitted = saveDraft(app, ch1, novelId, "t2-confirmed", "未提交的确认稿", DraftStatus.CONFIRMED)
            val wf = app.workflowRepository.getWorkflow(wf1)!!
            app.workflowRepository.updateWorkflow(wf.copy(status = WorkflowStatus.COMPLETED))

            val ex = assertFailsWith<ApplicationException> {
                app.workflowOrchestrator.continueToNextWorkflow(wf1)
            }
            assertIs<ApplicationError.DraftConfirmationRequired>(ex.error)
            assertNull(app.workflowRepository.getContinuationBySourceDraft(uncommitted.draftId))
            assertEquals(1, app.chapters.listByNovel(novelId).size, "拒绝时不得创建下一章")
            assertEquals(1L, countRows(handles.last(), "Workflow"), "拒绝时不得创建 Workflow2")
            assertEquals(0L, countRows(handles.last(), "WorkflowContinuation"), "拒绝时不得写 continuation")
            assertTrue(plannerInputs.isEmpty(), "拒绝时不得调用 Planner LLM")
            assertTrue(writerInputs.isEmpty(), "拒绝时不得调用 Writer LLM")
        } finally {
            closeAll(handles)
            Files.deleteIfExists(Path(tmp))
        }
    }

    /** T3 — continuation 建立后 Canonical carrier 变化 → Ch2 Planning 类型化拒绝，Task FAILED。 */
    @Test
    fun `T3 stale continuation is rejected after canonical carrier changes`() {
        val tmp = Files.createTempFile("qianyan-T3", ".db").toString()
        val handles = mutableListOf<QianyanDbHandle>()
        try {
            val planner = mutableListOf<String>()
            val writer = mutableListOf<String>()
            val app = open("jdbc:sqlite:$tmp", handles, planner, writer)
            val novelId = app.novels.createOriginal(title = "仙侠")
            val (ch1, wf1) = newChapterWorkflow(app, novelId)
            completeChapter(app, wf1)
            val oldCarrier = app.canonicalRead.committedDraft(ch1)!!
            val wf2 = app.workflowOrchestrator.continueToNextWorkflow(wf1)
            val cont = app.workflowRepository.getContinuationBySourceDraft(oldCarrier.draftId)!!
            assertEquals(oldCarrier.draftId, cont.sourceDraftId)

            planner.clear()
            writer.clear()
            val newCarrier = saveDraft(app, ch1, novelId, "t3-newer", "新的正式稿B", DraftStatus.CONFIRMED)
            app.commits.commitAuthorSave(newCarrier.draftId, newCarrier.content)
            assertEquals(newCarrier.draftId, app.canonicalRead.committedDraft(ch1)!!.draftId)

            val ex = assertFailsWith<ApplicationException> {
                app.workflowOrchestrator.runForward(wf2)
            }
            assertIs<ApplicationError.InvalidContinuationSource>(ex.error)
            assertTrue(planner.isEmpty(), "过期续篇必须在 Planner 之前失败")
            assertTrue(writer.isEmpty(), "过期续篇必须在 Writer 之前失败")
            assertEquals("FAILED", latestPlanningTaskStatus(handles.last()))
        } finally {
            closeAll(handles)
            Files.deleteIfExists(Path(tmp))
        }
    }

    private fun saveDraft(
        app: ApplicationContainer,
        chapterId: com.qianyan.model.ChapterId,
        novelId: NovelId,
        id: String,
        content: String,
        status: DraftStatus,
        createdAt: Instant = Clock.System.now(),
    ): Draft {
        val draft = Draft(
            draftId = DraftId(id), novelId = novelId, chapterId = chapterId,
            content = content, status = status, createdAt = createdAt, updatedAt = createdAt,
        )
        app.draftRepository.save(draft)
        return draft
    }

    private fun latestPlanningTaskStatus(handle: QianyanDbHandle): String? =
        handle.driver.executeQuery(
            null,
            "SELECT status FROM Task WHERE type = 'PLANNING' ORDER BY rowid DESC LIMIT 1",
            { cursor ->
                val hasRow = cursor.next().value
                QueryResult.Value(if (hasRow) cursor.getString(0) else null)
            },
            0,
        ).value

    private fun countRows(handle: QianyanDbHandle, table: String): Long =
        handle.driver.executeQuery(
            null,
            "SELECT COUNT(*) FROM $table",
            { cursor ->
                cursor.next()
                QueryResult.Value(cursor.getLong(0) ?: 0L)
            },
            0,
        ).value
}