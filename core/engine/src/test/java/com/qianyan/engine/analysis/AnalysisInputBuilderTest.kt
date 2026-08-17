package com.qianyan.engine.analysis

import com.qianyan.model.NovelId
import com.qianyan.model.TextBlockId
import com.qianyan.model.TxtChapterId
import com.qianyan.model.TxtDocumentId
import com.qianyan.model.txt.SourceLocation
import com.qianyan.model.txt.TextBlock
import com.qianyan.model.txt.TxtChapter
import com.qianyan.model.txt.TxtDocument
import com.qianyan.model.txt.TxtEncoding
import com.qianyan.model.txt.TxtParseStatus
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** P6.4 确定性 TXT→AnalysisInput 测试（core:engine，不触库/不调 LLM）。 */
class AnalysisInputBuilderTest {

    private val novelId = NovelId("novel-a")
    private val docId = TxtDocumentId("doc-1")
    private val doc = TxtDocument(
        documentId = docId,
        novelId = novelId,
        sourceName = "novel.txt",
        title = "我的小说",
        encoding = TxtEncoding.UTF8,
        hadBom = false,
        byteCount = 100,
        charCount = 40,
        originalText = "第一章\n\n正文1。\n\n第二章\n\n正文2。",
        normalizedText = "第一章\n\n正文1。\n\n第二章\n\n正文2。",
        contentHash = "hash",
        ruleVersion = "1;1",
        status = TxtParseStatus.SUCCESS,
        createdAt = Instant.parse("2026-01-01T00:00:00Z"),
    )

    private fun ch(id: String, ordinal: Int, title: String, start: Int, end: Int) = TxtChapter(
        chapterId = TxtChapterId("$docId#$id"),
        documentId = docId,
        novelId = novelId,
        ordinal = ordinal,
        title = title,
        sourceLocation = SourceLocation(start, end),
        firstBlockOrdinal = 0,
        blockCount = 1,
    )

    private fun blk(chapterId: String, id: String, ordinal: Int, text: String, start: Int, end: Int) = TextBlock(
        blockId = TextBlockId("$docId#$id"),
        chapterId = TxtChapterId("$docId#$chapterId"),
        documentId = docId,
        novelId = novelId,
        ordinal = ordinal,
        text = text,
        sourceLocation = SourceLocation(start, end),
    )

    private val c0 = ch("c0", 0, "第一章", 0, 3)
    private val c1 = ch("c1", 1, "第二章", 6, 9)
    private val b0 = blk("c0", "b0", 0, "正文1。", 4, 7)
    private val b1 = blk("c1", "b1", 1, "正文2。", 12, 15)

    @Test
    fun `sorts chapters and blocks by ordinal deterministically`() {
        // 乱序输入 → 章节/段块按 ordinal 升序稳定输出
        val input = AnalysisInputBuilder.build(doc, listOf(c1, c0), listOf(b1, b0))
        assertEquals(2, input.chapters.size)
        assertEquals(listOf(0, 1), input.chapters.map { it.ordinal })
        assertEquals("第一章", input.chapters[0].title)
        assertEquals(listOf("正文1。"), input.chapters[0].blocks.map { it.text })
        assertEquals(listOf("正文2。"), input.chapters[1].blocks.map { it.text })
        assertEquals(novelId, input.novelId)
        assertEquals(docId, input.documentId)
        assertEquals("我的小说", input.title)
    }

    @Test
    fun `preserves sourceLocation per block`() {
        val input = AnalysisInputBuilder.build(doc, listOf(c0, c1), listOf(b0, b1))
        assertEquals(SourceLocation(4, 7), input.chapters[0].blocks[0].sourceLocation)
        assertEquals(SourceLocation(12, 15), input.chapters[1].blocks[0].sourceLocation)
    }

    @Test
    fun `same input yields identical output (determinism)`() {
        val a = AnalysisInputBuilder.build(doc, listOf(c0, c1), listOf(b0, b1))
        val b = AnalysisInputBuilder.build(doc, listOf(c0, c1), listOf(b0, b1))
        assertEquals(a, b)
    }

    @Test
    fun `only this document's chapters are included`() {
        val otherChapter = ch("c99", 5, "其它文档", 0, 1).copy(documentId = TxtDocumentId("other"))
        val input = AnalysisInputBuilder.build(doc, listOf(c0, otherChapter), listOf(b0))
        assertEquals(1, input.chapters.size)
        assertEquals("第一章", input.chapters[0].title)
    }

    @Test
    fun `unbound document without novelId throws`() {
        val unbound = doc.copy(novelId = null)
        val ex = assertFailsWith<IllegalArgumentException> {
            AnalysisInputBuilder.build(unbound, listOf(c0), listOf(b0))
        }
        assertTrue(ex.message!!.contains("未绑定"))
    }
}