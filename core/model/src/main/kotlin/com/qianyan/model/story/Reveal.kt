package com.qianyan.model.story

import com.qianyan.model.ChapterId
import com.qianyan.model.NovelId
import com.qianyan.model.RevealId
import com.qianyan.model.VariantId
import com.qianyan.model.VariantScope
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/**
 * P13 LCL-D · Reveal = **向读者揭示某一信息/知识事实的运行时 Story State fact**。
 *
 * 语义冻结（Decision Record Revision 1）：
 *  - 主体**仅 READER**；**无 `revealedTo` 字段**；不引入 CHARACTER / CHARACTER_ID / BOTH / Knowledge State；
 *  - Reveal ≠ Event；Reveal ≠ TimelineEntry；Reveal ≠ Foreshadow（伏笔生命周期）≠ Payoff；
 *  - LCL-C 的 Foreshadow RESOLVED **不**由 Reveal 自动触发（无 automatic transition）；
 *  - Reveal **不进入** ChapterContextPack / NarrativeState（不产 NarrativeDelta、不 bump version）。
 *
 * 生命周期：Reveal 是**已经发生的 fact**，无复杂状态机；重点保证 stable identity / deterministic ordering /
 * variant isolation（Original 只读，Variant own+Override）/ duplicate handling。
 *
 * @param informationId 被揭示的信息/知识事实的引用（opaque reference；不搭建完整 Knowledge System）。
 */
@Serializable
data class Reveal(
    val revealId: RevealId,
    val novelId: NovelId,
    val variantId: VariantId? = null,
    val scope: VariantScope = VariantScope.ORIGINAL,
    val chapterId: ChapterId? = null,
    val informationId: String,
    val occurredAt: Instant,
    val reason: String? = null,
    val createdAt: Instant,
)