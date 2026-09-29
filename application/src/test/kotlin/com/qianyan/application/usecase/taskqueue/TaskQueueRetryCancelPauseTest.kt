package com.qianyan.application.usecase.taskqueue

import com.qianyan.application.error.ApplicationException
import com.qianyan.application.usecase.taskqueue.TaskQueueUseCases.Companion.DEFAULT_RETRY_BACKOFF_MILLIS
import com.qianyan.model.task.TaskStatus
import com.qianyan.model.taskqueue.TaskQueueErrorCodes
import com.qianyan.model.taskqueue.TaskQueueItemStatus
import com.qianyan.model.workflow.AttemptErrorCategory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * I13 · 失败 / 有限重试 / 取消 / 暂停恢复（§15 / §16 / §17 / §18 / §28 E·F·G·H）。
 */
class TaskQueueRetryCancelPauseTest {

    /* ---------------- E. Failure ---------------- */

    @Test
    fun `non retryable failure terminates the task at FAILED and dequeues`() {
        val f = taskQueueFixture()
        val projectId = f.newProject()
        val item = f.enqueue(projectId)
        f.queue.claim(item.taskId, "worker-A", projectId)

        val outcome = f.queue.fail(item.taskId, "不可重试的失败", AttemptErrorCategory.NON_RETRYABLE)

        assertEquals(TaskFailureDisposition.FAILED, outcome.disposition)
        assertEquals(false, outcome.willRetry)
        assertEquals(1, outcome.attempt)
        assertEquals(TaskStatus.FAILED, f.app.tasks.findById(item.taskId).status)
        assertEquals("不可重试的失败", f.app.tasks.findById(item.taskId).error)
        assertNull(f.queue.findByTask(item.taskId), "终止失败后条目出队")
        f.close()
    }

    /* ---------------- F. Retry（有限） ---------------- */

    @Test
    fun `retryable failure requeues with backoff and preserves attempt count`() {
        val f = taskQueueFixture()
        val projectId = f.newProject()
        val item = f.enqueue(projectId, maxAttempts = 3)
        f.queue.claim(item.taskId, "worker-A", projectId)

        val first = f.queue.fail(item.taskId, "boom-1", AttemptErrorCategory.RETRYABLE)

        assertEquals(TaskFailureDisposition.RETRY_SCHEDULED, first.disposition)
        assertEquals(1, first.attempt)
        assertEquals(3, first.maxAttempts)
        // Task 未进入终态（仍 RUNNING，等待重试）—— 重试不是第二套 Workflow
        assertEquals(TaskStatus.RUNNING, f.app.tasks.findById(item.taskId).status)
        val requeued = f.queue.findByTask(item.taskId)!!
        assertEquals(TaskQueueItemStatus.QUEUED, requeued.status)
        assertEquals(1, requeued.attempt)
        assertNull(requeued.claimedBy)
        assertEquals("boom-1", requeued.lastError)

        // 退避生效：未到 availableAt 之前不可领取
        assertNull(f.queue.claimNext("worker-A", projectId))
        val early = assertFailsWith<ApplicationException> { f.queue.claim(item.taskId, "worker-A", projectId) }
        assertTrue(early.message!!.contains(TaskQueueErrorCodes.TASK_NOT_CLAIMABLE), early.message!!)

        f.clock.advanceBy(DEFAULT_RETRY_BACKOFF_MILLIS)
        assertEquals(item.taskId, f.queue.claimNext("worker-A", projectId)?.taskId, "退避到期后重新可领取")
        assertEquals(1, f.queue.findByTask(item.taskId)?.attempt, "attempt 跨重试保留")
        f.close()
    }

    @Test
    fun `retryable failures stop at maxAttempts and then FAIL`() {
        val f = taskQueueFixture()
        val projectId = f.newProject()
        val item = f.enqueue(projectId, maxAttempts = 3)
        f.queue.claim(item.taskId, "worker-A", projectId)

        // attempt 1 → 重试
        assertEquals(TaskFailureDisposition.RETRY_SCHEDULED, f.queue.fail(item.taskId, "b1", AttemptErrorCategory.RETRYABLE).disposition)
        f.clock.advanceBy(DEFAULT_RETRY_BACKOFF_MILLIS)
        f.queue.claim(item.taskId, "worker-A", projectId)
        // attempt 2 → 重试
        assertEquals(TaskFailureDisposition.RETRY_SCHEDULED, f.queue.fail(item.taskId, "b2", AttemptErrorCategory.RETRYABLE).disposition)
        f.clock.advanceBy(DEFAULT_RETRY_BACKOFF_MILLIS)
        f.queue.claim(item.taskId, "worker-A", projectId)
        // attempt 3 = maxAttempts → 终止 FAILED（禁止无限 retry）
        val third = f.queue.fail(item.taskId, "b3", AttemptErrorCategory.RETRYABLE)
        assertEquals(TaskFailureDisposition.FAILED, third.disposition)
        assertEquals(3, third.attempt)
        assertEquals(TaskStatus.FAILED, f.app.tasks.findById(item.taskId).status)
        assertNull(f.queue.findByTask(item.taskId))
        f.close()
    }

    @Test
    fun `explicit retry is bounded and never steals a claimed item`() {
        val f = taskQueueFixture()
        val projectId = f.newProject()
        // maxAttempts = 1 ⇒ 显式 retry 只能成功一次
        val item = f.enqueue(projectId, maxAttempts = 1)

        // 未领取：显式 retry 让条目重新可领取并累计 attempt
        val retried = f.queue.retry(item.taskId)
        assertEquals(1, retried.attempt)
        assertEquals(TaskQueueItemStatus.QUEUED, retried.status)

        // 已达上限
        val reached = assertFailsWith<ApplicationException> { f.queue.retry(item.taskId) }
        assertTrue(reached.message!!.contains(TaskQueueErrorCodes.MAX_ATTEMPTS_REACHED), reached.message!!)

        // 执行中（CLAIMED）不允许被 retry 抢走（否则会被执行两次）
        val f2 = taskQueueFixture()
        val p2 = f2.newProject()
        val i2 = f2.enqueue(p2)
        f2.queue.claim(i2.taskId, "worker-A", p2)
        val claimed = assertFailsWith<ApplicationException> { f2.queue.retry(i2.taskId) }
        assertTrue(claimed.message!!.contains(TaskQueueErrorCodes.TASK_ALREADY_CLAIMED), claimed.message!!)
        f2.close()
        f.close()
    }

    /* ---------------- G. Cancellation ---------------- */

    @Test
    fun `cancelling a queued task cancels immediately`() {
        val f = taskQueueFixture()
        val projectId = f.newProject()
        val item = f.enqueue(projectId)

        val disposition = f.queue.cancel(item.taskId)

        assertEquals(TaskCancelDisposition.CANCELLED, disposition)
        assertEquals(TaskStatus.CANCELLED, f.app.tasks.findById(item.taskId).status)
        assertNull(f.queue.findByTask(item.taskId), "取消后条目出队")
        // 已取消：再次取消是稳定错误
        val again = assertFailsWith<ApplicationException> { f.queue.cancel(item.taskId) }
        assertTrue(again.message!!.contains(TaskQueueErrorCodes.TASK_CANCELLED), again.message!!)
        f.close()
    }

    @Test
    fun `cancelling a claimed task only requests cancellation and converges at the boundary`() {
        val f = taskQueueFixture()
        val projectId = f.newProject()
        val item = f.enqueue(projectId)
        f.queue.claim(item.taskId, "worker-A", projectId)

        val disposition = f.queue.cancel(item.taskId)

        assertEquals(TaskCancelDisposition.CANCELLATION_REQUESTED, disposition)
        assertEquals(true, f.queue.isCancellationRequested(item.taskId))
        // 不强制杀线程：Task 仍 RUNNING，条目仍 CLAIMED，等 Worker 在执行边界收敛
        assertEquals(TaskStatus.RUNNING, f.app.tasks.findById(item.taskId).status)
        assertEquals(TaskQueueItemStatus.CLAIMED, f.queue.findByTask(item.taskId)?.status)
        // 已请求取消的条目不会再被领取
        assertNull(f.queue.claimNext("worker-B", projectId))

        val converged = f.queue.convergeCancellation(item.taskId)
        assertEquals(TaskStatus.CANCELLED, converged.status)
        assertNull(f.queue.findByTask(item.taskId))
        f.close()
    }

    /* ---------------- H. Pause / Resume ---------------- */

    @Test
    fun `pause stops claiming and resume makes it claimable again`() {
        val f = taskQueueFixture()
        val projectId = f.newProject()
        val item = f.enqueue(projectId)
        f.queue.claim(item.taskId, "worker-A", projectId)

        val paused = f.queue.pause(item.taskId)
        assertEquals(TaskStatus.PAUSED, f.app.tasks.findById(item.taskId).status)
        assertEquals(true, paused.paused)
        assertEquals(TaskQueueItemStatus.QUEUED, paused.status)
        assertNull(paused.claimedBy, "暂停释放领取")
        assertNull(f.queue.claimNext("worker-B", projectId), "PAUSED ⇒ 不再 claim")

        val resumed = f.queue.resume(item.taskId)
        assertEquals(TaskStatus.RUNNING, f.app.tasks.findById(item.taskId).status)
        assertEquals(false, resumed.paused)
        assertEquals(item.taskId, f.queue.claimNext("worker-B", projectId)?.taskId, "恢复后重新可领取")

        // 状态不匹配是稳定错误
        val notPaused = assertFailsWith<ApplicationException> { f.queue.resume(item.taskId) }
        assertTrue(notPaused.message!!.contains(TaskQueueErrorCodes.TASK_PAUSED), notPaused.message!!)
        f.close()
    }
}