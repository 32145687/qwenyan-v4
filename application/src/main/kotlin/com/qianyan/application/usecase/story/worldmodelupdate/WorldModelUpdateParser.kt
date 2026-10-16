package com.qianyan.application.usecase.story.worldmodelupdate

import com.qianyan.model.worldmodel.CandidateFactKind
import com.qianyan.model.worldmodel.CandidateFactOperation
import kotlinx.serialization.json.Json

/**
 * World Model Update 候选解析（Phase 3；镜像 `KnowledgeUpdateParser` 范式）。
 *
 * 把 [WorldModelUpdateAgent] 的 LLM 原始文本可靠映射为 [ExtractedFact] 列表。
 * 本 Parser 只负责"结构边界"（JSON → 候选），不写库、不裁决、不解决冲突。
 *
 * 规则（严格，无 silent fallback）：
 *  - 空 / 非 JSON / 任一条缺必填字段（factId / operation / name / summary）/ 字段类型错误 /
 *    operation 或 kind 非法枚举 → [WorldModelUpdateException.InvalidOutput]；
 *  - 作用域 / Commit 来源由调用上下文注入，不由 LLM 生成。
 */
object WorldModelUpdateParser {

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(raw: String): List<ExtractedFact> {
        if (raw.isBlank()) {
            throw WorldModelUpdateException.InvalidOutput("empty world model update output")
        }
        val dto = try {
            json.decodeFromString(ExtractedFact.BatchDto.serializer(), raw)
        } catch (e: Exception) {
            throw WorldModelUpdateException.InvalidOutput("illegal json or wrong field type: ${e.message}")
        }
        return dto.facts.map { d ->
            val kind = parseEnum(d.kind, "kind") { CandidateFactKind.valueOf(it) }
            val operation = parseEnum(d.operation, "operation") { CandidateFactOperation.valueOf(it) }
            if (d.factId.isBlank()) throw WorldModelUpdateException.InvalidOutput("factId 为空")
            if (d.name.isBlank()) throw WorldModelUpdateException.InvalidOutput("name 为空")
            if (d.summary.isBlank()) throw WorldModelUpdateException.InvalidOutput("summary 为空")
            ExtractedFact(
                factId = d.factId,
                kind = kind,
                operation = operation,
                name = d.name,
                summary = d.summary,
                excerpt = d.excerpt,
                reason = d.reason,
            )
        }
    }

    private inline fun <T> parseEnum(raw: String, field: String, parse: (String) -> T): T =
        try {
            parse(raw)
        } catch (e: IllegalArgumentException) {
            throw WorldModelUpdateException.InvalidOutput("$field 非法: $raw")
        }
}
