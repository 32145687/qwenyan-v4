package com.qianyan.model.timeline

import com.qianyan.model.ChapterId
import com.qianyan.model.CharacterId
import com.qianyan.model.EventId
import com.qianyan.model.KnowledgeId
import com.qianyan.model.LocationId
import com.qianyan.model.NovelId
import com.qianyan.model.ParticipantRole
import com.qianyan.model.StateId
import com.qianyan.model.TimelineEntryId
import com.qianyan.model.TimeType
import com.qianyan.model.VariantId
import com.qianyan.model.VariantScope
import com.qianyan.model.knowledge.Evidence
import com.qianyan.model.knowledge.FactLevel
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
data class TimelinePosition(
    val storyTime: StoryTime? = null,
    val chapterReference: ChapterReference? = null,
    val relativeTime: RelativeTime? = null,
    val confidence: Float = 1f,
)

@Serializable
data class StoryTime(
    val year: Int? = null,
    val month: Int? = null,
    val day: Int? = null,
    val hour: Int? = null,
    val era: String? = null,
    val description: String = "",
)

@Serializable
data class ChapterReference(val chapterId: ChapterId, val sceneIndex: Int? = null, val position: String? = null)

@Serializable
data class RelativeTime(val baseEventId: EventId, val offset: String = "", val relation: TimeRelation = TimeRelation.UNKNOWN)

@Serializable
enum class TimeRelation { BEFORE, AFTER, DURING, UNKNOWN }

/**
 * Event。V4.2：增加 scope + variantId。
 * - ORIGINAL：原文已发生事实。
 * - VARIANT + variantId：Variant 的事件（覆盖/新增）；"删除"事件用 EntityOverride REMOVE 表达，不在此处凭空删表。
 */
@Serializable
data class Event(
    val id: EventId,
    val novelId: NovelId,
    val variantId: VariantId? = null,
    val scope: VariantScope = VariantScope.ORIGINAL,
    val name: String,
    val description: String = "",
    val type: EventType = EventType.MAIN_PLOT,
    val importance: Int = 5,
    val what: String = "",
    val `when`: TimelinePosition? = null,
    val where: List<LocationId> = emptyList(),
    val who: List<CharacterId> = emptyList(),
    val cause: List<EventId> = emptyList(),
    val consequence: List<EventId> = emptyList(),
    val participants: List<EventParticipant> = emptyList(),
    val evidence: List<Evidence> = emptyList(),
    val status: EventStatus = EventStatus.CONFIRMED,
    val chapterId: ChapterId? = null,
    val factLevel: FactLevel = FactLevel.EXPLICIT,
    val createdAt: Instant,
)

@Serializable
data class EventParticipant(
    val characterId: CharacterId,
    val role: ParticipantRole = ParticipantRole.ACTOR,
    val actions: List<String> = emptyList(),
    val stateBefore: StateId? = null,
    val stateAfter: StateId? = null,
)

@Serializable
enum class EventType { MAIN_PLOT, SUB_PLOT, CHARACTER_ARC, WORLD_EVENT, REVELATION, TRANSITION }

@Serializable
enum class EventStatus { CONFIRMED, PLANNED, IN_PROGRESS, COMPLETED, CANCELLED }

/**
 * TimelineEntry。V4.2：增加 scope + variantId，使 Timeline 可区分 Original 与 Variant 主线。
 */
@Serializable
data class TimelineEntry(
    val id: TimelineEntryId,
    val novelId: NovelId,
    val variantId: VariantId? = null,
    val scope: VariantScope = VariantScope.ORIGINAL,
    val position: TimelinePosition,
    val eventId: EventId? = null,
    val description: String = "",
    val timeType: TimeType = TimeType.UNKNOWN,
    val chapterId: ChapterId? = null,
)