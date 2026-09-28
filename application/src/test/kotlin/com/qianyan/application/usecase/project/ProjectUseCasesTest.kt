package com.qianyan.application.usecase.project

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.qianyan.application.di.ApplicationContainer
import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.model.BaseNovelId
import com.qianyan.model.ChapterId
import com.qianyan.model.NovelId
import com.qianyan.model.ProjectId
import com.qianyan.model.TaskId
import com.qianyan.model.VariantId
import com.qianyan.model.core.VariantContext
import com.qianyan.provider.impl.MockLLMGateway
import com.qianyan.storage.db.QianyanDbFactory
import com.qianyan.storage.db.QianyanDbHandle
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * I1 · Project 聚合 + Project State 应用层测试。
 *
 * 覆盖（对应 I1 要求）：
 *  - Project：创建（复用既有 Novel 创建路径）/ 查询（novelId 与 projectId 入口）/ 与 Novel 身份关系 /
 *      activeVariant 可选关系 / **不复制 Novel 核心数据**。
 *  - ProjectState：创建 / 读取 / 更新 / **持久化恢复**（重建容器后仍在）/ 与 Project 正确关联。
 *  - 生命周期：Project 操作**不产生第二套 Workflow/Task 状态机**（不写 Workflow / Task 表）。
 *  - 链路：Application → Repository → Storage（真实 SQLite + 真实 Repository，无替身）。
 */
class ProjectUseCasesTest {

    private class Fixture(val app: ApplicationContainer, val handle: QianyanDbHandle) {
        fun close() = handle.driver.close()
    }

    private fun inMemory(): Fixture {
        val handle = QianyanDbFactory.open(JdbcSqliteDriver.IN_MEMORY)
        return Fixture(ApplicationContainer.fromDriver(handle.driver, MockLLMGateway()), handle)
    }

    private fun onFile(path: String): Fixture {
        val handle = QianyanDbFactory.open("jdbc:sqlite:${java.nio.file.Paths.get(path).toAbsolutePath()}")
        return Fixture(ApplicationContainer.fromDriver(handle.driver, MockLLMGateway()), handle)
    }

    private fun newVariant(app: ApplicationContainer, novelId: NovelId, variantId: String): VariantId =
        app.novels.createVariant(
            context = VariantContext(baseNovelId = BaseNovelId(novelId.value), variantId = VariantId(variantId)),
            name = "改线",
            variantId = VariantId(variantId),
        )

    // ---------- Project ----------

    @Test
    fun `create project reuses existing novel creation and links identity`() {
        val f = inMemory()
        val project = f.app.projects.createProject(title = "I1 书", genre = listOf("东方幻想"))

        val novels = f.app.novels.listOriginals()
        assertEquals(1, novels.size, "createProject 复用既有 Novel 创建路径（不存在第二条创建路径）")
        assertEquals(novels.first().novelId, project.novelId, "身份锚必须是既有 Novel")
        assertEquals(novels.first().projectId, project.projectId, "projectId 来自 Novel.projectId（不新建身份）")

        val state = assertNotNull(f.app.projects.state(project.projectId), "创建即建立运行态")
        assertEquals(project.novelId, state.novelId, "运行态必须与身份锚一致")
        assertTrue(state.isOriginalScope, "默认处于 Original 作用域")
        assertNull(state.activeChapterId)
        assertNull(state.activeTaskId)
        f.close()
    }

    @Test
    fun `project reads by novel id and by project id once opened`() {
        val f = inMemory()
        val created = f.app.projects.createProject(title = "I1 书")

        assertEquals(created, f.app.projects.projectOf(created.novelId))
        assertEquals(created, f.app.projects.projectOf(created.projectId))

        // 未建立运行态的项目：projectId 入口返回 null（不伪造），novelId 入口仍可用（返回默认运行态）
        val novelOnly = f.app.novels.createOriginal(title = "未打开的书")
        val novel = assertNotNull(f.app.novels.getNovel(novelOnly))
        assertNull(f.app.projects.projectOf(novel.projectId), "未打开的项目无运行态记录")
        val aggregated = f.app.projects.projectOf(novelOnly)
        assertEquals(novel.projectId, aggregated.projectId)
        assertNull(f.app.projects.state(novel.projectId), "只读聚合不落库（不伪造进度）")
        f.close()
    }

    @Test
    fun `project aggregates without copying novel metadata`() {
        val f = inMemory()
        val project = f.app.projects.createProject(title = "I1 书", genre = listOf("东方幻想"), synopsis = "简介")

        // 元数据唯一真源仍是 Novel（经既有 UseCase 读取）
        val novel = assertNotNull(f.app.novels.getNovel(project.novelId))
        assertEquals("I1 书", novel.title)
        // 聚合态不携带任何 Novel 元数据副本（结构守卫：无 title / genre / synopsis 字段）
        val rendered = project.state.toString()
        listOf("title", "genre", "synopsis", "scope").forEach {
            assertTrue(it !in rendered, "Project 聚合不得复制 Novel 元数据字段 '$it'")
        }
        f.close()
    }

    @Test
    fun `open project is idempotent`() {
        val f = inMemory()
        val project = f.app.projects.createProject(title = "I1 书")

        val again = f.app.projects.openProject(project.novelId)
        assertEquals(project, again)
        assertEquals(1L, count(f.handle, "ProjectState"), "重复打开不产生第二行运行态")

        // projectOf 对未打开项目不落库；openProject 才建立
        val novelOnly = f.app.novels.createOriginal(title = "未打开的书")
        f.app.projects.openProject(novelOnly)
        assertEquals(2L, count(f.handle, "ProjectState"))
        f.close()
    }

    // ---------- ProjectState ----------

    @Test
    fun `select variant supports original scope and rejects foreign variant`() {
        val f = inMemory()
        val a = f.app.projects.createProject(title = "书A")
        val b = f.app.projects.createProject(title = "书B")
        val variantOfA = newVariant(f.app, a.novelId, "v-a1")

        // null = Original 作用域（可选关系）
        assertTrue(f.app.projects.selectVariant(a.novelId, null).state.isOriginalScope)

        // 选择本项目的 Variant
        val selected = f.app.projects.selectVariant(a.novelId, variantOfA)
        assertEquals(variantOfA, selected.state.activeVariantId)

        // 跨项目 Variant 必须拒绝（复用既有 getVariantContext 校验）
        val ex = assertFailsWith<ApplicationException> { f.app.projects.selectVariant(b.novelId, variantOfA) }
        assertTrue(ex.error is ApplicationError.VariantMismatch, "跨 Novel 的 Variant 必须类型化拒绝")
        f.close()
    }

    @Test
    fun `select chapter validates ownership and scope`() {
        val f = inMemory()
        val a = f.app.projects.createProject(title = "书A")
        val b = f.app.projects.createProject(title = "书B")
        val chapterOfA = f.app.chapters.createNextChapter("第 1 章", a.novelId)

        // 正常选择（Original 作用域 → Original 章节）
        val selected = f.app.projects.selectChapter(a.novelId, chapterOfA.chapterId)
        assertEquals(chapterOfA.chapterId, selected.state.activeChapterId)

        // 清除选择
        assertNull(f.app.projects.selectChapter(a.novelId, null).state.activeChapterId)

        // 章节不属于该 Novel → 类型化拒绝
        val foreign = assertFailsWith<ApplicationException> {
            f.app.projects.selectChapter(b.novelId, chapterOfA.chapterId)
        }
        assertTrue(foreign.error is ApplicationError.InvalidOperation)

        // 章节不存在 → EntityNotFound
        val ghost = assertFailsWith<ApplicationException> {
            f.app.projects.selectChapter(a.novelId, ChapterId("ghost"))
        }
        assertTrue(ghost.error is ApplicationError.EntityNotFound)

        // 作用域不一致：切到 Variant 后不能继续选中 Original 章节
        val variantOfA = newVariant(f.app, a.novelId, "v-a1")
        f.app.projects.selectVariant(a.novelId, variantOfA)
        val mismatch = assertFailsWith<ApplicationException> {
            f.app.projects.selectChapter(a.novelId, chapterOfA.chapterId)
        }
        assertTrue(mismatch.error is ApplicationError.InvalidOperation, "章节作用域必须与当前工作作用域一致")
        f.close()
    }

    @Test
    fun `switching variant clears a chapter from the previous scope`() {
        val f = inMemory()
        val a = f.app.projects.createProject(title = "书A")
        val originalChapter = f.app.chapters.createNextChapter("第 1 章", a.novelId)
        f.app.projects.selectChapter(a.novelId, originalChapter.chapterId)
        assertEquals(originalChapter.chapterId, f.app.projects.state(a.projectId)?.activeChapterId)

        val variant = newVariant(f.app, a.novelId, "v-a1")
        f.app.projects.selectVariant(a.novelId, variant)

        val state = assertNotNull(f.app.projects.state(a.projectId))
        assertEquals(variant, state.activeVariantId)
        assertNull(state.activeChapterId, "切换作用域后不得保留跨作用域的工作章节（不变式）")
        f.close()
    }

    @Test
    fun `active task is stored as a reference only`() {
        val f = inMemory()
        val project = f.app.projects.createProject(title = "I1 书")

        val updated = f.app.projects.setActiveTask(project.novelId, TaskId("t-1"))
        assertEquals(TaskId("t-1"), updated.state.activeTaskId)
        assertEquals(TaskId("t-1"), f.app.projects.state(project.projectId)?.activeTaskId)

        assertNull(f.app.projects.setActiveTask(project.novelId, null).state.activeTaskId)
        f.close()
    }

    @Test
    fun `project state persists across container reopen`() {
        val dir = Files.createTempDirectory("qianyan-i1-state")
        val path = dir.resolve("qianyan.db").toString()

        val first = onFile(path)
        val project = first.app.projects.createProject(title = "持久化书")
        val variant = newVariant(first.app, project.novelId, "v-p1")
        // 章节必须与当前工作作用域一致（Original 章节不能在 Variant 作用域下被选中）
        val chapter = first.app.chapters.createNextChapter("第 1 章", project.novelId, variant)
        first.app.projects.selectVariant(project.novelId, variant)
        first.app.projects.selectChapter(project.novelId, chapter.chapterId)
        first.app.projects.setActiveTask(project.novelId, TaskId("t-persist"))
        first.close()

        // 重建容器（真实 SQLite 文件）→ 运行态必须恢复
        val second = onFile(path)
        val restored = assertNotNull(second.app.projects.state(project.projectId), "运行态必须跨容器恢复")
        assertEquals(project.novelId, restored.novelId)
        assertEquals(variant, restored.activeVariantId)
        assertEquals(chapter.chapterId, restored.activeChapterId)
        assertEquals(TaskId("t-persist"), restored.activeTaskId)
        // projectId 入口同样可读到该聚合（身份一致；运行态即上面恢复的值）
        val reopened = assertNotNull(second.app.projects.projectOf(project.projectId))
        assertEquals(project.projectId, reopened.projectId)
        assertEquals(project.novelId, reopened.novelId)
        assertEquals(restored, reopened.state)
        second.close()
    }

    // ---------- 生命周期边界 ----------

    @Test
    fun `project operations do not create a second workflow or task state machine`() {
        val f = inMemory()
        val project = f.app.projects.createProject(title = "I1 书")
        val chapter = f.app.chapters.createNextChapter("第 1 章", project.novelId)
        f.app.projects.openProject(project.novelId)
        f.app.projects.selectChapter(project.novelId, chapter.chapterId)
        f.app.projects.setActiveTask(project.novelId, TaskId("t-x"))

        assertEquals(0L, count(f.handle, "Workflow"), "Project 不建立 Workflow（生命周期仍以既有 Workflow 为准）")
        assertEquals(0L, count(f.handle, "Task"), "Project 不建立 Task（Task 状态仍以既有 Task/Checkpoint 为准）")
        assertNull(f.app.workflows.getWorkflowByActiveChapter(chapter.chapterId), "章节不应被 Project 操作挂上 Workflow")
        f.close()
    }

    private fun count(handle: QianyanDbHandle, table: String): Long =
        handle.driver.executeQuery(
            null,
            "SELECT COUNT(*) FROM $table",
            { cursor ->
                cursor.next()
                QueryResult.Value(cursor.getLong(0) ?: 0L)
            },
            0,
        ).value
}