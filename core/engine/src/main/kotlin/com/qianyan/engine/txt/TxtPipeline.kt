package com.qianyan.engine.txt

import com.qianyan.model.NovelId
import com.qianyan.model.TxtDocumentId
import com.qianyan.model.txt.ImportResult
import com.qianyan.model.txt.ParseResult
import com.qianyan.model.txt.TextBlock
import com.qianyan.model.txt.TxtChapter
import com.qianyan.model.txt.TxtDocument
import com.qianyan.model.txt.TxtParseStatus
import kotlinx.datetime.Clock
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * TXT Pipeline 门面（P4）：编排 Importer → Normalizer → Detector，并支持文本重建。
 *
 * - 确定性：Importer/Normalizer/Detector 均为纯函数式阶段，无随机/时间参与业务结果
 *   （createdAt 仅书签元数据）；ruleVersion 记录规范化 + 章节规则版本。
 * - 不读取文件（平台负责）、不调用 LLM、不接触 SQLite（持久化由 Application → Repository）。
 * - 平台无关：纯 JDK + core:model。
 *
 * 文本重建契约：reconstruct(chapters, blocks) == normalizedText（详见 [reconstruct]）。
 */
class TxtPipeline(
    private val importer: TxtImporter = TxtImporter(),
) {

    companion object {
        const val NORMALIZER_VERSION = TextNormalizer.VERSION
        const val CHAPTER_RULES_VERSION = ChapterRules.VERSION

        /** 规范化 + 章节规则版本串（写入 TxtDocument.ruleVersion，参与确定性校验）。 */
        const val RULE_VERSION = "$NORMALIZER_VERSION;$CHAPTER_RULES_VERSION"
    }

    /**
     * 完整导入：字节 → 原始文本 → 规范化 → 结构化。
     * @param documentId 由调用方（Use Case）提供唯一 ID；章节/段落 ID 由本方法确定性派生。
     */
    fun import(
        source: TxtSource,
        documentId: TxtDocumentId,
        novelId: NovelId? = null,
        title: String = "",
    ): TxtImportResult {
        val outcome = importer.import(source, documentId)
        val normalized = TextNormalizer.normalize(outcome.rawText)
        val detect = ChapterDetector().detect(documentId, normalized, novelId)
        val status = if (detect.warnings.isEmpty()) TxtParseStatus.SUCCESS else TxtParseStatus.SUCCESS_WITH_WARNINGS
        val paragraphCount = detect.blocks.size + detect.chapters.count { it.title.isNotBlank() }

        val importResult = outcome.importResult.copy(
            status = status,
            paragraphCount = paragraphCount,
            warnings = detect.warnings,
        )
        val document = TxtDocument(
            documentId = documentId,
            novelId = novelId,
            sourceName = source.displayName,
            title = title,
            encoding = outcome.importResult.encoding,
            hadBom = outcome.importResult.hadBom,
            byteCount = outcome.importResult.byteCount,
            charCount = outcome.importResult.charCount,
            originalText = outcome.rawText,
            normalizedText = normalized,
            contentHash = contentHash(normalized),
            ruleVersion = RULE_VERSION,
            status = status,
            createdAt = Clock.System.now(),
        )
        val parseResult = ParseResult(documentId, status, detect.chapters, detect.blocks, detect.warnings)
        return TxtImportResult(document, importResult, parseResult)
    }

    /**
     * 从结构化结果恢复正文（纯函数，确定性）。
     *
     * 语义：按章节顺序，先输出非空标题、再输出本章段落，全部以单个空行（"\n\n"）分隔。
     * 因为规范化形态即"段落以单个空行分隔"，本函数保证：
     * reconstruct(chapters, blocks) == TxtDocument.normalizedText。
     */
    fun reconstruct(chapters: List<TxtChapter>, blocks: List<TextBlock>): String {
        val sb = StringBuilder()
        var needSeparator = false
        for (ch in chapters) {
            if (ch.title.isNotBlank()) {
                if (needSeparator) sb.append("\n\n")
                sb.append(ch.title)
                needSeparator = true
            }
            val end = (ch.firstBlockOrdinal + ch.blockCount).coerceAtMost(blocks.size)
            for (i in ch.firstBlockOrdinal until end) {
                val block = blocks.getOrNull(i) ?: continue
                if (needSeparator) sb.append("\n\n")
                sb.append(block.text)
                needSeparator = true
            }
        }
        return sb.toString()
    }

    /** 规范化文本的确定性指纹（SHA-256，hex）。 */
    fun contentHash(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}

/** 一次 TXT 导入的完整产出：文档 + Import 结果 + Parse 结果。 */
data class TxtImportResult(
    val document: TxtDocument,
    val import: ImportResult,
    val parse: ParseResult,
)
