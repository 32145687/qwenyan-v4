package com.qianyan.application.usecase.task

import com.qianyan.application.di.ApplicationContainer
import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.application.error.ErrorMapper
import com.qianyan.model.TaskId
import com.qianyan.model.task.TaskStatus
import com.qianyan.model.task.TaskType
import com.qianyan.provider.impl.MockLLMGateway
import com.qianyan.storage.repository.RevisionLimitExceededException
import com.qianyan.storage.repository.TaskNotFoundException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * P8.2 TaskManagerUseCases 行为测试（通过 ApplicationContainer + 真实内存 SQLite）。
 *
 * 覆盖：生命周期操作、非法转换（类型化错误）、终态拒绝、revision 顺序控制、
 * Checkpoint 保存/恢复、restoreCheckpoint 语义、以及 Task 领域错误不再落入 UnknownStorage。
 */
class TaskManagerUseCaseTest {

    private fun container(): ApplicationContainer = ApplicationContainer.open(analysisGateway = MockLLMGateway())

    /** P8.2 最小 Checkpoint snapshot 契约（结构化 JSON，非领域模型）。 */
    private fun importSnapshot(source: String, title: String, novelId: String): JsonObject = buildJsonObject {
        put("type", "IMPORT")
        put("input", buildJsonObject {
            put("source", source)
            put("title", title)
        })
        put("output", buildJsonObject {
            put("novelId", novelId)
        })
    }

    /* ---- create / findById ---- */

    @Test
    fun `create returns id and findById returns pending task`() {
        val app = container()
        val id = app.tasks.create(TaskType.IMPORT)
        val task = app.tasks.findById(id)
        assertEquals(id, task.taskId)
        assertEquals(TaskStatus.PENDING, task.status)
        assertEquals(0, task.revisionCount)
        assertEquals(0f, task.progress)
    }

    @Test
    fun `findById on missing task throws TaskNotFound`() {
        val app = container()
        val ex = assertFailsWith<ApplicationException> { app.tasks.findById(TaskId("no-such-task")) }
        assertIs<ApplicationError.TaskNotFound>(ex.error)
    }

    /* ---- 生命周期状态转换 ---- */

    @Test
    fun `start moves pending to running`() {
        val app = container()
        val id = app.tasks.create(TaskType.IMPORT)
        val running = app.tasks.start(id, progress = 0.3f)
        assertEquals(TaskStatus.RUNNING, running.status)
        assertEquals(TaskStatus.RUNNING, app.tasks.findById(id).status)
    }

    @Test
    fun `pause moves running to paused with progress`() {
        val app = container()
        val id = app.tasks.create(TaskType.IMPORT)
        app.tasks.start(id)
        val paused = app.tasks.pause(id, progress = 0.5f)
        assertEquals(TaskStatus.PAUSED, paused.status)
        assertEquals(0.5f, paused.progress)
        assertEquals(TaskStatus.PAUSED, app.tasks.findById(id).status)
    }

    @Test
    fun `resume moves paused back to running`() {
        val app = container()
        val id = app.tasks.create(TaskType.IMPORT)
        app.tasks.start(id)
        app.tasks.pause(id, progress = 0.5f)
        val running = app.tasks.resume(id, progress = 0.6f)
        assertEquals(TaskStatus.RUNNING, running.status)
        assertEquals(0.6f, running.progress)
    }

    @Test
    fun `cancel from pending moves to cancelled`() {
        val app = container()
        val id = app.tasks.create(TaskType.IMPORT)
        val cancelled = app.tasks.cancel(id)
        assertEquals(TaskStatus.CANCELLED, cancelled.status)
        assertEquals(TaskStatus.CANCELLED, app.tasks.findById(id).status)
    }

    @Test
    fun `complete moves running to completed with progress 1`() {
        val app = container()
        val id = app.tasks.create(TaskType.IMPORT)
        app.tasks.start(id)
        val done = app.tasks.complete(id)
        assertEquals(TaskStatus.COMPLETED, done.status)
        assertEquals(1f, done.progress)
    }

    @Test
    fun `complete from paused is allowed`() {
        val app = container()
        val id = app.tasks.create(TaskType.IMPORT)
        app.tasks.start(id)
        app.tasks.pause(id, progress = 0.5f)
        assertEquals(TaskStatus.COMPLETED, app.tasks.complete(id).status)
    }

    @Test
    fun `fail moves running to failed with reason`() {
        val app = container()
        val id = app.tasks.create(TaskType.IMPORT)
        app.tasks.start(id)
        val failed = app.tasks.fail(id, error = "解析失败")
        assertEquals(TaskStatus.FAILED, failed.status)
        assertEquals("解析失败", failed.error)
    }

    /* ---- 非法转换 → 类型化错误 ---- */

    @Test
    fun `pending cannot pause`() {
        val app = container()
        val id = app.tasks.create(TaskType.IMPORT)
        val ex = assertFailsWith<ApplicationException> { app.tasks.pause(id, progress = 0.1f) }
        assertIs<ApplicationError.InvalidTaskStateTransition>(ex.error)
    }

    @Test
    fun `pending cannot complete`() {
        val app = container()
        val id = app.tasks.create(TaskType.IMPORT)
        val ex = assertFailsWith<ApplicationException> { app.tasks.complete(id) }
        assertIs<ApplicationError.InvalidTaskStateTransition>(ex.error)
    }

    @Test
    fun `running cannot start again`() {
        val app = container()
        val id = app.tasks.create(TaskType.IMPORT)
        app.tasks.start(id)
        val ex = assertFailsWith<ApplicationException> { app.tasks.start(id) }
        assertIs<ApplicationError.InvalidTaskStateTransition>(ex.error)
    }

    @Test
    fun `paused cannot fail`() {
        val app = container()
        val id = app.tasks.create(TaskType.IMPORT)
        app.tasks.start(id)
        app.tasks.pause(id, progress = 0.5f)
        val ex = assertFailsWith<ApplicationException> { app.tasks.fail(id, error = "x") }
        assertIs<ApplicationError.InvalidTaskStateTransition>(ex.error)
    }

    /* ---- 终态拒绝 ---- */

    @Test
    fun `completed rejects every lifecycle operation`() {
        val app = container()
        val id = app.tasks.create(TaskType.IMPORT)
        app.tasks.start(id)
        app.tasks.complete(id)
        val ex = assertFailsWith<ApplicationException> { app.tasks.cancel(id) }
        assertIs<ApplicationError.TaskAlreadyCompleted>(ex.error)
        assertFailsWith<ApplicationException> { app.tasks.start(id) }
        assertFailsWith<ApplicationException> { app.tasks.pause(id, progress = 0f) }
        assertFailsWith<ApplicationException> { app.tasks.resume(id, progress = 0f) }
        assertFailsWith<ApplicationException> { app.tasks.fail(id, error = "x") }
        assertFailsWith<ApplicationException> { app.tasks.complete(id) }
        // 终态仍可保存/恢复 Checkpoint（只恢复上下文，不改变状态）
        app.tasks.saveCheckpoint(id, stage = "final", snapshot = importSnapshot("a.txt", "A", "n1"))
    }

    @Test
    fun `cancelled rejects every lifecycle operation`() {
        val app = container()
        val id = app.tasks.create(TaskType.IMPORT)
        app.tasks.cancel(id)
        val ex = assertFailsWith<ApplicationException> { app.tasks.complete(id) }
        assertIs<ApplicationError.TaskAlreadyCancelled>(ex.error)
        assertFailsWith<ApplicationException> { app.tasks.start(id) }
        assertFailsWith<ApplicationException> { app.tasks.pause(id, progress = 0f) }
        assertFailsWith<ApplicationException> { app.tasks.resume(id, progress = 0f) }
        assertFailsWith<ApplicationException> { app.tasks.cancel(id) }
        assertFailsWith<ApplicationException> { app.tasks.fail(id, error = "x") }
    }

    @Test
    fun `failed rejects every lifecycle operation including retry`() {
        val app = container()
        val id = app.tasks.create(TaskType.IMPORT)
        app.tasks.start(id)
        app.tasks.fail(id, error = "boom")
        // FAILED → RUNNING / PENDING 均禁止（retry 属后续 Workflow）
        val ex = assertFailsWith<ApplicationException> { app.tasks.start(id) }
        assertIs<ApplicationError.InvalidTaskStateTransition>(ex.error)
        assertFailsWith<ApplicationException> { app.tasks.resume(id, progress = 0f) }
        assertFailsWith<ApplicationException> { app.tasks.complete(id) }
        assertFailsWith<ApplicationException> { app.tasks.cancel(id) }
    }

    /* ---- TaskNotFound（所有 Manager API） ---- */

    @Test
    fun `lifecycle operations on missing task throw TaskNotFound`() {
        val app = container()
        val missing = TaskId("missing")
        listOf(
            { app.tasks.start(missing) },
            { app.tasks.pause(missing, progress = 0f) },
            { app.tasks.resume(missing, progress = 0f) },
            { app.tasks.cancel(missing) },
            { app.tasks.complete(missing) },
            { app.tasks.fail(missing, error = "x") },
            { app.tasks.saveCheckpoint(missing, stage = "s") },
            { app.tasks.restoreCheckpoint(missing) },
            { app.tasks.findCheckpoints(missing) },
        ).forEach { op ->
            val ex = assertFailsWith<ApplicationException> { op.invoke() }
            assertIs<ApplicationError.TaskNotFound>(ex.error, "缺失 Task 应抛 TaskNotFound，实际: ${ex.error}")
        }
    }

    /* ---- Revision 顺序控制 ---- */

    @Test
    fun `checkpoint revisions are sequential without gaps`() {
        val app = container()
        val id = app.tasks.create(TaskType.IMPORT)
        app.tasks.start(id)
        repeat(3) { i ->
            app.tasks.saveCheckpoint(id, stage = "s${i + 1}", snapshot = importSnapshot("a.txt", "A", "n${i + 1}"))
            val task = app.tasks.findById(id)
            assertEquals(i + 1, task.revisionCount)
        }
        val checkpoints = app.tasks.findCheckpoints(id)
        assertEquals(listOf(1, 2, 3), checkpoints.map { it.revision })
    }

    @Test
    fun `checkpoint revision 4 is rejected with RevisionLimitExceeded`() {
        val app = container()
        val id = app.tasks.create(TaskType.IMPORT)
        app.tasks.start(id)
        repeat(3) { i -> app.tasks.saveCheckpoint(id, stage = "s${i + 1}") }
        val ex = assertFailsWith<ApplicationException> { app.tasks.saveCheckpoint(id, stage = "s4") }
        assertIs<ApplicationError.RevisionLimitExceeded>(ex.error)
        assertEquals(3, app.tasks.findById(id).revisionCount)
    }

    @Test
    fun `manager computes next revision from revisionCount`() {
        val app = container()
        val id = app.tasks.create(TaskType.IMPORT)
        app.tasks.start(id)
        // revisionCount=0 → 1；revisionCount=1 → 2；revisionCount=2 → 3
        app.tasks.saveCheckpoint(id, stage = "a")
        assertEquals(2, app.tasks.saveCheckpoint(id, stage = "b").revision)
        assertEquals(3, app.tasks.saveCheckpoint(id, stage = "c").revision)
        // 不允许调用方指定 revision（saveCheckpoint 无 revision 入参）
        assertEquals(listOf(1, 2, 3), app.tasks.findCheckpoints(id).map { it.revision })
    }

    /* ---- Checkpoint / Restore ---- */

    @Test
    fun `latest checkpoint becomes task checkpoint view`() {
        val app = container()
        val id = app.tasks.create(TaskType.IMPORT)
        app.tasks.start(id)
        app.tasks.saveCheckpoint(id, stage = "s1", snapshot = importSnapshot("a.txt", "A", "n1"))
        app.tasks.saveCheckpoint(id, stage = "s2", snapshot = importSnapshot("b.txt", "B", "n2"))
        app.tasks.saveCheckpoint(id, stage = "s3", snapshot = importSnapshot("c.txt", "C", "n3"))
        val task = app.tasks.findById(id)
        val latest = task.checkpoint
        assertNotNull(latest)
        assertEquals(3, latest.revision)
        val output = latest.snapshot?.get("output")
        assertNotNull(output)
        assertEquals("n3", ((output as JsonObject)["novelId"] as JsonPrimitive).content)
    }

    @Test
    fun `restoreCheckpoint returns latest snapshot`() {
        val app = container()
        val id = app.tasks.create(TaskType.IMPORT)
        app.tasks.start(id)
        app.tasks.saveCheckpoint(id, stage = "s1", snapshot = importSnapshot("a.txt", "A", "n1"))
        app.tasks.saveCheckpoint(id, stage = "s2", snapshot = importSnapshot("b.txt", "B", "n2"))
        val restored = app.tasks.restoreCheckpoint(id)
        assertEquals(2, restored.revision)
        val output = restored.snapshot?.get("output")
        assertNotNull(output)
        assertEquals("n2", ((output as JsonObject)["novelId"] as JsonPrimitive).content)
        // restore 只恢复上下文，不改变 Task 状态
        assertEquals(TaskStatus.RUNNING, app.tasks.findById(id).status)
    }

    @Test
    fun `restoreCheckpoint without checkpoint throws CheckpointNotFound`() {
        val app = container()
        val id = app.tasks.create(TaskType.IMPORT)
        app.tasks.start(id)
        val ex = assertFailsWith<ApplicationException> { app.tasks.restoreCheckpoint(id) }
        assertIs<ApplicationError.CheckpointNotFound>(ex.error)
    }

    @Test
    fun `findCheckpoints returns empty list for task without checkpoints`() {
        val app = container()
        val id = app.tasks.create(TaskType.IMPORT)
        assertTrue(app.tasks.findCheckpoints(id).isEmpty())
    }

    /* ---- Error Mapping：Task 领域错误不落入 UnknownStorage ---- */

    @Test
    fun `storage task exceptions map to typed application errors`() {
        assertIs<ApplicationError.TaskNotFound>(ErrorMapper.map(TaskNotFoundException("no task")).error)
        assertIs<ApplicationError.RevisionLimitExceeded>(
            ErrorMapper.map(RevisionLimitExceededException("revision 4")).error,
        )
    }

    @Test
    fun `task errors never fall into UnknownStorage`() {
        val app = container()
        // 缺失 Task 的完整操作链 → 一律 TaskNotFound（非 UnknownStorage）
        val missing = TaskId("ghost")
        assertIs<ApplicationError.TaskNotFound>(assertFailsWith<ApplicationException> { app.tasks.start(missing) }.error)
        assertIs<ApplicationError.TaskNotFound>(assertFailsWith<ApplicationException> { app.tasks.complete(missing) }.error)
        assertIs<ApplicationError.TaskNotFound>(assertFailsWith<ApplicationException> { app.tasks.restoreCheckpoint(missing) }.error)
        // 超过 revision 上限 → RevisionLimitExceeded（非 UnknownStorage）
        val id = app.tasks.create(TaskType.IMPORT)
        app.tasks.start(id)
        repeat(3) { i -> app.tasks.saveCheckpoint(id, stage = "s${i + 1}") }
        assertIs<ApplicationError.RevisionLimitExceeded>(
            assertFailsWith<ApplicationException> { app.tasks.saveCheckpoint(id, stage = "s4") }.error,
        )
    }
}
