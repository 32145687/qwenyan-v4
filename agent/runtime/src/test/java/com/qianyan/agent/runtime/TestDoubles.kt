package com.qianyan.agent.runtime

import com.qianyan.agent.tool.Tool
import com.qianyan.agent.tool.ToolContext
import com.qianyan.model.agent.ToolName
import com.qianyan.model.tool.ToolDefinition
import com.qianyan.model.tool.ToolParameterSpec
import com.qianyan.model.tool.ToolRequest
import com.qianyan.model.tool.ToolResult
import com.qianyan.provider.ChatMessage
import com.qianyan.provider.ChatRole
import com.qianyan.provider.LLMGateway
import com.qianyan.provider.ModelProfile
import com.qianyan.provider.ProviderRequest
import com.qianyan.provider.ProviderResponse
import com.qianyan.provider.Usage
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 脚本化假 LLM（实现 [LLMGateway] 契约，即"Fake Provider"）。
 * 按 [queue] 顺序逐条返回正文；超出队列抛 [NoSuchElementException] 以暴露多余调用。
 * 记录每一次收到的完整消息列表，供测试断言 ToolResult 观察是否已注入上下文。
 */
class FakeProvider(vararg replies: String) : LLMGateway {
    private val queue: MutableList<String> = replies.toMutableList()
    private val _messages: MutableList<List<ChatMessage>> = mutableListOf()
    private val _models: MutableList<ModelProfile> = mutableListOf()

    val messagesSent: List<List<ChatMessage>> get() = _messages.toList()
    val requestedModels: List<ModelProfile> get() = _models.toList()

    override fun chat(request: ProviderRequest): ProviderResponse {
        _models.add(request.model)
        _messages.add(request.messages.toList())
        val content = queue.removeAt(0)
        return ProviderResponse(
            message = ChatMessage(ChatRole.ASSISTANT, content),
            usage = Usage(promptTokens = 10, completionTokens = 5, totalTokens = 15),
        )
    }
}

/** 简单回显工具，供 Agent + Tool 集成测试使用。 */
class EchoToolForAgent : Tool {
    override val definition = ToolDefinition(
        toolName = ToolName("echo"),
        description = "回显输入 value",
        parameters = listOf(ToolParameterSpec("value", required = true)),
    )

    override fun execute(request: ToolRequest, context: ToolContext): ToolResult {
        val value = request.arguments["value"]?.let { (it as JsonPrimitive).content } ?: ""
        return ToolResult(
            toolName = definition.toolName,
            success = true,
            output = buildJsonObject { put("value", value) },
        )
    }
}