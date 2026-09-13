package com.qianyan.model.story

import com.qianyan.model.ChapterId
import com.qianyan.model.ForeshadowingId
import com.qianyan.model.NovelId
import com.qianyan.model.VariantId
import com.qianyan.model.VariantScope
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * Foreshadow（伏笔）持久化模型（P12.1.1 · Story State Persistence；P13 LCL-C 扩展生命周期）。
 *
 * 与既有 [Foreshadowing]（结构规划层、含 plantedAt/payoffWindow/status）不同，
 * 本模型是**章节落地后再簿记的伏笔状态**，供 StoryWorldContextResolver 重建全局上下文。
 *
 * ### P13 LCL-C 生命周期（冻结决策）
 *  - **[state]** 为**唯一规范状态源**（[ForeshadowLifecycleState]）；
 *  - **[resolved]** 由 state **派生**（`RESOLVED / ABANDONED → true`），仅为兼容旧调用而保留；
 *  - DB 中旧 `resolved` 列保留，与 state 同步写入（由 Engineering 层保证一致）；
 *  - [updatedAt] / [lastTransitionReason] / [payoffChapterId?] 在成功迁移时维护：
 *    仅 `RESOLVED` 允许 payoffChapterId；ABANDONED 不产生 payoffChapterId。
 */
@Serializable
data class Foreshadow(
    val foreshadowId: ForeshadowingId,
    val novelId: NovelId,
    val variantId: VariantId? = null,
    val scope: VariantScope = VariantScope.ORIGINAL,
    val chapterId: ChapterId? = null,
    val content: String,
    val state: ForeshadowLifecycleState = ForeshadowLifecycleState.PLANTED,
    val createdAt: Instant,
    val updatedAt: Instant = createdAt,
    val lastTransitionReason: String? = null,
    val payoffChapterId: ChapterId? = null,
) {
    /** 兼容字段：由 state 派生（RESOLVED / ABANDONED → true）。不参与序列化。 */
    @Transient
    val resolved: Boolean get() = ForeshadowLifecycleRules.resolvedOf(state)

    /** 兼容构造：旧 `resolved:Boolean` → 初始 state（true→RESOLVED，false→PLANTED）。 */
    constructor(
        foreshadowId: ForeshadowingId,
        novelId: NovelId,
        variantId: VariantId? = null,
        scope: VariantScope = VariantScope.ORIGINAL,
        chapterId: ChapterId? = null,
        content: String,
        resolved: Boolean,
        createdAt: Instant,
    ) : this(
        foreshadowId = foreshadowId,
        novelId = novelId,
        variantId = variantId,
        scope = scope,
        chapterId = chapterId,
        content = content,
        state = if (resolved) ForeshadowLifecycleState.RESOLVED else ForeshadowLifecycleState.PLANTED,
        updatedAt = createdAt,
        createdAt = createdAt,
    )
}