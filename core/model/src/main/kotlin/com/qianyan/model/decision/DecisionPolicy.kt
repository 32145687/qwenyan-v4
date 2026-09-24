package com.qianyan.model.decision

/*
 * P19 · DecisionPolicy —— 统一决策结果契约（stateless；Same Policy Shape + Origin）。
 *
 * 冻结约束（P19 Contract 修正版）：
 *  - 只有 decisionType / outcome / source 三个字段；无 id / version / timestamp / reason /
 *    matchedRule / confidence / score / weight / history / metadata / persistence 字段。
 *  - source 仅表示 provenance（RULE_MATRIX / DETERMINISTIC_DEFAULT），不代表权重 / 优先级 /
 *    可信度 / score / 决策强度。
 *  - Planner / Writer 统一消费本契约，不根据 source 分支。
 */

/** DecisionPolicy 产生来源（仅 provenance）。 */
enum class DecisionPolicySource {
    /** 由确定性 Rule Matrix（Preference → Core → DNA）产生。 */
    RULE_MATRIX,

    /** 由 Semantic-Neutral Default（NEUTRAL）产生。 */
    DETERMINISTIC_DEFAULT,
}

/** P19 统一决策结果。 */
data class DecisionPolicy(
    val decisionType: DecisionType,
    val outcome: DecisionOutcome,
    val source: DecisionPolicySource,
)
