package com.qianyan.application.usecase.task

import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.application.error.ErrorMapper
import com.qianyan.application.usecase.UseCase
import com.qianyan.model.CheckpointId
import com.qianyan.model.TaskId
import com.qianyan.model.task.Checkpoint
import com.qianyan.model.task.Task
import com.qianyan.model.task.TaskStatus
import com.qianyan.model.task.TaskType
import com.qianyan.storage.repository.TaskRepository
import kotlinx.datetime.Clock
import kotlinx.serialization.json.JsonObject

/**
 * Task Manager Use Case（P8.2）：在 P8.1 持久化之上提供严格生命周期的任务管理。
 *
 * 职责边界：
 *  - 状态机：所有状态变更经 [TaskStateMachine] 校验，非法转换抛类型化错误，终态拒绝一切操作；
 *  - Revision：`nextRevision = task.revisionCount + 1`，顺序追加、禁止空洞/乱序/调用方指定 revision；
 *    上限 revisionCount <= 3 由本层在调用仓储前强制（P8.1 仓储已校验并 DB CHECK 兜底）；
 *  - Checkpoint：snapshot 继续使用结构化 JsonObject，不新增数据库列、不给 Task 增加 input/output 字段；
 *  - restoreCheckpoint 只恢复上下文（返回最近 Checkpoint），**不重新执行、不调用 LLM/Agent/Tool**；
 *  - 持久化一律经 [TaskRepository] 接口注入（create / update / saveCheckpoint 单事务，复用 P8.1）；
 *    本层不直接触碰 SQLDelight / 写 SQL。
 *
 * 明确范围外（P8.2 不做）：Agent / Tool / Orchestrator / Workflow / HITL / 自动 retry / 真实 Provider。
 */
class TaskManagerUseCases(
    private val taskRepository: TaskRepository,
    errorMapper: ErrorMapper,
) : UseCase(errorMapper) {

    /** 创建 PENDING Task（不自动保存 Checkpoint；revisionCount=0）。返回强类型 TaskId。 */
    fun create(type: TaskType, taskId: TaskId = TaskId(nextId())): TaskId {
        val now = Clock.System.now()
        val task = Task(
            taskId = taskId,
            type = type,
            status = TaskStatus.PENDING,
            progress = 0f,
            checkpoint = null,
            revisionCount = 0,
            error = null,
            createdAt = now,
            updatedAt = now,
        )
        guard { taskRepository.create(task) }
        return taskId
    }

    /** 查询 Task（附带最近 Checkpoint 派生视图）。不存在抛 [ApplicationError.TaskNotFound]。 */
    fun findById(taskId: TaskId): Task = requireTask(taskId)

    /** start：PENDING → RUNNING。 */
    fun start(taskId: TaskId, progress: Float = 0f): Task =
        transition(taskId, TaskOperation.START) { it.copy(status = TaskStatus.RUNNING, progress = progress.coerceIn(0f, 1f)) }

    /** pause：RUNNING → PAUSED（记录当前进度）。 */
    fun pause(taskId: TaskId, progress: Float): Task =
        transition(taskId, TaskOperation.PAUSE) { it.copy(status = TaskStatus.PAUSED, progress = progress.coerceIn(0f, 1f)) }

    /** resume：PAUSED → RUNNING（记录恢复进度）。 */
    fun resume(taskId: TaskId, progress: Float): Task =
        transition(taskId, TaskOperation.RESUME) { it.copy(status = TaskStatus.RUNNING, progress = progress.coerceIn(0f, 1f)) }

    /** cancel：PENDING / RUNNING / PAUSED → CANCELLED（终态）。 */
    fun cancel(taskId: TaskId): Task =
        transition(taskId, TaskOperation.CANCEL) { it.copy(status = TaskStatus.CANCELLED) }

    /** complete：RUNNING / PAUSED → COMPLETED（终态，progress=1.0）。 */
    fun complete(taskId: TaskId): Task =
        transition(taskId, TaskOperation.COMPLETE) { it.copy(status = TaskStatus.COMPLETED, progress = 1f) }

    /** fail：RUNNING → FAILED（终态，记录失败原因）。 */
    fun fail(taskId: TaskId, error: String): Task =
        transition(taskId, TaskOperation.FAIL) { it.copy(status = TaskStatus.FAILED, error = error) }

    /**
     * 保存 Checkpoint（revision 由 Manager 控制，调用方不可指定）。
     *
     * 流程：查询 Task → 计算 nextRevision = revisionCount + 1 → 校验 <= 3 → 创建 Checkpoint →
     * 复用 [TaskRepository.saveCheckpoint]（单事务同步 Task.revisionCount / updatedAt）。
     */
    fun saveCheckpoint(taskId: TaskId, stage: String, snapshot: JsonObject? = null): Checkpoint {
        val task = requireTask(taskId)
        val nextRevision = task.revisionCount + 1
        if (nextRevision > MAX_REVISIONS) {
            throw ApplicationException(
                ApplicationError.RevisionLimitExceeded("Task ${taskId.value} nextRevision=$nextRevision 超出上限 $MAX_REVISIONS"),
            )
        }
        val checkpoint = Checkpoint(
            checkpointId = CheckpointId(nextId()),
            taskId = taskId,
            revision = nextRevision,
            stage = stage,
            snapshot = snapshot,
            createdAt = Clock.System.now(),
        )
        guard { taskRepository.saveCheckpoint(checkpoint) }
        return checkpoint
    }

    /**
     * 恢复最近 Checkpoint（只恢复上下文，不重新执行）。
     *
     * 返回最近 Checkpoint（含 snapshot）；Task 不存在抛 [ApplicationError.TaskNotFound]，
     * 无 Checkpoint 抛 [ApplicationError.CheckpointNotFound]。
     */
    fun restoreCheckpoint(taskId: TaskId): Checkpoint {
        requireTask(taskId)
        return guard { taskRepository.findLatestCheckpoint(taskId) }
            ?: throw ApplicationException(
                ApplicationError.CheckpointNotFound("Task ${taskId.value} 无 Checkpoint 可恢复"),
            )
    }

    /** 查询 Task 的全部 Checkpoint（revision 升序）。Task 不存在抛 [ApplicationError.TaskNotFound]。 */
    fun findCheckpoints(taskId: TaskId): List<Checkpoint> {
        requireTask(taskId)
        return guard { taskRepository.findCheckpoints(taskId) }
    }

    // ---- 私有：状态转换通用路径 ----

    /** 读取 Task → 校验状态机 → 变更字段 → 更新 updatedAt → 经 Repository 单事务持久化。 */
    private fun transition(taskId: TaskId, op: TaskOperation, apply: (Task) -> Task): Task {
        val current = requireTask(taskId)
        val target = TaskStateMachine.transition(current.status, op)
        if (target == null) {
            throw illegalTransition(current.status, op)
        }
        val updated = apply(current).copy(
            // 派生视图不落库（Task.checkpoint 由最近 Checkpoint 派生，见 P8.1 约定）
            checkpoint = null,
            updatedAt = Clock.System.now(),
        )
        guard { taskRepository.update(updated) }
        return updated
    }

    private fun requireTask(taskId: TaskId): Task =
        guard { taskRepository.findById(taskId) }
            ?: throw ApplicationException(ApplicationError.TaskNotFound("Task 不存在: ${taskId.value}"))

    private fun illegalTransition(from: TaskStatus, op: TaskOperation): ApplicationException = when (from) {
        TaskStatus.COMPLETED -> ApplicationException(
            ApplicationError.TaskAlreadyCompleted("Task 已 COMPLETED，不能再执行 $op"),
        )
        TaskStatus.CANCELLED -> ApplicationException(
            ApplicationError.TaskAlreadyCancelled("Task 已 CANCELLED，不能再执行 $op"),
        )
        else -> ApplicationException(
            ApplicationError.InvalidTaskStateTransition("非法状态转换: $from --$op--> ?"),
        )
    }

    private companion object {
        /** P8 Preflight 冻结：revisionCount <= 3。 */
        const val MAX_REVISIONS = 3
    }
}
