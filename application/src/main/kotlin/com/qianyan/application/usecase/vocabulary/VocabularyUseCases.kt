package com.qianyan.application.usecase.vocabulary

import com.qianyan.application.error.ErrorMapper
import com.qianyan.application.usecase.UseCase
import com.qianyan.model.NovelId
import com.qianyan.model.VocabularyId
import com.qianyan.model.vocabulary.Vocabulary
import com.qianyan.model.vocabulary.VocabularyCandidate
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

    /** FindCandidatesByNovel：查询某 Novel 的全部候选词条（P7.1：Android UI 展示 AI 提取候选所需）。 */
    fun findCandidatesByNovel(novelId: NovelId): List<VocabularyCandidate> = guard { repo.findCandidatesByNovel(novelId) }

    /**
     * GetOrCreateNovelVocabulary（P7.6 最小 UI 查询入口）：为某 Original 找到或创建其 NOVEL 作用域词库容器。
     *  - 复用：先按 NOVEL scope 查询现有词库，匹配确定性 id 或 novelId，避免重复创建；
     *  - 创建：不存在时以确定性 id（"novel-vocab-<novelId>"）创建并保存；
     *  - 只组合既有仓储能力（findVocabularyByScope + saveVocabulary），不改 Schema / core:model。
     */
    fun getOrCreateNovelVocabulary(novelId: NovelId): VocabularyId = guard {
        val deterministicId = VocabularyId("novel-vocab-${novelId.value}")
        val existing = repo.findVocabularyByScope(VocabularyScopeLevel.NOVEL)
            .firstOrNull { it.vocabularyId == deterministicId || it.novelId == novelId }
        if (existing != null) {
            existing.vocabularyId
        } else {
            repo.saveVocabulary(
                Vocabulary(
                    vocabularyId = deterministicId,
                    novelId = novelId,
                    scopeLevel = VocabularyScopeLevel.NOVEL,
                    name = "NOVEL词库",
                ),
            )
            deterministicId
        }
    }
}