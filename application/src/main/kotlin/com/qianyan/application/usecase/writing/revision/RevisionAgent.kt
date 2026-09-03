package com.qianyan.application.usecase.writing.revision

import com.qianyan.agent.runtime.AgentException
import com.qianyan.agent.runtime.AgentRuntime
import com.qianyan.agent.tool.ToolException
import com.qianyan.agent.tool.ToolExecutor
import com.qianyan.agent.tool.ToolRegistry
import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.application.error.ErrorMapper
import com.qianyan.application.usecase.writing.DraftParser
import com.qianyan.application.usecase.writing.DraftStructure
import com.qianyan.application.usecase.writing.WritingException
import com.qianyan.model.AgentId
import com.qianyan.model.agent.AgentContract
import com.qianyan.model.spec.ValidationResult
import com.qianyan.model.writing.Draft
import com.qianyan.model.writing.DraftStatus
import com.qianyan.provider.LLMGateway
import com.qianyan.provider.ModelProfile
import com.qianyan.provider.ProviderException
import kotlinx.datetime.Clock

/**
 * Revision Agent（P11.4）。
 *
 * 职责：把 [Draft]（当前草稿）+ [ValidationResult]（评审意见）经 P10 的 [AgentRuntime] → [LLMGateway]
 * 交给 Writer LLM 产出一版修订 [Draft]，并把其最终输出解析为修订 Draft。
 *
 * 依赖约束（架构硬约束）：
 *  - 只依赖 :provider:api 的 [LLMGateway] 抽象，**禁止** DeepSeek/MiMo/HTTP/API Key/Storage/SQLite/Repository；
 *  - 复用 P10 [AgentRuntime]，不重写 Agent loop；Revision 无工具（allowedTools 空），
 *    [ToolExecutor] 用空注册（不新增 Tool）；
 *  - 正文解析复用 P11.3 [DraftParser]（保持严格转写），结构字段（novelId/variantId/scope/chapterId/planId）
 *    沿用当前 Draft，draftId 重新生成、status=REVISED，**原 Draft 不被破坏、可独立恢复**；
 *  - 默认使用 Mock 模型（[ModelProfile.MOCK]），保持测试确定性。
 */
class RevisionAgent(
    private val gateway: LLMGateway,
    private val errorMapper: ErrorMapper,
    private val model: ModelProfile = ModelProfile.MOCK,
) {

    /**
     * 依据 [currentDraft] + [critique] 执行一次 Revision（同步，无网络除非装配方注入真实 Provider）。
     * 返回新 draftId、status=REVISED 的 [Draft]；失败抛类型化 [ApplicationException]。
     */
    fun revise(currentDraft: Draft, critique: ValidationResult): Draft {
        val structure = DraftStructure(
            draftId = java.util.UUID.randomUUID().toString(),
            novelId = currentDraft.novelId,
            variantId = currentDraft.variantId,
            scope = currentDraft.scope,
            chapterId = currentDraft.chapterId,
            planId = currentDraft.planId,
            sourceModel = model.id,
            now = Clock.System.now(),
        )
        return try {
            val result = runtime.run(REVISION_AGENT, renderInput(currentDraft, critique))
            val raw = result.answer
                ?: throw RevisionException.InvalidOutput("revision returned no answer")
            val parsed = parseRevisionDraft(raw, structure)
            // DraftParser 固定产出 WRITTEN；修订产物显式标记 REVISED，draftId 已是新 id（原 Draft 保留）；
            // P1-1：previousDraftId = 当前 Draft，建立 A→B→C 版本链（不新增第二套 revision counter）。
            parsed.copy(status = DraftStatus.REVISED, previousDraftId = currentDraft.draftId)
        } catch (e: ApplicationException) {
            throw e
        } catch (e: RevisionException) {
            throw errorMapper.map(e)
        } catch (e: ProviderException) {
            throw when (e) {
                is ProviderException.InvalidResponse,
                is ProviderException.MalformedOutput,
                -> ApplicationException(ApplicationError.InvalidRevisionOutput(e.message ?: "revision output malformed"))
                else -> errorMapper.map(e)
            }
        } catch (e: AgentException) {
            throw ApplicationException(ApplicationError.RevisionFailed(e.message ?: "agent runtime failed"))
        } catch (e: ToolException) {
            throw ApplicationException(ApplicationError.RevisionFailed(e.message ?: "tool failed"))
        }
    }

    /** 复用 [DraftParser] 严格解析；其 [WritingException] 翻译为 [RevisionException.InvalidOutput]，由外层归一。 */
    private fun parseRevisionDraft(raw: String, structure: com.qianyan.application.usecase.writing.DraftStructure): Draft =
        try {
            DraftParser.parse(raw, structure)
        } catch (e: WritingException) {
            throw RevisionException.InvalidOutput(e.message ?: "revision output malformed")
        }

    /** 构造供 Revision LLM 使用的输入文本（原正文 + 评审意见 + 修订指令）。 */
    private fun renderInput(currentDraft: Draft, critique: ValidationResult): String = buildString {
        appendLine("【原草稿】${currentDraft.draftId.value}")
        if (currentDraft.content.isNotBlank()) {
            appendLine(currentDraft.content.take(REVISION_INPUT_CONTENT_LIMIT))
        } else {
            appendLine("（空正文）")
        }

        appendLine("【评审意见】")
        if (critique.issues.isEmpty()) {
            appendLine("（无具体意见）passed=${critique.passed}")
        } else {
            critique.issues.forEach { i ->
                appendLine("- [${i.severity}] ${i.field.ifBlank { "正文" }}: ${i.message}")
            }
        }

        appendLine()
        appendLine("请依据上述评审意见对【原草稿】进行修订，按 JSON 输出新正文：" +
            "{\"content\":\"修订后的完整正文\"}")
    }.trimEnd()

    private val runtime: AgentRuntime = AgentRuntime(
        gateway = gateway,
        toolExecutor = ToolExecutor(ToolRegistry()),
        model = model,
    )

    private companion object {
        val REVISION_AGENT: AgentContract = AgentContract(
            agentId = AgentId("story-writer-revision"),
            name = "StoryRevisionAgent",
            capabilities = listOf(),
            allowedTools = emptyList(),
        )

        /** 修订输入原正文截断阈值（修订聚焦意见所指范围，不整章重排）。 */
        const val REVISION_INPUT_CONTENT_LIMIT: Int = 4000
    }
}