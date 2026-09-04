package com.qianyan.model.story

import com.qianyan.model.ChapterId
import com.qianyan.model.ForeshadowingId
import com.qianyan.model.NovelId
import com.qianyan.model.VariantId
import com.qianyan.model.VariantScope
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/**
 * Foreshadow（伏笔）持久化模型（P12.1.1 · Story State Persistence）。
 *
 * 与既有 [Foreshadowing]（结构规划层、含 plantedAt/payoffWindow/status）不同，
 * 本模型是**章节落地后再簿记的伏笔状态**：记录某 fell 章节埋下的伏笔内容，
 * 以及该伏笔是否已在后续章节兑现（resolved）。供 StoryWorldContextResolver 重建
 * 全局上下文时聚合未兑现伏笔。
 *
 * 简化持久化：仅保留基础稳定字段；chapterId 标识伏笔所在章节，resolved 表示是否已兑现。
 */
@Serializable
data class Foreshadow(
    val foreshadowId: ForeshadowingId,
    val novelId: NovelId,
    val variantId: VariantId? = null,
    val scope: VariantScope = VariantScope.ORIGINAL,
    val chapterId: ChapterId? = null,
    val content: String,
    val resolved: Boolean = false,
    val createdAt: Instant,
)