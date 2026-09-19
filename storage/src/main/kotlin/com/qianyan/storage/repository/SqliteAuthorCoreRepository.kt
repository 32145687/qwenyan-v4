package com.qianyan.storage.repository

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
import com.qianyan.storage.db.QianyanDb
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/** [AuthorCoreRepository] 的 SQLDelight + SQLite 实现（P17；独立 AuthorCore Storage Boundary）。 */
class SqliteAuthorCoreRepository(private val db: QianyanDb) : AuthorCoreRepository {

    // ---- AuthorCore ----
    override fun upsertAuthorCore(core: AuthorCore) {
        val r = StorageMappers.domainAuthorCore(core)
        db.authorCoreQueries.upsertAuthorCore(
            core_id = r.core_id, version = r.version, scope = r.scope, novel_id = r.novel_id,
            status = r.status, confirmed = r.confirmed, confidence = r.confidence,
            core_pattern_key = r.core_pattern_key, pattern_id = r.pattern_id,
            created_at = r.created_at, updated_at = r.updated_at,
            superseded_by = r.superseded_by, revoked_at = r.revoked_at,
        )
    }

    override fun getAuthorCore(id: AuthorCoreId): AuthorCore? =
        db.authorCoreQueries.getAuthorCoreById(id.value).executeAsOneOrNull()?.let { StorageMappers.dbAuthorCore(it) }

    override fun listCores(scope: AuthorCoreScope?): List<AuthorCore> {
        val rows = if (scope != null) {
            db.authorCoreQueries.getAuthorCoresByScope(scope.name).executeAsList()
        } else {
            db.authorCoreQueries.getAllAuthorCores().executeAsList()
        }
        return rows.map { StorageMappers.dbAuthorCore(it) }
    }

    override fun listStableCores(): List<AuthorCore> =
        listCores().filter { it.status == AuthorCoreStatus.STABLE }

    override fun setCoreStatus(id: AuthorCoreId, status: AuthorCoreStatus, supersededBy: AuthorCoreId?, revokedAt: Instant?) {
        db.authorCoreQueries.setAuthorCoreStatus(
            status = status.name,
            updated_at = Clock.System.now().toEpochMillisSafe(),
            superseded_by = supersededBy?.value,
            revoked_at = revokedAt?.toEpochMillisSafe(),
            core_id = id.value,
        )
    }

    override fun deleteAuthorCore(id: AuthorCoreId) {
        db.authorCoreQueries.deleteAuthorCore(id.value)
    }

    // ---- AuthorCorePattern ----
    override fun upsertAuthorCorePattern(pattern: AuthorCorePattern) {
        val r = StorageMappers.domainAuthorCorePattern(pattern)
        db.authorCoreQueries.upsertAuthorCorePattern(
            pattern_id = r.pattern_id, pattern_key = r.pattern_key, statement = r.statement,
            condition = r.condition, direction = r.direction, scope = r.scope, novel_id = r.novel_id,
            version = r.version, confidence = r.confidence, status = r.status,
            evidence_refs = r.evidence_refs, created_at = r.created_at, updated_at = r.updated_at,
        )
    }

    override fun getAuthorCorePattern(id: AuthorCorePatternId): AuthorCorePattern? =
        db.authorCoreQueries.getAuthorCorePatternById(id.value).executeAsOneOrNull()?.let { StorageMappers.dbAuthorCorePattern(it) }

    override fun getAuthorCorePatternsByKey(patternKey: String): List<AuthorCorePattern> =
        db.authorCoreQueries.getAuthorCorePatternsByKey(patternKey).executeAsList().map { StorageMappers.dbAuthorCorePattern(it) }

    override fun listAuthorCorePatterns(scope: AuthorCoreScope?, novelId: NovelId?): List<AuthorCorePattern> {
        val rows = when {
            novelId != null ->
                db.authorCoreQueries.getAuthorCorePatternsByNovel(novelId.value).executeAsList()
            scope != null ->
                db.authorCoreQueries.getAuthorCorePatternsByScope(scope.name).executeAsList()
            else ->
                db.authorCoreQueries.getAllAuthorCorePatterns().executeAsList()
        }
        return rows.map { StorageMappers.dbAuthorCorePattern(it) }
    }

    override fun deleteAuthorCorePattern(id: AuthorCorePatternId) {
        db.authorCoreQueries.deleteAuthorCorePattern(id.value)
    }

    // ---- AuthorCoreCandidate ----
    override fun upsertAuthorCoreCandidate(candidate: AuthorCoreCandidate) {
        val r = StorageMappers.domainAuthorCoreCandidate(candidate)
        db.authorCoreQueries.upsertAuthorCoreCandidate(
            candidate_id = r.candidate_id, pattern_key = r.pattern_key, scope = r.scope, novel_id = r.novel_id,
            statement = r.statement, condition = r.condition, positive_evidence = r.positive_evidence,
            negative_evidence = r.negative_evidence, observation_count = r.observation_count,
            weighted_score = r.weighted_score, consistency = r.consistency, recency = r.recency,
            contradiction_count = r.contradiction_count, confidence = r.confidence, status = r.status,
            created_at = r.created_at, updated_at = r.updated_at,
        )
    }

    override fun getAuthorCoreCandidate(id: AuthorCoreCandidateId): AuthorCoreCandidate? =
        db.authorCoreQueries.getAuthorCoreCandidateById(id.value).executeAsOneOrNull()?.let { StorageMappers.dbAuthorCoreCandidate(it) }

    override fun listCandidates(scope: AuthorCoreScope?, novelId: NovelId?): List<AuthorCoreCandidate> {
        val rows = when {
            novelId != null ->
                db.authorCoreQueries.getAuthorCoreCandidatesByNovel(novelId.value).executeAsList()
            scope != null ->
                db.authorCoreQueries.getAuthorCoreCandidatesByScope(scope.name).executeAsList()
            else ->
                db.authorCoreQueries.getAllAuthorCoreCandidates().executeAsList()
        }
        return rows.map { StorageMappers.dbAuthorCoreCandidate(it) }
    }

    override fun deleteAuthorCoreCandidate(id: AuthorCoreCandidateId) {
        db.authorCoreQueries.deleteAuthorCoreCandidate(id.value)
    }

    // ---- AuthorCoreEvidenceLink（幂等） ----
    override fun linkEvidence(link: AuthorCoreEvidenceLink) {
        val r = StorageMappers.domainAuthorCoreEvidenceLink(link)
        db.authorCoreQueries.insertAuthorCoreEvidenceLink(
            link_id = r.link_id, core_pattern_key = r.core_pattern_key,
            evidence_id = r.evidence_id, provenance_novel_id = r.provenance_novel_id,
            created_at = r.created_at,
        )
    }

    override fun evidenceLinkExists(corePatternKey: String, evidenceId: AuthorEvidenceId): Boolean =
        db.authorCoreQueries.evidenceLinkExists(corePatternKey, evidenceId.value).executeAsOne() > 0

    override fun listEvidenceLinks(corePatternKey: String): List<AuthorCoreEvidenceLink> =
        db.authorCoreQueries.getEvidenceLinksByPattern(corePatternKey).executeAsList().map { StorageMappers.dbAuthorCoreEvidenceLink(it) }

    /** P18-B：某 patternKey 已链证据的去重来源 Novel 数（runtime-derived，DEC-P18B-005）。 */
    override fun distinctProvenanceNovelCount(patternKey: String): Long =
        db.authorCoreQueries.selectDistinctProvenanceNovelCount(patternKey).executeAsOne()

    override fun listEvidenceKeysByEvidenceId(evidenceId: AuthorEvidenceId): List<String> =
        db.authorCoreQueries.getEvidenceLinksByEvidenceId(evidenceId.value).executeAsList().map { it.core_pattern_key }

    override fun deleteEvidenceLinksByPattern(corePatternKey: String) {
        db.authorCoreQueries.deleteEvidenceLinksByPattern(corePatternKey)
    }

    // ---- AuthorCoreLearning ----
    override fun isLearningPaused(): Boolean =
        db.authorCoreQueries.getAuthorCoreLearning().executeAsOneOrNull()?.paused ?: false

    override fun setLearningPaused(paused: Boolean) {
        db.authorCoreQueries.upsertAuthorCoreLearning(paused)
    }

    override fun wipeLearningResults() {
        db.authorCoreQueries.wipeAuthorCoreCandidates()
        db.authorCoreQueries.wipeAuthorCoreEvidenceLinks()
        db.authorCoreQueries.wipeAuthorCorePatterns()
        db.authorCoreQueries.wipeAuthorCores()
        setLearningPaused(false)
    }

    private fun Instant.toEpochMillisSafe(): Long = epochSeconds * 1000 + nanosecondsOfSecond / 1_000_000
}