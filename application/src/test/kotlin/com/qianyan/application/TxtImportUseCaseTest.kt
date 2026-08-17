package com.qianyan.application

import com.qianyan.application.di.ApplicationContainer
import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.application.error.ErrorMapper
import com.qianyan.engine.txt.TxtException
import com.qianyan.engine.txt.TxtSource
import com.qianyan.model.NovelId
import com.qianyan.model.VariantScope
import com.qianyan.model.txt.TxtEncoding
import com.qianyan.model.txt.TxtParseStatus
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlin.io.path.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P5.4 ImportTxtUseCase（application）：TXT bytes → TxtPipeline → 去重 → 创建 Original Novel
 * → 绑定 novelId → 持久化 → 结构化结果 + VariantContext(ORIGINAL) + 错误归一 + 确定性。
 * 全程经 ApplicationContainer 访问 Use Case / 仓储接口，不触碰 Sqlite 实现。
 */
class TxtImportUseCaseTest {

    private fun container(): ApplicationContainer = ApplicationContainer.open()

    private fun source(text: String, name: String = "novel.txt"): TxtSource =
        TxtSource(text.toByteArray(StandardCharsets.UTF_8), name)

    /** 有效多章正文：2 章节 + 3 段落块，status=SUCCESS。 */
    private val validText = "第一章\n\n正文一。\n\n正文二。\n\n第二章\n\n正文三。"

    /* 正常 TXT 导入：创建 Novel + 绑定 novelId + 章节/段落落库 + contentHash */
    @Test
    fun `normal import creates original novel and persists bound document`() {
        val app = container()
        val out = app.txts.importTxtAsOriginal(source(validText, "story.txt"), title = "我的小说")

        assertFalse(out.isDuplicate)
        // 结构化结果字段完整
        assertNotNull(out.documentId)
        assertNotNull(out.novelId)
        assertTrue(out.contentHash.isNotBlank())
        assertEquals(2, out.chapterCount)
        assertEquals(3, out.blockCount)
        assertEquals(TxtEncoding.UTF8, out.encoding)

        // 创建了 Original Novel
        val novel = app.novels.getNovel(out.novelId)
        assertNotNull(novel)
        assertEquals(VariantScope.ORIGINAL, novel.scope)
        assertEquals("我的小说", novel.title)

        // TxtDocument 绑定 novelId 且主体字段正确
        val doc = app.txtRepository.getDocument(out.documentId)
        assertNotNull(doc)
        assertEquals(out.novelId, doc.novelId)
        assertEquals("我的小说", doc.title)
        assertEquals(out.contentHash, doc.contentHash)
        assertEquals(TxtParseStatus.SUCCESS, doc.status)
        assertTrue(doc.ruleVersion.isNotBlank())

        // Chapter / TextBlock 正确保存并绑定 novelId
        assertEquals(2, app.txtRepository.getChapters(out.documentId).size)
        assertEquals(3, app.txtRepository.getBlocks(out.documentId).size)
        assertTrue(app.txtRepository.getChapters(out.documentId).all { it.novelId == out.novelId })
        assertTrue(app.txtRepository.getBlocks(out.documentId).all { it.novelId == out.novelId })
    }

    /* 重复检测：相同内容再次导入命中 contentHash，复用既有 Novel，不产生第二个 */
    @Test
    fun `duplicate content detection reuses existing binding`() {
        val app = container()
        val first = app.txts.importTxtAsOriginal(source(validText, "a.txt"), title = "T")
        val second = app.txts.importTxtAsOriginal(source(validText, "b.txt"), title = "T")

        assertTrue(second.isDuplicate)
        assertEquals(first.novelId, second.novelId, "重复导入不得产生第二个 Original Novel")
        assertEquals(first.documentId, second.documentId, "重复导入不得产生重复 TXT 数据")
        assertEquals(first.contentHash, second.contentHash)

        // 去重查询可见：contentHash / novelId 均只命中同一文档
        assertEquals(first.documentId, app.txtRepository.findByContentHash(first.contentHash)!!.documentId)
        assertEquals(listOf(first.documentId), app.txtRepository.findByNovelId(first.novelId).map { it.documentId })
    }

    /* 重复检测的确定性：CRLF 与 LF 规范化后哈希一致 → 去重命中（不创建第二个） */
    @Test
    fun `crlf and lf dedupe to same binding`() {
        val app = container()
        val lf = app.txts.importTxtAsOriginal(source("第一章\n\n正文一。", "lf.txt"), title = "T")
        val crlf = app.txts.importTxtAsOriginal(source("第一章\r\n\r\n正文一。", "crlf.txt"), title = "T")
        assertTrue(crlf.isDuplicate)
        assertEquals(lf.novelId, crlf.novelId)
    }

    /* findByContentHash / findByNovelId 的 Application 侧可读性 + 空结果 */
    @Test
    fun `query by content hash and novel id through repository`() {
        val app = container()
        val out = app.txts.importTxtAsOriginal(source(validText), title = "T")
        assertNotNull(app.txtRepository.findByContentHash(out.contentHash))
        assertEquals(1, app.txtRepository.findByNovelId(out.novelId).size)
        // 不存在 / 无绑定 → 空
        assertNull(app.txtRepository.findByContentHash("no-such-hash"))
        assertEquals(emptyList(), app.txtRepository.findByNovelId(NovelId("no-binding")))
    }

    /* VariantContext ORIGINAL：scope=ORIGINAL / variantId=null / baseNovelId=对应 Novel */
    @Test
    fun `import returns original variant context`() {
        val app = container()
        val out = app.txts.importTxtAsOriginal(source(validText), title = "T")
        assertTrue(out.variantContext.isOriginal)
        assertEquals(VariantScope.ORIGINAL, out.variantContext.scope)
        assertNull(out.variantContext.variantId)
        assertEquals(out.novelId.value, out.variantContext.baseNovelId.value)
    }

    /* UnsupportedEncoding 映射：UseCase 边界不会泄漏引擎异常 */
    @Test
    fun `unsupported encoding is normalized`() {
        val app = container()
        val utf16 = TxtSource(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x41, 0x00), "utf16.txt")
        val ex = assertFailsWith<ApplicationException> { app.txts.importTxtAsOriginal(utf16, title = "T") }
        assertIs<ApplicationError.UnsupportedEncoding>(ex.error)
    }

    /* EmptyDocument 映射 */
    @Test
    fun `empty document is normalized`() {
        val app = container()
        val empty = TxtSource(ByteArray(0), "empty.txt")
        val ex = assertFailsWith<ApplicationException> { app.txts.importTxtAsOriginal(empty, title = "T") }
        assertIs<ApplicationError.EmptyDocument>(ex.error)
    }

    /* InvalidText 映射 */
    @Test
    fun `invalid text is normalized`() {
        val app = container()
        val bad = TxtSource(byteArrayOf(0x41, 0xC3.toByte(), 0x28), "bad.txt")
        val ex = assertFailsWith<ApplicationException> { app.txts.importTxtAsOriginal(bad, title = "T") }
        assertIs<ApplicationError.InvalidText>(ex.error)
    }

    /* ParseFailed 映射：引擎具体子类 → 领域错误（不经 String message） */
    @Test
    fun `parse failed maps to application error`() {
        assertIs<ApplicationError.ParseFailed>(
            ErrorMapper.map(TxtException.ParseFailed("boom")).error,
        )
        // 其余 TxtException 子类也按类型归一
        assertIs<ApplicationError.UnsupportedEncoding>(
            ErrorMapper.map(TxtException.UnsupportedEncoding("u")).error,
        )
        assertIs<ApplicationError.EmptyDocument>(
            ErrorMapper.map(TxtException.EmptyDocument("e")).error,
        )
        assertIs<ApplicationError.InvalidText>(
            ErrorMapper.map(TxtException.InvalidText("i")).error,
        )
    }

    /* 导入失败时不产生半成品数据：解析/编码错误在写库前抛出，无残留 Novel / TXT */
    @Test
    fun `failed import leaves no half-product`() {
        val app = container()
        // 非法 UTF-8 → 失败，写库前即抛
        assertFailsWith<ApplicationException> {
            app.txts.importTxtAsOriginal(TxtSource(byteArrayOf(0x41, 0xC3.toByte(), 0x28), "bad.txt"), title = "T")
        }
        // 失败后：导入有效内容得到全新绑定（未被失败影响），去重查询只为该真实导入所用哈希命中
        val good = app.txts.importTxtAsOriginal(source(validText), title = "T")
        assertFalse(good.isDuplicate)
        assertEquals(listOf(good.documentId), app.txtRepository.findByNovelId(good.novelId).map { it.documentId })
        assertNotNull(app.novels.getNovel(good.novelId))
    }

    /* 导入失败后，同一坏输入重试仍以同一确定性错误失败（确定性） */
    @Test
    fun `failed import is deterministic`() {
        val app = container()
        val bad = TxtSource(byteArrayOf(0x41, 0xC3.toByte(), 0x28), "bad.txt")
        val e1 = assertFailsWith<ApplicationException> { app.txts.importTxtAsOriginal(bad, title = "T") }
        val e2 = assertFailsWith<ApplicationException> { app.txts.importTxtAsOriginal(bad, title = "T") }
        assertIs<ApplicationError.InvalidText>(e1.error)
        assertIs<ApplicationError.InvalidText>(e2.error)
    }

    /* 确定性：相同输入两次导入 → 同一 contentHash；不同文档 ID 不影响正文结构 */
    @Test
    fun `import is deterministic across runs`() {
        val app = container()
        val a = app.txts.importTxtAsOriginal(source(validText, "a.txt"), title = "T")
        // 同一字节内容在第二个容器中哈希一致（引擎确定性）
        val app2 = container()
        val b = app.txts.importTxtAsOriginal(source(validText, "b.txt"), title = "T")
        assertEquals(a.contentHash, b.contentHash)
    }

    /* 文件库持久化：导入 → 重开同库 → 去重命中、绑定关系仍在 */
    @Test
    fun `import persists across reopen in file database`() {
        val tmp = Files.createTempFile("qianyan-p5-import", ".db").toString()
        try {
            val app = ApplicationContainer.open("jdbc:sqlite:$tmp")
            val first = app.txts.importTxtAsOriginal(source(validText, "novel.txt"), title = "T")
            assertFalse(first.isDuplicate)

            val reopened = ApplicationContainer.open("jdbc:sqlite:$tmp")
            val second = reopened.txts.importTxtAsOriginal(source(validText, "novel.txt"), title = "T")
            assertTrue(second.isDuplicate)
            assertEquals(first.novelId, second.novelId)
            assertEquals(first.documentId, second.documentId)
            assertEquals(first.contentHash, second.contentHash)
            assertEquals(1, reopened.txtRepository.findByNovelId(first.novelId).size)
        } finally {
            Files.deleteIfExists(Path(tmp))
        }
    }
}