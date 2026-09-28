package com.qianyan.application.usecase.action

import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.application.error.ErrorMapper
import com.qianyan.application.usecase.UseCase
import com.qianyan.application.usecase.workflow.WorkflowService
import com.qianyan.model.DraftId
import com.qianyan.model.action.ActionDecision
import com.qianyan.model.action.ActionRisk
import com.qianyan.model.action.AgentAction
import com.qianyan.model.action.AgentActionKind
import com.qianyan.model.workflow.HumanDecision
import com.qianyan.model.workflow.WorkflowHumanGate
import com.qianyan.model.workflow.WorkflowHumanGateId
import com.qianyan.model.workflow.WorkflowStatus
import com.qianyan.model.workflow.WorkflowStepId
import com.qianyan.model.workflow.WorkflowId
import com.qianyan.storage.repository.WorkflowRepository
import kotlinx.datetime.Clock

/**
 * I2 · Action Policy（Agent 行动权限）+ Human Gate 复用。
 *
 * 关系（architecture §16 / §27）：
 * ```
 * Agent 提出 Action → [ActionPolicyUseCases.evaluate] → ALLOW / DENY / NEEDS_HUMAN
 *                                                          NEEDS_HUMAN ↓
 *                                     [openHumanGate] → 既有 Workflow Human Gate（PENDING）
 *                                                          ↓ 既有 WorkflowStatus.WAITING_HUMAN
 *                                     [resolveHumanGate] → 既有 approveGate / rejectGate
 *                                                          → APPROVED / REJECTED / REQUEST_REVISION
 * ```
 *
 * 硬边界：
 *  - **不重新实现 Human Gate**：创建与决议全部委托既有 [WorkflowService]（`createPendingGate` / `approveGate` /
 *      `rejectGate`）；本类不写 gate 状态、不新增 gate 表、不新增 gate 状态词汇；
 *  - **不改 Workflow 核心机制**：只**使用**既有 `WorkflowStatus.WAITING_HUMAN` 与既有 `pendingGateId` 字段，
 *      不改 PAUSED / CANCELLED / FAILED / COMPLETED / Retry / Resume / Checkpoint / Attempt 的任何语义；
 *  - **不实现 Commit / Working Draft / Session / Tool / Skill / Validation / Artifact / History**（后续阶段）；
 *  - **与 P19 无关**：判断不读取、不产生、不修改任何创作决策（P19 仍为唯一决策来源，SEALED）；
 *  - **确定性**：判断不调用 LLM、不读时间、不依赖外部状态（同输入同输出）。
 */
class ActionPolicyUseCases(
    private val workflowRepository: WorkflowRepository,
    private val workflowService: WorkflowService,
    errorMapper: ErrorMapper,
) : UseCase(errorMapper) {

    /**
     * 判断一次行动是否允许（**纯确定性**：无 IO、无 LLM、无时间、无随机）。
     *
     * 规则表（按动作类别，不按 UI 按钮）：
     *   LOW    → Allowed    READ / SEARCH / ANALYZE / VALIDATE
     *   MEDIUM → Allowed    CREATE_WORKING_DRAFT / EDIT_WORKING_DRAFT / PROPOSE_CHANGE
     *   HIGH   → NeedsHuman MODIFY_PLAN / UPDATE_WORLD_MODEL / MODIFY_CANONICAL / COMMIT_CANONICAL
     *   HIGH   → Denied     DELETE（FD-10 明确排除；删除能力开放前不进入人工确认流程）
     */
    fun evaluate(action: AgentAction): ActionDecision = when (action.kind) {
        AgentActionKind.READ,
        AgentActionKind.SEARCH,
        AgentActionKind.ANALYZE,
        AgentActionKind.VALIDATE,
        -> ActionDecision.Allowed(action, ActionRisk.LOW, "只读/派生动作，不改动任何长期事实")

        AgentActionKind.CREATE_WORKING_DRAFT,
        AgentActionKind.EDIT_WORKING_DRAFT,
        AgentActionKind.PROPOSE_CHANGE,
        -> ActionDecision.Allowed(action, ActionRisk.MEDIUM, "仅写 Working Draft，不影响 Canonical（执行需记录）")

        AgentActionKind.MODIFY_PLAN,
        AgentActionKind.UPDATE_WORLD_MODEL,
        AgentActionKind.MODIFY_CANONICAL,
        AgentActionKind.COMMIT_CANONICAL,
        -> ActionDecision.NeedsHuman(action, ActionRisk.HIGH, "改动规划/世界模型/Canonical，须人工确认后继续")

        AgentActionKind.DELETE,
        -> ActionDecision.Denied(action, ActionRisk.HIGH, "当前范围明确排除删除类动作（FD-10；删除能力未开放）")
    }

    /**
     * 要求动作为"允许"，否则类型化拒绝（供 Agent 在执行前调用）。
     * DENY / NEEDS_HUMAN 都不会被执行 —— 需要人工的动作必须先经 [openHumanGate] 与 [resolveHumanGate]。
     */
    fun requireAllowed(action: AgentAction): ActionDecision.Allowed =
        when (val decision = evaluate(action)) {
            is ActionDecision.Allowed -> decision
            is ActionDecision.Denied -> throw ApplicationException(
                ApplicationError.InvalidOperation("动作 ${action.kind} 被拒绝：${decision.reason}"),
            )
            is ActionDecision.NeedsHuman -> throw ApplicationException(
                ApplicationError.InvalidOperation("动作 ${action.kind} 需要人工确认：${decision.reason}"),
            )
        }

    /**
     * 为**需要人工确认**的动作开启既有 Human Gate（幂等）。
     *
     * 复用：
     *  - [WorkflowService.createPendingGate]（既有 gateKey 方案 `${workflowId}:${stepId}:${draftId}`，同键复用既有 gate）；
     *  - 既有 `WorkflowStatus.WAITING_HUMAN` 与既有 `Workflow.pendingGateId` 字段。
     *
     * 非 NEEDS_HUMAN 的动作不得开启 Gate（防止把低风险动作塞进人工确认流程）。
     */
    fun openHumanGate(
        workflowId: WorkflowId,
        stepId: WorkflowStepId,
        draftId: DraftId,
        action: AgentAction,
    ): WorkflowHumanGate {
        val decision = evaluate(action)
        if (decision !is ActionDecision.NeedsHuman) {
            throw ApplicationException(
                ApplicationError.InvalidOperation("动作 ${action.kind} 无需人工确认（${decision.risk}），不得开启 Human Gate"),
            )
        }
        val gate = guard { workflowService.createPendingGate(workflowId, stepId, draftId) }
        // 复用既有 WAITING_HUMAN 语义（不新增状态）；审批/拒绝后由既有决议路径收敛。
        val workflow = guard { workflowRepository.getWorkflow(workflowId) }
            ?: throw ApplicationException(ApplicationError.EntityNotFound("Workflow 不存在: ${workflowId.value}"))
        if (workflow.status != WorkflowStatus.WAITING_HUMAN) {
            guard {
                workflowRepository.updateWorkflow(
                    workflow.copy(status = WorkflowStatus.WAITING_HUMAN, updatedAt = Clock.System.now()),
                )
            }
        }
        return gate
    }

    /**
     * 决议既有 Human Gate —— **完全复用既有语义**：
     *  - `APPROVED` → [WorkflowService.approveGate]（既有：确认 Draft + 幂等决议 + 既有 Knowledge Update 链）；
     *  - `REJECTED` / `REQUEST_REVISION` → [WorkflowService.rejectGate]（既有：PENDING→RESOLVED，幂等）；
     *  - `PENDING` 不是决议 → 类型化拒绝。
     *
     * 返回决议后的 gate（沿用既有 [WorkflowHumanGate] 词汇与状态，不新增结果模型）。
     */
    fun resolveHumanGate(
        gateId: WorkflowHumanGateId,
        decision: HumanDecision,
        by: String = "user",
    ): WorkflowHumanGate {
        when (decision) {
            HumanDecision.APPROVED -> guard { workflowService.approveGate(gateId) }
            HumanDecision.REJECTED, HumanDecision.REQUEST_REVISION -> guard {
                workflowService.rejectGate(gateId, decision, by)
            }
            HumanDecision.PENDING -> throw ApplicationException(
                ApplicationError.InvalidOperation("PENDING 不是决议，无法 resolve Human Gate ${gateId.value}"),
            )
        }
        return guard { workflowRepository.getGate(gateId) }
            ?: throw ApplicationException(ApplicationError.EntityNotFound("Human Gate 不存在: ${gateId.value}"))
    }
}