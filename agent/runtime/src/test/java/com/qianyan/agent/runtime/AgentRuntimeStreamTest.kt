package com.qianyan.agent.runtime

import com.qianyan.agent.tool.ToolExecutor
import com.qianyan.agent.tool.ToolRegistry
import com.qianyan.model.AgentId
import com.qianyan.model.agent.AgentContract
import com.qianyan.model.agent.AgentState
import com.qianyan.provider.ChatMessage
import com.qianyan.provider.ChatRole
import com.qianyan.provider.LLMGateway
import com.qianyan.provider.ProviderException
import com.qianyan.provider.ProviderRequest
import com.qianyan.provider.ProviderResponse
import com.qianyan.provider.Usage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * DSH-07 · 流式输出运行时测试（假流契约测试）。
 *
 * 覆盖：顺序增量拼接等价全文 / 中途断流异常透传且已收增量不丢 /
 * 非流式网关默认单块路径行为不变 / 取消（DSH-06）与流式共存。
 */
class AgentRuntimeStreamTest {

    /** 全流式假网关：按给定增量顺序拉取；**chat 必须不被调用**（证明走的是流式通道）。 */
    private class StreamingProvider(
        private val deltas: List<String>,
        private val failAfter: Int = Int.MAX_VALUE,
    ) : LLMGateway {
        override fun chat(request: ProviderRequest): ProviderResponse =
            throw IllegalStateException("chat 不应被调用（应走 stream 通道）")

        override fun stream(request: ProviderRequest): Sequence<String> = sequence {
            deltas.forEachIndexed { index, delta ->
                if (index >= failAfter) throw ProviderException.InvalidResponse("断流")
                yield(delta)
            }
        }
    }

    /** 非流式假网关（FakeProvider 同构）：不覆盖 stream → 走默认单块。 */
    private class ChatOnlyProvider(private val content: String) : LLMGateway {
        var chatCalls = 0
        override fun chat(request: ProviderRequest): ProviderResponse {
            chatCalls++
            return ProviderResponse(
                message = ChatMessage(ChatRole.ASSISTANT, content),
                usage = Usage(10, 5, 15),
            )
        }
    }

    private fun runtime(provider: LLMGateway): AgentRuntime = AgentRuntime(
        gateway = provider,
        toolExecutor = ToolExecutor(ToolRegistry().apply { register(EchoToolForAgent()) }),
        model = com.qianyan.provider.ModelProfile.MOCK,
    )

    private fun contract() = AgentContract(
        agentId = AgentId("agent-stream"),
        name = "StreamAgent",
        allowedTools = emptyList(),
    )

    @Test
    fun `fake stream deltas render in order and concatenate to the full answer`() {
        // 提示词示例：假流 ["雨","夜","。"] → 拼接「雨夜。」（协议包裹为 JSON 片段）
        val deltas = listOf("""{"answer":"雨""", """夜。""", """"}""")
        val received = mutableListOf<String>()
        val provider = StreamingProvider(deltas)

        val result = runtime(provider).run(contract(), "hi", onDelta = { received.add(it) })

        assertEquals(deltas, received, "增量必须按序原样回调（顺序正确）")
        assertEquals("雨夜。", result.answer, "拼接结果等价全文")
        assertEquals(AgentState.COMPLETED, result.state)
    }

    @Test
    fun `mid stream failure keeps received deltas and goes through the failure channel`() {
        val received = mutableListOf<String>()
        val provider = StreamingProvider(deltas = listOf("""{"answer":"雨""", """夜。"""), failAfter = 1)

        val error = assertFailsWith<ProviderException.InvalidResponse> {
            runtime(provider).run(contract(), "hi", onDelta = { received.add(it) })
        }

        assertTrue("断流" in error.message!!, "断流异常透传（既有失败通道）")
        assertEquals(listOf("""{"answer":"雨"""), received, "已收增量保留（不回滚）")
        // 无候选：异常透传（既有失败通道），runtime 不产出任何 answer
        assertTrue(true)
    }

    @Test
    fun `non streaming gateway falls back to single block default stream`() {
        val received = mutableListOf<String>()
        val provider = ChatOnlyProvider("""{"answer":"hello"}""")

        val result = runtime(provider).run(contract(), "hi", onDelta = { received.add(it) })

        assertEquals(listOf("""{"answer":"hello"}"""), received, "非流式网关 = 单个增量（不伪造分块）")
        assertEquals("hello", result.answer, "单块路径行为不变")
        assertEquals(1, provider.chatCalls, "默认实现恰经 chat 一次")
    }

    @Test
    fun `cancel side effect inside onDelta coexists with streaming`() {
        val received = mutableListOf<String>()
        val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
        val deltas = listOf("""{"answer":"雨""", """夜。""", """"}""")
        val provider = StreamingProvider(deltas)

        val result = runtime(provider).run(
            contract(),
            "hi",
            onDelta = { delta ->
                received.add(delta)
                cancelled.set(true) // 首增量后置位取消（DSH-06 令牌语义）——流式不被打断，边界处收敛
            },
        )

        assertEquals(deltas, received, "取消与流式共存：运行时不受影响，增量全部送达")
        assertEquals("雨夜。", result.answer)
        assertTrue(cancelled.get(), "取消已置位（由编排层在下一相位边界收敛）")
    }
}
