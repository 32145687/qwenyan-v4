package com.qianyan.storage.repository

import com.qianyan.model.NovelId
import com.qianyan.model.session.ConversationMessage

/**
 * DSH-1 · 对话记忆仓储（O-113 后续；FD-9 additive：只新增表，不改既有表 / 列 / 语义）。
 *
 * 职责边界（与 AgentSession 同口径）：只承载消息身份 + 内容 + 归属；
 * 不承载小说事实——对话内容**永不**写 MemoryEntry / CandidateFact / Canonical（铁律：Memory ≠ 事实源）。
 */
interface AgentConversationRepository {

    /** 追加一条对话消息（同 messageId 覆盖，不产生第二行）。 */
    fun append(message: ConversationMessage)

    /** 按作品列出全部对话消息（创建时间升序；空作品返回空列表，不伪造）。 */
    fun listByNovel(novelId: NovelId): List<ConversationMessage>

    /** 按作品计数（诊断用）。 */
    fun countByNovel(novelId: NovelId): Long
}
