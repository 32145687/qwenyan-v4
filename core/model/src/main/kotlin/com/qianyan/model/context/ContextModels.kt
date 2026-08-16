package com.qianyan.model.context

import com.qianyan.model.BaseNovelId
import com.qianyan.model.IntentType
import com.qianyan.model.PlanningScope
import com.qianyan.model.RequestId
import com.qianyan.model.VariantId
import com.qianyan.model.VariantScope
import kotlinx.serialization.Serializable

/**
 * ContextCandidate。V4.2 增加作用域信息，使 Agent / Context Pipeline
 * 只检索当前 baseNovelId + variantId 作用域下可见的数据。
 */
@Serializable
data class ContextCandidate(
    val id: String,
    val type: ContextType,
    val content: String,
    val successRegex: String,
    val baseNovelId: BaseNovelId? = null,
    val variantId: VariantId? = null,
    val scope: VariantScope = VariantScope.ORIGINAL,
    val relevance: Float = 0f,
    val authority: Float = 0f,
    val priority: Float = 0f,
    val recency: Float = 0f,
    val tokenCost: Int = 0,
    val score: Float = 0f,
)

@Serializable
enum class ContextType {
    CURRENT_CHAPTER_TEXT, CURRENT_SCENE, CHARACTER_STATE, CURRENT_EVENT,
    DIRECT_CHARACTERS, RELATED_CHARACTERS, TIMELINE_POSITION, WORLD_RULES,
    RECENT_EVENTS, RELEVANT_HISTORY, RELEVANT_MEMORY, STYLE_PROFILE, WRITING_MEMORY,
    CURRENT_STORY_ARC, CURRENT_ACT, CHAPTER_PLAN, CURRENT_CONFLICTS,
    CHARACTER_ARC_PROGRESS, OPEN_FORESHADOWING, EXPECTED_PAYOFFS,
    INFORMATION_STATE, PACING, EMOTIONAL_ARC,
}

/** 请求目标引用（架构 §21.1）。 */
@Serializable
enum class TargetKind { CHAPTER, SCENE, ARC, NOVEL }

@JvmInline
@Serializable
value class TargetRefId(val value: String)

/**
 * 结构化写作请求（架构 §21.1，IntentAgent 输出）。
 * V4.2 增加 baseNovelId / variantId / scope，使本次写作确定作用于某 Original 或 Variant。
 */
@Serializable
data class UserWritingRequest(
    val requestId: RequestId,
    val intentType: IntentType,
    val target: TargetRef,
    val planningScope: PlanningScope,
    val constraints: List<String> = emptyList(),
    val styleHints: List<String> = emptyList(),
    val rawText: String = "",
    val baseNovelId: BaseNovelId? = null,
    val variantId: VariantId? = null,
    val scope: VariantScope = VariantScope.ORIGINAL,
)

/** 请求目标：类型 + 目标全局 ID。 */
@Serializable
data class TargetRef(
    val kind: TargetKind,
    val id: TargetRefId? = null,
)