package com.qianyan.application.usecase.writing.revision

import com.qianyan.agent.runtime.AgentRuntime
import com.qianyan.agent.tool.Tool
import com.qianyan.agent.tool.ToolContext
import com.qianyan.agent.tool.ToolExecutor
import com.qianyan.agent.tool.ToolRegistry
import com.qianyan.application.error.ErrorMapper
import com.qianyan.model.AgentId
import com.qianyan.model.NovelId
import com.qianyan.model.agent.AgentContract
import com.qianyan.model.agent.ToolName
import com.qianyan.model.spec.ValidationResult
import com.qianyan.model.tool.ToolDefinition
import com.qianyan.model.tool.ToolParameterSpec
import com.qianyan.model.tool.ToolRequest
import com.qianyan.model.tool.ToolResult
import com.qianyan.provider.ChatMessage
import com.qianyan.provider.ChatRole
import com.qianyan.provider.FinishReason
import com.qianyan.provider.LLMGateway
import com.qianyan.provider.ModelProfile
import com.qianyan.provider.ProviderRequest
import com.qianyan.provider.ProviderResponse
import com.qianyan.provider.Usage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T-O74-F（裁决 A-25）契约测试：`AgentRuntime.systemPrompt` 只在**存在可用工具**时广播 tool 协议。
 *
 *  - 空工具集（Revision 现状：`allowedTools` 空 + `ToolExecutor(ToolRegistry())` 空注册）：
 *    SYSTEM 只含标题行 + answer 协议行，不出现 tool 协议行与「可用工具」清单；
 *  - 非空工具集：tool 协议行 + 经 `allowedTools` 过滤后的工具清单保持原样。
 *
 * 脚本化 Gateway，无网络、无真实 LLM。
 */
class RevisionNoToolsProtocolTest {

    /** 记录每次请求（快照 messages，AgentRuntime 复用同一个 MutableList）并返回固定正文的脚本化 LLM。 */
    private class RecordingGateway(private val reply: String = """{"answer":"ok"}""") : LLMGateway {
        val requests = mutableListOf<ProviderRequest>()

        override fun chat(request: ProviderRequest): ProviderResponse {
            requests += request.copy(messages = request.messages.toList())
            return ProviderResponse(
                message = ChatMessage(ChatRole.ASSISTANT, reply),
                usage = Usage(1, 1, 2),
                finishReason = FinishReason.STOP,
            )
        }

        fun systemPrompt(index: Int = 0): String =
            requests[index].messages.first { it.role == ChatRole.SYSTEM }.content
    }

    /** 具名工具（仅用于系统提示渲染与白名单过滤取证，不关心执行）。 */
    private class NamedTool(name: String, description: String, parameter: String? = null) : Tool {
        override val definition = ToolDefinition(
            toolName = ToolName(name),
            description = description,
            parameters = if (parameter == null) emptyList() else listOf(ToolParameterSpec(parameter)),
        )

        override fun execute(request: ToolRequest, context: ToolContext): ToolResult =
            ToolResult(toolName = definition.toolName, success = true)
    }

    private fun reviseOnce(gateway: LLMGateway): String =
        RevisionAgent(gateway, ErrorMapper, ModelProfile.MOCK).reviseContent(
            workingRef = "wd-o74",
            content = "女主推开古碑。",
            critique = ValidationResult(passed = false),
            novelId = NovelId("novel-o74"),
        )

    @Test
    fun `empty toolset omits tool protocol and answers in one step`() {
        val gateway = RecordingGateway("女主推开古碑，雷劫降临。")

        assertEquals("女主推开古碑，雷劫降临。", reviseOnce(gateway))
        // 不请求工具时一步收敛（不再出现"请求工具 → ToolNotFound → 再请求"的 10 步耗尽）
        assertEquals(1, gateway.requests.size)

        val system = gateway.systemPrompt()
        println("=== empty-toolset SYSTEM (begin) ===\n$system\n=== empty-toolset SYSTEM (end) ===")
        // 空工具集：不广播 tool 协议（A-25 唯一生产改动点）
        assertFalse(system.contains("需要调用工具时返回"), "空工具集不得广播 tool 协议：$system")
        assertFalse(system.contains("\"tool\":\"<名称>\""), "空工具集不得广播 tool 协议：$system")
        // 空工具集：不出现工具清单
        assertFalse(system.contains("可用工具"), "空工具集不得声明任何工具：$system")
        // answer 协议与 Agent 身份仍保留
        assertTrue(system.contains("StoryRevisionAgent"), "标题行必须保留：$system")
        assertTrue(system.contains("\"answer\":\"<正文>\""), "answer 协议必须保留：$system")
    }

    @Test
    fun `toolset with tools keeps tool protocol and lists allowed tools`() {
        val gateway = RecordingGateway()
        val registry = ToolRegistry().apply {
            register(NamedTool("echo", "回显工具", parameter = "text"))
            register(NamedTool("secret", "未授权工具"))
        }
        val runtime = AgentRuntime(
            gateway = gateway,
            toolExecutor = ToolExecutor(registry),
            model = ModelProfile.MOCK,
        )
        val contract = AgentContract(
            agentId = AgentId("agent-o74"),
            name = "ToolAgent",
            allowedTools = listOf(ToolName("echo")),
        )

        runtime.run(contract, "hi")

        val withTools = gateway.systemPrompt()
        println("=== with-tools SYSTEM (begin) ===\n$withTools\n=== with-tools SYSTEM (end) ===")
        // 非空工具集：tool 协议行与清单保持原样
        assertTrue(withTools.contains("需要调用工具时返回"), "有工具时必须广播 tool 协议：$withTools")
        assertTrue(withTools.contains("可用工具："), "有工具时必须列出清单：$withTools")
        assertTrue(withTools.contains("echo: 回显工具（参数: text）"), "必须列出授权工具及其参数：$withTools")
        // allowedTools 过滤：未授权工具不出现在提示中
        assertFalse(withTools.contains("secret"), "未授权工具不得出现在提示中：$withTools")

        // 同一 Runtime 换成空注册表：tools 为空 ⇒ 无 tool 协议行、无清单
        val emptyRuntime = AgentRuntime(
            gateway = gateway,
            toolExecutor = ToolExecutor(ToolRegistry()),
            model = ModelProfile.MOCK,
        )
        emptyRuntime.run(contract, "hi")

        val emptyTools = gateway.systemPrompt(index = 1)
        assertFalse(emptyTools.contains("需要调用工具时返回"), "空注册表不得广播 tool 协议：$emptyTools")
        assertFalse(emptyTools.contains("可用工具"), "空注册表不得列出工具清单：$emptyTools")
        assertTrue(emptyTools.contains("\"answer\":\"<正文>\""), "answer 协议必须保留：$emptyTools")
    }
}
