package com.qianyan.application.usecase.session

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.qianyan.model.NovelId
import com.qianyan.model.ProjectId
import com.qianyan.model.session.ConversationMessage
import com.qianyan.model.session.ConversationRole
import com.qianyan.storage.db.QianyanDbFactory
import kotlinx.datetime.Instant
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * DSH-03 · 会话窗口纯函数 + 会话治理守卫。
 *
 *  - 窗口选择与 DSH-02 完全一致（条数/字符双预算、最旧先丢、单条截尾、excludeContent）；
 *  - 守卫一：AgentConversationMessage 真实表结构不含任何事实源列（PRAGMA table_info）；
 *  - 守卫二：usecase/session 包源码不出现 MemoryEntry / CandidateFact / Canonical 写入调用。
 */
class ConversationWindowTest {

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

    /* 1. 条数窗口：25 条 / maxMessages=20 → 最新 20 条、时间升序 */
    @Test
    fun `selects newest messages in ascending order`() {
        val selected = ConversationWindow.select(
            (0 until 25).map { msg(ConversationRole.USER, "消息$it", it) },
            maxMessages = 20,
        )
        assertEquals(20, selected.size)
        assertEquals((5..24).map { "消息$it" }, selected.map { it.content })
        assertEquals(selected.sortedBy { it.createdAt }, selected, "顺序必须仍是时间升序")
    }

    /* 2. 单条超预算 → 截尾含 …、总长 ≤ 预算；excludeContent 只跳过末尾同文 USER */
    @Test
    fun `truncates overlong message and excludes current input`() {
        val selected = ConversationWindow.select(
            listOf(msg(ConversationRole.ASSISTANT, "乙".repeat(500), 0)),
            maxChars = 170,
        )
        assertEquals(1, selected.size)
        assertTrue(selected.single().content.endsWith("…"), "超长单条必须截尾追加省略号")
        // 窗口内渲染长度 = 换行 1 + 前缀 5 + 正文；必须 ≤ 预算
        val rendered = 1 + 5 + codePoints(selected.single().content)
        assertTrue(rendered <= 170, "渲染长度 $rendered 超预算")

        val excluded = ConversationWindow.select(
            listOf(
                msg(ConversationRole.USER, "当前请求", 0),
                msg(ConversationRole.USER, "旧请求", 1),
                msg(ConversationRole.USER, "当前请求", 2),
            ),
            excludeContent = "当前请求",
        )
        assertEquals(2, excluded.size, "只跳过最后一条同文 USER")
        assertEquals("旧请求", excluded.last().content)
        assertEquals(1, excluded.count { it.content == "当前请求" }, "更早的同文消息保留")
    }

    /* 3. 守卫一：真实表结构不含事实源列 */
    @Test
    fun `conversation table has no fact source columns`() {
        val handle = QianyanDbFactory.open(JdbcSqliteDriver.IN_MEMORY)
        try {
            val cols = handle.driver.executeQuery(
                null,
                "PRAGMA table_info(AgentConversationMessage)",
                { cursor ->
                    val names = mutableListOf<String>()
                    while (cursor.next().value) names.add(cursor.getString(1)!!)
                    app.cash.sqldelight.db.QueryResult.Value(names)
                },
                0,
            ).value
            assertEquals(
                setOf("message_id", "project_id", "novel_id", "role", "content", "created_at"),
                cols.toSet(),
                "对话表只允许会话转录字段",
            )
            listOf("fact_id", "memory_id", "candidate_fact_id").forEach { forbidden ->
                assertTrue(cols.none { it == forbidden }, "对话表不得出现事实源字段：$forbidden")
            }
        } finally {
            handle.driver.close()
        }
    }

    /* 4. 守卫二：session 包源码不引用事实源模型（注释除外） */
    @Test
    fun `session package never touches fact source models`() {
        val dir = Path.of("src/main/kotlin/com/qianyan/application/usecase/session")
        val files = Files.list(dir).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".kt") }.toList()
        }
        assertTrue(files.isNotEmpty(), "session 包不得为空")
        val offenders = files.mapNotNull { path ->
            val code = stripComments(Files.readString(path))
            listOf("MemoryEntry", "CandidateFact", "Canonical")
                .firstOrNull { it in code }
                ?.let { "${path.fileName}: $it" }
        }
        assertTrue(offenders.isEmpty(), "session 包不得引用事实源模型：$offenders")
    }

    /** 去掉块注释与行注释后再扫描（文档中的边界说明不算写入调用）。 */
    private fun stripComments(source: String): String =
        source.replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
            .lineSequence()
            .joinToString("\n") { it.substringBefore("//") }
}
