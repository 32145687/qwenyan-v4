package com.qianyan.model.characterconsistency

import com.qianyan.model.CharacterId
import com.qianyan.model.ChapterId
import com.qianyan.model.ChapterPlanId
import com.qianyan.model.DraftId
import com.qianyan.model.memory.MemoryLayer
import com.qianyan.model.spec.IssueSeverity
import kotlinx.serialization.Serializable

/**
 * Phase 6 · CharacterConsistency 结果契约（v0.1）。
 *
 * 本模型只表达**只读检查结果**：不写 Draft / WorkingDraft / Change / Commit / Canonical。
 * evidence 必须来自本次检查的真实输入；无法落实的推断不得进入 findings。
 * severity 复用 [IssueSeverity]；不新造第二套严重度词汇。
 */
@Serializable
enum class CharacterConsistencyStatus {
    PASS,
    ISSUES,
    INSUFFICIENT_EVIDENCE,
}

@Serializable
enum class CharacterConsistencyScope {
    CURRENT_CHAPTER,
    CONTINUITY,
}

@Serializable
enum class CharacterConsistencyFindingType {
    TRAIT,
    GOAL,
    FEAR,
    ABILITY,
    RELATIONSHIP,
    KNOWLEDGE,
    TEMPORAL_STATE,
    UNRESOLVED_CHARACTER,
    OTHER,
}

@Serializable
enum class CharacterConsistencyConfidence {
    EXPLICIT,
    INFERRED,
    UNKNOWN,
}

@Serializable
enum class CharacterConsistencyEvidenceSource {
    CURRENT_DRAFT,
    PRIOR_CANONICAL,
    CHARACTER_PROFILE,
    CHARACTER_STATE,
    WORLD_RULE,
    EVENT,
    TIMELINE,
    FORESHADOW,
    PLAN,
    MEMORY,
}

@Serializable
enum class CharacterConsistencyMissingInput {
    CURRENT_DRAFT,
    CHARACTER_PROFILE,
    CHARACTER_STATE,
    PRIOR_CANONICAL,
    WORLD_RULE,
    EVENT,
    TIMELINE,
    FORESHADOW,
    MEMORY,
    PLAN,
}

/**
 * 单条证据。sourceId 对除 MEMORY 外的来源必填；MEMORY 用 layer + sourceIndex 定位
 * （当前 StoryWorldContext 的记忆层是 List<String>，没有稳定的 MemoryEntryId）。
 */
@Serializable
data class CharacterConsistencyEvidence(
    val quote: String,
    val sourceKind: CharacterConsistencyEvidenceSource,
    val sourceId: String? = null,
    val field: String? = null,
    val memoryLayer: MemoryLayer? = null,
    val sourceIndex: Int? = null,
    val startOffset: Int? = null,
    val endOffset: Int? = null,
)

@Serializable
data class CharacterConsistencyFinding(
    val type: CharacterConsistencyFindingType,
    val severity: IssueSeverity,
    val characterId: CharacterId? = null,
    val characterName: String,
    val message: String,
    val evidence: List<CharacterConsistencyEvidence> = emptyList(),
    val confidence: CharacterConsistencyConfidence,
    val suggestion: String? = null,
)

@Serializable
data class CheckedCharacter(
    val characterId: CharacterId,
    val name: String,
)

@Serializable
data class MemoryLayerCount(
    val layer: MemoryLayer,
    val count: Int,
)

/** 依据摘要：只放可追踪的 ID / 计数，不复制正文。 */
@Serializable
data class CharacterConsistencyBasis(
    val chapterId: ChapterId,
    val currentDraftId: DraftId? = null,
    val planId: ChapterPlanId? = null,
    val priorCanonicalDraftIds: List<DraftId> = emptyList(),
    /** D-23a：紧邻前章 Canonical 链中断处的缺章；missingInputs 语义不变。 */
    val priorCanonicalGapChapterIds: List<ChapterId> = emptyList(),
    val checkedCharacterIds: List<CharacterId> = emptyList(),
    val availableCharacterCount: Int = 0,
    val availableStateCount: Int = 0,
    val worldRuleCount: Int = 0,
    val eventCount: Int = 0,
    val timelineCount: Int = 0,
    val foreshadowCount: Int = 0,
    val memoryLayerCounts: List<MemoryLayerCount> = emptyList(),
    val worldContextUsed: Boolean = true,
)

@Serializable
data class CharacterConsistencyResult(
    val status: CharacterConsistencyStatus,
    val scope: CharacterConsistencyScope,
    val findings: List<CharacterConsistencyFinding> = emptyList(),
    val checkedCharacters: List<CheckedCharacter> = emptyList(),
    val missingInputs: List<CharacterConsistencyMissingInput> = emptyList(),
    val basis: CharacterConsistencyBasis,
    val summary: String,
    val truncated: Boolean = false,
)
