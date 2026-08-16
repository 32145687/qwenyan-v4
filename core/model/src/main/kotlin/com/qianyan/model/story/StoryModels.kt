package com.qianyan.model.story

import com.qianyan.model.ActId
import com.qianyan.model.ArcId
import com.qianyan.model.ArcStatus
import com.qianyan.model.BeatId
import com.qianyan.model.ChapterId
import com.qianyan.model.ChapterPlanId
import com.qianyan.model.CharacterArcProgressId
import com.qianyan.model.CharacterId
import com.qianyan.model.CharacterRole
import com.qianyan.model.EventId
import com.qianyan.model.ForeshadowingId
import com.qianyan.model.KnowledgeId
import com.qianyan.model.LocationId
import com.qianyan.model.LocationRef
import com.qianyan.model.NovelId
import com.qianyan.model.PacingProfile
import com.qianyan.model.PayoffId
import com.qianyan.model.RevealLevel
import com.qianyan.model.SceneId
import com.qianyan.model.ScenePlanId
import com.qianyan.model.StoryConflictId
import com.qianyan.model.TurningPoint
import com.qianyan.model.VariantId
import com.qianyan.model.VariantScope
import com.qianyan.model.timeline.TimelinePosition
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/** 指向某结构节点的一处引用（Variant 复用 Original 未改节点，或其他 Variant 自有节点）。 */
@Serializable
data class OriginalNodeRef(
    val kind: StoryNodeKind,
    val id: String,
    val note: String = "",
)

@Serializable
enum class StoryNodeKind { ARC, ACT, CHAPTER, CHAPTER_PLAN, SCENE, SCENE_PLAN, BEAT }

/** Chapter：文本组织单位（≠ Scene 戏剧单位）。P1 承载 scope，正文存储与 Draft 在 Writing/Storage 层。 */
@Serializable
data class Chapter(
    val chapterId: ChapterId,
    val novelId: NovelId,
    val variantId: VariantId? = null,
    val scope: VariantScope = VariantScope.ORIGINAL,
    val title: String = "",
    val order: Int = 0,
    val status: ChapterStatus = ChapterStatus.PLANNED,
    val createdAt: Instant,
)

@Serializable
enum class ChapterStatus { PLANNED, DRAFTING, WRITTEN, REVISED, FINAL }

/** Scene：戏剧单位。 */
@Serializable
data class Scene(
    val sceneId: SceneId,
    val novelId: NovelId,
    val variantId: VariantId? = null,
    val scope: VariantScope = VariantScope.ORIGINAL,
    val chapterId: ChapterId? = null,
    val location: LocationId? = null,
    val time: TimelinePosition? = null,
    val order: Int = 0,
)

/**
 * StoryArc。V4.2 增加 scope + variantId + originalRef：
 * Variant 可拥有一套自有结构（isOwned=originalRef.sourceOwned），也可引用 Original 未改 Arc。
 */
@Serializable
data class StoryArc(
    val arcId: ArcId,
    val novelId: NovelId,
    val variantId: VariantId? = null,
    val scope: VariantScope = VariantScope.ORIGINAL,
    val originalRef: OriginalNodeRef? = null,
    val name: String,
    val description: String = "",
    val purpose: String = "",
    val startState: String = "",
    val targetState: String = "",
    val mainConflict: StoryConflictId? = null,
    val stakes: Stakes? = null,
    val keyCharacters: List<CharacterId> = emptyList(),
    val keyEvents: List<EventId> = emptyList(),
    val turningPoints: List<TurningPoint> = emptyList(),
    val climax: Climax? = null,
    val resolution: String = "",
    val relatedForeshadowing: List<ForeshadowingId> = emptyList(),
    val expectedPayoffs: List<PayoffId> = emptyList(),
    val status: ArcStatus = ArcStatus.PLANNED,
)

@Serializable
data class Climax(val position: String = "", val content: String = "")

@Serializable
data class Act(
    val actId: ActId,
    val arcId: ArcId,
    val novelId: NovelId,
    val variantId: VariantId? = null,
    val scope: VariantScope = VariantScope.ORIGINAL,
    val originalRef: OriginalNodeRef? = null,
    val order: Int = 0,
    val name: String = "",
    val goal: String = "",
    val conflict: String = "",
    val turningPoint: String = "",
    val majorEvents: List<EventId> = emptyList(),
    val characterChanges: List<CharacterArcProgressId> = emptyList(),
    val emotionalDirection: String = "",
    val endingCondition: String = "",
)

@Serializable
data class ChapterPlan(
    val chapterPlanId: ChapterPlanId,
    val chapterId: ChapterId? = null,
    val arcId: ArcId,
    val actId: ActId,
    val novelId: NovelId,
    val variantId: VariantId? = null,
    val scope: VariantScope = VariantScope.ORIGINAL,
    val originalRef: OriginalNodeRef? = null,
    val chapterGoal: String = "",
    val mainConflict: StoryConflictId? = null,
    val characterGoals: Map<CharacterId, String> = emptyMap(),
    val expectedEvents: List<String> = emptyList(),
    val requiredInformation: List<KnowledgeId> = emptyList(),
    val foreshadowing: List<ForeshadowingId> = emptyList(),
    val payoffs: List<PayoffId> = emptyList(),
    val emotionalDirection: String = "",
    val pacing: PacingProfile? = null,
    val endingHook: String = "",
    val constraints: List<String> = emptyList(),
    val forbiddenEvents: List<String> = emptyList(),
    val scenePlans: List<ScenePlanId> = emptyList(),
)

/** 场景起始/目标状态。 */
@Serializable
data class SceneState(
    val summary: String = "",
    val characterStates: Map<CharacterId, String> = emptyMap(),
)

@Serializable
data class ScenePlan(
    val scenePlanId: ScenePlanId,
    val chapterPlanId: ChapterPlanId,
    val sceneId: SceneId? = null,
    val novelId: NovelId,
    val variantId: VariantId? = null,
    val scope: VariantScope = VariantScope.ORIGINAL,
    val originalRef: OriginalNodeRef? = null,
    val sceneGoal: String = "",
    val characters: List<CharacterRole> = emptyList(),
    val location: LocationRef = LocationRef(LocationId("")),
    val time: TimelinePosition? = null,
    val entryState: SceneState = SceneState(),
    val desiredExitState: SceneState = SceneState(),
    val conflict: String = "",
    val stakes: Stakes? = null,
    val motivation: String = "",
    val emotionalState: String = "",
    val importantEvents: List<String> = emptyList(),
    val discoveries: List<String> = emptyList(),
    val informationRevealed: List<KnowledgeId> = emptyList(),
    val informationHidden: List<KnowledgeId> = emptyList(),
    val foreshadowing: List<ForeshadowingId> = emptyList(),
    val payoff: List<PayoffId> = emptyList(),
    val characterChanges: List<CharacterArcProgressId> = emptyList(),
    val events: List<String> = emptyList(),
    val pacing: PacingProfile? = null,
    val pov: CharacterId? = null,
    val dialoguePurpose: String = "",
    val revealLevel: RevealLevel = RevealLevel.PARTIAL,
    val constraints: List<String> = emptyList(),
    val forbiddenEvents: List<String> = emptyList(),
    val expectedOutcome: String = "",
    val beats: List<BeatId> = emptyList(),
)

@Serializable
data class Beat(
    val beatId: BeatId,
    val scenePlanId: ScenePlanId,
    val novelId: NovelId,
    val variantId: VariantId? = null,
    val scope: VariantScope = VariantScope.ORIGINAL,
    val originalRef: OriginalNodeRef? = null,
    val order: Int = 0,
    val purpose: String = "",
    val action: String = "",
    val conflict: String? = null,
    val characterReaction: Map<CharacterId, String> = emptyMap(),
    val information: List<KnowledgeId> = emptyList(),
    val emotionalChange: String = "",
    val result: String = "",
)

/* ---- Story Intelligence 支撑模型 ---- */

@Serializable
data class StoryConflict(
    val conflictId: StoryConflictId,
    val novelId: NovelId,
    val variantId: VariantId? = null,
    val scope: VariantScope = VariantScope.ORIGINAL,
    val type: ConflictType = ConflictType.EXTERNAL,
    val participants: List<CharacterId> = emptyList(),
    val goalA: String = "",
    val goalB: String = "",
    val status: ConflictStatus = ConflictStatus.OPEN,
)

@Serializable
enum class ConflictType { EXTERNAL, INTERNAL, INTERPERSONAL, ENVIRONMENTAL, SOCIAL, MYSTERY }

@Serializable
enum class ConflictStatus { OPEN, ESCALATING, PEAK, RESOLVED, ABANDONED }

@Serializable
data class Stakes(val level: Int = 0, val description: String = "")

@Serializable
data class Foreshadowing(
    val foreshadowingId: ForeshadowingId,
    val novelId: NovelId,
    val variantId: VariantId? = null,
    val scope: VariantScope = VariantScope.ORIGINAL,
    val content: String = "",
    val plantedAt: String = "",
    val payoffWindow: String = "",
    val status: ForeshadowingStatus = ForeshadowingStatus.OPEN,
)

@Serializable
enum class ForeshadowingStatus { OPEN, EXPIRING, RECALLED, MERGED, DROPPED }

@Serializable
data class Payoff(
    val payoffId: PayoffId,
    val novelId: NovelId,
    val variantId: VariantId? = null,
    val scope: VariantScope = VariantScope.ORIGINAL,
    val content: String = "",
    val fulfilledAt: String = "",
)

@Serializable
enum class InformationState { KNOWN_TO_READER, KNOWN_TO_POV, KNOWN_TO_SUSPICIOUS, SECRET, REVEALED }

@Serializable
data class EmotionalArc(
    val arcId: ArcId,
    val novelId: NovelId,
    val variantId: VariantId? = null,
    val scope: VariantScope = VariantScope.ORIGINAL,
    val beats: List<String> = emptyList(),
)