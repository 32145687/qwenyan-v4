package com.qianyan.app.android.ui.writer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.application.usecase.workflow.ChapterPhase
import com.qianyan.application.usecase.writing.WriterGateway
import com.qianyan.model.ChapterId
import com.qianyan.model.DraftId
import com.qianyan.model.NovelId
import com.qianyan.model.VariantId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Writer 屏任务状态（用户层投影；由 durable Workflow 的 [ChapterPhase] 映射而来，非新业务状态机）。 */
enum class WriterTaskStatus { IDLE, PLANNING, WRITING, CRITIQUE, REVISION, COMPLETED, FAILED }

/**
 * Writer 屏 UI 状态（P20-P3）。
 * 字段贴合规格：chapterTitle/draftContent/draftFormat/taskStatus/error/isSaving/isGenerating
 * （另含 novelTitle 与 draftId：前者供章节信息区展示，后者是保存所必需的真实 Draft 身份）。
 */
data class WriterUiState(
    val novelTitle: String = "",
    val chapterTitle: String = "",
    val draftId: DraftId? = null,
    val draftContent: String = "",
    val draftFormat: String? = null,
    val taskStatus: WriterTaskStatus = WriterTaskStatus.IDLE,
    val error: String? = null,
    val isSaving: Boolean = false,
    val isGenerating: Boolean = false,
) {
    /** 尚无 Draft 不可保存（不伪造草稿；需先经 AI 生成初稿）。 */
    val canSave: Boolean get() = draftId != null && !isSaving && !isGenerating

    val canGenerate: Boolean get() = !isGenerating && !isSaving
}

/**
 * Writer 屏 ViewModel（P20-P3 · Android Writer）。
 *
 * 架构边界（硬约束）：
 *  - 只依赖用户层 [WriterGateway]（Application seam）；**不**接触 Repository / SQL / Provider / Agent；
 *  - **不拥有 DecisionModel**：不触发、不读取、不重算 DecisionPolicy；AI 写作经 Gateway → 既有
 *    Application orchestration（DecisionPolicy 由 Planning/Workflow 注入，WriterAgent 只消费）；
 *  - 只做状态编排与错误映射，不复刻任何写作 / 修订 / Gate 业务规则。
 *
 * 状态流：单一 [uiState]（StateFlow）。加载 → 编辑 → 保存 / AI 继续写 / AI 改写 → 重新加载 Draft 与阶段。
 */
class WriterViewModel(
    private val novelId: NovelId,
    private val variantId: VariantId?,
    private val chapterId: ChapterId,
    private val gateway: WriterGateway,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _uiState = MutableStateFlow(WriterUiState())
    val uiState: StateFlow<WriterUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    /** 加载章节信息 + 章节最新 Draft + durable Workflow 阶段（只读，不推进状态）。 */
    fun load() {
        viewModelScope.launch {
            try {
                val context = withContext(ioDispatcher) { gateway.loadContext(novelId, variantId, chapterId) }
                _uiState.update { it.applyContext(context) }
            } catch (e: ApplicationException) {
                _uiState.update { it.copy(error = messageFor(e.error)) }
            } catch (_: Exception) {
                _uiState.update { it.copy(error = "无法加载章节，请重试") }
            }
        }
    }

    /** 用户编辑正文（纯 UI 状态变更，不落库；保存由 [save] 显式触发）。 */
    fun onContentChange(text: String) {
        _uiState.update { it.copy(draftContent = text, error = null) }
    }

    /** 保存当前编辑内容到既有 Draft（身份 / lineage / format 不变）。尚无 Draft → 明确提示，不伪造。 */
    fun save() {
        val state = _uiState.value
        val draftId = state.draftId
        if (draftId == null) {
            _uiState.update { it.copy(error = "尚无草稿：请先使用「AI 继续写」生成初稿") }
            return
        }
        if (state.isSaving || state.isGenerating) return
        _uiState.update { it.copy(isSaving = true, error = null) }
        viewModelScope.launch {
            try {
                val saved = withContext(ioDispatcher) { gateway.saveContent(draftId, state.draftContent) }
                _uiState.update {
                    it.copy(isSaving = false, draftContent = saved.content, draftFormat = saved.format, draftId = saved.draftId)
                }
            } catch (e: ApplicationException) {
                _uiState.update { it.copy(isSaving = false, error = messageFor(e.error)) }
            } catch (_: Exception) {
                _uiState.update { it.copy(isSaving = false, error = "保存失败，请重试") }
            }
        }
    }

    /** AI 继续写：驱动既有章节 Workflow（PLANNING → WRITING → …），完成后重新加载 Draft。 */
    fun continueWriting() = generate(WriterTaskStatus.PLANNING) { gateway.continueWriting(novelId, variantId, chapterId) }

    /** AI 改写：复用既有 Critique → Revision（新 draftId），完成后重新加载 Draft。 */
    fun rewrite() = generate(WriterTaskStatus.REVISION) { gateway.rewrite(novelId, variantId, chapterId) }

    /**
     * 执行一次 AI 操作：先置 [transient] 状态（UI 本地过程态，非业务状态机），完成后以
     * durable Workflow 阶段投影 + 最新 Draft 覆盖。
     */
    private fun generate(transient: WriterTaskStatus, block: () -> Unit) {
        if (!_uiState.value.canGenerate) return
        _uiState.update { it.copy(isGenerating = true, error = null, taskStatus = transient) }
        viewModelScope.launch {
            try {
                val context = withContext(ioDispatcher) {
                    block()
                    gateway.loadContext(novelId, variantId, chapterId)
                }
                _uiState.update { it.copy(isGenerating = false).applyContext(context) }
            } catch (e: ApplicationException) {
                _uiState.update { it.copy(isGenerating = false, error = messageFor(e.error)) }
            } catch (_: Exception) {
                _uiState.update { it.copy(isGenerating = false, error = "AI 写作失败，请重试") }
            }
        }
    }

    /** 把用户层上下文写入 UI 状态（章节信息 / Draft / 阶段投影）。 */
    private fun WriterUiState.applyContext(context: com.qianyan.application.usecase.writing.WriterChapterContext): WriterUiState =
        copy(
            novelTitle = context.novelTitle,
            chapterTitle = context.chapterTitle,
            draftId = context.draft?.draftId,
            draftContent = context.draft?.content ?: "",
            draftFormat = context.draft?.format,
            taskStatus = context.phase.toWriterTaskStatus(),
            error = null,
        )

    /** ApplicationError → 用户可读文案（不泄露底层细节）。 */
    private fun messageFor(error: ApplicationError): String = when (error) {
        is ApplicationError.EntityNotFound -> "目标不存在：${error.detail}"
        is ApplicationError.VariantMismatch -> "作用域不匹配：${error.detail}"
        is ApplicationError.ProviderCredentialMissing -> "AI 服务未配置密钥"
        is ApplicationError.ProviderUnavailable -> "AI 服务暂不可用"
        is ApplicationError.InvalidPlanningOutput -> "规划输出无效，请重试"
        is ApplicationError.PlanningFailed -> "规划失败，请重试"
        is ApplicationError.InvalidWritingOutput -> "写作输出无效，请重试"
        is ApplicationError.WritingFailed -> "写作失败，请重试"
        is ApplicationError.InvalidCritiqueOutput -> "评审输出无效，请重试"
        is ApplicationError.CritiqueFailed -> "评审失败，请重试"
        is ApplicationError.RevisionNotAllowed -> "修订次数已达上限"
        is ApplicationError.InvalidRevisionOutput -> "修订输出无效，请重试"
        is ApplicationError.RevisionFailed -> "修订失败，请重试"
        is ApplicationError.RevisionLimitExceeded -> "任务检查点已达上限"
        is ApplicationError.TaskNotFound -> "任务不存在：${error.detail}"
        is ApplicationError.InvalidOperation -> "操作被拒绝：${error.detail}"
        is ApplicationError.UnknownStorage -> "存储错误，请重试"
        else -> "操作失败，请重试"
    }

    companion object {
        /** MainActivity 装配用简单工厂（绑定章节作用域 + 用户层 Writer seam）。 */
        fun factory(
            novelId: NovelId,
            variantId: VariantId?,
            chapterId: ChapterId,
            gateway: WriterGateway,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                WriterViewModel(novelId, variantId, chapterId, gateway) as T
        }
    }
}

/** durable Workflow 阶段 → Writer 屏状态（纯投影；WAITING_CONFIRMATION / UPDATING_STORY 视为写作已完成）。 */
private fun ChapterPhase.toWriterTaskStatus(): WriterTaskStatus = when (this) {
    ChapterPhase.NOT_STARTED -> WriterTaskStatus.IDLE
    ChapterPhase.PLANNING -> WriterTaskStatus.PLANNING
    ChapterPhase.WRITING -> WriterTaskStatus.WRITING
    ChapterPhase.REVIEWING -> WriterTaskStatus.CRITIQUE
    ChapterPhase.REVISING -> WriterTaskStatus.REVISION
    ChapterPhase.WAITING_CONFIRMATION -> WriterTaskStatus.COMPLETED
    ChapterPhase.UPDATING_STORY -> WriterTaskStatus.COMPLETED
    ChapterPhase.COMPLETED -> WriterTaskStatus.COMPLETED
    ChapterPhase.FAILED -> WriterTaskStatus.FAILED
}