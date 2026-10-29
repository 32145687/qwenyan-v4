package com.qianyan.application.usecase.writing.critique

import com.qianyan.agent.runtime.AgentException
import com.qianyan.agent.runtime.AgentRuntime
import com.qianyan.agent.tool.ToolException
import com.qianyan.agent.tool.ToolExecutor
import com.qianyan.agent.tool.ToolRegistry
import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.application.error.ErrorMapper
import com.qianyan.application.usecase.writing.planning.VocabularyLite
import com.qianyan.model.AgentId
import com.qianyan.model.agent.AgentContract
import com.qianyan.model.spec.IssueSeverity
import com.qianyan.model.spec.ValidationIssue
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

    /** 实际执行 Agent 身份（供 Skill.bindings 的生产一致性校验）。 */
    val agentId: AgentId get() = CRITIC_AGENT.agentId

    /**
     * 对 [Draft] 执行一次 Critique（同步，无网络除非装配方注入真实 Provider）。
     * 返回 [ValidationResult]；失败抛类型化 [ApplicationException]。
     */
    fun critique(draft: Draft, vocabulary: List<VocabularyLite> = emptyList()): ValidationResult =
        runCritique(renderInput(draft, vocabulary), draft.content, vocabulary)

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
        vocabulary: List<VocabularyLite> = emptyList(),
    ): ValidationResult = runCritique(renderInput(content, planId, sourceModel, vocabulary), content, vocabulary)

    private fun runCritique(input: String, content: String, vocabulary: List<VocabularyLite>): ValidationResult {
        return try {
            val result = runtime.run(CRITIC_AGENT, input)
            val raw = result.answer
                ?: throw CritiqueException.InvalidOutput("critic returned no answer")
            val parsed = CritiqueParser.parse(raw)
            val warnings = aliasWarnings(content, vocabulary)
            if (warnings.isEmpty()) parsed else parsed.copy(issues = parsed.issues + warnings)
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

    /** D-38：别名只产生 WARNING；不改变 passed、不生成 ERROR、不修改正文。 */
    private fun aliasWarnings(content: String, vocabulary: List<VocabularyLite>): List<ValidationIssue> {
        if (vocabulary.isEmpty()) return emptyList()
        val seen = mutableSetOf<String>()
        val warnings = mutableListOf<ValidationIssue>()
        for (entry in vocabulary) {
            for (alias in entry.aliases) {
                if (alias.isBlank() || alias == entry.canonical) continue
                val key = entry.canonical + "\u0000" + alias
                if (!seen.add(key)) continue
                // 若 alias 本身是 canonical 的子串（如「石」⊂「灵石」），先剔除规范词本身，
                // 避免"只用了 canonical"也被误报；规范词之外的别名仍会命中。
                val scanContent = if (alias in entry.canonical) content.replace(entry.canonical, "") else content
                if (!scanContent.contains(alias)) continue
                warnings += ValidationIssue(
                    field = "词库别名",
                    severity = IssueSeverity.WARNING,
                    message = "建议使用规范词「${entry.canonical}」（别名「$alias」）",
                )
                if (warnings.size >= ALIAS_WARNING_LIMIT) return warnings
            }
        }
        return warnings
    }

    /** 构造供 Critic LLM 使用的输入文本（正文 + 关联信息）。 */
    private fun renderInput(draft: Draft, vocabulary: List<VocabularyLite>): String =
        renderInput(draft.content, draft.planId, draft.sourceModel, vocabulary)

    /** 构造供 Critic LLM 使用的输入文本（正文 + 关联信息）；Draft 与 WorkingDraft 共用同一模板。 */
    private fun renderInput(
        content: String,
        planId: com.qianyan.model.ChapterPlanId?,
        sourceModel: String,
        vocabulary: List<VocabularyLite>,
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
        if (vocabulary.isNotEmpty()) {
            appendLine()
            appendVocabularyPrompt(vocabulary)
        }
        appendLine()
        appendLine("请按 JSON 输出评审结果：" +
            "{\"passed\":<true|false>,\"issues\":[{\"field\":\"...\",\"severity\":\"ERROR|WARNING|INFO\",\"message\":\"...\"}]}")
    }.trimEnd()

    private fun StringBuilder.appendVocabularyPrompt(vocabulary: List<VocabularyLite>) {
        appendLine("【词库规范】")
        vocabulary.take(VOCABULARY_PROMPT_LIMIT).forEach { entry ->
            val aliases = entry.aliases.filter { it.isNotBlank() && it != entry.canonical }
            val aliasText = if (aliases.isEmpty()) "" else "（别名：${aliases.joinToString("、")}）"
            val replacementText = entry.replacement?.takeIf { it.isNotBlank() }?.let { " -> $it" }.orEmpty()
            appendLine("- ${entry.canonical}$aliasText$replacementText")
        }
    }

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

        /** D-38：词库 prompt 最多 50 条；别名 WARNING 最多 20 条。 */
        const val VOCABULARY_PROMPT_LIMIT: Int = 50
        const val ALIAS_WARNING_LIMIT: Int = 20
    }
}