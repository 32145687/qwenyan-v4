package com.qianyan.application.usecase.workflow

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.qianyan.application.di.ApplicationContainer
import com.qianyan.model.BaseNovelId
import com.qianyan.model.MemoryEntryId
import com.qianyan.model.NovelId
import com.qianyan.model.VariantId
import com.qianyan.model.VariantScope
import com.qianyan.model.core.VariantContext
import com.qianyan.model.memory.MemoryEntry
import com.qianyan.model.memory.MemoryLayer
import com.qianyan.model.workflow.AttemptErrorCategory
import com.qianyan.model.workflow.HumanDecision
import com.qianyan.model.workflow.HumanGateStatus
import com.qianyan.model.workflow.Workflow
import com.qianyan.model.workflow.WorkflowAttemptStatus
import com.qianyan.model.workflow.WorkflowId
import com.qianyan.model.workflow.WorkflowKind
import com.qianyan.model.workflow.WorkflowStatus
import com.qianyan.model.workflow.WorkflowStepPhase
import com.qianyan.model.workflow.WorkflowStepStatus
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
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P12.2 · ConcurrentForward Audit（CF1–CF10）——多 Workflow 实例在同一本小说中安全共存/隔离回归测试。
 *
 * 不做真实并发调度器（本阶段只验证 durable 模型能否让多个 Workflow 共存、恢复、继续而不串线）。
 * 全部真实链路（UseCase → Task → AgentRuntime → Mock Provider），交替驱动 A / B 两个 Workflow。
 */
class ConcurrentForwardIsolationTest {

    /** 可审计 Fake LLM：按 Agent 计数，可按需让 Writer 首次失败 / Critic 抛错，以停在 Writing 之后。 */
    private class AuditedGateway(
        var criticPasses: List<Boolean> = emptyList(),
        var throwCritique: Boolean = false,
        var writerFailFirst: Boolean = false,
    ) : LLMGateway {
        var plannerCalls = 0; var writerCalls = 0; var criticCalls = 0; var revisionCalls = 0; var kuCalls = 0
        private var writerAttempt = 0

        override fun chat(request: ProviderRequest): ProviderResponse {
            val sys = request.messages.first { it.role == ChatRole.SYSTEM }.content
            val body = when {
                "StoryWriterAgent" in sys -> {
                    writerCalls++; writerAttempt++
                    if (writerFailFirst && writerAttempt == 1) throw ProviderException.Timeout("writer transient")
                    """{"content":"正文"}"""
                }
                "StoryCriticAgent" in sys -> {
                    criticCalls++
                    if (throwCritique) throw ProviderException.Timeout("critique stop (phase1)")
                    val passed = if (criticPasses.isEmpty()) true else criticPasses[(criticCalls - 1).coerceAtMost(criticPasses.size - 1)]
                    if (passed) """{"passed":true}""" else """{"passed":false,"issues":[{"field":"正文","severity":"ERROR","message":"需修订"}]}"""
                }
                "StoryRevisionAgent" in sys -> { revisionCalls++; """{"content":"修订版"}""" }
                "StoryKnowledgeUpdateAgent" in sys || "KnowledgeUpdateAgent" in sys -> {
                    kuCalls++; """{"changes":[{"changeId":"k1","operation":"ADD","target":"主角","content":"已突破金丹期"}]}"""
                }
                "StoryPlannerAgent" in sys -> { plannerCalls++; """{"chapterGoal":"揭开秘辛","mainConflict":"c","expectedEvents":["遇险"]}""" }
                else -> """{"content":"占位"}"""
            }
            return ProviderResponse(
                message = ChatMessage(ChatRole.ASSISTANT, buildJsonObject { put("answer", body) }.toString()),
                usage = Usage(10, 10, 20), finishReason = FinishReason.STOP,
            )
        }
    }

    /** 建一个 chapter + 专属 Workflow（同一 novel 下可多 Workflow，chapterId 不同）。经 Repository 原子建章（与 P12.1.8 一致）。 */
    private fun chapterWorkflow(app: ApplicationContainer, novelId: NovelId, variantId: VariantId?): Pair<com.qianyan.model.ChapterId, WorkflowId> {
        val now = Clock.System.now()
        val ch = app.chapterRepository.createNextChapter(
            com.qianyan.model.story.Chapter(
                chapterId = com.qianyan.model.ChapterId(java.util.UUID.randomUUID().toString()),
                novelId = novelId, variantId = variantId,
                scope = if (variantId == null) VariantScope.ORIGINAL else VariantScope.VARIANT,
                title = "章", order = 0, status = com.qianyan.model.story.ChapterStatus.PLANNED,
                createdAt = now, updatedAt = now,
            ),
        )
        val wfId = WorkflowId("wf:${ch.chapterId.value}")
        app.workflowRepository.createWorkflow(
            Workflow(wfId, novelId, variantId, WorkflowKind.WRITE_NOVEL, status = WorkflowStatus.CREATED,
                activeChapterId = ch.chapterId, createdAt = Clock.System.now(), updatedAt = Clock.System.now()),
        )
        return ch.chapterId to wfId
    }

    private fun open(url: String, handles: MutableList<QianyanDbHandle>, gateway: LLMGateway): ApplicationContainer {
        val h = QianyanDbFactory.open(url)
        handles += h
        return ApplicationContainer.fromDriver(h.driver, analysisGateway = gateway)
    }

    private fun closeAll(handles: MutableList<QianyanDbHandle>) {
        handles.forEach { (it.driver as JdbcSqliteDriver?)?.getConnection()?.close() }
        handles.clear()
    }

    private fun runToGate(app: ApplicationContainer, wfId: WorkflowId): WorkflowStatus {
        val r = app.workflowOrchestrator.runForward(wfId)
        if (r.status == WorkflowStatus.FAILED) return r.status
        return app.workflowRepository.getWorkflow(wfId)!!.status
    }

    /** CF1 — 同一 Novel 下两个 Workflow 共存并能推进。 */
    @Test
    fun `CF1 two workflows same novel coexist`() {
        val app = ApplicationContainer.open(analysisGateway = AuditedGateway())
        val novelId = app.novels.createOriginal(title = "仙侠")
        val (ch10, wfA) = chapterWorkflow(app, novelId, null)
        val (ch11, wfB) = chapterWorkflow(app, novelId, null)
        assertNotEquals(ch10, ch11)

        assertEquals(WorkflowStatus.WAITING_HUMAN, runToGate(app, wfA))
        assertEquals(WorkflowStatus.WAITING_HUMAN, runToGate(app, wfB))

        assertNotEquals(wfA, wfB)
        assertEquals(ch10, app.workflowRepository.getWorkflow(wfA)!!.activeChapterId)
        assertEquals(ch11, app.workflowRepository.getWorkflow(wfB)!!.activeChapterId)
    }

    /** CF2 — Draft 隔离：latestByChapter 各自归位。 */
    @Test
    fun `CF2 draft isolation`() {
        val app = ApplicationContainer.open(analysisGateway = AuditedGateway())
        val novelId = app.novels.createOriginal(title = "仙侠")
        val (ch10, wfA) = chapterWorkflow(app, novelId, null)
        val (ch11, wfB) = chapterWorkflow(app, novelId, null)
        runToGate(app, wfA); runToGate(app, wfB)

        val d10 = app.draftRepository.latestByChapter(ch10)!!
        val d11 = app.draftRepository.latestByChapter(ch11)!!
        assertEquals(ch10, d10.chapterId)
        assertEquals(ch11, d11.chapterId)
        assertNotEquals(d10.draftId, d11.draftId) // 同一个 novel 的两个 chapter 不得共用同一 draft
        assertEquals(1, app.draftRepository.listByChapter(ch10).size)
        assertEquals(1, app.draftRepository.listByChapter(ch11).size)
    }

    /** CF3 — Attempt 隔离：A 重试一次(2 attempts)、B 单次(1 attempt)，attempt 不共享。 */
    @Test
    fun `CF3 retry attempts are isolated per workflow`() {
        val app = ApplicationContainer.open(analysisGateway = AuditedGateway(writerFailFirst = true))
        val novelId = app.novels.createOriginal(title = "仙侠")
        val (_, wfA) = chapterWorkflow(app, novelId, null)
        val (_, wfB) = chapterWorkflow(app, novelId, null)
        runToGate(app, wfA); runToGate(app, wfB)

        val stepA = app.workflowRepository.listSteps(wfA).first { it.phase == WorkflowStepPhase.WRITING }
        val stepB = app.workflowRepository.listSteps(wfB).first { it.phase == WorkflowStepPhase.WRITING }
        val attemptA = app.workflowRepository.listAttempts(stepA.stepId)
        val attemptB = app.workflowRepository.listAttempts(stepB.stepId)
        assertEquals(2, attemptA.size)
        assertEquals(WorkflowAttemptStatus.FAILED, attemptA[0].status)
        assertEquals(AttemptErrorCategory.RETRYABLE, attemptA[0].errorCategory)
        assertEquals(WorkflowAttemptStatus.COMPLETED, attemptA[1].status)
        assertEquals(1, attemptB.size) // B 不受 A 的 retry 影响
        assertEquals(WorkflowAttemptStatus.COMPLETED, attemptB[0].status)
    }

    /** CF4 — Human Gate 隔离：approve(A) 只影响 A，不影响 B。 */
    @Test
    fun `CF4 human gate isolation`() {
        val app = ApplicationContainer.open(analysisGateway = AuditedGateway())
        val novelId = app.novels.createOriginal(title = "仙侠")
        val (ch10, wfA) = chapterWorkflow(app, novelId, null)
        val (_, wfB) = chapterWorkflow(app, novelId, null)
        runToGate(app, wfA); runToGate(app, wfB)
        val draft10Before = app.draftRepository.latestByChapter(ch10)!!.draftId

        val gateA = app.workflowRepository.getGate(app.workflowRepository.getWorkflow(wfA)!!.pendingGateId!!)!!
        val gateB = app.workflowRepository.getGate(app.workflowRepository.getWorkflow(wfB)!!.pendingGateId!!)!!

        app.workflowOrchestrator.approveGate(gateA.gateId)

        // A：gate RESOLVED/APPROVED
        val gA = app.workflowRepository.getGate(gateA.gateId)!!
        assertEquals(HumanGateStatus.RESOLVED, gA.status)
        assertEquals(HumanDecision.APPROVED, gA.decision)
        // B：gate 保持 PENDING，B 未受影响
        val gB = app.workflowRepository.getGate(gateB.gateId)!!
        assertEquals(HumanGateStatus.PENDING, gB.status)
        assertEquals(HumanDecision.PENDING, gB.decision)
        assertEquals(WorkflowStatus.WAITING_HUMAN, app.workflowRepository.getWorkflow(wfB)!!.status)
        // approve A 不产生第二个业务结果污染 A 回退：A 仍 WAITING（approve 后未 runForward 前）
        assertEquals(draft10Before, app.draftRepository.latestByChapter(ch10)!!.draftId)
    }

    /** CF5 — Revision 隔离：A 修订产生 lineage；B 不修订，二者不串。 */
    @Test
    fun `CF5 revision lineage isolated`() {
        val app = ApplicationContainer.open(analysisGateway = AuditedGateway(criticPasses = listOf(false, true)))
        val novelId = app.novels.createOriginal(title = "仙侠")
        val (ch10, wfA) = chapterWorkflow(app, novelId, null)
        val (ch11, wfB) = chapterWorkflow(app, novelId, null)
        runToGate(app, wfA); runToGate(app, wfB)

        val dA = app.draftRepository.listByChapter(ch10)
        val dB = app.draftRepository.listByChapter(ch11)
        assertEquals(2, dA.size) // A：原稿 + 修订稿（revision lineage）
        assertEquals(dA[0].draftId, dA[1].previousDraftId) // 修订父链只在 ch10 内部
        assertEquals(1, dB.size) // B：无修订
        assertNull(dB[0].previousDraftId) // 未被跨 workflow 的 revision 父引用污染
        assertEquals(1, app.workflowRepository.listSteps(wfA).count { it.phase == WorkflowStepPhase.REVISION })
        assertEquals(0, app.workflowRepository.listSteps(wfB).count { it.phase == WorkflowStepPhase.REVISION })
    }

    /** CF6 — StoryState/Memory 承接 + Workflow 临时态不泄漏：
     *  A 完成 KU 的「业务事实」按 Novel 规则对后续可见；A 的 Draft/Task 等临时态不进入 B 的 chapter。 */
    @Test
    fun `CF6 story continuity and workflow transient isolation`() {
        val app = ApplicationContainer.open(analysisGateway = AuditedGateway())
        val novelId = app.novels.createOriginal(title = "仙侠")
        val (ch10, wfA) = chapterWorkflow(app, novelId, null)
        val (ch11, wfB) = chapterWorkflow(app, novelId, null)

        // A 全链完成 → KU 沉淀事实
        runToGate(app, wfA)
        val gateA = app.workflowRepository.getGate(app.workflowRepository.getWorkflow(wfA)!!.pendingGateId!!)!!
        app.workflowOrchestrator.approveGate(gateA.gateId)
        assertEquals(WorkflowStatus.COMPLETED, runToGate(app, wfA))
        assertTrue(app.storyWorldContextResolver.resolve(novelId).memories.any { it.contains("已突破金丹期") })

        // B 独立推进至 gate；B 的 chapter 只含 B 自己的 draft
        assertEquals(WorkflowStatus.WAITING_HUMAN, runToGate(app, wfB))
        assertEquals(ch11, app.draftRepository.latestByChapter(ch11)!!.chapterId)
        assertEquals(1, app.draftRepository.listByChapter(ch11).size)
        // A 的 chapter 只含 A 自己的 draft（不因 B 增多 / 被 B 覆盖）
        assertEquals(1, app.draftRepository.listByChapter(ch10).size)
        assertEquals(DraftStatus.CONFIRMED, app.draftRepository.latestByChapter(ch10)!!.status)
    }

    /** CF7 — Variant 隔离：ORIGINAL 与 VARIANT 的 Memory/StoryState 不串；Workflow Draft scope 正确。 */
    @Test
    fun `CF7 variant isolation`() {
        val app = ApplicationContainer.open(analysisGateway = AuditedGateway())
        val novelId = app.novels.createOriginal(title = "仙侠")
        val vx = VariantId("vx")
        app.novels.createVariant(VariantContext(BaseNovelId(novelId.value), vx), "VX", variantId = vx)
        assertEquals(vx, app.novelRepository.getVariant(vx)!!.variantId)

        val now = Clock.System.now()
        app.memoryRepository.saveEntry(MemoryEntry(MemoryEntryId("m-orig"), novelId, null, VariantScope.ORIGINAL, MemoryLayer.WRITING, "原版独有事实", createdAt = now, updatedAt = now))
        app.memoryRepository.saveEntry(MemoryEntry(MemoryEntryId("m-vx"), novelId, vx, VariantScope.VARIANT, MemoryLayer.WRITING, "变体独有事实", createdAt = now, updatedAt = now))

        val orig = app.storyWorldContextResolver.resolve(novelId) // ORIGINAL
        val vctx = app.storyWorldContextResolver.resolve(novelId, vx) // VARIANT
        assertEquals(VariantScope.ORIGINAL, orig.scope)
        assertEquals(VariantScope.VARIANT, vctx.scope)
        assertTrue(orig.memories.any { it.contains("原版独有") })
        assertTrue(orig.memories.none { it.contains("变体独有") }, "ORIGINAL 不得读到 Variant 独有事实")
        assertTrue(vctx.memories.any { it.contains("变体独有") }, "Variant 读到自身事实")

        // 两个 Workflow：ORIGINAL(Ch10) 与 VARIANT(Ch11)，Draft scope 不串
        val (ch10, wfA) = chapterWorkflow(app, novelId, null)
        val (ch11, wfB) = chapterWorkflow(app, novelId, vx)
        runToGate(app, wfA); runToGate(app, wfB)
        assertEquals(VariantScope.ORIGINAL, app.draftRepository.latestByChapter(ch10)!!.scope)
        assertEquals(VariantScope.VARIANT, app.draftRepository.latestByChapter(ch11)!!.scope)
        assertEquals(null, app.draftRepository.latestByChapter(ch10)!!.variantId)
        assertEquals(vx, app.draftRepository.latestByChapter(ch11)!!.variantId)
    }

    /** CF8 — Continuation 幂等 + 每个 Workflow 各自的 continuation 独立。 */
    @Test
    fun `CF8 continuation idempotent and independent per source`() {
        val app = ApplicationContainer.open(analysisGateway = AuditedGateway())
        val novelId = app.novels.createOriginal(title = "仙侠")
        val (ch10, wfA) = chapterWorkflow(app, novelId, null)
        val (ch11, wfB) = chapterWorkflow(app, novelId, null)
        completeChapter(app, wfA)
        completeChapter(app, wfB)
        val draftA = app.draftRepository.latestByChapter(ch10)!!.draftId
        val draftB = app.draftRepository.latestByChapter(ch11)!!.draftId

        // continue(A) ×3 → 同一个 continuation、同一个 target
        val tgtA1 = app.workflowOrchestrator.continueToNextWorkflow(wfA)
        val tgtA2 = app.workflowOrchestrator.continueToNextWorkflow(wfA)
        val tgtA3 = app.workflowOrchestrator.continueToNextWorkflow(wfA)
        assertEquals(tgtA1, tgtA2); assertEquals(tgtA1, tgtA3)
        val contA = app.workflowRepository.getContinuationBySourceDraft(draftA)!!
        assertEquals(ch10, contA.sourceChapterId)
        assertEquals(draftA, contA.sourceDraftId)
        assertEquals(ch11, contA.targetChapterId) // A 续篇落到已有 Ch11（不新建）

        // continue(B) ×3 → B 自己的 continuation、独立 target（新章）
        val tgtB1 = app.workflowOrchestrator.continueToNextWorkflow(wfB)
        val tgtB2 = app.workflowOrchestrator.continueToNextWorkflow(wfB)
        assertEquals(tgtB1, tgtB2)
        val contB = app.workflowRepository.getContinuationBySourceDraft(draftB)!!
        assertEquals(draftB, contB.sourceDraftId)
        assertNotEquals(draftA, contB.sourceDraftId)

        // A 续篇复用既有 Ch11（不新建）；B 续篇新建 Ch12 → 共 3 章，无重复
        assertEquals(ch11, contA.targetChapterId)
        assertNotEquals(ch11, contB.targetChapterId)
        val chapters = app.chapters.listByNovel(novelId)
        assertEquals(3, chapters.size)
        assertEquals(3, chapters.map { it.chapterId }.distinct().size)
    }

    /** CF9 — Crash/Rebuild 隔离：A/B 交错推进后关库重建，各自续走自己的 chapter。 */
    @Test
    fun `CF9 crash rebuild isolation`() {
        val tmp = Files.createTempFile("qianyan-CF9", ".db").toString()
        val handles = mutableListOf<QianyanDbHandle>()
        try {
            var app = open("jdbc:sqlite:$tmp", handles, AuditedGateway(throwCritique = true))
            val novelId = app.novels.createOriginal(title = "仙侠")
            val (ch10, wfA) = chapterWorkflow(app, novelId, null)
            val (ch11, wfB) = chapterWorkflow(app, novelId, null)
            // 交错执行：A Planning/Writing → B Planning/Writing（critique 抛错停在 Writing 之后）
            try { app.workflowOrchestrator.runForward(wfA) } catch (_: Throwable) {}
            try { app.workflowOrchestrator.runForward(wfB) } catch (_: Throwable) {}
            assertEquals(null, app.draftRepository.latestByChapter(ch10)!!.variantId)
            assertEquals(null, app.draftRepository.latestByChapter(ch11)!!.variantId)
            closeAll(handles)

            app = open("jdbc:sqlite:$tmp", handles, AuditedGateway()) // 恢复后 critic 通过
            assertEquals(WorkflowStatus.WAITING_HUMAN, runToGate(app, wfA))
            assertEquals(WorkflowStatus.WAITING_HUMAN, runToGate(app, wfB))
            // 不串线
            assertEquals(ch10, app.draftRepository.latestByChapter(ch10)!!.chapterId)
            assertEquals(ch11, app.draftRepository.latestByChapter(ch11)!!.chapterId)
            assertNotEquals(ch10, app.draftRepository.latestByChapter(ch11)!!.chapterId)
            assertEquals(ch10, app.workflowRepository.getWorkflow(wfA)!!.activeChapterId)
            assertEquals(ch11, app.workflowRepository.getWorkflow(wfB)!!.activeChapterId)
        } finally {
            closeAll(handles)
            Files.deleteIfExists(Path(tmp))
        }
    }

    /** CF10 — LLM Recovery 隔离：rebuild 后已 COMPLETED 的 Planning/Writing 不再重调 LLM（A 与 B 均不重跑已完步骤）。 */
    @Test
    fun `CF10 llm recovery isolation`() {
        val tmp = Files.createTempFile("qianyan-CF10", ".db").toString()
        val handles = mutableListOf<QianyanDbHandle>()
        try {
            val gw1 = AuditedGateway(throwCritique = true)
            var app = open("jdbc:sqlite:$tmp", handles, gw1)
            val novelId = app.novels.createOriginal(title = "仙侠")
            val (_, wfA) = chapterWorkflow(app, novelId, null)
            val (_, wfB) = chapterWorkflow(app, novelId, null)
            try { app.workflowOrchestrator.runForward(wfA) } catch (_: Throwable) {}
            try { app.workflowOrchestrator.runForward(wfB) } catch (_: Throwable) {}
            // phase1：两个 workflow 各完成过 Planning + Writing
            assertEquals(2, gw1.plannerCalls)
            assertEquals(2, gw1.writerCalls)
            closeAll(handles)

            val gw2 = AuditedGateway()
            app = open("jdbc:sqlite:$tmp", handles, gw2)
            assertEquals(WorkflowStatus.WAITING_HUMAN, runToGate(app, wfA))
            assertEquals(WorkflowStatus.WAITING_HUMAN, runToGate(app, wfB))
            // 已 COMPLETED 的 Planning/Writing 绝不重调（两 Workflow 皆不重跑）
            assertEquals(0, gw2.plannerCalls)
            assertEquals(0, gw2.writerCalls)
            // 恢复后继续执行的是未完成步骤（Critique → Finalize → Confirmation），A/B 各一次
            assertEquals(2, gw2.criticCalls)
        } finally {
            closeAll(handles)
            Files.deleteIfExists(Path(tmp))
        }
    }

    private fun completeChapter(app: ApplicationContainer, wfId: WorkflowId) {
        assertEquals(WorkflowStatus.WAITING_HUMAN, runToGate(app, wfId))
        val gate = app.workflowRepository.getGate(app.workflowRepository.getWorkflow(wfId)!!.pendingGateId!!)!!
        app.workflowOrchestrator.approveGate(gate.gateId)
        assertEquals(WorkflowStatus.COMPLETED, runToGate(app, wfId))
    }
}