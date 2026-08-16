package com.qianyan.storage.repository

import com.qianyan.model.TextBlockId
import com.qianyan.model.TxtChapterId
import com.qianyan.model.TxtDocumentId
import com.qianyan.model.txt.TextBlock
import com.qianyan.model.txt.TxtChapter
import com.qianyan.model.txt.TxtDocument
import com.qianyan.storage.db.QianyanDb

/** [TxtRepository] 的 SQLDelight + SQLite JDBC 实现。 */
class SqliteTxtRepository(
    private val db: QianyanDb,
) : TxtRepository {

    override fun saveImport(document: TxtDocument, chapters: List<TxtChapter>, blocks: List<TextBlock>) {
        val doc = StorageMappers.domainTxtDocument(document)
        val chapterRows = chapters.map { StorageMappers.domainTxtChapter(it) }
        val blockRows = blocks.map { StorageMappers.domainTextBlock(it) }
        db.transaction {
            runCatching {
                db.txtQueries.insertTxtDocument(
                    doc.document_id, doc.novel_id, doc.source_name, doc.title,
                    doc.encoding, doc.had_bom, doc.byte_count, doc.char_count,
                    doc.original_text, doc.normalized_text, doc.content_hash,
                    doc.rule_version, doc.status, doc.created_at,
                )
                chapterRows.forEach {
                    db.txtQueries.insertTxtChapter(
                        it.chapter_id, it.document_id, it.novel_id, it.ordinal,
                        it.title, it.source_start, it.source_end,
                        it.first_block_ordinal, it.block_count,
                    )
                }
                blockRows.forEach {
                    db.txtQueries.insertTextBlock(
                        it.block_id, it.chapter_id, it.document_id, it.novel_id,
                        it.ordinal, it.text, it.source_start, it.source_end,
                    )
                }
            }.onFailure { throw mapWriteError(it) }
        }
    }

    override fun getDocument(documentId: TxtDocumentId): TxtDocument? =
        db.txtQueries.getTxtDocument(documentId.value).executeAsOneOrNull()
            ?.let { StorageMappers.dbTxtDocument(it) }

    override fun getChapters(documentId: TxtDocumentId): List<TxtChapter> =
        db.txtQueries.getTxtChapters(documentId.value).executeAsList()
            .map { StorageMappers.dbTxtChapter(it) }

    override fun getBlocks(documentId: TxtDocumentId): List<TextBlock> =
        db.txtQueries.getTextBlocks(documentId.value).executeAsList()
            .map { StorageMappers.dbTextBlock(it) }

    override fun getBlocksOfChapter(chapterId: TxtChapterId): List<TextBlock> =
        db.txtQueries.getTextBlocksOfChapter(chapterId.value).executeAsList()
            .map { StorageMappers.dbTextBlock(it) }

    override fun getBlock(blockId: TextBlockId): TextBlock? =
        db.txtQueries.getTextBlock(blockId.value).executeAsOneOrNull()
            ?.let { StorageMappers.dbTextBlock(it) }

    private fun mapWriteError(e: Throwable): Throwable {
        val msg = e.message.orEmpty()
        return when {
            msg.contains("UNIQUE", ignoreCase = true) -> UniqueConflictException("违反唯一约束: $msg")
            msg.contains("constraint", ignoreCase = true) -> UniqueConflictException("违反约束: $msg")
            msg.contains("immutable", ignoreCase = true) -> OriginalImmutableException()
            msg.contains("Variant base", ignoreCase = true) -> VariantBaseViolation()
            else -> e
        }
    }
}
