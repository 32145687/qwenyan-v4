package com.qianyan.storage.repository

import com.qianyan.model.NovelId
import com.qianyan.model.ProjectId
import com.qianyan.model.project.ProjectState
import com.qianyan.storage.db.QianyanDb

/** [ProjectStateRepository] 的 SQLDelight + SQLite 实现（I1）。 */
class SqliteProjectStateRepository(
    private val db: QianyanDb,
) : ProjectStateRepository {

    override fun save(state: ProjectState) {
        val row = StorageMappers.domainProjectState(state)
        db.projectStateQueries.upsertProjectState(
            project_id = row.project_id,
            novel_id = row.novel_id,
            active_variant_id = row.active_variant_id,
            active_chapter_id = row.active_chapter_id,
            active_task_id = row.active_task_id,
            updated_at = row.updated_at,
        )
    }

    override fun get(projectId: ProjectId): ProjectState? =
        db.projectStateQueries.getProjectState(projectId.value).executeAsOneOrNull()
            ?.let { StorageMappers.dbProjectState(it) }

    override fun getByNovel(novelId: NovelId): ProjectState? =
        db.projectStateQueries.getProjectStateByNovel(novelId.value).executeAsOneOrNull()
            ?.let { StorageMappers.dbProjectState(it) }
}