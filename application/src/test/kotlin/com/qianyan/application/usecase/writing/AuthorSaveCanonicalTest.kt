package com.qianyan.application.usecase.writing

import com.qianyan.application.di.ApplicationContainer
import com.qianyan.engine.markdown.MarkdownBlock
import com.qianyan.model.ChapterId
import com.qianyan.model.DraftId
import com.qianyan.model.NovelId
import com.qianyan.model.writing.Draft
import com.qianyan.model.writing.DraftFormat
import com.qianyan.model.writing.DraftStatus
import com.qianyan.provider.ChatMessage
import com.qianyan.provider.ChatRole
import com.qianyan.provider.FinishReason
import com.qianyan.provider.ProviderResponse
import com.qianyan.provider.Usage
import com.qianyan.provider.impl.MockLLMGateway
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P1-01f · 作者手改保存 → Canonical 最小闭环（v3.1 §4.1「保存即批准」；R3 §23B）。
 *
 * 覆盖真实 Application 链路（内存库 + Mock LLM，无网络）：
 *  - `writerGateway.saveContent`（= Desktop/Android Writer 的保存入口）→ `CommitUseCases.commitAuthorSave`
 *    → `CommitHistory` → `canonicalRead` → `reading.openChapter`（Reader）；
 *  - AI 产出（continue / rewrite / 低层 Draft 写）**不**产生 Canonical。
 */
class AuthorSaveCanonicalTest {

    /* ---------------- Case B：首次保存 ---------------- */

    @Test
    fun `first author save creates canonical and reader shows saved content`() {
        val app = ApplicationContainer.open(analysisGateway = MockLLMGateway())
        val novelId = app.novels.createOriginal(title = "测试书")
        val chapter = app.chapters.createNextChapter(title = "第一章", novelId = novelId)
        val draft = draft(novelId, chapter.chapterId, "d-author", "初稿正文")
        app.draftRepository.save(draft)

        assertNull(app.canonicalRead.committedContent(chapter.chapterId), "保存前不应有 Canonical")

        val saved = app.writerGateway.saveContent(draft.draftId, "作者保存的正文")

        assertEquals(draft.draftId, saved.draftId, "编辑稿身份不变")
        assertEquals("作者保存的正文", app.canonicalRead.committedContent(chapter.chapterId), "保存即产生 Canonical")
        assertEquals(
            listOf(MarkdownBlock.Paragraph("作者保存的正文")),
            app.reading.openChapter(novelId, null, chapter.chapterId).blocks,
            "Reader 读到刚保存的 Canonical 正文",
        )
        val history = app.commits.history(app.projects.projectOf(novelId).projectId)
        assertEquals(1, history.size, "应产生一条 CommitHistory")
        assertEquals(null, history.first().artifactId, "作者手改无 Change Artifact")
        assertEquals("作者保存的正文", history.first().resultingContent)
    }

    /* ---------------- Case A：覆盖保存 ---------------- */

    @Test
    fun `author save overwrites existing canonical and reader follows`() {
        val app = ApplicationContainer.open(analysisGateway = MockLLMGateway())
        val novelId = app.novels.createOriginal(title = "测试书")
        val chapter = app.chapters.createNextChapter(title = "第一章", novelId = novelId)
        val draft = draft(novelId, chapter.chapterId, "d-author", "初稿正文")
        app.draftRepository.save(draft)
        val projectId = app.projects.projectOf(novelId).projectId

        app.writerGateway.saveContent(draft.draftId, "Canonical A")
        assertEquals("Canonical A", app.canonicalRead.committedContent(chapter.chapterId))

        app.writerGateway.saveContent(draft.draftId, "Canonical B")

        assertEquals("Canonical B", app.canonicalRead.committedContent(chapter.chapterId), "保存后 Canonical 更新为 B")
        assertEquals(
            listOf(MarkdownBlock.Paragraph("Canonical B")),
            app.reading.openChapter(novelId, null, chapter.chapterId).blocks,
        )
        assertEquals(draft.draftId, app.draftRepository.latestByChapter(chapter.chapterId)?.draftId, "同一 Draft 身份保存")
        val history = app.commits.history(projectId)
        assertEquals(2, history.size)
        assertEquals("Canonical A", history[0].resultingContent)
        assertEquals("Canonical B", history[1].resultingContent)
        assertEquals(draft.draftId, history[1].resultingDraftId, "作者编辑稿自身即 Canonical 载体")
        assertNull(history[1].previousDraftId, "同一 Draft 重复保存不自链")
        assertEquals("Canonical A", history[1].previousContent, "基线取上一已提交快照，不是编辑稿旧正文")
    }

    @Test
    fun `author save on a different draft chains to the previous canonical`() {
        val app = ApplicationContainer.open(analysisGateway = MockLLMGateway())
        val novelId = app.novels.createOriginal(title = "测试书")
        val chapter = app.chapters.createNextChapter(title = "第一章", novelId = novelId)
        val first = draft(novelId, chapter.chapterId, "d-one", "初稿正文")
        app.draftRepository.save(first)
        app.writerGateway.saveContent(first.draftId, "Canonical A")

        val second = draft(novelId, chapter.chapterId, "d-two", "换了稿纸")
        app.draftRepository.save(second)
        app.writerGateway.saveContent(second.draftId, "Canonical B")

        val history = app.commits.history(app.projects.projectOf(novelId).projectId)
        assertEquals(2, history.size)
        assertEquals(first.draftId, history[1].previousDraftId, "跨 Draft 版本链指向上一次 Canonical 载体")
        assertEquals(second.draftId, history[1].resultingDraftId)
        assertEquals("Canonical A", history[1].previousContent)
    }

    /* ---------------- FD-1：legacy 草稿保存不被静默迁移 ---------------- */

    @Test
    fun `author save keeps legacy draft format and reader shows plain text`() {
        val app = ApplicationContainer.open(analysisGateway = MockLLMGateway())
        val novelId = app.novels.createOriginal(title = "测试书")
        val chapter = app.chapters.createNextChapter(title = "第一章", novelId = novelId)
        val legacy = draft(novelId, chapter.chapterId, "d-legacy", "旧正文（legacy 纯文本）").copy(
            format = DraftFormat.PLAIN_TEXT,
            status = DraftStatus.DRAFTING,
        )
        app.draftRepository.save(legacy)

        app.writerGateway.saveContent(legacy.draftId, "旧正文（legacy 纯文本）—— 改了一行")

        val reread = assertNotNull(app.draftRepository.latestByChapter(chapter.chapterId))
        assertEquals(legacy.draftId, reread.draftId)
        assertNull(reread.format, "普通保存不得把 legacy Draft 静默迁移为受控 Markdown")
        assertEquals(DraftStatus.DRAFTING, reread.status, "保存不得改变 Draft.status")
        assertEquals("旧正文（legacy 纯文本）—— 改了一行", app.canonicalRead.committedContent(chapter.chapterId))
        val view = app.reading.openChapter(novelId, null, chapter.chapterId)
        assertNull(view.format, "Canonical 仍为 legacy 纯文本")
        assertEquals(listOf(MarkdownBlock.Paragraph("旧正文（legacy 纯文本）—— 改了一行")), view.blocks)
    }

    /* ---------------- Case C：未保存的新 Draft 不影响 Canonical ---------------- */

    @Test
    fun `unsaved newer draft does not affect canonical`() {
        val app = ApplicationContainer.open(analysisGateway = MockLLMGateway())
        val novelId = app.novels.createOriginal(title = "测试书")
        val chapter = app.chapters.createNextChapter(title = "第一章", novelId = novelId)
        val draft = draft(novelId, chapter.chapterId, "d-author", "初稿正文")
        app.draftRepository.save(draft)
        app.writerGateway.saveContent(draft.draftId, "Canonical A")

        // 更晚的未提交 Draft（未经作者保存）
        val later = Instant.fromEpochMilliseconds(Long.MAX_VALUE)
        val uncommitted = DraftId("d-uncommitted")
        app.draftRepository.save(
            draft(novelId, chapter.chapterId, uncommitted.value, "未提交草稿").copy(
                previousDraftId = draft.draftId,
                createdAt = later,
                updatedAt = later,
            ),
        )
        assertEquals(uncommitted, app.draftRepository.latestByChapter(chapter.chapterId)?.draftId, "前置：最新 Draft 为未提交")

        assertEquals("Canonical A", app.canonicalRead.committedContent(chapter.chapterId), "未保存 Draft 不得改变 Canonical")
        assertEquals(
            listOf(MarkdownBlock.Paragraph("Canonical A")),
            app.reading.openChapter(novelId, null, chapter.chapterId).blocks,
            "Reader 仍读旧 Canonical",
        )
    }

    /* ---------------- Case D：AI 路径不产生 Canonical ---------------- */

    @Test
    fun `ai rewrite does not create canonical and reader stays empty`() {
        val app = ApplicationContainer.open(analysisGateway = aiGateway())
        val novelId = app.novels.createOriginal(title = "测试书")
        val chapter = app.chapters.createNextChapter(title = "第一章", novelId = novelId)
        app.draftRepository.save(draft(novelId, chapter.chapterId, "d-orig", "原正文"))

        val revised = app.writerGateway.rewrite(novelId, null, chapter.chapterId)

        assertEquals(DraftStatus.REVISED, revised.status, "AI 改写产出修订稿")
        assertNull(app.canonicalRead.committedContent(chapter.chapterId), "AI 产出不得自动成为 Canonical")
        assertTrue(app.commits.history(app.projects.projectOf(novelId).projectId).isEmpty(), "AI 路径不得写 CommitHistory")
        assertTrue(
            app.reading.openChapter(novelId, null, chapter.chapterId).blocks.isEmpty(),
            "未提交 → Reader 无 Canonical 内容（不 fallback 到 Draft）",
        )
    }

    @Test
    fun `low level saveContent alone does not create canonical`() {
        val app = ApplicationContainer.open(analysisGateway = MockLLMGateway())
        val novelId = app.novels.createOriginal(title = "测试书")
        val chapter = app.chapters.createNextChapter(title = "第一章", novelId = novelId)
        val draft = draft(novelId, chapter.chapterId, "d-low", "初稿正文")
        app.draftRepository.save(draft)

        // 低层写入（AI / 其它调用者使用）：只改正文，不产生 Canonical
        app.writerUseCases.saveContent(draft.draftId, "只改正文")

        assertEquals("只改正文", app.draftRepository.getById(draft.draftId)?.content, "低层保存仍然落库")
        assertNull(app.canonicalRead.committedContent(chapter.chapterId), "低层保存不产生 Canonical")
        assertTrue(app.commits.history(app.projects.projectOf(novelId).projectId).isEmpty())
    }

    /* ---------------- helpers ---------------- */

    private fun draft(novelId: NovelId, chapterId: ChapterId, id: String, content: String) = Draft(
        draftId = DraftId(id),
        novelId = novelId,
        chapterId = chapterId,
        content = content,
        format = DraftFormat.CONTROLLED_MARKDOWN,
        status = DraftStatus.WRITTEN,
        createdAt = Clock.System.now(),
        updatedAt = Clock.System.now(),
    )

    /** AI 网关：Critique 通过 + Revision 产出修订稿（与既有 WriterGateway 集成测试同形）。 */
    private fun aiGateway(): MockLLMGateway = MockLLMGateway { req ->
        val system = req.messages.first { it.role == ChatRole.SYSTEM }.content
        val body = when {
            "StoryRevisionAgent" in system -> """{"content":"修订后的正文"}"""
            "StoryCriticAgent" in system -> """{"passed":true}"""
            else -> """{"content":"AI 生成的正文"}"""
        }
        ProviderResponse(
            message = ChatMessage(ChatRole.ASSISTANT, buildJsonObject { put("answer", body) }.toString()),
            usage = Usage(1, 1, 2),
            finishReason = FinishReason.STOP,
        )
    }
}
