package com.qianyan.app.android.ui.analysis

import com.qianyan.model.analysis.VocabularySuggestion

/**
 * Analysis 页 UI 状态（P7.6）。
 *
 * 显式五态：空闲 / 分析中 / 成功 / 成功（带警告） / 错误。
 *  Success / SuccessWithWarnings 携带 AnalysisOutput 中供展示的字段：
 *  词汇建议（vocabularySuggestions）与候选数量（candidateIds.size）。
 *  Error.message 为用户可读文案（不泄露底层技术细节 / 堆栈）。
 */
sealed interface AnalysisUiState {

    /** 尚未触发分析。 */
    data object Idle : AnalysisUiState

    /** 正在调用 AnalysisUseCases（Mock 网关返回前）。 */
    data object Loading : AnalysisUiState

    /** 分析成功：至少提取到一条有效词汇建议。 */
    data class Success(
        val suggestions: List<VocabularySuggestion>,
        val candidateCount: Int,
    ) : AnalysisUiState

    /** 分析成功但无有效建议（警告状态，非失败）。 */
    data class SuccessWithWarnings(
        val suggestions: List<VocabularySuggestion>,
        val warning: String,
        val candidateCount: Int,
    ) : AnalysisUiState

    /** 分析失败。 */
    data class Error(val message: String) : AnalysisUiState
}
