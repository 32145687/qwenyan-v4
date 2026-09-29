package com.qianyan.storage.repository

import com.qianyan.model.ActivityId
import com.qianyan.model.AgentSessionId
import com.qianyan.model.ProjectId
import com.qianyan.model.ToolCallId
import com.qianyan.model.log.ToolCallLog
import com.qianyan.storage.db.QianyanDb

/** [ToolCallLogRepository] 的 SQLDelight + SQLite 实现（I4）。 */
class SqliteToolCallLogRepository(
    private val db: QianyanDb,
) : ToolCallLogRepository {

    override fun save(call: ToolCallLog) {
        val row = StorageMappers.domainToolCallLog(call)
        db.toolCallLogQueries.upsertToolCallLog(
            tool_call_id = row.tool_call_id,
            activity_id = row.activity_id,
            session_id = row.session_id,
            project_id = row.project_id,
            tool_name = row.tool_name,
            status = row.status,
            started_at = row.started_at,
            completed_at = row.completed_at,
            input_summary = row.input_summary,
            output_summary = row.output_summary,
            error = row.error,
        )
    }

    override fun get(toolCallId: ToolCallId): ToolCallLog? =
        db.toolCallLogQueries.getToolCallLog(toolCallId.value).executeAsOneOrNull()
            ?.let { StorageMappers.dbToolCallLog(it) }

    override fun listByActivity(activityId: ActivityId): List<ToolCallLog> =
        db.toolCallLogQueries.listToolCallLogsByActivity(activityId.value).executeAsList()
            .map { StorageMappers.dbToolCallLog(it) }

    override fun listBySession(sessionId: AgentSessionId): List<ToolCallLog> =
        db.toolCallLogQueries.listToolCallLogsBySession(sessionId.value).executeAsList()
            .map { StorageMappers.dbToolCallLog(it) }

    override fun listByProject(projectId: ProjectId): List<ToolCallLog> =
        db.toolCallLogQueries.listToolCallLogsByProject(projectId.value).executeAsList()
            .map { StorageMappers.dbToolCallLog(it) }
}