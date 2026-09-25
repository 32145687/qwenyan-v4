package com.qianyan.storage.repository

import com.qianyan.model.ChapterId
import com.qianyan.model.reading.ReadingProgress
import com.qianyan.storage.db.QianyanDb

/** [ReadingProgressRepository] 的 SQLDelight + SQLite 实现（P20-P4）。 */
class SqliteReadingProgressRepository(
    private val db: QianyanDb,
) : ReadingProgressRepository {

    override fun save(progress: ReadingProgress) {
        val row = StorageMappers.domainReadingProgress(progress)
        db.readingProgressQueries.upsertReadingProgress(
            chapter_id = row.chapter_id,
            novel_id = row.novel_id,
            position = row.position,
            updated_at = row.updated_at,
        )
    }

    override fun get(chapterId: ChapterId): ReadingProgress? =
        db.readingProgressQueries.getReadingProgress(chapterId.value).executeAsOneOrNull()
            ?.let { StorageMappers.dbReadingProgress(it) }
}