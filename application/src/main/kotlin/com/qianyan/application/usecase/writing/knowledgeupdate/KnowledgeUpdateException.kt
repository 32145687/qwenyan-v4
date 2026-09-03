package com.qianyan.application.usecase.writing.knowledgeupdate

/**
 * Knowledge Update 流程类型化异常（P11.5，Application 层）。
 *
 * AI 知识候选输出的结构化解析 / 确定性校验失败由 KnowledgeUpdateParser / KnowledgeValidator 负责；
 * 本异常表示"无法得到可用的候选知识变化"（空 / 非 JSON / 缺字段 / 类型错误 / 非法枚举）。
 * 由 [com.qianyan.application.error.ErrorMapper] 归一为
 * [com.qianyan.application.error.ApplicationError.InvalidKnowledgeUpdateOutput] /
 * [com.qianyan.application.error.ApplicationError.KnowledgeUpdateFailed]。
 * 不放 Provider / AgentRuntime 的底层细节，不经 String message 判断类型。
 */
sealed class KnowledgeUpdateException(message: String) : Exception(message) {

    /** LLM 知识候选输出无法解析为合法 CandidateKnowledgeChange 列表（空 / 非 JSON / 缺字段 / 类型错误 / 非法 operation）。 */
    class InvalidOutput(detail: String) : KnowledgeUpdateException("Knowledge Update 输出无法解析: $detail")

    /** Knowledge Update 流程级失败（Agent / 工具 / 编排 / 校验失败）。 */
    class Failed(detail: String) : KnowledgeUpdateException("Knowledge Update 失败: $detail")
}