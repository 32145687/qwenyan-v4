package com.qianyan.application.usecase.writing.knowledgeupdate

import com.qianyan.model.knowledge.CandidateKnowledgeChange
import com.qianyan.model.knowledge.KnowledgeOperation
import com.qianyan.model.VariantScope

/**
 * 确定性知识候选校验（P11.5,Truth Enforcement）。
 *
 * LLM 只提候选；是否可落地由本 Validator 的确定性规则决定。**相同输入 → 相同裁决**，不调 LLM。
 *
 * 规则（当前项目实际能力为限）：
 *  - 空 target / 空 content / 置信度无效 → REJECT；
 *  - judges **immutable canon 保护**：scope=ORIGINAL（Original 只读基座）下，
 *    只允许 ADD（新增知识沉淀），**禁止 UPDATE / REMOVE**（不得覆盖或删除 canon）→ REJECT；
 *  - scope=VARIANT：ADD / UPDATE / REMOVE 均接受（Variant 可写语义）；
 *  - 候选列表内 changeId 重复 → REJECT 后续重复项。
 *  - 目标存在性 / timeline 冲突：当前 Domain 无对应持久化索引，不在此做（阶段边界，不臆造）。
 */
object KnowledgeValidator {

    /** 对候选逐条裁决，返回接受/拒绝列表（确定性）。 */
    fun validate(changes: List<CandidateKnowledgeChange>): ValidatedKnowledgeUpdate {
        val accepted = mutableListOf<CandidateKnowledgeChange>()
        val rejected = mutableListOf<RejectedChange>()
        val seen = mutableSetOf<String>()
        for (c in changes) {
            val reason = check(c, seen)
            if (reason == null) {
                accepted += c
            } else {
                rejected += RejectedChange(c, reason)
            }
        }
        return ValidatedKnowledgeUpdate(accepted, rejected)
    }

    /** 返回 null 表示接受；否则返回拒绝原因。 */
    private fun check(c: CandidateKnowledgeChange, seen: MutableSet<String>): String? {
        if (c.target.isBlank()) return "target 为空"
        if (c.content.isBlank()) return "content 为空"
        if (c.confidence < 0f || c.confidence > 1f) return "confidence 超出 [0,1]"
        if (!seen.add(c.changeId)) return "changeId 重复: ${c.changeId}"
        // immutable canon：Original 只读，禁止 UPDATE/REMOVE（不得覆盖/删除 canon）。
        return when {
            c.scope == VariantScope.ORIGINAL && c.operation == KnowledgeOperation.UPDATE ->
                "immutable canon: Original 不允许 UPDATE"
            c.scope == VariantScope.ORIGINAL && c.operation == KnowledgeOperation.REMOVE ->
                "immutable canon: Original 不允许 REMOVE"
            else -> null
        }
    }
}

/** 确定性校验结果：接受列表 + 拒绝列表。 */
data class ValidatedKnowledgeUpdate(
    val accepted: List<CandidateKnowledgeChange>,
    val rejected: List<RejectedChange>,
) {
    val hasRejected: Boolean get() = rejected.isNotEmpty()
}

/** 被确定性拒绝的单条候选及原因。 */
data class RejectedChange(
    val change: CandidateKnowledgeChange,
    val reason: String,
)