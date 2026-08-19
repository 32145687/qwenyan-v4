package com.qianyan.application.usecase.task

import com.qianyan.application.di.ApplicationContainer
import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.engine.txt.TxtSource
import com.qianyan.model.NovelId
import com.qianyan.model.TaskId
import com.qianyan.model.TxtDocumentId
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P8.3 TaskExecutionTest：完整执行生命周期验证。
 *
 * 成功：create → start → execute → checkpoint → complete；
 * 失败：create → start → execute failure → fail。
 * 验证 status / revisionCount / checkpoint / error 四要素，以及 snapshot 结构化上下文可恢复。
 */
class TaskExecutionTest {

    private fun container(): ApplicationContainer = ApplicationContainer.open(analysisGateway = MockLLMGateway())

    private fun source(text: String = "第一章\n\n正文一。\n\n第二章\n\n正文二。", name: String = "exec.txt"): TxtSource =
        TxtSource(text.toByteArray(StandardCharsets.UTF_8), name)

    /* 成功完整生命周期：状态、revision、checkpoint、输出上下文全部正确 */
    @Test
    fun `successful execution completes full lifecycle`() {
        val app = container()
        val id: TaskId = app.tasks.create(TaskType.IMPORT)

        // 执行驱动内部：PENDING → RUNNING → checkpoint → COMPLETED
        val done = app.taskRunner.execute(id, source(), title = "执行测试小说")

        // status
        assertEquals(TaskStatus.COMPLETED, done.status)
        assertEquals(TaskStatus.COMPLETED, app.tasks.findById(id).status)
        assertEquals(1f, app.tasks.findById(id).progress)

        // revisionCount + checkpoint（一次执行保存 1 个 checkpoint）
        val task = app.tasks.findById(id)
        assertEquals(1, task.revisionCount)
        val cp = task.checkpoint
        assertNotNull(cp)
        assertEquals(1, cp.revision)
        assertEquals("IMPORT", cp.stage)

        // checkpoint 结构化上下文：type / input（title、source）/ output（真实导入结果）
        val snapshot = cp.snapshot
        assertNotNull(snapshot)
        assertEquals("IMPORT", (snapshot["type"] as JsonPrimitive).content)
        val input = snapshot["input"] as JsonObject
        assertEquals("执行测试小说", (input["title"] as JsonPrimitive).content)
        assertEquals("exec.txt", (input["source"] as JsonPrimitive).content)
        val output = snapshot["output"] as JsonObject
        val documentId = TxtDocumentId((output["documentId"] as JsonPrimitive).content)
        val novelId = NovelId((output["novelId"] as JsonPrimitive).content)
        assertEquals(false, (output["isDuplicate"] as JsonPrimitive).content.toBoolean())
        assertTrue(((output["contentHash"] as JsonPrimitive).content).isNotBlank())
        assertTrue(((output["chapterCount"] as JsonPrimitive).content).toInt() >= 1)
        assertTrue(((output["blockCount"] as JsonPrimitive).content).toInt() >= 1)

        // 真实导入产物可读
        assertNotNull(app.txtRepository.getDocument(documentId))
        assertNotNull(app.novels.getNovel(novelId))

        // restoreCheckpoint 只恢复上下文（不重新执行）：返回同一 IMPORT snapshot，状态不改变
        val restored = app.tasks.restoreCheckpoint(id)
        assertEquals(1, restored.revision)
        assertEquals("IMPORT", restored.stage)
        assertEquals(TaskStatus.COMPLETED, app.tasks.findById(id).status)
        assertEquals(TaskStatus.COMPLETED, done.status)
    }

    /* 失败完整生命周期：execute failure → FAILED，错误记录、无 checkpoint */
    @Test
    fun `failed execution records error and no checkpoint`() {
        val app = container()
        val id: TaskId = app.tasks.create(TaskType.IMPORT)

        val ex = assertFailsWith<ApplicationException> {
            app.taskRunner.execute(id, TxtSource(ByteArray(0), "broken.txt"))
        }
        assertIs<ApplicationError.EmptyDocument>(ex.error)

        val failed = app.tasks.findById(id)
        assertEquals(TaskStatus.FAILED, failed.status)
        assertNotNull(failed.error)
        assertTrue(failed.error!!.isNotBlank())
        assertEquals(0, failed.revisionCount)
        assertNull(failed.checkpoint)
        assertTrue(app.tasks.findCheckpoints(id).isEmpty())
    }

    /* 失败后终态保护：FAILED 不可再 start / complete（retry 属后续阶段） */
    @Test
    fun `failed task cannot be restarted`() {
        val app = container()
        val id = app.tasks.create(TaskType.IMPORT)
        assertFailsWith<ApplicationException> { app.taskRunner.execute(id, TxtSource(ByteArray(0))) }
        val ex = assertFailsWith<ApplicationException> { app.tasks.start(id) }
        assertIs<ApplicationError.InvalidTaskStateTransition>(ex.error)
        assertEquals(TaskStatus.FAILED, app.tasks.findById(id).status)
    }
}
