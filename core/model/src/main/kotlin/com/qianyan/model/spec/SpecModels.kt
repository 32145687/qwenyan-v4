package com.qianyan.model.spec

import com.qianyan.model.ConflictId
import com.qianyan.model.ConfirmationId
import com.qianyan.model.KnowledgeId
import com.qianyan.model.NovelId
import com.qianyan.model.UserId
import com.qianyan.model.VariantId
import com.qianyan.model.VariantScope
import com.qianyan.model.knowledge.Evidence
import com.qianyan.model.knowledge.KnowledgeEntry
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/*
 * Spec 域：输出校验、冲突检测、用户确认（架构 §12 / §13 / §14）。
 * 约定：为避免 Map<String, Any> 充当领域模型（AskNo.12），原架构中的
 * normalizedData / originalValue / resolvedValue 统一用结构化 JsonElement 表达，
 * 字段名保持不变，仅替代动态类型。
 */

@Serializable
enum class IssueSeverity { ERROR, WARNING, INFO }

/** 单条校验问题（架构 §12.2）。original/resolved 为结构化 JSON，不使用 Any。 */
@Serializable
data class ValidationIssue(
    val field: String,
    val severity: IssueSeverity,
    val message: String,
    val originalValue: JsonElement? = null,
    val resolvedValue: JsonElement? = null,
)

/** 输出校验结果（架构 §12）。 */
@Serializable
data class ValidationResult(
    val passed: Boolean,
    val issues: List<ValidationIssue> = emptyList(),
    val normalizedData: JsonElement? = null,
    val conflicts: List<KnowledgeConflict> = emptyList(),
    val partialSuccess: Boolean = false,
    val failedFields: List<String> = emptyList(),
    val retryCount: Int = 0,
)

@Serializable
enum class ConflictType {
    CONTRADICTION, INCONSISTENCY, TIMELINE_CONFLICT,
    CHARACTER_CONFLICT, WORLD_RULE_VIOLATION,
}

@Serializable
enum class ConflictSeverity { CRITICAL, MAJOR, MINOR, COSMETIC }

@Serializable
enum class ClaimSource { AI_GENERATED, AI_INFERRED, USER_INPUT, TXT_ANALYSIS }

@Serializable
enum class ResolutionDecision { KEEP_EXISTING, ACCEPT_NEW, MERGE, DEFER }

/** 冲突解决结果（最小；完整裁决策略为后续阶段）。 */
@Serializable
data class ConflictResolution(
    val decision: ResolutionDecision? = null,
    val note: String = "",
    val resolvedAt: Instant? = null,
)

/** 冲突检测模型（架构 §13.1）。V4.2 增加 variantId + scope 以定位冲突所属 Variant。 */
@Serializable
data class KnowledgeConflict(
    val id: ConflictId,
    val novelId: NovelId,
    val variantId: VariantId? = null,
    val scope: VariantScope = VariantScope.ORIGINAL,
    val type: ConflictType,
    val severity: ConflictSeverity,
    // 已有知识
    val existingKnowledge: KnowledgeEntry,
    val existingEvidence: List<Evidence> = emptyList(),
    // 新声明
    val newClaim: String,
    val newClaimSource: ClaimSource,
    // 解决
    val resolution: ConflictResolution? = null,
    val resolvedBy: UserId? = null,
    val resolvedAt: Instant? = null,
    val createdAt: Instant,
)

@Serializable
enum class ConfirmationType { KNOWLEDGE_CONFIRM, CONFLICT_RESOLVE, FACT_UPGRADE, STATE_UPDATE }

@Serializable
enum class ConfirmationStatus { PENDING, ACCEPTED, REJECTED, EDITED, DEFERRED, EXPIRED }

/** 待确认条目（架构 §14.2）。V4.2 增加 variantId + scope 保持 Variant 一致性。 */
@Serializable
data class PendingConfirmation(
    val id: ConfirmationId,
    val novelId: NovelId,
    val variantId: VariantId? = null,
    val scope: VariantScope = VariantScope.ORIGINAL,
    val knowledgeId: KnowledgeId,
    val type: ConfirmationType,
    val suggestion: String,
    val aiRationale: String? = null,
    val confidence: Float = 0f,
    val evidence: List<Evidence> = emptyList(),
    val conflicts: List<KnowledgeConflict> = emptyList(),
    val status: ConfirmationStatus = ConfirmationStatus.PENDING,
    val createdAt: Instant,
    val expiresAt: Instant? = null,
    val resolvedAt: Instant? = null,
)