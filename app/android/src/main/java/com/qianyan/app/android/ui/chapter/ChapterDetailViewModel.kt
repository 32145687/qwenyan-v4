package com.qianyan.app.android.ui.chapter

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.application.usecase.chapter.ChapterUseCases
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
 * 章节详情 ViewModel（P12.1.6）。按稳定 ID（novelId + variantId + chapterId）加载真实章节。
 * 进入（含 Configuration Change / 重进）都从数据库读取，不依赖内存缓存（T7：退出再回来仍存在）。
 */
class ChapterDetailViewModel(
    private val chapters: ChapterUseCases,
    private val novelId: NovelId,
    private val variantId: VariantId?,
    private val chapterId: ChapterId,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _uiState = MutableStateFlow<ChapterDetailUiState>(ChapterDetailUiState.Loading)
    val uiState: StateFlow<ChapterDetailUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    /** 从 Application Use Case（Storage）读取章节；不存在 → Error。 */
    fun load() {
        _uiState.value = ChapterDetailUiState.Loading
        viewModelScope.launch {
            _uiState.value = try {
                val chapter = withContext(ioDispatcher) { chapters.findById(chapterId) }
                if (chapter != null) ChapterDetailUiState.Content(chapter)
                else ChapterDetailUiState.Error("章节不存在")
            } catch (e: ApplicationException) {
                ChapterDetailUiState.Error(messageFor(e.error))
            } catch (_: Exception) {
                ChapterDetailUiState.Error("无法加载章节，请重试")
            }
        }
    }

    private fun messageFor(error: ApplicationError): String = when (error) {
        is ApplicationError.EntityNotFound -> "目标不存在：${error.detail}"
        is ApplicationError.UnknownStorage -> "存储错误，请重试"
        else -> "加载失败，请重试"
    }

    companion object {
        fun factory(
            chapters: ChapterUseCases,
            novelId: NovelId,
            variantId: VariantId?,
            chapterId: ChapterId,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ChapterDetailViewModel(chapters, novelId, variantId, chapterId) as T
        }
    }
}