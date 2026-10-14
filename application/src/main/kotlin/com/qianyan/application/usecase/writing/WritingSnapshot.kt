package com.qianyan.application.usecase.writing

import com.qianyan.model.DraftId
import com.qianyan.model.workingdraft.WorkingDraft
import com.qianyan.model.workingdraft.WorkingDraftId
import com.qianyan.model.writing.Draft
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * WRITING / REVISION Checkpoint 快照编解码（P11.3 / P12.0 P1-2 / P1-02c+d）。
 *
 * **P1-2：不再复制完整 Draft 正文** —— Checkpoint 只保存「恢复索引/引用」（draftId + 必要关联 id），
 * 正文由 [com.qianyan.storage.repository.DraftRepository]（ChapterDraft 表）单一负责。
 *
 * **P1-02c+d（AI 产出离开章节 Draft 表）**：AI 写作链的工作产物是 `WorkingDraft`（内存工作区，独立
 * `WorkingDraftId`），Checkpoint 以**显式类型化**的工作引用 `workingDraftId` 承载，**不得**把
 * `WorkingDraftId` 写进 `draftId` 键（两者是不同对象类型）。恢复侧据此走
 * [WritingExecutionUseCase.workingDraftFrom]，**不**用 WorkingDraftId 查询 DraftRepository。
 * 既有 `draftId` 键保留（历史 Checkpoint 与遗留 Draft 路径的读取兼容）。
 */
object WritingSnapshot {

    const val STAGE = "WRITING"

    /**
     * P1-02c+d：AI 工作产物引用的类型前缀（`WORKING:<workingDraftId>`）。
     * 用于 `WorkflowStep.resultReference` 与 WRITING/REVISION Checkpoint —— **单一来源**，与 DraftId 严格区分。
     */
    const val WORKING_REF_PREFIX = "WORKING:"

    private const val KEY_TYPE = "type"
    private const val KEY_DRAFT_ID = "draftId"

    /** P1-02c+d：AI 工作产物引用（`WorkingDraftId`，与 DraftId 严格区分）。 */
    private const val KEY_WORKING_DRAFT_ID = "workingDraftId"
    private const val KEY_CHAPTER_ID = "chapterId"
    private const val KEY_PLAN_ID = "planId"

    /** 把 [Draft] 编码为 Checkpoint 可见的结构化引用 JsonObject（含 type 标签，**不含正文**）。 */
    fun encode(draft: Draft): JsonObject = encode(draft, emptyList())

    /** P20-P5：额外写入本任务使用的 [DecisionPolicy] 快照（空列表则不写入该键；旧读取兼容）。 */
    fun encode(draft: Draft, policies: List<com.qianyan.model.decision.DecisionPolicy>): JsonObject = buildJsonObject {
        put(KEY_TYPE, STAGE)
        put(KEY_DRAFT_ID, draft.draftId.value)
        draft.chapterId?.let { put(KEY_CHAPTER_ID, it.value) }
        draft.planId?.let { put(KEY_PLAN_ID, it.value) }
        putPolicies(policies)
    }

    /**
     * P1-02c+d：把 AI 工作产物（[WorkingDraft]）编码为 WRITING Checkpoint 引用。
     * 只写入类型化的工作引用与 target 章节，**不写 draftId**（不把 WorkingDraftId 冒充 DraftId）。
     */
    fun encodeWorking(
        workingDraft: WorkingDraft,
        policies: List<com.qianyan.model.decision.DecisionPolicy>,
    ): JsonObject = encodeWorkingReference(workingDraft.workingDraftId.value, workingDraft.target.chapterId, policies)

    /** P1-02c+d：按标识构造工作产物引用（供只有 id 的调用方，如 REVISION 阶段 Checkpoint）。 */
    fun encodeWorkingReference(
        workingDraftId: String,
        chapterId: com.qianyan.model.ChapterId? = null,
        policies: List<com.qianyan.model.decision.DecisionPolicy> = emptyList(),
    ): JsonObject = buildJsonObject {
        put(KEY_TYPE, STAGE)
        put(KEY_WORKING_DRAFT_ID, workingDraftId)
        chapterId?.let { put(KEY_CHAPTER_ID, it.value) }
        putPolicies(policies)
    }

    /** P1-02c+d：解码 AI 工作产物引用（无该键 = 该 Checkpoint 属遗留 Draft 路径）→ [WorkingDraftId]。 */
    fun decodeWorkingReference(snapshot: JsonObject?): WorkingDraftId? {
        if (snapshot == null) return null
        if ((snapshot[KEY_TYPE]?.jsonPrimitive?.contentOrNull ?: "") != STAGE) return null
        val id = snapshot[KEY_WORKING_DRAFT_ID]?.jsonPrimitive?.contentOrNull ?: return null
        return WorkingDraftId(id)
    }

    private fun JsonObjectBuilder.putPolicies(
        policies: List<com.qianyan.model.decision.DecisionPolicy>,
    ) {
        if (policies.isEmpty()) return
        put(
            com.qianyan.application.usecase.decision.DecisionPolicySnapshot.KEY_TYPE,
            com.qianyan.application.usecase.decision.DecisionPolicySnapshot.encode(policies),
        )
    }

    /**
     * P20-P5：从 WRITING Checkpoint 恢复 [DecisionPolicy] 快照。
     * @return `null` = 该 Checkpoint 早于 P5；列表 = 显式政策。
     */
    fun decodePolicies(snapshot: JsonObject?): List<com.qianyan.model.decision.DecisionPolicy>? =
        com.qianyan.application.usecase.decision.DecisionPolicySnapshot.decode(snapshot)

    /**
     * 从 [com.qianyan.model.task.Checkpoint.snapshot] 解码恢复索引 [DraftId]。
     * snapshot 缺失 / 非 WRITING / 结构非法 → 返回 null（调用方决定如何处理）。
     */
    fun decodeReference(snapshot: JsonObject?): DraftId? {
        if (snapshot == null) return null
        if ((snapshot[KEY_TYPE]?.jsonPrimitive?.contentOrNull ?: "") != STAGE) return null
        val id = snapshot[KEY_DRAFT_ID]?.jsonPrimitive?.contentOrNull ?: return null
        return DraftId(id)
    }
}