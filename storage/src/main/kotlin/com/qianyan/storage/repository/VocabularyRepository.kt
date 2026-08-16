package com.qianyan.storage.repository

import com.qianyan.model.NovelId
import com.qianyan.model.VariantId
import com.qianyan.model.vocabulary.Vocabulary
import com.qianyan.model.vocabulary.VocabularyCandidate
import com.qianyan.model.vocabulary.VocabularyEntry
import com.qianyan.model.vocabulary.VocabularyRule
import com.qianyan.model.vocabulary.VocabularyScopeLevel

/**
 * Vocabulary 最小持久化仓储（P2.11）。
 * 仅覆盖 Storage Foundation 所需：保存 + 按 scope 查询。
 * 不实现最终词库解析算法（P1 已确定层级 Global > Novel > Variant > Task，此处不改设计）。
 */
interface VocabularyRepository {

    /** 保存一个词库容器。 */
    fun saveVocabulary(vocabulary: Vocabulary)

    /** 按作用域层级查询词库（Global/Novel/Variant/Task 定点查询）。 */
    fun findVocabularyByScope(scopeLevel: VocabularyScopeLevel): List<Vocabulary>

    /** 保存词条（scopeLevel / variantId 原样持久化）。 */
    fun saveEntry(entry: VocabularyEntry)

    /** 查询某 Variant 的词条。 */
    fun findEntriesByVariant(variantId: VariantId): List<VocabularyEntry>

    /** 查询某 Novel（scope=NOVEL）的词条。 */
    fun findEntriesByNovel(novelId: NovelId): List<VocabularyEntry>

    /** 保存规则。 */
    fun saveRule(rule: VocabularyRule)

    /** 保存候选词条。 */
    fun saveCandidate(candidate: VocabularyCandidate)
}