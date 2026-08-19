package com.qianyan.app.android.ui.novel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.application.usecase.novel.NovelUseCases
import com.qianyan.application.usecase.txt.TxtUseCases
import com.qianyan.engine.txt.TxtSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 小说列表 ViewModel（P7.4 + P7.5）。
 *
 * 架构边界：只依赖 [NovelUseCases] / [TxtUseCases]（Application 层业务入口），不直接触碰
 * Repository / SQLDelight / Android 平台 API。文件读取（Uri → bytes + 展示名）由 MainActivity
 * 完成，本类只接收平台无关的字节 + 展示名，保证可 JVM 单元测试。
 *
 * 状态流：
 *  - [uiState]：Loading → Success / Empty / Error；[load] 可被"重试"再次调用。
 *  - [importState]：Idle → Loading → Success / Error；导入成功后自动刷新 [uiState]。
 */
class NovelListViewModel(
    private val novels: NovelUseCases,
    private val txts: TxtUseCases,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _uiState = MutableStateFlow<NovelListUiState>(NovelListUiState.Loading)
    val uiState: StateFlow<NovelListUiState> = _uiState.asStateFlow()

    private val _importState = MutableStateFlow<TxtImportUiState>(TxtImportUiState.Idle)
    val importState: StateFlow<TxtImportUiState> = _importState.asStateFlow()

    init {
        load()
    }

    /** 加载 Original 列表；失败时进入 Error 状态而非崩溃，重试可再次触发。 */
    fun load() {
        _uiState.value = NovelListUiState.Loading
        viewModelScope.launch {
            _uiState.value = try {
                val list = withContext(ioDispatcher) { novels.listOriginals() }
                if (list.isEmpty()) NovelListUiState.Empty else NovelListUiState.Success(list)
            } catch (_: Exception) {
                // 加载失败统一降级为 Error 状态；不向 UI 泄露异常类型 / 堆栈。
                NovelListUiState.Error
            }
        }
    }

    /**
     * 导入 TXT（P7.5）：bytes / displayName 来自 MainActivity 的 SAF 文件读取。
     * 解析 + 写库在 [ioDispatcher] 执行；成功后刷新列表；失败映射为用户可读文案。
     * Loading 期间忽略新的导入请求，防止重复触发。
     */
    fun importTxt(bytes: ByteArray, displayName: String) {
        if (_importState.value is TxtImportUiState.Loading) return
        _importState.value = TxtImportUiState.Loading
        viewModelScope.launch {
            _importState.value = try {
                val title = deriveTitle(displayName)
                val output = withContext(ioDispatcher) {
                    txts.importTxtAsOriginal(TxtSource(bytes, displayName), title)
                }
                load()
                TxtImportUiState.Success(title = title, isDuplicate = output.isDuplicate)
            } catch (e: ApplicationException) {
                TxtImportUiState.Error(messageFor(e.error))
            } catch (e: Exception) {
                TxtImportUiState.Error("导入失败：${e.message ?: "未知错误"}")
            }
        }
    }

    /** 平台层（MainActivity）读取文件失败时汇报为导入错误；不进入 Loading，避免与导入流程状态竞争。 */
    fun showImportError(message: String) {
        if (_importState.value is TxtImportUiState.Loading) return
        _importState.value = TxtImportUiState.Error(message)
    }

    /** 由展示名推导书名：去掉尾部 .txt 后缀；空白名回退为占位标题。 */
    private fun deriveTitle(displayName: String): String {
        val base = if (displayName.endsWith(".txt", ignoreCase = true)) displayName.dropLast(4) else displayName
        return base.trim().ifEmpty { "未命名小说" }
    }

    /** ApplicationError → 用户可读文案（不泄露底层技术细节 / 堆栈）。 */
    private fun messageFor(error: ApplicationError): String = when (error) {
        is ApplicationError.TxtImportFailed -> "导入失败：${error.detail}"
        is ApplicationError.UnsupportedEncoding -> "不支持的编码：仅支持 UTF-8 文本"
        is ApplicationError.EmptyDocument -> "文件内容为空，没有可导入的内容"
        is ApplicationError.InvalidText -> "不是合法的文本文件，无法导入"
        is ApplicationError.ParseFailed -> "解析失败：${error.detail}"
        is ApplicationError.ImmutableOriginal -> "无法写入：该内容受保护"
        is ApplicationError.VariantBaseInvalid -> "无法导入：Variant 基座无效"
        is ApplicationError.DuplicateTarget -> "内容重复：${error.detail}"
        is ApplicationError.EntityNotFound -> "内容不存在：${error.detail}"
        is ApplicationError.InvalidOperation -> "操作不被允许：${error.detail}"
        is ApplicationError.VariantMismatch -> "上下文不匹配：${error.detail}"
        is ApplicationError.UnknownStorage -> "存储错误，请重试"
        is ApplicationError.ProviderUnavailable -> "分析服务不可用，请稍后重试"
        is ApplicationError.InvalidAnalysisOutput -> "分析结果无效，请重试"
        is ApplicationError.AnalysisFailed -> "分析失败，请重试"
        is ApplicationError.TaskNotFound -> "任务不存在：${error.detail}"
        is ApplicationError.InvalidTaskStateTransition -> "任务状态不允许该操作：${error.detail}"
        is ApplicationError.RevisionLimitExceeded -> "任务检查点数量已达上限：${error.detail}"
        is ApplicationError.CheckpointNotFound -> "未找到可恢复的检查点：${error.detail}"
        is ApplicationError.TaskAlreadyCompleted -> "任务已完成：${error.detail}"
        is ApplicationError.TaskAlreadyCancelled -> "任务已取消：${error.detail}"
        is ApplicationError.RestoreFailure -> "任务上下文恢复失败：${error.detail}"
    }

    companion object {
        /** 供 [ViewModelProvider]（MainActivity 装配）与 compose viewModel() 使用的简单工厂。 */
        fun factory(novels: NovelUseCases, txts: TxtUseCases): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    NovelListViewModel(novels, txts) as T
            }
    }
}
