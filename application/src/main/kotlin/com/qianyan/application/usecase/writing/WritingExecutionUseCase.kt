package com.qianyan.application.usecase.writing

import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.application.error.ErrorMapper
import com.qianyan.application.usecase.UseCase
import com.qianyan.application.usecase.task.TaskManagerUseCases
import com.qianyan.application.usecase.writing.planning.PlanningContextAssembly
import com.qianyan.model.TaskId
import com.qianyan.model.context.UserWritingRequest
import com.qianyan.model.story.ChapterPlan
import com.qianyan.model.task.Checkpoint
import com.qianyan.model.task.TaskType
import com.qianyan.model.writing.Draft
import com.qianyan.storage.repository.DraftRepository

/**
 * Writing 执行 Use Case（P11.3）。
 *
 * 目标链路：
 * ```
 * Task → WRITING → RUNNING → context assembly → WriterAgent(AgentRuntime → LLMGateway → Mock)
 *     → DraftParser → DraftRepository.save → WRITING Checkpoint → COMPLETED / FAILED
 * ```
 *
 * 职责边界：
 *  - 校验 Task type == WRITING（非 WRITING → [ApplicationError.InvalidOperation]；不存在 → TaskNotFound）；
 *  - 复用 P8.2 [TaskManagerUseCases] 生命周期（start / saveCheckpoint / complete / fail），不经状态机直改状态；
 *  - 接收 taskId + [UserWritingRequest] + [ChapterPlan]（ChapterPlan 由 PLANNING Checkpoint 恢复而来）；
 *  - 组装 [com.qianyan.application.usecase.writing.planning.PlanningContext]（复用 Planning 上下文，不创建第二套）；
 *  - 调用 [WriterAgent] → [DraftParser] → [DraftRepository.save] → saveCheckpoint("WRITING", WritingSnapshot)
 *    → complete；
 *  - 成功：PENDING → RUNNING → WRITING → Checkpoint → COMPLETED；
 *    失败：RUNNING → FAILED（保存类型化 Task error）→ 继续抛类型化错误；绝不伪造 Draft。
 *  - 不修改 TaskStateMachine；不实现 Agent loop / Workflow / HITL / retry（属 P11.4+）。
 */
class WritingExecutionUseCase(
    private val taskManager: TaskManagerUseCases,
    private val contextAssembly: PlanningContextAssembly,
    private val writer: WriterAgent,
    private val draftRepository: DraftRepository,
    errorMapper: ErrorMapper,
) : UseCase(errorMapper) {

    /**
     * 执行一个 WRITING Task 到 COMPLETED / FAILED，并返回产出 [Draft]。
     * Task 类型非 WRITING → [ApplicationError.InvalidOperation]；不存在 → TaskNotFound。
     */
    fun execute(taskId: TaskId, request: UserWritingRequest, plan: ChapterPlan): Draft {
        val task = taskManager.findById(taskId)
        if (task.type != TaskType.WRITING) {
            throw ApplicationException(
                ApplicationError.InvalidOperation("Task ${taskId.value} 类型 ${task.type} 不是 WRITING，无法执行写作"),
            )
        }

        taskManager.start(taskId)
        try {
            val context = contextAssembly.assemble(request)
            val draft = writer.write(context, plan)
            guard { draftRepository.save(draft) }
            taskManager.saveCheckpoint(taskId, WritingSnapshot.STAGE, WritingSnapshot.encode(draft))
            taskManager.complete(taskId)
            return draft
        } catch (e: ApplicationException) {
            taskManager.fail(taskId, describe(e.error))
            throw e
        } catch (t: Throwable) {
            val mapped = errorMapper.map(t)
            taskManager.fail(taskId, describe(mapped.error))
            throw mapped
        }
    }

    /** 从 WRITING Checkpoint 恢复 [Draft]（只读恢复上下文，不重新执行）。 */
    fun draftFrom(checkpoint: Checkpoint): Draft? = WritingSnapshot.decode(checkpoint.snapshot)

    private fun describe(error: ApplicationError): String = when (error) {
        is ApplicationError.UnknownStorage -> "UnknownStorage: ${error.cause.message ?: error.cause::class.simpleName}"
        else -> error.toString()
    }
}