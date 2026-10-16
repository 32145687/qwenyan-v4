package com.qianyan.storage.repository

import com.qianyan.model.worldmodel.CandidateFact
import com.qianyan.model.worldmodel.CandidateFactStatus
import com.qianyan.storage.db.QianyanDb

/** [WorldModelCandidateRepository] 的 SQLDelight + SQLite 实现（v28，CandidateFact 表）。 */
class SqliteWorldModelCandidateRepository(
    private val db: QianyanDb,
) : WorldModelCandidateRepository {

    override fun save(candidate: CandidateFact) {
        val row = StorageMappers.domainCandidateFact(candidate)
        db.worldModelCandidateQueries.insertCandidateFact(
            candidate_id = row.candidate_id,
            history_id = row.history_id,
            commit_id = row.commit_id,
            novel_id = row.novel_id,
            variant_id = row.variant_id,
            scope = row.scope,
            chapter_id = row.chapter_id,
            kind = row.kind,
            operation = row.operation,
            fact_name = row.fact_name,
            summary = row.summary,
            source_excerpt = row.source_excerpt,
            reason = row.reason,
            status = row.status,
            decision_reason = row.decision_reason,
            conflict_with = row.conflict_with,
            resulting_fact_id = row.resulting_fact_id,
            created_at = row.created_at,
        )
    }

    override fun getById(candidateId: String): CandidateFact? =
        db.worldModelCandidateQueries.getCandidateFactById(candidateId).executeAsOneOrNull()
            ?.let { StorageMappers.dbCandidateFact(it) }

    override fun listByHistory(historyId: String): List<CandidateFact> =
        db.worldModelCandidateQueries.listCandidateFactsByHistory(historyId).executeAsList()
            .map { StorageMappers.dbCandidateFact(it) }

    override fun listByStatus(status: CandidateFactStatus): List<CandidateFact> =
        db.worldModelCandidateQueries.listCandidateFactsByStatus(status.name).executeAsList()
            .map { StorageMappers.dbCandidateFact(it) }

    override fun updateStatus(
        candidateId: String,
        status: CandidateFactStatus,
        decisionReason: String?,
        conflictWith: String?,
        resultingFactId: String?,
    ) {
        db.worldModelCandidateQueries.updateCandidateFactStatus(
            candidate_id = candidateId,
            status = status.name,
            decision_reason = decisionReason,
            conflict_with = conflictWith,
            resulting_fact_id = resultingFactId,
        )
    }
}
