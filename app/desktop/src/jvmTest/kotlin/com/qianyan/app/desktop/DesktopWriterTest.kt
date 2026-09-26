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

    @Test
    fun `ai produced draft is stamped controlled markdown`() {
        val (c, _) = openContainer()
        val (novelId, chapterId) = seedChapter(c)

        val draft = assertNotNull(driveUntilDraft(c, novelId, chapterId))
        assertEquals(DraftFormat.CONTROLLED_MARKDOWN, draft.format, "AI 新产生的 Draft 必须标记受控 Markdown v1")
        assertTrue(draft.content.isNotBlank())
    }

    // ---------- 5. Rewrite ----------

    @Test
    fun `rewrite reuses critique and revision and keeps the previous draft in lineage`() {
        val (c, _) = openContainer()
        val (novelId, chapterId) = seedChapter(c)
        val original = assertNotNull(driveUntilDraft(c, novelId, chapterId))

        val controller = controllerFor(c, novelId, chapterId)
        controller.load()
        assertTrue(controller.uiState.value.canRewrite)

        controller.rewrite()

        val state = controller.uiState.value
        assertNull(state.error, "rewrite 不应失败")
        assertNotNull(state.draftId)
        assertNotEquals(original.draftId, state.draftId, "改写必须产生新 Draft")
        assertEquals(DraftStatus.REVISED.name, state.draftStatus, "改写产物 status=REVISED")
        assertEquals(DraftFormat.CONTROLLED_MARKDOWN, state.draftFormat)
        assertTrue(state.draftContent.isNotBlank())

        val lineage = assertNotNull(c.writerGateway.loadContext(novelId, null, chapterId).draft)
        assertEquals(original.draftId, lineage.previousDraftId, "版本链必须指向被改写的上一版")
        assertEquals(state.draftId, lineage.draftId)
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

        // 正向断言：Worker UI 必须经既有 Writer seam + 既有 Workflow 用户层 seam 接线
        val writeSources = sources.filter { it.parentFile.name == "write" }.joinToString("\n") { it.readText() }
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