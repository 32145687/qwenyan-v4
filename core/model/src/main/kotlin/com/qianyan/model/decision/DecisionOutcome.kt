package com.qianyan.model.decision

/*
 * P19 · DecisionOutcome —— 统一受控决策结果（Enum-based；无 String / Any / type+value / metadata）。
 *
 * 冻结约束（P19 Contract 修正版）：
 *  - 统一 enum，替代 per-type Outcome（避免 DecisionPolicy.outcome: Any）。
 *  - 不携带 confidence / score / weight / reason / LLM 输出 / 自然语言解释。
 *  - NEUTRAL = 语义中性默认（Deterministic Default），不代表任何作者偏好。
 *
 * 合法组合（由 Rule Matrix 保证）：
 *  (STORY_DIRECTION, PROGRESSIVE_REVEAL / DIRECT_PRESENTATION / NEUTRAL)
 *  (WRITING_STYLE,   SHORT_DOMINANT / LONG_DOMINANT / NEUTRAL)
 */
enum class DecisionOutcome {
    /** STORY_DIRECTION：渐进揭示。 */
    PROGRESSIVE_REVEAL,

    /** STORY_DIRECTION：直接呈现。 */
    DIRECT_PRESENTATION,

    /** WRITING_STYLE：短句主导。 */
    SHORT_DOMINANT,

    /** WRITING_STYLE：长句主导。 */
    LONG_DOMINANT,

    /** 语义中性默认（跨类型通用；Deterministic Default）。 */
    NEUTRAL,
}
