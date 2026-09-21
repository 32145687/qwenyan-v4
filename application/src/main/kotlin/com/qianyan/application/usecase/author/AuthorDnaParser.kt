package com.qianyan.application.usecase.author

import com.qianyan.model.author.AuthorDnaDimension
import com.qianyan.model.author.DnaFeatureCandidate
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Author DNA LLM 输出的确定性解析 + 校验（P18-C · DEC-P18C-009/010/011）。
 *
 * 边界：
 *  - **LLM 只提出候选**：[parse] 把 LLM JSON 文本解析为 [DnaFeatureCandidate] 列表；
 *    program 负责 dimension 校验、非空校验、confidence 校验（DEC-P18C-010）。
 *  - **不允许 LLM 伪造 sourceLocation**：本解析器**不接受**任何 location / sourceId / authorId 字段；
 *    这些一律由 [AuthorDnaUseCases] 从确定性 `AnalysisInput` 程序绑定（DEC-P18C-010/015）。
 *  - **confidence**：LLM 自报可选；缺失/非法 → [com.qianyan.model.author.Confidence.HALF]（0.5）；
 *    最终 `clamp [0, 0.9]`（复用 [AuthorCoreAggregator.CONFIDENCE_CEILING]；无新统计公式，DEC-P18C-011）。
 *    跨 source 一致性校正不在本类做（避免发明公式）；多源等权聚合在 UseCase 层确定性完成。
 *  - 非法行（unknown dimension / 空 featureKey / 空 value / 空 statement）**丢弃**，不抛错。
 */
internal object AuthorDnaParser {

    const val LIQUID_CEILING: Double = AuthorCoreAggregator.CONFIDENCE_CEILING // 0.9
    const val DEFAULT_CONFIDENCE: Double = 0.5 // Confidence.HALF

    private val aiJson = Json { ignoreUnknownKeys = true }

    fun parse(content: String): List<DnaFeatureCandidate> {
        if (content.isBlank()) return emptyList()
        val payload = try {
            aiJson.decodeFromString<DnaPayload>(content)
        } catch (e: Exception) {
            return emptyList() // 非法 JSON → 无候选（保守丢弃）
        }
        return payload.features.mapNotNull { it.toCandidate() }
    }

    /** 单条 LLM 候选 → 合法 [DnaFeatureCandidate]；非法（dimension/value/featureKey/statement）返回 null。 */
    private fun DnaFeatureDto.toCandidate(): DnaFeatureCandidate? {
        val dim = AuthorDnaDimension.entries.firstOrNull { it.name == this.dimension }
            ?: return null // unknown dimension → reject（DEC-P18C-009 固定 8 类）
        val key = this.featureKey.trim()
        if (key.isBlank()) return null
        if (this.value.isBlank()) return null
        if (this.statement.isBlank()) return null
        return DnaFeatureCandidate(
            dimension = dim,
            featureKey = key,
            value = this.value,
            statement = this.statement,
            confidence = validateConfidence(this.confidence),
            evidence = this.evidence?.takeIf { it.isNotBlank() },
        )
    }

    /** confidence 校验：null/非法 → [DEFAULT_CONFIDENCE]（0.5）。上限留给 UseCase 统一 clamp。 */
    private fun validateConfidence(raw: Double?): Double =
        if (raw != null && raw in 0.0..1.0) raw else DEFAULT_CONFIDENCE

    @Serializable
    private data class DnaPayload(val features: List<DnaFeatureDto> = emptyList())

    @Serializable
    private data class DnaFeatureDto(
        val dimension: String = "",
        val featureKey: String = "",
        val value: String = "",
        val statement: String = "",
        val confidence: Double? = null,
        val evidence: String? = null,
    )
}