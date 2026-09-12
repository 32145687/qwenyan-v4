package com.qianyan.model.lcl

import com.qianyan.model.ChapterId
import com.qianyan.model.CharacterId
import com.qianyan.model.ForeshadowingId
import com.qianyan.model.NarrativeDeltaId
import com.qianyan.model.NarrativeStateId
import com.qianyan.model.NovelId
import com.qianyan.model.PacingProfile
import com.qianyan.model.StoryConflictId
import com.qianyan.model.VariantId
import com.qianyan.model.VariantScope
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/**
 * P13 LCL-A · Narrative State（叙事状态账本）领域模型。
 *
 * 定位：**可增量维护、按章递进的一等叙事状态**，区别于 StoryWorldContextResolver 的"即时全量派生"。
 * 每确认一章 Final Draft → 追加一条 [NarrativeDelta] → 经 [NarrativeStateFold.fold] 确定性折叠为 [NarrativeState]。
 *
 * 设计规则：
 *  - 纯领域模型：**无 storage 依赖、无 AI/LLM 调用**、确定性折叠；
 *  - Variant 隔离：账本以 (novelId, variantId) 为身份；Original（variantId=null）账本**只读**（写保护由上层校验）；
 *  - version 乐观锁：version = 已追加 Delta 数，append 支持 expectedVersion 校验防并发丢失更新。
 *
 * 仅做领域建模，不触碰 P12.5 Provider / Agent / Workflow / StoryWorldContextResolver / 既有 Story State 表。
 */

/** 人物阶段投影（Narrative State 内的角色进展示意图）。 */
@Serializable
data class CharacterStage(
    val characterId: CharacterId,
    val stage: String = "",
    val goal: String = "",
)

/** 情感/关系变化（一次本章关系增量）。 */
@Serializable
data class RelationshipDelta(
    val participants: List<CharacterId> = emptyList(),
    val change: String = "",
)

/** 伏笔压力（对某 Foreshadow 的压力值）。 */
@Serializable
data class ForeshadowPressure(
    val foreshadowId: ForeshadowingId,
    val pressure: Int = 0,
)

/** 未解决问题（OpenThread）。 */
@Serializable
data class OpenThread(
    val id: String,
    val description: String,
)

/**
 * 一次叙事增量（某章产生的最小叙事变化）。
 *
 * 折叠语义（[NarrativeStateFold]）：
 *  - 标量字段（mainGoal / currentConflict / pacing / summary）：last-write-wins（取末条非空值）；
 *  - 集合字段（openThreads / characterStages / relationshipDeltas / foreshadowPressures）：以末条 Delta 为"本章最终集合"（replace 语义）。
 */
@Serializable
data class NarrativeDelta(
    val deltaId: NarrativeDeltaId,
    val novelId: NovelId,
    val variantId: VariantId?,
    val scope: VariantScope = if (variantId == null) VariantScope.ORIGINAL else VariantScope.VARIANT,
    val chapterId: ChapterId? = null,
    val mainGoal: String? = null,
    /** 当前冲突引用（引用既有 StoryConflictId；不含冲突正文）。 */
    val currentConflict: StoryConflictId? = null,
    val openThreads: List<OpenThread> = emptyList(),
    val characterStages: Map<CharacterId, CharacterStage> = emptyMap(),
    val relationshipDeltas: List<RelationshipDelta> = emptyList(),
    val foreshadowPressures: List<ForeshadowPressure> = emptyList(),
    val pacing: PacingProfile? = null,
    /** 本章变化摘要（投影为 NarrativeState.lastChapterDelta）。 */
    val summary: String = "",
    val createdAt: Instant,
)

/** 当前折叠后的叙事状态账本（每 novel+variant 一条）。 */
@Serializable
data class NarrativeState(
    val id: NarrativeStateId,
    val novelId: NovelId,
    val variantId: VariantId?,
    val scope: VariantScope = if (variantId == null) VariantScope.ORIGINAL else VariantScope.VARIANT,
    /** 版本号 = 已折叠 Delta 数（乐观锁；append 的 expectedVersion 与之对齐）。 */
    val version: Long,
    val mainGoal: String = "",
    /** 当前冲突引用。 */
    val currentConflict: StoryConflictId? = null,
    val openThreads: List<OpenThread> = emptyList(),
    val characterStages: Map<CharacterId, CharacterStage> = emptyMap(),
    val relationshipDeltas: List<RelationshipDelta> = emptyList(),
    val foreshadowPressures: List<ForeshadowPressure> = emptyList(),
    val currentPacing: PacingProfile? = null,
    /** 最近一次应用章节产生的变化摘要。 */
    val lastChapterDelta: String = "",
    val updatedAt: Instant,
)

/** Narrative State 账本的确定性折叠（纯函数，无 storage / 无 AI）。 */
object NarrativeStateFold {

    /** 由 (novelId, variantId) 推导的确定性账本 ID（同 scope 恒同一条账本，upsert 幂等）。 */
    fun ledgerId(novelId: NovelId, variantId: VariantId?): NarrativeStateId =
        NarrativeStateId("ns-" + novelId.value + "-" + (variantId?.value ?: "original"))

    /**
     * 把 deltas 确定性折叠为当前 [NarrativeState]。
     * 顺序：createdAt 升序 → deltaId 升序（稳定、可复现，无随机）。
     */
    fun fold(deltas: List<NarrativeDelta>, novelId: NovelId, variantId: VariantId?): NarrativeState {
        val ordered = deltas.sortedWith(compareBy({ it.createdAt }, { it.deltaId.value }))
        val last = ordered.lastOrNull()
        return NarrativeState(
            id = ledgerId(novelId, variantId),
            novelId = novelId,
            variantId = variantId,
            scope = if (variantId == null) VariantScope.ORIGINAL else VariantScope.VARIANT,
            version = ordered.size.toLong(),
            mainGoal = lastNonNull(ordered) { it.mainGoal } ?: "",
            currentConflict = lastNonNull(ordered) { it.currentConflict },
            openThreads = last?.openThreads ?: emptyList(),
            characterStages = last?.characterStages ?: emptyMap(),
            relationshipDeltas = last?.relationshipDeltas ?: emptyList(),
            foreshadowPressures = last?.foreshadowPressures ?: emptyList(),
            currentPacing = last?.pacing,
            lastChapterDelta = last?.summary ?: "",
            updatedAt = last?.createdAt ?: EPOCH,
        )
    }

    /** 有序折叠加权——取最后一个非空值（label末尾扫描）。 */
    private fun <T> lastNonNull(ordered: List<NarrativeDelta>, sel: (NarrativeDelta) -> T?): T? {
        var out: T? = null
        for (d in ordered) sel(d)?.let { out = it }
        return out
    }

    private val EPOCH: Instant = Instant.fromEpochSeconds(0, 0)
}