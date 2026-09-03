package com.qianyan.application.usecase.writing.knowledgeupdate

import com.qianyan.model.NovelId
import com.qianyan.model.VariantId
import com.qianyan.model.VariantScope
import com.qianyan.model.knowledge.CandidateKnowledgeChange
import com.qianyan.model.knowledge.KnowledgeCategory
import com.qianyan.model.knowledge.KnowledgeOperation
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Knowledge Update 候选解析（P11.5）。
 *
 * 把 KnowledgeUpdateAgent 的 LLM 原始文本可靠映射为 [CandidateKnowledgeChange] 列表。
 * LLM 只提供候选；本 Parser 只负责"结构边界"（JSON → 候选），不写库、不裁决、不解决冲突。
 *
 * 规则（严格，无 silent fallback）：
 *  - 空 / 非 JSON / 根节点非数组 / 任一条缺必填字段 / 字段类型错误 / operation 非法 / target 或 content 为空
 *    → [KnowledgeUpdateException.InvalidOutput]（类型化，不经 String.contains）；
 *  - scope / variantId 来自调用上下文注入，不由 LLM 生成（避免 LLM 伪造作用域）。
 */
object KnowledgeUpdateParser {

    /** 解析为候选列表。scope 来自上下文，LLM 不提供 scope/variant。 */
    fun parse(raw: String, novelId: NovelId, variantId: VariantId?, scope: VariantScope): List<CandidateKnowledgeChange> {
        val dto = decode(raw)
        return dto.changes.map { it.toCandidate(novelId, variantId, scope) }
    }

    private fun decode(raw: String): KnowledgeUpdateDto {
        if (raw.isBlank()) {
            throw KnowledgeUpdateException.InvalidOutput("empty knowledge update output")
        }
        return try {
            json.decodeFromString<KnowledgeUpdateDto>(raw)
        } catch (e: Exception) {
            throw KnowledgeUpdateException.InvalidOutput("illegal json or wrong field type: ${e.message}")
        }
    }

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class KnowledgeUpdateDto(val changes: List<ChangeDto>)

    @Serializable
    private data class ChangeDto(
        val changeId: String,
        val operation: KnowledgeOperation,
        val target: String,
        val category: KnowledgeCategory = KnowledgeCategory.WORLD_RULE,
        val content: String,
        val confidence: Float = 0.5f,
        val source: String = "",
        val reason: String = "",
    ) {
        /** 必填字段缺失/空 → 类型化错误（不把残缺候选静默接受为有效）。changeId 在数组内唯一性由校验层负责。 */
        fun toCandidate(novelId: NovelId, variantId: VariantId?, scope: VariantScope): CandidateKnowledgeChange {
            if (changeId.isBlank()) throw KnowledgeUpdateException.InvalidOutput("changeId is empty")
            if (target.isBlank()) throw KnowledgeUpdateException.InvalidOutput("candidate $changeId has empty target")
            if (content.isBlank()) throw KnowledgeUpdateException.InvalidOutput("candidate $changeId has empty content")
            return CandidateKnowledgeChange(
                changeId = changeId,
                novelId = novelId,
                variantId = variantId,
                scope = scope,
                operation = operation,
                target = target,
                category = category,
                content = content,
                confidence = confidence.coerceIn(0f, 1f),
                source = source,
                reason = reason,
            )
        }
    }
}