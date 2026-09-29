package com.qianyan.storage.repository

import com.qianyan.model.ActivityId
import com.qianyan.model.AgentSessionId
import com.qianyan.model.ProjectId
import com.qianyan.model.log.Activity
import com.qianyan.storage.db.QianyanDb

/** [ActivityRepository] 的 SQLDelight + SQLite 实现（I4）。 */
class SqliteActivityRepository(
    private val db: QianyanDb,
) : ActivityRepository {

    override fun save(activity: Activity) {
        val row = StorageMappers.domainActivity(activity)
        db.activityQueries.upsertActivity(
            activity_id = row.activity_id,
            session_id = row.session_id,
            project_id = row.project_id,
            kind = row.kind,
            status = row.status,
            started_at = row.started_at,
            completed_at = row.completed_at,
            summary = row.summary,
            error = row.error,
        )
    }

    override fun get(activityId: ActivityId): Activity? =
        db.activityQueries.getActivity(activityId.value).executeAsOneOrNull()
            ?.let { StorageMappers.dbActivity(it) }

    override fun listBySession(sessionId: AgentSessionId): List<Activity> =
        db.activityQueries.listActivitiesBySession(sessionId.value).executeAsList()
            .map { StorageMappers.dbActivity(it) }

    override fun listByProject(projectId: ProjectId): List<Activity> =
        db.activityQueries.listActivitiesByProject(projectId.value).executeAsList()
            .map { StorageMappers.dbActivity(it) }
}