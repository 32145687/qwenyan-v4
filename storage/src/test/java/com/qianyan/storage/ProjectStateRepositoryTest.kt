package com.qianyan.storage

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.qianyan.model.ChapterId
import com.qianyan.model.NovelId
import com.qianyan.model.ProjectId
import com.qianyan.model.TaskId
import com.qianyan.model.VariantId
import com.qianyan.model.project.ProjectState
import com.qianyan.storage.db.QianyanDbFactory
import com.qianyan.storage.repository.SqliteProjectStateRepository
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * I1 · ProjectState 仓储测试（FD-9 additive：一张表，只存运行态引用）。
 *
 * 覆盖：写入/读取、聚合键对（projectId ↔ novelId）、同 projectId 覆盖（不产生第二行）、
 * 缺失返回 null，以及**结构守卫**——该表只含引用列，不含任何 Novel 元数据或生命周期状态列。
 */
class ProjectStateRepositoryTest {

    private fun state(
        projectId: String = "p-i1",
        novelId: String = "n-i1",
        variantId: String? = null,
        chapterId: String? = null,
        taskId: String? = null,
        at: Instant = Clock.System.now(),
    ) = ProjectState(
        projectId = ProjectId(projectId),
        novelId = NovelId(novelId),
        activeVariantId = variantId?.let { VariantId(it) },
        activeChapterId = chapterId?.let { ChapterId(it) },
        activeTaskId = taskId?.let { TaskId(it) },
        updatedAt = at,
    )

    /** 建一个含 Novel（满足 FK）的内存库 + 仓储。 */
    private fun repo(): Pair<SqliteProjectStateRepository, JdbcSqliteDriver> {
        val h = QianyanDbFactory.open(JdbcSqliteDriver.IN_MEMORY)
        val driver = h.driver as JdbcSqliteDriver
        driver.execute(
            null,
            "INSERT INTO Novel(novel_id, project_id, title, source, genre, synopsis, scope, status, created_at, updated_at) " +
                "VALUES ('n-i1', 'p-i1', '书', 'ORIGINAL_NOVEL', '[]', '', 'ORIGINAL', 'DRAFT', 1, 1)",
            0,
        )
        return SqliteProjectStateRepository(h.db) to driver
    }

    @Test
    fun `save then get round trips references`() {
        val (repo, driver) = repo()
        val at = Instant.fromEpochMilliseconds(1_700_000_000_000)
        repo.save(state(variantId = "v-1", chapterId = "c-9", taskId = "t-3", at = at))

        val read = repo.get(ProjectId("p-i1"))
        assertEquals(ProjectId("p-i1"), read?.projectId)
        assertEquals(NovelId("n-i1"), read?.novelId)
        assertEquals(VariantId("v-1"), read?.activeVariantId)
        assertEquals(ChapterId("c-9"), read?.activeChapterId)
        assertEquals(TaskId("t-3"), read?.activeTaskId)
        assertEquals(at, read?.updatedAt)
        driver.getConnection().close()
    }

    @Test
    fun `original scope keeps nullable references`() {
        val (repo, driver) = repo()
        repo.save(state())

        val read = repo.get(ProjectId("p-i1"))
        assertNull(read?.activeVariantId, "null = Original 作用域（与 VariantContext 语义一致）")
        assertNull(read?.activeChapterId)
        assertNull(read?.activeTaskId)
        assertTrue(read!!.isOriginalScope)
        driver.getConnection().close()
    }

    @Test
    fun `missing state returns null`() {
        val (repo, driver) = repo()
        assertNull(repo.get(ProjectId("ghost")), "无记录 → null（不伪造运行态）")
        assertNull(repo.getByNovel(NovelId("ghost")))
        driver.getConnection().close()
    }

    @Test
    fun `get by novel resolves the aggregate key pair`() {
        val (repo, driver) = repo()
        repo.save(state())
        assertEquals(ProjectId("p-i1"), repo.getByNovel(NovelId("n-i1"))?.projectId)
        driver.getConnection().close()
    }

    @Test
    fun `save is idempotent per project and overwrites the single row`() {
        val (repo, driver) = repo()
        repo.save(state(chapterId = "c-1"))
        repo.save(state(chapterId = "c-2"))

        assertEquals(1L, count(driver, "ProjectState"), "同 projectId 覆盖，不产生第二行")
        assertEquals(ChapterId("c-2"), repo.get(ProjectId("p-i1"))?.activeChapterId)
        driver.getConnection().close()
    }

    @Test
    fun `project state table stores references only`() {
        val (_, driver) = repo()
        val columns = columns(driver, "ProjectState")

        assertEquals(
            setOf("project_id", "novel_id", "active_variant_id", "active_chapter_id", "active_task_id", "updated_at"),
            columns.toSet(),
            "ProjectState 只持有运行态引用，不得复制 Novel 元数据或引入状态机列",
        )
        // 结构守卫：不得出现 Novel 元数据列，也不得出现 Workflow/Task 生命周期列。
        listOf("title", "genre", "synopsis", "status", "state", "phase", "scope").forEach {
            assertTrue(it !in columns, "ProjectState 不应包含列 '$it'（不复制元数据 / 不建第二套状态机）")
        }
        driver.getConnection().close()
    }

    private fun columns(driver: JdbcSqliteDriver, table: String): List<String> =
        driver.executeQuery(
            null,
            "PRAGMA table_info($table)",
            { cursor ->
                val out = mutableListOf<String>()
                while (cursor.next().value) out += cursor.getString(1) ?: ""
                QueryResult.Value(out.toList())
            },
            0,
        ).value

    private fun count(driver: JdbcSqliteDriver, table: String): Long =
        driver.executeQuery(
            null,
            "SELECT COUNT(*) FROM $table",
            { cursor ->
                cursor.next()
                QueryResult.Value(cursor.getLong(0) ?: 0L)
            },
            0,
        ).value
}