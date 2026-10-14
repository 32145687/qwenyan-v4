package com.qianyan.application.usecase.writing.critique

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
import com.qianyan.model.spec.ValidationResult
import com.qianyan.model.writing.Draft
import com.qianyan.provider.LLMGateway
import com.qianyan.provider.ModelProfile
import com.qianyan.provider.ProviderException

/**
 * Critic Agent（P11.4）。
 *
 * 职责：把 [Draft] 的正文经 P10 的 [AgentRuntime] → [LLMGateway] 交给 Critic LLM，
 * 并把其最终输出解析为 [ValidationResult]（复用 core:model.spec）。
 *
 * 依赖约束（架构硬约束）：
 *  - 只依赖 :provider:api 的 [LLMGateway] 抽象，**禁止** DeepSeek/MiMo/HTTP/API Key/Storage/SQLite/Repository；
 *  - 复用 P10 [AgentRuntime]，不重写 Agent loop；Critic 无工具（allowedTools 空），
 *    [ToolExecutor] 用空注册（不新增 Tool）；
 *  - 输出解析经 [CritiqueParser]，非法/空/缺字段/类型错误 → 类型化错误（不经 String.contains）；
 *  - 默认使用 Mock 模型（[ModelProfile.MOCK]），保持测试确定性。
 */
class CritiqueAgent(
    private val gateway: LLMGateway,
    private val errorMapper: ErrorMapper,
    private val model: ModelProfile = ModelProfile.MOCK,
) {

    /**
     * 对 [Draft] 执行一次 Critique（同步，无网络除非装配方注入真实 Provider）。
     * 返回 [ValidationResult]；失败抛类型化 [ApplicationException]。
     */
    fun critique(draft: Draft): ValidationResult = runCritique(renderInput(draft))

    /**
     * **P1-02c+d**：对 **AI 工作产物正文**（WorkingDraft 内容，无章节 Draft 行）执行一次 Critique。
     *
     * 与 [critique] 行为完全一致（同一 Agent / Parser / 输入模板 / 错误映射），只是输入来源改为
     * 「正文 + 可选计划引用」，避免为 WorkingDraft 伪造 DraftId 或让它冒充 Draft。
     */
    fun critiqueContent(
        content: String,
        planId: com.qianyan.model.ChapterPlanId? = null,
        sourceModel: String = "",
    ): ValidationResult = runCritique(renderInput(content, planId, sourceModel))

    private fun runCritique(input: String): ValidationResult {
        return try {
            val result = runtime.run(CRITIC_AGENT, input)
            val raw = result.answer
                ?: throw CritiqueException.InvalidOutput("critic returned no answer")
            CritiqueParser.parse(raw)
        } catch (e: ApplicationException) {
            throw e
        } catch (e: CritiqueException) {
            throw errorMapper.map(e)
        } catch (e: ProviderException) {
            throw when (e) {
                is ProviderException.InvalidResponse,
                is ProviderException.MalformedOutput,
                -> ApplicationException(ApplicationError.InvalidCritiqueOutput(e.message ?: "critique output malformed"))
                else -> errorMapper.map(e)
            }
        } catch (e: AgentException) {
            throw ApplicationException(ApplicationError.CritiqueFailed(e.message ?: "agent runtime failed"))
        } catch (e: ToolException) {
            throw ApplicationException(ApplicationError.CritiqueFailed(e.message ?: "tool failed"))
        }
    }

    /** 构造供 Critic LLM 使用的输入文本（正文 + 关联信息）。 */
    private fun renderInput(draft: Draft): String =
        renderInput(draft.content, draft.planId, draft.sourceModel)

    /** 构造供 Critic LLM 使用的输入文本（正文 + 关联信息）；Draft 与 WorkingDraft 共用同一模板。 */
    private fun renderInput(
        content: String,
        planId: com.qianyan.model.ChapterPlanId?,
        sourceModel: String,
    ): String = buildString {
        appendLine("【草稿正文】")
        if (content.isNotBlank()) {
            // 正文可能很长，评审重点是结构性与方向性意见，截取前段足以覆盖评审意图。
            appendLine(content.take(CRITIC_INPUT_CONTENT_LIMIT))
        } else {
            appendLine("（空正文）")
        }
        if (planId != null) appendLine("chapterPlanId: ${planId.value}")
        if (sourceModel.isNotBlank()) appendLine("sourceModel: $sourceModel")
        appendLine()
        appendLine("请按 JSON 输出评审结果：" +
            "{\"passed\":<true|false>,\"issues\":[{\"field\":\"...\",\"severity\":\"ERROR|WARNING|INFO\",\"message\":\"...\"}]}")
    }.trimEnd()

    private val runtime: AgentRuntime = AgentRuntime(
        gateway = gateway,
        toolExecutor = ToolExecutor(ToolRegistry()),
        model = model,
    )

    private companion object {
        val CRITIC_AGENT: AgentContract = AgentContract(
            agentId = AgentId("story-critic"),
            name = "StoryCriticAgent",
            capabilities = listOf(),
            allowedTools = emptyList(),
        )

        /** 评审输入正文截断阈值（评审聚焦方向/结构，不逐字审读）。 */
        const val CRITIC_INPUT_CONTENT_LIMIT: Int = 2000
    }
}