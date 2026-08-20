package com.qianyan.application.usecase.task

import com.qianyan.application.di.ApplicationContainer
import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.engine.txt.TxtSource
import com.qianyan.model.TaskId
import com.qianyan.model.task.TaskStatus
import com.qianyan.model.task.TaskType
import com.qianyan.provider.impl.MockLLMGateway
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * P8.3 TaskRunner 行为测试：受管 Task 执行驱动的薄适配器。
 *
 * 覆盖：IMPORT 成功 / IMPORT 失败 / Unsupported TaskType（类型化拒绝）/
 * PENDING 执行 / RUNNING 状态保护 / 终态保护（COMPLETED / CANCELLED）。
 * 真实执行 TxtUseCases.importTxtAsOriginal（非 fake），数据库为真实内存 SQLite。
 */
class TaskRunnerTest {

    private fun container(): ApplicationContainer = ApplicationContainer.open(analysisGateway = MockLLMGateway())

    private fun source(text: String = "第一章\n\n正文一。\n\n第二章\n\n正文二。", name: String = "novel.txt"): TxtSource =
        TxtSource(text.toByteArray(StandardCharsets.UTF_8), name)

    /* IMPORT 成功：PENDING → RUNNING → checkpoint → COMPLETED，且真正导入（Novel/Document 落库） */
    @Test
    fun `import success completes task and persists real import`() {
        val app = container()
        val id = app.tasks.create(TaskType.IMPORT)
        val done = app.taskRunner.execute(id, source(), title = "我的小说")

        assertEquals(TaskStatus.COMPLETED, done.status)
        assertEquals(TaskStatus.COMPLETED, app.tasks.findById(id).status)
        assertEquals(1f, app.tasks.findById(id).progress)
        // checkpoint 已保存（revision=1, stage=IMPORT），snapshot 结构化且可读
        val task = app.tasks.findById(id)
        assertEquals(1, task.revisionCount)
        val cp = task.checkpoint
        assertNotNull(cp)
        assertEquals(1, cp.revision)
        assertEquals("IMPORT", cp.stage)
        // 真实导入：Original Novel 与 TXT Document 确实创建
        val snapshot = cp.snapshot
        assertNotNull(snapshot)
        val novelId = ((snapshot["output"] as JsonObject)["novelId"] as JsonPrimitive).content
        assertNotNull(app.novels.getNovel(com.qianyan.model.NovelId(novelId)))
    }

    /* IMPORT 失败：空正文 → EmptyDocument → RUNNING → FAILED，错误记录且继续抛出类型化错误 */
    @Test
    fun `import failure moves task to failed with typed error`() {
        val app = container()
        val id = app.tasks.create(TaskType.IMPORT)
        val ex = assertFailsWith<ApplicationException> {
            app.taskRunner.execute(id, TxtSource(ByteArray(0), "empty.txt"))
        }
        assertIs<ApplicationError.EmptyDocument>(ex.error)

        val failed = app.tasks.findById(id)
        assertEquals(TaskStatus.FAILED, failed.status)
        assertNotNull(failed.error)
        assertTrue(failed.error!!.isNotBlank(), "失败原因必须记录到 Task.error")
        // 失败路径未保存 checkpoint
        assertEquals(0, failed.revisionCount)
        assertTrue(app.tasks.findCheckpoints(id).isEmpty())
    }

    /* Unsupported TaskType：WRITING / KNOWLEDGE_UPDATE 无真实执行入口，字节源 execute 一律类型化拒绝，
       Task 保持 PENDING。（P11.2 起 PLANNING 已放开，走 executePlanning 专用入口。） */
    @Test
    fun `unsupported task types are rejected with typed error`() {
        val app = container()
        for (type in listOf(TaskType.WRITING, TaskType.KNOWLEDGE_UPDATE)) {
            val id = app.tasks.create(type)
            val ex = assertFailsWith<ApplicationException> {
                app.taskRunner.execute(id, source())
            }
            assertIs<ApplicationError.UnsupportedTaskType>(ex.error, "类型 $type 应抛 UnsupportedTaskType")
            // 拒绝发生在 start 之前：Task 保持 PENDING、无 checkpoint
            assertEquals(TaskStatus.PENDING, app.tasks.findById(id).status, "类型 $type 不应被启动")
            assertTrue(app.tasks.findCheckpoints(id).isEmpty())
        }
    }

    /* PENDING 执行：PENDING 是 execute 的合法起点 */
    @Test
    fun `pending task can be executed`() {
        val app = container()
        val id = app.tasks.create(TaskType.IMPORT)
        assertEquals(TaskStatus.PENDING, app.tasks.findById(id).status)
        val done = app.taskRunner.execute(id, source())
        assertEquals(TaskStatus.COMPLETED, done.status)
    }

    /* RUNNING 状态保护：已 RUNNING 的 Task 不能再次 execute（start 被状态机拒绝） */
    @Test
    fun `running task cannot be executed again`() {
        val app = container()
        val id = app.tasks.create(TaskType.IMPORT)
        app.tasks.start(id)
        val ex = assertFailsWith<ApplicationException> {
            app.taskRunner.execute(id, source())
        }
        assertIs<ApplicationError.InvalidTaskStateTransition>(ex.error)
        assertEquals(TaskStatus.RUNNING, app.tasks.findById(id).status)
    }

    /* 终态保护：COMPLETED 拒绝再次执行 */
    @Test
    fun `completed task rejects execution`() {
        val app = container()
        val id = app.tasks.create(TaskType.IMPORT)
        app.taskRunner.execute(id, source())
        val ex = assertFailsWith<ApplicationException> {
            app.taskRunner.execute(id, source())
        }
        assertIs<ApplicationError.TaskAlreadyCompleted>(ex.error)
    }

    /* 终态保护：CANCELLED 拒绝执行 */
    @Test
    fun `cancelled task rejects execution`() {
        val app = container()
        val id = app.tasks.create(TaskType.IMPORT)
        app.tasks.cancel(id)
        val ex = assertFailsWith<ApplicationException> {
            app.taskRunner.execute(id, source())
        }
        assertIs<ApplicationError.TaskAlreadyCancelled>(ex.error)
    }

    /* 缺失 Task：TaskNotFound（类型化，非 UnknownStorage） */
    @Test
    fun `executing missing task throws TaskNotFound`() {
        val app = container()
        val ex = assertFailsWith<ApplicationException> {
            app.taskRunner.execute(TaskId("ghost"), source())
        }
        assertIs<ApplicationError.TaskNotFound>(ex.error)
    }
}
