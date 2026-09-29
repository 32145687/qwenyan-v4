package com.qianyan.application.usecase.taskqueue

import com.qianyan.application.error.ApplicationException
import com.qianyan.model.ProjectId
import com.qianyan.model.TaskId
import com.qianyan.model.task.TaskStatus
import com.qianyan.model.taskqueue.TaskKind
import com.qianyan.model.taskqueue.TaskPriority
import com.qianyan.model.taskqueue.TaskQueueErrorCodes
import com.qianyan.model.taskqueue.TaskQueueItem
import com.qianyan.model.taskqueue.TaskQueueItemId
import com.qianyan.model.taskqueue.TaskQueueItemStatus
import com.qianyan.model.taskqueue.TaskQueueOrdering
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * I13 · 入队 / 确定性排序 / 原子领取 / 完成 / 重复入队（§6 / §7 / §13 / §28 A·B·C·D·L）。
 */
class TaskQueueEnqueueClaimTest {

    @Test
    fun `enqueue queues a pending background task without touching task lifecycle`() {
        val f = taskQueueFixture()
        val projectId = f.newProject()
        val taskId = f.newTask()

        val item = f.queue.enqueue(taskId, projectId, TaskKind.PROJECT_INDEX_REBUILD, TaskPriority.NORMAL)

        assertEquals(taskId, item.taskId)
        assertEquals(projectId, item.projectId)
        assertEquals(TaskKind.PROJECT_INDEX_REBUILD, item.kind)
        assertEquals(TaskQueueItemStatus.QUEUED, item.status)
        assertEquals(0, item.attempt)
        assertEquals(TaskQueueItem.DEFAULT_MAX_ATTEMPTS, item.maxAttempts)
        assertNull(item.claimedBy)
        assertTrue(item.isClaimable)

        // Task 仍是 PENDING（入队不改写 Task 生命周期；只有领取才 start）
        assertEquals(TaskStatus.PENDING, f.app.tasks.findById(taskId).status)
        assertEquals(item, f.queue.findByTask(taskId))
        assertEquals(listOf(item.taskId), f.queue.listByProject(projectId).map { it.taskId })
        f.close()
    }

    @Test
    fun `duplicate enqueue of the same task is rejected`() {
        val f = taskQueueFixture()
        val projectId = f.newProject()
        val taskId = f.newTask()
        f.queue.enqueue(taskId, projectId, TaskKind.PROJECT_INDEX_REBUILD)

        val error = assertFailsWith<ApplicationException> {
            f.queue.enqueue(taskId, projectId, TaskKind.PROJECT_INDEX_REBUILD)
        }
        assertTrue(error.message!!.contains(TaskQueueErrorCodes.TASK_ALREADY_QUEUED), error.message!!)
        // 队列里仍只有一条（不会产生不可控重复执行）
        assertEquals(1, f.queue.listByProject(projectId).size)
        f.close()
    }

    @Test
    fun `enqueue rejects a task that is not pending`() {
        val f = taskQueueFixture()
        val projectId = f.newProject()
        val taskId = f.newTask()
        f.app.tasks.start(taskId) // PENDING → RUNNING

        val error = assertFailsWith<ApplicationException> {
            f.queue.enqueue(taskId, projectId, TaskKind.PROJECT_INDEX_REBUILD)
        }
        assertTrue(error.message!!.contains(TaskQueueErrorCodes.TASK_NOT_CLAIMABLE), error.message!!)
        f.close()
    }

    @Test
    fun `ordering is deterministic by priority availability queuedAt and taskId`() {
        val base = TASK_QUEUE_FIXED_INSTANT
        val later = Instant.fromEpochMilliseconds(base.toEpochMilliseconds() + 1_000)

        // priority DESC 优先于一切：LOW 即使时间最早也排最后
        val high = pureItem("t-high", TaskPriority.HIGH, later, later)
        val normal = pureItem("t-normal", TaskPriority.NORMAL, base, base)
        val low = pureItem("t-low", TaskPriority.LOW, base, base)
        assertEquals(
            listOf("t-high", "t-normal", "t-low"),
            TaskQueueOrdering.sorted(listOf(low, normal, high)).map { it.taskId.value },
        )

        // 同 priority：availableAt ASC → queuedAt ASC → taskId ASC
        val a1 = pureItem("t-a1", TaskPriority.NORMAL, base, base)
        val a2 = pureItem("t-a2", TaskPriority.NORMAL, base, base)
        val early = pureItem("t-zz", TaskPriority.NORMAL, base, later)
        val laterAvail = pureItem("t-late", TaskPriority.NORMAL, later, base)
        assertEquals(
            listOf("t-a1", "t-a2", "t-zz", "t-late"),
            TaskQueueOrdering.sorted(listOf(laterAvail, early, a2, a1)).map { it.taskId.value },
        )

        // 相同输入的不同排列 ⇒ 相同输出（不依赖 HashMap 迭代序 / 插入序）
        val permA = TaskQueueOrdering.sorted(listOf(high, normal, low, a1, a2, early, laterAvail))
        val permB = TaskQueueOrdering.sorted(listOf(laterAvail, a2, low, a1, high, early, normal))
        assertEquals(permA.map { it.taskId.value }, permB.map { it.taskId.value })
    }

    @Test
    fun `claimNext picks the highest priority claimable item first`() {
        val f = taskQueueFixture()
        val projectId = f.newProject()
        val low = f.enqueue(projectId, priority = TaskPriority.LOW)
        val high = f.enqueue(projectId, priority = TaskPriority.HIGH)
        val normal = f.enqueue(projectId, priority = TaskPriority.NORMAL)

        val first = f.queue.claimNext("worker-1", projectId)
        val second = f.queue.claimNext("worker-1", projectId)
        val third = f.queue.claimNext("worker-1", projectId)

        assertEquals(high.taskId, first?.taskId)
        assertEquals(normal.taskId, second?.taskId)
        assertEquals(low.taskId, third?.taskId)
        assertNull(f.queue.claimNext("worker-1", projectId), "队列已空")
        // 领取即让 Task 进入 RUNNING（复用既有状态机）
        assertEquals(TaskStatus.RUNNING, f.app.tasks.findById(high.taskId).status)
        f.close()
    }

    @Test
    fun `claim is atomic and a second worker cannot claim the same task`() {
        val f = taskQueueFixture()
        val projectId = f.newProject()
        val item = f.enqueue(projectId)
        val other = f.enqueue(projectId)

        val claimed = f.queue.claim(item.taskId, "worker-A", projectId)
        assertEquals(TaskQueueItemStatus.CLAIMED, claimed.status)
        assertEquals("worker-A", claimed.claimedBy)
        assertNotNull(claimed.claimedAt)
        assertEquals(TaskStatus.RUNNING, f.app.tasks.findById(item.taskId).status)

        val error = assertFailsWith<ApplicationException> {
            f.queue.claim(item.taskId, "worker-B", projectId)
        }
        assertTrue(error.message!!.contains(TaskQueueErrorCodes.TASK_ALREADY_CLAIMED), error.message!!)

        // claimNext 不会再把已领取条目（或被他人领取的条目）交出去
        val next = f.queue.claimNext("worker-B", projectId)
        assertEquals(other.taskId, next?.taskId)
        assertNull(f.queue.claimNext("worker-B", projectId))
        assertEquals("worker-A", f.queue.findByTask(item.taskId)?.claimedBy, "claim 未被 worker-B 覆盖")
        f.close()
    }

    @Test
    fun `complete finishes the task and dequeues the item`() {
        val f = taskQueueFixture()
        val projectId = f.newProject()
        val item = f.enqueue(projectId)
        f.queue.claim(item.taskId, "worker-A", projectId)

        val done = f.queue.complete(item.taskId)

        assertEquals(TaskStatus.COMPLETED, done.status)
        assertEquals(1f, done.progress)
        assertEquals(TaskStatus.COMPLETED, f.app.tasks.findById(item.taskId).status)
        assertNull(f.queue.findByTask(item.taskId), "完成后条目出队")
        assertTrue(f.queue.listByProject(projectId).isEmpty())
        f.close()
    }

    private fun pureItem(
        taskId: String,
        priority: TaskPriority,
        availableAt: Instant,
        queuedAt: Instant,
    ): TaskQueueItem = TaskQueueItem(
        queueItemId = TaskQueueItemId("q-$taskId"),
        taskId = TaskId(taskId),
        projectId = ProjectId("p-pure"),
        kind = TaskKind.PROJECT_INDEX_REBUILD,
        priority = priority,
        queuedAt = queuedAt,
        availableAt = availableAt,
    )
}