package com.qianyan.application.usecase.taskqueue

import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.application.error.ErrorMapper
import com.qianyan.application.usecase.UseCase
import com.qianyan.application.usecase.task.TaskManagerUseCases
import com.qianyan.model.ProjectId
import com.qianyan.model.TaskId
import com.qianyan.model.task.Task
import com.qianyan.model.task.TaskStatus
import com.qianyan.model.taskqueue.TaskKind
import com.qianyan.model.taskqueue.TaskPriority
import com.qianyan.model.taskqueue.TaskQueueErrorCodes
import com.qianyan.model.taskqueue.TaskQueueItem
import com.qianyan.model.taskqueue.TaskQueueItemId
import com.qianyan.model.taskqueue.TaskQueueItemStatus
import com.qianyan.model.taskqueue.TaskQueueOrdering
import com.qianyan.model.workflow.AttemptErrorCategory
import com.qianyan.storage.repository.TaskQueueRepository
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.time.Duration.Companion.milliseconds

/**
 * 后台任务**失败**的最终处置（I13 §15 / §16；不是第二套 Task 生命周期）。
 *
 *  - [RETRY_SCHEDULED]：失败可重试且未达上限 ⇒ 条目重新变为可领取（Task 保持 RUNNING，等待重试）；
 *  - [FAILED]：不可重试 / 已达上限 ⇒ Task 经既有状态机进入终态 FAILED，条目出队。
 */
enum class TaskFailureDisposition { RETRY_SCHEDULED, FAILED }

/** [TaskQueueUseCases.fail] 的结果：处置方式 + 当前 attempt / maxAttempts。 */
data class TaskFailureOutcome(
    val disposition: TaskFailureDisposition,
    val attempt: Int,
    val maxAttempts: Int,
) {
    val willRetry: Boolean get() = disposition == TaskFailureDisposition.RETRY_SCHEDULED
}

/**
 * 取消请求的处置结果（I13 §17）。
 *
 *  - [CANCELLED]：领取前（QUEUED）⇒ 立即取消（Task 经既有状态机进入 CANCELLED，条目出队）；
 *  - [CANCELLATION_REQUESTED]：执行中（CLAIMED）⇒ 只置"已请求取消"，由 Worker 在**执行边界**收敛
 *    （不强制杀线程、不新建取消状态机）。
 */
enum class TaskCancelDisposition { CANCELLED, CANCELLATION_REQUESTED }

/**
 * I13 · 后台任务队列（Background Task / Queue）：只负责**调度**，不拥有 Task 的业务生命周期。
 *
 * 关系（I13 §0 / §3）：
 * ```
 * Task（既有 TaskManagerUseCases；RUNNING / PAUSED / COMPLETED / FAILED / CANCELLED）
 *     ↓ 入队（enqueue）
 * Queue（本类 + TaskQueueItem：QUEUED / CLAIMED —— 只有调度层状态）
 *     ↓ 领取（claim / claimNext，**原子**）
 * Worker（TaskWorker：claim → execute → complete / fail）
 *     ↓
 * 既有 UseCase / Agent / Index（本类**不**调用业务能力，执行由 Worker 注入的 Executor 完成）
 * ```
 *
 * 硬边界：
 *  - **复用既有 Task**：全部生命周期转换经既有 [TaskManagerUseCases]（start / pause / resume / cancel /
 *    complete / fail），本类绝不 `task.copy(status=...)`、绝不新建第二套 Task；
 *  - **不是第二套状态机**：队列只有 `QUEUED / CLAIMED`；业务结果一律落回 Task；
 *  - **不是第二套负载**：条目只存 `projectId / taskId / kind` + 调度元数据，不复制 Novel / Chapter / Draft；
 *  - **确定性**：候选排序用 [TaskQueueOrdering]（priority DESC → availableAt ASC → queuedAt ASC → taskId ASC），
 *    不依赖 Map 迭代序 / random / 当前时间；
 *  - **本地优先**：单进程 + 本地 SQLite，无分布式锁 / 外部队列中间件；
 *  - **Project 隔离**：领取一律按 Project 过滤；跨 Project 一律表现为"不存在"（不泄漏存在性）。
 */
class TaskQueueUseCases(
    private val queue: TaskQueueRepository,
    private val taskManager: TaskManagerUseCases,
    private val clock: Clock = Clock.System,
    private val retryBackoffMillis: Long = DEFAULT_RETRY_BACKOFF_MILLIS,
    errorMapper: ErrorMapper,
) : UseCase(errorMapper) {

    /* ---------------- 入队 ---------------- */

    /**
     * 把一个**已存在且 PENDING** 的 Task 放入队列（返回调度条目）。
     *
     * 同一 Task 重复入队 → [TaskQueueErrorCodes.TASK_ALREADY_QUEUED]（禁止不可控重复执行，§28 L）；
     * 非 PENDING（已 RUNNING / 终态）→ [TaskQueueErrorCodes.TASK_NOT_CLAIMABLE]。
     *
     * @param projectId 声明的 Project 归属（领取按此隔离，§28 J）。
     * @param kind 调度键（决定由哪个既有能力执行，§10）。
     */
    fun enqueue(
        taskId: TaskId,
        projectId: ProjectId,
        kind: TaskKind,
        priority: TaskPriority = TaskPriority.NORMAL,
        maxAttempts: Int = TaskQueueItem.DEFAULT_MAX_ATTEMPTS,
    ): TaskQueueItem {
        require(maxAttempts >= 1) { "maxAttempts 必须 >= 1（有限重试，禁止无限 retry）" }
        val task = requireTask(taskId)
        if (task.status != TaskStatus.PENDING) {
            throw notClaimable(taskId, "Task 状态为 ${task.status}，只有 PENDING 可入队")
        }
        if (guard { queue.getByTask(taskId) } != null) throw alreadyQueued(taskId)

        val now = now()
        val item = TaskQueueItem(
            queueItemId = TaskQueueItemId(nextId()),
            taskId = taskId,
            projectId = projectId,
            kind = kind,
            priority = priority,
            status = TaskQueueItemStatus.QUEUED,
            queuedAt = now,
            availableAt = now,
            attempt = 0,
            maxAttempts = maxAttempts,
        )
        guard { queue.insert(item) }
        return item
    }

    /* ---------------- 查询 ---------------- */

    /** 按 Task 查询调度条目；不存在返回 null。 */
    fun findByTask(taskId: TaskId): TaskQueueItem? = guard { queue.getByTask(taskId) }

    /** 某 Project 的全部调度条目（确定性排序）。 */
    fun listByProject(projectId: ProjectId): List<TaskQueueItem> =
        TaskQueueOrdering.sorted(guard { queue.listByProject(projectId) })

    /** 是否已请求取消（Worker 在执行边界轮询此信号；§17）。 */
    fun isCancellationRequested(taskId: TaskId): Boolean =
        guard { queue.getByTask(taskId) }?.cancelRequested == true

    /* ---------------- 领取（原子） ---------------- */

    /**
     * 领取该 Project 中**下一个**符合确定性顺序的条目（QUEUED + 未暂停 + 未请求取消 + 已到 availableAt）。
     *
     * 领取成功即把条目置 CLAIMED 并让 Task 进入 RUNNING（PENDING → start）；无可领取条目返回 null。
     */
    @Synchronized
    fun claimNext(workerId: String, projectId: ProjectId): TaskQueueItem? {
        val now = now()
        val candidates = TaskQueueOrdering.sorted(
            guard { queue.listByProject(projectId) }.filter { it.isClaimable && it.availableAt <= now },
        )
        for (candidate in candidates) {
            val claimed = guard { queue.tryClaim(candidate.queueItemId, workerId, now) } ?: continue
            enterRunning(claimed.taskId)
            return claimed
        }
        return null
    }

    /**
     * 领取**指定** Task 的条目（§7：同一 Task 只能被一个 Worker 领取）。
     *
     * 已被领取 → [TaskQueueErrorCodes.TASK_ALREADY_CLAIMED]；暂停 / 已请求取消 / 未到时间 / 越界 → 对应稳定错误。
     */
    @Synchronized
    fun claim(taskId: TaskId, workerId: String, projectId: ProjectId): TaskQueueItem {
        val item = requireItemInProject(taskId, projectId)
        if (item.status == TaskQueueItemStatus.CLAIMED) throw alreadyClaimed(taskId)
        val now = now()
        if (!item.isClaimable) throw notClaimable(taskId, "条目不可领取（暂停=${item.paused} 取消请求=${item.cancelRequested}）")
        if (item.availableAt > now) throw notClaimable(taskId, "尚未到可领取时间（availableAt=${item.availableAt}）")

        val claimed = guard { queue.tryClaim(item.queueItemId, workerId, now) } ?: throw alreadyClaimed(taskId)
        enterRunning(taskId)
        return claimed
    }

    /* ---------------- 完成 / 失败 ---------------- */

    /** 完成：Task → COMPLETED（既有状态机），条目出队。 */
    fun complete(taskId: TaskId): Task {
        val item = requireItem(taskId)
        requireTask(taskId)
        val done = guard { taskManager.complete(taskId) }
        guard { queue.delete(item.queueItemId) }
        return done
    }

    /**
     * 失败（§15 / §16）：可重试且未达 `maxAttempts` ⇒ 重新可领取（Task 保持 RUNNING）；
     * 否则 Task → FAILED（既有状态机）并出队。
     *
     * 分类一律复用既有 [AttemptErrorCategory]，不新建失败分类体系。
     */
    fun fail(
        taskId: TaskId,
        error: String,
        category: AttemptErrorCategory = AttemptErrorCategory.NON_RETRYABLE,
    ): TaskFailureOutcome {
        val item = requireItem(taskId)
        val nextAttempt = item.attempt + 1
        val retryable = category == AttemptErrorCategory.RETRYABLE

        if (retryable && nextAttempt < item.maxAttempts) {
            val now = now()
            guard {
                queue.updateScheduling(
                    item.copy(
                        status = TaskQueueItemStatus.QUEUED,
                        attempt = nextAttempt,
                        claimedBy = null,
                        claimedAt = null,
                        availableAt = now + retryBackoffMillis.milliseconds,
                        lastError = error,
                    ),
                )
            }
            return TaskFailureOutcome(TaskFailureDisposition.RETRY_SCHEDULED, nextAttempt, item.maxAttempts)
        }

        guard { taskManager.fail(taskId, error) }
        guard { queue.delete(item.queueItemId) }
        return TaskFailureOutcome(TaskFailureDisposition.FAILED, nextAttempt, item.maxAttempts)
    }

    /**
     * 显式重试（§6）：把条目重新置为可领取（attempt + 1）。
     *
     * 已被领取（CLAIMED）→ [TaskQueueErrorCodes.TASK_ALREADY_CLAIMED]（绝不抢走执行中的条目 ⇒ 不会被执行两次）；
     * Task 已终态（CANCELLED / COMPLETED / FAILED）→ [TaskQueueErrorCodes.TASK_NOT_CLAIMABLE]；
     * 已达 [TaskQueueItem.maxAttempts] → [TaskQueueErrorCodes.MAX_ATTEMPTS_REACHED]。
     */
    fun retry(taskId: TaskId): TaskQueueItem {
        val item = requireItem(taskId)
        val task = requireTask(taskId)
        if (item.status == TaskQueueItemStatus.CLAIMED) throw alreadyClaimed(taskId)
        if (task.status == TaskStatus.CANCELLED) throw cancelled(taskId)
        if (task.status == TaskStatus.COMPLETED || task.status == TaskStatus.FAILED) {
            throw notClaimable(taskId, "Task 已终态 ${task.status}，不可重试")
        }
        if (!item.canRetry) throw maxAttemptsReached(taskId)
        val updated = item.copy(
            status = TaskQueueItemStatus.QUEUED,
            attempt = item.attempt + 1,
            claimedBy = null,
            claimedAt = null,
            availableAt = now(),
        )
        guard { queue.updateScheduling(updated) }
        return updated
    }

    /* ---------------- 取消（§17） ---------------- */

    /**
     * 取消：QUEUED ⇒ 立即 CANCELLED（Task 经既有状态机 + 出队）；
     * CLAIMED ⇒ 只置"已请求取消"，由 Worker 在执行边界经 [convergeCancellation] 收敛。
     */
    fun cancel(taskId: TaskId): TaskCancelDisposition {
        val task = requireTask(taskId)
        if (task.status == TaskStatus.CANCELLED) throw cancelled(taskId)
        val item = guard { queue.getByTask(taskId) }
        if (item == null) {
            guard { taskManager.cancel(taskId) }
            return TaskCancelDisposition.CANCELLED
        }
        return when (item.status) {
            TaskQueueItemStatus.QUEUED -> {
                guard { taskManager.cancel(taskId) }
                guard { queue.delete(item.queueItemId) }
                TaskCancelDisposition.CANCELLED
            }

            TaskQueueItemStatus.CLAIMED -> {
                guard { queue.updateScheduling(item.copy(cancelRequested = true)) }
                TaskCancelDisposition.CANCELLATION_REQUESTED
            }
        }
    }

    /** 执行边界收敛：把"已请求取消"的领取中条目真正落为 CANCELLED（Task 经既有状态机 + 出队）。 */
    fun convergeCancellation(taskId: TaskId): Task {
        val item = requireItem(taskId)
        requireTask(taskId)
        val done = guard { taskManager.cancel(taskId) }
        guard { queue.delete(item.queueItemId) }
        return done
    }

    /* ---------------- 暂停 / 恢复（§18：复用既有 Task.PAUSED） ---------------- */

    /**
     * 暂停：Task RUNNING → PAUSED（既有状态机）；条目变为"已暂停"（不再被领取）。
     * 若条目处于 CLAIMED，同时**释放领取**（避免暂停后仍被占用）。
     */
    fun pause(taskId: TaskId): TaskQueueItem {
        val item = requireItem(taskId)
        val task = requireTask(taskId)
        if (task.status == TaskStatus.CANCELLED) throw cancelled(taskId)
        if (task.status == TaskStatus.PAUSED) throw paused(taskId)
        guard { taskManager.pause(taskId, task.progress) }
        val updated = item.copy(
            status = TaskQueueItemStatus.QUEUED,
            paused = true,
            claimedBy = null,
            claimedAt = null,
        )
        guard { queue.updateScheduling(updated) }
        return updated
    }

    /** 恢复：Task PAUSED → RUNNING（既有状态机）；条目重新可领取（= 重新入队，§18）。 */
    fun resume(taskId: TaskId): TaskQueueItem {
        val item = requireItem(taskId)
        val task = requireTask(taskId)
        if (task.status != TaskStatus.PAUSED) throw paused(taskId)
        guard { taskManager.resume(taskId, task.progress) }
        val updated = item.copy(paused = false, claimedBy = null, claimedAt = null)
        guard { queue.updateScheduling(updated) }
        return updated
    }

    /* ---------------- 崩溃恢复（§25） ---------------- */

    /**
     * 回收 **lease 过期**的领取中条目（Worker 崩溃 / 进程被杀后不留下永久 CLAIMED）。
     *
     *  - 未请求取消 ⇒ 重新变为可领取（QUEUED，释放 claim）；
     *  - 已请求取消 ⇒ 收敛为 CANCELLED 并出队（领取者已消失，无法再在执行边界收敛）。
     *
     * @return 被回收的条目数。不做分布式锁（本地优先）。
     */
    @Synchronized
    fun recoverExpiredLeases(now: Instant = this.now(), leaseMillis: Long = DEFAULT_LEASE_MILLIS): Int {
        val threshold = Instant.fromEpochMilliseconds(now.toEpochMilliseconds() - leaseMillis)
        var recovered = 0
        guard { queue.listClaimedBefore(threshold) }.forEach { item ->
            if (item.cancelRequested) {
                // 领取者已消失：取消请求无法再经执行边界收敛，直接收敛为 CANCELLED（best-effort）。
                runCatching { taskManager.cancel(item.taskId) }
                guard { queue.delete(item.queueItemId) }
            } else {
                guard {
                    queue.updateScheduling(
                        item.copy(
                            status = TaskQueueItemStatus.QUEUED,
                            claimedBy = null,
                            claimedAt = null,
                            availableAt = now,
                        ),
                    )
                }
            }
            recovered++
        }
        return recovered
    }

    /* ---------------- internals ---------------- */

    /** 领取后让 Task 进入 RUNNING：仅 PENDING 时经既有状态机 start；重试重新领取时 Task 已在 RUNNING。 */
    private fun enterRunning(taskId: TaskId) {
        when (requireTask(taskId).status) {
            TaskStatus.PENDING -> guard { taskManager.start(taskId) }
            TaskStatus.RUNNING -> Unit
            else -> throw notClaimable(taskId, "Task 状态不可执行")
        }
    }

    private fun requireTask(taskId: TaskId): Task = guard { taskManager.findById(taskId) }

    private fun requireItem(taskId: TaskId): TaskQueueItem =
        guard { queue.getByTask(taskId) } ?: throw notFound(taskId)

    /** 越界统一表现为"不存在"（不泄漏其他 Project 是否有该条目，§28 J）。 */
    private fun requireItemInProject(taskId: TaskId, projectId: ProjectId): TaskQueueItem {
        val item = requireItem(taskId)
        if (item.projectId != projectId) throw notFound(taskId)
        return item
    }

    private fun notFound(taskId: TaskId): ApplicationException = ApplicationException(
        ApplicationError.EntityNotFound("[${TaskQueueErrorCodes.TASK_NOT_FOUND}] 队列条目不存在: ${taskId.value}"),
    )

    private fun alreadyQueued(taskId: TaskId): ApplicationException = ApplicationException(
        ApplicationError.InvalidOperation(
            "[${TaskQueueErrorCodes.TASK_ALREADY_QUEUED}] Task 已在队列中: ${taskId.value}",
        ),
    )

    private fun alreadyClaimed(taskId: TaskId): ApplicationException = ApplicationException(
        ApplicationError.InvalidOperation(
            "[${TaskQueueErrorCodes.TASK_ALREADY_CLAIMED}] Task 已被领取: ${taskId.value}",
        ),
    )

    private fun notClaimable(taskId: TaskId, detail: String): ApplicationException = ApplicationException(
        ApplicationError.InvalidOperation("[${TaskQueueErrorCodes.TASK_NOT_CLAIMABLE}] $detail: ${taskId.value}"),
    )

    private fun cancelled(taskId: TaskId): ApplicationException = ApplicationException(
        ApplicationError.InvalidOperation("[${TaskQueueErrorCodes.TASK_CANCELLED}] Task 已取消: ${taskId.value}"),
    )

    private fun paused(taskId: TaskId): ApplicationException = ApplicationException(
        ApplicationError.InvalidOperation(
            "[${TaskQueueErrorCodes.TASK_PAUSED}] Task 暂停状态不匹配: ${taskId.value}",
        ),
    )

    private fun maxAttemptsReached(taskId: TaskId): ApplicationException = ApplicationException(
        ApplicationError.InvalidOperation(
            "[${TaskQueueErrorCodes.MAX_ATTEMPTS_REACHED}] 已达最大尝试次数: ${taskId.value}",
        ),
    )

    /** 时间戳精度与存储一致（epoch 毫秒）；时钟可注入，保证测试确定性。 */
    private fun now(): Instant = Instant.fromEpochMilliseconds(clock.now().toEpochMilliseconds())

    companion object {
        /** 默认 lease 时长（崩溃恢复阈值，§25）。 */
        const val DEFAULT_LEASE_MILLIS: Long = 60_000L

        /** 默认重试退避（确定性常量，不依赖随机）。 */
        const val DEFAULT_RETRY_BACKOFF_MILLIS: Long = 1_000L
    }
}