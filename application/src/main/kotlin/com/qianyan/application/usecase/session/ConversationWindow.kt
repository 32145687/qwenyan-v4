package com.qianyan.application.usecase.session

import com.qianyan.model.session.ConversationMessage
import com.qianyan.model.session.ConversationRole

/**
 * DSH-03 · 对话窗口选择（纯函数，DSH-02 规则抽取复用）。
 *
 * 只负责"选哪些消息、单条超长截到什么长度"，不负责 prompt 标题与拼接（见
 * [ConversationPromptSection]）。规则与 DSH-02 完全一致：
 *  - 只保留 USER / ASSISTANT（丢弃 ERROR）；
 *  - 仅保留最新 [maxMessages] 条（时间升序输入）；
 *  - 从最旧开始丢弃，直到渲染长度（每行 = 换行 1 + 前缀 + 正文）≤ [maxChars]；
 *  - 单条仍超预算 → 该条尾部按 code point 截断并追加 `…`；
 *  - [excludeContent] 非空时跳过内容完全相等的**最后一条** USER。
 *
 * 注意：[maxChars] 是**窗口渲染长度**（不含 prompt 标题）；调用方如需含标题的总预算，
 * 应自行扣减标题长度后再传入。
 */
object ConversationWindow {

    internal const val USER_PREFIX = "[用户] "
    internal const val ASSISTANT_PREFIX = "[千言] "
    private const val ELLIPSIS = "…"

    fun select(
        messages: List<ConversationMessage>,
        maxMessages: Int = 20,
        maxChars: Int = 2000,
        excludeContent: String? = null,
    ): List<ConversationMessage> {
        if (maxMessages <= 0 || maxChars <= 0) return emptyList()

        val excludeIndex = excludeContent?.takeIf { it.isNotBlank() }?.let { content ->
            messages.indexOfLast { it.role == ConversationRole.USER && it.content == content }
        } ?: -1

        var kept = messages.withIndex()
            .filter { (i, m) ->
                i != excludeIndex && (m.role == ConversationRole.USER || m.role == ConversationRole.ASSISTANT)
            }
            .map { it.value }
            .sortedBy { it.createdAt }
        if (kept.isEmpty()) return emptyList()
        if (kept.size > maxMessages) kept = kept.takeLast(maxMessages)

        while (kept.size > 1 && renderedChars(kept) > maxChars) {
            kept = kept.drop(1)
        }
        if (renderedChars(kept) <= maxChars) return kept

        val only = kept.single()
        // 渲染长度 = 换行 1 + 前缀 + 正文 + 省略号 1
        val contentBudget = maxChars - 1 - codePoints(prefixOf(only.role)) - 1
        if (contentBudget <= 0) return emptyList()
        val content = normalize(only.content)
        if (codePoints(content) <= contentBudget) return kept
        val truncated = content.substring(0, content.offsetByCodePoints(0, contentBudget)) + ELLIPSIS
        return listOf(only.copy(content = truncated))
    }

    internal fun prefixOf(role: ConversationRole): String =
        if (role == ConversationRole.USER) USER_PREFIX else ASSISTANT_PREFIX

    /** 内容单行化：换行替换为空格并去掉首尾空白。 */
    internal fun normalize(content: String): String =
        content.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ').trim()

    internal fun codePoints(text: String): Int = text.codePointCount(0, text.length)

    private fun renderedChars(messages: List<ConversationMessage>): Int =
        messages.sumOf { 1 + codePoints(prefixOf(it.role)) + codePoints(normalize(it.content)) }
}
