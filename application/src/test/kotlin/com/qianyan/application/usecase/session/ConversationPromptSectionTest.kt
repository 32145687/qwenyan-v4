package com.qianyan.application.usecase.session

import com.qianyan.model.NovelId
import com.qianyan.model.ProjectId
import com.qianyan.model.session.ConversationMessage
import com.qianyan.model.session.ConversationRole
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * DSH-02 · 对话上下文段落（纯函数）测试。
 *
 * 覆盖：时间升序/前缀/标题格式、条数预算（最旧先丢）、字符预算（最旧先丢）、
 * 单条超长尾部截断、空/全 ERROR → null、excludeContent 去重。
 */
class ConversationPromptSectionTest {

    private fun msg(role: ConversationRole, content: String, index: Int): ConversationMessage =
        ConversationMessage(
            messageId = "m$index",
            projectId = ProjectId("p1"),
            novelId = NovelId("n1"),
            role = role,
            content = content,
            createdAt = Instant.fromEpochSeconds(index.toLong()),
        )

    private fun codePoints(s: String): Int = s.codePointCount(0, s.length)

    /* 1. 顺序与格式：时间升序、前缀正确、标题行存在、内容单行化 */
    @Test
    fun `renders ascending order with prefixes and title`() {
        val out = assertNotNull(
            ConversationPromptSection.build(
                listOf(
                    msg(ConversationRole.USER, "先写雨夜\n要有雷声", 0),
                    msg(ConversationRole.ASSISTANT, "好的，已规划。", 1),
                    msg(ConversationRole.USER, "继续", 2),
                ),
            ),
        )
        val lines = out.lines()
        assertEquals(ConversationPromptSection.TITLE, lines[0])
        assertEquals("[用户] 先写雨夜 要有雷声", lines[1])
        assertEquals("[千言] 好的，已规划。", lines[2])
        assertEquals("[用户] 继续", lines[3])
        assertEquals(4, lines.size)
    }

    /* 2. 条数上限：25 条 + maxMessages=20 → 只保留最新 20 条，顺序仍升序 */
    @Test
    fun `keeps only newest messages when over count budget`() {
        val messages = (0 until 25).map { msg(ConversationRole.USER, "消息$it", it) }
        val out = assertNotNull(ConversationPromptSection.build(messages, maxMessages = 20))
        val lines = out.lines()
        assertEquals(21, lines.size)
        assertEquals("[用户] 消息5", lines[1])
        assertEquals("[用户] 消息24", lines[20])
    }

    /* 3. 字符预算：总长超预算 → 从最旧开始丢；最新一条完整保留 */
    @Test
    fun `drops oldest messages until under char budget`() {
        // 每条内容 20 code point；标题 30 + 每行（前缀 5 + 内容 20 + 换行 1）
        val messages = (0 until 5).map { msg(ConversationRole.USER, "第${it}条" + "甲".repeat(17), it) }
        val out = assertNotNull(ConversationPromptSection.build(messages, maxChars = 120))
        assertTrue(codePoints(out) <= 120, "总长 ${codePoints(out)} 超预算")
        assertEquals("[用户] 第2条" + "甲".repeat(17), out.lines()[1])
        assertEquals(4, out.lines().size)
        assertTrue(out.contains("第4条"), "最新一条应完整保留")
        assertFalse(out.contains("第0条"), "最旧一条应被丢弃")
    }

    /* 4. 单条超预算：尾部截断含 …、总长 ≤ 预算 */
    @Test
    fun `truncates single overlong message with ellipsis`() {
        val out = assertNotNull(
            ConversationPromptSection.build(
                listOf(msg(ConversationRole.ASSISTANT, "乙".repeat(500), 0)),
                maxChars = 200,
            ),
        )
        assertTrue(out.endsWith("…"), "超长单条应在尾部追加省略号")
        assertTrue(codePoints(out) <= 200, "总长 ${codePoints(out)} 超预算")
        assertEquals(200, codePoints(out), "应恰好用满预算（标题 30 + 换行 1 + 前缀 5 + 正文 163 + … 1）")
        assertTrue(out.contains("乙".repeat(100)), "超长内容应保留前缀")
    }

    /* 5. 空 / 全 ERROR → null；excludeContent 跳过最后一条相同 USER */
    @Test
    fun `returns null for empty or error only and excludes current input`() {
        assertNull(ConversationPromptSection.build(emptyList()))
        assertNull(
            ConversationPromptSection.build(
                listOf(
                    msg(ConversationRole.ERROR, "运行失败", 0),
                    msg(ConversationRole.ERROR, "重试失败", 1),
                ),
            ),
        )

        val out = assertNotNull(
            ConversationPromptSection.build(
                listOf(
                    msg(ConversationRole.USER, "第一轮请求", 0),
                    msg(ConversationRole.ASSISTANT, "第一轮回复", 1),
                    msg(ConversationRole.USER, "当前请求", 2),
                ),
                excludeContent = "当前请求",
            ),
        )
        assertFalse(out.contains("当前请求"), "当前输入不得重复注入")
        assertTrue(out.contains("第一轮请求"))
        assertTrue(out.contains("第一轮回复"))
        assertEquals(3, out.lines().size)
    }

    /* 5b. ERROR 混在正常消息中也不得注入 */
    @Test
    fun `drops error messages but keeps valid ones`() {
        val out = assertNotNull(
            ConversationPromptSection.build(
                listOf(
                    msg(ConversationRole.USER, "问题", 0),
                    msg(ConversationRole.ERROR, "超时了", 1),
                    msg(ConversationRole.ASSISTANT, "回答", 2),
                ),
            ),
        )
        assertFalse(out.contains("超时了"))
        assertEquals("[用户] 问题", out.lines()[1])
        assertEquals("[千言] 回答", out.lines()[2])
    }
}
