package com.qianyan.engine.txt

import com.qianyan.model.TxtDocumentId
import com.qianyan.model.txt.ImportResult
import com.qianyan.model.txt.TxtEncoding
import com.qianyan.model.txt.TxtParseStatus
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * TXT Importer：接收字节 → 解码 → 产出原始文本与确定性 Import 元数据。
 *
 * 确定性规则：
 *  - BOM 处理：UTF-8 BOM（EF BB BF）剥离并记录；UTF-16 BOM 明确报 [TxtException.UnsupportedEncoding]
 *    （P4 不做 UTF-16 猜测解码）；
 *  - 非法 UTF-8：使用 REPORT 严格解码 → [TxtException.InvalidText]，绝不静默替换/损坏文本；
 *  - 解码后正文为空/全空白 → [TxtException.EmptyDocument]。
 *
 * 纯 JDK（java.nio），不依赖 Android / Desktop API；对相同字节总是返回相同结果。
 */
class TxtImporter {

    fun import(source: TxtSource, documentId: TxtDocumentId): ImportOutcome {
        val bytes = source.bytes
        var offset = 0
        var hadBom = false
        var encoding = TxtEncoding.UTF8

        // BOM 检测（确定性字节前缀判断）
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        ) {
            offset = 3
            hadBom = true
            encoding = TxtEncoding.UTF8_BOM
        } else if (bytes.size >= 2 &&
            ((bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) ||
                (bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()))
        ) {
            throw TxtException.UnsupportedEncoding(
                "检测到 UTF-16 BOM（FF FE / FE FF）；P4 仅支持 UTF-8 家族，拒绝猜测解码（source=${source.displayName}）"
            )
        }

        val text = decodeUtf8Strict(bytes, offset)
        if (text.isEmpty() || text.isBlank()) {
            throw TxtException.EmptyDocument("解码后正文为空（source=${source.displayName}）")
        }

        return ImportOutcome(
            importResult = ImportResult(
                documentId = documentId,
                status = TxtParseStatus.SUCCESS,
                encoding = encoding,
                hadBom = hadBom,
                byteCount = bytes.size.toLong(),
                charCount = text.length,
            ),
            rawText = text,
        )
    }

    private fun decodeUtf8Strict(bytes: ByteArray, from: Int): String {
        return try {
            val decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            decoder.decode(ByteBuffer.wrap(bytes, from, bytes.size - from)).toString()
        } catch (e: Exception) {
            throw TxtException.InvalidText("文本不是合法 UTF-8，拒绝损坏文本（无法安全解码）。", e)
        }
    }
}

/** Importer 输出：确定性 Import 元数据 + 原始文本。 */
data class ImportOutcome(
    val importResult: ImportResult,
    val rawText: String,
)
