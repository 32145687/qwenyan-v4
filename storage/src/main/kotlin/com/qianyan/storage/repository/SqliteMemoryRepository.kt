package com.qianyan.storage.repository

import com.qianyan.model.NovelId
import com.qianyan.model.VariantId
import com.qianyan.model.memory.MemoryEntry
import com.qianyan.storage.db.QianyanDb

/** [MemoryRepository] 的 SQLDelight + SQLite JDBC 实现。 */
class SqliteMemoryRepository(private val db: QianyanDb) : MemoryRepository {

    override fun saveEntry(entry: MemoryEntry) {
        val row = StorageMappers.domainMemory(entry)
        db.memoryQueries.insertMemory(
            row.memory_id, row.novel_id, row.variant_id, row.scope, row.layer,
            row.content, row.source, row.created_by, row.created_at, row.updated_at,
        )
    }

    override fun findEntriesByNovel(novelId: NovelId): List<MemoryEntry> =
        db.memoryQueries.selectMemoriesByNovel(novelId.value)
            .executeAsList().map { StorageMappers.dbMemory(it) }

    override fun findEntriesByVariant(novelId: NovelId, variantId: VariantId): List<MemoryEntry> =
        db.memoryQueries.selectMemoriesByVariant(novelId.value, variantId.value)
            .executeAsList().map { StorageMappers.dbMemory(it) }
}