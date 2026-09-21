package com.qianyan.application.usecase.author

import com.qianyan.model.AuthorDnaFeatureId
import com.qianyan.model.AuthorDnaSourceId
import com.qianyan.model.AuthorProfileId
import com.qianyan.model.TxtDocumentId
import com.qianyan.model.author.AuthorDna
import com.qianyan.model.author.AuthorDnaSource
import com.qianyan.model.author.AuthorDnaVersion

/**
 * P18-C · AuthorDnaGateway —— Android / 未来 Desktop 共用 Author DNA Application seam（极薄委托）。
 *
 * 只暴露 P18-C 最小能力：来源管理 / Full Rebuild / 视图 / Confirm / Reject / AuthorDnaLite 投影。
 * UI 只能经本 seam 触 Author DNA 能力，不得触碰 AuthorDnaRepository / SQLDelight / LLM（DEC-P18C-014/015）。
 */
interface AuthorDnaGateway {

    // ---- Source ----
    fun registerSource(authorId: AuthorProfileId, documentId: TxtDocumentId): AuthorDnaSource
    fun listSources(authorId: AuthorProfileId): List<AuthorDnaSource>
    fun deregisterSource(authorId: AuthorProfileId, sourceId: AuthorDnaSourceId)

    // ---- Full Rebuild ----
    fun analyze(authorId: AuthorProfileId, documentIds: List<TxtDocumentId>): AuthorDna
    fun rebuild(authorId: AuthorProfileId): AuthorDna

    // ---- View ----
    fun viewActive(authorId: AuthorProfileId): AuthorDna?
    fun listVersions(authorId: AuthorProfileId): List<AuthorDnaVersion>

    // ---- Confirm / Reject ----
    fun confirmFeature(featureId: AuthorDnaFeatureId)
    fun rejectFeature(featureId: AuthorDnaFeatureId)
}

/**
 * P18-C · AuthorDnaFacade —— [AuthorDnaGateway] 的**极薄委托**实现。
 * 纯转发 [AuthorDnaUseCases]，不携带业务规则。
 */
class AuthorDnaFacade(
    private val useCases: AuthorDnaUseCases,
) : AuthorDnaGateway {

    override fun registerSource(authorId: AuthorProfileId, documentId: TxtDocumentId): AuthorDnaSource =
        useCases.registerSource(authorId, documentId)

    override fun listSources(authorId: AuthorProfileId): List<AuthorDnaSource> =
        useCases.listSources(authorId)

    override fun deregisterSource(authorId: AuthorProfileId, sourceId: AuthorDnaSourceId) =
        useCases.deregisterSource(authorId, sourceId)

    override fun analyze(authorId: AuthorProfileId, documentIds: List<TxtDocumentId>): AuthorDna =
        useCases.analyze(authorId, documentIds)

    override fun rebuild(authorId: AuthorProfileId): AuthorDna =
        useCases.rebuild(authorId)

    override fun viewActive(authorId: AuthorProfileId): AuthorDna? =
        useCases.viewActive(authorId)

    override fun listVersions(authorId: AuthorProfileId): List<AuthorDnaVersion> =
        useCases.listVersions(authorId)

    override fun confirmFeature(featureId: AuthorDnaFeatureId) =
        useCases.confirmFeature(featureId)

    override fun rejectFeature(featureId: AuthorDnaFeatureId) =
        useCases.rejectFeature(featureId)
}