package com.qianyan.application.usecase.writing.critique

import com.qianyan.model.spec.IssueSeverity
import com.qianyan.model.spec.ValidationIssue
import com.qianyan.model.spec.ValidationResult
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * [ValidationResult] 评审输出解析（P11.4）。
 *
 * 把 Critic 的 LLM 原始文本可靠映射为现有 [ValidationResult]（复用 core:model.spec，
 * 不新建重复的 critique 模型）。Critique 只输出 `passed` + 评审意见列表。
 *
 * 规则（严格，无 silent fallback）：
 *  - 空输出 / 非 JSON / 缺 `passed` / `passed` 非布尔 / `issues` 非数组 / 单条 issue 缺 `message`
 *    或 message 为空 → [CritiqueException.InvalidOutput]（类型化，不经 String.contains）；
 *  - `severity` 必须是 [IssueSeverity] 枚举名或缺失（缺失默认 INFO）；
 *  - 绝不把 LLM 自由文本 / 无关 JSON（如 Mock 词汇响应）当成成功评审结果。
 */
object CritiqueParser {

    /** 解析 Critic 输出为 [ValidationResult]。 */
    fun parse(raw: String): ValidationResult {
        val dto = decode(raw)
        val issues = dto.issues.map { it.toIssue() }
        return ValidationResult(
            passed = dto.passed,
            issues = issues,
        )
    }

    private fun decode(raw: String): CritiqueDto {
        // T-O78-F：仅剥**一层**完整 Markdown 围栏（首行 ```/```json + 末行 ```），其余原样严格解码。
        val text = stripSingleMarkdownFence(raw)
        if (text.isBlank()) {
            throw CritiqueException.InvalidOutput("empty critique output")
        }
        return try {
            json.decodeFromString<CritiqueDto>(text)
        } catch (e: Exception) {
            // 非 JSON / 缺 passed / passed 类型错误 / issues 类型错误 统一走类型化解析错误。
            throw CritiqueException.InvalidOutput("illegal json or wrong field type: ${e.message}")
        }
    }

    /**
     * 仅当整段被**一层** Markdown 围栏包裹时剥离：首行为 ``` 或 ```json 等语言标记，末行为 ```。
     *
     * 不做"取首个 { 到末个 }"，不容忍围栏之外的散文/前言，不做贪婪匹配；不符合条件时返回原文（逐字节不变）。
     */
    private fun stripSingleMarkdownFence(raw: String): String {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("```") || !trimmed.endsWith("```")) return raw
        val lines = trimmed.split('\n')
        if (lines.size < 3) return raw
        val first = lines.first().trim()
        // 首行只能是围栏本身（可带语言标记），不能与正文同处一行。
        if (!FENCE_OPEN.matches(first)) return raw
        if (lines.last().trim() != "```") return raw
        return lines.subList(1, lines.size - 1).joinToString("\n").trim()
    }

    private val FENCE_OPEN = Regex("^```[A-Za-z0-9_+\\-]*$")

    private val json = Json { ignoreUnknownKeys = true }

    /** Critic JSON 的 DTO。passed 与 issues 均为必填结构（缺失/类型错误 → 解码即失败）。 */
    @Serializable
    private data class CritiqueDto(
        val passed: Boolean,
        val issues: List<IssueDto> = emptyList(),
    )

    @Serializable
    private data class IssueDto(
        val field: String = "",
        val severity: IssueSeverity? = null,
        val message: String = "",
    ) {
        /** message 缺失/空白 → 类型化错误（issue 无意见内容即失败，绝不静默接受）。 */
        fun toIssue(): ValidationIssue {
            if (message.isBlank()) {
                throw CritiqueException.InvalidOutput("issue has empty message")
            }
            return ValidationIssue(
                field = field,
                severity = severity ?: IssueSeverity.INFO,
                message = message,
            )
        }
    }
}