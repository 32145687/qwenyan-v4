package com.qianyan.application.usecase.task

import com.qianyan.application.di.ApplicationContainer
import com.qianyan.engine.txt.TxtSource
import com.qianyan.model.NovelId
import com.qianyan.model.TxtDocumentId
import com.qianyan.model.task.TaskStatus
import com.qianyan.model.task.TaskType
import com.qianyan.provider.impl.MockLLMGateway
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlin.io.path.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * P8.3 TaskExecutionIntegrationTest：真实全链路。
 *
 * ApplicationContainer + TaskManager + TaskRunner + TxtUseCases + SQLite(文件)：
 * Task 创建 → TaskRunner 执行 IMPORT → 数据库 close → reopen →
 * Task = COMPLETED 且 Checkpoint 存在（snapshot 可 restore 回读）。
 *
 * 必须真实调用 TxtUseCases.importTxtAsOriginal（经 TaskRunner），不使用 fakeImport。
 */
class TaskExecutionIntegrationTest {

    private fun container(url: String = JdbcSqliteDriver.IN_MEMORY): ApplicationContainer =
        ApplicationContainer.open(url, analysisGateway = MockLLMGateway())

    private fun source(text: String = "第一章\n\n正文一。\n\n第二章\n\n正文二。", name: String = "integration.txt"): TxtSource =
        TxtSource(text.toByteArray(StandardCharsets.UTF_8), name)

    /* 全链路 + SQLite close/reopen：COMPLETED 与 checkpoint 均保留，导入产物仍在 */
    @Test
    fun `import task stays completed with checkpoint across reopen`() {
        val tmp = Files.createTempFile("qianyan-p83-exec", ".db").toString()
        try {
            // 第一阶段：创建 PENDING Task → TaskRunner 真实执行 IMPORT → COMPLETED
            val app = container("jdbc:sqlite:$tmp")
            val id = app.tasks.create(TaskType.IMPORT)
            val done = app.taskRunner.execute(id, source(), title = "持久化小说")
            assertEquals(TaskStatus.COMPLETED, done.status)
            assertEquals(TaskStatus.COMPLETED, app.tasks.findById(id).status)

            // 第二阶段：close/reopen 后状态与 checkpoint 仍在，且可 restore
            val reopened = container("jdbc:sqlite:$tmp")
            val task = reopened.tasks.findById(id)
            assertEquals(TaskStatus.COMPLETED, task.status)
            assertEquals(1f, task.progress)
            assertEquals(1, task.revisionCount)
            val cp = task.checkpoint
            assertNotNull(cp)
            assertEquals("IMPORT", cp.stage)

            val restored = reopened.tasks.restoreCheckpoint(id)
            assertEquals(1, restored.revision)
            val snapshot = restored.snapshot
            assertNotNull(snapshot)
            assertEquals("IMPORT", (snapshot["type"] as JsonPrimitive).content)
            val output = snapshot["output"] as JsonObject
            val novelId = NovelId((output["novelId"] as JsonPrimitive).content)
            val documentId = TxtDocumentId((output["documentId"] as JsonPrimitive).content)

            // 导入产物（Novel / TXT Document）跨 reopen 保留
            assertNotNull(reopened.novels.getNovel(novelId))
            assertNotNull(reopened.txtRepository.getDocument(documentId))
            assertTrue(reopened.txtRepository.getChapters(documentId).isNotEmpty())
        } finally {
            Files.deleteIfExists(Path(tmp))
        }
    }

    /* 失败路径跨 reopen：FAILED + error 保留 */
    @Test
    fun `failed task keeps error across reopen`() {
        val tmp = Files.createTempFile("qianyan-p83-fail", ".db").toString()
        try {
            val app = container("jdbc:sqlite:$tmp")
            val id = app.tasks.create(TaskType.IMPORT)
            val ex = kotlin.test.assertFailsWith<com.qianyan.application.error.ApplicationException> {
                app.taskRunner.execute(id, TxtSource(ByteArray(0), "broken.txt"))
            }
            assertTrue(ex.error is com.qianyan.application.error.ApplicationError.EmptyDocument)

            val reopened = container("jdbc:sqlite:$tmp")
            val failed = reopened.tasks.findById(id)
            assertEquals(TaskStatus.FAILED, failed.status)
            assertNotNull(failed.error)
            assertTrue(failed.error!!.isNotBlank())
            assertEquals(0, failed.revisionCount)
        } finally {
            Files.deleteIfExists(Path(tmp))
        }
    }
}
