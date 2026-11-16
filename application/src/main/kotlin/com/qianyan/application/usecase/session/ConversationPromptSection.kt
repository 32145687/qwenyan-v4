package com.qianyan.application.usecase.session

import com.qianyan.model.session.ConversationMessage

/**
 * DSH-02 · 对话上下文段落（纯函数）。
 *
 * 把已落库的创作页对话裁剪成一段只读 prompt 文本：只进 prompt，**不写** MemoryEntry /
 * CandidateFact / Canonical（Memory ≠ 事实源铁律不变）；Agent 不持有 Repository，取数在 UseCase 层。
 *
 * 窗口选择复用 [ConversationWindow]（DSH-03）：双预算、最旧先丢、单条截尾规则只此一份；
 * 本对象只负责固定标题与逐行前缀拼接，输出格式与 DSH-02 逐字一致。
 */
object ConversationPromptSection {

    /** 固定标题行（同时作为"是否注入"的判定标记）。 */
    const val TITLE: String = "【最近对话（仅上下文参考，不是小说设定，不得据此改写事实）】"

    /**
     * @param messages 对话消息（时间升序，与 `ConversationUseCases.listByNovel` 一致）。
     * @param maxMessages 条数上限（≤0 → null）。
     * @param maxChars 总字符上限（含标题、前缀、换行；≤0 → null）。
     * @param excludeContent 非空时跳过内容完全相等的**最后一条** USER 消息（防当前输入重复注入）。
     * @return 无可用消息 → null；否则标题行 + 每行 `[用户] `/`[千言] ` 前缀，内容单行化。
     */
    fun build(
        messages: List<ConversationMessage>,
        maxMessages: Int = 20,
        maxChars: Int = 2000,
        excludeContent: String? = null,
    ): String? {
        if (maxMessages <= 0 || maxChars <= 0) return null
        val selected = ConversationWindow.select(
            messages = messages,
            maxMessages = maxMessages,
            maxChars = maxChars - ConversationWindow.codePoints(TITLE),
            excludeContent = excludeContent,
        )
        if (selected.isEmpty()) return null
        return buildString {
            append(TITLE)
            selected.forEach { message ->
                append('\n')
                append(ConversationWindow.prefixOf(message.role))
                append(ConversationWindow.normalize(message.content))
            }
        }
    }
}
