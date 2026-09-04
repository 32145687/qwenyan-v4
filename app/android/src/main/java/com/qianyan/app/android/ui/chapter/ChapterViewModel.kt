package com.qianyan.app.android.ui.chapter

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.application.usecase.chapter.ChapterUseCases
import com.qianyan.model.NovelId
import com.qianyan.model.VariantId
import com.qianyan.model.story.Chapter
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 章节列表 + 创建 ViewModel（P12.1.6）。
 *
 * 架构边界：只依赖 [ChapterUseCases]（Application 层），不直接触碰 ChapterRepository / SQLDelight。
 * 状态：
 *  - [uiState]：Loading → Success / Error；
 *  - [createState]：Idle → Creating → Idle / Error；**一次创建期间 UI 禁用新建**（防重复点击，T9）；
 *  - [created]：创建成功时单次下发真实 [Chapter]，供 UI 导航到 ChapterDetail（MainActivity 消费后调用
 *    [onCreatedConsumed]）。创建以数据库成功结果为准，不提前导航（T6/T14）。
 */
class ChapterViewModel(
    private val chapters: ChapterUseCases,
    private val novelId: NovelId,
    private val variantId: VariantId?,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _uiState = MutableStateFlow<ChapterListUiState>(ChapterListUiState.Loading)
    val uiState: StateFlow<ChapterListUiState> = _uiState.asStateFlow()

    private val _createState = MutableStateFlow<ChapterCreateState>(ChapterCreateState.Idle)
    val createState: StateFlow<ChapterCreateState> = _createState.asStateFlow()

    private val _created = MutableStateFlow<Chapter?>(null)
    val created: StateFlow<Chapter?> = _created.asStateFlow()

    init {
        load()
    }

    /** 加载真实章节（Application → Storage order ASC）。失败进入 Error。 */
    fun load() {
        _uiState.value = ChapterListUiState.Loading
        viewModelScope.launch {
            _uiState.value = try {
                val list = withContext(ioDispatcher) { chapters.listByNovel(novelId, variantId) }
                ChapterListUiState.Success(list)
            } catch (e: ApplicationException) {
                ChapterListUiState.Error(messageFor(e.error))
            } catch (_: Exception) {
                ChapterListUiState.Error("无法加载章节，请重试")
            }
        }
    }

    /**
     * 新建章节：真实经 Application Use Case 写入 SQLite；成功后经 [created] 下发导航。
     * 创建期间忽略重复调用（T9：UI 在一次创建请求期间 disabled，不产生重复状态）。
     */
    fun createChapter(title: String) {
        if (_createState.value is ChapterCreateState.Creating) return
        _createState.value = ChapterCreateState.Creating
        viewModelScope.launch {
            _createState.value = try {
                val created = withContext(ioDispatcher) { chapters.createNextChapter(title, novelId, variantId) }
                load() // 以数据库为准刷新列表
                _created.value = created
                ChapterCreateState.Idle
            } catch (e: ApplicationException) {
                ChapterCreateState.Error(messageFor(e.error))
            } catch (e: Exception) {
                ChapterCreateState.Error("创建失败：${e.message ?: "未知错误"}")
            }
        }
    }

    /** MainActivity 导航到 Detail 后消费 [created] 单次事件。 */
    fun onCreatedConsumed() {
        _created.value = null
    }

    /** ApplicationError → 用户可读文案（不泄露底层技术细节；无关类型兜底）。 */
    private fun messageFor(error: ApplicationError): String = when (error) {
        is ApplicationError.EntityNotFound -> "目标不存在：${error.detail}"
        is ApplicationError.VariantMismatch -> "作用域不匹配：${error.detail}"
        is ApplicationError.InvalidOperation -> "操作被拒绝：${error.detail}"
        is ApplicationError.UnknownStorage -> "存储错误，请重试"
        else -> "操作失败，请重试"
    }

    companion object {
        /** MainActivity 装配用简单工厂（绑定 novelId + variantId 作用域）。 */
        fun factory(
            chapters: ChapterUseCases,
            novelId: NovelId,
            variantId: VariantId?,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ChapterViewModel(chapters, novelId, variantId) as T
        }
    }
}