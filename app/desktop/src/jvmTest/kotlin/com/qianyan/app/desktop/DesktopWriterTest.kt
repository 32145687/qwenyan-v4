package com.qianyan.app.desktop

import com.qianyan.app.desktop.adapter.DesktopProviderAssembler
import com.qianyan.app.desktop.ui.write.WriterController
import com.qianyan.app.desktop.ui.write.WriterTaskStatus
import com.qianyan.application.di.ApplicationContainer
import com.qianyan.application.usecase.workflow.ChapterPhase
import com.qianyan.model.ChapterId
import com.qianyan.model.DraftId
import com.qianyan.model.NovelId
import com.qianyan.model.VariantScope
import com.qianyan.model.writing.Draft
import com.qianyan.model.workingdraft.WorkingDraftStatus
import com.qianyan.model.writing.DraftFormat
import com.qianyan.model.writing.DraftStatus
import com.qianyan.provider.InMemoryProviderCredentialStore
import com.qianyan.provider.ProviderConfiguration
import com.qianyan.provider.ProviderType
import com.qianyan.provider.impl.DefaultProviderAssembler
import com.qianyan.storage.db.QianyanDbFactory
import com.qianyan.storage.db.QianyanDbHandle
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.datetime.Clock
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P20-PC2 · Desktop Writer 测试（JVM，无 GUI）。
 *
 * 覆盖（全部经**真实** SQLite + 真实 Application 层 + 真实 Writer seam，无替身 UI、无演示数据）：
 *  1. Writer Load：打开章节 → [com.qianyan.application.usecase.writing.WriterGateway.loadContext] → 读取 Draft；
 *  2. Save：编辑 → [WriterController.save] → 重新加载 → 正文一致，且 Draft 身份 / status / format 不变；
 *  3. Continue Writing：`continueWriting` 驱动既有 durable Workflow（不自行组合 Planning/Writing）；
 *  4. AI Draft：AI 新产生的 Draft = `markdown:controlled:v1`（P2/FD-1）；
 *  5. Rewrite：复用既有 Critique → Revision，新 draftId + 版本链（previousDraftId）；
 *  6. Legacy Draft：`format=null` 经普通保存**不被**静默迁移为受控 Markdown；
 *  7. 架构守卫：Desktop UI 层不出现 Repository / WorkflowOrchestrator / Decision / SQLDelight 直连
 *     （即「Desktop UI 不重新决定 DecisionPolicy、不绕过 Writer seam」的可执行断言）。
 */
class DesktopWriterTest {

    private val opened = mutableListOf<app.cash.sqldelight.db.SqlDriver>()

    @org.junit.jupiter.api.AfterEach
    fun closeDrivers() {
        opened.forEach { runCatching { it.close() } }
        opened.clear()
    }

    // ---------- fixtures ----------

    private fun openContainer(): Pair<ApplicationContainer, QianyanDbHandle> {
        val dir = Files.createTempDirectory("qianyan-desktop-writer")
        val handle = QianyanDbFactory.open("jdbc:sqlite:${dir.resolve("qianyan.db").toAbsolutePath()}")
        opened += handle.driver
        val container = ApplicationContainer.fromDriver(
            handle.driver,
            DesktopProviderAssembler(DefaultProviderAssembler(InMemoryProviderCredentialStore())),
            ProviderConfiguration(ProviderType.MOCK),
        )
        return container to handle
    }

    /** 真实作品 + 真实章节（测试 fixture，不进入产品代码）。 */
    private fun seedChapter(c: ApplicationContainer): Pair<NovelId, ChapterId> {
        val novelId = c.novels.createOriginal(title = "PC2 测试作品", genre = listOf("东方幻想"), synopsis = "Desktop Writer 测试")
        val chapter = c.chapters.createNextChapter("第 1 章 · 试写", novelId)
        return novelId to chapter.chapterId
    }

    private fun controllerFor(c: ApplicationContainer, novelId: NovelId, chapterId: ChapterId): WriterController {
        // 测试用同步调度：Unconfined scope + Unconfined io，调用返回即状态已更新（确定性断言）。
        val chapter = assertNotNull(c.chapters.findById(chapterId))
        return WriterController(
            novelId = novelId,
            chapter = chapter,
            gateway = c.writerGateway,
            workflow = c.workflowFacade,
            scope = CoroutineScope(Dispatchers.Unconfined),
            ioDispatcher = Dispatchers.Unconfined,
        )
    }

    /**
     * 驱动既有 Writer seam，直到章节产生 Draft（人工门出现时**人工通过**，不自动批准）。
     * 只使用 `WriterGateway.continueWriting` / `ChapterWorkflowGateway.approve` 两个既有入口。
     */
    private fun driveUntilDraft(c: ApplicationContainer, novelId: NovelId, chapterId: ChapterId): Draft? {
        var guard = 0
        while (guard++ < 12) {
            c.writerGateway.loadContext(novelId, null, chapterId).draft?.let { return it }
            if (c.workflowFacade.getChapterProgress(chapterId).waitingForUser) c.workflowFacade.approve(chapterId)
            else c.writerGateway.continueWriting(novelId, null, chapterId)
        }
        return c.writerGateway.loadContext(novelId, null, chapterId).draft
    }

    // ---------- 1. Writer Load ----------

    @Test
    fun `writer loads real chapter context and draft through the writer gateway`() {
        val (c, _) = openContainer()
        val (novelId, chapterId) = seedChapter(c)
        val generated = assertNotNull(driveUntilDraft(c, novelId, chapterId), "AI 流程应产生真实 Draft")

        val controller = controllerFor(c, novelId, chapterId)
        controller.load()
        val state = controller.uiState.value

        assertTrue(state.isLoaded)
        assertEquals("PC2 测试作品", state.novelTitle)
        assertEquals("第 1 章 · 试写", state.chapterTitle)
        assertEquals(1, state.chapterOrder)
        assertEquals(generated.draftId, state.draftId)
        assertEquals(generated.content, state.draftContent)
        assertTrue(state.draftContent.isNotBlank())
        assertFalse(state.dirty)
        assertNull(state.error)
    }

    // ---------- 2. Save ----------

    @Test
    fun `save persists edited content and keeps draft identity and format`() {
        val (c, _) = openContainer()
        val (novelId, chapterId) = seedChapter(c)
        val generated = assertNotNull(driveUntilDraft(c, novelId, chapterId))

        val controller = controllerFor(c, novelId, chapterId)
        controller.load()
        controller.onContentChange("雪停了。他改了开头这一行。")
        assertTrue(controller.uiState.value.dirty)

        controller.save()
        val afterSave = controller.uiState.value
        assertNull(afterSave.error)
        assertFalse(afterSave.dirty)
        assertFalse(afterSave.isSaving)

        // 重新加载（新的 Controller）→ 正文一致
        val reread = controllerFor(c, novelId, chapterId)
        reread.load()
        assertEquals("雪停了。他改了开头这一行。", reread.uiState.value.draftContent)
        assertEquals(generated.draftId, reread.uiState.value.draftId, "保存必须复用同一 Draft 身份")
        assertEquals(generated.status.name, reread.uiState.value.draftStatus, "保存不得改变 Draft.status")
        assertEquals(generated.format, reread.uiState.value.draftFormat, "保存不得改变 Draft.format")

        // P1-01f：真实 Desktop 保存入口（WriterController.save）即产生 Canonical，Reader 侧可读到同一正文
        assertEquals("雪停了。他改了开头这一行。", c.canonicalRead.committedContent(chapterId), "保存即 Canonical")
        // P1-02c+d:驱动到 Draft 的路径含「人工门批准 → 正式 Commit」（1 条），本次作者保存再追加 1 条。
        assertEquals(2, c.commits.history(c.projects.projectOf(novelId).projectId).size, "AI 提交 + 作者保存各一条")
    }

    // ---------- 3. Continue Writing ----------

    @Test
    fun `continue writing advances the existing workflow through the gateway`() {
        val (c, _) = openContainer()
        val (novelId, chapterId) = seedChapter(c)

        val controller = controllerFor(c, novelId, chapterId)
        controller.load()
        assertEquals(WriterTaskStatus.IDLE, controller.uiState.value.taskStatus)
        assertFalse(controller.uiState.value.hasDraft)
        assertTrue(controller.uiState.value.canGenerate)

        controller.continueWriting()

        val state = controller.uiState.value
        assertFalse(state.isGenerating)
        assertNull(state.error, "continueWriting 不应失败")
        assertNotEquals(WriterTaskStatus.IDLE, state.taskStatus, "继续写作必须推进既有 Workflow")
        assertNotEquals(ChapterPhase.NOT_STARTED, c.writerGateway.loadContext(novelId, null, chapterId).phase)
    }

    // ---------- 4. AI Draft format ----------

    @Test
    fun `approve gate uses the existing human gate and never auto approves`() {
        val (c, _) = openContainer()
        val (novelId, chapterId) = seedChapter(c)

        // 驱动到人工门（只在**未**等待确认时推进，绝不自动 approve）
        var guard = 0
        while (guard++ < 12 && !c.workflowFacade.getChapterProgress(chapterId).waitingForUser) {
            c.writerGateway.continueWriting(novelId, null, chapterId)
        }
        assertTrue(c.workflowFacade.getChapterProgress(chapterId).waitingForUser, "流程应停在人工门")

        val controller = controllerFor(c, novelId, chapterId)
        controller.load()
        assertTrue(controller.uiState.value.waitingForUser, "UI 必须显示「等待人工确认」")
        assertTrue(controller.uiState.value.canApprove, "停在人工门时才提供人工通过入口")
        assertNull(controller.uiState.value.error)

        controller.approveGate()

        val after = controller.uiState.value
        assertNull(after.error, "人工通过不应失败")
        assertFalse(after.isApproving)
        assertFalse(after.waitingForUser, "人工通过后不应再停在人工门")
    }

    /**
     * P0-3 · 「通过确认」必须真的决议 Human Gate，并让 Workflow 继续向前，且不重复命中同一个闸门。
     *
     * 修复前：按钮走 `continueWriting → advance → runForward`，命中同一 PENDING gate 后再次
     * WAITING_HUMAN —— 点击无副作用（死循环）。修复后：按钮走 `approveGate`。
     */
    @Test
    fun `confirming the human gate resolves it and lets the workflow proceed without recreating the gate`() {
        val (c, _) = openContainer()
        val (novelId, chapterId) = seedChapter(c)

        var guard = 0
        while (guard++ < 12 && !c.workflowFacade.getChapterProgress(chapterId).waitingForUser) {
            c.writerGateway.continueWriting(novelId, null, chapterId)
        }
        val before = c.workflowFacade.getChapterProgress(chapterId)
        assertTrue(before.waitingForUser, "前置：流程必须停在人工门")
        assertEquals(ChapterPhase.WAITING_CONFIRMATION, before.phase)
        // P1-02c+d：人工门绑定的待审对象是 AI 工作产物（WorkingDraft），章节 Draft 表此刻为空；
        // Gate 不把 WorkingDraftId 冒充 DraftId。
        assertNull(before.draftId, "AI 待审期间章节无 Draft")
        assertEquals(0, c.draftRepository.listByChapter(chapterId).size, "AI 产出不得直写章节 Draft 表")
        val pendingGate = assertNotNull(
            c.workflowRepository.getWorkflowByActiveChapter(chapterId)?.pendingGateId?.let { c.workflowRepository.getGate(it) },
        )
        assertNull(pendingGate.draftId, "工作产物 Gate 不绑 Draft")
        val working = c.workingDrafts.list(c.projects.projectOf(novelId).projectId)
            .single { it.target.chapterId == chapterId && !it.status.isTerminal }

        val controller = controllerFor(c, novelId, chapterId)
        controller.load()
        assertTrue(controller.uiState.value.canApprove)

        // ① 决议：走既有 approveGate（不是 advance）
        controller.approveGate()
        val afterApprove = controller.uiState.value
        assertNull(afterApprove.error)
        assertFalse(afterApprove.waitingForUser, "批准后不得再停在人工门")
        // 批准 = 经 I9/I10 正式提交：产生 Canonical 载体 Draft 并标记 CONFIRMED
        val carrier = assertNotNull(c.draftRepository.latestByChapter(chapterId), "批准后产生 Canonical 载体")
        assertEquals(DraftStatus.CONFIRMED, carrier.status, "批准后 Canonical 载体为 CONFIRMED")
        assertEquals(WorkingDraftStatus.DISCARDED, c.workingDrafts.get(working.workingDraftId).status, "工作产物提交后收尾")

        // P1-02：批准 = 人工明确批准该 AI 正文 ⇒ 经 I10 唯一入口形成 Canonical Commit（Desktop 真实 seam）
        val committed = assertNotNull(c.canonicalRead.committedContent(chapterId), "批准后该章节必须有 Canonical 正文")
        assertEquals(carrier.content, committed, "Canonical 正文 = 已批准（提交）的正文")
        assertEquals(1, c.commits.history(c.projects.projectOf(novelId).projectId).size, "只追加一条 CommitHistory")

        // ② 不重复创建同一个闸门：重复批准是**幂等**的（既有 Gate 已 RESOLVED，不产生新闸门、不回到等待）
        val second = c.workflowFacade.approve(chapterId)
        assertFalse(second.waitingForUser, "重复批准必须幂等：不得重新进入等待人工确认，也不得新建闸门")

        // ③ 继续推进：不得再次命中同一个 gate，也不得再产生新的待确认闸门
        // T-O48：决议成功后有 500ms 生成防抖窗口；等待窗口结束再推进（防抖不是永久禁用）。
        Thread.sleep(600)
        controller.continueWriting()
        val afterAdvance = c.workflowFacade.getChapterProgress(chapterId)
        assertFalse(afterAdvance.waitingForUser, "批准后再推进不得重新停在同一人工门")
        assertNotEquals(ChapterPhase.WAITING_CONFIRMATION, afterAdvance.phase)
        assertFalse(controller.uiState.value.isApproving)
    }

    /**
     * P0-3 · 接线守卫：桌面「通过确认」必须绑定 `approveGate`，**不得**绑定 `continueWriting`。
     *
     * 沿用仓库既有的"读源码断言 token"守卫范式（见 §3.1 那批 boundary 测试）：
     * Compose 按钮的回调绑定无法用无 GUI 的 JVM 测试驱动，因此把"接错线"这一缺陷本身钉死。
     */
    @Test
    fun `confirm button is wired to approveGate and not to advance`() {
        val page = File("src/jvmMain/kotlin/com/qianyan/app/desktop/ui/pages/WritePage.kt")
        assertTrue(page.isFile, "找不到桌面正文页源码：$page")
        val code = page.readText()

        // 该文案在 Composable 的 kdoc 里也出现，故必须定位到真正声明按钮的那一行
        val confirmLine = code.lines().first { "通过确认" in it && "ActionButton" in it }
        assertTrue("onApprove" in confirmLine, "「通过确认」必须绑定 onApprove，实际：$confirmLine")
        assertFalse("onContinue" in confirmLine, "「通过确认」不得绑定 onContinue（那只会 advance，命中同一 PENDING gate 而无副作用）")

        assertTrue(
            "onApprove = { controller?.approveGate() }" in code,
            "onApprove 必须接到既有 WriterController.approveGate()（不新建接口、不直接改 status）",
        )
        assertTrue("onApprove: () -> Unit" in code, "ChapterBar 必须显式接收 onApprove 回调")
        assertTrue(
            "ActionButton(\"继续写\", ActionLevel.PRIMARY, ui.canGenerate, onContinue)" in code,
            "「继续写」仍应绑定 onContinue（两个动作不得合并成同一个回调）",
        )
        // P0-2.1：拒绝必须走既有 Workflow reject，且与批准是两个独立回调。
        assertTrue(
            "onReject = { controller?.rejectGate() }" in code,
            "onReject 必须接到 WriterController.rejectGate()",
        )
        assertTrue(
            "ActionButton(\"拒绝\", ActionLevel.TEXT, ui.canReject, onReject)" in code,
            "「拒绝」必须绑定 onReject，且待审态可用性由 canReject 决定",
        )
        // 待审态不得提供普通保存入口（未批准候选不能经保存绕过 Gate）。
        val waitingBranch = code.substringAfter("if (ui.waitingForUser) {").substringBefore("} else {")
        assertFalse("onSave" in waitingBranch, "待审分支不得包含普通保存入口")
    }

    @Test
    fun `ai produced draft is stamped controlled markdown`() {
        val (c, _) = openContainer()
        val (novelId, chapterId) = seedChapter(c)

        val draft = assertNotNull(driveUntilDraft(c, novelId, chapterId))
        assertEquals(DraftFormat.CONTROLLED_MARKDOWN, draft.format, "AI 新产生的 Draft 必须标记受控 Markdown v1")
        assertTrue(draft.content.isNotBlank())
    }

    // ---------- 5. Rewrite ----------

    /**
     * P0-2.1：改写必须先进入 WorkingDraft + Human Gate；批准前**不写 Draft / 不改 Canonical**。
     * 批准后由既有 Change/Commit 链产生新的 Canonical 载体，版本链指向被改写的上一版。
     */
    @Test
    fun `rewrite enters the human gate first and approval commits the candidate`() {
        val (c, _) = openContainer()
        val (novelId, chapterId) = seedChapter(c)
        c.projects.openProject(novelId)
        // fixture：已有正文（与离线 Revision 输出不同，保证候选确有变化）
        val now = Clock.System.now()
        val original = Draft(
            draftId = DraftId("rewrite-base-p021"),
            novelId = novelId,
            variantId = null,
            scope = VariantScope.ORIGINAL,
            chapterId = chapterId,
            content = "改写前的旧正文",
            format = null,
            status = DraftStatus.DRAFTING,
            createdAt = now,
            updatedAt = now,
        )
        c.draftRepository.save(original)
        val projectId = c.projects.projectOf(novelId).projectId

        val controller = controllerFor(c, novelId, chapterId)
        controller.load()
        assertTrue(controller.uiState.value.canRewrite)

        controller.rewrite()

        val pending = controller.uiState.value
        assertNull(pending.error, "rewrite 不应失败")
        assertEquals(original.draftId, pending.draftId, "批准前编辑区仍是已提交正文")
        assertEquals("改写前的旧正文", pending.draftContent)
        assertTrue(pending.waitingForUser, "改写必须停在人工门")
        assertNotNull(pending.pendingProposal, "必须提供原文 / 候选 / Diff")
        assertEquals("改写前的旧正文", pending.pendingProposal!!.baseContent)
        assertTrue(pending.pendingProposal!!.diff.hasChanges, "候选必须与原文有差异")
        assertFalse(pending.canSave, "待审期间不得普通保存")
        assertEquals(1, c.draftRepository.listByChapter(chapterId).size, "批准前不得新增 Draft")
        assertNull(c.canonicalRead.committedContent(chapterId), "批准前 Canonical 不变")

        val candidate = pending.pendingProposal!!.candidateContent
        controller.approveGate()

        val after = controller.uiState.value
        assertNull(after.error, "批准候选不应失败")
        assertFalse(after.waitingForUser)
        assertNotEquals(original.draftId, after.draftId, "批准后产生新的 Canonical 载体")
        assertEquals(candidate, c.canonicalRead.committedContent(chapterId), "批准后 Canonical = 候选正文")
        val lineage = assertNotNull(c.draftRepository.latestByChapter(chapterId))
        assertEquals(original.draftId, lineage.previousDraftId, "版本链必须指向被改写的上一版")
        assertEquals(1, c.commits.history(projectId).size, "批准只产生一条 CommitHistory")
    }

    /** 改写未产生实际变化 ⇒ 明确失败，不建立无法提交的 Gate。 */
    @Test
    fun `rewrite without an actual change does not open a human gate`() {
        val (c, _) = openContainer()
        val (novelId, chapterId) = seedChapter(c)
        val original = assertNotNull(driveUntilDraft(c, novelId, chapterId))
        val projectId = c.projects.projectOf(novelId).projectId
        val historyBefore = c.commits.history(projectId).size

        val controller = controllerFor(c, novelId, chapterId)
        controller.load()
        controller.rewrite()

        val state = controller.uiState.value
        assertNotNull(state.error, "无实际变化必须明确失败")
        assertFalse(state.waitingForUser, "不得建立无法提交的人工门")
        assertEquals(original.draftId, state.draftId)
        assertEquals(1, c.draftRepository.listByChapter(chapterId).size, "不得新增 Draft")
        assertEquals(historyBefore, c.commits.history(projectId).size, "不得新增提交")
    }

    /** P0-2.1：拒绝只结束当前 Gate；Canonical / Draft 不变。 */
    @Test
    fun `rejecting a rewrite candidate leaves canonical unchanged`() {
        val (c, _) = openContainer()
        val (novelId, chapterId) = seedChapter(c)
        c.projects.openProject(novelId)
        val now = Clock.System.now()
        val original = Draft(
            draftId = DraftId("rewrite-reject-base"),
            novelId = novelId,
            variantId = null,
            scope = VariantScope.ORIGINAL,
            chapterId = chapterId,
            content = "拒绝前的正文",
            format = null,
            status = DraftStatus.DRAFTING,
            createdAt = now,
            updatedAt = now,
        )
        c.draftRepository.save(original)
        val projectId = c.projects.projectOf(novelId).projectId

        val controller = controllerFor(c, novelId, chapterId)
        controller.load()
        controller.rewrite()
        assertTrue(controller.uiState.value.waitingForUser)
        val candidateWorking = c.workingDrafts.list(projectId)
            .single { it.target.chapterId == chapterId && !it.status.isTerminal }

        controller.rejectGate()

        val after = controller.uiState.value
        assertNull(after.error, "拒绝不应失败")
        assertFalse(after.waitingForUser, "拒绝后不再等待人工")
        assertNull(after.pendingProposal, "被拒候选不再作为待审内容展示")
        assertEquals(original.draftId, after.draftId)
        assertEquals("拒绝前的正文", after.draftContent)
        assertNull(c.canonicalRead.committedContent(chapterId), "拒绝后 Canonical 不变")
        assertTrue(c.commits.history(projectId).isEmpty(), "拒绝不产生 CommitHistory")
        assertEquals(1, c.draftRepository.listByChapter(chapterId).size, "拒绝不得写 Draft")
        assertEquals(
            WorkingDraftStatus.VALIDATED,
            c.workingDrafts.get(candidateWorking.workingDraftId).status,
            "拒绝不丢弃工作产物（保留可审计候选）",
        )
    }

    // ---------- 6. Legacy Draft ----------

    @Test
    fun `legacy draft format stays null after a normal save`() {
        val (c, _) = openContainer()
        val (novelId, chapterId) = seedChapter(c)

        // fixture：直接落一条 legacy（format=null）Draft；产品代码路径不产生 legacy
        val now = Clock.System.now()
        val legacyId = DraftId("legacy-draft-pc2")
        c.draftRepository.save(
            Draft(
                draftId = legacyId,
                novelId = novelId,
                variantId = null,
                scope = VariantScope.ORIGINAL,
                chapterId = chapterId,
                previousDraftId = null,
                content = "旧正文（legacy 纯文本）",
                format = null,
                status = DraftStatus.DRAFTING,
                createdAt = now,
                updatedAt = now,
            ),
        )

        val controller = controllerFor(c, novelId, chapterId)
        controller.load()
        assertEquals(legacyId, controller.uiState.value.draftId)
        assertTrue(controller.uiState.value.isLegacyFormat)
        assertEquals(null, controller.uiState.value.draftFormat)

        controller.onContentChange("旧正文（legacy 纯文本）—— 改了一行")
        controller.save()

        val reread = assertNotNull(c.writerGateway.loadContext(novelId, null, chapterId).draft)
        assertEquals(legacyId, reread.draftId)
        assertEquals("旧正文（legacy 纯文本）—— 改了一行", reread.content)
        assertNull(reread.format, "普通保存**不得**把 legacy Draft 静默迁移为受控 Markdown")
        assertEquals(DraftStatus.DRAFTING, reread.status)
    }

    @Test
    fun `continue writing on a finished chapter keeps an existing legacy draft legacy`() {
        val (c, _) = openContainer()
        val (novelId, chapterId) = seedChapter(c)

        // 驱动既有 Workflow 到 COMPLETED（人工门只**人工通过**，不自动批准）
        var guard = 0
        while (guard++ < 20 && c.workflowFacade.getChapterProgress(chapterId).phase != ChapterPhase.COMPLETED) {
            if (c.workflowFacade.getChapterProgress(chapterId).waitingForUser) c.workflowFacade.approve(chapterId)
            else c.writerGateway.continueWriting(novelId, null, chapterId)
        }
        assertEquals(
            ChapterPhase.COMPLETED,
            c.workflowFacade.getChapterProgress(chapterId).phase,
            "应能驱动到 COMPLETED",
        )

        // fixture：构造「P20-P2 之前的库」状态 —— COMPLETED 章节上最新 Draft 仍是 legacy（format=null）
        val now = Clock.System.now()
        val legacyId = DraftId("legacy-completed-pc21")
        c.draftRepository.save(
            Draft(
                draftId = legacyId,
                novelId = novelId,
                variantId = null,
                scope = VariantScope.ORIGINAL,
                chapterId = chapterId,
                previousDraftId = null,
                content = "旧正文（P14 legacy，已定稿）",
                format = null,
                status = DraftStatus.CONFIRMED,
                createdAt = now.plus(1.seconds),
                updatedAt = now.plus(1.seconds),
            ),
        )

        val controller = controllerFor(c, novelId, chapterId)
        controller.load()
        assertEquals(legacyId, controller.uiState.value.draftId)
        assertTrue(controller.uiState.value.isLegacyFormat)

        // COMPLETED 章节再次「继续写作」：本次推进不产生新 Draft
        controller.continueWriting()

        val state = controller.uiState.value
        assertNull(state.error, "COMPLETED 章节继续写作不应失败")
        assertEquals(legacyId, state.draftId, "不得产生新 Draft")
        assertEquals(null, state.draftFormat, "无新 Draft 时不得把既有 legacy Draft 回溯迁移为受控 Markdown")
        assertEquals(
            null,
            c.writerGateway.loadContext(novelId, null, chapterId).draft?.format,
            "Repository 回读：legacy 必须保持 legacy",
        )
    }

    // ---------- 7. 架构守卫 ----------

    @Test
    fun `desktop writer ui has no repository workflow or decision dependencies`() {
        val uiDir = File("src/jvmMain/kotlin/com/qianyan/app/desktop/ui")
        assertTrue(uiDir.isDirectory, "找不到 Desktop UI 源码目录：${uiDir.absolutePath}")

        val sources = uiDir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue(sources.isNotEmpty())

        // UI 层禁止出现的「直连/越权」用法（按实际调用形态匹配，避免误伤注释中的说明文字）
        val forbidden = listOf(
            "container.draftRepository",
            "container.storyState",
            "container.readingProgressRepository",
            "container.novelRepository",
            "container.chapterRepository",
            "container.taskRepository",
            "container.workflowRepository",
            "container.workflowOrchestrator",
            "container.planning",
            "container.writingExecution",
            "container.decisionModelUseCases",
            "container.decisionModelGateway",
            "app.cash.sqldelight",
        )
        val violations = sources.flatMap { file ->
            val code = file.readText().codeLinesOnly()
            forbidden.filter { code.contains(it) }.map { "${file.name}: $it" }
        }
        assertEquals(emptyList(), violations, "Desktop UI 层不得直连 Repository / Orchestrator / Decision / SQLDelight")

        // 正向断言：Writer UI 必须经既有 Writer seam + 既有 Workflow 用户层 seam 接线
        // （I14-UI 收敛后，正文页位于 ui/pages/WritePage.kt；ui/write 保留 WriterController / UiState / RewriteDialog）
        val writeSources = sources
            .filter { it.parentFile.name == "write" || it.name == "WritePage.kt" }
            .joinToString("\n") { it.readText() }
        assertTrue(writeSources.contains("writerGateway"), "Writer UI 必须经 container.writerGateway 接线")
        assertTrue(writeSources.contains("workflowFacade"), "Writer UI 必须经 container.workflowFacade 读取 HITL / 阶段")
    }

    /** 只保留代码行：过滤以行注释符 / 星号 / 块注释起始符开头的行，避免注释中的说明被误判为违规用法。 */
    private fun String.codeLinesOnly(): String = lines()
        .filterNot { line ->
            val t = line.trimStart()
            t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
        }
        .joinToString("\n")
}