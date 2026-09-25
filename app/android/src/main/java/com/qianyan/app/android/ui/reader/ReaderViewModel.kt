package com.qianyan.app.android.ui.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.application.usecase.reading.ReadingUseCases
import com.qianyan.engine.markdown.MarkdownBlock
import com.qianyan.model.ChapterId
import com.qianyan.model.NovelId
import com.qianyan.model.VariantId
import com.qianyan.model.writing.DraftFormat
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Reader 屏 UI 状态（P20-P4）。
 *
 * [blocks] 直接使用 P2 的 [MarkdownBlock]（同一份文档模型，Reader 只消费，不复制第二套）；
 * [isControlledMarkdown] 为 true 时才做行内强调渲染，legacy（format=null）按纯文本展示。
 */
data class ReaderUiState(
    val chapterId: ChapterId? = null,
    val chapterTitle: String = "",
    val chapterOrder: Int = 0,
    val hasDraft: Boolean = false,
    val format: String? = null,
    val blocks: List<MarkdownBlock> = emptyList(),
    /** 阅读位置（块序号）；无进度 → 0。渲染时按块数钳制。 */
    val position: Int = 0,
    val previousChapterId: ChapterId? = null,
    val nextChapterId: ChapterId? = null,
    val error: String? = null,
    val isLoading: Boolean = true,
) {
    val isControlledMarkdown: Boolean get() = format == DraftFormat.CONTROLLED_MARKDOWN
    val canGoPrevious: Boolean get() = previousChapterId != null && !isLoading
    val canGoNext: Boolean get() = nextChapterId != null && !isLoading

    /** 可安全使用的初始滚动位置（块数变化后防御性钳制，不视为错误）。 */
    val safePosition: Int get() = position.coerceIn(0, maxOf(0, blocks.size - 1))
}

/**
 * Reader 屏 ViewModel（P20-P4 · FD-7）。
 *
 * 架构边界（硬约束）：
 *  - 只依赖 Application 层 [ReadingUseCases]；**不**接触 Repository / SQLDelight / Provider / Agent；
 *  - **不新建第二套章节正文模型**：正文与展示块全部来自 ReadingUseCases（Chapter + Draft + P2 解析）；
 *  - 上一章 / 下一章只消费 Application 给出的相邻章节 ID，不在 UI 维护章节顺序；
 *  - 只做阅读位置读写（FD-9），不含阅读统计 / 时长 / 行为分析 / 云同步。
 */
class ReaderViewModel(
    private val novelId: NovelId,
    private val variantId: VariantId?,
    private val chapterId: ChapterId,
    private val reading: ReadingUseCases,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ReaderUiState())
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()

    init {
        load(chapterId)
    }

    /** 加载指定章节（默认进入章节；上一章 / 下一章亦经此）；恢复该章节自己的阅读位置。 */
    fun load(target: ChapterId = chapterId) {
        _uiState.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            try {
                val chapter = withContext(ioDispatcher) { reading.openChapter(novelId, variantId, target) }
                _uiState.update { it.applyChapter(chapter) }
            } catch (e: ApplicationException) {
                _uiState.update { it.copy(isLoading = false, error = messageFor(e.error)) }
            } catch (_: Exception) {
                _uiState.update { it.copy(isLoading = false, error = "无法加载章节，请重试") }
            }
        }
    }

    /** 上一章（无上一章时不动作，由 UI 禁用按钮兜底）。 */
    fun goToPreviousChapter() {
        _uiState.value.previousChapterId?.let { load(it) }
    }

    /** 下一章（无下一章时不动作，由 UI 禁用按钮兜底）。 */
    fun goToNextChapter() {
        _uiState.value.nextChapterId?.let { load(it) }
    }

    /**
     * 阅读位置变更（滚动停止在首块时回调）：保存当前章节的阅读位置。
     * 同值不重复落库；无当前章节 → 不动作。
     */
    fun onPositionChanged(position: Int) {
        val state = _uiState.value
        val current = state.chapterId ?: return
        if (position.coerceAtLeast(0) == state.position) return
        viewModelScope.launch {
            try {
                val saved = withContext(ioDispatcher) { reading.savePosition(novelId, current, position) }
                // 仅当仍停留在同一章节时回写，避免快速切章时把旧章节位置写到新章节状态上
                _uiState.update { if (it.chapterId == saved.chapterId) it.copy(position = saved.position) else it }
            } catch (_: Exception) {
                // 阅读位置保存失败不打断阅读（FD-9：位置只是辅助信息，不是业务结果）
            }
        }
    }

    private fun ReaderUiState.applyChapter(chapter: com.qianyan.application.usecase.reading.ReaderChapter): ReaderUiState =
        copy(
            chapterId = chapter.chapterId,
            chapterTitle = chapter.chapterTitle,
            chapterOrder = chapter.chapterOrder,
            hasDraft = chapter.hasDraft,
            format = chapter.format,
            blocks = chapter.blocks,
            position = chapter.position.coerceAtLeast(0),
            previousChapterId = chapter.previousChapterId,
            nextChapterId = chapter.nextChapterId,
            error = null,
            isLoading = false,
        )

    /** ApplicationError → 用户可读文案（不泄露底层细节）。 */
    private fun messageFor(error: ApplicationError): String = when (error) {
        is ApplicationError.EntityNotFound -> "章节不存在：${error.detail}"
        is ApplicationError.VariantMismatch -> "作用域不匹配：${error.detail}"
        is ApplicationError.InvalidOperation -> "操作被拒绝：${error.detail}"
        is ApplicationError.UnknownStorage -> "存储错误，请重试"
        else -> "操作失败，请重试"
    }

    companion object {
        /** MainActivity 装配用简单工厂（绑定章节作用域 + Application 阅读入口）。 */
        fun factory(
            novelId: NovelId,
            variantId: VariantId?,
            chapterId: ChapterId,
            reading: ReadingUseCases,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ReaderViewModel(novelId, variantId, chapterId, reading) as T
        }
    }
}