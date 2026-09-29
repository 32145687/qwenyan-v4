package com.qianyan.storage.repository

import com.qianyan.model.ProjectId
import com.qianyan.model.change.ChangeArtifactId
import com.qianyan.model.commit.CommitHistoryEntry
import com.qianyan.model.commit.CommitHistoryId
import com.qianyan.storage.db.QianyanDb

/** [CommitHistoryRepository] 的 SQLDelight + SQLite 实现（I10）。 */
class SqliteCommitHistoryRepository(
    private val db: QianyanDb,
) : CommitHistoryRepository {

    override fun append(entry: CommitHistoryEntry) {
        val row = StorageMappers.domainCommitHistory(entry)
        db.commitHistoryQueries.insertCommitHistory(
            history_id = row.history_id,
            commit_id = row.commit_id,
            project_id = row.project_id,
            operation = row.operation,
            artifact_id = row.artifact_id,
            novel_id = row.novel_id,
            chapter_id = row.chapter_id,
            variant_id = row.variant_id,
            previous_draft_id = row.previous_draft_id,
            resulting_draft_id = row.resulting_draft_id,
            previous_content = row.previous_content,
            resulting_content = row.resulting_content,
            summary = row.summary,
            reverted_history_id = row.reverted_history_id,
            created_at = row.created_at,
        )
    }

    override fun getById(historyId: CommitHistoryId): CommitHistoryEntry? =
        db.commitHistoryQueries.getCommitHistoryById(historyId.value).executeAsOneOrNull()
            ?.let { StorageMappers.dbCommitHistory(it) }

    override fun getByArtifact(artifactId: ChangeArtifactId): CommitHistoryEntry? =
        db.commitHistoryQueries.getCommitHistoryByArtifactId(artifactId.value).executeAsOneOrNull()
            ?.let { StorageMappers.dbCommitHistory(it) }

    override fun listByProject(projectId: ProjectId): List<CommitHistoryEntry> =
        db.commitHistoryQueries.listCommitHistoryByProject(projectId.value).executeAsList()
            .map { StorageMappers.dbCommitHistory(it) }
}