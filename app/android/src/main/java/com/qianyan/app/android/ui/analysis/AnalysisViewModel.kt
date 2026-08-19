package com.qianyan.app.android.ui.analysis

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.application.usecase.analysis.AnalysisUseCases
import com.qianyan.application.usecase.txt.TxtUseCases
import com.qianyan.application.usecase.vocabulary.VocabularyUseCases
import com.qianyan.model.BaseNovelId
import com.qianyan.model.analysis.AnalysisStatus
import com.qianyan.model.core.Novel
import com.qianyan.model.core.VariantContext
import com.qianyan.model.vocabulary.VocabularyCandidate
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Analysis 页 ViewModel（P7.6）。
 *
 * 架构边界：只依赖 [AnalysisUseCases] / [VocabularyUseCases] / [TxtUseCases]（Application 层业务入口），
 * 不直接触碰 Repository / SQLDelight / LLM。小说选择由 MainActivity 传入 [novel]（平台无关领域模型）。
 *
 * 流程（Analysis 状态机）：
 *  - Idle → [startAnalysis] → Loading → Success / SuccessWithWarnings / Error；
 *  - Loading 期间忽略重复触发；
 *  - Analysis 完成后经 [VocabularyUseCases.findCandidatesByNovel] 加载候选（独立状态流 [candidates]）。
 *
 * 数据准备（Application 最小查询入口）：
 *  - documentId ← [TxtUseCases.findDocumentsByNovel]（无绑定 → EntityNotFound 错误）；
 *  - vocabularyId ← [VocabularyUseCases.getOrCreateNovelVocabulary]（复用不重复创建）；
 *  - 显式 [VariantContext]：scope=ORIGINAL、variantId=null（P6 仅支持 Original）。
 */
class AnalysisViewModel(
    private val analysis: AnalysisUseCases,
    private val vocabularies: VocabularyUseCases,
    private val txts: TxtUseCases,
    private val novel: Novel,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _uiState = MutableStateFlow<AnalysisUiState>(AnalysisUiState.Idle)
    val uiState: StateFlow<AnalysisUiState> = _uiState.asStateFlow()

    private val _candidates = MutableStateFlow<List<VocabularyCandidate>>(emptyList())
    val candidates: StateFlow<List<VocabularyCandidate>> = _candidates.asStateFlow()

    /** 是否已执行过候选查询（区分"尚未分析"与"分析后无候选"）。 */
    private val _candidatesLoaded = MutableStateFlow(false)
    val candidatesLoaded: StateFlow<Boolean> = _candidatesLoaded.asStateFlow()

    /**
     * 触发一次 Analysis：解析 + 写候选在 [ioDispatcher] 执行；成功/失败均落在 [uiState]。
     * 失败不崩溃，映射为用户可读文案；可再次调用（重试）。
     */
    fun startAnalysis() {
        if (_uiState.value is AnalysisUiState.Loading) return
        _uiState.value = AnalysisUiState.Loading
        viewModelScope.launch {
            _uiState.value = try {
                val (output, candidateList) = withContext(ioDispatcher) {
                    val document = txts.findDocumentsByNovel(novel.novelId).firstOrNull()
                        ?: throw ApplicationException(
                            ApplicationError.EntityNotFound("该小说没有可分析的 TXT 文本"),
                        )
                    val vocabularyId = vocabularies.getOrCreateNovelVocabulary(novel.novelId)
                    val context = VariantContext(baseNovelId = BaseNovelId(novel.novelId.value))
                    val result = analysis.analyzeTxtOriginal(document.documentId, vocabularyId, context)
                    val candidates = vocabularies.findCandidatesByNovel(novel.novelId)
                    result to candidates
                }
                _candidates.value = candidateList
                _candidatesLoaded.value = true
                output.toUiState()
            } catch (e: ApplicationException) {
                AnalysisUiState.Error(messageFor(e.error))
            } catch (e: Exception) {
                AnalysisUiState.Error("分析失败：${e.message ?: "未知错误"}")
            }
        }
    }

    /** 把 Analysis 结构化输出映射为 UI 状态（状态字段来自真实 AnalysisOutput / AnalysisResult）。 */
    private fun AnalysisUseCases.AnalysisOutput.toUiState(): AnalysisUiState = when (analysisResult.status) {
        AnalysisStatus.SUCCESS -> AnalysisUiState.Success(
            suggestions = analysisResult.vocabularySuggestions,
            candidateCount = candidateIds.size,
        )
        AnalysisStatus.SUCCESS_WITH_WARNINGS -> AnalysisUiState.SuccessWithWarnings(
            suggestions = analysisResult.vocabularySuggestions,
            warning = analysisResult.warning ?: "分析完成，但未提取到有效词汇",
            candidateCount = candidateIds.size,
        )
        AnalysisStatus.FAILED -> AnalysisUiState.Error("分析失败：无法得到可用的分析结果")
    }

    /** ApplicationError → 用户可读文案（不泄露底层技术细节 / 堆栈）。 */
    private fun messageFor(error: ApplicationError): String = when (error) {
        is ApplicationError.ProviderUnavailable -> "分析服务不可用，请稍后重试"
        is ApplicationError.InvalidAnalysisOutput -> "分析结果无效，请重试"
        is ApplicationError.AnalysisFailed -> "分析失败，请重试"
        is ApplicationError.EntityNotFound -> error.detail
        is ApplicationError.InvalidOperation -> "操作不被允许：${error.detail}"
        is ApplicationError.VariantMismatch -> "上下文不匹配：${error.detail}"
        is ApplicationError.TxtImportFailed -> "导入数据异常：${error.detail}"
        is ApplicationError.ImmutableOriginal -> "无法写入：该内容受保护"
        is ApplicationError.VariantBaseInvalid -> "Variant 基座无效"
        is ApplicationError.DuplicateTarget -> "内容重复：${error.detail}"
        is ApplicationError.UnsupportedEncoding -> "不支持的编码：仅支持 UTF-8 文本"
        is ApplicationError.EmptyDocument -> "文件内容为空"
        is ApplicationError.InvalidText -> "不是合法的文本文件"
        is ApplicationError.ParseFailed -> "解析失败：${error.detail}"
        is ApplicationError.TaskNotFound -> "任务不存在：${error.detail}"
        is ApplicationError.InvalidTaskStateTransition -> "任务状态不允许该操作：${error.detail}"
        is ApplicationError.RevisionLimitExceeded -> "任务检查点数量已达上限：${error.detail}"
        is ApplicationError.CheckpointNotFound -> "未找到可恢复的检查点：${error.detail}"
        is ApplicationError.TaskAlreadyCompleted -> "任务已完成：${error.detail}"
        is ApplicationError.TaskAlreadyCancelled -> "任务已取消：${error.detail}"
        is ApplicationError.RestoreFailure -> "任务上下文恢复失败：${error.detail}"
        is ApplicationError.UnknownStorage -> "存储错误，请重试"
    }

    companion object {
        /** 供 [ViewModelProvider]（MainActivity 装配）使用的简单工厂。 */
        fun factory(
            analysis: AnalysisUseCases,
            vocabularies: VocabularyUseCases,
            txts: TxtUseCases,
            novel: Novel,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                AnalysisViewModel(analysis, vocabularies, txts, novel) as T
        }
    }
}
