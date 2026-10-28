package com.qianyan.application.usecase.writing.consistency

import com.qianyan.model.characterconsistency.CharacterConsistencyConfidence
import com.qianyan.model.characterconsistency.CharacterConsistencyEvidenceSource
import com.qianyan.model.characterconsistency.CharacterConsistencyFindingType
import com.qianyan.model.characterconsistency.CharacterConsistencyMissingInput
import com.qianyan.model.characterconsistency.CharacterConsistencyScope
import com.qianyan.model.characterconsistency.CharacterConsistencyStatus
import com.qianyan.model.memory.MemoryLayer
import com.qianyan.model.spec.IssueSeverity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** CharacterConsistency 稳定错误码（Parser 与 Validator 共用）。 */
internal object CharacterConsistencyErrorCodes {
    const val INVALID_OUTPUT = "INVALID_OUTPUT"
    const val INVALID_STATUS = "INVALID_STATUS"
    const val INVALID_SCOPE = "INVALID_SCOPE"
    const val CHARACTER_NOT_IN_INPUT = "CHARACTER_NOT_IN_INPUT"
    const val UNRESOLVED_CHARACTER_INVALID = "UNRESOLVED_CHARACTER_INVALID"
    const val EVIDENCE_MISSING = "EVIDENCE_MISSING"
    const val EVIDENCE_SOURCE_UNKNOWN = "EVIDENCE_SOURCE_UNKNOWN"
    const val EVIDENCE_FIELD_UNKNOWN = "EVIDENCE_FIELD_UNKNOWN"
    const val EVIDENCE_QUOTE_NOT_FOUND = "EVIDENCE_QUOTE_NOT_FOUND"
    const val EVIDENCE_QUOTE_AMBIGUOUS = "EVIDENCE_QUOTE_AMBIGUOUS"
    const val EVIDENCE_OFFSET_INVALID = "EVIDENCE_OFFSET_INVALID"
    const val EVIDENCE_MEMORY_INVALID = "EVIDENCE_MEMORY_INVALID"
    const val SEVERITY_CONFIDENCE_INVALID = "SEVERITY_CONFIDENCE_INVALID"
    const val CHECKED_CHARACTER_INVALID = "CHECKED_CHARACTER_INVALID"
    const val BASIS_INVALID = "BASIS_INVALID"
    const val PASS_WITH_MISSING_INPUT = "PASS_WITH_MISSING_INPUT"
    const val INSUFFICIENT_WITHOUT_MISSING = "INSUFFICIENT_WITHOUT_MISSING"
}

/** 内部类型化异常；由 ErrorMapper 映射为 ApplicationError。 */
internal sealed class CharacterConsistencyException(message: String) : RuntimeException(message) {
    abstract val code: String

    internal class InvalidOutput(
        override val code: String,
        message: String,
    ) : CharacterConsistencyException(message)

    internal class InvalidResult(
        override val code: String,
        message: String,
    ) : CharacterConsistencyException(message)
}

/** Parser 产物：只包含 LLM 可声明的字段；basis / truncated 由 Validator 计算。 */
@Serializable
internal data class CharacterConsistencyRawResult(
    val status: CharacterConsistencyStatus,
    val scope: CharacterConsistencyScope,
    val findings: List<CharacterConsistencyRawFinding>,
    val checkedCharacters: List<CharacterConsistencyRawCheckedCharacter>,
    val missingInputs: List<CharacterConsistencyMissingInput>,
    val summary: String,
)

@Serializable
internal data class CharacterConsistencyRawFinding(
    val type: CharacterConsistencyFindingType,
    val severity: IssueSeverity,
    val characterId: String? = null,
    val characterName: String,
    val message: String,
    val evidence: List<CharacterConsistencyRawEvidence>,
    val confidence: CharacterConsistencyConfidence,
    val suggestion: String? = null,
)

@Serializable
internal data class CharacterConsistencyRawEvidence(
    val quote: String,
    val sourceKind: CharacterConsistencyEvidenceSource,
    val sourceId: String? = null,
    val field: String? = null,
    val memoryLayer: MemoryLayer? = null,
    val sourceIndex: Int? = null,
    val startOffset: Int? = null,
    val endOffset: Int? = null,
)

@Serializable
internal data class CharacterConsistencyRawCheckedCharacter(
    val characterId: String,
    val name: String,
)

/**
 * 严格解析 CharacterConsistency LLM 输出。
 *
 * 规则：空输出、非 JSON、缺字段、类型错误、非法枚举、未知字段、尾随内容、
 * 必填字符串为空 → [CharacterConsistencyException.InvalidOutput]。
 * 不使用 lenient / ignoreUnknownKeys / 自动补字段。
 */
internal object CharacterConsistencyParser {

    private val json = Json {
        ignoreUnknownKeys = false
        isLenient = false
        encodeDefaults = true
    }

    fun parse(raw: String): CharacterConsistencyRawResult {
        if (raw.isBlank()) {
            invalidOutput("empty CharacterConsistency output")
        }
        val result = try {
            json.decodeFromString<CharacterConsistencyRawResult>(raw)
        } catch (e: Exception) {
            invalidOutput("illegal json or wrong field type: ${e.message}")
        }
        if (result.summary.isBlank()) {
            invalidOutput("summary is blank")
        }
        result.findings.forEach { finding ->
            if (finding.message.isBlank()) {
                invalidOutput("finding message is blank")
            }
            if (finding.characterName.isBlank()) {
                invalidOutput("finding characterName is blank")
            }
            finding.evidence.forEach { evidence ->
                if (evidence.quote.isBlank()) {
                    invalidOutput("evidence quote is blank")
                }
            }
        }
        result.checkedCharacters.forEach { checked ->
            if (checked.characterId.isBlank() || checked.name.isBlank()) {
                invalidOutput("checked character id/name is blank")
            }
        }
        return result
    }

    private fun invalidOutput(detail: String): Nothing =
        throw CharacterConsistencyException.InvalidOutput(
            CharacterConsistencyErrorCodes.INVALID_OUTPUT,
            detail,
        )
}
