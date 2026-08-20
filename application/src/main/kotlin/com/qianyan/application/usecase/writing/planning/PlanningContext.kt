package com.qianyan.application.usecase.writing.planning

import com.qianyan.model.CharacterId
import com.qianyan.model.NovelId
import com.qianyan.model.VariantId
import com.qianyan.model.VariantScope
import com.qianyan.model.context.UserWritingRequest
import kotlinx.serialization.Serializable

/**
 * 最小写作规划上下文（P11.2）。
 *
 * 聚合 Planning 真正需要的信息：用户创作要求（[UserWritingRequest]）+
 * 必要的小说 / Variant 背景 + 可选的 Character / Memory / Vocabulary 既有信息。
 * 只收集 Planner 真正需要的信息，不一次性把整个小说世界塞给 LLM。
 *
 * 不新增第二套上下文模型：直接复用已有领域模型（Novel / NovelVariant / Character /
 * MemoryEntry / VocabularyEntry），本类只做**最小投影**（字符串化），供 Planner Agent 渲染。
 *
 * 作用域语义：scope=ORIGINAL 且 variantId=null 表示 Original；否则为 Variant（与领域一致）。
 */
@Serializable
data class PlanningContext(
    val request: UserWritingRequest,
    val novelId: NovelId,
    val variantId: VariantId? = null,
    val scope: VariantScope = if (variantId == null) VariantScope.ORIGINAL else VariantScope.VARIANT,
    val novelTitle: String = "",
    val novelGenre: List<String> = emptyList(),
    val novelSynopsis: String = "",
    val variantName: String = "",
    val variantDirective: String = "",
    /** 与本次规划相关的既有 Character 最小投影（name + personality + goals）。 */
    val characters: List<CharacterLite> = emptyList(),
    /** 与本次规划相关的既有 Memory 最小投影（content）。 */
    val memories: List<String> = emptyList(),
    /** 与本次规划相关的既有 Vocabulary 最小投影（canonical + aliases + replacement）。 */
    val vocabulary: List<VocabularyLite> = emptyList(),
) {
    val isOriginal: Boolean get() = scope == VariantScope.ORIGINAL
}

/** Character 最小投影（P11.2：仅收集 Planner 需要的字段）。 */
@Serializable
data class CharacterLite(
    val characterId: CharacterId,
    val name: String,
    val personality: List<String> = emptyList(),
    val goals: List<String> = emptyList(),
)

/** Vocabulary 最小投影（P11.2：仅收集 Planner 需要的字段）。 */
@Serializable
data class VocabularyLite(
    val canonical: String,
    val aliases: List<String> = emptyList(),
    val replacement: String? = null,
)