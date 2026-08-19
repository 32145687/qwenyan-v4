package com.qianyan.application.usecase.task

import com.qianyan.application.di.ApplicationContainer
import com.qianyan.model.task.TaskStatus
import com.qianyan.model.task.TaskType
import com.qianyan.provider.impl.MockLLMGateway
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import kotlin.io.path.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * P8.2 全链路集成测试：
 * ApplicationContainer → TaskManagerUseCases → TaskRepository(SQLite)。
 *
 * 覆盖完整生命周期 + 数据库 close/reopen 后的状态与 Checkpoint 持久化回读。
 */
class TaskManagerIntegrationTest {

    private fun container(url: String = JdbcSqliteDriver.IN_MEMORY): ApplicationContainer =
        ApplicationContainer.open(url, analysisGateway = MockLLMGateway())

    private fun analysisSnapshot(): JsonObject = buildJsonObject {
        put("type", "ANALYSIS")
        put("input", buildJsonObject {
            put("documentId", "doc-1")
            put("vocabularyId", "vocab-1")
        })
        put("output", buildJsonObject {
            put("candidateIds", kotlinx.serialization.json.buildJsonArray { })
        })
    }

    /* 容器装配：taskRepository 注入 + tasks 可用 */
    @Test
    fun `container wires task repository and task manager`() {
        val app = container()
        assertNotNull(app.taskRepository)
        assertNotNull(app.tasks)
        val id = app.tasks.create(TaskType.IMPORT)
        assertEquals(TaskStatus.PENDING, app.tasks.findById(id).status)
    }

    /* 完整生命周期：create → start → pause → resume → complete → 重开 → COMPLETED */
    @Test
    fun `full lifecycle persists across reopen`() {
        val tmp = Files.createTempFile("qianyan-p82-lifecycle", ".db").toString()
        try {
            val app = container("jdbc:sqlite:$tmp")
            val id = app.tasks.create(TaskType.IMPORT)
            app.tasks.start(id, progress = 0.2f)
            app.tasks.pause(id, progress = 0.5f)
            assertEquals(TaskStatus.PAUSED, app.tasks.findById(id).status)
            app.tasks.resume(id, progress = 0.7f)
            app.tasks.complete(id)

            val reopened = container("jdbc:sqlite:$tmp")
            val task = reopened.tasks.findById(id)
            assertEquals(TaskStatus.COMPLETED, task.status)
            assertEquals(1f, task.progress)
        } finally {
            Files.deleteIfExists(Path(tmp))
        }
    }

    /* create → start → close/reopen → RUNNING（状态持久化回读） */
    @Test
    fun `start persists across reopen`() {
        val tmp = Files.createTempFile("qianyan-p82-start", ".db").toString()
        try {
            val app = container("jdbc:sqlite:$tmp")
            val id = app.tasks.create(TaskType.IMPORT)
            app.tasks.start(id)

            val reopened = container("jdbc:sqlite:$tmp")
            assertEquals(TaskStatus.RUNNING, reopened.tasks.findById(id).status)
        } finally {
            Files.deleteIfExists(Path(tmp))
        }
    }

    /* pause → close/reopen → PAUSED（暂停进度持久化回读） */
    @Test
    fun `pause persists across reopen`() {
        val tmp = Files.createTempFile("qianyan-p82-pause", ".db").toString()
        try {
            val app = container("jdbc:sqlite:$tmp")
            val id = app.tasks.create(TaskType.IMPORT)
            app.tasks.start(id)
            app.tasks.pause(id, progress = 0.6f)

            val reopened = container("jdbc:sqlite:$tmp")
            val task = reopened.tasks.findById(id)
            assertEquals(TaskStatus.PAUSED, task.status)
            assertEquals(0.6f, task.progress)
        } finally {
            Files.deleteIfExists(Path(tmp))
        }
    }

    /* Checkpoint：save 1/2/3 → close/reopen → 最近 Checkpoint=3、Task.checkpoint=3、revisionCount=3 */
    @Test
    fun `checkpoints persist across reopen with latest revision`() {
        val tmp = Files.createTempFile("qianyan-p82-cp", ".db").toString()
        try {
            val app = container("jdbc:sqlite:$tmp")
            val id = app.tasks.create(TaskType.IMPORT)
            app.tasks.start(id)
            app.tasks.saveCheckpoint(id, stage = "s1", snapshot = analysisSnapshot())
            app.tasks.saveCheckpoint(id, stage = "s2", snapshot = analysisSnapshot())
            app.tasks.saveCheckpoint(id, stage = "s3", snapshot = analysisSnapshot())

            val reopened = container("jdbc:sqlite:$tmp")
            val task = reopened.tasks.findById(id)
            assertEquals(3, task.revisionCount)
            val latest = task.checkpoint
            assertNotNull(latest)
            assertEquals(3, latest.revision)
            assertEquals("s3", latest.stage)
            assertEquals(listOf(1, 2, 3), reopened.tasks.findCheckpoints(id).map { it.revision })
            assertEquals(3, reopened.tasks.restoreCheckpoint(id).revision)
        } finally {
            Files.deleteIfExists(Path(tmp))
        }
    }

    /* 受管生命周期不影响既有容器能力：同一容器中 Novel/TXT 仍可用 */
    @Test
    fun `task manager coexists with existing use cases in one container`() {
        val app = container()
        val novelId = app.novels.createOriginal(title = "原著")
        assertNotNull(app.novels.getNovel(novelId))

        val id = app.tasks.create(TaskType.IMPORT)
        app.tasks.start(id)
        assertEquals(TaskStatus.RUNNING, app.tasks.findById(id).status)
        assertTrue(app.tasks.findCheckpoints(id).isEmpty())
    }
}
