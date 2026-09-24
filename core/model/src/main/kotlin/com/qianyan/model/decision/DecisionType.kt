package com.qianyan.model.decision

/*
 * P19 · DecisionType —— 系统最终要做的**创作决策类型**（输出层 vocabulary）。
 *
 * 冻结约束（P19 Contract）：
 *  - 独立 enum，**不直接复用 PreferenceDimension / AuthorDnaDimension**（输入/输出分离）。
 *  - V1 最小集合：只有 STORY_DIRECTION / WRITING_STYLE；未来新增 = 扩 enum + 新 rule + 测试，
 *    不改 DecisionPolicy / Planner-Writer 消费契约。
 */
enum class DecisionType {
    /** 故事方向类创作决策（如"渐进揭示 vs 直接呈现"）。 */
    STORY_DIRECTION,

    /** 文风/表达类创作决策（如"短句主导 vs 长句主导"）。 */
    WRITING_STYLE,
}
