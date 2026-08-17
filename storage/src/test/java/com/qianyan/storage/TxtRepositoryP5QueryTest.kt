package com.qianyan.storage

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
import com.qianyan.storage.db.QianyanDbFactory
import com.qianyan.storage.repository.SqliteTxtRepository
import kotlinx.datetime.Instant
import java.nio.file.Files
import kotlin.io.path.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P5.3 TxtRepository 查询扩展测试（storage）：
 * findByContentHash / findByNovelId 的命中、缺失、确定性顺序与文件库重开一致性。
 * 仅新增只读查询，不新增表、不改表结构、不建第二套 Repository（P5 约束）。
 */
class TxtRepositoryP5QueryTest {

    private val docId1 = TxtDocumentId("doc-p5-1")
    private val docId2 = TxtDocumentId("doc-p5-2")
    private val novelId = NovelId("novel-p5-binding")

    private fun boundDocument(id: TxtDocumentId, hash: String, sourceName: String, at: Instant) = TxtDocument(
        documentId = id,
        novelId = novelId,
        sourceName = sourceName,
        title = "P5 文档 $sourceName",
        encoding = TxtEncoding.UTF8,
        hadBom = false,
        byteCount = 40,
        charCount = 12,
        originalText = "第一章\n\n正文。",
        normalizedText = "第一章\n\n正文。",
        contentHash = hash,
        ruleVersion = "1;1",
        status = TxtParseStatus.SUCCESS,
        createdAt = at,
    )

    private fun chapters(docId: TxtDocumentId) = listOf(
        TxtChapter(TxtChapterId("${docId.value}#c0"), docId, novelId, 0, "第一章", SourceLocation(0, 3), 0, 1),
    )

    private fun blocks(docId: TxtDocumentId) = listOf(
        TextBlock(TextBlockId("${docId.value}#b0"), TxtChapterId("${docId.value}#c0"), docId, novelId, 0, "正文。", SourceLocation(4, 7)),
    )

    @Test
    fun `findByContentHash returns the bound document`() {
        val repo = SqliteTxtRepository(QianyanDbFactory.open().db)
        val doc = boundDocument(docId1, "hash-abc", "a.txt", Instant.parse("2026-01-01T00:00:00Z"))
        repo.saveImport(doc, chapters(docId1), blocks(docId1))

        val found = repo.findByContentHash("hash-abc")
        assertEquals(doc, found)
        assertEquals(novelId, found!!.novelId)
    }

    @Test
    fun `findByContentHash missing returns null`() {
        val repo = SqliteTxtRepository(QianyanDbFactory.open().db)
        assertNull(repo.findByContentHash("not-exist-hash"))
    }

    @Test
    fun `findByNovelId returns only bound documents in deterministic order`() {
        val repo = SqliteTxtRepository(QianyanDbFactory.open().db)
        val d1 = boundDocument(docId1, "h1", "a.txt", Instant.parse("2026-01-02T00:00:00Z"))
        val d2 = boundDocument(docId2, "h2", "b.txt", Instant.parse("2026-01-01T00:00:00Z"))
        repo.saveImport(d1, chapters(docId1), blocks(docId1))
        repo.saveImport(d2, chapters(docId2), blocks(docId2))

        val docs = repo.findByNovelId(novelId)
        assertEquals(listOf("b.txt", "a.txt"), docs.map { it.sourceName }, "应按 created_at 升序稳定返回")
        // 多次读取顺序一致（确定性）
        assertEquals(docs, repo.findByNovelId(novelId))
        assertTrue(docs.all { it.novelId == novelId })
    }

    @Test
    fun `findByNovelId with no binding returns empty`() {
        val repo = SqliteTxtRepository(QianyanDbFactory.open().db)
        assertEquals(emptyList(), repo.findByNovelId(NovelId("no-binding-novel")))
    }

    @Test
    fun `queries survive file reopen`() {
        val tmp = Files.createTempFile("qianyan-txt-p5", ".db").toString()
        try {
            val doc = boundDocument(docId1, "hash-persist", "a.txt", Instant.parse("2026-01-01T00:00:00Z"))
            SqliteTxtRepository(QianyanDbFactory.open("jdbc:sqlite:$tmp").db)
                .saveImport(doc, chapters(docId1), blocks(docId1))

            val reopened = SqliteTxtRepository(QianyanDbFactory.open("jdbc:sqlite:$tmp").db)
            assertEquals(doc, reopened.findByContentHash("hash-persist"))
            assertEquals(listOf(doc), reopened.findByNovelId(novelId))
        } finally {
            Files.deleteIfExists(Path(tmp))
        }
    }
}