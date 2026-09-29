package com.qianyan.model.taskqueue

import com.qianyan.model.ProjectId
import com.qianyan.model.TaskId
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/*
 * I13 · Background Task / Queue 契约（纯领域：无 storage / provider / agent runtime / UI 依赖）。
 *
 * 六个概念**严格分开**（见 I13 §0）：
 *   Task        = 后台执行载体（"要执行什么"；生命周期由既有 TaskStatus 表达）
 *   Queue       = 等待 / 排队 / 调度（"什么时候执行 / 等待谁执行"）
 *   Worker      = 实际执行者（claim → execute → complete/fail）
 *   Workflow    = 业务流程（既有 WorkflowOrchestrator，本阶段不改）
 *   AgentSession= Agent 工作会话（既有 I3，本阶段不改）
 *   ProjectState= 当前项目运行态（既有 I1，本阶段不改）
 *
 * 硬边界：
 *  - **不是第二套 Task**：Task 仍由既有 `com.qianyan.model.task.Task` + `TaskManagerUseCases` 承载；
 *    本模型只是 **Task 的调度条目**，对 Task 只有 `taskId` 引用，不复制 Task 的任何生命周期字段；
 *  - **不是第二套状态机**：[TaskQueueItemStatus] 只有 `QUEUED / CLAIMED` 两个**调度层**状态；
 *    RUNNING / PAUSED / COMPLETED / FAILED / CANCELLED 一律**只**由 Task 表达（队列不拥有业务生命周期）；
 *  - **不是第二套 Task 负载**：只保存 `projectId / taskId / kind` 与调度元数据；
 *    禁止把 Novel / Chapter / Draft / ContextPack 等业务对象复制进来（实际输入由既有 UseCase 按引用查询）；
 *  - **确定性调度**：[TaskQueueOrdering] 冻结排序；不依赖 HashMap 迭代序 / random / 当前时间；
 *  - **本地优先**：仅本进程 + 本地 SQLite（无 Redis / Kafka / MQ / Cloud Queue）。
 */

/** 队列条目身份（全局唯一）。 */
@JvmInline
@Serializable
value class TaskQueueItemId(val value: String)

/**
 * 队列条目的**调度层**状态（只有两个）。
 *
 * 刻意**不**包含 RUNNING / PAUSED / COMPLETED / FAILED / CANCELLED —— 那些是 Task 的生命周期，
 * 由 `com.qianyan.model.task.TaskStatus` 唯一表达（§5 / §30：队列不得变成第二套状态机）。
 */
@Serializable
enum class TaskQueueItemStatus {
    /** 已入队、等待被领取。 */
    QUEUED,

    /** 已被某个 Worker 领取（execution 进行中或即将进行）。 */
    CLAIMED,
}

/**
 * 调度优先级（最小三级，§14；ordinal 即权重：HIGH > NORMAL > LOW）。
 *
 * 不建立几十级优先级；若既有系统已有 Priority 应复用（本阶段无既有 Priority）。
 */
@Serializable
enum class TaskPriority { LOW, NORMAL, HIGH }

/**
 * 后台任务类型（§10：只加入当前真实需要的，不提前加入未来功能）。
 *
 * 这是**队列的调度键**：Worker 依此选择"由哪个既有能力执行"。
 * 不代表业务 Workflow（业务流程仍由既有 Workflow / UseCase 承担）。
 */
@Serializable
enum class TaskKind {
    /** 交给既有 I11 `NovelAgent`（本阶段只建立 seam，不复制其七相位）。 */
    NOVEL_AGENT,

    /** 交给既有 I12 `ProjectIndexUseCases.rebuild(...)` 后台重建索引。 */
    PROJECT_INDEX_REBUILD,
}

/**
 * 队列条目：**Task 的调度条目**（不是第二个 Task）。
 *
 * 生命周期由 Task 承载；本条目只回答"是否可被领取 / 谁领取了 / 重试到第几次"。
 *
 * @param queuedAt 入队时间（插入后**不可变**；参与确定性排序）。
 * @param availableAt 最早可领取时间（retry 退避写入；参与确定性排序）。
 * @param attempt 已发生的**失败尝试次数**（0 = 从未失败）；达到 [maxAttempts] 后不再重试。
 * @param maxAttempts 允许的最大失败尝试次数（有限重试，禁止无限 retry，§15）。
 * @param paused 调度暂停标志：`true` ⇒ 不再被领取。**不是**队列自己的生命周期状态，
 *   而是对既有 `TaskStatus.PAUSED` 的**可领取性镜像**（§18：PAUSED → 不再 claim）。
 * @param cancelRequested 已请求取消但尚未在执行边界收敛（§17：不强制杀线程）。
 * @param claimedBy Worker 标识（§8：最小 workerId，不建 WorkerSession / 状态机）。
 * @param claimedAt 领取时间（§25：lease 起点，供崩溃恢复判定过期）。
 * @param lastError 最近一次失败原因（可读文案；分类仍以 `AttemptErrorCategory` 为准）。
 */
@Serializable
data class TaskQueueItem(
    val queueItemId: TaskQueueItemId,
    val taskId: TaskId,
    val projectId: ProjectId,
    val kind: TaskKind,
    val priority: TaskPriority = TaskPriority.NORMAL,
    val status: TaskQueueItemStatus = TaskQueueItemStatus.QUEUED,
    val queuedAt: Instant,
    val availableAt: Instant,
    val attempt: Int = 0,
    val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    val paused: Boolean = false,
    val cancelRequested: Boolean = false,
    val claimedBy: String? = null,
    val claimedAt: Instant? = null,
    val lastError: String? = null,
) {
    /** 是否**当前**可被领取（调度资格判定：已入队 + 未暂停 + 未请求取消）。 */
    val isClaimable: Boolean
        get() = status == TaskQueueItemStatus.QUEUED && !paused && !cancelRequested

    /** 是否还能再重试一次（有限重试的上限判定）。 */
    val canRetry: Boolean get() = attempt < maxAttempts

    companion object {
        /** 默认最大失败尝试次数（§15：有限重试，禁止无限 retry）。 */
        const val DEFAULT_MAX_ATTEMPTS: Int = 3
    }
}

/**
 * 队列条目**确定性**调度序（§13）。
 *
 * `priority DESC → availableAt ASC → queuedAt ASC → taskId ASC`。
 * 相同输入 ⇒ 相同"下一个任务"；不依赖 Map 迭代序 / random / 当前时间 / 未定义 DB 顺序。
 */
object TaskQueueOrdering {

    /** "谁先执行"的冻结比较器。 */
    val NEXT: Comparator<TaskQueueItem> =
        compareByDescending<TaskQueueItem> { it.priority.ordinal }
            .thenBy { it.availableAt }
            .thenBy { it.queuedAt }
            .thenBy { it.taskId.value }

    /** 按 [NEXT] 排序的稳定快照（确定性输出）。 */
    fun sorted(items: List<TaskQueueItem>): List<TaskQueueItem> = items.sortedWith(NEXT)
}

/**
 * Background Task / Queue 的稳定错误码（承载在既有 `ApplicationError` 的 detail 中；**不新建错误体系**，§29）。
 *
 * 复用既有错误代数：`EntityNotFound`（不存在）/ `InvalidOperation`（状态不允许）。
 */
object TaskQueueErrorCodes {

    /** Task / 队列条目不存在（越界统一表现为不存在，不泄漏其他 Project 的存在性）。 */
    const val TASK_NOT_FOUND: String = "TASK_NOT_FOUND"

    /** 同一 Task 重复入队（禁止不可控重复执行，§28 L）。 */
    const val TASK_ALREADY_QUEUED: String = "TASK_ALREADY_QUEUED"

    /** 条目已被（其他）Worker 领取（§7：同一 Task 绝不允许被执行两次）。 */
    const val TASK_ALREADY_CLAIMED: String = "TASK_ALREADY_CLAIMED"

    /** 条目当前不可领取（已暂停 / 已请求取消 / Task 处于不可继续状态）。 */
    const val TASK_NOT_CLAIMABLE: String = "TASK_NOT_CLAIMABLE"

    /** Task 已 CANCELLED，拒绝再次操作。 */
    const val TASK_CANCELLED: String = "TASK_CANCELLED"

    /** Task 已 PAUSED（调度暂停 ⇒ 不再 claim）。 */
    const val TASK_PAUSED: String = "TASK_PAUSED"

    /** 已达 `maxAttempts`，禁止继续重试（§15）。 */
    const val MAX_ATTEMPTS_REACHED: String = "MAX_ATTEMPTS_REACHED"
}