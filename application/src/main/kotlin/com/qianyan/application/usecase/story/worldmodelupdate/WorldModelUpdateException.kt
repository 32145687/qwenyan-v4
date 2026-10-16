package com.qianyan.application.usecase.story.worldmodelupdate

/**
 * World Model Update 流程类型化异常（Phase 3 · v3.1 §12，Application 层）。
 *
 * 镜像既有 `KnowledgeUpdateException` 范式：候选输出的结构化解析 / 确定性校验失败由
 * [WorldModelUpdateParser] / [WorldModelFactValidator] 负责；本异常表示
 * "无法得到可用的候选事实"（空 / 非 JSON / 缺字段 / 类型错误 / 非法枚举）。
 * 不放 Provider / AgentRuntime 底层细节，不经 String message 判断类型。
 */
sealed class WorldModelUpdateException(message: String) : Exception(message) {

    /** LLM 候选事实输出无法解析为合法 ExtractedFact 列表（空 / 非 JSON / 缺字段 / 类型错误 / 非法枚举）。 */
    class InvalidOutput(detail: String) : WorldModelUpdateException("World Model Update 输出无法解析: $detail")

    /** World Model Update 流程级失败（Agent / 编排 / 事务失败）。 */
    class Failed(detail: String) : WorldModelUpdateException("World Model Update 失败: $detail")
}
