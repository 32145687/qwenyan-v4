package com.qianyan.application.usecase.decision

import com.qianyan.model.author.AuthorContext
import com.qianyan.model.author.AuthorDnaDimension
import com.qianyan.model.author.AuthorDnaLite
import com.qianyan.model.author.PreferenceDimension
import com.qianyan.model.decision.DecisionOutcome
import com.qianyan.model.decision.DecisionPolicy
import com.qianyan.model.decision.DecisionPolicySource
import com.qianyan.model.decision.DecisionType

/*
 * P19 · DecisionModel —— 确定性转译器：AuthorContext → DecisionPolicy。
 *
 * 冻结约束（P19 Contract 修正版）：
 *  - 纯函数：无 Repository / Storage / Provider / LLM / Agent / Orchestrator / 历史 / 时间 / 随机 / 隐藏状态。
 *    同一输入 → 同一输出。
 *  - 只读取调用方传入的 [AuthorContext]（Preference > Core > DNA 冲突优先级；非权重 / 非数值融合）。
 *  - Rule Matrix：per-type 确定性 `when`；不命中任何 rule → DETERMINISTIC_DEFAULT + NEUTRAL。
 *  - 合法组合由本类 when 分支保证（每 type 只产出该类型合法 outcome；NEUTRAL 跨类型合法）。
 *  - V1 不新增字段 / 权重 / 推断机制；信号明确性由**代码常量字面量匹配**判定（DEC-P19-109 code constants）。
 */
object DecisionModel {

    /**
     * V1 规则字面量词汇（代码常量；确定性 contains 匹配，忽略大小写）。
     * 说明：AuthorContext 的 Lite 只携带自由文本 statement / value；本词汇是 Rule Matrix 的确定性判定键，
     * 不引入权重 / 分数 / 推断。未命中 → 回退到更低优先级，最终 NEUTRAL。
     */
    private object Vocabulary {
        const val PROGRESSIVE = "progressive"
        const val DIRECT = "direct"
        const val SHORT = "short"
        const val LONG = "long"
    }

    /** 对给定类型求唯一 [DecisionPolicy]。 */
    fun evaluate(authorContext: AuthorContext, type: DecisionType): DecisionPolicy = when (type) {
        DecisionType.STORY_DIRECTION -> storyDirection(authorContext)
        DecisionType.WRITING_STYLE -> writingStyle(authorContext)
    }

    /** 批量求值（类型以调用顺序稳定返回）。 */
    fun decide(authorContext: AuthorContext, vararg types: DecisionType): List<DecisionPolicy> =
        types.toList().map { evaluate(authorContext, it) }

    // ================= STORY_DIRECTION（Preference > Core > DNA > NEUTRAL） =================

    private fun storyDirection(ctx: AuthorContext): DecisionPolicy {
        preferenceStory(ctx)?.let { return policy(DecisionType.STORY_DIRECTION, it, DecisionPolicySource.RULE_MATRIX) }
        coreStory(ctx)?.let { return policy(DecisionType.STORY_DIRECTION, it, DecisionPolicySource.RULE_MATRIX) }
        dnaStory(ctx)?.let { return policy(DecisionType.STORY_DIRECTION, it, DecisionPolicySource.RULE_MATRIX) }
        return default(DecisionType.STORY_DIRECTION)
    }

    private fun preferenceStory(ctx: AuthorContext): DecisionOutcome? =
        ctx.preferences
            .firstOrNull { it.dimension == PreferenceDimension.STORY_DIRECTION }
            ?.statement
            ?.let { storyOutcomeFromText(it) }

    private fun coreStory(ctx: AuthorContext): DecisionOutcome? =
        ctx.cores.firstOrNull { it.patternKey == "core:foundation" }?.statement?.let { storyOutcomeFromText(it) }

    private fun dnaStory(ctx: AuthorContext): DecisionOutcome? =
        ctx.dna.firstOrNull { it.dimension == AuthorDnaDimension.NARRATIVE }?.let { storyOutcomeFromText(it.value + " " + it.statement) }

    private fun storyOutcomeFromText(text: String): DecisionOutcome? = when {
        contains(text, Vocabulary.PROGRESSIVE) -> DecisionOutcome.PROGRESSIVE_REVEAL
        contains(text, Vocabulary.DIRECT) -> DecisionOutcome.DIRECT_PRESENTATION
        else -> null
    }

    // ================= WRITING_STYLE（Preference > Core > DNA > NEUTRAL） =================

    private fun writingStyle(ctx: AuthorContext): DecisionPolicy {
        preferenceStyle(ctx)?.let { return policy(DecisionType.WRITING_STYLE, it, DecisionPolicySource.RULE_MATRIX) }
        coreStyle(ctx)?.let { return policy(DecisionType.WRITING_STYLE, it, DecisionPolicySource.RULE_MATRIX) }
        dnaStyle(ctx)?.let { return policy(DecisionType.WRITING_STYLE, it, DecisionPolicySource.RULE_MATRIX) }
        return default(DecisionType.WRITING_STYLE)
    }

    private fun preferenceStyle(ctx: AuthorContext): DecisionOutcome? =
        ctx.preferences
            .firstOrNull { it.dimension == PreferenceDimension.VOICE || it.dimension == PreferenceDimension.TONE }
            ?.statement
            ?.let { styleOutcomeFromText(it) }

    private fun coreStyle(ctx: AuthorContext): DecisionOutcome? =
        ctx.cores.firstOrNull()?.statement?.let { styleOutcomeFromText(it) }

    private fun dnaStyle(ctx: AuthorContext): DecisionOutcome? =
        ctx.dna.firstOrNull { it.dimension == AuthorDnaDimension.SENTENCE }?.let { styleOutcomeFromText(it.value + " " + it.statement) }

    private fun styleOutcomeFromText(text: String): DecisionOutcome? = when {
        contains(text, Vocabulary.SHORT) -> DecisionOutcome.SHORT_DOMINANT
        contains(text, Vocabulary.LONG) -> DecisionOutcome.LONG_DOMINANT
        else -> null
    }

    // ================= Default（Semantic-Neutral） =================

    private fun default(type: DecisionType): DecisionPolicy =
        policy(type, DecisionOutcome.NEUTRAL, DecisionPolicySource.DETERMINISTIC_DEFAULT)

    /**
     * 构造策略并强制合法组合：若 outcome 与 type 不匹配则回退 NEUTRAL（防御；正常 when 分支不会触发）。
     * 合法组合：STORY_DIRECTION + (PROGRESSIVE_REVEAL/DIRECT_PRESENTATION/NEUTRAL)；
     *          WRITING_STYLE + (SHORT_DOMINANT/LONG_DOMINANT/NEUTRAL)。
     */
    private fun policy(type: DecisionType, outcome: DecisionOutcome, source: DecisionPolicySource): DecisionPolicy {
        val legal = when (type) {
            DecisionType.STORY_DIRECTION -> outcome == DecisionOutcome.PROGRESSIVE_REVEAL ||
                outcome == DecisionOutcome.DIRECT_PRESENTATION || outcome == DecisionOutcome.NEUTRAL
            DecisionType.WRITING_STYLE -> outcome == DecisionOutcome.SHORT_DOMINANT ||
                outcome == DecisionOutcome.LONG_DOMINANT || outcome == DecisionOutcome.NEUTRAL
        }
        return DecisionPolicy(
            decisionType = type,
            outcome = if (legal) outcome else DecisionOutcome.NEUTRAL,
            source = source,
        )
    }

    private fun contains(text: String, marker: String): Boolean = text.contains(marker, ignoreCase = true)
}
