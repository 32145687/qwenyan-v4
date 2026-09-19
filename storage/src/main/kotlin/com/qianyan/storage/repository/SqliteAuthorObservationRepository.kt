package com.qianyan.storage.repository

import com.qianyan.model.AuthorObservationId
import com.qianyan.model.author.AuthorObservation
import com.qianyan.storage.db.QianyanDb

/** [AuthorObservationRepository] 的 SQLDelight + SQLite 实现（P18-A；独立 AuthorObservation Storage Boundary）。 */
class SqliteAuthorObservationRepository(private val db: QianyanDb) : AuthorObservationRepository {

    override fun upsert(observation: AuthorObservation) {
        val r = StorageMappers.domainAuthorObservation(observation)
        db.authorObservationQueries.upsertAuthorObservation(
            observation_id = r.observation_id,
            scope = r.scope,
            decision_type = r.decision_type,
            novel_id = r.novel_id,
            source = r.source,
            metadata = r.metadata,
            occurred_at = r.occurred_at,
            created_at = r.created_at,
        )
    }

    override fun getById(id: AuthorObservationId): AuthorObservation? =
        db.authorObservationQueries.getAuthorObservationById(id.value)
            .executeAsOneOrNull()
            ?.let { StorageMappers.dbAuthorObservation(it) }

    override fun exists(id: AuthorObservationId): Boolean =
        db.authorObservationQueries.existsAuthorObservation(id.value).executeAsOne() == 1L

    override fun all(): List<AuthorObservation> =
        db.authorObservationQueries.getAllAuthorObservations().executeAsList()
            .map { StorageMappers.dbAuthorObservation(it) }

    override fun count(): Long =
        db.authorObservationQueries.countAuthorObservations().executeAsOne()

    override fun delete(id: AuthorObservationId) {
        db.authorObservationQueries.deleteAuthorObservation(id.value)
    }
}