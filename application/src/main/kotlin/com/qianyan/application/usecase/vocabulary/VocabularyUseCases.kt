package com.qianyan.application.usecase.vocabulary

import com.qianyan.application.error.ErrorMapper
import com.qianyan.application.usecase.UseCase
import com.qianyan.model.vocabulary.Vocabulary
import com.qianyan.model.vocabulary.VocabularyEntry
import com.qianyan.model.vocabulary.VocabularyScopeLevel
import com.qianyan.storage.repository.VocabularyRepository

/**
 * Vocabulary 相关 Use Case（P3.2）：
 *  - SaveVocabulary / 保存词条：保存词库容器与词条（scopeLevel / variantId 原样持久化）。
 *  - QueryVocabulary：按作用域层级查询，以及按 Variant 查询词条。
 *
 * 保持 P1 已确定层级 Global > Novel > Variant > Task；不在此实现最终词库解析算法（P2.11/P1）。
 */
class VocabularyUseCases(
    private val repo: VocabularyRepository,
    errorMapper: ErrorMapper,
) : UseCase(errorMapper) {

    /** SaveVocabulary：保存一个词库容器。 */
    fun saveVocabulary(vocabulary: Vocabulary): Unit = guard { repo.saveVocabulary(vocabulary) }

    /** SaveVocabularyEntry：保存一条词条（连同其作用域信息）。 */
    fun saveEntry(entry: VocabularyEntry): Unit = guard { repo.saveEntry(entry) }

    /** QueryVocabulary：按作用域层级查询词库。 */
    fun query(scopeLevel: VocabularyScopeLevel): List<Vocabulary> = guard { repo.findVocabularyByScope(scopeLevel) }
}