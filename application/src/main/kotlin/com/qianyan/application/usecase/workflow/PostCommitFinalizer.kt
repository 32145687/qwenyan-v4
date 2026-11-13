package com.qianyan.application.usecase.workflow

import com.qianyan.application.usecase.story.worldmodelupdate.WorldModelUpdateException
import com.qianyan.application.usecase.story.worldmodelupdate.WorldModelUpdateUseCases
import com.qianyan.application.usecase.task.TaskManagerUseCases
import com.qianyan.model.TaskId
import com.qianyan.model.commit.CommitHistoryId
import com.qianyan.model.task.TaskStatus
import com.qianyan.model.task.TaskType
import com.qianyan.model.workflow.WorkflowHumanGateId
import com.qianyan.model.workflow.WorkflowId
import com.qianyan.storage.repository.CommitHistoryRepository
import com.qianyan.storage.repository.TaskRepository
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** T-O108（A-32）：批准后后台收尾的用户可见阶段（UI 只读投影，不是新状态机）。 */
enum class PostCommitStage { NONE, PENDING, FAILED }

/**
 * T-O108（A-32）· **提交后收尾器**：把 WMU 与 runForward（含 KNOWLEDGE_UPDATE）从批准调用链移到后台。
 *
 * 持久承载：`Task(type=BACKGROUND)` + 一条 `Checkpoint(stage=[STAGE], snapshot={historyId,gateId,workflowId,chapterId})`。
 * 崩溃恢复：`resumeOnStartup()` 扫描未完成的 BACKGROUND 任务（不做「无候选即待处理」的推导，避免误跑历史）。
 * 幂等：WMU 按 historyId replay（WorldModelUpdateUseCases.onCommit）；runForward 由 Step 收敛保证。
 * 失败：落 Task.FAILED（不自动重试），UI 经 [stateOf] 投影为「更新失败」并提供手动重试。
 * 并发：本类方法 `@Synchronized`（进程内单实例串行），避免同一提交被两次收尾。
 */
class PostCommitFinalizer(
    private val tasks: TaskManagerUseCases,
    private val taskRepository: TaskRepository,
    private val history: CommitHistoryRepository,
    private val worldModel: WorldModelUpdateUseCases,
    private val orchestrator: WorkflowOrchestrator,
) {

    /**
     * 投递一个提交后收尾任务（幂等：同 gateId（优先）或 historyId 的未完成任务直接复用）。
     *
     * @param gateId 批准的人工作物门（补偿路径只拿得到它；finalizer 用 `getByGate` 反查提交记录）
     */
    fun enqueue(historyId: String?, gateId: String?, workflowId: String, chapterId: String): TaskId {
        findUnfinished(gateId, historyId)?.let { return it }
        val taskId = tasks.create(TaskType.BACKGROUND)
        tasks.saveCheckpoint(
            taskId,
            STAGE,
            buildJsonObject {
                put("stage", STAGE)
                historyId?.let { put("historyId", it) }
                gateId?.let { put("gateId", it) }
                put("workflowId", workflowId)
                put("chapterId", chapterId)
            },
        )
        return taskId
    }

    /**
     * 执行该章节的全部未完成任务（同步；调用方负责放到 IO 线程）。
     * 桌面端在「UI 已恢复之后」调用，因此不阻塞交互。
     */
    @Synchronized
    fun runPending(chapterId: String): Int {
        val pending = unfinishedTasks().filter { payloadOf(it)?.chapterId == chapterId }
        var ran = 0
        pending.forEach { task -> if (run(task.taskId)) ran++ }
        return ran
    }

    /**
     * 启动恢复：扫描未完成（PENDING / RUNNING）的 BACKGROUND 任务并逐条执行。
     * 同步执行（调用方应在 IO 线程），返回实际执行条数。
     */
    @Synchronized
    fun resumeOnStartup(): Int {
        var ran = 0
        unfinishedTasks().forEach { task ->
            if (run(task.taskId)) ran++
        }
        return ran
    }

    /**
     * 执行一个收尾任务：WMU（按 historyId / gateId 反查提交记录，replay 幂等）→ runForward → complete。
     * 返回 true 表示本轮推进成功；失败落 Task.FAILED 且不抛出（UI 协程不受影响）。
     */
    fun run(taskId: TaskId): Boolean {
        val task = runCatching { tasks.findById(taskId) }.getOrNull() ?: return false
        if (task.status != TaskStatus.PENDING && task.status != TaskStatus.RUNNING) return false
        if (task.status == TaskStatus.PENDING) {
            runCatching { tasks.start(taskId) }.onFailure { return false }
        }
        val payload = payloadOf(task) ?: run {
            fail(taskId, "POST_COMMIT 负载缺失")
            return false
        }
        return try {
            val entry = payload.historyId?.let { history.getById(CommitHistoryId(it)) }
                ?: payload.gateId?.let { history.getByGate(WorkflowHumanGateId(it)) }
            if (entry != null) {
                try {
                    worldModel.onCommit(entry)
                } catch (e: WorldModelUpdateException) {
                    // T-O110：后台路径与提交路径同一失败语义 —— 类型化失败必须留可追溯失败标记
                    // （recordFailure 自身按 historyId 幂等；写标记失败不得掩盖原始错误）。
                    runCatching { worldModel.recordFailure(entry, e.message ?: "world model update failed") }
                    throw e
                }
            }
            val result = orchestrator.runForward(WorkflowId(payload.workflowId))
            if (result.status == com.qianyan.model.workflow.WorkflowStatus.FAILED) {
                fail(taskId, "Workflow 收尾失败（已终止）：需要重新发起任务")
                return false
            }
            tasks.complete(taskId)
            true
        } catch (t: Throwable) {
            fail(taskId, t.message ?: t::class.simpleName ?: "post-commit failed")
            false
        }
    }

    /** 手动重试：新建一个后台任务（不复用 FAILED），返回新 TaskId（无负载时返回 null）。 */
    fun retry(gateId: String?, historyId: String?, workflowId: String, chapterId: String): TaskId =
        enqueue(historyId, gateId, workflowId, chapterId)

    /** 按该章节最近一个 POST_COMMIT 负载重试（工作流已是终态时也会入队，失败会再次可见）。 */
    @Synchronized
    fun retryChapter(chapterId: String): TaskId? {
        val latest = postCommitTasks().firstOrNull { payloadOf(it)?.chapterId == chapterId } ?: return null
        val p = payloadOf(latest) ?: return null
        return enqueue(p.historyId, p.gateId, p.workflowId, p.chapterId)
    }

    /** UI 投影：该章节最近一个 POST_COMMIT 任务的状态。 */
    fun stateOf(chapterId: String): PostCommitStage {
        val latest = postCommitTasks()
            .filter { payloadOf(it)?.chapterId == chapterId }
            .firstOrNull() ?: return PostCommitStage.NONE
        return when (latest.status) {
            TaskStatus.PENDING, TaskStatus.RUNNING, TaskStatus.PAUSED -> PostCommitStage.PENDING
            TaskStatus.FAILED -> PostCommitStage.FAILED
            else -> PostCommitStage.NONE
        }
    }

    /* ---------- 内部 ---------- */

    private fun unfinishedTasks() = postCommitTasks().filter {
        it.status == TaskStatus.PENDING || it.status == TaskStatus.RUNNING
    }

    /** 按 checkpoint 倒序（created_at DESC）取 POST_COMMIT 任务；不依赖 Task 表新增列/Schema。 */
    private fun postCommitTasks() =
        taskRepository.findCheckpointsByStage(STAGE, SCAN_LIMIT)
            .map { it.taskId }
            .distinct()
            .mapNotNull { id -> runCatching { taskRepository.findById(id) }.getOrNull() }

    private fun findUnfinished(gateId: String?, historyId: String?): TaskId? =
        unfinishedTasks().firstOrNull { task ->
            val p = payloadOf(task) ?: return@firstOrNull false
            (gateId != null && p.gateId == gateId) || (gateId == null && historyId != null && p.historyId == historyId)
        }?.taskId

    private fun payloadOf(task: com.qianyan.model.task.Task): Payload? {
        val snapshot: JsonObject = taskRepository.findLatestCheckpoint(task.taskId)?.snapshot ?: return null
        val workflowId = snapshot["workflowId"]?.jsonPrimitive?.contentOrNull ?: return null
        val chapterId = snapshot["chapterId"]?.jsonPrimitive?.contentOrNull ?: return null
        return Payload(
            historyId = snapshot["historyId"]?.jsonPrimitive?.contentOrNull,
            gateId = snapshot["gateId"]?.jsonPrimitive?.contentOrNull,
            workflowId = workflowId,
            chapterId = chapterId,
        )
    }

    private fun fail(taskId: TaskId, message: String) {
        runCatching { tasks.fail(taskId, message.take(MAX_ERROR_LENGTH)) }
    }

    private data class Payload(
        val historyId: String?,
        val gateId: String?,
        val workflowId: String,
        val chapterId: String,
    )

    companion object {
        const val STAGE = "POST_COMMIT"
        private const val SCAN_LIMIT = 200L
        private const val MAX_ERROR_LENGTH = 200
    }
}
