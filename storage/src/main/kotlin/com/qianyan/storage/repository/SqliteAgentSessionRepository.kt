package com.qianyan.storage.repository

import com.qianyan.model.AgentSessionId
import com.qianyan.model.ProjectId
import com.qianyan.model.session.AgentSession
import com.qianyan.storage.db.QianyanDb

/** [AgentSessionRepository] 的 SQLDelight + SQLite 实现（I3）。 */
class SqliteAgentSessionRepository(
    private val db: QianyanDb,
) : AgentSessionRepository {

    override fun save(session: AgentSession) {
        val row = StorageMappers.domainAgentSession(session)
        db.agentSessionQueries.upsertAgentSession(
            session_id = row.session_id,
            project_id = row.project_id,
            novel_id = row.novel_id,
            workflow_id = row.workflow_id,
            task_id = row.task_id,
            status = row.status,
            created_at = row.created_at,
            updated_at = row.updated_at,
            last_activity_at = row.last_activity_at,
        )
    }

    override fun get(sessionId: AgentSessionId): AgentSession? =
        db.agentSessionQueries.getAgentSession(sessionId.value).executeAsOneOrNull()
            ?.let { StorageMappers.dbAgentSession(it) }

    override fun listByProject(projectId: ProjectId): List<AgentSession> =
        db.agentSessionQueries.listAgentSessionsByProject(projectId.value).executeAsList()
            .map { StorageMappers.dbAgentSession(it) }

    override fun findResumableByProject(projectId: ProjectId): AgentSession? =
        db.agentSessionQueries.findResumableSessionByProject(projectId.value).executeAsOneOrNull()
            ?.let { StorageMappers.dbAgentSession(it) }
}