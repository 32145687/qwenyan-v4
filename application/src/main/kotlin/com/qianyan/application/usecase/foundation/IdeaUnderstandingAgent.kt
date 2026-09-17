package com.qianyan.application.usecase.foundation

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
import com.qianyan.model.foundation.StoryIntent
import com.qianyan.provider.LLMGateway
import com.qianyan.provider.ModelProfile
import com.qianyan.provider.ProviderException

/**
 * P15-C · Idea Understanding Agent。
 *
 * 职责：把 [StoryIntent.rawIdea] 经现有 [AgentRuntime] → [LLMGateway] 交给 LLM，产出
 * [IdeaUnderstanding]（aiSummary + [FoundationProposal]）。
 *
 * 依赖约束（与 PlannerAgent/WriterAgent 一致）：
 *  - 只依赖 :provider:api [LLMGateway] 抽象；复用 [AgentRuntime]；**不新增 Provider / Runtime / Gateway**；
 *  - 不复用 PlannerAgent（其输出是 ChapterPlan，契约为 Issue 侧不同）；
 *  - Agent 只做 AI understanding / proposal generation：输出**不包含** StoryFoundation 写入指令 / Gate /
 *    Decision / Repository / Checkpoint / Planning 操作。
 */
class IdeaUnderstandingAgent(
    private val gateway: LLMGateway,
    private val errorMapper: ErrorMapper,
    private val model: ModelProfile = ModelProfile.MOCK,
) {

    /** 对 [StoryIntent.rawIdea] 做理解，返回 aiSummary + FoundationProposal。 */
    fun understand(storyIntent: StoryIntent): IdeaUnderstanding {
        return try {
            val result = runtime.run(AGENT, renderInput(storyIntent.rawIdea))
            val raw = result.answer ?: throw IdeaParseException("idea agent returned no answer")
            IdeaParser.parse(raw)
        } catch (e: IdeaParseException) {
            throw ApplicationException(ApplicationError.InvalidAnalysisOutput(e.message ?: "idea output malformed"))
        } catch (e: ProviderException) {
            throw when (e) {
                is ProviderException.InvalidResponse,
                is ProviderException.MalformedOutput,
                -> ApplicationException(ApplicationError.InvalidAnalysisOutput(e.message ?: "idea provider returned malformed response"))
                else -> errorMapper.map(e)
            }
        } catch (e: AgentException) {
            throw ApplicationException(ApplicationError.AnalysisFailed(e.message ?: "idea agent runtime failed"))
        } catch (e: ToolException) {
            throw ApplicationException(ApplicationError.AnalysisFailed(e.message ?: "idea tool failed"))
        }
    }

    /** 构造供 Idea LLM 使用的输入文本（仅创作性输入，不含写入/操作指令）。 */
    private fun renderInput(rawIdea: String): String = buildString {
        appendLine("你是一位故事创作构思助手。请把用户的一句话故事想法，解析为结构化 JSON。")
        appendLine("只输出一个 JSON 对象，结构如下（不要输出其它内容）：")
        appendLine("{\"aiSummary\":\"用一句话总结用户想写什么\",\"proposal\":")
        appendLine("{\"genre\":[\"题材名\"],\"direction\":{\"theme\":\"主题\",\"conflict\":\"核心冲突\",\"promise\":\"给读者的承诺\",\"storyType\":\"故事类型\"},")
        appendLine("\"audience\":{\"pov\":\"视角\",\"readerTone\":\"读者基调\"},\"policy\":{\"rules\":[\"写作规则\"]}}}")
        appendLine("【用户的一句话想法】")
        appendLine(rawIdea)
    }.trimEnd()

    private val runtime: AgentRuntime = AgentRuntime(
        gateway = gateway,
        toolExecutor = ToolExecutor(ToolRegistry()),
        model = model,
    )

    private companion object {
        val AGENT: AgentContract = AgentContract(
            agentId = AgentId("idea-understanding"),
            name = "IdeaUnderstandingAgent",
            capabilities = listOf(),
            allowedTools = emptyList(),
        )
    }
}