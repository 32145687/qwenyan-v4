package com.qianyan.storage.repository

import com.qianyan.model.AuthorDnaFeatureId
import com.qianyan.model.AuthorDnaSourceId
import com.qianyan.model.AuthorDnaVersionId
import com.qianyan.model.AuthorProfileId
import com.qianyan.model.NovelId
import com.qianyan.model.TxtDocumentId
import com.qianyan.model.author.AuthorDnaFeature
import com.qianyan.model.author.AuthorDnaFeatureStatus
import com.qianyan.model.author.AuthorDnaSource
import com.qianyan.model.author.AuthorDnaVersion
import com.qianyan.model.author.AuthorDnaVersionStatus

/**
 * Author DNA 持久化仓储（P18-C · DEC-P18C-001~015）。
 *
 * 语义：
 *  - **独立 Author DNA Storage Boundary**：只持久化 AuthorDna 表族（Version / Feature / Source），
 *    不修改 AuthorCore / AuthorPreference / AuthorObservation，不改任何 Story 表。
 *  - DNA scope = GLOBAL；identity = authorId（== AuthorProfile.profile_id）。sourceNovelId 仅作 provenance。
 *  - 版本化：每次 Full Rebuild 产生新 version；旧 version 保留（SUPERSEDED=历史）。Feature 生命周期
 *    CANDIDATE → ACTIVE/REJECTED，REJECT 仅属当前 version。
 *  - 本层只做持久化；LLM 解析 / dimension 校验 / confidence 校正 / sourceLocation binding / 版本生成 / 确认语义
 *    在 Application 层（[com.qianyan.application.usecase.author.AuthorDnaUseCases]）。
 */
interface AuthorDnaRepository {

    // ---- AuthorDnaVersion ----
    fun upsertAuthorDnaVersion(version: AuthorDnaVersion)
    fun getAuthorDnaVersion(id: AuthorDnaVersionId): AuthorDnaVersion?
    fun listAuthorDnaVersions(authorId: AuthorProfileId): List<AuthorDnaVersion>
    /** 该作者当前 status（如 ACTIVE）的版本清单（version 降序）。 */
    fun listAuthorDnaVersionsByStatus(authorId: AuthorProfileId, status: AuthorDnaVersionStatus): List<AuthorDnaVersion>

    // ---- AuthorDnaFeature ----
    fun upsertAuthorDnaFeature(feature: AuthorDnaFeature)
    fun getAuthorDnaFeature(id: AuthorDnaFeatureId): AuthorDnaFeature?
    fun listAuthorDnaFeatures(versionId: AuthorDnaVersionId): List<AuthorDnaFeature>
    fun setAuthorDnaFeatureStatus(id: AuthorDnaFeatureId, status: AuthorDnaFeatureStatus)
    fun deleteAuthorDnaFeatures(versionId: AuthorDnaVersionId)

    // ---- AuthorDnaSource ----
    fun upsertAuthorDnaSource(source: AuthorDnaSource)
    fun getAuthorDnaSource(id: AuthorDnaSourceId): AuthorDnaSource?
    fun listAuthorDnaSources(authorId: AuthorProfileId): List<AuthorDnaSource>
    fun deleteAuthorDnaSource(id: AuthorDnaSourceId)
    fun deleteAuthorDnaSources(authorId: AuthorProfileId)
}