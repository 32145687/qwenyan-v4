package com.qianyan.application.usecase.writing.knowledgeupdate

import com.qianyan.agent.runtime.AgentException
import com.qianyan.agent.runtime.AgentRuntime
import com.qianyan.agent.tool.ToolException
import com.qianyan.agent.tool.ToolExecutor
import com.qianyan.agent.tool.ToolRegistry
import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.application.error.ErrorMapper
import com.qianyan.model.AgentId
import com.qianyan.model.agent.AgentContract
import com.qianyan.model.knowledge.CandidateKnowledgeChange
import com.qianyan.model.writing.Draft
import com.qianyan.provider.LLMGateway
import com.qianyan.provider.ModelProfile
import com.qianyan.provider.ProviderException

/**
 * Knowledge Update Agent（P11.5）。
 *
 * 职责：把 [Draft]（最终创作产物）+ 现有记忆引用 经 P10 [AgentRuntime] → [LLMGateway] 交给 LLM，
 * 并把其输出经 [KnowledgeUpdateParser] 严格解析为 [CandidateKnowledgeChange] 列表。
 *
 * LLM 只负责**提出候选知识变化**；是否落地由确定性 [KnowledgeValidator] / [KnowledgeApplicator] 决定。
 * 依赖约束（架构硬约束）：
 *  - 只依赖 :provider:api 的 [LLMGateway] 抽象，**禁止** DeepSeek/MiMo/HTTP/API Key/Storage/SQLite/Repository；
 *  - 复用 P10 [AgentRuntime]，不重写 Agent loop；Agent 无工具（allowedTools 空），不新增 Tool；
 *  - 输出解析经 [KnowledgeUpdateParser]，非法 → 类型化错误（不经 String.contains）。
 */
class KnowledgeUpdateAgent(
    private val gateway: LLMGateway,
    private val errorMapper: ErrorMapper,
    private val model: ModelProfile = ModelProfile.MOCK,
) {

    /**
     * 从 [draft] + 已有记忆引用 提议候选知识变化（同步，无网络除非装配方注入真实 Provider）。
     * 返回候选列表；失败抛类型化 [ApplicationException]。
     */
    fun propose(draft: Draft, existingMemories: List<String>): List<CandidateKnowledgeChange> {
        return try {
            val result = runtime.run(KNOWLEDGE_UPDATE_AGENT, renderInput(draft, existingMemories))
            val raw = result.answer
                ?: throw KnowledgeUpdateException.InvalidOutput("knowledge update returned no answer")
            KnowledgeUpdateParser.parse(raw, draft.novelId, draft.variantId, draft.scope)
        } catch (e: ApplicationException) {
            throw e
        } catch (e: KnowledgeUpdateException) {
            throw errorMapper.map(e)
        } catch (e: ProviderException) {
            throw when (e) {
                is ProviderException.InvalidResponse,
                is ProviderException.MalformedOutput,
                -> ApplicationException(ApplicationError.InvalidKnowledgeUpdateOutput(e.message ?: "knowledge update output malformed"))
                else -> errorMapper.map(e)
            }
        } catch (e: AgentException) {
            throw ApplicationException(ApplicationError.KnowledgeUpdateFailed(e.message ?: "agent runtime failed"))
        } catch (e: ToolException) {
            throw ApplicationException(ApplicationError.KnowledgeUpdateFailed(e.message ?: "tool failed"))
        }
    }

    /** 构造供 LLM 使用的输入文本：正文 + 简明已有记忆引用（不把整个库塞给 LLM）。 */
    private fun renderInput(draft: Draft, existingMemories: List<String>): String = buildString {
        if (existingMemories.isNotEmpty()) {
            appendLine("【已有记忆（引用）】")
            existingMemories.take(INPUT_MEMORY_LIMIT).forEach { appendLine("- $it") }
        }
        appendLine("【创作产物 ${draft.draftId.value}】")
        if (draft.content.isNotBlank()) {
            appendLine(draft.content.take(INPUT_CONTENT_LIMIT))
        } else {
            appendLine("（空正文）")
        }
        appendLine()
        appendLine("请从上述创作产物中提取候选知识变化，按 JSON 输出：" +
            "{\"changes\":[{\"changeId\":\"...\",\"operation\":\"ADD|UPDATE|REMOVE\",\"target\":\"人物/地点/事件/规则主题\",\"category\":\"WORLD_RULE\",\"content\":\"知识内容\",\"source\":\"draft:<id>\",\"reason\":\"依据\"}]}")
        appendLine("只输出候选；是否为 canon 事实由系统裁决。")
    }.trimEnd()

    private val runtime: AgentRuntime = AgentRuntime(
        gateway = gateway,
        toolExecutor = ToolExecutor(ToolRegistry()),
        model = model,
    )

    private companion object {
        val KNOWLEDGE_UPDATE_AGENT: AgentContract = AgentContract(
            agentId = AgentId("knowledge-update"),
            name = "KnowledgeUpdateAgent",
            capabilities = listOf(),
            allowedTools = emptyList(),
        )

        /** 提取正文截断阈值。 */
        const val INPUT_CONTENT_LIMIT: Int = 4000

        /** 已有记忆引用条数上限。 */
        const val INPUT_MEMORY_LIMIT: Int = 20
    }
}