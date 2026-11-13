package com.qianyan.app.desktop

import com.qianyan.app.desktop.ui.write.FAILURE_SUMMARY_LIMIT
import com.qianyan.app.desktop.ui.write.WORKFLOW_FAILED_SUMMARY
import com.qianyan.app.desktop.ui.write.WriterController
import com.qianyan.app.desktop.ui.write.WriterFailureAction
import com.qianyan.app.desktop.ui.write.failureSummary
import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.application.usecase.workflow.ChapterPhase
import com.qianyan.application.usecase.workflow.ChapterWorkflowGateway
import com.qianyan.application.usecase.workflow.ChapterWorkflowProgress
import com.qianyan.application.usecase.writing.WriterChapterContext
import com.qianyan.application.usecase.writing.WriterGateway
import com.qianyan.model.ChapterId
import com.qianyan.model.DraftId
import com.qianyan.model.NovelId
import com.qianyan.model.VariantId
import com.qianyan.model.story.Chapter
import com.qianyan.model.workingdraft.WorkingDraftId
import com.qianyan.model.writing.Draft
import com.qianyan.model.workflow.WorkflowKind
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.datetime.Clock

/**
 * T-O82-F · 写作 / 改写失败后正文页可见原因 + 重试。
 *
 * 只验证 **UI 投影**：失败原因写入 [com.qianyan.app.desktop.ui.write.WriterUiState.failure]，
 * 成功（含停在人工门）时清空；摘要单行化且 ≤200 字。工作流语义 / 重试策略不变。
 */
class DesktopWriterFailureBannerTest {

    private val novelId = NovelId("novel-t-o82")
    private val chapterId = ChapterId("chapter-t-o82")

    private fun controller(
        gateway: WriterGateway,
        workflow: ChapterWorkflowGateway,
    ): WriterController = WriterController(
        novelId = novelId,
        chapter = Chapter(
            chapterId = chapterId,
            novelId = novelId,
            title = "第 1 章 · 失败可见",
            order = 1,
            createdAt = Clock.System.now(),
        ),
        gateway = gateway,
        workflow = workflow,
        scope = CoroutineScope(Dispatchers.Unconfined),
        ioDispatcher = Dispatchers.Unconfined,
    )

    /* ---------- 1. FAILED：摘要在正文页状态里 ---------- */

    @Test
    fun `failed workflow surfaces failure summary`() {
        // 真实失败形态之一：改写链路抛出类型化错误（如 Key 失效）→ 摘要含用户可读原因。
        val gateway = StubWriterGateway(StubBehavior.THROW_AUTH)
        val state = controller(gateway, StubWorkflow(ChapterPhase.NOT_STARTED)).let { c ->
            c.load()
            c.rewrite()
            c.uiState.value
        }

        val failure = assertNotNull(state.failure, "失败后正文页必须持有可见摘要")
        assertTrue("API Key 无效或已过期" in failure, "摘要必须是用户可读原因：$failure")
        assertTrue(failure.length <= FAILURE_SUMMARY_LIMIT, "摘要不得整段展示：${failure.length}")
        assertEquals(WriterFailureAction.REWRITE, state.failureAction, "重试必须知道原动作")

        // 另一种失败形态：工作流以 FAILED 收尾但不抛（失败被工作流吸收）→ 兜底摘要，仍不静默。
        val absorbed = StubWriterGateway(StubBehavior.FAILED_PHASE)
        val absorbedState = controller(absorbed, StubWorkflow(ChapterPhase.FAILED)).let { c ->
            c.load()
            c.continueWriting()
            c.uiState.value
        }
        assertEquals(WORKFLOW_FAILED_SUMMARY, absorbedState.failure)
        assertEquals(WriterFailureAction.CONTINUE_WRITING, absorbedState.failureAction)
    }

    /* ---------- 1b. 真实失败摘要优先于兜底（T-O82b-F） ---------- */

    @Test
    fun `workflow failure message takes precedence over fallback`() {
        val gateway = StubWriterGateway(StubBehavior.FAILED_WITH_MESSAGE)
        val state = controller(gateway, StubWorkflow(ChapterPhase.FAILED)).let { c ->
            c.load()
            c.continueWriting()
            c.uiState.value
        }

        assertEquals(ATTEMPT_TEXT, state.failure, "有 attempt 文本时必须原样展示（不落回兜底）")
        assertTrue(state.failure != WORKFLOW_FAILED_SUMMARY)
        assertEquals(WriterFailureAction.CONTINUE_WRITING, state.failureAction)
    }

    /* ---------- 2. 成功 / 等待人工门：清空失败（不残留） ---------- */
    @Test
    fun `completed workflow clears failure`() {
        val gateway = StubWriterGateway(StubBehavior.THROW_AUTH)
        val workflow = StubWorkflow(ChapterPhase.NOT_STARTED)
        val controller = controller(gateway, workflow)
        controller.load()
        controller.rewrite()
        assertNotNull(controller.uiState.value.failure, "前置：先制造一次失败")

        // 恢复后同一动作成功（停在人工门也是成功收尾）→ 失败条必须消失。
        gateway.behavior = StubBehavior.OK
        workflow.phase = ChapterPhase.WAITING_CONFIRMATION
        controller.rewrite()

        val state = controller.uiState.value
        assertNull(state.failure, "成功后不得残留上次失败")
        assertNull(state.failureAction)
        assertNull(state.error)
    }

    /* ---------- 3. 摘要裁剪 ---------- */

    @Test
    fun `failure summary is single line and capped`() {
        val long = "第一行\n第二行\r\n" + "长".repeat(FAILURE_SUMMARY_LIMIT + 50)
        val summary = failureSummary(long)
        assertEquals(FAILURE_SUMMARY_LIMIT, summary.length, "≤200 字硬上限")
        assertTrue('\n' !in summary && '\r' !in summary, "摘要必须单行化")
    }

    /* ---------- 4. 正文页接线（源码守卫） ---------- */

    @Test
    fun `write page renders failure banner with retry only when failure is present`() {
        val page = File("src/jvmMain/kotlin/com/qianyan/app/desktop/ui/pages/WritePage.kt")
        assertTrue(page.isFile, "找不到桌面正文页源码：$page")
        val code = page.readText()

        assertTrue("ui.failure?.let" in code, "失败条必须受 ui.failure 控制（成功时自然不渲染）")
        assertTrue("上次写作未完成" in code, "失败条必须给出明确标题")
        assertTrue("ActionButton(\"重试\", ActionLevel.SECONDARY, enabled, onRetry)" in code, "重试必须是次要按钮")
        assertTrue("controller?.retryFailed()" in code, "重试必须回到 Controller 的同一动作入口")
    }

    /* ---------- fixtures ---------- */

    /** 可被嵌套 stub 访问（nested class 读不到外部实例字段）。 */
    private companion object {
        const val ATTEMPT_TEXT: String = "attempt 1 失败: InvalidRevisionOutput(detail=Revision 输出无法解析)"
    }

    private enum class StubBehavior { OK, THROW_AUTH, FAILED_PHASE, FAILED_WITH_MESSAGE }

    private class StubWriterGateway(var behavior: StubBehavior) : WriterGateway {
        override fun loadContext(novelId: NovelId, variantId: VariantId?, chapterId: ChapterId): WriterChapterContext =
            WriterChapterContext(
                novelId = novelId,
                novelTitle = "T-O82 测试作品",
                chapterId = chapterId,
                chapterTitle = "第 1 章 · 失败可见",
                draft = null,
                phase = phaseOf(),
                waitingForUser = phaseOf() == ChapterPhase.WAITING_CONFIRMATION,
            )

        override fun saveContent(draftId: DraftId, content: String): Draft = error("saveContent 不应被调用")

        override fun saveFirstDraft(chapterId: ChapterId, content: String): Draft = error("saveFirstDraft 不应被调用")

        override fun continueWriting(novelId: NovelId, variantId: VariantId?, chapterId: ChapterId): ChapterWorkflowProgress {
            if (behavior == StubBehavior.THROW_AUTH) throw ApplicationException(ApplicationError.ProviderAuthFailed("401"))
            return progress()
        }

        override fun rewrite(novelId: NovelId, variantId: VariantId?, chapterId: ChapterId): ChapterWorkflowProgress {
            if (behavior == StubBehavior.THROW_AUTH) throw ApplicationException(ApplicationError.ProviderAuthFailed("401"))
            return progress()
        }

        override fun rejectGate(chapterId: ChapterId): ChapterWorkflowProgress = error("rejectGate 不应被调用")

        private fun phaseOf(): ChapterPhase = when (behavior) {
            StubBehavior.OK -> ChapterPhase.WAITING_CONFIRMATION
            StubBehavior.FAILED_PHASE, StubBehavior.FAILED_WITH_MESSAGE -> ChapterPhase.FAILED
            StubBehavior.THROW_AUTH -> ChapterPhase.REVISING
        }

        private fun progress(): ChapterWorkflowProgress = ChapterWorkflowProgress(
            chapterId = ChapterId("chapter-t-o82"),
            novelId = NovelId("novel-t-o82"),
            variantId = null,
            phase = phaseOf(),
            waitingForUser = phaseOf() == ChapterPhase.WAITING_CONFIRMATION,
            revisionCount = 0,
            draftId = null,
            failureMessage = ATTEMPT_TEXT.takeIf { behavior == StubBehavior.FAILED_WITH_MESSAGE },
        )
    }

    private class StubWorkflow(var phase: ChapterPhase) : ChapterWorkflowGateway {
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
            novelId = NovelId("novel-t-o82"),
            variantId = null,
            phase = phase,
            waitingForUser = phase == ChapterPhase.WAITING_CONFIRMATION,
            revisionCount = 0,
            draftId = null,
        )

        override fun approve(chapterId: ChapterId): ChapterWorkflowProgress = error("approve 不应被调用")
        override fun reject(chapterId: ChapterId): ChapterWorkflowProgress = error("reject 不应被调用")
        override fun proposeRewrite(chapterId: ChapterId, workingDraftId: WorkingDraftId): ChapterWorkflowProgress =
            error("proposeRewrite 不应被调用")

        override fun abandonRound(chapterId: ChapterId): ChapterWorkflowProgress = error("abandonRound 不应被调用")
        override fun continueToNextChapter(chapterId: ChapterId): ChapterWorkflowProgress =
            error("continueToNextChapter 不应被调用")
    }
}
