package com.qianyan.application.usecase.writing

/**
 * Writing 流程类型化异常（P11.3，Application 层）。
 *
 * LLM 写作输出的结构化解析与校验由 WriterAgent / DraftParser 负责；
 * 本异常表示"无法得到可用的 Draft"（空输出 / 非 JSON / 缺 content / 类型错误），
 * 由 [com.qianyan.application.error.ErrorMapper] 归一为
 * [com.qianyan.application.error.ApplicationError.InvalidWritingOutput] /
 * [com.qianyan.application.error.ApplicationError.WritingFailed]。
 * 不放 Provider / AgentRuntime 的底层细节，不经 String message 判断类型。
 */
sealed class WritingException(message: String) : Exception(message) {

    /** Writer 输出无法解析为合法 Draft（空输出 / 非 JSON / 缺 content / 类型错误）。 */
    class InvalidOutput(detail: String) : WritingException("Writer 输出无法解析: $detail")

    /** Writing 流程级失败（Agent / 工具 / 编排等）。 */
    class Failed(detail: String) : WritingException("Writing 失败: $detail")
}