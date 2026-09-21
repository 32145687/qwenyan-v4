package com.qianyan.model.author

import com.qianyan.model.AuthorDnaFeatureId
import com.qianyan.model.AuthorDnaSourceId
import com.qianyan.model.AuthorDnaVersionId
import com.qianyan.model.AuthorProfileId
import com.qianyan.model.NovelId
import com.qianyan.model.TxtDocumentId
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/*
 * P18-C Author DNA · 作者风格指纹领域模型（纯领域，无 storage / provider / agent / UI 依赖）。
 *
 * 冻结语义（DEC-P18C-001 ~ 015）：
 *  - **DNA ≠ Preference ≠ Core ≠ Evidence ≠ Story ≠ NovelKnowledge ≠ DecisionModel**（DEC-007/008）。
 *  - DNA = 从**作品文本分析**得到的作者长期写作风格指纹（语言习惯 / 叙事模式），**不是**用户行为学习系统
 *    (Observation→Evidence→Core)。两条路径独立；禁止 `TXT → Evidence → CoreCandidate`、`DNA → CoreCandidate`。
 *  - **scope = GLOBAL**（跨作品作者风格）；identity = `authorId`（≠ novelId）。`sourceNovelId` 仅作**来源追踪**
 *    (provenance)，不是 DNA scope（DEC-P18C-006）。
 *  - **分析产物**：TXT → Analysis → AuthorDna。LLM 只产出 transient 候选（[DnaFeatureCandidate]），
 *    程序负责结构校验 / dimension 校验 / confidence 校正 / sourceLocation binding / authorId binding / 版本生成 / 落库。
 *    **绝不信任 LLM 提供的 sourceLocation**（DEC-P18C-010）。
 *  - **版本化**：Full Rebuild 每次产生新 version；旧 version 保留可追溯；失败不产生 ACTIVE DNA；
 *    无来源 → EMPTY/NO_ACTIVE_DNA（DEC-P18C-002/004/007）。
 *  - **Status 生命周期**：版本 [AuthorDnaStatus] EMPTY/ACTIVE/SUPERSEDED；Feature [AuthorDnaFeatureStatus]
 *    CANDIDATE → ACTIVE(Confirm) / REJECTED(Reject)；Reject 仅属当前版本，下次 Rebuild 重进 CANDIDATE（DEC-P18C-013）。
 */

/** DNA 特征维度（DEC-P18C-009 受控固定集合，第一版 8 类；featureKey 自由扩展，value 非受控）。 */
@Serializable
enum class AuthorDnaDimension {
    SENTENCE,
    PARAGRAPH,
    DIALOGUE,
    NARRATIVE,
    POV,
    DESCRIPTION,
    EMOTION,
    ACTION,
}

/** 一次 DNA Full Rebuild 指纹快照的版本状态。 */
@Serializable
enum class AuthorDnaVersionStatus {
    /** 无有效来源 / 空指纹。 */
    EMPTY,

    /** 当前生效指纹。 */
    ACTIVE,

    /** 被后续 Rebuild 取代的旧版本（保留可追溯）。 */
    SUPERSEDED,
}

/** DNA Feature 生命周期（DEC-P18C-013）：CANDIDATE → ACTIVE(Confirm) / REJECTED(Reject)。 */
@Serializable
enum class AuthorDnaFeatureStatus {
    CANDIDATE,
    ACTIVE,
    REJECTED,
}

/** 单条 Feature 可追溯引用（sourceId + sourceLocation；程序绑定，非 LLM）。 */
@Serializable
data class AuthorDnaSourceRef(
    val sourceId: TxtDocumentId,
    val sourceNovelId: NovelId? = null,
    /** 规范化文本中的源偏移区间（由确定性 AnalysisInput 程序绑定）。 */
    val sourceStart: Int,
    val sourceEnd: Int,
)

/**
 * Author DNA 来源（一份参与分析的 TXT）。
 * 一份来源可被多个 version 引用；删除来源只从关联中移除，不物理改 TXT。
 */
@Serializable
data class AuthorDnaSource(
    val sourceId: AuthorDnaSourceId,
    val authorId: AuthorProfileId,
    val txtDocumentId: TxtDocumentId,
    /** 来源追踪（provenance），非 DNA scope。 */
    val sourceNovelId: NovelId? = null,
    val contentHash: String = "",
    val createdAt: Instant,
    val updatedAt: Instant,
)

/** 一次 DNA Full Rebuild 的版本（身份证 + 关联来源清单）。Feature 另行持久化。 */
@Serializable
data class AuthorDnaVersion(
    val versionId: AuthorDnaVersionId,
    val authorId: AuthorProfileId,
    val version: Long = 1,
    val status: AuthorDnaVersionStatus = AuthorDnaVersionStatus.ACTIVE,
    /** 分析规则版本（确定性可复现）。 */
    val ruleVersion: String,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/** DNA Feature（作者风格指纹的最小结构化单元）。 */
@Serializable
data class AuthorDnaFeature(
    val featureId: AuthorDnaFeatureId,
    val versionId: AuthorDnaVersionId,
    val authorId: AuthorProfileId,
    val dimension: AuthorDnaDimension,
    val featureKey: String,
    val value: String,
    val statement: String,
    val confidence: Confidence = Confidence.HALF,
    val status: AuthorDnaFeatureStatus = AuthorDnaFeatureStatus.CANDIDATE,
    /** 程序绑定的来源引用（sourceLocation 由程序从 AnalysisInput 绑定，非 LLM）。 */
    val sourceRefs: List<AuthorDnaSourceRef> = emptyList(),
    val createdAt: Instant,
    val updatedAt: Instant,
)

/** Author DNA 聚合视图：某 author 当前 ACTIVE DNA 版本 + 其 Feature。 */
@Serializable
data class AuthorDna(
    val versionId: AuthorDnaVersionId,
    val authorId: AuthorProfileId,
    val version: Long,
    val status: AuthorDnaVersionStatus,
    val ruleVersion: String,
    val features: List<AuthorDnaFeature>,
    val createdAt: Instant,
)

/*
 * 以下为 **transient**（LLM 分析候选中间结果），不落 Storage。
 * 只承载"LLM 提出的候选特征 + 可选 confidence/evidence 描述"；sourceLocation / authorId / version
 * 由程序在应用层绑定，绝不能由 LLM 伪造。
 */
@Serializable
data class DnaFeatureCandidate(
    val dimension: AuthorDnaDimension,
    val featureKey: String,
    val value: String,
    val statement: String,
    /** LLM 自报 confidence（0..1）；null/非法由程序 fallback。 */
    val confidence: Double? = null,
    /** LLM 提供的可解释性素材（自由文本，仅作文档说明）。 */
    val evidence: String? = null,
)

/** LLM 一次 AuthorDna 分析的 transient 中间产物（不落库）。 */
@Serializable
data class AuthorDnaAnalysis(
    val authorId: AuthorProfileId,
    val sources: List<TxtDocumentId>,
    val features: List<DnaFeatureCandidate>,
)

/**
 * P18-C · AuthorDnaLite —— 供 [AuthorContext] 投影的最小只读视图（DEC-P18C-014）。
 * 只携带 featureKey/dimension/value/statement/confidence/sourceRef；**不携带**原文 / TextBlock /
 * AnalysisResult / LLM raw / DNA history / Storage ID 细节 / internal scoring。
 */
@Serializable
data class AuthorDnaLite(
    val featureKey: String,
    val dimension: AuthorDnaDimension,
    val value: String,
    val statement: String,
    val confidence: Confidence = Confidence.HALF,
    /** 最小 sourceRef（如 "sourceId:sourceLocation"），用于 explainability；非原文。 */
    val sourceRef: String? = null,
)