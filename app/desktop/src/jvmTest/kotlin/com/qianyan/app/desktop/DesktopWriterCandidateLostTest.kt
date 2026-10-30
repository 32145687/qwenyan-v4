package com.qianyan.app.desktop

import com.qianyan.app.desktop.adapter.DesktopProviderAssembler
import com.qianyan.app.desktop.ui.write.CANDIDATE_LOST_MESSAGE
import com.qianyan.app.desktop.ui.write.WriterController
import com.qianyan.app.desktop.ui.write.WriterUiState
import com.qianyan.app.desktop.ui.write.messageFor
import com.qianyan.application.di.ApplicationContainer
import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.application.usecase.workflow.ChapterPhase
import com.qianyan.application.usecase.workflow.ChapterWorkflowGateway
import com.qianyan.application.usecase.workflow.ChapterWorkflowProgress
import com.qianyan.application.usecase.writing.WriterChapterContext
import com.qianyan.application.usecase.writing.WriterGateway
import com.qianyan.application.usecase.writing.WriterPendingProposal
import com.qianyan.model.ChapterId
import com.qianyan.model.DraftId
import com.qianyan.model.NovelId
import com.qianyan.model.VariantId
import com.qianyan.model.change.TextDiffer
import com.qianyan.model.story.Chapter
import com.qianyan.model.workflow.HumanDecision
import com.qianyan.model.workflow.HumanGateStatus
import com.qianyan.model.workflow.WorkflowKind
import com.qianyan.model.workingdraft.WorkingDraftId
import com.qianyan.model.writing.Draft
import com.qianyan.provider.InMemoryProviderCredentialStore
import com.qianyan.provider.ProviderConfiguration
import com.qianyan.provider.ProviderType
import com.qianyan.provider.impl.DefaultProviderAssembler
import com.qianyan.storage.db.QianyanDbFactory
import com.qianyan.storage.db.QianyanDbHandle
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.datetime.Clock

/**
 * T-O47 · 重启后失效候选 UI。
 *
 * 场景：Gate 是 durable 的（Workflow PENDING），但待审候选 WorkingDraft 只存在进程内存
 * （[com.qianyan.application.usecase.draft.WorkingDraftUseCases] 的存储决策），进程重启后
 * `waitingForUser=true` 而 `pendingProposal=null`。
 *
 * 语义边界（T-O47-FIX）：
 *  - 批准**依赖**候选 artifact ⇒ 失效时不可用；
 *  - 拒绝只结束当前 Gate（`ChapterWorkflowFacade.reject` 不读 artifact）⇒ 失效时仍可用；
 *  - `abandonRound` 只接受 RESOLVED+REJECTED，不能处理 PENDING，因此它不是本场景的出口。
 */
class DesktopWriterCandidateLostTest {

    private val opened = mutableListOf<app.cash.sqldelight.db.SqlDriver>()

    @org.junit.jupiter.api.AfterEach
    fun closeDrivers() {
        opened.forEach { runCatching { it.close() } }
        opened.clear()
    }

    // ---------- 1. 纯 UI 状态 ----------

    @Test
    fun `candidate lost disables approve but keeps reject`() {
        val lost = WriterUiState(isLoaded = true, waitingForUser = true, pendingProposal = null)
        assertTrue(lost.candidateLost, "人工门仍在但候选为空 = 候选已失效")
        assertFalse(lost.canApprove, "失效候选没有批准对象")
        assertTrue(lost.canReject, "拒绝只结束 Gate、不依赖 artifact，仍应可用")

        val alive = lost.copy(pendingProposal = proposal())
        assertFalse(alive.candidateLost)
        assertTrue(alive.canApprove, "正常待决 Gate：批准与拒绝同时可用")
        assertTrue(alive.canReject)
    }

    @Test
    fun `missing artifact error maps to the explicit candidate lost copy`() {
        assertEquals("候选已失效（可能因进程重启）：请重新发起任务。", CANDIDATE_LOST_MESSAGE)

        val mapped = messageFor(ApplicationException(ApplicationError.RestoreFailure("该轮 AI 工作产物已失效")))
        assertEquals(CANDIDATE_LOST_MESSAGE, mapped, "不得落回泛化的「操作失败，请重试」")
    }

    // ---------- 2. Controller + 替身：失效候选只拒绝、不批准 ----------

    @Test
    fun `controller rejects a lost candidate and never approves`() {
        val gate = GateState(waitingForUser = true, proposal = null)
        val workflow = CountingWorkflow(gate)
        val controller = controller(StubWriterGateway(gate), workflow)
        controller.load()

        val before = controller.uiState.value
        assertTrue(before.isLoaded)
        assertTrue(before.waitingForUser)
        assertNull(before.pendingProposal)
        assertTrue(before.candidateLost)
        assertFalse(before.canApprove)
        assertTrue(before.canReject)

        controller.approveGate()
        assertEquals(0, workflow.approveCalls, "失效候选不得调用 Workflow approve")

        controller.rejectGate()
        assertEquals(1, workflow.rejectCalls, "失效候选仍可经既有 Workflow reject 关闭 Gate")
        assertEquals(0, workflow.approveCalls)

        val after = controller.uiState.value
        assertNull(after.error)
        assertFalse(after.waitingForUser, "拒绝成功后回到普通编辑态")
        assertFalse(after.candidateLost)
    }

    @Test
    fun `approval that loses its artifact surfaces the explicit copy`() {
        val gate = GateState(waitingForUser = true, proposal = proposal())
        val failure = ApplicationException(ApplicationError.RestoreFailure("内存工作区不含 WORKING:lost"))
        val workflow = CountingWorkflow(gate, approveFailure = failure)
        val controller = controller(StubWriterGateway(gate), workflow)
        controller.load()
        assertTrue(controller.uiState.value.canApprove, "前置：有候选时「通过确认」可用")
        assertTrue(controller.uiState.value.canReject, "正常待决 Gate：拒绝同时可用")

        controller.approveGate()

        val state = controller.uiState.value
        assertEquals(1, workflow.approveCalls)
        assertEquals(0, workflow.rejectCalls)
        assertFalse(state.isApproving)
        assertEquals(CANDIDATE_LOST_MESSAGE, state.error, "缺失工作产物必须显式提示候选已失效")
    }

    // ---------- 3. 真实 DB + 新进程（同一 DB 文件）：复现 G6 重启 ----------

    @Test
    fun `after restart the durable gate stays pending while the candidate is gone`() {
        val dir = Files.createTempDirectory("qianyan-desktop-candidate-lost")
        val dbPath = dir.resolve("qianyan.db").toAbsolutePath().toString()

        // —— 重启前：真实 Workflow 停在人工门，候选在工作区 ——
        val before = openContainer(dbPath)
        val novelId = before.container.novels.createOriginal(
            title = "T-O47 重启测试",
            genre = listOf("东方幻想"),
            synopsis = "候选失效场景",
        )
        val chapterId = before.container.chapters.createNextChapter("第 1 章 · 重启", novelId).chapterId
        var guard = 0
        while (guard++ < 12 && !before.container.workflowFacade.getChapterProgress(chapterId).waitingForUser) {
            before.container.writerGateway.continueWriting(novelId, null, chapterId)
        }
        assertTrue(before.container.workflowFacade.getChapterProgress(chapterId).waitingForUser, "前置：必须停在人工门")
        assertNotNull(
            before.container.writerGateway.loadContext(novelId, null, chapterId).pendingProposal,
            "前置：重启前候选仍在",
        )
        val projectId = before.container.projects.projectOf(novelId).projectId
        val gateId = assertNotNull(
            before.container.workflowRepository.getWorkflowByActiveChapter(chapterId)?.pendingGateId,
        )
        assertEquals(0, before.container.commits.history(projectId).size)
        assertNull(before.container.canonicalRead.committedContent(chapterId))
        // 进程退出：内存工作区（WorkingDraft 候选）随之丢失，durable Workflow / Gate 保留。
        before.driver.close()

        // —— 重启后：同一 DB 文件、全新 Container（空工作区）——
        val after = openContainer(dbPath)
        val progress = after.container.workflowFacade.getChapterProgress(chapterId)
        assertTrue(progress.waitingForUser, "durable Gate 重启后仍为 PENDING")
        val gate = assertNotNull(after.container.workflowRepository.getGate(gateId))
        assertEquals(HumanGateStatus.PENDING, gate.status)
        val context = after.container.writerGateway.loadContext(novelId, null, chapterId)
        assertTrue(context.waitingForUser)
        assertNull(context.pendingProposal, "重启后的候选工作产物必然丢失")

        val chapter = assertNotNull(after.container.chapters.findById(chapterId))
        val controller = WriterController(
            novelId = novelId,
            chapter = chapter,
            gateway = after.container.writerGateway,
            workflow = after.container.workflowFacade,
            scope = CoroutineScope(Dispatchers.Unconfined),
            ioDispatcher = Dispatchers.Unconfined,
        )
        controller.load()
        val state = controller.uiState.value
        assertTrue(state.isLoaded)
        assertTrue(state.waitingForUser, "G6 实际值：waitingForUser=true")
        assertNull(state.pendingProposal, "G6 实际值：pendingProposal=null")
        assertTrue(state.candidateLost)
        assertFalse(state.canApprove, "失效候选不得提供批准")
        assertTrue(state.canReject, "拒绝不依赖 artifact：失效 Gate 仍可关闭")

        // 批准入口不可用（controller 直接短路，不触达 Workflow）。
        controller.approveGate()
        assertEquals(HumanGateStatus.PENDING, assertNotNull(after.container.workflowRepository.getGate(gateId)).status)
        assertNull(after.container.canonicalRead.committedContent(chapterId))
        assertEquals(0, after.container.commits.history(projectId).size)

        // 直接尝试批准（模拟任何绕过 UI 的路径）→ 类型化 RestoreFailure → 显式文案；不改状态。
        val failure = assertFailsWith<ApplicationException> { after.container.workflowFacade.approve(chapterId) }
        assertEquals(CANDIDATE_LOST_MESSAGE, messageFor(failure))
        assertEquals(HumanGateStatus.PENDING, assertNotNull(after.container.workflowRepository.getGate(gateId)).status)

        // 拒绝失效提案：不需要 artifact，Gate → RESOLVED+REJECTED；Canonical / commits 不变。
        controller.rejectGate()
        val afterReject = controller.uiState.value
        assertNull(afterReject.error)
        assertFalse(afterReject.waitingForUser, "拒绝成功后回到普通编辑态")
        assertFalse(afterReject.candidateLost)
        val resolved = assertNotNull(after.container.workflowRepository.getGate(gateId))
        assertEquals(HumanGateStatus.RESOLVED, resolved.status)
        assertEquals(HumanDecision.REJECTED, resolved.decision)
        assertEquals(gateId, after.container.workflowRepository.getWorkflowByActiveChapter(chapterId)?.pendingGateId)
        assertNull(after.container.canonicalRead.committedContent(chapterId))
        assertEquals(0, after.container.commits.history(projectId).size)
    }

    // ---------- 4. 正文页接线守卫 ----------

    @Test
    fun `write page offers reject for lost candidates and agent only after the gate closes`() {
        val page = File("src/jvmMain/kotlin/com/qianyan/app/desktop/ui/pages/WritePage.kt")
        assertTrue(page.isFile, "找不到桌面正文页源码：$page")
        val code = page.readText()
        assertTrue("CANDIDATE_LOST_MESSAGE" in code, "正文页必须显示候选失效文案")

        val lostBranch = code.substringAfter("if (ui.waitingForUser) {").substringBefore("} else {")
        assertTrue("拒绝失效提案" in lostBranch, "失效候选必须提供拒绝入口")
        assertFalse("通过确认" in lostBranch, "失效候选不得提供批准入口")
        assertTrue(
            "ActionButton(\"拒绝失效提案\", ActionLevel.SECONDARY, ui.canReject, onReject)" in code,
            "「拒绝失效提案」必须绑定既有 onReject",
        )
        assertTrue("去 Agent 重新发起" in code, "Gate 关闭后仍保留重新发起入口")
        assertTrue("if (!ui.waitingForUser)" in code, "PENDING Gate 未关闭时不得引导去 Agent")
    }

    // ---------- fixtures ----------

    /** 可变的门状态：拒绝会关闭 Gate（与真实 facade 的 reject 语义一致）。 */
    private class GateState(
        var waitingForUser: Boolean,
        var proposal: WriterPendingProposal?,
    )

    private fun proposal() = WriterPendingProposal(
        baseContent = "原文",
        candidateContent = "候选",
        diff = TextDiffer.diff("原文", "候选"),
    )

    private fun controller(gateway: WriterGateway, workflow: ChapterWorkflowGateway): WriterController =
        WriterController(
            novelId = NovelId("novel-t-o47"),
            chapter = Chapter(
                chapterId = ChapterId("chapter-t-o47"),
                novelId = NovelId("novel-t-o47"),
                title = "第 1 章 · 候选失效",
                order = 1,
                createdAt = Clock.System.now(),
            ),
            gateway = gateway,
            workflow = workflow,
            scope = CoroutineScope(Dispatchers.Unconfined),
            ioDispatcher = Dispatchers.Unconfined,
        )

    private fun openContainer(dbPath: String): OpenedContainer {
        val handle: QianyanDbHandle = QianyanDbFactory.open("jdbc:sqlite:$dbPath")
        opened += handle.driver
        val container = ApplicationContainer.fromDriver(
            handle.driver,
            DesktopProviderAssembler(DefaultProviderAssembler(InMemoryProviderCredentialStore())),
            ProviderConfiguration(ProviderType.MOCK),
        )
        return OpenedContainer(container, handle.driver)
    }

    private class OpenedContainer(val container: ApplicationContainer, val driver: app.cash.sqldelight.db.SqlDriver)

    private class StubWriterGateway(private val gate: GateState) : WriterGateway {
        override fun loadContext(novelId: NovelId, variantId: VariantId?, chapterId: ChapterId): WriterChapterContext =
            WriterChapterContext(
                novelId = novelId,
                novelTitle = "T-O47 测试作品",
                chapterId = chapterId,
                chapterTitle = "第 1 章 · 候选失效",
                draft = null,
                phase = if (gate.waitingForUser) ChapterPhase.WAITING_CONFIRMATION else ChapterPhase.NOT_STARTED,
                waitingForUser = gate.waitingForUser,
                // 与真实 WriterFacade 一致：只有 Gate 仍 PENDING 时才投影候选。
                pendingProposal = gate.proposal?.takeIf { gate.waitingForUser },
            )

        override fun saveContent(draftId: DraftId, content: String): Draft = error("saveContent 不应被调用")
        override fun continueWriting(novelId: NovelId, variantId: VariantId?, chapterId: ChapterId): ChapterWorkflowProgress =
            error("continueWriting 不应被调用")
        override fun rewrite(novelId: NovelId, variantId: VariantId?, chapterId: ChapterId): ChapterWorkflowProgress =
            error("rewrite 不应被调用")
        override fun rejectGate(chapterId: ChapterId): ChapterWorkflowProgress = error("rejectGate 不应被调用")
    }

    private class CountingWorkflow(
        private val gate: GateState,
        private val approveFailure: Throwable? = null,
    ) : ChapterWorkflowGateway {
        var approveCalls: Int = 0
        var rejectCalls: Int = 0

        override fun startChapter(
            novelId: NovelId,
            variantId: VariantId?,
            chapterId: ChapterId,
            kind: WorkflowKind,
        ): ChapterWorkflowProgress = error("startChapter 不应被调用")

        override fun advance(chapterId: ChapterId): ChapterWorkflowProgress = error("advance 不应被调用")
        override fun resume(chapterId: ChapterId): ChapterWorkflowProgress = error("resume 不应被调用")

        override fun getChapterProgress(chapterId: ChapterId): ChapterWorkflowProgress = ChapterWorkflowProgress(
            chapterId = chapterId,
            novelId = NovelId("novel-t-o47"),
            variantId = null,
            phase = if (gate.waitingForUser) ChapterPhase.WAITING_CONFIRMATION else ChapterPhase.NOT_STARTED,
            waitingForUser = gate.waitingForUser,
            revisionCount = 0,
            draftId = null,
        )

        override fun approve(chapterId: ChapterId): ChapterWorkflowProgress {
            approveCalls++
            approveFailure?.let { throw it }
            return getChapterProgress(chapterId)
        }

        override fun reject(chapterId: ChapterId): ChapterWorkflowProgress {
            rejectCalls++
            // 拒绝只结束当前 Gate；Canonical 不变，候选（若有）不再作为待审内容展示。
            gate.waitingForUser = false
            return getChapterProgress(chapterId)
        }

        override fun proposeRewrite(chapterId: ChapterId, workingDraftId: WorkingDraftId): ChapterWorkflowProgress =
            error("proposeRewrite 不应被调用")
        override fun abandonRound(chapterId: ChapterId): ChapterWorkflowProgress = error("abandonRound 不应被调用")
        override fun continueToNextChapter(chapterId: ChapterId): ChapterWorkflowProgress =
            error("continueToNextChapter 不应被调用")
    }
}
