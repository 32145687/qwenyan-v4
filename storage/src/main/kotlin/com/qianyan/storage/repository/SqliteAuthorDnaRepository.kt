package com.qianyan.storage.repository

import com.qianyan.model.AuthorDnaFeatureId
import com.qianyan.model.AuthorDnaSourceId
import com.qianyan.model.AuthorDnaVersionId
import com.qianyan.model.AuthorProfileId
import com.qianyan.model.author.AuthorDnaFeature
import com.qianyan.model.author.AuthorDnaFeatureStatus
import com.qianyan.model.author.AuthorDnaSource
import com.qianyan.model.author.AuthorDnaVersion
import com.qianyan.model.author.AuthorDnaVersionStatus
import com.qianyan.storage.db.QianyanDb
import kotlinx.datetime.Clock

/** [AuthorDnaRepository] 的 SQLDelight + SQLite 实现（P18-C；独立 Author DNA Storage Boundary）。 */
class SqliteAuthorDnaRepository(private val db: QianyanDb) : AuthorDnaRepository {

    override fun upsertAuthorDnaVersion(version: AuthorDnaVersion) {
        val r = StorageMappers.domainAuthorDnaVersion(version)
        db.authorDnaQueries.upsertAuthorDnaVersion(
            dna_id = r.dna_id,
            author_id = r.author_id,
            version = r.version,
            status = r.status,
            rule_version = r.rule_version,
            created_at = r.created_at,
            updated_at = r.updated_at,
        )
    }

    override fun getAuthorDnaVersion(id: AuthorDnaVersionId): AuthorDnaVersion? =
        db.authorDnaQueries.getAuthorDnaVersionById(id.value).executeAsOneOrNull()
            ?.let { StorageMappers.dbAuthorDnaVersion(it) }

    override fun listAuthorDnaVersions(authorId: AuthorProfileId): List<AuthorDnaVersion> =
        db.authorDnaQueries.getAuthorDnaVersions(authorId.value).executeAsList()
            .map { StorageMappers.dbAuthorDnaVersion(it) }

    override fun listAuthorDnaVersionsByStatus(authorId: AuthorProfileId, status: AuthorDnaVersionStatus): List<AuthorDnaVersion> =
        db.authorDnaQueries.getAuthorDnaVersionByStatus(authorId.value, status.name).executeAsList()
            .map { StorageMappers.dbAuthorDnaVersion(it) }

    override fun upsertAuthorDnaFeature(feature: AuthorDnaFeature) {
        val r = StorageMappers.domainAuthorDnaFeature(feature)
        db.authorDnaQueries.upsertAuthorDnaFeature(
            feature_id = r.feature_id,
            dna_id = r.dna_id,
            author_id = r.author_id,
            dimension = r.dimension,
            feature_key = r.feature_key,
            value_text = r.value_text,
            statement = r.statement,
            confidence = r.confidence,
            status = r.status,
            source_refs = r.source_refs,
            created_at = r.created_at,
            updated_at = r.updated_at,
        )
    }

    override fun getAuthorDnaFeature(id: AuthorDnaFeatureId): AuthorDnaFeature? =
        db.authorDnaQueries.getAuthorDnaFeatureById(id.value).executeAsOneOrNull()
            ?.let { StorageMappers.dbAuthorDnaFeature(it) }

    override fun listAuthorDnaFeatures(versionId: AuthorDnaVersionId): List<AuthorDnaFeature> =
        db.authorDnaQueries.getAuthorDnaFeatures(versionId.value).executeAsList()
            .map { StorageMappers.dbAuthorDnaFeature(it) }

    override fun setAuthorDnaFeatureStatus(id: AuthorDnaFeatureId, status: AuthorDnaFeatureStatus) {
        db.authorDnaQueries.setAuthorDnaFeatureStatus(
            status = status.name,
            updated_at = Clock.System.now().toEpochMilliseconds(),
            feature_id = id.value,
        )
    }

    override fun deleteAuthorDnaFeatures(versionId: AuthorDnaVersionId) {
        db.authorDnaQueries.deleteAuthorDnaFeaturesByDna(versionId.value)
    }

    override fun upsertAuthorDnaSource(source: AuthorDnaSource) {
        val r = StorageMappers.domainAuthorDnaSource(source)
        db.authorDnaQueries.upsertAuthorDnaSource(
            source_id = r.source_id,
            author_id = r.author_id,
            txt_document_id = r.txt_document_id,
            source_novel_id = r.source_novel_id,
            content_hash = r.content_hash,
            created_at = r.created_at,
            updated_at = r.updated_at,
        )
    }

    override fun getAuthorDnaSource(id: AuthorDnaSourceId): AuthorDnaSource? =
        db.authorDnaQueries.getAuthorDnaSourceById(id.value).executeAsOneOrNull()
            ?.let { StorageMappers.dbAuthorDnaSource(it) }

    override fun listAuthorDnaSources(authorId: AuthorProfileId): List<AuthorDnaSource> =
        db.authorDnaQueries.getAuthorDnaSources(authorId.value).executeAsList()
            .map { StorageMappers.dbAuthorDnaSource(it) }

    override fun deleteAuthorDnaSource(id: AuthorDnaSourceId) {
        db.authorDnaQueries.deleteAuthorDnaSource(id.value)
    }

    override fun deleteAuthorDnaSources(authorId: AuthorProfileId) {
        db.authorDnaQueries.deleteAuthorDnaSourcesByAuthor(authorId.value)
    }
}