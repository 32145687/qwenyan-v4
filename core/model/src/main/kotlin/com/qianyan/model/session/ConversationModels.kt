package com.qianyan.model.session

import com.qianyan.model.NovelId
import com.qianyan.model.ProjectId

/**
 * DSH-1 · 对话记忆（O-113 后续）：创作页对话消息的持久化模型。
 *
 * 边界（与 AgentSession 同口径，architecture §13）：
 *  - 只承载消息身份 + 内容 + 归属；不承载小说事实（Memory ≠ 事实源铁律不变）；
 *  - AI 提案（WorkingDraft / ChangeArtifact）仍走 WorkingDraft + Human Gate，不经本表。
 */
enum class ConversationRole {
    /** 用户输入。 */
    USER,

    /** 千言回复。 */
    ASSISTANT,

    /** 错误提示（运行失败等）。 */
    ERROR,
}

/** 一条创作对话消息（归属到作品；role + 内容 + 时间）。 */
data class ConversationMessage(
    val messageId: String,
    val projectId: ProjectId,
    val novelId: NovelId,
    val role: ConversationRole,
    val content: String,
    val createdAt: kotlinx.datetime.Instant,
)
