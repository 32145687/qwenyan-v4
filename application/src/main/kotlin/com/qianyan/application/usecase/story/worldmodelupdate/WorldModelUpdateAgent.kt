package com.qianyan.application.usecase.story.worldmodelupdate

import com.qianyan.provider.LLMGateway
import com.qianyan.provider.ModelProfile
import com.qianyan.provider.ProviderException
import com.qianyan.agent.runtime.AgentException
import com.qianyan.agent.runtime.AgentRuntime
import com.qianyan.agent.tool.ToolException
import com.qianyan.agent.tool.ToolExecutor
import com.qianyan.agent.tool.ToolRegistry
import com.qianyan.model.AgentId
import com.qianyan.model.agent.AgentContract

/**
 * World Model Update Agent（Phase 3 · v3.1 §12.5「Extract（规则 + Index + AI）」的 AI 腿）。
 *
 * 镜像既有 `KnowledgeUpdateAgent` 范式：把 Commit 的前后正文 + 现有事实引用（Index 腿，确定性）
 * 经 P10 [AgentRuntime] → [LLMGateway] 交给 LLM，输出经 [WorldModelUpdateParser] 严格解析为
 * [ExtractedFact] 列表。LLM 只**提出候选**；是否落地由确定性 [WorldModelFactValidator] 与既有
 * World Model 索引决定，**绝不直接入库**。
 *
 * 依赖约束（架构硬约束，与 KnowledgeUpdateAgent 一致）：
 *  - 只依赖 :provider:api 的 [LLMGateway] 抽象，禁止 DeepSeek/MiMo/HTTP/API Key/Storage/Repository；
 *  - 复用 P10 [AgentRuntime]，不重写 Agent loop；Agent 无工具（allowedTools 空），不新增 Tool。
 */
class WorldModelUpdateAgent(
    private val gateway: LLMGateway,
    private val model: ModelProfile = ModelProfile.MOCK,
) {

    /**
     * 从 Commit 前后正文 + 现有事实引用提议候选事实（同步）。失败抛类型化 [WorldModelUpdateException]。
     */
    fun propose(context: WorldModelUpdateContext, existingEventNames: List<String>): List<ExtractedFact> {
        return try {
            val result = runtime.run(WORLD_MODEL_UPDATE_AGENT, renderInput(context, existingEventNames))
            val raw = result.answer
                ?: throw WorldModelUpdateException.InvalidOutput("world model update returned no answer")
            WorldModelUpdateParser.parse(raw)
        } catch (e: WorldModelUpdateException) {
            throw e
        } catch (e: ProviderException) {
            throw WorldModelUpdateException.Failed(e.message ?: "provider failed")
        } catch (e: AgentException) {
            throw WorldModelUpdateException.Failed(e.message ?: "agent runtime failed")
        } catch (e: ToolException) {
            throw WorldModelUpdateException.Failed(e.message ?: "tool failed")
        }
    }

    /** 构造 LLM 输入：Commit 前后正文 + 现有事实身份引用（Index；不把整个世界模型塞给 LLM）。 */
    private fun renderInput(context: WorldModelUpdateContext, existingEventNames: List<String>): String =
        buildString {
            appendLine("【提交前正文（基线）】")
            appendLine(context.previousContent.take(INPUT_CONTENT_LIMIT).ifBlank { "（空）" })
            appendLine()
            appendLine("【提交后正文（新正典）】")
            appendLine(context.resultingContent.take(INPUT_CONTENT_LIMIT).ifBlank { "（空）" })
            if (existingEventNames.isNotEmpty()) {
                appendLine()
                appendLine("【已有事件（身份引用；同名即冲突，不得当作新增）】")
                existingEventNames.take(INPUT_FACTS_LIMIT).forEach { appendLine("- $it") }
            }
            appendLine()
            appendLine("请从上述正文变化中提取候选世界事实（人物状态 / 事件等），按 JSON 输出：")
            appendLine(
                "{\"facts\":[{\"factId\":\"...\",\"kind\":\"EVENT\",\"operation\":\"ADD|UPDATE|REMOVE\"," +
                    "\"name\":\"事实身份名\",\"summary\":\"事实内容摘要\",\"excerpt\":\"来源正文片段（必须逐字出现于提交后正文）\"," +
                    "\"reason\":\"依据\"}]}",
            )
            appendLine("只输出候选；是否可写入 / 是否冲突由系统裁决。excerpt 必须是提交后正文的逐字子串。")
        }.trimEnd()

    private val runtime: AgentRuntime = AgentRuntime(
        gateway = gateway,
        toolExecutor = ToolExecutor(ToolRegistry()),
        model = model,
    )

    private companion object {
        val WORLD_MODEL_UPDATE_AGENT: AgentContract = AgentContract(
            agentId = AgentId("world-model-update"),
            name = "WorldModelUpdateAgent",
            capabilities = listOf(),
            allowedTools = emptyList(),
        )

        /** 提取正文截断阈值（每侧）。 */
        const val INPUT_CONTENT_LIMIT: Int = 4000

        /** 已有事实身份引用条数上限。 */
        const val INPUT_FACTS_LIMIT: Int = 50
    }
}
