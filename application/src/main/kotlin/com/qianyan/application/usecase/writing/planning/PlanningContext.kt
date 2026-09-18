package com.qianyan.application.usecase.writing.planning

import com.qianyan.model.CharacterId
import com.qianyan.model.GenreId
import com.qianyan.model.NovelId
import com.qianyan.model.VariantId
import com.qianyan.model.VariantScope
import com.qianyan.model.author.AuthorContext
import com.qianyan.model.context.StoryWorldContext
import com.qianyan.model.context.UserWritingRequest
import com.qianyan.model.foundation.NarrativeProfile
import com.qianyan.model.foundation.StoryDirection
import com.qianyan.model.foundation.WritingPolicy
import com.qianyan.model.story.Chapter
import com.qianyan.model.story.ContinuationReference
import com.qianyan.model.writing.Draft
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
    /** 与本次规划相关的既有 Memory 最小投影（canon 优先的确定性顺序，P11.6）。 */
    val memories: List<String> = emptyList(),
    /** 与本次规划相关的既有 Vocabulary 最小投影（canonical + aliases + replacement）。 */
    val vocabulary: List<VocabularyLite> = emptyList(),
    /** 确定性组装的故事世界上下文视图（canon/layer 分层，P11.6；供渲染与检索，非新持久化）。 */
    val worldContext: StoryWorldContext? = null,
    // ---- P12.1.3：Explicit ContinuationReference ----
    /** 本次 Planning 的显式续篇来源引用（Chapter1 → null；Chapter2+ → 非 null）。只指向，不携带正文。 */
    val continuationReference: ContinuationReference? = null,
    /** 已解析的续篇来源 Chapter（经 reference 在 Assembly/Resolver 确定性解析，供渲染；非新持久化）。 */
    val sourceChapter: Chapter? = null,
    /** 已解析的续篇来源最终 Draft（reference-only 运行时读取；非新持久化，不落正文）。 */
    val sourceFinalDraft: Draft? = null,
    // ---- P14-F.4：已确认的 Story Foundation（Original-only；只读投影） ----
    /** 用户已确认的 [com.qianyan.model.foundation.StoryFoundation] 最小只读投影；未确认时 null（可选输入，不影响旧流程）。 */
    val foundation: ConfirmedStoryFoundationContext? = null,
    // ---- P16 AIL-1：AuthorContext（最小只读投影；Planner/Writer 唯一 Author 入口） ----
    /** 当前创作的作者偏好最小只读投影（只含稳定且激活偏好，不含未确认 Candidate / 原始 Evidence）；无则 null。 */
    val authorContext: AuthorContext? = null,
) {
    val isOriginal: Boolean get() = scope == VariantScope.ORIGINAL
}

/**
 * P14-F.4 · 已确认 Story Foundation 的只读投影（供 Planner / Writer Agent 消费）。
 *
 * 只携带当前真正需要的信息（confirmedGenre / direction / audience / policy），
 * 不直接暴露 domain [com.qianyan.model.foundation.StoryFoundation]；
 * 不加 P15/P16/P17 未来字段；不进入 Story State / ChapterContextPack。
 */
@Serializable
data class ConfirmedStoryFoundationContext(
    val confirmedGenre: List<GenreId> = emptyList(),
    val direction: StoryDirection = StoryDirection(),
    val audience: NarrativeProfile = NarrativeProfile(),
    val writingPolicy: WritingPolicy = WritingPolicy(),
)

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