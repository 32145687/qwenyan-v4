package com.qianyan.application.usecase.workflow

import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.application.error.ErrorMapper
import com.qianyan.application.usecase.UseCase
import com.qianyan.application.usecase.task.TaskManagerUseCases
import com.qianyan.application.usecase.writing.confirmation.ConfirmationExecutionUseCase
import com.qianyan.application.usecase.writing.knowledgeupdate.KnowledgeUpdateExecutionUseCase
import com.qianyan.model.DraftId
import com.qianyan.model.workflow.HumanDecision
import com.qianyan.model.workflow.HumanGateStatus
import com.qianyan.model.workflow.Workflow
import com.qianyan.model.workflow.WorkflowHumanGate
import com.qianyan.model.workflow.WorkflowId
import com.qianyan.model.workflow.WorkflowStatus
import com.qianyan.model.workflow.WorkflowStep
import com.qianyan.model.workflow.WorkflowStepId
import com.qianyan.model.workflow.WorkflowStepStatus
import com.qianyan.storage.repository.DraftRepository
import com.qianyan.storage.repository.WorkflowRepository
import kotlinx.datetime.Clock

/**
 * Durable Workflow 最小服务（P12.2 · Recovery/HITL 地基）。
 *
 * 只建立两条核心不变量，不做大 Orchestrator：
 *  A. **ResultReference First**：Step.resultReference != null → 已拥有可复用持久化结果；恢复时
 *     验证结果仍存在 → 复用，绝不重调 LLM / 不产生第二个业务结果。
 *  B. **HumanGate 幂等批准**：approve 只允许 PENDING→APPROVED 一次；重复 approve → NO-OP（不再 KU）；
 *     已 REJECTED 后 approve → INVALID_TRANSITION（不改原决定）。
 * 语义：at-least-once 执行 + durable result idempotency（不声称 exactly-once；LLM HTTP 层的
 * crash boundary 无法由本地 SQLite 单独证明恰一次）。
 */
class WorkflowService(
    private val workflowRepository: WorkflowRepository,
    private val draftRepository: DraftRepository,
    private val taskManager: TaskManagerUseCases,
    private val confirmation: ConfirmationExecutionUseCase,
    private val knowledgeUpdate: KnowledgeUpdateExecutionUseCase,
    errorMapper: ErrorMapper,
) : UseCase(errorMapper) {

    /**
     * 单事务完成一个 Step 并固化结果引用（§8 Result Binding）。
     * 同一 SQLite transaction：校验 Workflow / Step / 结果存在 → 写 resultReference → Step COMPLETED → Attempt COMPLETED。
     */
    fun completeStepWithResult(
        workflowId: WorkflowId,
        stepId: WorkflowStepId,
        resultReference: String,
    ): WorkflowStep {
        val wf = requireWorkflow(workflowId)
        val step = requireStep(stepId)
        validateResultReference(resultReference)
        var completed: WorkflowStep? = null
        workflowRepository.inTransaction {
            val stillStep = requireStep(stepId)
            if (stillStep.status == WorkflowStepStatus.COMPLETED) {
                completed = stillStep // 幂等：已 COMPLETED 则原样返回
                return@inTransaction
            }
            val done = stillStep.copy(
                status = WorkflowStepStatus.COMPLETED,
                resultReference = resultReference,
                completedAt = Clock.System.now(),
            )
            workflowRepository.updateStep(done)
            workflowRepository.updateWorkflow(
                wf.copy(currentStepId = stepId, updatedAt = Clock.System.now()),
            )
            completed = done
        }
        return completed!!
    }

    /**
     * 恢复一个 Step 的已知边界：若 currentStep 已带 resultReference 且对应业务结果仍存在 → 复用并标记 COMPLETED；
     * 不调用任何 Agent / LLM，不产生第二个业务结果。（Test B）
     */
    fun resumeWorkflow(workflowId: WorkflowId): ResumeOutcome {
        val wf = requireWorkflow(workflowId)
        if (wf.status == WorkflowStatus.WAITING_HUMAN) {
            // Human 等待：恢复只保留待决 Gate，不推进。
            val gate = wf.pendingGateId?.let { workflowRepository.getGate(it) }
            return ResumeOutcome(status = wf.status, reusedResult = null, stepCompleted = false, pendingGate = gate)
        }
        // 取 currentStep（currentStepId 为空时回退到第一个未完成 Step）
        val step = currentStep(wf)
            ?: return ResumeOutcome(status = wf.status, reusedResult = null, stepCompleted = false)
        val ref = step.resultReference ?: run {
            // 无 resultReference：无法确定是否已消费 → RECOVERY_ERROR（不猜测、不重跑）。
            return ResumeOutcome(status = wf.status, reusedResult = null, stepCompleted = false, recoveryError = "step ${step.stepId.value} 无 resultReference，无法安全恢复")
        }
        validateResultReference(ref) // 不存在 → TERMINAL / RECOVERY_ERROR
        var completed = false
        workflowRepository.inTransaction {
            val s = requireStep(step.stepId)
            if (s.status != WorkflowStepStatus.COMPLETED) {
                workflowRepository.updateStep(
                    s.copy(status = WorkflowStepStatus.COMPLETED, resultReference = ref, completedAt = Clock.System.now()),
                )
                completed = true
            }
        }
        return ResumeOutcome(status = wf.status, reusedResult = ref, stepCompleted = completed)
    }

    /** 幂等批准：PENDING→APPROVED 一次；重复 APPROVE → NO-OP；已 REJECTED 再 APPROVE → INVALID。 */
    fun approveGate(gateId: com.qianyan.model.workflow.WorkflowHumanGateId): GateOutcome {
        val gate = requireGate(gateId)
        return if (gate.status == HumanGateStatus.RESOLVED) {
            when (gate.decision) {
                HumanDecision.APPROVED -> GateOutcome.AlreadyApproved
                HumanDecision.REJECTED, HumanDecision.REQUEST_REVISION -> throw ApplicationException(
                    ApplicationError.InvalidOperation("Gate ${gate.gateId.value} 已 ${gate.decision}，不能 APPROVE"),
                )
                else -> throw ApplicationException(ApplicationError.InvalidOperation("Gate ${gate.gateId.value} 状态异常"))
            }
        } else {
            approvePending(gate)
        }
    }

    private fun approvePending(gate: WorkflowHumanGate): GateOutcome {
        val wf = requireWorkflow(gate.workflowId)
        if (wf.pendingGateId != gate.gateId) {
            throw ApplicationException(ApplicationError.InvalidOperation("Gate ${gate.gateId.value} 不是 Workflow ${wf.workflowId.value} 的 pendingGate"))
        }
        val draftId = gate.draftId ?: throw ApplicationException(
            ApplicationError.InvalidOperation("Gate ${gate.gateId.value} 未绑定 Draft，无法批准"),
        )
        val draft = draftRepository.getById(draftId)
            ?: throw ApplicationException(ApplicationError.EntityNotFound("Gate Draft 不存在: ${draftId.value}"))

        // 先确认（幂等；Confirmation UseCase），使 KU 门禁可通过
        confirmation.confirmFinalDraft(draft.draftId, wf.novelId, wf.variantId)

        // DB 事务内完成 PENDING→APPROVED 的 transition（唯一一次）；竞态下仅一个调用方 resolve。
        val resolved = resolveGatePending(gate)
        if (resolved) {
            // 仅 resolve 成功者执行 Knowledge Update（同一 Gate+Draft 只一次 KU）。
            val confirmed = draftRepository.getById(draftId)
                ?: throw ApplicationException(ApplicationError.EntityNotFound("Confirmed Draft 消失: ${draftId.value}"))
            knowledgeUpdate.execute(taskManager.create(com.qianyan.model.task.TaskType.KNOWLEDGE_UPDATE), confirmed)
        }
        return GateOutcome.Approved
    }

    /** Reject / RequestRevision：PENDING→RESOLVED(REJECTED|REQUEST_REVISION)；幂等。 */
    fun rejectGate(gateId: com.qianyan.model.workflow.WorkflowHumanGateId, decision: HumanDecision, by: String = "user") {
        val gate = requireGate(gateId)
        if (gate.status == HumanGateStatus.RESOLVED) return // 幂等
        if (decision != HumanDecision.REJECTED && decision != HumanDecision.REQUEST_REVISION) {
            throw ApplicationException(ApplicationError.InvalidOperation("reject 仅接受 REJECTED/REQUEST_REVISION"))
        }
        workflowRepository.inTransaction {
            val g = requireGate(gate.gateId)
            if (g.status != HumanGateStatus.RESOLVED) {
                workflowRepository.updateGate(
                    g.copy(status = HumanGateStatus.RESOLVED, decision = decision,
                        resolvedAt = Clock.System.now(), resolvedBy = by),
                )
            }
        }
    }

    fun createPendingGate(workflowId: WorkflowId, stepId: WorkflowStepId, draftId: DraftId): WorkflowHumanGate {
        val existing = workflowRepository.getGateByKey("${workflowId.value}:${stepId.value}:${draftId.value}")
        if (existing != null) return existing
        val now = Clock.System.now()
        val gate = WorkflowHumanGate(
            gateId = com.qianyan.model.workflow.WorkflowHumanGateId(java.util.UUID.randomUUID().toString()),
            workflowId = workflowId, stepId = stepId, draftId = draftId,
            gateKey = "${workflowId.value}:${stepId.value}:${draftId.value}",
            status = HumanGateStatus.PENDING, decision = HumanDecision.PENDING, createdAt = now,
        )
        workflowRepository.createGate(gate)
        val wf = requireWorkflow(workflowId)
        workflowRepository.updateWorkflow(wf.copy(pendingGateId = gate.gateId, updatedAt = now))
        return gate
    }

    /* ---------- helpers ---------- */

    /** 只有 PENDING 且未被并发放批准时才 resolve；返回是否由本次调用完成 transition。 */
    private fun resolveGatePending(gate: WorkflowHumanGate): Boolean {
        var resolved = false
        workflowRepository.inTransaction {
            val g = requireGate(gate.gateId)
            if (g.status == HumanGateStatus.PENDING) {
                workflowRepository.updateGate(
                    g.copy(status = HumanGateStatus.RESOLVED, decision = HumanDecision.APPROVED,
                        resolvedAt = Clock.System.now(), resolvedBy = "user"),
                )
                resolved = true
            }
        }
        return resolved
    }

    /** resultReference 必须能对应到真实持久化业务结果（本阶段按 DraftId 解读）。 */
    private fun validateResultReference(ref: String) {
        val draftId = ref.removePrefix("PLAN:").removePrefix("KU:").removePrefix("CONT:")
        // 按 DraftId 解读（WRITING 结果为 DraftId）；若读取失败 → 不能假设完成。
        val d = draftRepository.getById(DraftId(draftId))
        if (d == null) {
            // 不允许"仅 resultReference 存在但结果消失"被当作完成。
            throw ApplicationException(
                ApplicationError.InvalidOperation("resultReference=$ref 对应的业务结果不存在，不能标记完成（RECOVERY_ERROR）"),
            )
        }
    }

    private fun requireWorkflow(id: WorkflowId): Workflow =
        workflowRepository.getWorkflow(id)
            ?: throw ApplicationException(ApplicationError.EntityNotFound("Workflow 不存在: ${id.value}"))

    /** currentStepId 为空时回退到第一个未完成 Step。 */
    private fun currentStep(wf: Workflow): WorkflowStep? {
        wf.currentStepId?.let { return workflowRepository.getStep(it) }
        return workflowRepository.listSteps(wf.workflowId)
            .firstOrNull { it.status != WorkflowStepStatus.COMPLETED && it.status != WorkflowStepStatus.SKIPPED }
    }

    private fun requireStep(id: WorkflowStepId): WorkflowStep =
        workflowRepository.getStep(id)
            ?: throw ApplicationException(ApplicationError.EntityNotFound("Step 不存在: ${id.value}"))

    private fun requireGate(id: com.qianyan.model.workflow.WorkflowHumanGateId): WorkflowHumanGate =
        workflowRepository.getGate(id)
            ?: throw ApplicationException(ApplicationError.EntityNotFound("Gate 不存在: ${id.value}"))
}

/** 恢复结果。 */
data class ResumeOutcome(
    val status: WorkflowStatus,
    val reusedResult: String?,
    val stepCompleted: Boolean,
    val recoveryError: String? = null,
    val pendingGate: WorkflowHumanGate? = null,
)

/** Gate 批准结果。 */
sealed interface GateOutcome {
    data object Approved : GateOutcome
    data object AlreadyApproved : GateOutcome
}