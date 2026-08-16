package com.qianyan.model.knowledge

import com.qianyan.model.EvidenceId
import com.qianyan.model.KnowledgeId
import com.qianyan.model.NovelId
import com.qianyan.model.UserId
import com.qianyan.model.VariantId
import com.qianyan.model.VariantScope
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
enum class FactLevel {
    EXPLICIT, INFERRED, UNCERTAIN, USER_CONFIRMED, USER_CREATED, GENERATED,
}

@Serializable
enum class KnowledgeCategory {
    CHARACTER_IDENTITY, CHARACTER_PERSONALITY, CHARACTER_GOAL,
    CHARACTER_MOTIVATION, CHARACTER_ABILITY, CHARACTER_KNOWLEDGE,
    CHARACTER_SECRET, CHARACTER_RELATIONSHIP, CHARACTER_STATE,
    WORLD_RULE, WORLD_HISTORY, LOCATION_INFO, FACTION_INFO, ITEM_INFO,
    EVENT, TIMELINE, PLOT_POINT,
    STYLE_FEATURE,
}

@Serializable
enum class KnowledgeStatus { DRAFT, ACTIVE, SUPERSEDED, REJECTED, DEPRECATED }

@Serializable
enum class SourceType {
    ORIGINAL_TEXT, AI_ANALYSIS, AI_INFERENCE, USER_INPUT,
    USER_CONFIRMATION, AI_GENERATION, DERIVED,
}

@Serializable
enum class ReferenceType { ORIGINAL_CHAPTER, TEXT_CHUNK, ANALYSIS_TASK, USER_INPUT_ID, KNOWLEDGE_ENTRY }

@Serializable
data class SourceReference(val refType: ReferenceType, val refId: String, val description: String = "")

@Serializable
data class KnowledgeSource(val type: SourceType, val references: List<SourceReference> = emptyList())

@Serializable
data class Evidence(
    val evidenceId: EvidenceId,
    val knowledgeId: KnowledgeId,
    val source: KnowledgeSource,
    val excerpt: String = "",
    val description: String = "",
)

/**
 * KnowledgeEntry。
 * V4.2 修改：增加 scope + variantId，以区分 Original 只读知识与 Variant Override/Add/Remove。
 * - scope=ORIGINAL, variantId=null  → Original 基座（只读）。
 * - scope=VARIANT, variantId=V      → 该 Variant 的新知识 / 覆盖知识。
 */
@Serializable
data class KnowledgeEntry(
    val id: KnowledgeId,
    val novelId: NovelId,
    val variantId: VariantId? = null,
    val scope: VariantScope = VariantScope.ORIGINAL,
    val type: KnowledgeType = KnowledgeType.FACT,
    val category: KnowledgeCategory,
    val content: String,
    val factLevel: FactLevel,
    val confidence: Float = 0.5f,
    val source: KnowledgeSource = KnowledgeSource(SourceType.AI_INFERENCE),
    val evidence: List<Evidence> = emptyList(),
    val status: KnowledgeStatus = KnowledgeStatus.DRAFT,
    val createdBy: KnowledgeCreator = KnowledgeCreator.AI,
    val confirmedBy: UserId? = null,
    val confirmedAt: Instant? = null,
    val version: Int = 1,
    val previousVersionId: KnowledgeId? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
)

@Serializable
enum class KnowledgeType { FACT, RULE, EVENT, STATE, RELATIONSHIP, STYLE }

@Serializable
enum class KnowledgeCreator { AI, USER, SYSTEM }