package com.qianyan.application.usecase.author

import com.qianyan.model.AuthorCoreCandidateId
import com.qianyan.model.AuthorCoreId
import com.qianyan.model.AuthorEvidenceId
import com.qianyan.model.NovelId
import com.qianyan.model.author.AuthorCore
import com.qianyan.model.author.AuthorCoreCandidate
import com.qianyan.model.author.AuthorCoreScope
import com.qianyan.model.author.AuthorEvidenceType

/**
 * P17 · AuthorCoreGateway —— Android / 未来 Desktop 共用 Author Core Application seam（DEC-P17-012/014）。
 *
 * 只暴露 P17 最小 User Control：view / confirm / reject / modify / delete / pause / resume / reset，
 * 以及 Evidence 采集入口（record / collect）。UI 只能经本 seam 触 AuthorCore 能力，不得触碰
 * AuthorCoreRepository / StorageModel / 内部候选聚合字段 / SQLDelight / LLM。
 */
interface AuthorCoreGateway {

    // ---- View ----
    fun viewCores(): List<AuthorCore>
    fun viewCore(coreId: AuthorCoreId): AuthorCore?
    fun viewCandidates(): List<AuthorCoreCandidate>
    fun viewCandidate(candidateId: AuthorCoreCandidateId): AuthorCoreCandidate?

    // ---- Confirmation ----
    fun confirmCoreCandidate(candidateId: AuthorCoreCandidateId): AuthorCore
    fun rejectCoreCandidate(candidateId: AuthorCoreCandidateId)

    // ---- Core 内容控制 ----
    fun modifyCore(coreId: AuthorCoreId, statement: String? = null, condition: String? = null, direction: String? = null): AuthorCore
    fun deleteCore(coreId: AuthorCoreId)

    // ---- Learning Control ----
    fun pauseCoreLearning()
    fun resumeCoreLearning()
    fun isLearningPaused(): Boolean
    fun resetCore()

    // ---- Evidence 采集（幂等） ----
    fun recordCoreEvidence(
        type: AuthorEvidenceType,
        evidenceId: AuthorEvidenceId,
        patternKey: String = "core:foundation",
        statement: String = "作者在长期创作中有持续而稳定的故事创作决策倾向",
        condition: String? = null,
        scope: AuthorCoreScope = AuthorCoreScope.GLOBAL,
        novelId: NovelId? = null,
        detail: String = "",
        source: String = "author-core",
    ): Boolean

    fun collectFoundationCoreEvidence(novelId: NovelId): Int
}

/**
 * P17 · AuthorCoreFacade —— [AuthorCoreGateway] 的**极薄委托**实现（镜像 P16 Facade 模式）。
 * 纯转发 [AuthorCoreUseCases]，不携带业务规则。
 */
class AuthorCoreFacade(
    private val useCases: AuthorCoreUseCases,
) : AuthorCoreGateway {

    override fun viewCores(): List<AuthorCore> = useCases.viewCores()

    override fun viewCore(coreId: AuthorCoreId): AuthorCore? = useCases.viewCore(coreId)

    override fun viewCandidates(): List<AuthorCoreCandidate> = useCases.viewCandidates()

    override fun viewCandidate(candidateId: AuthorCoreCandidateId): AuthorCoreCandidate? =
        useCases.viewCandidate(candidateId)

    override fun confirmCoreCandidate(candidateId: AuthorCoreCandidateId): AuthorCore =
        useCases.confirmCoreCandidate(candidateId)

    override fun rejectCoreCandidate(candidateId: AuthorCoreCandidateId) =
        useCases.rejectCoreCandidate(candidateId)

    override fun modifyCore(coreId: AuthorCoreId, statement: String?, condition: String?, direction: String?): AuthorCore =
        useCases.modifyCore(coreId, statement, condition, direction)

    override fun deleteCore(coreId: AuthorCoreId) = useCases.deleteCore(coreId)

    override fun pauseCoreLearning() = useCases.pauseCoreLearning()

    override fun resumeCoreLearning() = useCases.resumeCoreLearning()

    override fun isLearningPaused(): Boolean = useCases.isLearningPaused()

    override fun resetCore() = useCases.resetCore()

    override fun recordCoreEvidence(
        type: AuthorEvidenceType,
        evidenceId: AuthorEvidenceId,
        patternKey: String,
        statement: String,
        condition: String?,
        scope: AuthorCoreScope,
        novelId: NovelId?,
        detail: String,
        source: String,
    ): Boolean = useCases.recordCoreEvidence(
        patternKey = patternKey, type = type, statement = statement, condition = condition, scope = scope,
        novelId = novelId, evidenceId = evidenceId, detail = detail, source = source,
    )

    override fun collectFoundationCoreEvidence(novelId: NovelId): Int =
        useCases.collectFoundationCoreEvidence(novelId)
}