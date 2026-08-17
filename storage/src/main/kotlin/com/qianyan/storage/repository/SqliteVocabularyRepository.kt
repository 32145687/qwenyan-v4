package com.qianyan.storage.repository

import com.qianyan.model.NovelId
import com.qianyan.model.VariantId
import com.qianyan.model.vocabulary.Vocabulary
import com.qianyan.model.vocabulary.VocabularyCandidate
import com.qianyan.model.vocabulary.VocabularyEntry
import com.qianyan.model.vocabulary.VocabularyRule
import com.qianyan.model.vocabulary.VocabularyScopeLevel
import com.qianyan.storage.db.QianyanDb

/** [VocabularyRepository] 的 SQLDelight + SQLite JDBC 实现。 */
class SqliteVocabularyRepository(private val db: QianyanDb) : VocabularyRepository {

    override fun saveVocabulary(vocabulary: Vocabulary) {
        val row = StorageMappers.domainVocabulary(vocabulary)
        db.vocabularyQueries.insertVocabulary(
            row.vocabulary_id, row.novel_id, row.variant_id, row.scope_level, row.name,
        )
    }

    override fun findVocabularyByScope(scopeLevel: VocabularyScopeLevel): List<Vocabulary> =
        db.vocabularyQueries.selectVocabularyByScope(scopeLevel.name)
            .executeAsList().map { StorageMappers.dbVocabulary(it) }

    override fun saveEntry(entry: VocabularyEntry) {
        val row = StorageMappers.domainVocabularyEntry(entry)
        db.vocabularyQueries.insertEntry(
            row.entry_id, row.vocabulary_id, row.novel_id, row.variant_id, row.scope_level,
            row.canonical, row.aliases, row.type, row.replacement, row.status,
        )
    }

    override fun findEntriesByVariant(variantId: VariantId): List<VocabularyEntry> =
        db.vocabularyQueries.selectEntriesByVariant(variantId.value)
            .executeAsList().map { StorageMappers.dbVocabularyEntry(it) }

    override fun findEntriesByNovel(novelId: NovelId): List<VocabularyEntry> =
        db.vocabularyQueries.selectEntriesByNovel(novelId.value)
            .executeAsList().map { StorageMappers.dbVocabularyEntry(it) }

    override fun saveRule(rule: VocabularyRule) {
        val row = StorageMappers.domainVocabularyRule(rule)
        db.vocabularyQueries.insertRule(
            row.rule_id, row.vocabulary_id, row.novel_id, row.variant_id, row.scope_level,
            row.vocab_from, row.vocab_to, row.enabled, row.deterministic_only,
        )
    }

    override fun saveCandidate(candidate: VocabularyCandidate) {
        val row = StorageMappers.domainVocabularyCandidate(candidate)
        db.vocabularyQueries.insertCandidate(
            row.candidate_id, row.vocabulary_id, row.novel_id, row.variant_id, row.scope_level,
            row.suggested, row.source, row.status, row.created_at,
        )
    }

    override fun findCandidatesByNovel(novelId: NovelId): List<VocabularyCandidate> =
        db.vocabularyQueries.selectCandidatesByNovel(novelId.value)
            .executeAsList().map { StorageMappers.dbVocabularyCandidate(it) }
}