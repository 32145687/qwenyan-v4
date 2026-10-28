package com.qianyan.application.usecase.context

import com.qianyan.model.AgentSessionId
import com.qianyan.model.IntentType
import com.qianyan.model.NovelId
import com.qianyan.model.ProjectId
import com.qianyan.model.context.ContextBudgetGuard
import com.qianyan.model.context.ContextPack
import com.qianyan.model.context.ContextRequest
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

/** A2 · ContextPackStore：按 session 保存/读取、Project 隔离、有界淘汰。 */
class ContextPackStoreTest {

    private val projectA = ProjectId("p-a")
    private val projectB = ProjectId("p-b")
    private val t0 = Instant.parse("2026-01-01T00:00:00Z")
    private val clock = object : Clock {
        override fun now(): Instant = t0
    }

    private fun pack(projectId: ProjectId, id: String, version: Long = 1L): ContextPack = ContextPack(
        packId = id,
        request = ContextRequest(projectId = projectId, purpose = IntentType.CONTINUE),
        novelId = NovelId("n-$id"),
        items = emptyList(),
        budgetGuard = ContextBudgetGuard(budgetTokens = 1200, estimatedTokens = 0, isTruncated = false),
        packVersion = version,
        createdAt = t0,
    )

    @Test
    fun `stores and reads the exact pack by session and project`() {
        val store = InMemoryContextPackStore(clock = clock)
        val session = AgentSessionId("s-1")
        val pack = pack(projectA, "ctx-1", 7L)

        store.put(session, projectA, pack)

        val stored = store.find(session, projectA)
        assertSame(pack, stored?.pack)
        assertEquals(projectA, stored?.projectId)
        assertEquals(session, stored?.sessionId)
        assertEquals(t0, stored?.publishedAt)
        assertNull(store.find(session, projectB), "跨 Project 必须按不存在处理")
        assertEquals(1, store.size)
    }

    @Test
    fun `same session overwrite keeps one entry`() {
        val store = InMemoryContextPackStore(clock = clock)
        val session = AgentSessionId("s-1")

        store.put(session, projectA, pack(projectA, "old"))
        store.put(session, projectA, pack(projectA, "new", 2L))

        assertEquals("new", store.find(session, projectA)?.pack?.packId)
        assertEquals(1, store.size)
    }

    @Test
    fun `oldest entry is evicted when capacity is reached`() {
        val store = InMemoryContextPackStore(maxEntries = 2, clock = clock)
        val first = AgentSessionId("s-1")
        val second = AgentSessionId("s-2")
        val third = AgentSessionId("s-3")

        store.put(first, projectA, pack(projectA, "ctx-1"))
        store.put(second, projectA, pack(projectA, "ctx-2"))
        store.put(third, projectA, pack(projectA, "ctx-3"))

        assertNull(store.find(first, projectA))
        assertEquals("ctx-2", store.find(second, projectA)?.pack?.packId)
        assertEquals("ctx-3", store.find(third, projectA)?.pack?.packId)
        assertEquals(2, store.size)
    }

    @Test
    fun `rejects a pack whose project does not match the key`() {
        val store = InMemoryContextPackStore(clock = clock)

        assertFailsWith<IllegalArgumentException> {
            store.put(AgentSessionId("s-1"), projectA, pack(projectB, "ctx-wrong"))
        }
        assertEquals(0, store.size)
    }
}
