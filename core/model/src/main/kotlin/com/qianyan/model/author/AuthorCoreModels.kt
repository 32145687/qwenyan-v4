package com.qianyan.model.author

import com.qianyan.model.AuthorCoreCandidateId
import com.qianyan.model.AuthorCoreEvidenceLinkId
import com.qianyan.model.AuthorCoreId
import com.qianyan.model.AuthorCorePatternId
import com.qianyan.model.AuthorEvidenceId
import com.qianyan.model.NovelId
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/*
 * P17 Author Core · 作者核心智能 —— 长期创作决策倾向领域模型（纯领域，无 storage/provider/agent/UI 依赖）。
 *
 * 语义冻结（DEC-P17-001 ~ 016）：
 *  - **AuthorCore** = 长期、稳定、经用户确认的"作者创作决策倾向集合"（如何做决策），**不是** Memory / Prompt /
 *    AuthorPreference 的别名（DEC-001）。
 *  - **AuthorCore 与 AuthorPreference 分离**（DEC-002）：Preference=偏好；Core=长期决策模式。P16 AuthorPreference 不改。
 *  - **AuthorCorePattern** 独立结构化，`patternKey` 为稳定字符串键，**不**用固定 enum 表达具体创作习惯（DEC-003）。
 *  - **AuthorCoreCandidate** 与 Preference Candidate 分离，承载长期决策模式候选（DEC-004）。
 *  - Agency 永远不改 Story（DEC-007）；NovelId 仅作 scope 键。
 *  - 三层 Scope：Global / Novel / Context，优先级 Context > Novel > Global（DEC-008）。
 *  - EvidenceLink 保留"Core 因何形成"（DEC-010）。
 */

/** AuthorCore 作用域：Global 长期跨作品；Novel 本书条件性长期；Context 当前章节/阶段临时。 */
@Serializable
enum class AuthorCoreScope { GLOBAL, NOVEL, CONTEXT }

/** AuthorCore / CoreCandidate 生命周期状态（DEC-009）。 */
@Serializable
enum class AuthorCoreStatus {
    CANDIDATE, STABLE, SUPERSEDED, REVOKED,
}

/**
 * AuthorCore —— 长期、稳定、经用户确认的创作决策倾向（生命周期/作用域包装）。
 *
 * 职责（DEC-001/003/009）：承载 版本 / 作用域 / 生命周期 / 确认 / 置信度 / 撤销 / supersede 链。
 * 内容性 Pattern（statement/condition/direction/value/evidenceRefs）由 [AuthorCorePattern] 承载，经 `corePatternKey` 关联。
 * 不是一个大 JSON；不直接写 Story。
 */
@Serializable
data class AuthorCore(
    val coreId: AuthorCoreId,
    val version: Long = 1,
    val scope: AuthorCoreScope,
    val novelId: NovelId? = null,
    val status: AuthorCoreStatus,
    val confirmed: Boolean = false,
    val confidence: Confidence,
    /** 稳定字符串键：本 Core 所实现的长期决策模式（关联 [AuthorCorePattern.patternKey]）。 */
    val corePatternKey: String,
    /** 本 Core 当前实现的具体 Pattern revision（M-1：版本历史可追溯；SUPERSEDED 旧 Core 保留其原 patternId 内容）。 */
    val patternId: AuthorCorePatternId? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
    val supersededBy: AuthorCoreId? = null,
    val revokedAt: Instant? = null,
)

/**
 * AuthorCorePattern —— 内容性长期决策模式（DEC-003）。
 *
 * `patternKey` 为稳定字符串键（dimension×rule 组织），不把具体创作例子做成 enum / hardcode。
 * 支持条件性 Pattern（[condition]）与 Global / Novel scope。
 */
@Serializable
data class AuthorCorePattern(
    val patternId: AuthorCorePatternId,
    val patternKey: String,
    val statement: String,
    val condition: String? = null,
    /** 倾向方向/取值（如"延迟揭示关键线索"）；自由文本，不枚举特定习惯。 */
    val direction: String = "",
    val scope: AuthorCoreScope,
    val novelId: NovelId? = null,
    val version: Long = 1,
    val confidence: Confidence = Confidence.HALF,
    val status: AuthorCoreStatus = AuthorCoreStatus.CANDIDATE,
    /** 形成该模式的关键 Evidence 引用（仅 id，不复制内容；权威链见 [AuthorCoreEvidenceLink]）。 */
    val evidenceRefs: List<AuthorEvidenceId> = emptyList(),
    val createdAt: Instant,
    val updatedAt: Instant,
)

/**
 * AuthorCoreCandidate —— 从多次创作行为聚合形成的长期决策模式候选（DEC-004/005/006）。
 *
 * 保守聚合字段：positive/negative evidence 计数、observationCount、weightedScore、consistency、
 * recency、contradictionCount、confidence。**禁止** `count * 0.1` 式简单计数。
 * 只有满足 min-observation + 用户确认后才晋升为长期 AuthorCore。
 */
@Serializable
data class AuthorCoreCandidate(
    val candidateId: AuthorCoreCandidateId,
    val patternKey: String,
    val scope: AuthorCoreScope,
    val novelId: NovelId? = null,
    val statement: String = "",
    val condition: String? = null,
    val positiveEvidence: Int = 0,
    val negativeEvidence: Int = 0,
    val observationCount: Int = 0,
    val weightedScore: Double = 0.0,
    val consistency: Double = 1.0,
    /** 最近一次观测时间（用于 recency 衰减）。 */
    val recency: Instant,
    val contradictionCount: Int = 0,
    val confidence: Confidence = Confidence.HALF,
    val status: AuthorCoreStatus = AuthorCoreStatus.CANDIDATE,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/**
 * AuthorCoreEvidenceLink —— "Core 因何形成"的引用（DEC-010）。
 * 只保存引用（patternKey + evidenceId），不复制完整 Evidence 内容；不泄漏到 AuthorContext。
 * `UNIQUE(corePatternKey, evidenceId)` 保证同一 Evidence 只入链一次（幂等）。
 */
@Serializable
data class AuthorCoreEvidenceLink(
    val linkId: AuthorCoreEvidenceLinkId,
    val corePatternKey: String,
    val evidenceId: AuthorEvidenceId,
    /** P18-B：证据来源/归属 Novel（dec-id 供 Global 多书门槛），可空。 */
    val provenanceNovelId: NovelId? = null,
    val createdAt: Instant,
)