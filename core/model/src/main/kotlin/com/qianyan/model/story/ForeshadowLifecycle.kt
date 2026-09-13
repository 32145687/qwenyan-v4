package com.qianyan.model.story

import kotlinx.serialization.Serializable

/**
 * P13 LCL-C · Foreshadow 生命周期状态。
 *
 * 规范状态（MVP 最小集）：
 *  - [PLANTED]：已埋下（初始状态）；进入 activeForeshadows。
 *  - [ACTIVE]：投入当前剧情；进入 activeForeshadows。
 *  - [RESOLVED]：已兑现（终态）；退出 activeForeshadows，可记录 payoffChapterId。
 *  - [ABANDONED]：已放弃（终态）；退出 activeForeshadows。
 *
 * 非 MVP 状态（PAYOFF_READY / ESCALATING / BUILDING / DROPPED / RECALLED / MERGED / EXPIRING）本阶段不加入。
 */
@Serializable
enum class ForeshadowLifecycleState { PLANTED, ACTIVE, RESOLVED, ABANDONED }

/**
 * Foreshadow 生命周期状态机（**纯 Kotlin、确定性、无存储/AI 依赖**）。
 *
 * 唯一合法转换：
 * ```
 * PLANTED  → ACTIVE
 * PLANTED  → ABANDONED
 * ACTIVE   → RESOLVED
 * ACTIVE   → ABANDONED
 * ```
 * 其余一律非法（含同态转换）。
 */
object ForeshadowLifecycleRules {

    /** 是否允许 by from → to 的直接迁移（同态=False；终态出向=False）。 */
    fun canTransition(from: ForeshadowLifecycleState, to: ForeshadowLifecycleState): Boolean =
        (from == ForeshadowLifecycleState.PLANTED && (to == ForeshadowLifecycleState.ACTIVE || to == ForeshadowLifecycleState.ABANDONED)) ||
            (from == ForeshadowLifecycleState.ACTIVE && (to == ForeshadowLifecycleState.RESOLVED || to == ForeshadowLifecycleState.ABANDONED))

    /** 终态：RESOLVED / ABANDONED 之后不可再迁移。 */
    fun isTerminal(state: ForeshadowLifecycleState): Boolean =
        state == ForeshadowLifecycleState.RESOLVED || state == ForeshadowLifecycleState.ABANDONED

    /** `resolved` 双字段一致性：RESOLVED / ABANDONED → true，其余 false。 */
    fun resolvedOf(state: ForeshadowLifecycleState): Boolean = isTerminal(state)
}