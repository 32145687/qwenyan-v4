package com.qianyan.storage.repository

import com.qianyan.model.AuthorCoreCandidateId
import com.qianyan.model.AuthorCoreId
import com.qianyan.model.AuthorEvidenceId
import com.qianyan.model.NovelId
import com.qianyan.model.author.AuthorCore
import com.qianyan.model.author.AuthorCoreCandidate
import com.qianyan.model.author.AuthorCoreEvidenceLink
import com.qianyan.model.author.AuthorCorePattern
import com.qianyan.model.author.AuthorCoreScope
import com.qianyan.model.author.AuthorCoreStatus

/**
 * Author Core 持久化仓储（P17）。
 *
 * 语义（DEC-P17-011/014/015）：
 *  - **独立 AuthorCore Storage Boundary**：只持久化 AuthorCore 表族（Core / Pattern / Candidate / EvidenceLink / Learning），
 *    不改 P16 三张 Author 表、不改任何 Story 表。
 *  - [AuthorCoreEvidenceLink] 以 `UNIQUE(corePatternKey, evidenceId)` 保证同一 Evidence 只入链一次（幂等）。
 *  - 本层只做持久化；聚合 / min-observation / 确认 / supersede / scope 解析在 Application 层。
 */
interface AuthorCoreRepository {

    // ---- AuthorCore ----
    fun upsertAuthorCore(core: AuthorCore)
    fun getAuthorCore(id: AuthorCoreId): AuthorCore?
    fun listCores(scope: AuthorCoreScope? = null): List<AuthorCore>
    fun listStableCores(): List<AuthorCore> // status=STABLE
    /** 更新生命周期：status / supersededBy / revokedAt（含时间戳）。 */
    fun setCoreStatus(id: AuthorCoreId, status: AuthorCoreStatus, supersededBy: AuthorCoreId?, revokedAt: kotlinx.datetime.Instant?)
    fun deleteAuthorCore(id: AuthorCoreId)

    // ---- AuthorCorePattern ----
    fun upsertAuthorCorePattern(pattern: AuthorCorePattern)
    fun getAuthorCorePattern(id: com.qianyan.model.AuthorCorePatternId): AuthorCorePattern?
    fun getAuthorCorePatternsByKey(patternKey: String): List<AuthorCorePattern>
    fun listAuthorCorePatterns(scope: AuthorCoreScope? = null, novelId: NovelId? = null): List<AuthorCorePattern>
    fun deleteAuthorCorePattern(id: com.qianyan.model.AuthorCorePatternId)

    // ---- AuthorCoreCandidate ----
    fun upsertAuthorCoreCandidate(candidate: AuthorCoreCandidate)
    fun getAuthorCoreCandidate(id: AuthorCoreCandidateId): AuthorCoreCandidate?
    fun listCandidates(scope: AuthorCoreScope? = null, novelId: NovelId? = null): List<AuthorCoreCandidate>
    fun deleteAuthorCoreCandidate(id: AuthorCoreCandidateId)

    // ---- AuthorCoreEvidenceLink（幂等） ----
    fun linkEvidence(link: AuthorCoreEvidenceLink)
    fun evidenceLinkExists(corePatternKey: String, evidenceId: AuthorEvidenceId): Boolean
    fun listEvidenceLinks(corePatternKey: String): List<AuthorCoreEvidenceLink>
    /** 某 Evidence 已被何 patternKey 引用（用于全局幂等核查）。 */
    fun listEvidenceKeysByEvidenceId(evidenceId: AuthorEvidenceId): List<String>
    fun deleteEvidenceLinksByPattern(corePatternKey: String)

    // ---- AuthorCoreLearning（单例暂停开关） ----
    fun isLearningPaused(): Boolean
    fun setLearningPaused(paused: Boolean)

    /** P17 reset：清空本表族学习结果（不得触碰 P16 AuthorPreference/AuthorEvidence）。 */
    fun wipeLearningResults()
}