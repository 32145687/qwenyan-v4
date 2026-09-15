package com.qianyan.model.foundation

import com.qianyan.model.BaseNovelId
import com.qianyan.model.GenreId
import com.qianyan.model.NovelId
import com.qianyan.model.VariantId
import com.qianyan.model.VariantScope
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/*
 * P14-F.1 · StoryFoundation 领域模型（纯领域，无 storage / provider / agent / UI 依赖）。
 *
 * 语义冻结（P14-F 架构审计）：
 *  - StoryFoundation = 用户**确认后**的、本书级故事基础。**不进入 Story State / NarrativeState**，
 *    不使用 EntityOverride、不新增 OverridableKind、不属于 NovelVariant、不读取 ProjectManifest.genre、
 *    不替代 / 不修改现有 Novel.genre。
 *  - FoundationOverride = 某 Variant 相对 Original StoryFoundation 的**差异**；
 *    仅保存差异、不复制完整 Foundation；null 字段 = 继承 Original，非 null = 该字段被 Variant 覆盖。
 *  - WritingPolicy = 本书 StoryFoundation 层规则；AuthorPreference / AuthorDNA 属未来 P16–P19，本模型不承载。
 */

/** 故事方向（MVP 最小值对象）。 */
@Serializable
data class StoryDirection(
    val theme: String = "",
    val conflict: String = "",
    val promise: String = "",
    val storyType: String = "",
)

/** 读者 / 叙事画像（MVP 最小值对象：POV + 读者基调）。 */
@Serializable
data class NarrativeProfile(
    val pov: String = "",
    val readerTone: String = "",
)

/** 本书写作规则（StoryFoundation 层；区别于未来 Author 长期偏好）。 */
@Serializable
data class WritingPolicy(
    val rules: List<String> = emptyList(),
)

/**
 * 用户确认后的本书级 Story Foundation（Novel-level；Original 真源）。
 * `genre` 复用 P14-A 的 [GenreId]（不从 Novel.genre / ProjectManifest 读取）。
 */
@Serializable
data class StoryFoundation(
    val novelId: NovelId,
    val baseNovelId: BaseNovelId,
    val scope: VariantScope = VariantScope.ORIGINAL,
    val version: Long = 0L,
    val genre: List<GenreId> = emptyList(),
    val direction: StoryDirection = StoryDirection(),
    val audience: NarrativeProfile = NarrativeProfile(),
    val policy: WritingPolicy = WritingPolicy(),
    val createdAt: Instant,
    val updatedAt: Instant,
)

/**
 * Variant 对 Original StoryFoundation 的差异覆盖（独立于 P13 六类 EntityOverride）。
 * null = 继承 Original；非 null = 该字段被本 Variant 覆盖。
 */
@Serializable
data class FoundationOverride(
    val variantId: VariantId,
    val genre: List<GenreId>? = null,
    val direction: StoryDirection? = null,
    val audience: NarrativeProfile? = null,
    val policy: WritingPolicy? = null,
    val updatedAt: Instant,
)