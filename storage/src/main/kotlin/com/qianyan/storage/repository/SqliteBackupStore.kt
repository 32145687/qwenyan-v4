package com.qianyan.storage.repository

import com.qianyan.model.core.BackupPackage
import com.qianyan.model.core.EntityOverride
import com.qianyan.model.core.Novel
import com.qianyan.model.core.NovelVariant
import com.qianyan.model.memory.MemoryEntry
import com.qianyan.model.vocabulary.Vocabulary
import com.qianyan.model.vocabulary.VocabularyCandidate
import com.qianyan.model.vocabulary.VocabularyEntry
import com.qianyan.model.vocabulary.VocabularyRule
import com.qianyan.storage.QianyanJson
import com.qianyan.storage.db.QianyanDbHandle
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

/**
 * [BackupStore] 的最小 SQLite 实现（P2.13）。
 * - 导出：把全部数据以领域 @Serializable [Snapshot] 写入结构化 JSON，包装为 [BackupPackage]。
 * - 恢复：单事务内按依赖倒序清空后重插，完整保留 Original/Variant、Override、强类型 ID。
 * 格式未定型，仅为存储边界基础快照，不扩展成完整备份系统。
 */
class SqliteBackupStore(
    private val handle: QianyanDbHandle,
) : BackupStore {

    override val schemaVersion: String = SCHEMA_VERSION

    /** 全量领域快照（各实体均为强类型领域模型，非 Map）。 */
    @Serializable
    private data class Snapshot(
        val novels: List<Novel> = emptyList(),
        val variants: List<NovelVariant> = emptyList(),
        val overrides: List<EntityOverride> = emptyList(),
        val vocabularies: List<Vocabulary> = emptyList(),
        val entries: List<VocabularyEntry> = emptyList(),
        val rules: List<VocabularyRule> = emptyList(),
        val candidates: List<VocabularyCandidate> = emptyList(),
        val memories: List<MemoryEntry> = emptyList(),
    )

    override fun exportBackup(): BackupPackage {
        val snapshot = Snapshot(
            novels = handle.db.novelQueries.getAllNovels().executeAsList().map { StorageMappers.dbNovel(it) },
            variants = handle.db.novelVariantQueries.getAll().executeAsList().map { StorageMappers.dbVariant(it) },
            overrides = handle.db.entityOverrideQueries.getAll().executeAsList().map { StorageMappers.dbOverride(it) },
            vocabularies = handle.db.vocabularyQueries.getAll().executeAsList().map { StorageMappers.dbVocabulary(it) },
            entries = handle.db.vocabularyQueries.getAllEntries().executeAsList().map { StorageMappers.dbVocabularyEntry(it) },
            rules = handle.db.vocabularyQueries.getAllRules().executeAsList().map { StorageMappers.dbVocabularyRule(it) },
            candidates = handle.db.vocabularyQueries.getAllCandidates().executeAsList().map { StorageMappers.dbVocabularyCandidate(it) },
            memories = handle.db.memoryQueries.getAll().executeAsList().map { StorageMappers.dbMemory(it) },
        )
        return BackupPackage(
            schemaVersion = schemaVersion,
            packageType = "BACKUP",
            exportedAt = Clock.System.now(),
            content = QianyanJson.json.encodeToJsonElement(snapshot),
        )
    }

    override fun restoreBackup(package_: BackupPackage) {
        val snapshot = QianyanJson.json.decodeFromJsonElement<Snapshot>(package_.content)
        handle.db.transaction {
            clearTables()
            snapshot.novels.forEach { insertNovelRow(StorageMappers.domainNovel(it)) }
            snapshot.variants.forEach { insertVariantRow(StorageMappers.domainVariant(it)) }
            snapshot.overrides.forEach { insertOverrideRow(StorageMappers.domainOverride(it)) }
            snapshot.vocabularies.forEach { insertVocabularyRow(StorageMappers.domainVocabulary(it)) }
            snapshot.entries.forEach { insertEntryRow(StorageMappers.domainVocabularyEntry(it)) }
            snapshot.rules.forEach { insertRuleRow(StorageMappers.domainVocabularyRule(it)) }
            snapshot.candidates.forEach { insertCandidateRow(StorageMappers.domainVocabularyCandidate(it)) }
            snapshot.memories.forEach { insertMemoryRow(StorageMappers.domainMemory(it)) }
        }
    }

    private fun clearTables() {
        // 子→父 依赖倒序清空，避免外键冲突。
        listOf(
            "EntityOverride", "MemoryEntry",
            "VocabularyCandidate", "VocabularyRule", "VocabularyEntry", "Vocabulary",
            "NovelVariant", "Novel",
        ).forEach { handle.driver.execute(null, "DELETE FROM $it", 0) }
    }

    private fun insertNovelRow(r: com.qianyan.storage.db.Novel) = handle.db.novelQueries.insertNovel(
        r.novel_id, r.project_id, r.title, r.source, r.genre, r.synopsis, r.scope, r.status, r.created_at, r.updated_at,
    )

    private fun insertVariantRow(r: com.qianyan.storage.db.NovelVariant) = handle.db.novelVariantQueries.insertVariant(
        r.variant_id, r.novel_id, r.base_novel_id, r.project_id, r.name, r.status, r.blueprint, r.scope_spec, r.created_at, r.updated_at,
    )

    private fun insertOverrideRow(r: com.qianyan.storage.db.EntityOverride) = handle.db.entityOverrideQueries.insertOverride(
        r.override_id, r.variant_id, r.target_kind, r.target_id, r.operation, r.replaced_value, r.note,
    )

    private fun insertVocabularyRow(r: com.qianyan.storage.db.Vocabulary) = handle.db.vocabularyQueries.insertVocabulary(
        r.vocabulary_id, r.novel_id, r.variant_id, r.scope_level, r.name,
    )

    private fun insertEntryRow(r: com.qianyan.storage.db.VocabularyEntry) = handle.db.vocabularyQueries.insertEntry(
        r.entry_id, r.vocabulary_id, r.novel_id, r.variant_id, r.scope_level,
        r.canonical, r.aliases, r.type, r.replacement, r.status,
    )

    private fun insertRuleRow(r: com.qianyan.storage.db.VocabularyRule) = handle.db.vocabularyQueries.insertRule(
        r.rule_id, r.vocabulary_id, r.novel_id, r.variant_id, r.scope_level,
        r.vocab_from, r.vocab_to, r.enabled, r.deterministic_only,
    )

    private fun insertCandidateRow(r: com.qianyan.storage.db.VocabularyCandidate) = handle.db.vocabularyQueries.insertCandidate(
        r.candidate_id, r.vocabulary_id, r.novel_id, r.variant_id, r.scope_level,
        r.suggested, r.source, r.status, r.created_at,
    )

    private fun insertMemoryRow(r: com.qianyan.storage.db.MemoryEntry) = handle.db.memoryQueries.insertMemory(
        r.memory_id, r.novel_id, r.variant_id, r.scope, r.layer,
        r.content, r.source, r.created_by, r.created_at, r.updated_at,
    )

    private companion object {
        const val SCHEMA_VERSION = "1"
    }
}