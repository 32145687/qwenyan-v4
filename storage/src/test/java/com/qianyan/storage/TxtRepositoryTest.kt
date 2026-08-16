package com.qianyan.storage

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.qianyan.model.TextBlockId
import com.qianyan.model.TxtChapterId
import com.qianyan.model.TxtDocumentId
import com.qianyan.model.txt.SourceLocation
import com.qianyan.model.txt.TextBlock
import com.qianyan.model.txt.TxtChapter
import com.qianyan.model.txt.TxtDocument
import com.qianyan.model.txt.TxtEncoding
import com.qianyan.model.txt.TxtParseStatus
import com.qianyan.storage.db.QianyanDbFactory
import com.qianyan.storage.repository.SqliteTxtRepository
import com.qianyan.storage.repository.UniqueConflictException
import kotlinx.datetime.Instant
import java.nio.file.Files
import kotlin.io.path.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * P4 TXT Repository round-trip（storage）：saveImport → 读回（文档/章节/段落/单块/章节内块）
 * 逐字段等价；重复主键明确报错；文件库可重开持久化。
 *
 * 覆盖要求 §13 第 20 项。持久化走当前 SQLDelight 架构（QianyanDb），不引入 Room / 第二套存储。
 */
class TxtRepositoryTest {

    private val docId = TxtDocumentId("doc-roundtrip")
    private val c0 = TxtChapterId("doc-roundtrip#c0")
    private val c1 = TxtChapterId("doc-roundtrip#c1")
    private val fixed = Instant.parse("2026-01-01T00:00:00Z")

    private fun document() = TxtDocument(
        documentId = docId,
        novelId = null,
        sourceName = "novel.txt",
        title = "测试文档",
        encoding = TxtEncoding.UTF8_BOM,
        hadBom = true,
        byteCount = 128,
        charCount = 42,
        originalText = "第一章\n\n正文一。\n\n正文二。",
        normalizedText = "第一章\n\n正文一。\n\n正文二。",
        contentHash = "deadbeef",
        ruleVersion = "1;1",
        status = TxtParseStatus.SUCCESS,
        createdAt = fixed,
    )

    private fun chapters() = listOf(
        TxtChapter(c0, docId, null, 0, "第一章", SourceLocation(0, 3), 0, 1),
        TxtChapter(c1, docId, null, 1, "第二章", SourceLocation(9, 12), 1, 2),
    )

    private fun blocks() = listOf(
        TextBlock(TextBlockId("doc-roundtrip#b0"), c0, docId, null, 0, "正文一。", SourceLocation(4, 8)),
        TextBlock(TextBlockId("doc-roundtrip#b1"), c1, docId, null, 1, "正文二。", SourceLocation(13, 17)),
        TextBlock(TextBlockId("doc-roundtrip#b2"), c1, docId, null, 2, "正文三。", SourceLocation(18, 22)),
    )

    @Test
    fun `save and read back document chapters and blocks`() {
        val repo = SqliteTxtRepository(QianyanDbFactory.open().db)
        repo.saveImport(document(), chapters(), blocks())

        val read = repo.getDocument(docId)
        assertNotNull(read)
        assertEquals(document(), read)

        assertEquals(chapters(), repo.getChapters(docId))
        assertEquals(blocks(), repo.getBlocks(docId))

        assertEquals(chapters(), repo.getChapters(docId).sortedBy { it.ordinal })
        assertEquals(blocks(), repo.getBlocks(docId).sortedBy { it.ordinal })

        // 章节内块（按全局序号有序）
        val c1Blocks = repo.getBlocksOfChapter(c1)
        assertEquals(listOf("正文二。", "正文三。"), c1Blocks.map { it.text })
        assertEquals(listOf(1, 2), c1Blocks.map { it.ordinal })

        // 单块读取
        assertEquals(blocks()[1], repo.getBlock(TextBlockId("doc-roundtrip#b1")))
        assertNull(repo.getBlock(TextBlockId("missing")))
    }

    @Test
    fun `duplicate document id save conflicts`() {
        val repo = SqliteTxtRepository(QianyanDbFactory.open().db)
        repo.saveImport(document(), chapters(), blocks())
        assertFailsWith<UniqueConflictException> {
            repo.saveImport(document(), chapters(), blocks())
        }
    }

    @Test
    fun `missing document returns null`() {
        val repo = SqliteTxtRepository(QianyanDbFactory.open().db)
        assertNull(repo.getDocument(TxtDocumentId("not-exist")))
        assertEquals(emptyList(), repo.getChapters(TxtDocumentId("not-exist")))
        assertEquals(emptyList(), repo.getBlocks(TxtDocumentId("not-exist")))
    }

    /** 文件库持久化：写入 → 重开同一文件 → 读回一致（验证真实 SQLite 落盘）。 */
    @Test
    fun `file database persists across reopen`() {
        val tmp = Files.createTempFile("qianyan-txt", ".db").toString()
        try {
            SqliteTxtRepository(QianyanDbFactory.open("jdbc:sqlite:$tmp").db)
                .saveImport(document(), chapters(), blocks())

            val reopened = SqliteTxtRepository(QianyanDbFactory.open("jdbc:sqlite:$tmp").db)
            assertEquals(document(), reopened.getDocument(docId))
            assertEquals(chapters(), reopened.getChapters(docId))
            assertEquals(blocks(), reopened.getBlocks(docId))
        } finally {
            Files.deleteIfExists(Path(tmp))
        }
    }
}
