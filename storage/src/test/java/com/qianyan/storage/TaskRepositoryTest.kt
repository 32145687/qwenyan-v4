package com.qianyan.storage

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.qianyan.model.CheckpointId
import com.qianyan.model.TaskId
import com.qianyan.model.task.Checkpoint
import com.qianyan.model.task.Task
import com.qianyan.model.task.TaskStatus
import com.qianyan.model.task.TaskType
import com.qianyan.storage.db.QianyanDbFactory
import com.qianyan.storage.db.QianyanDbHandle
import com.qianyan.storage.repository.RevisionLimitExceededException
import com.qianyan.storage.repository.SqliteTaskRepository
import com.qianyan.storage.repository.TaskNotFoundException
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P8.1 Task / Checkpoint 持久化测试（storage）。
 * 每个测试使用独立内存数据库（IN_MEMORY），互不影响。
 */
class TaskRepositoryTest {

    private fun handle(url: String = JdbcSqliteDriver.IN_MEMORY): QianyanDbHandle = QianyanDbFactory.open(url)

    private fun randomId(prefix: String) = "$prefix-${Random.nextLong().toString(16)}"

    /** 固定毫秒精度时间（与 DB epoch-毫秒映射一致，读回后 Instant 可精确相等）。 */
    private val fixed = Instant.parse("2026-01-01T00:00:00Z")

    private fun makeTask(
        taskId: String = randomId("task"),
        status: TaskStatus = TaskStatus.PENDING,
        progress: Float = 0f,
        error: String? = null,
    ) = Task(
        taskId = TaskId(taskId),
        type = TaskType.ANALYSIS,
        status = status,
        progress = progress,
        checkpoint = null,
        revisionCount = 0,
        error = error,
        createdAt = fixed,
        updatedAt = fixed,
    )

    private fun makeCheckpoint(
        checkpointId: String = randomId("cp"),
        taskId: TaskId,
        revision: Int,
        stage: String = "analysis",
        snapshot: JsonObject? = null,
    ) = Checkpoint(
        checkpointId = CheckpointId(checkpointId),
        taskId = taskId,
        revision = revision,
        stage = stage,
        snapshot = snapshot,
        createdAt = fixed,
    )

    /* 1. create → find：创建后可读回等价数据 */
    @Test
    fun `create then find returns equivalent task`() {
        val repo = SqliteTaskRepository(handle().db)
        val task = makeTask(status = TaskStatus.RUNNING, progress = 0.5f)
        val id = repo.create(task)
        assertEquals(task.taskId, id)
        val read = repo.findById(task.taskId)
        assertNotNull(read)
        assertEquals(task.taskId, read.taskId)
        assertEquals(TaskType.ANALYSIS, read.type)
        assertEquals(TaskStatus.RUNNING, read.status)
        assertEquals(0.5f, read.progress)
        assertEquals(0, read.revisionCount)
        assertNull(read.error)
        assertNull(read.checkpoint)
        assertEquals(task.createdAt, read.createdAt)
        assertEquals(task.updatedAt, read.updatedAt)
    }

    /* 2. update → find：更新后可读回变更 */
    @Test
    fun `update then find reflects changes`() {
        val repo = SqliteTaskRepository(handle().db)
        val task = makeTask()
        repo.create(task)
        val later = Instant.parse("2026-02-01T00:00:00Z")
        repo.update(
            task.copy(
                status = TaskStatus.COMPLETED,
                progress = 1f,
                revisionCount = 1,
                error = null,
                updatedAt = later,
            ),
        )
        val read = repo.findById(task.taskId)
        assertNotNull(read)
        assertEquals(TaskStatus.COMPLETED, read.status)
        assertEquals(1f, read.progress)
        assertEquals(1, read.revisionCount)
        assertEquals(later, read.updatedAt)
    }

    /* 3. not found：未知 ID 返回 null */
    @Test
    fun `find returns null for unknown id`() {
        val repo = SqliteTaskRepository(handle().db)
        assertNull(repo.findById(TaskId("missing-task")))
    }

    /* 4. checkpoint save → find：Task 附带最近 Checkpoint 可读回 */
    @Test
    fun `save checkpoint then find task exposes latest checkpoint`() {
        val repo = SqliteTaskRepository(handle().db)
        val task = makeTask()
        repo.create(task)
        repo.saveCheckpoint(makeCheckpoint("cp-1", task.taskId, revision = 1, stage = "analysis"))
        val read = repo.findById(task.taskId)
        assertNotNull(read)
        val cp = read.checkpoint
        assertNotNull(cp)
        assertEquals("cp-1", cp.checkpointId.value)
        assertEquals(1, cp.revision)
        assertEquals("analysis", cp.stage)
        // saveCheckpoint 同事务同步 Task.revisionCount
        assertEquals(1, read.revisionCount)
    }

    /* 5. latest checkpoint：返回 revision 最大者 */
    @Test
    fun `latest checkpoint is the highest revision`() {
        val repo = SqliteTaskRepository(handle().db)
        val task = makeTask()
        repo.create(task)
        repo.saveCheckpoint(makeCheckpoint("cp-1", task.taskId, revision = 1))
        repo.saveCheckpoint(makeCheckpoint("cp-2", task.taskId, revision = 2))
        val latest = repo.findLatestCheckpoint(task.taskId)
        assertNotNull(latest)
        assertEquals("cp-2", latest.checkpointId.value)
        assertEquals(2, latest.revision)
        // findById 的 checkpoint 视图与 findLatestCheckpoint 一致
        assertEquals("cp-2", repo.findById(task.taskId)?.checkpoint?.checkpointId?.value)
    }

    /* 6. 多 revision：按 revision 升序列出，snapshot JSON 可读回 */
    @Test
    fun `multiple revisions are listed in ascending order`() {
        val repo = SqliteTaskRepository(handle().db)
        val task = makeTask()
        repo.create(task)
        val snapshots = listOf(
            buildJsonObject { put("progress", 0.3) },
            buildJsonObject { put("progress", 0.6); put("stage", "writing") },
            buildJsonObject { put("progress", 1.0) },
        )
        repeat(3) { i ->
            repo.saveCheckpoint(
                makeCheckpoint("cp-${i + 1}", task.taskId, revision = i + 1, snapshot = snapshots[i]),
            )
        }
        val list = repo.findCheckpoints(task.taskId)
        assertEquals(listOf(1, 2, 3), list.map { it.revision })
        assertEquals(listOf("cp-1", "cp-2", "cp-3"), list.map { it.checkpointId.value })
        // 结构化 JsonObject（非 Map<String,Any>）序列化/反序列化后可读回且内容一致
        assertEquals(0.6, (list[1].snapshot!!["progress"] as JsonPrimitive).content.toDouble())
        assertEquals("writing", (list[1].snapshot!!["stage"] as JsonPrimitive).content)
    }

    /* 7. revision 边界：上限 3 之外被拒绝（P8 Preflight 冻结 revisionCount <= 3） */
    @Test
    fun `revision above 3 is rejected`() {
        val repo = SqliteTaskRepository(handle().db)
        val task = makeTask()
        repo.create(task)
        assertFailsWith<RevisionLimitExceededException> {
            repo.saveCheckpoint(makeCheckpoint("cp-bad", task.taskId, revision = 4))
        }
    }

    @Test
    fun `revision below 1 is rejected`() {
        val repo = SqliteTaskRepository(handle().db)
        val task = makeTask()
        repo.create(task)
        assertFailsWith<RevisionLimitExceededException> {
            repo.saveCheckpoint(makeCheckpoint("cp-zero", task.taskId, revision = 0))
        }
    }

    /* 8. Task 与 Checkpoint 关系：同 (task_id, revision) 唯一、孤儿 Checkpoint 被拒绝、删除级联 */
    @Test
    fun `duplicate revision for same task is rejected`() {
        val repo = SqliteTaskRepository(handle().db)
        val task = makeTask()
        repo.create(task)
        repo.saveCheckpoint(makeCheckpoint("cp-1a", task.taskId, revision = 1))
        assertFailsWith<Exception> {
            repo.saveCheckpoint(makeCheckpoint("cp-1b", task.taskId, revision = 1))
        }
        // 唯一约束失败不留下半成品：仍只有 1 个 checkpoint
        assertEquals(1, repo.findCheckpoints(task.taskId).size)
    }

    @Test
    fun `checkpoint for unknown task is rejected`() {
        val repo = SqliteTaskRepository(handle().db)
        assertFailsWith<TaskNotFoundException> {
            repo.saveCheckpoint(makeCheckpoint("cp-orphan", TaskId("ghost-task"), revision = 1))
        }
    }

    @Test
    fun `delete removes task and its checkpoints`() {
        val repo = SqliteTaskRepository(handle().db)
        val task = makeTask()
        repo.create(task)
        repo.saveCheckpoint(makeCheckpoint("cp-1", task.taskId, revision = 1))
        repo.saveCheckpoint(makeCheckpoint("cp-2", task.taskId, revision = 2))
        repo.delete(task.taskId)
        assertNull(repo.findById(task.taskId))
        assertTrue(repo.findCheckpoints(task.taskId).isEmpty())
    }

    /* 9. 数据库重新打开后持久化读取 */
    @Test
    fun `task and checkpoints survive database reopen`() {
        val file = java.nio.file.Files.createTempFile("qianyan_task_test", ".db").toAbsolutePath()
        val url = "jdbc:sqlite:$file"
        val h1 = handle(url)
        val repo1 = SqliteTaskRepository(h1.db)
        val task = makeTask(status = TaskStatus.RUNNING, progress = 0.7f)
        repo1.create(task)
        repo1.saveCheckpoint(makeCheckpoint("cp-r1", task.taskId, revision = 1))
        repo1.saveCheckpoint(makeCheckpoint("cp-r2", task.taskId, revision = 2))
        (h1.driver as JdbcSqliteDriver).getConnection().close()

        val h2 = handle(url)
        val repo2 = SqliteTaskRepository(h2.db)
        val read = repo2.findById(task.taskId)
        assertNotNull(read)
        assertEquals(TaskStatus.RUNNING, read.status)
        assertEquals(0.7f, read.progress)
        assertEquals(2, read.revisionCount)
        assertEquals(2, read.checkpoint?.revision)
        assertEquals(2, repo2.findCheckpoints(task.taskId).size)
        java.nio.file.Files.deleteIfExists(file)
    }

    /* 10. 事务失败 / 原子性：部分写入失败不留下半成品 */
    @Test
    fun `create with invalid checkpoint leaves no partial task`() {
        val repo = SqliteTaskRepository(handle().db)
        val task = makeTask()
        // Task 插入 + Checkpoint 插入在同一事务；Checkpoint 违反 CHECK(revision BETWEEN 1 AND 3)
        // → 整个事务回滚，Task 记录也不得存在。
        assertFailsWith<Exception> {
            repo.create(task.copy(checkpoint = makeCheckpoint("cp-bad", task.taskId, revision = 4)))
        }
        assertNull(repo.findById(task.taskId))
    }

    @Test
    fun `failed saveCheckpoint keeps task row untouched`() {
        val repo = SqliteTaskRepository(handle().db)
        val task = makeTask()
        repo.create(task)
        repo.saveCheckpoint(makeCheckpoint("cp-1", task.taskId, revision = 1))
        val before = repo.findById(task.taskId)!!

        // 同 (task_id, revision=1) 重复 → Checkpoint INSERT 失败；同事务的 Task 更新必须一并回滚。
        val attemptTime = Clock.System.now()
        assertFailsWith<Exception> {
            repo.saveCheckpoint(makeCheckpoint("cp-dup", task.taskId, revision = 1, stage = "dup").copy(createdAt = attemptTime))
        }
        val after = repo.findById(task.taskId)!!
        // Task 行未被失败的 saveCheckpoint 触碰（updatedAt / revisionCount 不变）
        assertEquals(before.updatedAt, after.updatedAt)
        assertEquals(before.revisionCount, after.revisionCount)
        assertEquals(1, repo.findCheckpoints(task.taskId).size)
        assertEquals("cp-1", repo.findCheckpoints(task.taskId).single().checkpointId.value)
    }
}
