package com.qianyan.application.usecase.writing.planning

import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.application.error.ErrorMapper
import com.qianyan.application.usecase.UseCase
import com.qianyan.application.usecase.task.TaskManagerUseCases
import com.qianyan.model.TaskId
import com.qianyan.model.context.UserWritingRequest
import com.qianyan.model.story.ChapterPlan
import com.qianyan.model.task.Checkpoint
import com.qianyan.model.task.TaskType

/**
 * Planning 执行 Use Case（P11.2）。
 *
 * 目标链路：
 * ```
 * Task → PLANNING → RUNNING → PlannerAgent(AgentRuntime → LLMGateway) → ChapterPlan
 *     → Checkpoint → COMPLETED / FAILED
 * ```
 *
 * 职责边界：
 *  - 复用 P8.2 [TaskManagerUseCases] 生命周期（start / saveCheckpoint / complete / fail），不经状态机直改状态；
 *  - Checkpoint 复用现有 [com.qianyan.model.task.Checkpoint]（snapshot 承载 ChapterPlan，**不加表 / 不迁移**）；
 *  - 成功：校验 PLANNING → start → context assembly → planner → saveCheckpoint → complete；
 *  - 失败：start 后任何失败 → fail（记录类型化原因）→ 继续抛类型化错误；
 *  - 不实现 Agent loop / Workflow / HITL / retry（属 P11.3+）。
 */
class PlanningExecutionUseCase(
    private val taskManager: TaskManagerUseCases,
    private val assembly: PlanningContextAssembly,
    private val planner: PlannerAgent,
    errorMapper: ErrorMapper,
) : UseCase(errorMapper) {

    /**
     * 执行一个 PLANNING Task 到 COMPLETED / FAILED，并返回产出 [ChapterPlan]。
     * Task 类型非 PLANNING → [ApplicationError.InvalidOperation]；不存在 → TaskNotFound。
     */
    fun execute(taskId: TaskId, request: UserWritingRequest): ChapterPlan {
        val task = taskManager.findById(taskId)
        if (task.type != TaskType.PLANNING) {
            throw ApplicationException(
                ApplicationError.InvalidOperation("Task ${taskId.value} 类型 ${task.type} 不是 PLANNING，无法执行规划"),
            )
        }

        taskManager.start(taskId)
        try {
            val context = assembly.assemble(request)
            val plan = planner.plan(context)
            taskManager.saveCheckpoint(taskId, PlanningSnapshot.STAGE, PlanningSnapshot.encode(plan))
            taskManager.complete(taskId)
            return plan
        } catch (e: ApplicationException) {
            taskManager.fail(taskId, describe(e.error))
            throw e
        } catch (t: Throwable) {
            val mapped = errorMapper.map(t)
            taskManager.fail(taskId, describe(mapped.error))
            throw mapped
        }
    }

    /** 从 PLANNING Checkpoint 恢复 [ChapterPlan]（只读恢复上下文，不重新执行）。 */
    fun chapterPlanFrom(checkpoint: Checkpoint): ChapterPlan? = PlanningSnapshot.decode(checkpoint.snapshot)

    private fun describe(error: ApplicationError): String = when (error) {
        is ApplicationError.UnknownStorage -> "UnknownStorage: ${error.cause.message ?: error.cause::class.simpleName}"
        else -> error.toString()
    }
}