package com.qianyan.application.usecase.vocabulary

import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.application.error.ErrorMapper
import com.qianyan.application.usecase.UseCase
import com.qianyan.model.NovelId
import com.qianyan.model.VocabularyCandidateId
import com.qianyan.model.VocabularyId
import com.qianyan.model.vocabulary.Vocabulary
import com.qianyan.model.vocabulary.VocabularyCandidate
import com.qianyan.model.vocabulary.VocabularyCandidateStatus
import com.qianyan.model.vocabulary.VocabularyEntry
import com.qianyan.model.vocabulary.VocabularyEntryStatus
import com.qianyan.model.vocabulary.VocabularyScopeLevel
import com.qianyan.storage.repository.VocabularyRepository

/**
 * Vocabulary 相关 Use Case（P3.2；P20-P1 扩展 Candidate 确认闭环）。
 *  - SaveVocabulary / 保存词条：保存词库容器与词条（scopeLevel / variantId 原样持久化）。
 *  - QueryVocabulary：按作用域层级查询，以及按 Variant 查询词条。
 *  - Candidate Confirmation（P20-P1）：confirm/reject/edit，状态机 PENDING → APPROVED / REJECTED；
 *    confirm 生成正式词条（去重）；edit 修改候选 suggested 内容（身份不变，状态不自动晋升）。
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

    // ================= P20-P1 · Candidate Confirmation =================

    /**
     * Confirm：PENDING → APPROVED，并把 suggested 词条落为正式词条（去重：同 canonical 已存在则不重复写）。
     * 幂等：已是 APPROVED → 直接返回（不重复生成词条）。
     */
    fun confirmCandidate(candidateId: VocabularyCandidateId, novelId: NovelId): VocabularyCandidate {
        val candidate = requireCandidate(candidateId, novelId)
        if (candidate.status == VocabularyCandidateStatus.APPROVED) return candidate
        requirePending(candidate)
        val entry = candidate.suggested.copy(status = VocabularyEntryStatus.APPROVED)
        val duplicate = guard { repo.findEntriesByNovel(novelId) }
            .any { it.vocabularyId == entry.vocabularyId && it.canonical == entry.canonical }
        if (!duplicate) {
            guard { repo.saveEntry(entry) }
        }
        val approved = candidate.copy(status = VocabularyCandidateStatus.APPROVED)
        guard { repo.updateCandidateStatus(candidateId, VocabularyCandidateStatus.APPROVED) }
        return approved
    }

    /**
     * Reject：PENDING → REJECTED。只改变候选状态，不写正式词条，不删除原始分析记录。
     * 幂等：已是 REJECTED → 直接返回。
     */
    fun rejectCandidate(candidateId: VocabularyCandidateId, novelId: NovelId): VocabularyCandidate {
        val candidate = requireCandidate(candidateId, novelId)
        if (candidate.status == VocabularyCandidateStatus.REJECTED) return candidate
        requirePending(candidate)
        val rejected = candidate.copy(status = VocabularyCandidateStatus.REJECTED)
        guard { repo.updateCandidateStatus(candidateId, VocabularyCandidateStatus.REJECTED) }
        return rejected
    }

    /**
     * Edit：修改候选 suggested 词条内容（canonical/aliases/type），保持候选身份，状态不自动晋升（仍是 PENDING，
     * 由用户随后 confirm/reject）。不产生重复候选（同一 candidateId 更新）。
     */
    fun editCandidate(
        candidateId: VocabularyCandidateId,
        novelId: NovelId,
        canonical: String,
        aliases: List<String>? = null,
        type: com.qianyan.model.vocabulary.VocabularyEntryType? = null,
    ): VocabularyCandidate {
        val candidate = requireCandidate(candidateId, novelId)
        if (canonical.isBlank()) {
            throw ApplicationException(ApplicationError.InvalidOperation("编辑后的候选词不能为空"))
        }
        val edited = candidate.copy(
            suggested = candidate.suggested.copy(
                canonical = canonical,
                aliases = aliases ?: candidate.suggested.aliases,
                type = type ?: candidate.suggested.type,
            ),
        )
        guard { repo.updateCandidateSuggested(candidateId, edited.suggested) }
        return edited
    }

    // ================= internal =================

    private fun requireCandidate(candidateId: VocabularyCandidateId, novelId: NovelId): VocabularyCandidate =
        guard { repo.findCandidatesByNovel(novelId) }
            .firstOrNull { it.candidateId == candidateId }
            ?: throw ApplicationException(ApplicationError.EntityNotFound("词汇候选不存在: ${candidateId.value}"))

    private fun requirePending(candidate: VocabularyCandidate) {
        if (candidate.status != VocabularyCandidateStatus.PENDING) {
            throw ApplicationException(
                ApplicationError.InvalidOperation("候选状态 ${candidate.status} 不允许该操作（仅 PENDING 可确认/拒绝）"),
            )
        }
    }
}