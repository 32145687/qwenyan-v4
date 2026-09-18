package com.qianyan.storage.repository

import com.qianyan.model.AuthorEvidenceId
import com.qianyan.model.AuthorPreferenceId
import com.qianyan.model.NovelId
import com.qianyan.model.author.AuthorEvidence
import com.qianyan.model.author.AuthorPreference
import com.qianyan.model.author.AuthorProfile
import com.qianyan.model.author.PreferenceScope
import com.qianyan.storage.db.QianyanDb

/** [AuthorPreferenceRepository] 的 SQLDelight + SQLite 实现（P16 AIL-1；独立 Author Storage Boundary）。 */
class SqliteAuthorPreferenceRepository(private val db: QianyanDb) : AuthorPreferenceRepository {

    override fun upsertAuthorProfile(profile: AuthorProfile) {
        val r = StorageMappers.domainAuthorProfile(profile)
        db.authorPreferenceQueries.upsertAuthorProfile(
            profile_id = r.profile_id,
            display_name = r.display_name,
            created_at = r.created_at,
            updated_at = r.updated_at,
        )
    }

    override fun getAuthorProfile(): AuthorProfile? =
        db.authorPreferenceQueries.getAuthorProfile().executeAsOneOrNull()
            ?.let { StorageMappers.dbAuthorProfile(it) }

    override fun upsertAuthorPreference(preference: AuthorPreference) {
        val r = StorageMappers.domainAuthorPreference(preference)
        db.authorPreferenceQueries.upsertAuthorPreference(
            preference_id = r.preference_id,
            scope = r.scope,
            novel_id = r.novel_id,
            dimension = r.dimension,
            statement = r.statement,
            origin = r.origin,
            confidence = r.confidence,
            confirmed = r.confirmed,
            revocable = r.revocable,
            paused = r.paused,
            obtained_at = r.obtained_at,
            expiry = r.expiry,
            created_at = r.created_at,
            updated_at = r.updated_at,
        )
    }

    override fun getAuthorPreference(id: AuthorPreferenceId): AuthorPreference? =
        db.authorPreferenceQueries.getAuthorPreferenceById(id.value).executeAsOneOrNull()
            ?.let { StorageMappers.dbAuthorPreference(it) }

    override fun listPreferences(scope: PreferenceScope?, novelId: NovelId?): List<AuthorPreference> {
        val rows = when {
            novelId != null ->
                db.authorPreferenceQueries.getAuthorPreferencesByNovel(novelId.value).executeAsList()
            scope != null ->
                db.authorPreferenceQueries.getAuthorPreferencesByScope(scope.name).executeAsList()
            else ->
                db.authorPreferenceQueries.getAllAuthorPreferences().executeAsList()
        }
        return rows.map { StorageMappers.dbAuthorPreference(it) }
    }

    override fun deleteAuthorPreference(id: AuthorPreferenceId) {
        db.authorPreferenceQueries.deleteAuthorPreference(id.value)
    }

    override fun upsertAuthorEvidence(evidence: AuthorEvidence) {
        val r = StorageMappers.domainAuthorEvidence(evidence)
        db.authorPreferenceQueries.upsertAuthorEvidence(
            evidence_id = r.evidence_id,
            novel_id = r.novel_id,
            type = r.type,
            detail = r.detail,
            source = r.source,
            observed_at = r.observed_at,
        )
    }

    override fun getAuthorEvidence(id: AuthorEvidenceId): AuthorEvidence? =
        db.authorPreferenceQueries.getAuthorEvidenceById(id.value).executeAsOneOrNull()
            ?.let { StorageMappers.dbAuthorEvidence(it) }

    override fun listEvidence(novelId: NovelId?): List<AuthorEvidence> {
        val rows = if (novelId != null) {
            db.authorPreferenceQueries.getAuthorEvidenceByNovel(novelId.value).executeAsList()
        } else {
            db.authorPreferenceQueries.getAllAuthorEvidence().executeAsList()
        }
        return rows.map { StorageMappers.dbAuthorEvidence(it) }
    }
}