package com.qianyan.storage.repository

import com.qianyan.model.AgentSessionId
import com.qianyan.model.RuntimeSessionRefId
import com.qianyan.model.runtime.RuntimeSessionRef
import com.qianyan.storage.db.QianyanDb

/** [RuntimeSessionRefRepository] 的 SQLDelight + SQLite 实现（I1）。 */
class SqliteRuntimeSessionRefRepository(
    private val db: QianyanDb,
) : RuntimeSessionRefRepository {

    override fun save(ref: RuntimeSessionRef) {
        val row = StorageMappers.domainRuntimeSessionRef(ref)
        db.runtimeSessionRefQueries.upsertRuntimeSessionRef(
            ref_id = row.ref_id,
            agent_session_id = row.agent_session_id,
            runtime_name = row.runtime_name,
            runtime_session_id = row.runtime_session_id,
            created_at = row.created_at,
        )
    }

    override fun get(refId: RuntimeSessionRefId): RuntimeSessionRef? =
        db.runtimeSessionRefQueries.getRuntimeSessionRef(refId.value).executeAsOneOrNull()
            ?.let { StorageMappers.dbRuntimeSessionRef(it) }

    override fun listByAgentSession(agentSessionId: AgentSessionId): List<RuntimeSessionRef> =
        db.runtimeSessionRefQueries.listRuntimeSessionRefsByAgentSession(agentSessionId.value).executeAsList()
            .map { StorageMappers.dbRuntimeSessionRef(it) }

    override fun latestByAgentSession(agentSessionId: AgentSessionId): RuntimeSessionRef? =
        db.runtimeSessionRefQueries.latestRuntimeSessionRefByAgentSession(agentSessionId.value).executeAsOneOrNull()
            ?.let { StorageMappers.dbRuntimeSessionRef(it) }
}