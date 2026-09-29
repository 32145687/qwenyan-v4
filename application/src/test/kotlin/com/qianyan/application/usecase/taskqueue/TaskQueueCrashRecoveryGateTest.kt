package com.qianyan.application.usecase.taskqueue

import com.qianyan.application.error.ApplicationException
import com.qianyan.application.usecase.taskqueue.TaskQueueUseCases.Companion.DEFAULT_LEASE_MILLIS
import com.qianyan.application.usecase.taskqueue.TaskQueueUseCases.Companion.DEFAULT_RETRY_BACKOFF_MILLIS
import com.qianyan.model.TaskId
import com.qianyan.model.task.TaskStatus
import com.qianyan.model.taskqueue.TaskKind
import com.qianyan.model.taskqueue.TaskQueueErrorCodes
import com.qianyan.model.taskqueue.TaskQueueItemStatus
import com.qianyan.model.workflow.AttemptErrorCategory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * I13 Final Recovery Gate · Crash Recovery 与既有 Task 生命周期的**闭环**回归。
 *
 * 审计结论（真实代码）：
 *  - `TaskStateMachine.START` 只允许 `PENDING → RUNNING`；
 *  - `TaskQueueUseCases.enterRunning` 在 `TaskStatus.RUNNING` 时**显式 no-op**（不重复 start）；
 *  ⇒ Worker 崩溃后 `Task = RUNNING / Queue = CLAIMED`，lease 过期回收为 `QUEUED` 后，
 *     新 Worker 领取**不会**再次 `start()`，可直接继续执行并 `complete()`。闭环成立，无需改代码。
 *
 * 本类即该闭环的回归证据（Case A–E + Retry 一致性）。
 */
class TaskQueueCrashRecoveryGateTest {

    /** Case A：RUNNING Task → lease 过期 → QUEUED → 新 Worker 领取 → 执行 → COMPLETED。 */
    @Test
    fun `case A crashed worker task is continued to completion by a new worker`() {
        val f = taskQueueFixture()
        val projectId = f.newProject()
        val item = f.enqueue(projectId)

        // 正常起步：claim 让 Task 进入 RUNNING（既有状态机 PENDING → RUNNING）
        f.queue.claim(item.taskId, "worker-crashed", projectId)
        assertEquals(TaskStatus.RUNNING, f.app.tasks.findById(item.taskId).status)

        // Worker 崩溃：lease 过期 → 条目回收为 QUEUED；Task **仍为 RUNNING**（不新建第二套 Task 状态机）
        f.clock.advanceBy(DEFAULT_LEASE_MILLIS + 1)
        assertEquals(1, f.queue.recoverExpiredLeases())
        val recovered = f.queue.findByTask(item.taskId)!!
        assertEquals(TaskQueueItemStatus.QUEUED, recovered.status)
        assertNull(recovered.claimedBy)
        assertEquals(TaskStatus.RUNNING, f.app.tasks.findById(item.taskId).status)

        // 新 Worker 合法继续执行：enterRunning 对 RUNNING 是 no-op ⇒ 不会重复 start（不会 InvalidTaskStateTransition）
        val executed = mutableListOf<TaskId>()
        val worker = TaskWorker(
            workerId = "worker-new",
            queue = f.queue,
            executors = mapOf(TaskKind.PROJECT_INDEX_REBUILD to TaskExecutor { executed += it.taskId }),
        )
        val processed = worker.runOnce(projectId)

        assertEquals(item.taskId, processed?.taskId)
        assertEquals(listOf(item.taskId), executed, "恢复后的 Task 被新 Worker 真正执行")
        assertEquals(TaskStatus.COMPLETED, f.app.tasks.findById(item.taskId).status)
        assertNull(f.queue.findByTask(item.taskId), "完成后出队")
        assertEquals(0, countRows(f.handle, "TaskQueueItem"))
        f.close()
    }

    /** Case B：RUNNING Task → lease 过期 + cancelRequested → CANCELLED（领取者已消失，无法再收敛于执行边界）。 */
    @Test
    fun `case B expired lease with a pending cancel request converges to cancelled`() {
        val f = taskQueueFixture()
        val projectId = f.newProject()
        val item = f.enqueue(projectId)
        f.queue.claim(item.taskId, "worker-crashed", projectId)

        // 执行中收到取消：只置"已请求取消"，Task 仍 RUNNING（不强制杀线程）
        assertEquals(TaskCancelDisposition.CANCELLATION_REQUESTED, f.queue.cancel(item.taskId))
        assertEquals(TaskStatus.RUNNING, f.app.tasks.findById(item.taskId).status)
        assertNull(f.queue.claimNext("worker-new", projectId), "已请求取消的条目不会被再领取")

        f.clock.advanceBy(DEFAULT_LEASE_MILLIS + 1)
        assertEquals(1, f.queue.recoverExpiredLeases())

        assertEquals(TaskStatus.CANCELLED, f.app.tasks.findById(item.taskId).status)
        assertNull(f.queue.findByTask(item.taskId), "取消收敛后出队，不留永久不可领取条目")
        assertEquals(0, countRows(f.handle, "TaskQueueItem"))
        f.close()
    }

    /** Case C：lease 未过期 ⇒ 第二 Worker 不得抢占。 */
    @Test
    fun `case C a live lease blocks any second worker`() {
        val f = taskQueueFixture()
        val projectId = f.newProject()
        val item = f.enqueue(projectId)
        f.queue.claim(item.taskId, "worker-A", projectId)

        // lease 未过期：恢复不动作
        assertEquals(0, f.queue.recoverExpiredLeases())
        assertEquals(TaskQueueItemStatus.CLAIMED, f.queue.findByTask(item.taskId)?.status)

        val stolen = assertFailsWith<ApplicationException> { f.queue.claim(item.taskId, "worker-B", projectId) }
        assertTrue(stolen.message!!.contains(TaskQueueErrorCodes.TASK_ALREADY_CLAIMED), stolen.message!!)
        assertNull(f.queue.claimNext("worker-B", projectId), "CLAIMED 条目不会被 claimNext 交给他人")
        assertEquals("worker-A", f.queue.findByTask(item.taskId)?.claimedBy, "claim 未被覆盖")
        assertEquals(TaskStatus.RUNNING, f.app.tasks.findById(item.taskId).status)
        f.close()
    }

    /** Case D：恢复不产生第二条 Queue Item，也不产生重复执行。 */
    @Test
    fun `case D recovery never duplicates the queue item or the execution`() {
        val f = taskQueueFixture()
        val projectId = f.newProject()
        val item = f.enqueue(projectId)
        f.queue.claim(item.taskId, "worker-crashed", projectId)

        f.clock.advanceBy(DEFAULT_LEASE_MILLIS + 1)
        f.queue.recoverExpiredLeases()
        // 幂等：重复恢复不再回收（条目已不是 CLAIMED），条目数恒为 1
        assertEquals(0, f.queue.recoverExpiredLeases())
        assertEquals(1, countRows(f.handle, "TaskQueueItem"), "恢复不得生成第二条 Queue Item")
        assertEquals(1, f.queue.listByProject(projectId).size)

        val executed = mutableListOf<TaskId>()
        val worker = TaskWorker(
            workerId = "worker-new",
            queue = f.queue,
            executors = mapOf(TaskKind.PROJECT_INDEX_REBUILD to TaskExecutor { executed += it.taskId }),
        )
        worker.runOnce(projectId)

        assertEquals(listOf(item.taskId), executed, "只执行一次（无重复执行）")
        assertEquals(executed.size, executed.distinct().size)
        assertNull(worker.runOnce(projectId), "队列已空：不会再次执行同一 Task")
        assertTrue(f.queue.listByProject(projectId).isEmpty())
        f.close()
    }

    /** Case E：Project A 的恢复任务不能被 Project B 的 Worker 领取。 */
    @Test
    fun `case E a recovered task stays isolated to its own project`() {
        val f = taskQueueFixture()
        val projectA = f.newProject("项目 A")
        val projectB = f.newProject("项目 B")
        val item = f.enqueue(projectA)
        f.queue.claim(item.taskId, "worker-A", projectA)

        f.clock.advanceBy(DEFAULT_LEASE_MILLIS + 1)
        assertEquals(1, f.queue.recoverExpiredLeases())

        // 恢复后仍严格隔离
        assertNull(f.queue.claimNext("worker-B", projectB), "Project B Worker 领取不到 Project A 的恢复任务")
        val cross = assertFailsWith<ApplicationException> { f.queue.claim(item.taskId, "worker-B", projectB) }
        assertTrue(cross.message!!.contains(TaskQueueErrorCodes.TASK_NOT_FOUND), cross.message!!)
        assertTrue(f.queue.listByProject(projectB).isEmpty())

        // 本 Project 的 Worker 可以正常继续
        val executed = mutableListOf<TaskId>()
        val worker = TaskWorker(
            workerId = "worker-A",
            queue = f.queue,
            executors = mapOf(TaskKind.PROJECT_INDEX_REBUILD to TaskExecutor { executed += it.taskId }),
        )
        assertEquals(item.taskId, worker.runOnce(projectA)?.taskId)
        assertEquals(listOf(item.taskId), executed)
        assertEquals(TaskStatus.COMPLETED, f.app.tasks.findById(item.taskId).status)
        f.close()
    }

    /**
     * Retry 与 Crash Recovery 的一致性（§6）：RETRYABLE 失败后 `Queue = QUEUED / Task = RUNNING`，
     * 下一次领取走 `enterRunning` 的 `RUNNING -> Unit` 显式路径（**不重复 start**），随后可正常 complete。
     */
    @Test
    fun `retryable requeue keeps the task running so the next claim never re starts it`() {
        val f = taskQueueFixture()
        val projectId = f.newProject()
        val item = f.enqueue(projectId, maxAttempts = 2)
        f.queue.claim(item.taskId, "worker-1", projectId)

        val outcome = f.queue.fail(item.taskId, "transient", AttemptErrorCategory.RETRYABLE)
        assertEquals(TaskFailureDisposition.RETRY_SCHEDULED, outcome.disposition)
        assertEquals(TaskQueueItemStatus.QUEUED, f.queue.findByTask(item.taskId)?.status)
        assertEquals(TaskStatus.RUNNING, f.app.tasks.findById(item.taskId).status, "有意设计：可重试失败不进入终态")

        // 退避到期后由新 Worker 直接继续执行（不再 start ⇒ 不会 InvalidTaskStateTransition）
        f.clock.advanceBy(DEFAULT_RETRY_BACKOFF_MILLIS)
        val executed = mutableListOf<TaskId>()
        val worker = TaskWorker(
            workerId = "worker-2",
            queue = f.queue,
            executors = mapOf(TaskKind.PROJECT_INDEX_REBUILD to TaskExecutor { executed += it.taskId }),
        )
        assertEquals(item.taskId, worker.runOnce(projectId)?.taskId)
        assertEquals(listOf(item.taskId), executed)
        assertEquals(TaskStatus.COMPLETED, f.app.tasks.findById(item.taskId).status)
        assertNull(f.queue.findByTask(item.taskId), "完成后条目已出队")
        f.close()
    }
}