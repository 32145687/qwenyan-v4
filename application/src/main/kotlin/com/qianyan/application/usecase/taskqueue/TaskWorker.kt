package com.qianyan.application.usecase.taskqueue

import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.application.error.ErrorMapper
import com.qianyan.model.ProjectId
import com.qianyan.model.taskqueue.TaskKind
import com.qianyan.model.taskqueue.TaskQueueItem
import com.qianyan.model.workflow.AttemptErrorCategory

/**
 * 后台任务执行能力（I13 §19）：按 [TaskKind] 注册的**实际执行者**。
 *
 * 只负责"怎么执行"，不决定 Skill / Context / Workflow / Commit —— 那些仍属于既有
 * NovelAgent / Workflow / UseCase（§19 / §21 / §22）。
 */
fun interface TaskExecutor {
    /** 执行一个已领取的条目（实现方按 [TaskQueueItem.projectId] / kind 调用既有应用能力）。 */
    fun execute(item: TaskQueueItem)
}

/**
 * I13 · 最小 Worker（§19 / §27）：`claim → execute → complete / fail`。
 *
 * 硬边界（§19 / §20 / §24）：
 *  - 只经 [TaskQueueUseCases] 访问队列 / Task 生命周期，**不直接访问数据库**（无 SqlDriver / 无 SQLite）；
 *  - **不是 Workflow**：不实现 INTENT / PLAN / WRITE / REVIEW / COMMIT，只按 [TaskKind] 委派给注入的 [TaskExecutor]；
 *  - 不决定 Skill / Context / Commit，不重写既有 Agent；
 *  - 第一版为**本地单步** Worker（[runOnce] / 有界 [drain]），不引入线程池 / 分布式锁（§26 / §27）。
 */
class TaskWorker(
    /** Worker 标识（§8：最小 workerId，不是 WorkerSession / 状态机）。 */
    val workerId: String,
    private val queue: TaskQueueUseCases,
    /** 执行能力映射（只注册当前真实需要的 kind；缺失 = 不可执行，落 NON_RETRYABLE 失败）。 */
    private val executors: Map<TaskKind, TaskExecutor>,
    private val errorMapper: ErrorMapper = ErrorMapper,
) {

    /**
     * 领取并执行**一个**任务；无可领取条目返回 null。
     *
     * 执行结果一律落回既有 Task 生命周期：成功 → [TaskQueueUseCases.complete]；
     * 失败 → [TaskQueueUseCases.fail]（分类复用既有 [AttemptErrorCategory]，有限重试）；
     * 执行边界发现"已请求取消" → [TaskQueueUseCases.convergeCancellation]（不强制杀线程）。
     */
    fun runOnce(projectId: ProjectId): TaskQueueItem? {
        val item = queue.claimNext(workerId, projectId) ?: return null

        if (queue.isCancellationRequested(item.taskId)) {
            queue.convergeCancellation(item.taskId)
            return item
        }

        val executor = executors[item.kind]
        if (executor == null) {
            queue.fail(item.taskId, "无可用 TaskExecutor: kind=${item.kind}", AttemptErrorCategory.NON_RETRYABLE)
            return item
        }

        try {
            executor.execute(item)
            if (queue.isCancellationRequested(item.taskId)) {
                queue.convergeCancellation(item.taskId)
            } else {
                queue.complete(item.taskId)
            }
        } catch (e: ApplicationException) {
            queue.fail(item.taskId, describe(e.error), classify(e.error))
        } catch (t: Throwable) {
            val mapped = errorMapper.map(t)
            queue.fail(item.taskId, describe(mapped.error), classify(mapped.error))
        }
        return item
    }

    /**
     * 有界执行（§27：不做复杂线程池）：最多处理 [maxTasks] 个可领取任务后返回。
     *
     * @return 本次处理过的条目（按领取顺序；确定性由队列排序保证）。
     */
    fun drain(projectId: ProjectId, maxTasks: Int = DEFAULT_MAX_TASKS_PER_DRAIN): List<TaskQueueItem> {
        require(maxTasks >= 1) { "maxTasks 必须 >= 1" }
        val processed = mutableListOf<TaskQueueItem>()
        while (processed.size < maxTasks) {
            val item = runOnce(projectId) ?: break
            processed += item
        }
        return processed
    }

    /**
     * 失败分类（**确定性**，复用既有 [AttemptErrorCategory]；不在本阶段新建失败分类体系，§16）。
     *
     * 与既有 WorkflowOrchestrator 的 classify 保持同一策略：Provider 不可用 = 可重试；
     * Provider 缺凭证 = 需人工；其余（含未知）= 不可重试（有界重试上限兜底，禁止无限 retry）。
     */
    private fun classify(error: ApplicationError): AttemptErrorCategory = when (error) {
        is ApplicationError.ProviderUnavailable -> AttemptErrorCategory.RETRYABLE
        is ApplicationError.ProviderCredentialMissing -> AttemptErrorCategory.NEEDS_HUMAN
        else -> AttemptErrorCategory.NON_RETRYABLE
    }

    /** 把类型化错误渲染为 Task.error / 条目 lastError 的可读文案（分类仍以类型为准）。 */
    private fun describe(error: ApplicationError): String = when (error) {
        is ApplicationError.UnknownStorage ->
            "UnknownStorage: ${error.cause.message ?: error.cause::class.simpleName}"

        else -> error.toString()
    }

    companion object {
        /** 单次 drain 的默认上限（有界，不无限循环）。 */
        const val DEFAULT_MAX_TASKS_PER_DRAIN: Int = 16
    }
}