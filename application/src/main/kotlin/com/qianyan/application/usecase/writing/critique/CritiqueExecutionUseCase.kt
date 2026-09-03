package com.qianyan.application.usecase.writing.critique

import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.application.error.ErrorMapper
import com.qianyan.application.usecase.UseCase
import com.qianyan.application.usecase.task.TaskManagerUseCases
import com.qianyan.model.TaskId
import com.qianyan.model.spec.ValidationResult
import com.qianyan.model.task.Checkpoint
import com.qianyan.model.task.TaskType
import com.qianyan.model.writing.Draft

/**
 * Critique 执行 Use Case（P11.4）。
 *
 * 目标链路：
 * ```
 * Task(WRITING) → CritiqueAgent(AgentRuntime → LLMGateway) → ValidationResult
 *     → CRITIQUE Checkpoint
 * ```
 *
 * 职责边界：
 *  - 校验 Task type == WRITING（非 WRITING → [ApplicationError.InvalidOperation]；不存在 → TaskNotFound）；
 *  - Critique 是**只读评审**：只产出 [ValidationResult] 并保存 CRITIQUE Checkpoint（复用
 *    [com.qianyan.model.task.Checkpoint.snapshot]，不加表），**不触发 start/complete**、不改写 Draft；
 *  - 复用 P8 [TaskManagerUseCases.saveCheckpoint]（在任意状态可写，revision 上限 3 由其中强制）承载评审上下文；
 *  - 失败：Critique 在任何 Task 状态下都可能执行（draft 可能来自已 COMPLETED 任务），因此失败只抛类型化
 *    [ApplicationException]（不调用 fail，避免对终态 Task 的非法状态转换）；
 *  - 不实现 Agent loop / Workflow / HITL / retry（属后续阶段）。
 */
class CritiqueExecutionUseCase(
    private val taskManager: TaskManagerUseCases,
    private val critic: CritiqueAgent,
    errorMapper: ErrorMapper,
) : UseCase(errorMapper) {

    /**
     * 对 [draft] 执行一次 Critique，返回 [ValidationResult]。
     * Task 类型非 WRITING → [ApplicationError.InvalidOperation]；不存在 → TaskNotFound。
     */
    fun execute(taskId: TaskId, draft: Draft): ValidationResult {
        val task = taskManager.findById(taskId)
        if (task.type != TaskType.WRITING) {
            throw ApplicationException(
                ApplicationError.InvalidOperation("Task ${taskId.value} 类型 ${task.type} 不是 WRITING，无法执行评审"),
            )
        }
        val result = critic.critique(draft)
        taskManager.saveCheckpoint(taskId, CritiqueSnapshot.STAGE, CritiqueSnapshot.encode(result))
        return result
    }

    /** 从 CRITIQUE Checkpoint 恢复 [ValidationResult]（只读恢复上下文，不重新执行）。 */
    fun critiqueFrom(checkpoint: Checkpoint): ValidationResult? = CritiqueSnapshot.decode(checkpoint.snapshot)
}