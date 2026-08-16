package com.qianyan.engine.txt

import com.qianyan.model.TxtDocumentId
import com.qianyan.model.spec.IssueSeverity
import com.qianyan.model.txt.TxtEncoding
import com.qianyan.model.txt.TxtIssueKind
import com.qianyan.model.txt.TxtParseStatus
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * P4 TXT Pipeline 确定性测试（core:engine）。
 *
 * 覆盖要求 §13 的 1–19 项（第 20 项 Repository round-trip 见 storage 模块 TxtRepositoryTest）：
 * UTF-8 / BOM / CRLF / LF / 空行 / 单章 / 多章 / 中文章节 / English / 序章 / 尾声 / 番外 /
 * 不规则标题 / 无章节 / 空 TXT / 乱码 / 大文本 / 重复章节 / 文本重建 / 确定性。
 *
 * 所有测试通过 [TxtPipeline] 门面（字节 → 结构化 → 重建），不触 SQLite / 不调用 LLM / 平台无关。
 */
class TxtPipelineTest {

    private val pipeline = TxtPipeline()

    /** 以 UTF-8 字节构建 [TxtSource]（模拟平台读取的文件字节，不绑定任何平台 API）。 */
    private fun source(text: String, name: String = "sample.txt") =
        TxtSource(text.toByteArray(StandardCharsets.UTF_8), name)

    private fun utf8Bom(text: String) = TxtSource(
        byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + text.toByteArray(StandardCharsets.UTF_8),
        "bom.txt",
    )

    private fun docId() = TxtDocumentId("doc-1")

    /* 1. UTF-8 基础导入 */
    @Test
    fun `utf8 txt imports successfully`() {
        val r = pipeline.import(source("第一章 风云\n\n正文内容一。\n\n正文内容二。"), docId())
        assertEquals(TxtParseStatus.SUCCESS, r.document.status)
        assertEquals(TxtEncoding.UTF8, r.document.encoding)
        assertFalse(r.document.hadBom)
        assertEquals(1, r.parse.chapters.size)
        assertEquals(2, r.parse.blocks.size)
        assertEquals("第一章 风云", r.parse.chapters[0].title)
    }

    /* 2. UTF-8 BOM 识别并剥离 */
    @Test
    fun `utf8 bom detected and stripped`() {
        val r = pipeline.import(utf8Bom("第一章\n\n正文。"), docId())
        assertEquals(TxtEncoding.UTF8_BOM, r.document.encoding)
        assertTrue(r.document.hadBom)
        assertFalse(r.document.normalizedText.startsWith("\uFEFF"))
        assertEquals("第一章\n\n正文。", r.document.normalizedText)
    }

    /* 3 + 4. CRLF 与 LF 统一为相同的规范化结果 */
    @Test
    fun `crlf and lf normalize identically`() {
        val crlf = pipeline.import(source("第一章\r\n\r\n正文一。\r\n\r\n正文二。"), docId())
        val lf = pipeline.import(source("第一章\n\n正文一。\n\n正文二。"), docId())
        assertEquals(lf.document.normalizedText, crlf.document.normalizedText)
        assertEquals(lf.document.contentHash, crlf.document.contentHash)
        assertEquals("第一章\n\n正文一。\n\n正文二。", crlf.document.normalizedText)
    }

    /* 5. 空行规范化：连续空行折叠为单个段落分隔，无首尾空行 */
    @Test
    fun `blank lines collapse and no leading trailing blank`() {
        val text = "\n\n\n第一章\n\n\n\n正文一。\n\n\n\n\n正文二。\n\n\n"
        val r = pipeline.import(source(text), docId())
        assertEquals("第一章\n\n正文一。\n\n正文二。", r.document.normalizedText)
        assertFalse(r.document.normalizedText.startsWith("\n"))
        assertFalse(r.document.normalizedText.endsWith("\n"))
    }

    /* 6. 单章 TXT */
    @Test
    fun `single chapter document`() {
        val r = pipeline.import(source("第一章 开场\n\n正文一。\n\n正文二。\n\n正文三。"), docId())
        val ch = r.parse.chapters.single()
        assertEquals(0, ch.ordinal)
        assertEquals("第一章 开场", ch.title)
        assertEquals(0, ch.firstBlockOrdinal)
        assertEquals(3, ch.blockCount)
        assertEquals(3, r.parse.blocks.size)
    }

    /* 7. 多章 TXT：序号连续、正文归属正确 */
    @Test
    fun `multi chapter document`() {
        val text = "第一章\n\n一甲。\n\n一乙。\n\n第二章\n\n二甲。\n\n第三章\n\n三甲。\n\n三乙。"
        val r = pipeline.import(source(text), docId())
        assertEquals(3, r.parse.chapters.size)
        assertEquals(listOf("第一章", "第二章", "第三章"), r.parse.chapters.map { it.title })
        assertEquals(listOf(0, 1, 2), r.parse.chapters.map { it.ordinal })
        assertEquals(listOf(0, 2, 3), r.parse.chapters.map { it.firstBlockOrdinal })
        assertEquals(listOf(2, 1, 2), r.parse.chapters.map { it.blockCount })
        assertEquals(5, r.parse.blocks.size)
        // 正文按出现顺序，不丢、不重排、不重复
        assertEquals(listOf("一甲。", "一乙。", "二甲。", "三甲。", "三乙。"), r.parse.blocks.map { it.text })
    }

    /* 8. 中文章节格式：第一章 / 第1章 / 第 1 章 / 卷一 第一章 */
    @Test
    fun `chinese chapter formats recognized`() {
        val text = "第一章\n\n正文。\n\n第1章\n\n正文。\n\n第 1 章\n\n正文。\n\n卷一 第一章\n\n正文。"
        val r = pipeline.import(source(text), docId())
        assertEquals(4, r.parse.chapters.size)
        assertEquals(TxtParseStatus.SUCCESS, r.document.status)
    }

    /* 9. English Chapter 格式 */
    @Test
    fun `english chapter recognized`() {
        val text = "Chapter 1\n\nHello.\n\nCHAPTER 12\n\nWorld."
        val r = pipeline.import(source(text), docId())
        assertEquals(2, r.parse.chapters.size)
        assertEquals(listOf("Chapter 1", "CHAPTER 12"), r.parse.chapters.map { it.title })
        assertEquals(TxtParseStatus.SUCCESS, r.document.status)
    }

    /* 10–12. 序章 / 尾声 / 番外 */
    @Test
    fun `prologue epilogue and extra recognized`() {
        val text = "序章\n\n开篇。\n\n这是第一段正文。\n\n尾声\n\n结局。\n\n番外\n\n小剧场。"
        val r = pipeline.import(source(text), docId())
        val titles = r.parse.chapters.map { it.title }
        assertEquals(listOf("序章", "尾声", "番外"), titles)
        assertEquals(4, r.parse.blocks.size)
        assertEquals(TxtParseStatus.SUCCESS, r.document.status)
    }

    /* 13. 不规则章节标题（带编号后缀或副标题的行内前缀） */
    @Test
    fun `irregular chapter titles recognized`() {
        val text = "第2章 剑出鞘\n\n正文。\n\nChapter 12: Return of the King\n\n正文。\n\n卷一 第一章 山门开\n\n正文。"
        val r = pipeline.import(source(text), docId())
        assertEquals(3, r.parse.chapters.size)
        assertEquals(listOf("第2章 剑出鞘", "Chapter 12: Return of the King", "卷一 第一章 山门开"), r.parse.chapters.map { it.title })
        assertEquals(TxtParseStatus.SUCCESS, r.document.status)
    }

    /* 14. 无章节标题的 TXT：单隐式章节 + NO_CHAPTER_HEADING 提示，不强行猜测 */
    @Test
    fun `no chapter heading produces implicit single chapter with warning`() {
        val r = pipeline.import(source("这是第一段。\n\n这是第二段。\n\n这是第三段。"), docId())
        assertEquals(1, r.parse.chapters.size)
        assertEquals("", r.parse.chapters[0].title)
        assertEquals(3, r.parse.blocks.size)
        assertEquals(TxtParseStatus.SUCCESS_WITH_WARNINGS, r.document.status)
        assertTrue(r.parse.warnings.any { it.kind == TxtIssueKind.NO_CHAPTER_HEADING })
        assertEquals(IssueSeverity.WARNING, r.parse.warnings[0].severity)
    }

    /* 15. 空 TXT：空字节 / 全空白 → EmptyDocument */
    @Test
    fun `empty document fails explicitly`() {
        assertFailsWith<TxtException.EmptyDocument> {
            pipeline.import(TxtSource(ByteArray(0), "empty.txt"), docId())
        }
        assertFailsWith<TxtException.EmptyDocument> {
            pipeline.import(source("   \n\n  \n"), docId())
        }
    }

    /* 16. 乱码 / 非法输入：非法 UTF-8 拒绝（不静默损坏）；UTF-16 BOM 明确报不支持 */
    @Test
    fun `invalid utf8 and utf16 bom are rejected`() {
        assertFailsWith<TxtException.InvalidText> {
            pipeline.import(TxtSource(byteArrayOf(0x41, 0xC3.toByte(), 0x28), "bad.txt"), docId())
        }
        assertFailsWith<TxtException.UnsupportedEncoding> {
            pipeline.import(
                TxtSource(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x41, 0x00), "utf16.txt"),
                docId(),
            )
        }
    }

    /* 17. 大文本：多章 数 MB 级正文（10 万+ 段落块），一次通过且可重建 */
    @Test
    fun `large multi chapter text processes and reconstructs`() {
        val sb = StringBuilder()
        val chapters = 500
        val parasPerChapter = 200
        for (c in 0 until chapters) {
            sb.append("第${c + 1}章\n")
            for (p in 0 until parasPerChapter) sb.append("这是第${c + 1}章的第${p + 1}段正文内容，用于验证大文本处理的稳定与顺序。\n\n")
        }
        val text = sb.toString()
        val r = pipeline.import(source(text, "large.txt"), docId())
        assertEquals(chapters, r.parse.chapters.size)
        assertEquals(chapters * parasPerChapter, r.parse.blocks.size)
        assertEquals(r.document.normalizedText, pipeline.reconstruct(r.parse.chapters, r.parse.blocks))
    }

    /* 18. 重复章节边界：重复标题保留按序切分 + DUPLICATE_CHAPTER_TITLE 提示 */
    @Test
    fun `duplicate chapter titles kept in order with warning`() {
        val text = "第一章\n\n正文A。\n\n第一章\n\n正文B。"
        val r = pipeline.import(source(text), docId())
        assertEquals(2, r.parse.chapters.size)
        assertEquals(listOf("第一章", "第一章"), r.parse.chapters.map { it.title })
        assertEquals(2, r.parse.blocks.size)
        assertEquals(TxtParseStatus.SUCCESS_WITH_WARNINGS, r.document.status)
        assertTrue(r.parse.warnings.any { it.kind == TxtIssueKind.DUPLICATE_CHAPTER_TITLE })
        // 文本重建仍等价
        assertEquals(r.document.normalizedText, pipeline.reconstruct(r.parse.chapters, r.parse.blocks))
    }

    /* 19. 文本重建：结构化结果 ↔ normalizedText 等价（含前置内容 + 特殊章节） */
    @Test
    fun `reconstruct equals normalized text`() {
        val text = "开篇引言。\n\n第一章\n\n正文一。\n\n第二章\n\n正文二。\n\n尾声\n\n正文三。\n\n番外\n\n小剧场。"
        val r = pipeline.import(source(text), docId())
        val rebuilt = pipeline.reconstruct(r.parse.chapters, r.parse.blocks)
        assertEquals(r.document.normalizedText, rebuilt)
        // 原文未被修改（originalText 保留原始字节解码内容）
        assertEquals(text, r.document.originalText)
    }

    /* 19b. 确定性：相同输入运行两次 → 结构/哈希/状态完全一致 */
    @Test
    fun `same input twice yields identical result`() {
        val text = "第一章\n\n正文一。\n\n正文二。\n\n第二章\n\n正文三。"
        val a = pipeline.import(source(text, "same.txt"), docId())
        val b = pipeline.import(source(text, "same.txt"), docId())
        assertEquals(a.document.contentHash, b.document.contentHash)
        assertEquals(a.document.normalizedText, b.document.normalizedText)
        assertEquals(a.document.status, b.document.status)
        assertEquals(a.parse.chapters, b.parse.chapters)
        assertEquals(a.parse.blocks, b.parse.blocks)
        assertEquals(a.parse.warnings, b.parse.warnings)
    }

    /* 19c. 确定性：不同文档 ID 不应影响结构与正文，只影响派生 ID */
    @Test
    fun `document id does not affect structure`() {
        val text = "第一章\n\n正文一。"
        val a = pipeline.import(source(text), TxtDocumentId("doc-A"))
        val b = pipeline.import(source(text), TxtDocumentId("doc-B"))
        assertEquals(a.parse.blocks.map { it.text }, b.parse.blocks.map { it.text })
        assertEquals(a.parse.chapters.map { it.title }, b.parse.chapters.map { it.title })
        assertEquals(a.document.contentHash, b.document.contentHash)
    }
}
