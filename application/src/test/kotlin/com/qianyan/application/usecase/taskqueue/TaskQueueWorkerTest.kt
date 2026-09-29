package com.qianyan.application.usecase.taskqueue

import com.qianyan.application.di.ApplicationContainer
import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.model.task.TaskStatus
import com.qianyan.model.taskqueue.TaskKind
import com.qianyan.model.taskqueue.TaskQueueItemStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * I13 · Worker（§19 / §20 / §21 / §22 / §27 / §28 C·D·E）：claim → execute → complete / fail。
 */
class TaskQueueWorkerTest {

    @Test
    fun `worker completes a task through the registered executor`() {
        val f = taskQueueFixture()
        val projectId = f.newProject()
        val item = f.enqueue(projectId, kind = TaskKind.PROJECT_INDEX_REBUILD)
        var executed: com.qianyan.model.TaskId? = null
        val worker = TaskWorker(
            workerId = "worker-1",
            queue = f.queue,
            executors = mapOf(TaskKind.PROJECT_INDEX_REBUILD to TaskExecutor { executed = it.taskId }),
        )

        val processed = worker.runOnce(projectId)

        assertEquals(item.taskId, processed?.taskId)
        assertEquals(item.taskId, executed, "执行器收到的是被领取条目引用的 Task（Worker 不重造负载）")
        assertEquals(TaskStatus.COMPLETED, f.app.tasks.findById(item.taskId).status)
        assertNull(f.queue.findByTask(item.taskId), "完成后条目出队")
        assertNull(worker.runOnce(projectId), "队列已空：不会重复执行同一 Task")
        f.close()
    }

    @Test
    fun `worker fails a task when no executor is registered for the kind`() {
        val f = taskQueueFixture()
        val projectId = f.newProject()
        // NOVEL_AGENT 本阶段只保留调度键 seam（无执行器）
        val item = f.enqueue(projectId, kind = TaskKind.NOVEL_AGENT)
        val worker = TaskWorker("worker-1", f.queue, executors = emptyMap())

        worker.runOnce(projectId)

        assertEquals(TaskStatus.FAILED, f.app.tasks.findById(item.taskId).status)
        assertNull(f.queue.findByTask(item.taskId), "不可执行 ⇒ 终止失败并出队")
        f.close()
    }

    @Test
    fun `worker schedules a bounded retry for a retryable failure`() {
        val f = taskQueueFixture()
        val projectId = f.newProject()
        val item = f.enqueue(projectId, maxAttempts = 2)
        val worker = TaskWorker(
            workerId = "worker-1",
            queue = f.queue,
            executors = mapOf(
                TaskKind.PROJECT_INDEX_REBUILD to TaskExecutor {
                    throw ApplicationException(ApplicationError.ProviderUnavailable("provider down"))
                },
            ),
        )

        worker.runOnce(projectId)

        val requeued = f.queue.findByTask(item.taskId)
        assertNotNull(requeued)
        assertEquals(TaskQueueItemStatus.QUEUED, requeued.status)
        assertEquals(1, requeued.attempt)
        assertTrue(requeued.lastError!!.contains("ProviderUnavailable"), requeued.lastError!!)
        assertEquals(TaskStatus.RUNNING, f.app.tasks.findById(item.taskId).status, "可重试 ⇒ Task 不进入终态")

        // 重试上限：再次失败即终止
        f.clock.advanceBy(TaskQueueUseCases.DEFAULT_RETRY_BACKOFF_MILLIS)
        worker.runOnce(projectId)
        assertEquals(TaskStatus.FAILED, f.app.tasks.findById(item.taskId).status)
        assertNull(f.queue.findByTask(item.taskId))
        f.close()
    }

    @Test
    fun `worker converges a cancellation requested during execution`() {
        val f = taskQueueFixture()
        val projectId = f.newProject()
        val item = f.enqueue(projectId)
        val worker = TaskWorker(
            workerId = "worker-1",
            queue = f.queue,
            executors = mapOf(TaskKind.PROJECT_INDEX_REBUILD to TaskExecutor { f.queue.cancel(it.taskId) }),
        )

        worker.runOnce(projectId)

        assertEquals(TaskStatus.CANCELLED, f.app.tasks.findById(item.taskId).status)
        assertNull(f.queue.findByTask(item.taskId))
        f.close()
    }

    @Test
    fun `bounded drain processes each claimable task exactly once`() {
        val f = taskQueueFixture()
        val projectId = f.newProject()
        val a = f.enqueue(projectId)
        val b = f.enqueue(projectId)
        val executed = mutableListOf<com.qianyan.model.TaskId>()
        val worker = TaskWorker(
            workerId = "worker-1",
            queue = f.queue,
            executors = mapOf(TaskKind.PROJECT_INDEX_REBUILD to TaskExecutor { executed += it.taskId }),
        )

        val processed = worker.drain(projectId, maxTasks = 5)

        assertEquals(2, processed.size)
        assertEquals(setOf(a.taskId, b.taskId), executed.toSet(), "每个 Task 只执行一次")
        assertTrue(executed.size == executed.distinct().size, "不出现重复执行")
        assertTrue(worker.drain(projectId, maxTasks = 5).isEmpty(), "队列已空 ⇒ 有界结束")
        f.close()
    }

    @Test
    fun `container worker rebuilds the project index through the real application seam`() {
        val f = taskQueueFixture()
        val projectId = f.newProject()
        val novelId = f.app.projects.projectOf(projectId)!!.novelId
        f.app.chapters.createNextChapter("第一章", novelId)
        val item = f.enqueue(projectId, kind = TaskKind.PROJECT_INDEX_REBUILD)

        // 容器的真实 seam：TaskKind → 既有 I12 ProjectIndexUseCases.rebuild（只调用，不重写）
        val worker = TaskWorker(ApplicationContainer.LOCAL_WORKER_ID, f.queue, f.app.taskExecutors())
        worker.runOnce(projectId)

        assertEquals(TaskStatus.COMPLETED, f.app.tasks.findById(item.taskId).status)
        assertTrue(f.app.projectIndex.entries(projectId).isNotEmpty(), "后台重建真的调用了既有 Project Index")
        f.close()
    }
}