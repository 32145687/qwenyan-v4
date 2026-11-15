package com.qianyan.application.usecase.session

import com.qianyan.application.error.ErrorMapper
import com.qianyan.application.usecase.UseCase
import com.qianyan.model.NovelId
import com.qianyan.model.ProjectId
import com.qianyan.model.session.ConversationMessage
import com.qianyan.model.session.ConversationRole
import com.qianyan.storage.repository.AgentConversationRepository

/**
 * DSH-1 · 对话记忆用例（O-113 后续）：创作页对话消息的持久化存取。
 *
 * 边界（铁律不变）：
 *  - 对话内容**不写** MemoryEntry / CandidateFact / Canonical（Memory ≠ 事实源）；
 *  - AI 提案仍走 WorkingDraft + Human Gate，不经本用例；
 *  - 聊天事实不自动晋升为小说设定（进正典只有门一条路）。
 */
class ConversationUseCases(
    private val conversations: AgentConversationRepository,
    errorMapper: ErrorMapper,
) : UseCase(errorMapper) {

    /** 追加一条对话消息（messageId 自动生成；同 id 覆盖不产生第二行）。 */
    fun append(
        projectId: ProjectId,
        novelId: NovelId,
        role: ConversationRole,
        content: String,
    ): ConversationMessage? = guard {
        val message = ConversationMessage(
            messageId = java.util.UUID.randomUUID().toString(),
            projectId = projectId,
            novelId = novelId,
            role = role,
            content = content,
            createdAt = kotlinx.datetime.Clock.System.now(),
        )
        conversations.append(message)
        message
    }

    /** 按作品列出全部对话消息（时间升序；空作品返回空列表，不伪造）。 */
    fun listByNovel(novelId: NovelId): List<ConversationMessage> =
        guard { conversations.listByNovel(novelId) }

    /** 按作品计数（诊断用）。 */
    fun countByNovel(novelId: NovelId): Long? =
        guard { conversations.countByNovel(novelId) }
}
