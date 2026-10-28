package com.qianyan.application.usecase.writing.revision

import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.application.error.ErrorMapper
import com.qianyan.application.usecase.UseCase
import com.qianyan.application.usecase.task.TaskManagerUseCases
import com.qianyan.application.usecase.writing.WritingSnapshot
import com.qianyan.model.TaskId
import com.qianyan.model.spec.ValidationResult
import com.qianyan.model.task.TaskType

/**
 * Revision 执行 Use Case（P11.4）。
 *
 * 目标链路：
 * ```
 * Task(WRITING, revisionCount<3) → RevisionGate → RevisionAgent(AgentRuntime → LLMGateway)
 *     → 修订正文 → REVISION Checkpoint → revisionCount+1
 * ```
 *
 * 职责边界：
 *  - 校验 Task type == WRITING（非 WRITING → [ApplicationError.InvalidOperation]；不存在 → TaskNotFound）；
 *  - 先经 [RevisionGate]（确定性业务规则，复用 P8 revisionCount<3）：达上限 → [ApplicationError.RevisionNotAllowed]
 *    **且不调用 LLM / WriterAgent**；允许 → 才进入修订；
 *  - 修订基于 **WorkingDraft 正文 + [ValidationResult]（Critique）**（P1-02c+d）；本体**不持久化章节 Draft**，
 *    调用方（WorkflowOrchestrator）用返回值替换工作区正文；
 *  - 经 P8 [TaskManagerUseCases.saveCheckpoint] 保存 REVISION Checkpoint（类型化工作引用），
 *    **revisionCount 由 saveCheckpoint 自动 +1，上限 3 其内强制**；
 *  - 失败：Critique / Revision 可在任意 Task 状态执行，因此失败只抛类型化 [ApplicationException]（不调用 fail，
 *    避免对终态 Task 的非法状态转换）；
 *  - 不实现自动无限修订 / Workflow / HITL / retry（每次修订都是显式、受 Gate 门控的可控阶段）。
 */
class RevisionExecutionUseCase(
    private val taskManager: TaskManagerUseCases,
    private val rewriter: RevisionAgent,
    errorMapper: ErrorMapper,
) : UseCase(errorMapper) {

    /**
     * **P1-02c+d · AI 写作链的修订入口**：对 **WorkingDraft 正文**执行一次 Revision，返回修订后的正文。
     *
     * **不产生 / 不持久化章节 Draft**（AI 工作产物属 WorkingDraft）；本体不做持久化，
     * 调用方（WorkflowOrchestrator）用返回值替换工作区正文。Gate（[RevisionGate] ≤3）与错误映射完全复用。
     *
     * @param workingDraftId 工作产物引用（WorkingDraftId），仅用于 Checkpoint 与输入渲染，**不当 DraftId 用**。
     */
    fun reviseWorking(
        taskId: TaskId,
        workingDraftId: String,
        content: String,
        critique: ValidationResult,
        novelId: com.qianyan.model.NovelId,
    ): String {
        val task = taskManager.findById(taskId)
        if (task.type != TaskType.WRITING) {
            throw ApplicationException(
                ApplicationError.InvalidOperation("Task ${taskId.value} 类型 ${task.type} 不是 WRITING，无法执行修订"),
            )
        }
        // Gate：达上限 → RevisionNotAllowed，绝不进入修订/调用 LLM。
        RevisionGate.requireAllowed(task)

        val revisedContent = rewriter.reviseContent(workingDraftId, content, critique, novelId)
        // 复用 WRITING 阶段快照的类型化工作引用（不含正文）；saveCheckpoint 自动 revisionCount+1（上限 3 兜底）。
        taskManager.saveCheckpoint(
            taskId,
            REVISION_STAGE,
            WritingSnapshot.encodeWorkingReference(workingDraftId),
        )
        return revisedContent
    }

    companion object {
        /** Revision Checkpoint 阶段标识（distinct from WRITING / CRITIQUE）。 */
        const val REVISION_STAGE = "REVISION"
    }
}