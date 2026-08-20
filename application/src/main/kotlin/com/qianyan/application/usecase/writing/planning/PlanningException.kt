package com.qianyan.application.usecase.writing.planning

/**
 * Planning 流程类型化异常（P11.2，Application 层）。
 *
 * AI 规划输出的结构化解析与校验由 Planner / ChapterPlanParser 负责；
 * 本异常表示"无法得到可用的 ChapterPlan"（非 JSON / 缺字段 / 类型错误），
 * 由 [com.qianyan.application.error.ErrorMapper] 归一为
 * [com.qianyan.application.error.ApplicationError.InvalidPlanningOutput] /
 * [com.qianyan.application.error.ApplicationError.PlanningFailed]。
 * 不放 Provider / AgentRuntime 的底层细节，不经 String message 判断类型。
 */
sealed class PlanningException(message: String) : Exception(message) {

    /** Planner 输出无法解析为合法 ChapterPlan（空输出 / 非 JSON / 缺字段 / 类型错误）。 */
    class InvalidOutput(detail: String) : PlanningException("Planner 输出无法解析: $detail")

    /** Planning 流程级失败（Agent/工具/编排等）。 */
    class Failed(detail: String) : PlanningException("Planning 失败: $detail")
}