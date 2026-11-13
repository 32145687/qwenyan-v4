package com.qianyan.application.usecase.writing

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.qianyan.application.di.ApplicationContainer
import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.model.writing.DraftStatus
import com.qianyan.provider.ChatMessage
import com.qianyan.provider.ChatRole
import com.qianyan.provider.FinishReason
import com.qianyan.provider.ProviderResponse
import com.qianyan.provider.Usage
import com.qianyan.provider.impl.MockLLMGateway
import com.qianyan.storage.db.QianyanDbFactory
import com.qianyan.storage.db.QianyanDbHandle
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * O-84（T-O84-F）：空章节作者首稿保存。
 *
 * 断言口径：file-backed SQLite（可直接二次查询 Draft / CommitHistory），不经内存对象。
 *  - 首稿：确定性 DraftId `<chapterId>#author-first`、status=WRITTEN、format=null、content=入参、1 条「作者手动保存（保存即批准）」；
 *  - 幂等：再次调用 Draft 行数不变、CommitHistory 再增 1 条；
 *  - 空正文：抛 InvalidOperation 且不产生任何行。
 */
class FirstDraftAuthorSaveTest {

    private fun gateway(): MockLLMGateway = MockLLMGateway { _ ->
        ProviderResponse(
            message = ChatMessage(ChatRole.ASSISTANT, """{"answer":"{\"content\":\"AI 生成正文\"}"}"""),
            usage = Usage(1, 1, 2),
            finishReason = FinishReason.STOP,
        )
    }

    private fun withEnv(block: (ApplicationContainer, QianyanDbHandle) -> Unit) {
        val tmp = Files.createTempFile("qianyan-o84", ".db")
        val handle = QianyanDbFactory.open("jdbc:sqlite:$tmp")
        try {
            block(ApplicationContainer.fromDriver(handle.driver, analysisGateway = gateway()), handle)
        } finally {
            runCatching { (handle.driver as JdbcSqliteDriver).getConnection().close() }
            Files.deleteIfExists(tmp)
        }
    }

    private fun draftRowCount(handle: QianyanDbHandle, chapterId: String): Int {
        val connection = (handle.driver as JdbcSqliteDriver).getConnection()
        return connection.createStatement().use { statement ->
            statement.executeQuery("SELECT COUNT(*) FROM ChapterDraft WHERE chapter_id = '$chapterId'").use { rs ->
                rs.next(); rs.getInt(1)
            }
        }
    }

    @Test
    fun `first draft save writes one author draft and one commit history`() {
        withEnv { app, handle ->
            val novelId = app.novels.createOriginal(title = "空章节")
            val chapter = app.chapters.createNextChapter(title = "第一章", novelId = novelId)
            val projectId = app.projects.projectOf(novelId).projectId
            assertEquals(0, app.draftRepository.listByChapter(chapter.chapterId).size, "前置：空章节无 Draft")

            val saved = app.writerGateway.saveFirstDraft(chapter.chapterId, "  第一章的第一段正文。  ")

            assertEquals("${chapter.chapterId.value}#author-first", saved.draftId.value)
            assertEquals(DraftStatus.WRITTEN, saved.status)
            assertNull(saved.format, "首稿保持 legacy 纯文本（format=null）")
            assertNull(saved.planId)
            assertNull(saved.previousDraftId)
            assertEquals("第一章的第一段正文。", saved.content, "正文按 trim 后写入")
            assertEquals(1, draftRowCount(handle, chapter.chapterId.value), "Draft 表新增恰 1 行")
            assertEquals(1, app.draftRepository.listByChapter(chapter.chapterId).size)

            val history = app.commits.history(projectId)
            assertEquals(1, history.size, "CommitHistory 新增恰 1 条")
            assertEquals("作者手动保存（保存即批准）", history.single().summary)
            assertEquals("第一章的第一段正文。", app.canonicalRead.committedContent(chapter.chapterId))
        }
    }

    @Test
    fun `first draft save is idempotent on draft row and appends one commit per save`() {
        withEnv { app, handle ->
            val novelId = app.novels.createOriginal(title = "空章节幂等")
            val chapter = app.chapters.createNextChapter(title = "第一章", novelId = novelId)
            val projectId = app.projects.projectOf(novelId).projectId

            app.writerGateway.saveFirstDraft(chapter.chapterId, "第一版正文")
            val second = app.writerGateway.saveFirstDraft(chapter.chapterId, "第二版正文")

            assertEquals("${chapter.chapterId.value}#author-first", second.draftId.value)
            assertEquals(1, draftRowCount(handle, chapter.chapterId.value), "重复调用不得产生第二行 Draft")
            assertEquals(1, app.draftRepository.listByChapter(chapter.chapterId).size)
            assertEquals(2, app.commits.history(projectId).size, "每次保存追加一条 CommitHistory")
            assertEquals("第二版正文", app.canonicalRead.committedContent(chapter.chapterId))
        }
    }

    @Test
    fun `blank content rejects first draft save without writing anything`() {
        withEnv { app, handle ->
            val novelId = app.novels.createOriginal(title = "空章节空正文")
            val chapter = app.chapters.createNextChapter(title = "第一章", novelId = novelId)
            val projectId = app.projects.projectOf(novelId).projectId

            val ex = assertFailsWith<ApplicationException> {
                app.writerGateway.saveFirstDraft(chapter.chapterId, "   \n\t ")
            }
            assertIs<ApplicationError.InvalidOperation>(ex.error)
            assertTrue(ex.error.toString().contains("正文为空"), "错误文案应含「正文为空」，实际=${ex.error}")
            assertEquals(0, draftRowCount(handle, chapter.chapterId.value), "不得创建 Draft")
            assertTrue(app.commits.history(projectId).isEmpty(), "不得写 CommitHistory")
        }
    }
}
