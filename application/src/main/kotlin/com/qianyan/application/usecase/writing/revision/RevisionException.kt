package com.qianyan.application.usecase.writing.revision

/**
 * Revision 流程类型化异常（P11.4，Application 层）。
 *
 * AI 修订输出的结构化解析与校验由 [RevisionAgent]（复用 [com.qianyan.application.usecase.writing.DraftParser]）
 * 负责；本异常表示"无法得到可用的修订 Draft"（空输出 / 非 JSON / 缺 content / 类型错误），
 * 由 [com.qianyan.application.error.ErrorMapper] 归一为
 * [com.qianyan.application.error.ApplicationError.InvalidRevisionOutput] /
 * [com.qianyan.application.error.ApplicationError.RevisionFailed]。
 * 不放 Provider / AgentRuntime 的底层细节，不经 String message 判断类型。
 */
sealed class RevisionException(message: String) : Exception(message) {

    /** Revision 输出无法解析为合法修订 Draft（空输出 / 非 JSON / 缺 content / 类型错误）。 */
    class InvalidOutput(detail: String) : RevisionException("Revision 输出无法解析: $detail")

    /** Revision 流程级失败（Agent / 工具 / 编排等）。 */
    class Failed(detail: String) : RevisionException("Revision 失败: $detail")
}