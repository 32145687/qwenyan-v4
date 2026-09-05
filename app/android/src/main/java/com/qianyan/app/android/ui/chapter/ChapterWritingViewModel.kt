package com.qianyan.app.android.ui.chapter

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.application.usecase.chapter.ChapterChainResult
import com.qianyan.application.usecase.chapter.ChapterWritingUseCases
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
 * 章节创作链 ViewModel（P12.1.7）。
 *
 * **只协调，不重写业务逻辑**：所有步骤委托给 [ChapterWritingUseCases] 打开的 [ChapterWritingSession]
 * （其内部复用既有 Planning / Writing / Critique / Revision / Confirmation / KnowledgeUpdate UseCases 与 Task）。
 * 本类只负责：发起操作、展示 Loading/Error、持有链结果、防重复点击（操作期间忽略新请求）。
 * 状态以 Application 落库结果为准（T9/T12：即使 UI 被绕过，Application 层也强制门禁）。
 */
class ChapterWritingViewModel(
    novelId: NovelId,
    variantId: VariantId?,
    chapterId: ChapterId,
    private val chapterWriting: ChapterWritingUseCases,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val session = chapterWriting.open(chapterId, novelId, variantId)

    private val _chain = MutableStateFlow<ChapterChainResult?>(null)
    val chain: StateFlow<ChapterChainResult?> = _chain.asStateFlow()

    private val _op = MutableStateFlow<ChapterWritingOp>(Idle)
    val op: StateFlow<ChapterWritingOp> = _op.asStateFlow()

    init {
        refresh()
    }

    /** 从数据库恢复链状态（Activity 重建 / 重进，真实持久化）。 */
    private fun refresh() {
        viewModelScope.launch {
            _chain.value = withContext(ioDispatcher) { session.refreshFromDatabase() }
        }
    }

    /** 执行一个链步骤（幂等由 session 保证；操作期间忽略重复点击）。 */
    private fun run(stage: String, block: () -> Unit) {
        if (_op.value is ChapterWritingOp.Running) return
        _op.value = ChapterWritingOp.Running(stage)
        viewModelScope.launch {
            try {
                withContext(ioDispatcher) { block() }
                _chain.value = session.current()
                _op.value = Idle
            } catch (e: ApplicationException) {
                _op.value = ChapterWritingOp.Error(stage, messageFor(e.error))
            } catch (_: Exception) {
                _op.value = ChapterWritingOp.Error(stage, "操作失败，请重试")
            }
        }
    }

    fun plan() = run("Planning") { session.plan() }
    fun write() = run("Writing") { session.write() }
    fun critique() = run("Critique") { session.critique() }
    fun revise() = run("Revision") { session.revise() }
    fun finalize() = run("Finalize") { session.finalize() }
    fun confirm() = run("Confirmation") { session.confirm() }
    fun knowledgeUpdate() = run("Knowledge Update") { session.knowledgeUpdate() }

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
            chapterWriting: ChapterWritingUseCases,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ChapterWritingViewModel(novelId, variantId, chapterId, chapterWriting) as T
        }
    }
}