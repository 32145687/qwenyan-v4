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

    /** 更新候选状态（P20-P1：confirm/reject 持久化；不改其余字段）。 */
    fun updateCandidateStatus(candidateId: com.qianyan.model.VocabularyCandidateId, status: com.qianyan.model.vocabulary.VocabularyCandidateStatus)

    /** 更新候选词条内容（P20-P1：edit 持久化 suggested 词条；不改身份/状态）。 */
    fun updateCandidateSuggested(candidateId: com.qianyan.model.VocabularyCandidateId, entry: VocabularyEntry)

    /** 查询某 Novel（variant 除外）的全部候选词条（P6：AI 提取候选回读校验入库与状态）。 */
    fun findCandidatesByNovel(novelId: NovelId): List<VocabularyCandidate>
}