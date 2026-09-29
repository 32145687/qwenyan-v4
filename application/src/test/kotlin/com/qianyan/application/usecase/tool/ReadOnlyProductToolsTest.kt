package com.qianyan.application.usecase.tool

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.qianyan.application.di.ApplicationContainer
import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.model.DraftId
import com.qianyan.model.NovelId
import com.qianyan.model.VocabularyCandidateId
import com.qianyan.model.VocabularyEntryId
import com.qianyan.model.agent.ToolName
import com.qianyan.model.vocabulary.VocabularyCandidate
import com.qianyan.model.vocabulary.VocabularyEntry
import com.qianyan.model.vocabulary.VocabularyEntryType
import com.qianyan.model.writing.Draft
import com.qianyan.model.writing.DraftStatus
import com.qianyan.provider.impl.MockLLMGateway
import com.qianyan.storage.db.QianyanDbFactory
import com.qianyan.storage.db.QianyanDbHandle
import kotlinx.datetime.Clock
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * I5 · 只读 Product Tool 行为测试。
 *
 * 覆盖：每个 Tool 的正常输入/正常返回/不存在/非法输入、Project 隔离（Project A 不得读到 Project B）、
 * 只读守卫（调用前后 Canonical 数据不变）、源码结构守卫（不直连存储、只经 Application 能力）。
 */
class ReadOnlyProductToolsTest {

    private class Fixture(val app: ApplicationContainer, val handle: QianyanDbHandle) {
        fun close() = handle.driver.close()
    }

    private fun fixture(): Fixture {
        val handle = QianyanDbFactory.open(JdbcSqliteDriver.IN_MEMORY)
        return Fixture(ApplicationContainer.fromDriver(handle.driver, MockLLMGateway()), handle)
    }

    /** 两个真实 Project（A / B）各带章节、草稿、词汇候选与 ProjectState。 */
    private class World(
        val novelA: NovelId, val novelB: NovelId,
        val chapterA1: com.qianyan.model.ChapterId, val chapterB1: com.qianyan.model.ChapterId,
        val projectA: com.qianyan.model.ProjectId, val projectB: com.qianyan.model.ProjectId,
    )

    private fun seed(f: Fixture): World {
        val now = Clock.System.now()
        val novelA = f.app.novels.createOriginal(title = "书A")
        val novelB = f.app.novels.createOriginal(title = "书B")
        val chapterA1 = f.app.chapters.createNextChapter("A-1", novelA).chapterId
        val chapterB1 = f.app.chapters.createNextChapter("B-1", novelB).chapterId
        f.app.draftRepository.save(
            Draft(
                draftId = DraftId("d-a1"), novelId = novelA, chapterId = chapterA1,
                content = "A 正文", status = DraftStatus.WRITTEN, createdAt = now, updatedAt = now,
            ),
        )
        f.app.draftRepository.save(
            Draft(
                draftId = DraftId("d-b1"), novelId = novelB, chapterId = chapterB1,
                content = "B 正文", status = DraftStatus.WRITTEN, createdAt = now, updatedAt = now,
            ),
        )
        val vocabA = f.app.vocabularies.getOrCreateNovelVocabulary(novelA)
        f.app.vocabularyRepository.saveCandidate(
            VocabularyCandidate(
                candidateId = VocabularyCandidateId("vc-a1"), vocabularyId = vocabA, novelId = novelA,
                suggested = VocabularyEntry(
                    entryId = VocabularyEntryId("ve-a1"), vocabularyId = vocabA, novelId = novelA,
                    canonical = "苏清", aliases = listOf("苏青"), type = VocabularyEntryType.CHARACTER_APPELLATION,
                ),
                createdAt = now,
            ),
        )
        f.app.projects.selectChapter(novelA, chapterA1)
        f.app.projects.selectChapter(novelB, chapterB1)
        return World(
            novelA, novelB, chapterA1, chapterB1,
            f.app.projects.projectOf(novelA).projectId, f.app.projects.projectOf(novelB).projectId,
        )
    }

    private fun tool(f: Fixture, name: String): ReadOnlyProductTool =
        assertNotNull(f.app.productTools.find(ToolName(name)), "工具 $name 必须已注册") as ReadOnlyProductTool

    // ---------- get_project / get_project_state ----------

    @Test
    fun `get project and project state return current project data`() {
        val f = fixture()
        val w = seed(f)

        val project = (tool(f, "get_project") as GetProjectTool)
            .call(w.projectA, GetProjectInput(w.projectA.value))
        assertEquals(w.projectA, project.projectId)
        assertEquals(w.novelA, project.novelId)
        assertEquals(w.chapterA1, project.activeChapterId)

        val state = (tool(f, "get_project_state") as GetProjectStateTool)
            .call(w.projectA, GetProjectStateInput(w.projectA.value))
        assertNotNull(state.state)
        assertEquals(w.projectA, state.state?.projectId)
        assertEquals(w.novelA, state.state?.novelId)
        assertEquals(w.chapterA1, state.state?.activeChapterId)
        f.close()
    }

    @Test
    fun `get project and project state cannot cross project boundary`() {
        val f = fixture()
        val w = seed(f)

        listOf<() -> Unit>(
            { (tool(f, "get_project") as GetProjectTool).call(w.projectA, GetProjectInput(w.projectB.value)) },
            { (tool(f, "get_project_state") as GetProjectStateTool).call(w.projectA, GetProjectStateInput(w.projectB.value)) },
        ).forEach { op ->
            val ex = assertFailsWith<ApplicationException> { op() }
            assertTrue(ex.error is ApplicationError.EntityNotFound, "Project B 对 Project A 不可见（NOT_FOUND，不泄漏存在性）")
        }
        f.close()
    }

    // ---------- get_novel ----------

    @Test
    fun `get novel returns novel view and rejects unknown novel`() {
        val f = fixture()
        val w = seed(f)
        val getNovel = tool(f, "get_novel") as GetNovelTool

        val view = getNovel.call(w.projectA, GetNovelInput(w.novelA.value))
        assertEquals(w.novelA, view.novelId)
        assertEquals(w.projectA, view.projectId)
        assertEquals("书A", view.title)

        val ex = assertFailsWith<ApplicationException> { getNovel.call(w.projectA, GetNovelInput("n-ghost")) }
        assertTrue(ex.error is ApplicationError.EntityNotFound)
        val exBlank = assertFailsWith<ApplicationException> { getNovel.call(w.projectA, GetNovelInput("  ")) }
        assertTrue(exBlank.error is ApplicationError.InvalidOperation, "非法输入（空 ID）→ InvalidOperation")
        f.close()
    }

    @Test
    fun `novel from another project is not visible`() {
        val f = fixture()
        val w = seed(f)
        val ex = assertFailsWith<ApplicationException> {
            (tool(f, "get_novel") as GetNovelTool).call(w.projectA, GetNovelInput(w.novelB.value))
        }
        assertTrue(ex.error is ApplicationError.EntityNotFound, "Project A 不得读到 Project B 的 Novel")
        f.close()
    }

    // ---------- list_chapters / get_chapter ----------

    @Test
    fun `list chapters and get chapter return chapter views`() {
        val f = fixture()
        val w = seed(f)
        f.app.chapters.createNextChapter("A-2", w.novelA)

        val list = (tool(f, "list_chapters") as ListChaptersTool)
            .call(w.projectA, ListChaptersInput(w.novelA.value))
        assertEquals(2, list.chapters.size, "按 novelId 列出章节")
        assertEquals(listOf("A-1", "A-2"), list.chapters.map { it.title })

        val chapter = (tool(f, "get_chapter") as GetChapterTool)
            .call(w.projectA, GetChapterInput(w.chapterA1.value))
        assertEquals(w.chapterA1, chapter.chapterId)
        assertEquals(w.novelA, chapter.novelId)
        assertEquals("A-1", chapter.title)
        f.close()
    }

    @Test
    fun `chapter tools reject unknown blank and cross project input`() {
        val f = fixture()
        val w = seed(f)
        val getChapter = tool(f, "get_chapter") as GetChapterTool
        val listChapters = tool(f, "list_chapters") as ListChaptersTool

        assertTrue(
            assertFailsWith<ApplicationException> { getChapter.call(w.projectA, GetChapterInput("c-ghost")) }
                .error is ApplicationError.EntityNotFound,
        )
        assertTrue(
            assertFailsWith<ApplicationException> { getChapter.call(w.projectA, GetChapterInput("")) }
                .error is ApplicationError.InvalidOperation,
        )
        assertTrue(
            assertFailsWith<ApplicationException> { getChapter.call(w.projectA, GetChapterInput(w.chapterB1.value)) }
                .error is ApplicationError.EntityNotFound,
            "Project A 不得读到 Project B 的 Chapter",
        )
        assertTrue(
            assertFailsWith<ApplicationException> { listChapters.call(w.projectA, ListChaptersInput(w.novelB.value)) }
                .error is ApplicationError.EntityNotFound,
            "Project A 不得列出 Project B 的章节",
        )
        f.close()
    }

    // ---------- get_latest_draft ----------

    @Test
    fun `get latest draft returns draft view and respects project scope`() {
        val f = fixture()
        val w = seed(f)
        val getDraft = tool(f, "get_latest_draft") as GetLatestDraftTool

        val view = getDraft.call(w.projectA, GetLatestDraftInput(w.chapterA1.value))
        assertEquals(DraftId("d-a1"), view.draftId)
        assertEquals("A 正文", view.content)
        assertEquals(DraftStatus.WRITTEN.name, view.status)

        assertTrue(
            assertFailsWith<ApplicationException> { getDraft.call(w.projectA, GetLatestDraftInput("c-ghost")) }
                .error is ApplicationError.EntityNotFound,
        )
        assertTrue(
            assertFailsWith<ApplicationException> { getDraft.call(w.projectA, GetLatestDraftInput(w.chapterB1.value)) }
                .error is ApplicationError.EntityNotFound,
            "Project A 不得读到 Project B 的草稿",
        )
        f.close()
    }

    // ---------- search_vocabulary ----------

    @Test
    fun `search vocabulary filters candidates by query and respects scope`() {
        val f = fixture()
        val w = seed(f)
        val search = tool(f, "search_vocabulary") as SearchVocabularyTool

        val all = search.call(w.projectA, SearchVocabularyInput(w.novelA.value))
        assertEquals(1, all.candidates.size)
        assertEquals("苏清", all.candidates.first().canonical)

        val filtered = search.call(w.projectA, SearchVocabularyInput(w.novelA.value, query = "苏清"))
        assertEquals(1, filtered.candidates.size)
        assertEquals(
            0,
            search.call(w.projectA, SearchVocabularyInput(w.novelA.value, query = "不存在的词")).candidates.size,
            "无命中 → 空列表（不是错误）",
        )
        assertTrue(
            assertFailsWith<ApplicationException> { search.call(w.projectA, SearchVocabularyInput(w.novelB.value)) }
                .error is ApplicationError.EntityNotFound,
            "Project A 不得检索 Project B 的词汇",
        )
        f.close()
    }

    // ---------- 只读守卫 ----------

    @Test
    fun `invoking all product tools leaves canonical data unchanged`() {
        val f = fixture()
        val w = seed(f)
        val before = Triple(
            f.app.novels.getNovel(w.novelA),
            f.app.chapters.findById(w.chapterA1),
            f.app.draftRepository.getById(DraftId("d-a1")),
        )
        val stateBefore = f.app.projects.state(w.projectA)
        val activityId = activityOf(f, w)

        listOf(
            ToolRequestX("get_project", """{"projectId":"${w.projectA.value}"}"""),
            ToolRequestX("get_project_state", """{"projectId":"${w.projectA.value}"}"""),
            ToolRequestX("get_novel", """{"novelId":"${w.novelA.value}"}"""),
            ToolRequestX("list_chapters", """{"novelId":"${w.novelA.value}"}"""),
            ToolRequestX("get_chapter", """{"chapterId":"${w.chapterA1.value}"}"""),
            ToolRequestX("get_latest_draft", """{"chapterId":"${w.chapterA1.value}"}"""),
            ToolRequestX("search_vocabulary", """{"novelId":"${w.novelA.value}"}"""),
        ).forEach { (name, args) ->
            val result = f.app.productTools.invoke(
                activityId,
                com.qianyan.model.tool.ToolRequest(
                    ToolName(name),
                    kotlinx.serialization.json.Json.parseToJsonElement(args) as kotlinx.serialization.json.JsonObject,
                ),
            )
            assertTrue(result.success, "$name 应成功返回")
        }

        assertEquals(before.first, f.app.novels.getNovel(w.novelA), "Novel 不得被修改")
        assertEquals(before.second, f.app.chapters.findById(w.chapterA1), "Chapter 不得被修改")
        assertEquals(before.third, f.app.draftRepository.getById(DraftId("d-a1")), "Draft 不得被修改")
        assertEquals(stateBefore, f.app.projects.state(w.projectA), "ProjectState 不得被修改")
        f.close()
    }

    // ---------- 结构守卫 ----------

    @Test
    fun `product tool sources stay read only and layered`() {
        val dir = File("src/main/kotlin/com/qianyan/application/usecase/tool")
        assertTrue(dir.isDirectory, "找不到 Product Tool 源码目录：${dir.absolutePath}")
        val code = dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.joinToString("\n") { file ->
            file.readText().lines()
                .filterNot { line ->
                    val t = line.trimStart()
                    t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
                }
                .joinToString("\n")
        }
        listOf(
            // 不得直连存储 / 绕过 Application 能力
            "SqlDriver", "DatabaseInitializer", "QianyanDb", "storage.db", "storage.repository",
            "Repository", "ApplicationContainer",
            // 不得吸收写路径 / 后续阶段职责
            "saveContent", "createOriginal", "createNextChapter", "confirmFinalDraft", "stampControlledMarkdown",
            "approveGate", "WorkflowOrchestrator", "ContextPack", "WorkingDraft", "LLMGateway",
        ).forEach { token -> assertTrue(token !in code, "Product Tool 不得出现 '$token'（只读 + 分层）") }
    }

    // ---------- helpers ----------

    private fun activityOf(f: Fixture, w: World): com.qianyan.model.ActivityId {
        val session = f.app.agentSessions.startSession(w.novelA)
        return f.app.activities.start(session.sessionId, "I5_TEST").activityId
    }

    private data class ToolRequestX(val name: String, val args: String)
}