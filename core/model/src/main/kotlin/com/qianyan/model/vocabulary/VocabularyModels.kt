package com.qianyan.model.vocabulary

import com.qianyan.model.NovelId
import com.qianyan.model.VariantId
import com.qianyan.model.VocabularyCandidateId
import com.qianyan.model.VocabularyEntryId
import com.qianyan.model.VocabularyId
import com.qianyan.model.VocabularyRuleId
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/**
 * Vocabulary（词库）域——独立于 Novel Knowledge。
 * Knowledge 回答"发生了什么"；Vocabulary 回答"该用什么词"。
 * 四级作用域（窄覆盖宽）：Global > Novel > Variant > Task。
 * 解析："灵石→星石" = Variant 层一条 replacement，优先级高于 Novel；不改动 Original。
 */
@Serializable
enum class VocabularyScopeLevel { GLOBAL, NOVEL, VARIANT, TASK }

@Serializable
enum class VocabularyEntryType {
    CHARACTER_APPELLATION, PROPER_NOUN, WORLD_TERM, PLACE, FACTION,
    REALM, ITEM, SKILL, FIXED_EXPRESSION, FORBIDDEN, STYLE_EXPRESSION,
}

/** 词库容器：某个作用域下的一组词条与规则。 */
@Serializable
data class Vocabulary(
    val vocabularyId: VocabularyId,
    val novelId: NovelId? = null,
    val variantId: VariantId? = null,
    val scopeLevel: VocabularyScopeLevel = VocabularyScopeLevel.GLOBAL,
    val name: String = "",
)

/** 词条：规范词 + 可选别名/替换目标。 */
@Serializable
data class VocabularyEntry(
    val entryId: VocabularyEntryId,
    val vocabularyId: VocabularyId,
    val novelId: NovelId? = null,
    val variantId: VariantId? = null,
    val scopeLevel: VocabularyScopeLevel = VocabularyScopeLevel.GLOBAL,
    val canonical: String,
    val aliases: List<String> = emptyList(),
    val type: VocabularyEntryType = VocabularyEntryType.PROPER_NOUN,
    val replacement: String? = null, // 如"灵石"→"星石"
    val status: VocabularyEntryStatus = VocabularyEntryStatus.APPROVED,
)

@Serializable
enum class VocabularyEntryStatus { CANDIDATE, APPROVED, DEPRECATED, REJECTED }

/** 确定性替换规则（不调 LLM）。 */
@Serializable
data class VocabularyRule(
    val ruleId: VocabularyRuleId,
    val vocabularyId: VocabularyId,
    val novelId: NovelId? = null,
    val variantId: VariantId? = null,
    val scopeLevel: VocabularyScopeLevel = VocabularyScopeLevel.GLOBAL,
    val from: String,
    val to: String,
    val enabled: Boolean = true,
    val deterministicOnly: Boolean = true, // 替换规则必须确定性，不调用 LLM
)

/** 候选词条（AI 提取/用户创建的未审核项）；须用户确认后才转正式词条。 */
@Serializable
data class VocabularyCandidate(
    val candidateId: VocabularyCandidateId,
    val vocabularyId: VocabularyId,
    val novelId: NovelId? = null,
    val variantId: VariantId? = null,
    val scopeLevel: VocabularyScopeLevel = VocabularyScopeLevel.NOVEL,
    val suggested: VocabularyEntry,
    val source: VocabularyCandidateSource = VocabularyCandidateSource.AUTO_EXTRACT,
    val status: VocabularyCandidateStatus = VocabularyCandidateStatus.PENDING,
    val createdAt: Instant,
)

@Serializable
enum class VocabularyCandidateSource { AUTO_EXTRACT, USER_CREATED, AI_ASSIST }

@Serializable
enum class VocabularyCandidateStatus { PENDING, APPROVED, REJECTED, MERGED }