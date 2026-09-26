package com.qianyan.app.desktop.ui.write

import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.application.usecase.workflow.ChapterWorkflowGateway
import com.qianyan.application.usecase.writing.WriterChapterContext
import com.qianyan.application.usecase.writing.WriterGateway
import com.qianyan.model.ChapterId
import com.qianyan.model.NovelId
import com.qianyan.model.VariantId
import com.qianyan.model.story.Chapter
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Desktop Writer 状态编排（P20-PC2）。**UI 层**，不含任何业务规则。
 *
 * 接线（严格单向）：
 * ```
 * WriteScreen → WriterController → WriterGateway（loadContext / saveContent / continueWriting / rewrite）
 *                               → ChapterWorkflowGateway（getChapterProgress / approve：HITL 状态与人工门）
 * ```
 * 明确不做：
 *  - **不接触** DraftRepository / SQLDelight / WorkflowOrchestrator / Provider / Agent；
 *  - **不 Decision**：不引用 DecisionModel / DecisionPolicy，不触发、不重算（AI 决策由既有
 *    Application orchestration 在 PLANNING 一次性产生并落 Checkpoint，FD-4）；
 *  - **不自建写作流程**：不组合 PlanningExecutionUseCase / WritingExecutionUseCase；
 *  - 不复刻 Gate / 修订 / 生命周期规则：状态只由 [ChapterWorkflowGateway] 投影得到。
 *
 * @param variantId PC-2 只处理 Original 作用域（Variant 工作台属后续阶段）。
 */
class WriterController(
    private val novelId: NovelId,
    private val chapter: Chapter,
    private val gateway: WriterGateway,
    private val workflow: ChapterWorkflowGateway,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val variantId: VariantId? = null,
) {

    private val chapterId: ChapterId get() = chapter.chapterId

    private val _uiState = MutableStateFlow(
        WriterUiState(
            chapterTitle = chapter.title,
            chapterOrder = chapter.order,
        ),
    )
    val uiState: StateFlow<WriterUiState> = _uiState.asStateFlow()

    /** 只读加载：章节信息 + 最新 Draft + Workflow 阶段 / 人工门（不推进任何状态）。 */
    fun load() {
        scope.launch {
            runCatching { readContext() }
                .onSuccess { loaded -> _uiState.update { it.applyContext(loaded).copy(isLoaded = true, dirty = false) } }
                .onFailure { e -> _uiState.update { it.copy(error = messageFor(e)) } }
        }
    }

    /** 用户编辑正文（仅本地缓冲，不落库；保存由 [save] 显式触发）。 */
    fun onContentChange(text: String) {
        _uiState.update { it.copy(draftContent = text, dirty = true, error = null) }
    }

    /**
     * 保存当前编辑内容到**既有** Draft：draftId / chapterId / status / format / lineage 全部不变
     * （WriterUseCases.saveContent 语义）。尚无 Draft → 明确提示，不伪造。
     */
    fun save() {
        val state = _uiState.value
        val draftId = state.draftId
        if (draftId == null) {
            _uiState.update { it.copy(error = "尚无草稿：请先使用「继续写作」生成初稿") }
            return
        }
        if (state.busy) return
        _uiState.update { it.copy(isSaving = true, error = null) }
        scope.launch {
            runCatching { withContext(ioDispatcher) { gateway.saveContent(draftId, state.draftContent) } }
                .onSuccess { saved ->
                    _uiState.update {
                        it.copy(
                            isSaving = false,
                            draftId = saved.draftId,
                            draftStatus = saved.status.name,
                            draftFormat = saved.format,
                            draftContent = saved.content,
                            dirty = false,
                        )
                    }
                }
                .onFailure { e -> _uiState.update { it.copy(isSaving = false, error = messageFor(e)) } }
        }
    }

    /**
     * AI 继续写：驱动既有章节 Workflow 前进（PLANNING → WRITING → …）。
     * 每次调用推进 Workflow 的**一步**（既有 WriterGateway.continueWriting 语义，UI 不改写）；
     * 完成后以 durable Workflow 阶段投影 + 最新 Draft 覆盖 UI 状态。
     */
    fun continueWriting() = generate(WriterTaskStatus.PLANNING) {
        gateway.continueWriting(novelId, variantId, chapterId)
    }

    /** AI 改写：复用既有 Critique → Revision（新 draftId、status=REVISED，原稿不破坏）。 */
    fun rewrite() = generate(WriterTaskStatus.REVISION) {
        gateway.rewrite(novelId, variantId, chapterId)
    }

    /** 通过人工门（HITL）：调用既有 [ChapterWorkflowGateway.approve]，**不自动批准、不绕过 Gate**。 */
    fun approveGate() {
        if (!_uiState.value.canApprove) return
        _uiState.update { it.copy(isApproving = true, error = null) }
        scope.launch {
            runCatching {
                withContext(ioDispatcher) { workflow.approve(chapterId) }
                readContext()
            }
                .onSuccess { loaded -> _uiState.update { it.applyContext(loaded).copy(isApproving = false) } }
                .onFailure { e -> _uiState.update { it.copy(isApproving = false, error = messageFor(e)) } }
        }
    }

    /** 执行一次 AI 操作：先置过程态（UI 本地提示，非业务状态机），完成后以真实投影覆盖。 */
    private fun generate(transient: WriterTaskStatus, block: () -> Unit) {
        if (!_uiState.value.canGenerate) return
        _uiState.update { it.copy(isGenerating = true, error = null, taskStatus = transient) }
        scope.launch {
            runCatching {
                withContext(ioDispatcher) {
                    block()
                    readContext()
                }
            }
                .onSuccess { loaded -> _uiState.update { it.applyContext(loaded).copy(isGenerating = false, dirty = false) } }
                .onFailure { e -> _uiState.update { it.copy(isGenerating = false, error = messageFor(e)) } }
        }
    }

    /** 用户层上下文（Draft + 阶段）+ Workflow 进度（人工门 / 修订计数）；只读、不推进状态。 */
    private fun readContext(): LoadedContext {
        val context = gateway.loadContext(novelId, variantId, chapterId)
        val progress = workflow.getChapterProgress(chapterId)
        return LoadedContext(context, progress.waitingForUser, progress.revisionCount)
    }

    private fun WriterUiState.applyContext(loaded: LoadedContext): WriterUiState = copy(
        novelTitle = loaded.context.novelTitle,
        chapterTitle = loaded.context.chapterTitle.ifBlank { "第 ${chapter.order} 章" },
        chapterOrder = chapter.order,
        draftId = loaded.context.draft?.draftId,
        draftStatus = loaded.context.draft?.status?.name,
        draftFormat = loaded.context.draft?.format,
        draftContent = loaded.context.draft?.content ?: "",
        taskStatus = WriterTaskStatus.of(loaded.context.phase),
        waitingForUser = loaded.waitingForUser,
        revisionCount = loaded.revisionCount,
        error = null,
    )

    /** 一次只读加载的合并结果（用户层上下文 + Workflow 进度投影）。 */
    private data class LoadedContext(
        val context: WriterChapterContext,
        val waitingForUser: Boolean,
        val revisionCount: Int,
    )
}

/** [ApplicationError] → 用户可读文案（不泄露底层细节）。 */
internal fun messageFor(error: Throwable): String = when (error) {
    is ApplicationException -> when (val e = error.error) {
        is ApplicationError.EntityNotFound -> "目标不存在：${e.detail}"
        is ApplicationError.VariantMismatch -> "作用域不匹配：${e.detail}"
        is ApplicationError.ProviderCredentialMissing -> "AI 服务未配置密钥（请在设置中填写 API Key）"
        is ApplicationError.ProviderUnavailable -> "AI 服务暂不可用（Provider unavailable）"
        is ApplicationError.InvalidPlanningOutput -> "规划输出无效，请重试"
        is ApplicationError.PlanningFailed -> "规划失败，请重试"
        is ApplicationError.InvalidWritingOutput -> "写作输出无效，请重试"
        is ApplicationError.WritingFailed -> "写作失败，请重试"
        is ApplicationError.InvalidCritiqueOutput -> "评审输出无效，请重试"
        is ApplicationError.CritiqueFailed -> "评审失败，请重试"
        is ApplicationError.InvalidRevisionOutput -> "修订输出无效，请重试"
        is ApplicationError.RevisionFailed -> "修订失败，请重试"
        is ApplicationError.RevisionNotAllowed -> "修订次数已达上限"
        is ApplicationError.RevisionLimitExceeded -> "任务检查点已达上限"
        is ApplicationError.TaskNotFound -> "任务不存在：${e.detail}"
        is ApplicationError.InvalidOperation -> "操作被拒绝：${e.detail}"
        is ApplicationError.UnknownStorage -> "存储错误，请重试"
        else -> "操作失败，请重试"
    }
    else -> "操作失败：${error.message ?: error.javaClass.simpleName}"
}