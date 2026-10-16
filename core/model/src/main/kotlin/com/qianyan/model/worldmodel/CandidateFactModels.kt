package com.qianyan.model.worldmodel

import com.qianyan.model.ChapterId
import com.qianyan.model.NovelId
import com.qianyan.model.VariantId
import com.qianyan.model.VariantScope
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/**
 * Phase 3 · 候选事实生命周期（v3.1 §12.8，冻结契约）。
 *
 *  - [PENDING]：已抽取、待裁决（事务内的中间态；崩溃回滚不留痕）；
 *  - [ACCEPTED]：已通过确定性校验且无冲突，**已写入** World Model；
 *  - [REJECTED]：被确定性校验拒绝（记录理由，不写入）；
 *  - [CONFLICT]：与现有 World Model 事实冲突（**不得覆盖**，留待人审）。
 */
@Serializable
enum class CandidateFactStatus { PENDING, ACCEPTED, REJECTED, CONFLICT }

/** 候选事实类别（§12 抽取范围；当前实现边界 = [EVENT]，扩展属后续裁决）。 */
@Serializable
enum class CandidateFactKind { EVENT }

/** 候选操作（与既有 KnowledgeOperation 同语义；当前仅 [ADD] 有持久化路径）。 */
@Serializable
enum class CandidateFactOperation { ADD, UPDATE, REMOVE }

/**
 * 一条候选事实：Commit 之后由 Update Pipeline 抽取，**不是** World Model 事实本身。
 *
 * 来源引用是写入的必要条件（§12.8）：[historyId] / [commitId] / [sourceExcerpt] 三元组把每条候选
 * 锚定到具体的 Commit 与正文片段；[resultingFactId] 在 ACCEPTED 后反向指向落地的 World Model 行。
 */
@Serializable
data class CandidateFact(
    val candidateId: String,
    /** 来源 CommitHistory.history_id。 */
    val historyId: String,
    /** 来源 CommitHistory.commit_id。 */
    val commitId: String,
    val novelId: NovelId,
    val variantId: VariantId? = null,
    val scope: VariantScope = if (variantId == null) VariantScope.ORIGINAL else VariantScope.VARIANT,
    /** 来源章节（Commit target）。 */
    val chapterId: ChapterId? = null,
    val kind: CandidateFactKind,
    val operation: CandidateFactOperation,
    /** 身份键（EVENT = 事件名；冲突判定按 novel + variant + scope + name）。 */
    val factName: String,
    /** 候选事实内容摘要。 */
    val summary: String,
    /** 来源正文片段（必须包含于提交后正文 —— 确定性校验，不信任 LLM 引用）。 */
    val sourceExcerpt: String,
    /** 提取依据（LLM 给出，仅记录）。 */
    val reason: String = "",
    val status: CandidateFactStatus,
    /** 裁决说明（拒绝原因 / 冲突说明）。 */
    val decisionReason: String? = null,
    /** 冲突对象引用（现有事实的 PK；无冲突 → null）。 */
    val conflictWith: String? = null,
    /** ACCEPTED 落地产物引用（如 Event.event_id；其余 → null）。 */
    val resultingFactId: String? = null,
    val createdAt: Instant,
)
