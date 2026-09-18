package com.qianyan.application.usecase.author

import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.application.error.ErrorMapper
import com.qianyan.application.usecase.UseCase
import com.qianyan.model.AuthorCoreCandidateId
import com.qianyan.model.AuthorCoreEvidenceLinkId
import com.qianyan.model.AuthorCoreId
import com.qianyan.model.AuthorCorePatternId
import com.qianyan.model.AuthorEvidenceId
import com.qianyan.model.NovelId
import com.qianyan.model.author.AuthorCore
import com.qianyan.model.author.AuthorCoreCandidate
import com.qianyan.model.author.AuthorCoreEvidenceLink
import com.qianyan.model.author.AuthorCorePattern
import com.qianyan.model.author.AuthorCoreScope
import com.qianyan.model.author.AuthorCoreStatus
import com.qianyan.model.author.AuthorEvidence
import com.qianyan.model.author.AuthorEvidenceType
import com.qianyan.model.author.Confidence
import com.qianyan.storage.repository.AuthorCoreRepository
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/**
 * Author Core UseCases（P17 · DEC-P17-001~016）。
 *
 * 职责：Evidence ingestion → 确定性聚合（[AuthorCoreAggregator]）→ CoreCandidate →
 * min-observation + User Confirmation → 长期 AuthorCore（生命周期 STABLE/SUPERSEDED/REVOKED；
 * Global 方向翻转须重新确认）。另含 User Control：view / confirm / reject / modify / delete /
 * pause / resume / reset。
 *
 * 边界：
 *  - **永不读写 Story**（DEC-007）；NovelId 仅作 scope 键。
 *  - 幂等：同一 Evidence ID 全局只入链 + 聚合一次（DEC-015，沿用 M-1）。
 *  - pause 时：停止新采集 / 停止晋升 / 停止投影。
 */
class AuthorCoreUseCases(
    private val repository: AuthorCoreRepository,
    private val evidenceSource: FoundationEvidenceSource,
    errorMapper: ErrorMapper,
) : UseCase(errorMapper) {

    /** P15 Foundation 信号映射到的稳定 patternKey（长期决策模式：故事方向的创作决策）。非固定习惯 enum，是稳定字符串键。 */
    private val defaultPatternKey: String get() = "core:foundation"
    private val defaultStatement: String get() = "作者在长期创作中有持续而稳定的故事创作决策倾向"

    // ================= Evidence → Candidate =================

    /**
     * 记录一条 Explicit Core Evidence 并聚合（幂等：同一 evidenceId 只应用一次）。
     * @return true = 本次新应用；false = 幂等跳过（已应用 或 学习已暂停）。
     */
    fun recordCoreEvidence(
        patternKey: String = defaultPatternKey,
        type: AuthorEvidenceType,
        statement: String = defaultStatement,
        condition: String? = null,
        scope: AuthorCoreScope = AuthorCoreScope.GLOBAL,
        novelId: NovelId? = null,
        evidenceId: AuthorEvidenceId,
        detail: String = "",
        source: String = "author-core",
    ): Boolean {
        if (repository.isLearningPaused()) return false
        if (repository.listEvidenceKeysByEvidenceId(evidenceId).isNotEmpty()) return false // 幂等

        val now = Clock.System.now()
        val evidence = AuthorEvidence(
            evidenceId = evidenceId,
            novelId = novelId ?: NovelId(""),
            type = type,
            detail = detail,
            source = source,
            observedAt = now,
        )
        val candidate = ensureCandidate(patternKey, scope, novelId, statement, condition, now)
        repository.upsertAuthorCoreCandidate(AuthorCoreAggregator.aggregate(candidate, evidence, now))
        repository.linkEvidence(AuthorCoreEvidenceLink(AuthorCoreEvidenceLinkId(nextId()), patternKey, evidenceId, now))
        return true
    }

    /** 从 P15 只读采集信号并聚合到 Global Core Candidate（幂等）。返回本次新增应用数。 */
    fun collectFoundationCoreEvidence(novelId: NovelId): Int {
        if (repository.isLearningPaused()) return 0
        var applied = 0
        evidenceSource.foundationSignals(novelId).forEach { signal ->
            if (repository.listEvidenceKeysByEvidenceId(signal.evidenceId).isEmpty()) {
                val now = signal.observedAt
                val candidate = ensureCandidate(defaultPatternKey, AuthorCoreScope.GLOBAL, null, defaultStatement, null, now)
                repository.upsertAuthorCoreCandidate(AuthorCoreAggregator.aggregate(candidate, signal, now))
                repository.linkEvidence(AuthorCoreEvidenceLink(AuthorCoreEvidenceLinkId(nextId()), defaultPatternKey, signal.evidenceId, now))
                applied++
            }
        }
        return applied
    }

    // ================= Candidate → Core（User Confirmation） =================

    /** 确认一个 Core Candidate → 长期 AuthorCore（STABLE）。必要条件：≥MIN_OBSERVATION + 学习未暂停。 */
    fun confirmCoreCandidate(candidateId: AuthorCoreCandidateId): AuthorCore {
        requireLearningActive()
        val cand = requireCandidate(candidateId)
        if (cand.status != AuthorCoreStatus.CANDIDATE) {
            throw ApplicationException(ApplicationError.InvalidOperation("候选状态 ${cand.status} 不可确认"))
        }
        if (cand.observationCount < AuthorCoreAggregator.MIN_OBSERVATION) {
            throw ApplicationException(
                ApplicationError.InvalidOperation(
                    "需至少 ${AuthorCoreAggregator.MIN_OBSERVATION} 次观测才能形成长期 Core（当前 ${cand.observationCount}）",
                ),
            )
        }
        val now = Clock.System.now()

        // 新内容 → 新 Pattern revision（新 patternId）+ 新 Core revision；旧 STABLE Core 被 SUPERSEDED 并保留原内容（M-1）。
        val existingStable = repository.listCores(cand.scope)
            .firstOrNull { it.corePatternKey == cand.patternKey && it.status == AuthorCoreStatus.STABLE && it.novelId == cand.novelId }
        val evidenceRefs = repository.listEvidenceLinks(cand.patternKey).map { it.evidenceId }
        val pattern = AuthorCorePattern(
            patternId = AuthorCorePatternId(nextId()),
            patternKey = cand.patternKey,
            statement = cand.statement.ifBlank { defaultStatement },
            condition = cand.condition,
            scope = cand.scope,
            novelId = cand.novelId,
            version = latestPatternVersion(cand.patternKey, cand.scope, cand.novelId) + 1,
            confidence = cand.confidence,
            status = AuthorCoreStatus.STABLE,
            evidenceRefs = evidenceRefs,
            createdAt = now,
            updatedAt = now,
        )
        repository.upsertAuthorCorePattern(pattern)

        val newCore = AuthorCore(
            coreId = AuthorCoreId(nextId()),
            version = (existingStable?.version ?: 0L) + 1,
            scope = cand.scope,
            novelId = cand.novelId,
            status = AuthorCoreStatus.STABLE,
            confirmed = true,
            confidence = cand.confidence,
            corePatternKey = cand.patternKey,
            patternId = pattern.patternId,
            createdAt = now,
            updatedAt = now,
        )
        if (existingStable != null) {
            repository.setCoreStatus(existingStable.coreId, AuthorCoreStatus.SUPERSEDED, supersededBy = newCore.coreId, revokedAt = null)
        }
        repository.upsertAuthorCore(newCore)
        repository.upsertAuthorCoreCandidate(cand.copy(status = AuthorCoreStatus.STABLE, updatedAt = now))
        return newCore
    }

    /** 拒绝 Core Candidate → 置 REVOKED（终态；Evidence 历史不受影响）。 */
    fun rejectCoreCandidate(candidateId: AuthorCoreCandidateId) {
        requireLearningActive()
        val cand = requireCandidate(candidateId)
        repository.upsertAuthorCoreCandidate(cand.copy(status = AuthorCoreStatus.REVOKED, updatedAt = Clock.System.now()))
    }

    // ================= Core 内容 User Control =================

    /** 修改一个已确认 Core → 生成新 Core revision（新 patternId + 新 coreId），旧版本 SUPERSEDED 且保留原内容（M-1）。 */
    fun modifyCore(coreId: AuthorCoreId, statement: String? = null, condition: String? = null, direction: String? = null): AuthorCore {
        requireLearningActive()
        val old = requireCore(coreId)
        if (old.status != AuthorCoreStatus.STABLE) {
            throw ApplicationException(ApplicationError.InvalidOperation("仅 STABLE 状态的 Author Core 可被修改"))
        }
        val oldPattern = patternOf(old)
            ?: throw ApplicationException(ApplicationError.EntityNotFound("Core ${coreId.value} 缺少关联 Pattern"))
        val now = Clock.System.now()
        val evidenceRefs = repository.listEvidenceLinks(old.corePatternKey).map { it.evidenceId }

        val pattern = AuthorCorePattern(
            patternId = AuthorCorePatternId(nextId()),
            patternKey = old.corePatternKey,
            statement = statement ?: oldPattern.statement,
            condition = condition ?: oldPattern.condition,
            direction = direction ?: oldPattern.direction,
            scope = old.scope,
            novelId = old.novelId,
            version = oldPattern.version + 1,
            confidence = old.confidence,
            status = AuthorCoreStatus.STABLE,
            evidenceRefs = evidenceRefs,
            createdAt = now,
            updatedAt = now,
        )
        repository.upsertAuthorCorePattern(pattern)

        val newCore = AuthorCore(
            coreId = AuthorCoreId(nextId()),
            version = old.version + 1,
            scope = old.scope,
            novelId = old.novelId,
            status = AuthorCoreStatus.STABLE,
            confirmed = true,
            confidence = old.confidence,
            corePatternKey = old.corePatternKey,
            patternId = pattern.patternId,
            createdAt = now,
            updatedAt = now,
        )
        repository.setCoreStatus(old.coreId, AuthorCoreStatus.SUPERSEDED, supersededBy = newCore.coreId, revokedAt = null)
        repository.upsertAuthorCore(newCore)
        return newCore
    }

    /** 删除/撤销一个 Core（软撤销 REVOKED；Evidence 历史保留）。 */
    fun deleteCore(coreId: AuthorCoreId) {
        requireLearningActive()
        val core = requireCore(coreId)
        repository.setCoreStatus(coreId, AuthorCoreStatus.REVOKED, supersededBy = null, revokedAt = Clock.System.now())
    }

    // ================= Learning Control =================

    fun pauseCoreLearning() = repository.setLearningPaused(true)
    fun resumeCoreLearning() = repository.setLearningPaused(false)
    fun isLearningPaused(): Boolean = repository.isLearningPaused()

    /** Reset：只清理 AuthorCore 学习结果（Core/Pattern/Candidate/Link），**不得**触碰 P16 AuthorPreference/AuthorEvidence。 */
    fun resetCore() = repository.wipeLearningResults()

    // ================= View =================

    fun viewCores(): List<AuthorCore> = repository.listStableCores()
    fun viewCore(coreId: AuthorCoreId): AuthorCore? = repository.getAuthorCore(coreId)
    fun viewCandidates(): List<AuthorCoreCandidate> = repository.listCandidates().filter { it.status == AuthorCoreStatus.CANDIDATE }
    fun viewCandidate(candidateId: AuthorCoreCandidateId): AuthorCoreCandidate? = repository.getAuthorCoreCandidate(candidateId)

    // ================= internal =================

    /** 取/建该 patternKey+scope+novel 的候选壳（candidateId 固定；聚合在该壳上推进）。 */
    private fun ensureCandidate(
        patternKey: String,
        scope: AuthorCoreScope,
        novelId: NovelId?,
        statement: String,
        condition: String?,
        now: Instant,
    ): AuthorCoreCandidate {
        val existing = repository.listCandidates(scope, novelId)
            .firstOrNull { it.patternKey == patternKey && it.scope == scope && it.novelId == novelId }
        if (existing != null) return existing
        val shell = AuthorCoreCandidate(
            candidateId = AuthorCoreCandidateId(nextId()),
            patternKey = patternKey,
            scope = scope,
            novelId = novelId,
            statement = statement,
            condition = condition,
            recency = now,
            confidence = Confidence.HALF,
            status = AuthorCoreStatus.CANDIDATE,
            createdAt = now,
            updatedAt = now,
        )
        repository.upsertAuthorCoreCandidate(shell)
        return shell
    }

    /** 某 Core 当前的 content Pattern：优先用 patternId；退化用 key+scope 最近版本。 */
    private fun patternOf(core: AuthorCore): AuthorCorePattern? =
        core.patternId?.let { repository.getAuthorCorePattern(it) }
            ?: repository.getAuthorCorePatternsByKey(core.corePatternKey)
                .firstOrNull { it.scope == core.scope && it.novelId == core.novelId }

    /** 该 (patternKey, scope, novel) 下已存在的最高 Pattern version。 */
    private fun latestPatternVersion(patternKey: String, scope: AuthorCoreScope, novelId: NovelId?): Long =
        repository.getAuthorCorePatternsByKey(patternKey)
            .filter { it.scope == scope && it.novelId == novelId }
            .maxOfOrNull { it.version }
            ?: 0L

    private fun requireCandidate(id: AuthorCoreCandidateId): AuthorCoreCandidate =
        repository.getAuthorCoreCandidate(id)
            ?: throw ApplicationException(ApplicationError.EntityNotFound("Core Candidate 不存在: ${id.value}"))

    private fun requireCore(id: AuthorCoreId): AuthorCore =
        repository.getAuthorCore(id)
            ?: throw ApplicationException(ApplicationError.EntityNotFound("Author Core 不存在: ${id.value}"))

    private fun requireLearningActive() {
        if (repository.isLearningPaused()) {
            throw ApplicationException(ApplicationError.InvalidOperation("Author Core 学习已暂停"))
        }
    }
}