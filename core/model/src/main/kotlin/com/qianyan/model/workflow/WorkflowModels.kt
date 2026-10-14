package com.qianyan.model.workflow

import com.qianyan.model.ChapterId
import com.qianyan.model.DraftId
import com.qianyan.model.NovelId
import com.qianyan.model.VariantId
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@JvmInline
@Serializable
value class WorkflowId(val value: String)

@JvmInline
@Serializable
value class WorkflowStepId(val value: String)

@JvmInline
@Serializable
value class WorkflowStepAttemptId(val value: String)

@JvmInline
@Serializable
value class WorkflowHumanGateId(val value: String)

@JvmInline
@Serializable
value class WorkflowContinuationId(val value: String)

/** Workflow 生命周期（只表达流程在哪个宏观阶段，不表达业务步骤）。 */
@Serializable
enum class WorkflowStatus { CREATED, RUNNING, PAUSED, WAITING_HUMAN, FAILED, CANCELLED, COMPLETED }

/** Workflow 类型。 */
@Serializable
enum class WorkflowKind { WRITE_NOVEL, REWRITE_CHAPTER, CONTINUE }

/** 业务步骤 Phase（Workflow.currentStepId → Step.phase）。 */
@Serializable
enum class WorkflowStepPhase {
    PLANNING, WRITING, CRITIQUE, REVISION, FINALIZE, CONFIRMATION, KNOWLEDGE_UPDATE, CONTINUATION,
}

@Serializable
enum class WorkflowStepStatus { PENDING, RUNNING, COMPLETED, FAILED, SKIPPED }

@Serializable
enum class WorkflowAttemptStatus { RUNNING, COMPLETED, FAILED, ABANDONED }

/** Attempt 失败类别：决定 Retry / Human / Terminal。 */
@Serializable
enum class AttemptErrorCategory { RETRYABLE, NON_RETRYABLE, NEEDS_HUMAN, TERMINAL }

@Serializable
enum class HumanGateStatus { PENDING, RESOLVED }

@Serializable
enum class HumanDecision { PENDING, APPROVED, REJECTED, REQUEST_REVISION }

/**
 * 创作流程实例（Durable Workflow 唯一流程权威）。
 * noveltyId + variantId 仅为 **Scope**，不是身份；同一 Novel/Variant 可存在多个 Workflow。
 */
@Serializable
data class Workflow(
    val workflowId: WorkflowId,
    val novelId: NovelId,
    val variantId: VariantId? = null,
    val kind: WorkflowKind,
    val definitionVersion: String = "P12.2-v1",
    val status: WorkflowStatus,
    val activeChapterId: ChapterId? = null,
    val currentStepId: WorkflowStepId? = null,
    val pendingGateId: WorkflowHumanGateId? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/** 一个逻辑业务步骤；logicalStepKey = workflowId:chapterId:phase（稳定，不含 attempt）。 */
@Serializable
data class WorkflowStep(
    val stepId: WorkflowStepId,
    val workflowId: WorkflowId,
    val chapterId: ChapterId,
    val phase: WorkflowStepPhase,
    val logicalStepKey: String,
    val status: WorkflowStepStatus,
    val currentAttemptNo: Int? = null,
    val currentTaskId: String? = null,
    /** 业务结果引用（draftId / continuationId / taskId）；非正文。null=尚未完成消费。 */
    val resultReference: String? = null,
    /**
     * 创作轮次编号（P1-02j.2）。`null` = 兼容历史记录，按首轮 `0` 解释；`0` = 首轮；`>0` = 后续创作轮次。
     *
     * 与 `attemptNo`（重试）、REVISION（修订次数）、`logicalStepKey`（逻辑身份）**四者互相独立**。
     * 仅"实际执行新一轮 WRITING"的操作（[com.qianyan.application.usecase.workflow.WorkflowOrchestrator.abandonRound]）
     * 才推进该编号；重新打开同一轮 Gate（reopen）**不**改变创作轮次。
     */
    val roundNo: Int? = null,
    val createdAt: Instant,
    val completedAt: Instant? = null,
)

/** 同一逻辑步骤的执行尝试；attemptNo=retry 次数（≠ revisionCount）。 */
@Serializable
data class WorkflowStepAttempt(
    val attemptId: WorkflowStepAttemptId,
    val stepId: WorkflowStepId,
    val attemptNo: Int,
    val taskId: String? = null,
    val status: WorkflowAttemptStatus,
    val errorCategory: AttemptErrorCategory? = null,
    val errorMessage: String? = null,
    val startedAt: Instant,
    val completedAt: Instant? = null,
)

/** HITL 决策独立持久化；gateKey 唯一且绑定 draftId。 */
@Serializable
data class WorkflowHumanGate(
    val gateId: WorkflowHumanGateId,
    /**
     * 所属 Workflow；`null` = **独立 Human Gate**（不挂在任何 Workflow 上）。
     *
     * P1-01：用户在 Writer 编辑器保存一条已 Commit 的 Canonical Draft 时，"保存"本身就是人类决策，
     * 此时不存在（也不应伪造）Workflow —— Gate 以 `workflowId=null` 独立存在，仅承载批准/审计语义，
     * 不参与 `WorkflowStatus.WAITING_HUMAN` / `pendingGateId` 等 Workflow 状态流转。
     */
    val workflowId: WorkflowId?,
    val stepId: WorkflowStepId? = null,
    val draftId: DraftId? = null,
    val gateKey: String,
    val status: HumanGateStatus,
    val decision: HumanDecision,
    val createdAt: Instant,
    val resolvedAt: Instant? = null,
    val resolvedBy: String? = null,
)

/** Chapter→Chapter 业务关系（独立表）；(sourceDraftId, targetChapterId) 唯一。 */
@Serializable
data class WorkflowContinuation(
    val continuationId: WorkflowContinuationId,
    val workflowId: WorkflowId,
    val sourceDraftId: DraftId,
    val sourceChapterId: ChapterId? = null,
    val targetChapterId: ChapterId,
    val createdAt: Instant,
)