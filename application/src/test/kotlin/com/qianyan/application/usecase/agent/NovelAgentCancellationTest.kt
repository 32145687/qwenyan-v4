package com.qianyan.application.usecase.agent

import com.qianyan.model.novelagent.NovelAgentOutcome
import com.qianyan.model.session.AgentSessionStatus
import com.qianyan.provider.ChatRole
import com.qianyan.provider.impl.MockLLMGateway
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * DSH-06 · 协作式取消（NovelAgent 相位边界检查点）。
 *
 * 事件源为 Mock 网关（**契约级测试**，无真实网络）；断言：相位边界可中止、终态走既有
 * CANCELLED 语义（不新造状态机）、零残留候选、未取消路径行为零变化。
 */
class NovelAgentCancellationTest {

    /** 计数网关：可注入「Writer（EXECUTE 相位）调用时取消令牌」的副作用。 */
    private fun countingGateway(calls: AtomicInteger, onCancelWriter: () -> Unit = {}): MockLLMGateway {
        val base = routingGateway()
        return MockLLMGateway { req ->
            calls.incrementAndGet()
            val system = req.messages.first { it.role == ChatRole.SYSTEM }.content
            if ("StoryWriterAgent" in system) onCancelWriter()
            base.chat(req)
        }
    }

    @Test
    fun `pre-cancelled token aborts at first boundary with no model calls and no residuals`() {
        val calls = AtomicInteger()
        val f = novelAgentFixture(countingGateway(calls))
        val w = seedNovelWorld(f)
        val token = AgentRunCancellation().also { it.cancel() }

        val result = f.app.novelAgent.run(request(w, "继续写第一章"), token)

        assertEquals(NovelAgentOutcome.CANCELLED, result.outcome, "首个相位边界即中止")
        assertTrue("已由作者中止" in result.summary, "摘要必须含取消文案")
        assertEquals(0, calls.get(), "不调用任何模型（首个检查点即停）")
        assertEquals(
            AgentSessionStatus.CANCELLED,
            f.app.agentSessions.sessionOf(result.sessionId!!).status,
            "会话走既有 CANCELLED（不新造状态机）",
        )
        assertNull(result.workingDraftId, "取消零残留：无 WorkingDraft")
        assertNull(result.artifactId, "取消零残留：无 ChangeArtifact")
        assertEquals(1L, countRows(f.handle, "ChapterDraft"), "正典草稿表无新增（取消不写数据）")
        f.close()
    }

    @Test
    fun `cancel during execute stops at next phase boundary`() {
        val calls = AtomicInteger()
        val token = AgentRunCancellation()
        val f = novelAgentFixture(countingGateway(calls) { token.cancel() })
        val w = seedNovelWorld(f)

        val result = f.app.novelAgent.run(request(w, "继续写第一章"), token)

        assertEquals(NovelAgentOutcome.CANCELLED, result.outcome, "EXECUTE 中取消 → 下一相位边界（REVIEW）中止")
        assertTrue(calls.get() >= 1, "EXECUTE 已发生模型调用（边界前）")
        assertEquals(
            AgentSessionStatus.CANCELLED,
            f.app.agentSessions.sessionOf(result.sessionId!!).status,
        )
        assertNull(result.workingDraftId, "REVIEW 未运行 → 零候选（WorkingDraft 收口不发生）")
        assertNull(result.artifactId, "REVIEW 未运行 → 零 ChangeArtifact")
        f.close()
    }

    @Test
    fun `cancel is idempotent`() {
        val token = AgentRunCancellation()
        assertTrue(token.cancel(), "首次取消生效")
        assertTrue(token.isCancelled())
        assertFalse(token.cancel(), "重复取消幂等（返回 false，不抛错）")
        assertTrue(token.isCancelled())
    }

    @Test
    fun `null token keeps existing behavior`() {
        val f = novelAgentFixture(routingGateway())
        val w = seedNovelWorld(f)

        val result = f.app.novelAgent.run(request(w, "继续写第一章"))

        assertEquals(NovelAgentOutcome.WAITING_HUMAN, result.outcome, "无令牌 = 既有行为零变化")
        assertNotEquals(AgentSessionStatus.CANCELLED, f.app.agentSessions.sessionOf(result.sessionId!!).status)
        assertTrue(result.artifactId != null, "正常路径照常产生候选（内存态）")
        f.close()
    }
}
