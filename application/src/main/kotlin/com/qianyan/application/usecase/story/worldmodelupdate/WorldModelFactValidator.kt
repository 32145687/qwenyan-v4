package com.qianyan.application.usecase.story.worldmodelupdate

import com.qianyan.model.worldmodel.CandidateFactKind
import com.qianyan.model.worldmodel.CandidateFactOperation

/**
 * 确定性候选事实校验（Phase 3 · v3.1 §12.8「复用 KnowledgeValidator 的校验范式；不新建第二套校验体系」）。
 *
 * LLM 只提候选；是否可落地由本 Validator 的确定性规则决定。**相同输入 → 相同裁决**，不调 LLM。
 *
 * 规则（**实现范围**为限；kind / operation 两条例外规则不是 §12 明文要求，属 Architecture Closure U3 待裁决项）：
 *  - 空 factId / name / summary → REJECT；
 *  - 来源片段校验（Truth Enforcement，§12.8「来源引用是写入的必要条件」）：excerpt **必填**，
 *    且必须是提交后正文的**逐字子串**，否则 REJECT（来源引用可追溯，不得伪造、不得留空）；
 *  - kind != EVENT → REJECT（当前实现只落地事件事实；§12 未限定 kind）；
 *  - operation != ADD → REJECT（UPDATE / REMOVE 的落地语义 §12 未定义；候选仍记录可追溯）；
 *  - 批次内 factId 重复 → REJECT 后续重复项。
 *
 * 冲突判定（与现有 World Model 的比较）**不在本对象**：它需要既有事实索引（[WorldModelUpdateUseCases]
 * 用 StoryStateRepository 的现有事件作 Index），按 novel + variant + scope + name 判 Conflict。
 */
object WorldModelFactValidator {

    /** 确定性校验结果：接受列表 + 拒绝列表（与 `ValidatedKnowledgeUpdate` 同型）。 */
    data class ValidatedFacts(
        val accepted: List<ExtractedFact>,
        val rejected: List<RejectedFact>,
    ) {
        val hasRejected: Boolean get() = rejected.isNotEmpty()
    }

    /** 被确定性拒绝的单条候选及原因。 */
    data class RejectedFact(val fact: ExtractedFact, val reason: String)

    fun validate(facts: List<ExtractedFact>, resultingContent: String): ValidatedFacts {
        val accepted = mutableListOf<ExtractedFact>()
        val rejected = mutableListOf<RejectedFact>()
        val seen = mutableSetOf<String>()
        for (f in facts) {
            val reason = check(f, resultingContent, seen)
            if (reason == null) {
                accepted += f
            } else {
                rejected += RejectedFact(f, reason)
            }
        }
        return ValidatedFacts(accepted, rejected)
    }

    /** 返回 null 表示接受；否则返回拒绝原因。 */
    private fun check(f: ExtractedFact, resultingContent: String, seen: MutableSet<String>): String? {
        if (f.factId.isBlank()) return "factId 为空"
        if (f.name.isBlank()) return "name 为空"
        if (f.summary.isBlank()) return "summary 为空"
        if (!seen.add(f.factId)) return "factId 重复: ${f.factId}"
        if (f.kind != CandidateFactKind.EVENT) return "kind 超出当前实现范围（§12 未限定 kind）: ${f.kind}"
        if (f.operation != CandidateFactOperation.ADD) {
            return "operation 超出当前实现范围（§12 未定义非 ADD 的落地语义）: ${f.operation}"
        }
        if (f.excerpt.isBlank()) return "excerpt 为空（来源正文片段是写入的必要条件）"
        if (!resultingContent.contains(f.excerpt)) {
            return "来源片段必须逐字包含于提交后正文（不可伪造来源引用）"
        }
        return null
    }
}
