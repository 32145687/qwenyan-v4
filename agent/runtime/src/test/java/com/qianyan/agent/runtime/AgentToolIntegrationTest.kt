package com.qianyan.agent.runtime

import com.qianyan.agent.tool.ToolExecutor
import com.qianyan.agent.tool.ToolRegistry
import com.qianyan.model.AgentId
import com.qianyan.model.agent.AgentContract
import com.qianyan.model.agent.ToolName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Agent + Tool 真实执行链集成测试：
 * Agent → FakeProvider(LLM) → ToolRequest → Tool(echo) → ToolResult → FakeProvider → Final。
 */
class AgentToolIntegrationTest {

    private fun runtime(provider: FakeProvider): AgentRuntime = AgentRuntime(
        gateway = provider,
        toolExecutor = ToolExecutor(ToolRegistry().apply { register(EchoToolForAgent()) }),
        model = com.qianyan.provider.ModelProfile.MOCK,
        maxSteps = 5,
    )

    private val contract = AgentContract(
        agentId = AgentId("agent-echo"),
        name = "EchoAgent",
        allowedTools = listOf(ToolName("echo")),
    )

    @Test
    fun `agent calls tool and uses tool result observation in next LLM round then finalizes`() {
        val provider = FakeProvider(
            """{"tool":"echo","arguments":{"value":"ping"}}""",
            """{"answer":"final-after-tool"}""",
        )
        val result = runtime(provider).run(contract, "please work")

        assertTrue(result.completed)
        assertEquals("final-after-tool", result.answer)
        assertEquals(2, result.steps)
        assertEquals(1, result.toolCalls.size)
        assertEquals(ToolName("echo"), result.toolCalls.first().toolName)

        // 第二轮上下文必须包含第一轮工具执行的观察结果（"ping" 已回显进入观察文本）
        val secondRoundMessages = provider.messagesSent[1]
        val join = secondRoundMessages.joinToString("|") { it.content }
        assertTrue(join.contains("ping"), "第二轮上下文应包含 echo 执行结果，实际：$join")
        assertTrue(join.contains("ToolResult"))
    }

    @Test
    fun `agent completes in a single round when no tool needed`() {
        val provider = FakeProvider("""{"answer":"no tool"}""")
        val result = runtime(provider).run(contract, "hi")

        assertTrue(result.completed)
        assertEquals(1, result.steps)
        assertTrue(result.toolCalls.isEmpty())
        assertEquals(1, provider.messagesSent.size)
    }
}