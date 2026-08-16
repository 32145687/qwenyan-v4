package com.qianyan.model.character

import com.qianyan.model.ArcStatus
import com.qianyan.model.CharacterArcId
import com.qianyan.model.CharacterArcProgressId
import com.qianyan.model.CharacterId
import com.qianyan.model.KnowledgeId
import com.qianyan.model.LocationId
import com.qianyan.model.NovelId
import com.qianyan.model.RelationshipId
import com.qianyan.model.StateId
import com.qianyan.model.StateSource
import com.qianyan.model.timeline.TimelinePosition
import com.qianyan.model.TurningPoint
import com.qianyan.model.VariantId
import com.qianyan.model.VariantScope
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/**
 * Character 基础档案（V4.2 增加 scope + variantId）。
 * - scope=ORIGINAL：Original 人物。
 * - scope=VARIANT + variantId：Variant Override 后的版本。
 * toId 指向全局唯一 CharacterId；同一人物跨 Variant 共享同一 ID，用 Override 表达变体差异。
 */
@Serializable
data class Character(
    val characterId: CharacterId,
    val novelId: NovelId,
    val variantId: VariantId? = null,
    val scope: VariantScope = VariantScope.ORIGINAL,
    val name: String,
    val personality: List<String> = emptyList(),
    val goals: List<String> = emptyList(),
    val fears: List<String> = emptyList(),
    val description: String = "",
    val createdAt: Instant,
    val updatedAt: Instant,
)

@Serializable
data class CharacterState(
    val id: StateId,
    val characterId: CharacterId,
    val novelId: NovelId,
    val variantId: VariantId? = null,
    val scope: VariantScope = VariantScope.ORIGINAL,
    val chapterId: com.qianyan.model.ChapterId? = null,
    val timelinePosition: TimelinePosition? = null,
    val snapshotType: SnapshotType,
    val location: LocationId? = null,
    val physicalState: PhysicalState = PhysicalState(),
    val emotionalState: EmotionalState = EmotionalState(),
    val currentGoal: String? = null,
    val currentMotivation: String? = null,
    val relationships: List<RelationshipSnapshot> = emptyList(),
    val knownFacts: List<KnowledgeId> = emptyList(),
    val unknownFacts: List<KnowledgeId> = emptyList(),
    val abilities: List<AbilitySnapshot> = emptyList(),
    val source: StateSource = StateSource.INFERRED,
    val createdAt: Instant,
)

@Serializable
enum class SnapshotType { CHAPTER_START, CHAPTER_END, KEY_EVENT, USER_DEFINED }

@Serializable
data class PhysicalState(val label: String = "", val details: String = "")

@Serializable
data class EmotionalState(val label: String = "", val intensity: Float = 0f)

@Serializable
data class RelationshipSnapshot(val otherCharacterId: CharacterId, val relation: String = "")

@Serializable
data class AbilitySnapshot(val name: String = "", val description: String = "")

/**
 * CharacterArc（轨迹）：长期变化，≠ State 快照。
 * V4.2 增加 scope + variantId，以支持 Variant 覆写弧光（如重构后新弧）。
 */
@Serializable
data class CharacterArc(
    val characterArcId: CharacterArcId,
    val characterId: CharacterId,
    val novelId: NovelId,
    val variantId: VariantId? = null,
    val scope: VariantScope = VariantScope.ORIGINAL,
    val startingState: String = "",
    val coreDesire: String = "",
    val coreFear: String = "",
    val falseBelief: String = "",
    val internalConflict: String = "",
    val externalGoal: String = "",
    val obstacles: List<String> = emptyList(),
    val turningPoints: List<TurningPoint> = emptyList(),
    val growth: List<String> = emptyList(),
    val regression: List<String> = emptyList(),
    val finalState: String = "",
    val status: ArcStatus = ArcStatus.PLANNED,
)

@Serializable
data class CharacterArcProgress(
    val characterArcProgressId: CharacterArcProgressId,
    val characterArcId: CharacterArcId,
    val novelId: NovelId,
    val variantId: VariantId? = null,
    val scope: VariantScope = VariantScope.ORIGINAL,
    val checkpoint: String = "",
    val createdAt: Instant,
)

@Serializable
data class Relationship(
    val relationshipId: RelationshipId,
    val novelId: NovelId,
    val variantId: VariantId? = null,
    val scope: VariantScope = VariantScope.ORIGINAL,
    val characterA: CharacterId,
    val characterB: CharacterId,
    val relationType: String = "",
    val dynamics: String = "",
)