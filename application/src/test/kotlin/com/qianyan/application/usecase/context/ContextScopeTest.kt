package com.qianyan.application.usecase.context

import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.model.IntentType
import com.qianyan.model.context.ContextRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * I6 · Scope 测试（§26 Scope）：Project A 不能生成 Project B 的信息。
 *
 * Context Engine 的所有来源都从 `projectId → novelId` 解析出的**同一作用域**读取，
 * 越界一律按"不存在"（NOT_FOUND）处理，不泄漏存在性。
 */
class ContextScopeTest {

    @Test
    fun `pack for one project contains no data from another project`() {
        val f = contextFixture()
        val a = seedProject(f, "书A", "A")
        val b = seedProject(f, "书B", "B")

        val pack = f.app.contextEngine.build(
            ContextRequest(projectId = a.projectId, purpose = IntentType.CONTINUE, focusChapterId = a.chapter2),
        )

        assertTrue(pack.items.isNotEmpty(), "Project A 自身应有内容")
        assertTrue(
            pack.items.none { it.itemId.contains(b.novelId.value) || it.itemId.contains(b.chapter1.value) || it.itemId.contains(b.chapter2.value) || it.itemId.contains(b.candidateId.value) },
            "条目 id 不得引用 Project B：${pack.items.map { it.itemId }}",
        )
        assertTrue(
            pack.items.none { it.content.contains("书B") || it.content.contains("B 苏清") },
            "条目内容不得包含 Project B 数据：${pack.items.map { it.content }}",
        )
        assertTrue(
            pack.items.all { item ->
                val ref = item.ref
                ref == null || ref.novelId == a.novelId
            },
            "所有条目的作用域引用必须指向 Project A 的 Novel",
        )
        assertEquals(a.novelId, pack.novelId)
        f.close()
    }

    @Test
    fun `session from another project is not visible`() {
        val f = contextFixture()
        val a = seedProject(f, "书A", "A")
        val b = seedProject(f, "书B", "B")
        val sessionOfB = f.app.agentSessions.startSession(b.novelId)

        val ex = assertFailsWith<ApplicationException> {
            f.app.contextEngine.build(
                ContextRequest(
                    projectId = a.projectId,
                    purpose = IntentType.CONTINUE,
                    sessionId = sessionOfB.sessionId,
                ),
            )
        }

        assertTrue(ex.error is ApplicationError.EntityNotFound, "跨 Project 的 Session → NOT_FOUND")
        f.close()
    }

    @Test
    fun `focus chapter from another project is not visible`() {
        val f = contextFixture()
        val a = seedProject(f, "书A", "A")
        val b = seedProject(f, "书B", "B")

        val ex = assertFailsWith<ApplicationException> {
            f.app.contextEngine.build(
                ContextRequest(
                    projectId = a.projectId,
                    purpose = IntentType.CONTINUE,
                    focusChapterId = b.chapter2,
                ),
            )
        }

        assertTrue(ex.error is ApplicationError.EntityNotFound, "跨 Project 的焦点章节 → NOT_FOUND")
        f.close()
    }

    @Test
    fun `session of the same project is accepted`() {
        val f = contextFixture()
        val a = seedProject(f, "书A", "A")
        val session = f.app.agentSessions.startSession(a.novelId)

        val pack = f.app.contextEngine.build(
            ContextRequest(projectId = a.projectId, purpose = IntentType.CONTINUE, sessionId = session.sessionId),
        )

        assertEquals(session.sessionId, pack.request.sessionId)
        f.close()
    }
}