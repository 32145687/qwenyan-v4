package com.qianyan.storage.repository

import com.qianyan.model.ChapterId
import com.qianyan.model.NovelId
import com.qianyan.model.VariantId
import com.qianyan.model.story.Chapter
import com.qianyan.storage.db.QianyanDb

/** [ChapterRepository] 的 SQLDelight + SQLite JDBC 实现（P12.0 / P0-4）。 */
class SqliteChapterRepository(private val db: QianyanDb) : ChapterRepository {

    override fun save(chapter: Chapter) {
        val row = StorageMappers.domainChapter(chapter)
        db.chapterQueries.insertChapter(
            chapter_id = row.chapter_id,
            novel_id = row.novel_id,
            variant_id = row.variant_id,
            scope = row.scope,
            order_no = row.order_no,
            title = row.title,
            status = row.status,
            created_at = row.created_at,
            updated_at = row.updated_at,
        )
    }

    override fun findById(chapterId: ChapterId): Chapter? =
        db.chapterQueries.getChapterById(chapterId.value).executeAsOneOrNull()
            ?.let { StorageMappers.dbChapter(it) }

    override fun listByNovel(novelId: NovelId): List<Chapter> =
        db.chapterQueries.listChaptersByNovel(novelId.value).executeAsList()
            .map { StorageMappers.dbChapter(it) }

    override fun nextOrder(novelId: NovelId, variantId: VariantId?): Int =
        db.chapterQueries.selectMaxChapterOrder(novelId.value, variantId?.value)
            .executeAsOne().toInt() + 1
}