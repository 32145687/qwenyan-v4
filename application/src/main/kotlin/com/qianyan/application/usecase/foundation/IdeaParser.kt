package com.qianyan.application.usecase.foundation

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * P15-C · Idea Understanding 结构化输出解析。
 *
 * 只负责把 Idea LLM 输出可靠解析为 [IdeaUnderstanding]（aiSummary + FoundationProposal）。
 * 复用 [FoundationProposal]（P15-A 既有模型，不再建第二套 Proposal）。
 * 解析失败抛 [IdeaParseException]，由 Agent 归一到现有 ApplicationError（不新增 error framework）。
 */
object IdeaParser {

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(raw: String): IdeaUnderstanding {
        if (raw.isBlank()) throw IdeaParseException("empty idea output")
        val dto = try {
            json.decodeFromString<IdeaDto>(raw)
        } catch (e: Exception) {
            throw IdeaParseException("illegal json or wrong field type: ${e.message}")
        }
        if (dto.aiSummary.isBlank()) throw IdeaParseException("missing aiSummary")
        return IdeaUnderstanding(aiSummary = dto.aiSummary, proposal = dto.proposal)
    }

    /** Idea LLM 的结构化输出契约（proposal 复用 [FoundationProposal]）。 */
    @Serializable
    private data class IdeaDto(
        val aiSummary: String = "",
        val proposal: FoundationProposal = FoundationProposal(),
    )
}

/** AI 对一句话想法的理解结果：简短摘要 + 可进入用户决策的 FoundationProposal（proposal ≠ 事实）。 */
data class IdeaUnderstanding(
    val aiSummary: String,
    val proposal: FoundationProposal,
)

/** 仅供 [IdeaParser] 内部使用的解析失败信号（不构成新异常体系；由 Agent 归一为 ApplicationError）。 */
class IdeaParseException(message: String) : RuntimeException(message)