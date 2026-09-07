package com.qianyan.app.android.ui.chapter

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.application.usecase.workflow.ChapterWorkflowGateway
import com.qianyan.application.usecase.workflow.ChapterWorkflowProgress
import com.qianyan.app.android.ui.chapter.ChapterWritingOp.Idle
import com.qianyan.model.ChapterId
import com.qianyan.model.NovelId
import com.qianyan.model.VariantId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 章节工作流 ViewModel（P12.2 M3 · Facade Migration）。
 *
 * **只协调，不实现任何 Workflow 逻辑**：所有章节推进/审批/恢复/续篇统一委托给用户层
 * [ChapterWorkflowGateway]（其唯一实现 [ChapterWorkflowFacade] → WorkflowOrchestrator）。
 *
 * ViewModel 只理解用户层概念：
 *  - [ChapterWorkflowProgress] / 其 [com.qianyan.application.usecase.workflow.ChapterPhase]
 *  - waitingForUser / revisionCount / draftId
 *  - [ApplicationException]/[ApplicationError] 的用户层错误映射
 *
 * ViewModel **不理解也不接触**：Workflow / WorkflowStep / WorkflowHumanGate / Attempt / ContinuationReference
 * / resultReference / logicalStepKey / attemptNo / WorkflowRepository / WorkflowService / WorkflowOrchestrator / Task。
 * 也不复刻第二步/重试/恢复/审批/续篇逻辑——这些一律由 Facade/Workflow 保证。
 */
class ChapterWritingViewModel(
    private val novelId: NovelId,
    private val variantId: VariantId?,
    private val chapterId: ChapterId,
    private val gateway: ChapterWorkflowGateway,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _progress = MutableStateFlow<ChapterWorkflowProgress?>(null)
    val progress: StateFlow<ChapterWorkflowProgress?> = _progress.asStateFlow()

    /** 续篇结果（下一章进度；供 UI 导航消费，单次）。 */
    private val _nextChapter = MutableStateFlow<ChapterWorkflowProgress?>(null)
    val nextChapter: StateFlow<ChapterWorkflowProgress?> = _nextChapter.asStateFlow()

    private val _op = MutableStateFlow<ChapterWritingOp>(Idle)
    val op: StateFlow<ChapterWritingOp> = _op.asStateFlow()

    init {
        open()
    }

    /** 打开章节：从 durable workflow 恢复进度（不依赖任何内存 Session）。 */
    private fun open() {
        viewModelScope.launch {
            _progress.value = withContext(ioDispatcher) { gateway.getChapterProgress(chapterId) }
        }
    }

    /** 显式启动章节工作流（进度为 NOT_STARTED 时由 UI 提供入口）。 */
    fun start() = run("Start") { gateway.startChapter(novelId, variantId, chapterId) }

    /** 推进章节工作流（Workflow 自行决定下一步；ViewModel 不读取 Phase 决定下一步）。 */
    fun advance() = run("Advance") { gateway.advance(chapterId) }

    /** 恢复章节工作流（durable recovery）。 */
    fun resume() = run("Resume") { gateway.resume(chapterId) }

    /** HITL：批准（幂等由 Facade/Workflow 保证，ViewModel 不保存 approved 状态）。 */
    fun approve() = run("Approve") { gateway.approve(chapterId) }

    /** 续到下一章：返回下一章进度（Facade 保证只创建一个下一章）。 */
    fun continueToNext() {
        if (_op.value is ChapterWritingOp.Running) return
        _op.value = ChapterWritingOp.Running("Next")
        viewModelScope.launch {
            try {
                val next = withContext(ioDispatcher) { gateway.continueToNextChapter(chapterId) }
                _nextChapter.value = next
                _op.value = Idle
            } catch (e: ApplicationException) {
                _op.value = ChapterWritingOp.Error("Next", messageFor(e.error))
            } catch (_: Exception) {
                _op.value = ChapterWritingOp.Error("Next", "操作失败，请重试")
            }
        }
    }

    /** MainActivity 导航到下一章后消费 [nextChapter] 单次事件。 */
    fun onNextConsumed() {
        _nextChapter.value = null
    }

    /** 执行一个当前章节操作（幂等防重复点击；结果投影为 [ChapterWorkflowProgress]）。 */
    private fun run(stage: String, block: () -> ChapterWorkflowProgress) {
        if (_op.value is ChapterWritingOp.Running) return
        _op.value = ChapterWritingOp.Running(stage)
        viewModelScope.launch {
            try {
                _progress.value = withContext(ioDispatcher) { block() }
                _op.value = Idle
            } catch (e: ApplicationException) {
                _op.value = ChapterWritingOp.Error(stage, messageFor(e.error))
            } catch (_: Exception) {
                _op.value = ChapterWritingOp.Error(stage, "操作失败，请重试")
            }
        }
    }

    /** ApplicationError → 用户可读文案（不泄露底层细节；无关类型兜底）。 */
    private fun messageFor(error: ApplicationError): String = when (error) {
        is ApplicationError.EntityNotFound -> "目标不存在：${error.detail}"
        is ApplicationError.VariantMismatch -> "作用域不匹配：${error.detail}"
        is ApplicationError.InvalidContinuationSource -> "续篇来源无效：${error.detail}"
        is ApplicationError.DraftConfirmationRequired -> "需先确认最终稿：${error.detail}"
        is ApplicationError.ProviderCredentialMissing -> "AI 服务未配置密钥"
        is ApplicationError.ProviderUnavailable -> "AI 服务暂不可用"
        is ApplicationError.RevisionNotAllowed -> "修订次数已达上限"
        is ApplicationError.RevisionLimitExceeded -> "任务检查点已达上限"
        is ApplicationError.TaskNotFound -> "任务不存在：${error.detail}"
        is ApplicationError.InvalidOperation -> "操作被拒绝：${error.detail}"
        is ApplicationError.UnknownStorage -> "存储错误，请重试"
        else -> "操作失败：请重试"
    }

    companion object {
        fun factory(
            novelId: NovelId,
            variantId: VariantId?,
            chapterId: ChapterId,
            gateway: ChapterWorkflowGateway,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ChapterWritingViewModel(novelId, variantId, chapterId, gateway) as T
        }
    }
}