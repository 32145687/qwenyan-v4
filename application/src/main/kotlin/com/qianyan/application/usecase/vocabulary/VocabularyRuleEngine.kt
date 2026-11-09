package com.qianyan.application.usecase.vocabulary

import com.qianyan.model.vocabulary.VocabularyRule
import com.qianyan.model.vocabulary.VocabularyRuleHit
import com.qianyan.model.vocabulary.VocabularyRuleSkip
import com.qianyan.model.vocabulary.VocabularyRuleSkipReason
import com.qianyan.model.vocabulary.VocabularyScopeLevel

/**
 * T-O37-A · VocabularyRule 确定性执行引擎（**纯函数**：无 IO / 无 LLM / 无仓储）。
 *
 * 语义（契约 A-19）：
 *  - 只接受 NOVEL / VARIANT 作用域的规则（归属在调用方过滤；GLOBAL/TASK → OUT_OF_SCOPE）；
 *  - 排序：`from` 长度降序 → 作用域窄优先（VARIANT 先于 NOVEL）→ `ruleId` 字典序；
 *  - 匹配：**单遍左→右、非重叠**；每个位置取排序后第一条命中；命中后跳过 `from.length`，
 *    替换结果**不再重扫**（不级联）；大小写敏感，不做 Unicode 归一化；
 *  - 边界：`from` 首/尾为 ASCII 字母/数字/下划线时，检查对应一侧的词边界；中文/混合串按子串匹配；
 *  - 跳过规则逐条记录 [VocabularyRuleSkipReason]（不静默）；
 *  - 无命中时原样返回同一字符串实例，`hits` 为空。
 */
object VocabularyRuleEngine {

    fun apply(content: String, rules: List<VocabularyRule>): VocabularyApplyResult {
        val skipped = mutableListOf<VocabularyRuleSkip>()
        val candidates = mutableListOf<VocabularyRule>()

        rules.forEach { rule ->
            when {
                rule.scopeLevel != VocabularyScopeLevel.NOVEL && rule.scopeLevel != VocabularyScopeLevel.VARIANT ->
                    skipped += VocabularyRuleSkip(rule.ruleId, VocabularyRuleSkipReason.OUT_OF_SCOPE)

                !rule.enabled ->
                    skipped += VocabularyRuleSkip(rule.ruleId, VocabularyRuleSkipReason.DISABLED)

                !rule.deterministicOnly ->
                    skipped += VocabularyRuleSkip(rule.ruleId, VocabularyRuleSkipReason.NON_DETERMINISTIC)

                rule.from.isBlank() ->
                    skipped += VocabularyRuleSkip(rule.ruleId, VocabularyRuleSkipReason.EMPTY_FROM)

                rule.from == rule.to ->
                    skipped += VocabularyRuleSkip(rule.ruleId, VocabularyRuleSkipReason.NO_OP)

                rule.to.contains(rule.from) ->
                    skipped += VocabularyRuleSkip(rule.ruleId, VocabularyRuleSkipReason.NON_IDEMPOTENT)

                else -> candidates += rule
            }
        }

        // 链风险：某规则的替换结果包含**另一条**活跃规则的 from ⇒ 下次执行会继续变化（非幂等）。
        val active = candidates.filter { rule ->
            candidates.none { other -> other !== rule && rule.to.contains(other.from) }
        }
        candidates.filterNot { it in active }
            .forEach { skipped += VocabularyRuleSkip(it.ruleId, VocabularyRuleSkipReason.CHAIN_RISK) }

        val sorted = active.sortedWith(
            compareByDescending<VocabularyRule> { it.from.length }
                .thenBy { if (it.scopeLevel == VocabularyScopeLevel.VARIANT) 0 else 1 }
                .thenBy { it.ruleId.value },
        )
        if (sorted.isEmpty() || content.isEmpty()) {
            return VocabularyApplyResult(content, emptyList(), skipped)
        }

        val hits = LinkedHashMap<String, MutableHit>()
        val out = StringBuilder(content.length)
        var index = 0
        while (index < content.length) {
            val matched = sorted.firstOrNull { rule -> matchesAt(content, index, rule.from) }
            if (matched == null) {
                out.append(content[index])
                index += 1
                continue
            }
            val accumulator = hits.getOrPut(matched.ruleId.value) {
                MutableHit(matched.ruleId, matched.from, matched.to, firstIndex = index)
            }
            accumulator.count += 1
            out.append(matched.to)
            index += matched.from.length
        }
        if (hits.isEmpty()) {
            return VocabularyApplyResult(content, emptyList(), skipped)
        }
        return VocabularyApplyResult(
            content = out.toString(),
            hits = hits.values.map { VocabularyRuleHit(it.ruleId, it.from, it.to, it.count, it.firstIndex) },
            skipped = skipped,
        )
    }

    private fun matchesAt(content: String, index: Int, from: String): Boolean {
        if (!content.startsWith(from, index)) return false
        if (isAsciiWordChar(from.first()) && index > 0 && isAsciiWordChar(content[index - 1])) return false
        val after = index + from.length
        if (isAsciiWordChar(from.last()) && after < content.length && isAsciiWordChar(content[after])) return false
        return true
    }

    private fun isAsciiWordChar(c: Char): Boolean =
        (c in 'a'..'z') || (c in 'A'..'Z') || (c in '0'..'9') || c == '_'

    private class MutableHit(
        val ruleId: com.qianyan.model.VocabularyRuleId,
        val from: String,
        val to: String,
        val firstIndex: Int,
    ) {
        var count: Int = 0
    }
}

/** 一次应用的结果：替换后正文 + 每规则命中 + 每条被跳过规则的原因。 */
data class VocabularyApplyResult(
    val content: String,
    val hits: List<VocabularyRuleHit>,
    val skipped: List<VocabularyRuleSkip>,
)
