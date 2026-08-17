package com.qianyan.application.error

/**
 * Analysis 流程异常（P6.6，Application 层）。
 *
 * AI 输出的结构化解析与校验由 Application 负责；本异常表示"无法得到可用结构化结果"，
 * 由 [ErrorMapper] 归一为 [ApplicationError.InvalidAnalysisOutput] / [ApplicationError.AnalysisFailed]。
 * 不放引擎/Provider 的底层细节。
 */
sealed class AnalysisException(message: String) : Exception(message) {

    /** AI 输出无法按结构解析（非 JSON / 缺字段 / 类型错误）。 */
    class InvalidOutput(detail: String) : AnalysisException("AI 输出无法解析: $detail")

    /** Analysis 流程级失败（预检 / 编排 / 其它）。 */
    class Failed(detail: String) : AnalysisException("Analysis 失败: $detail")
}