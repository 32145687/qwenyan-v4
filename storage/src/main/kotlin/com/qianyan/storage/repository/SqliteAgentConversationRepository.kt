package com.qianyan.storage.repository

import com.qianyan.model.NovelId
import com.qianyan.model.session.ConversationMessage
import com.qianyan.storage.db.QianyanDb

/** [AgentConversationRepository] 的 SQLDelight + SQLite 实现（DSH-1，FD-9 additive）。 */
class SqliteAgentConversationRepository(
    private val db: QianyanDb,
) : AgentConversationRepository {

    override fun append(message: ConversationMessage) {
        val row = StorageMappers.dbConversationMessage(message)
        db.agentConversationQueries.insertAgentConversationMessage(
            message_id = row.message_id,
            project_id = row.project_id,
            novel_id = row.novel_id,
            role = row.role,
            content = row.content,
            created_at = row.created_at,
        )
    }

    override fun listByNovel(novelId: NovelId): List<ConversationMessage> =
        db.agentConversationQueries.listAgentConversationMessagesByNovel(novelId.value)
            .executeAsList()
            .map { StorageMappers.domainConversationMessage(it) }

    override fun countByNovel(novelId: NovelId): Long =
        db.agentConversationQueries.countAgentConversationMessagesByNovel(novelId.value)
            .executeAsOne() ?: 0L
}
