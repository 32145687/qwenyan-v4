package com.qianyan.application.usecase.writing.critique

/**
 * Critique 流程类型化异常（P11.4，Application 层）。
 *
 * AI 评审输出的结构化解析与校验由 [CritiqueAgent] / [CritiqueParser] 负责；
 * 本异常表示"无法得到可用的 ValidationResult"（空输出 / 非 JSON / 缺必填字段 / 类型错误），
 * 由 [com.qianyan.application.error.ErrorMapper] 归一为
 * [com.qianyan.application.error.ApplicationError.InvalidCritiqueOutput] /
 * [com.qianyan.application.error.ApplicationError.CritiqueFailed]。
 * 不放 Provider / AgentRuntime 的底层细节，不经 String message 判断类型。
 */
sealed class CritiqueException(message: String) : Exception(message) {

    /** Critique 输出无法解析为合法 ValidationResult（空输出 / 非 JSON / 缺字段 / 类型错误）。 */
    class InvalidOutput(detail: String) : CritiqueException("Critique 输出无法解析: $detail")

    /** Critique 流程级失败（Agent / 工具 / 编排等）。 */
    class Failed(detail: String) : CritiqueException("Critique 失败: $detail")
}