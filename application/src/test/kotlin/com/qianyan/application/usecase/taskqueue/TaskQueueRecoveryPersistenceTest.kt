package com.qianyan.application.usecase.taskqueue

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.qianyan.application.di.ApplicationContainer
import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.application.usecase.taskqueue.TaskQueueUseCases.Companion.DEFAULT_LEASE_MILLIS
import com.qianyan.model.task.TaskStatus
import com.qianyan.model.task.TaskType
import com.qianyan.model.taskqueue.TaskKind
import com.qianyan.model.taskqueue.TaskPriority
import com.qianyan.model.taskqueue.TaskQueueErrorCodes
import com.qianyan.model.taskqueue.TaskQueueItemStatus
import com.qianyan.provider.impl.MockLLMGateway
import com.qianyan.storage.db.QianyanDbFactory
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * I13 · 崩溃恢复 / 本地持久化 / Project 隔离 / Canonical 隔离（§11 / §25 / §28 I·J·K·M）。
 */
class TaskQueueRecoveryPersistenceTest {

    /* ---------------- I. Crash recovery ---------------- */

    @Test
    fun `expired lease returns a claimed item to QUEUED`() {
        val f = taskQueueFixture()
        val projectId = f.newProject()
        val item = f.enqueue(projectId)
        f.queue.claim(item.taskId, "worker-A", projectId)

        // 未过期：不动
        assertEquals(0, f.queue.recoverExpiredLeases())
        assertEquals(TaskQueueItemStatus.CLAIMED, f.queue.findByTask(item.taskId)?.status)

        // Worker 崩溃：lease 过期后必须能恢复（不留永久 CLAIMED）
        f.clock.advanceBy(DEFAULT_LEASE_MILLIS + 1)
        assertEquals(1, f.queue.recoverExpiredLeases())

        val recovered = f.queue.findByTask(item.taskId)!!
        assertEquals(TaskQueueItemStatus.QUEUED, recovered.status)
        assertNull(recovered.claimedBy)
        assertNull(recovered.claimedAt)
        assertEquals(item.taskId, f.queue.claimNext("worker-B", projectId)?.taskId, "恢复后可由其他 worker 领取")
        f.close()
    }

    @Test
    fun `expired lease with a cancellation request converges to CANCELLED`() {
        val f = taskQueueFixture()
        val projectId = f.newProject()
        val item = f.enqueue(projectId)
        f.queue.claim(item.taskId, "worker-A", projectId)
        f.queue.cancel(item.taskId) // 只置"已请求取消"

        f.clock.advanceBy(DEFAULT_LEASE_MILLIS + 1)
        assertEquals(1, f.queue.recoverExpiredLeases())

        // 领取者已消失：取消请求收敛为终态 CANCELLED，条目出队（不会永远停留在不可领取状态）
        assertEquals(TaskStatus.CANCELLED, f.app.tasks.findById(item.taskId).status)
        assertNull(f.queue.findByTask(item.taskId))
        f.close()
    }

    /* ---------------- K. Persistence ---------------- */

    @Test
    fun `queue items survive a database reopen`() {
        val dir = Files.createTempDirectory("qianyan-i13-persistence")
        val url = "jdbc:sqlite:${dir.resolve("qianyan.db").toAbsolutePath()}"

        val firstHandle = QianyanDbFactory.open(url)
        val first = ApplicationContainer.fromDriver(firstHandle.driver, MockLLMGateway())
        val projectId = first.projects.createProject(title = "持久化之书").projectId
        val taskId = first.tasks.create(TaskType.BACKGROUND)
        first.taskQueue.enqueue(taskId, projectId, TaskKind.PROJECT_INDEX_REBUILD, TaskPriority.HIGH)
        firstHandle.driver.close()

        val secondHandle = QianyanDbFactory.open(url)
        val second = ApplicationContainer.fromDriver(secondHandle.driver, MockLLMGateway())
        val reloaded = second.taskQueue.findByTask(taskId)
        assertNotNull(reloaded, "进程重启后队列条目不应丢失")
        assertEquals(TaskQueueItemStatus.QUEUED, reloaded.status)
        assertEquals(TaskPriority.HIGH, reloaded.priority)
        assertEquals(projectId, reloaded.projectId)
        assertEquals(TaskKind.PROJECT_INDEX_REBUILD, reloaded.kind)
        // 重开后仍可正常领取（本地优先：不依赖外部中间件）
        assertEquals(taskId, second.taskQueue.claimNext("worker-1", projectId)?.taskId)
        secondHandle.driver.close()
    }

    /* ---------------- J. Project isolation ---------------- */

    @Test
    fun `a worker of one project can never claim another project task`() {
        val f = taskQueueFixture()
        val projectA = f.newProject("项目 A")
        val projectB = f.newProject("项目 B")
        val itemA = f.enqueue(projectA)

        assertNull(f.queue.claimNext("worker-B", projectB), "Project B 的 worker 领取不到 Project A 的任务")
        val error = assertFailsWith<ApplicationException> { f.queue.claim(itemA.taskId, "worker-B", projectB) }
        assertTrue(error.message!!.contains(TaskQueueErrorCodes.TASK_NOT_FOUND), error.message!!)
        assertTrue(
            f.queue.listByProject(projectB).isEmpty(),
            "Project B 队列不包含 Project A 的条目",
        )

        assertEquals(itemA.taskId, f.queue.claimNext("worker-A", projectA)?.taskId, "本 Project 的 worker 正常领取")
        f.close()
    }

    /* ---------------- M. Canonical isolation ---------------- */

    @Test
    fun `queue operations never modify canonical data`() {
        val f = taskQueueFixture()
        val projectId = f.newProject()
        val novelId = f.app.projects.projectOf(projectId)!!.novelId
        f.app.chapters.createNextChapter("第一章", novelId)
        f.app.chapters.createNextChapter("第二章", novelId)

        val tables = listOf("Novel", "Chapter", "ChapterDraft", "StoryFoundation", "CommitHistory", "ProjectState")
        val before = tables.associateWith { countRows(f.handle, it) }

        val first = f.enqueue(projectId, priority = TaskPriority.HIGH)
        val second = f.enqueue(projectId)
        val third = f.enqueue(projectId)
        f.queue.claimNext("worker-A", projectId)
        f.queue.claim(second.taskId, "worker-A", projectId)
        f.queue.fail(second.taskId, "失败但不重试")
        f.queue.cancel(third.taskId)
        f.queue.pause(first.taskId)
        f.queue.resume(first.taskId)
        f.queue.complete(first.taskId)
        f.queue.recoverExpiredLeases()

        tables.forEach { table ->
            assertEquals(before[table], countRows(f.handle, table), "$table 行数不得变化（队列只写 Task / TaskQueueItem）")
        }
        assertEquals(0, countRows(f.handle, "TaskQueueItem"), "队列自身写入应已随完成 / 取消 / 失败出队收敛")
        f.close()
    }

    /* ---------------- 边界：不存在 / 越界 ---------------- */

    @Test
    fun `unknown task fails with a stable code`() {
        val f = taskQueueFixture()
        val projectId = f.newProject()
        f.newTask() // 建一个真实 Task 但不入队

        val error = assertFailsWith<ApplicationException> {
            f.queue.claim(com.qianyan.model.TaskId("t-missing"), "worker-A", projectId)
        }
        assertTrue(error.message!!.contains(TaskQueueErrorCodes.TASK_NOT_FOUND), error.message!!)
        f.close()
    }

    @Test
    fun `enqueue on an unknown task fails with the existing task not found error`() {
        val f = taskQueueFixture()
        val projectId = f.newProject()

        val error = assertFailsWith<ApplicationException> {
            f.queue.enqueue(com.qianyan.model.TaskId("t-missing"), projectId, TaskKind.NOVEL_AGENT)
        }
        // 复用既有 Task 错误（不新建错误体系，§29）
        assertTrue(error.error is ApplicationError.TaskNotFound, "应复用既有 TaskNotFound: ${error.error}")
        // 未产生任何队列条目
        assertTrue(f.queue.listByProject(projectId).isEmpty())
        assertEquals(0, countRows(f.handle, "TaskQueueItem"))
        f.close()
    }

    @Test
    fun `in memory fixture uses an isolated database per test`() {
        val f = taskQueueFixture()
        assertEquals(0, countRows(f.handle, "TaskQueueItem"))
        f.close()
    }
}