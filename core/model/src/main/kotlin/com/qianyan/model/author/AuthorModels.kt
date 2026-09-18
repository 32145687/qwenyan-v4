package com.qianyan.model.author

import com.qianyan.model.AuthorEvidenceId
import com.qianyan.model.AuthorPreferenceId
import com.qianyan.model.AuthorProfileId
import com.qianyan.model.NovelId
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/*
 * P16 AIL-1 · Author Intelligence 第一层 —— Author Preference 领域模型（纯领域，无 storage / provider / agent / UI 依赖）。
 *
 * 语义冻结（P16 AIL-0 Decision Freeze）：
 *  - **Explicit / Inferred 双态**：Explicit = 用户明确表达/确认的作者偏好（天然 Stable）；
 *    Inferred = 系统从 P15 创作信号中推断出的**候选**偏好（Candidate），**未确认不得自动晋升为 Stable**。
 *  - **Candidate → Confidence → User Confirmation → Stable**：Inferred 候选可存在、可衰减（[expiry]）、
 *    可被否定（[rejectPreference]/[expiry]），只有用户确认（[confirmed]=true）后才成为 Stable Author Preference。
 *  - **[revocable]**：候选/学习得来偏好是否可被后续证据撤销；Explicit 确认偏好默认不可撤销。
 *  - **AuthorContext** 是唯一跨层输出（P16 AIL-0 DEC-006/007）：只暴露稳定且激活的偏好，不暴露原始 Evidence / Repository。
 *  - 独立 Author Domain，禁止写入 StoryFoundation / StoryState / NarrativeState / NovelKnowledge / ChapterContextPack。
 */

/** Author Preference 作用域：Global（跨作品长期倾向）或 Novel（本书覆盖）。 */
@Serializable
enum class PreferenceScope { GLOBAL, NOVEL }

/** Preference 来源：Explicit（用户表达/确认）或 Inferred（系统从证据推断）。 */
@Serializable
enum class PreferenceOrigin { EXPLICIT, INFERRED }

/**
 * Author Preference 维度（AIL-1 最小受控目录；未来 P17+ 可扩展）。
 * 描述"作者是什么样的创作者 / 作者倾向什么"（Author Intelligence），区别于 Story Intelligence。
 */
@Serializable
enum class PreferenceDimension {
    PACING, TONE, ROMANCE, CONFLICT, VOICE, STRUCTURE, STORY_DIRECTION, UNSPECIFIED,
}

/** 置信度（0.0 ～ 1.0）。Inferred 候选的置信度用于决定是否值得请求用户确认。/ */
@Serializable
data class Confidence(val value: Double) {
    init {
        require(value in 0.0..1.0) { "Confidence 必须在 0.0..1.0，实际 $value" }
    }

    companion object {
        val ZERO = Confidence(0.0)
        val HALF = Confidence(0.5)
        val HIGH = Confidence(0.9)
    }
}

/** P16 AIL-0 DEC-003：Author Evidence 类型（P15 只读信号 → Author 学习证据）。 */
@Serializable
enum class AuthorEvidenceType {
    /** 用户修改了内容/提案。 */
    MODIFY,
    /** 用户采纳/确认了提案（无修改或确认）。 */
    ADOPT,
    /** 用户拒绝了。 */
    REJECT,
    /** 用户要求部分改写/请求修订。 */
    PARTIAL_REWRITE,
    /** 用户先采纳后修订。 */
    ADOPT_THEN_REVISE,
}

/**
 * Author Profile（全局单例作者画像）。
 * 本阶段为最小结构：仅身份展示；偏好全部承载于 [AuthorPreference]。
 */
@Serializable
data class AuthorProfile(
    val profileId: AuthorProfileId,
    val displayName: String,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/**
 * Author Preference（作者偏好，Global 或 Novel-scoped）。
 *
 * 稳定性判定：
 *  - `isStable`  = Explicit 或 用户已确认（confirmed）。
 *  - `isCandidate` = Inferred 且未确认（仅信誉候选，不得作为 Stable 呈现）。
 *  - `isActive`（供投影）= isStable 且未暂停且未过期。
 *
 * AIL-1 最小结构：**不实现复杂 Override 合并规则**；Novel-scoped Preference（[scope]=NOVEL）即"本书覆盖
 * Global 倾向"的最小表达，同一维度下 Novel 覆盖优先（由 [AuthorContextProjection] 在 Application 侧取舍）。
 */
@Serializable
data class AuthorPreference(
    val preferenceId: AuthorPreferenceId,
    val scope: PreferenceScope = PreferenceScope.GLOBAL,
    /** NOVEL scope 时必须为该 Novel；GLOBAL 时忽略。 */
    val novelId: NovelId? = null,
    val dimension: PreferenceDimension = PreferenceDimension.UNSPECIFIED,
    /** 人类可读偏好描述（如"作者倾向长期低信任关系"）。 */
    val statement: String,
    val origin: PreferenceOrigin,
    val confidence: Confidence = Confidence.HALF,
    /** 用户确认 → Inferred 晋升 Stable。 */
    val confirmed: Boolean = false,
    /** 该偏好是否可被后续证据撤销（Inferred 候选默认 true）。 */
    val revocable: Boolean = false,
    /** 用户暂停：投影时隐藏但保留记录。 */
    val paused: Boolean = false,
    val obtainedAt: Instant,
    /** 候选衰减/失效时间；null = 不自动失效（Explicit 或已确认常驻）。 */
    val expiry: Instant? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    val isStable: Boolean get() = origin == PreferenceOrigin.EXPLICIT || confirmed
    val isCandidate: Boolean get() = origin == PreferenceOrigin.INFERRED && !confirmed
}

/** Author Evidence（来自 P15 信号的作者学习证据；只读采集，不写入 P15）。 */
@Serializable
data class AuthorEvidence(
    val evidenceId: AuthorEvidenceId,
    val novelId: NovelId,
    val type: AuthorEvidenceType,
    val detail: String = "",
    /** 证据来源标识（如 `p15:foundation`）。 */
    val source: String,
    val observedAt: Instant,
)

/**
 * P16 AIL-0 DEC-006/007 · AuthorContext —— 唯一跨层只读 **最小投影**。
 *
 * - read-only、controlled、minimal：不等于整个 Author 数据库 / Evidence 原始记录 / JSON dump / prompt / Memory。
 * - 只暴露**当前创作真正需要**的、**稳定且激活（isActive）** 的 Author Preference。
 * - 可进入 PlanningContext / WritingContext（经 Application 组装，不绑定 LLM Provider）。
 */
@Serializable
data class AuthorContext(
    val preferences: List<AuthorPreferenceLite> = emptyList(),
) {
    val isEmpty: Boolean get() = preferences.isEmpty()

    /** 供 Planner / Writer 消费的最小偏好投影（不携带原始 Evidence / Repository / 未确认候选）。 */
    @Serializable
    data class AuthorPreferenceLite(
        val preferenceId: AuthorPreferenceId,
        val scope: PreferenceScope,
        val novelId: NovelId? = null,
        val dimension: PreferenceDimension,
        val statement: String,
        val confidence: Confidence,
        val revocable: Boolean,
    )
}