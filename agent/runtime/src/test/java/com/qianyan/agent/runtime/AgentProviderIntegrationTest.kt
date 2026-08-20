package com.qianyan.agent.runtime

import com.qianyan.agent.tool.ToolExecutor
import com.qianyan.agent.tool.ToolRegistry
import com.qianyan.model.AgentId
import com.qianyan.model.agent.AgentContract
import com.qianyan.model.agent.AgentState
import com.qianyan.provider.ModelProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Agent + Provider 集成测试。
 * 验证 AgentRuntime 只依赖 [com.qianyan.provider.LLMGateway] 抽象（Fake 实现），
 * 不发真实 DeepSeek / MiMo 网络请求，且正确传递模型标识与消息上下文。
 */
class AgentProviderIntegrationTest {

    private val contract = AgentContract(agentId = AgentId("agent-prov"), name = "ProviderAgent")

    private fun runtime(provider: FakeProvider, model: ModelProfile = ModelProfile.MOCK): AgentRuntime =
        AgentRuntime(
            gateway = provider,
            toolExecutor = ToolExecutor(ToolRegistry()),
            model = model,
            maxSteps = 3,
        )

    @Test
    fun `agent runs through LLMGateway abstraction and returns completed result`() {
        val provider = FakeProvider("""{"answer":"from provider"}""")
        val result = runtime(provider).run(contract, "hello")

        assertTrue(result.completed)
        assertEquals(AgentState.COMPLETED, result.state)
        assertEquals("from provider", result.answer)
        assertEquals(1, provider.messagesSent.size)
    }

    @Test
    fun `agent passes expected model profile and seed messages to provider`() {
        val provider = FakeProvider("""{"answer":"ok"}""")
        val custom = ModelProfile("test-model")
        runtime(provider, custom).run(contract, "hi")

        assertEquals(listOf(custom), provider.requestedModels)

        val firstRound = provider.messagesSent.first()
        // 首轮上下文 = SYSTEM 提示 + USER 输入
        assertEquals(2, firstRound.size)
        assertEquals("hi", firstRound.last().content)
    }
}