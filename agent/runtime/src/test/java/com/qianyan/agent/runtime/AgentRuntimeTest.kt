package com.qianyan.agent.runtime

import com.qianyan.agent.tool.ToolExecutor
import com.qianyan.agent.tool.ToolRegistry
import com.qianyan.model.AgentId
import com.qianyan.model.agent.AgentContract
import com.qianyan.model.agent.AgentState
import com.qianyan.model.agent.ToolName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AgentRuntimeTest {

    private fun contract(allowedTools: List<ToolName> = emptyList()) = AgentContract(
        agentId = AgentId("agent-1"),
        name = "TestAgent",
        allowedTools = allowedTools,
    )

    private fun runtime(provider: FakeProvider, maxSteps: Int = 10): AgentRuntime =
        AgentRuntime(
            gateway = provider,
            toolExecutor = ToolExecutor(ToolRegistry().apply { register(EchoToolForAgent()) }),
            model = com.qianyan.provider.ModelProfile.MOCK,
            maxSteps = maxSteps,
        )

    @Test
    fun `agent calls LLM once and returns final answer`() {
        val provider = FakeProvider("""{"answer":"hello"}""")
        val result = runtime(provider).run(contract(), "hi")

        assertTrue(result.completed)
        assertEquals(AgentState.COMPLETED, result.state)
        assertEquals("hello", result.answer)
        assertEquals(1, result.steps)
        assertTrue(result.toolCalls.isEmpty())
        // Provider 仅被调用一次
        assertEquals(1, provider.messagesSent.size)
    }

    @Test
    fun `plain text output is treated as final answer`() {
        val provider = FakeProvider("直接给出答案")
        val result = runtime(provider).run(contract(), "hi")

        assertTrue(result.completed)
        assertEquals("直接给出答案", result.answer)
    }

    @Test
    fun `empty output is treated as final answer fallback`() {
        val provider = FakeProvider("")
        val result = runtime(provider).run(contract(), "hi")

        assertTrue(result.completed)
        assertEquals("", result.answer)
    }

    @Test
    fun `agent exceeds maxSteps throws MaxStepsExceeded`() {
        // 恒返回工具调用 → 永不 Final；maxSteps=3 后必须失败
        val provider = FakeProvider(
            """{"tool":"echo","arguments":{"value":"a"}}""",
            """{"tool":"echo","arguments":{"value":"b"}}""",
            """{"tool":"echo","arguments":{"value":"c"}}""",
        )
        val ex = assertFailsWith<AgentException.MaxStepsExceeded> {
            runtime(provider, maxSteps = 3).run(contract(allowedTools = listOf(ToolName("echo"))), "hi")
        }
        assertEquals(3, ex.maxSteps)
    }

    @Test
    fun `agent records tool calls in result after completing`() {
        val provider = FakeProvider(
            """{"tool":"echo","arguments":{"value":"ping"}}""",
            """{"answer":"done"}""",
        )
        val result = runtime(provider).run(contract(allowedTools = listOf(ToolName("echo"))), "hi")

        assertTrue(result.completed)
        assertEquals("done", result.answer)
        assertEquals(2, result.steps)
        assertEquals(1, result.toolCalls.size)
        assertEquals(ToolName("echo"), result.toolCalls.first().toolName)
    }

    @Test
    fun `agent context tracks agent id and steps`() {
        val provider = FakeProvider("""{"answer":"ok"}""")
        val result = runtime(provider).run(contract(), "hi")

        assertEquals("agent-1", result.agentId.value)
        assertTrue(result.toolCalls.isEmpty())
        assertTrue(result.completed)
    }
}