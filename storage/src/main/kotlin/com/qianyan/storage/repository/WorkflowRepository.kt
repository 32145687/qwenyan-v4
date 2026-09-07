package com.qianyan.storage.repository

import com.qianyan.model.DraftId
import com.qianyan.model.NovelId
import com.qianyan.model.VariantId
import com.qianyan.model.workflow.Workflow
import com.qianyan.model.workflow.WorkflowContinuation
import com.qianyan.model.workflow.WorkflowContinuationId
import com.qianyan.model.workflow.WorkflowHumanGate
import com.qianyan.model.workflow.WorkflowHumanGateId
import com.qianyan.model.workflow.WorkflowId
import com.qianyan.model.workflow.WorkflowStep
import com.qianyan.model.workflow.WorkflowStepAttempt
import com.qianyan.model.workflow.WorkflowStepAttemptId
import com.qianyan.model.workflow.WorkflowStepId

/**
 * Durable Workflow 仓储（P12.2）。管理 Workflow / Step / Attempt / HumanGate / Continuation 五表。
 *
 * 职责：**只读 + 单表写**；跨表原子步骤（业务结果 + resultReference + Step/Attempt 完成在同一事务）
 * 由上层 [com.qianyan.application.usecase.workflow.WorkflowService] 编排（复用外部分配的 [Transaction]）。
 * 隔离：Workflow 按 novelId(+variantId) 为 Scope；查询如需 strict scope 隔离时按 Scope 过滤。
 */
interface WorkflowRepository {

    /* ---- Workflow ---- */
    fun createWorkflow(workflow: Workflow)
    fun getWorkflow(workflowId: WorkflowId): Workflow?
    fun updateWorkflow(workflow: Workflow)

    /** 按 activeChapter 查其归属 Workflow（P12.2 M1-M2：用户层 chapterId → workflow 句柄）。 */
    fun getWorkflowByActiveChapter(activeChapterId: com.qianyan.model.ChapterId): Workflow?

    /* ---- Step ---- */
    fun createStep(step: WorkflowStep)
    fun getStep(stepId: WorkflowStepId): WorkflowStep?
    fun getStepByKey(workflowId: WorkflowId, logicalStepKey: String): WorkflowStep?
    fun listSteps(workflowId: WorkflowId): List<WorkflowStep>
    fun updateStep(step: WorkflowStep)

    /* ---- Attempt ---- */
    fun createAttempt(attempt: WorkflowStepAttempt)
    fun getAttempt(stepId: WorkflowStepId, attemptNo: Int): WorkflowStepAttempt?
    fun listAttempts(stepId: WorkflowStepId): List<WorkflowStepAttempt>
    fun updateAttempt(attempt: WorkflowStepAttempt)

    /* ---- HumanGate ---- */
    fun createGate(gate: WorkflowHumanGate)
    fun getGate(gateId: WorkflowHumanGateId): WorkflowHumanGate?
    fun getGateByKey(gateKey: String): WorkflowHumanGate?
    fun updateGate(gate: WorkflowHumanGate)

    /* ---- Continuation ---- */
    fun createContinuation(continuation: WorkflowContinuation)
    fun getContinuation(continuationId: WorkflowContinuationId): WorkflowContinuation?
    fun getContinuation(sourceDraftId: DraftId, targetChapterId: com.qianyan.model.ChapterId): WorkflowContinuation?

    /** 按 source Draft 查询其唯一的续篇目标（P12.2 Test E：幂等目标解析）。 */
    fun getContinuationBySourceDraft(sourceDraftId: DraftId): WorkflowContinuation?

    /** 按目标 Chapter 查询续篇来源（P12.2 Test E：Ch2 Planning 从 durable continuation 读取 ContinuationReference）。 */
    fun getContinuationByTargetChapter(targetChapterId: com.qianyan.model.ChapterId): WorkflowContinuation?

    /** 在给定事务内执行一次写操作（供跨表原子完成）。 */
    fun inTransaction(block: () -> Unit)
}