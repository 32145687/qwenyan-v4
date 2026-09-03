package com.qianyan.application.usecase.writing

import com.qianyan.model.DraftId
import com.qianyan.model.writing.Draft
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * WRITING / REVISION Checkpoint 快照编解码（P11.3 / P12.0 P1-2）。
 *
 * **P1-2：不再复制完整 Draft 正文** —— Checkpoint 只保存「恢复索引/引用」（draftId + 必要关联 id），
 * 正文由 [com.qianyan.storage.repository.DraftRepository]（ChapterDraft 表）单一负责。
 * 恢复流程：Checkpoint → [decodeReference] 得 draftId → DraftRepository.getById → Draft。
 * 这消除正文双重持久化，并缩小 Android CursorWindow 暴露面。不新增数据库表。
 */
object WritingSnapshot {

    const val STAGE = "WRITING"

    private const val KEY_TYPE = "type"
    private const val KEY_DRAFT_ID = "draftId"
    private const val KEY_CHAPTER_ID = "chapterId"
    private const val KEY_PLAN_ID = "planId"

    /** 把 [Draft] 编码为 Checkpoint 可见的结构化引用 JsonObject（含 type 标签，**不含正文**）。 */
    fun encode(draft: Draft): JsonObject = buildJsonObject {
        put(KEY_TYPE, STAGE)
        put(KEY_DRAFT_ID, draft.draftId.value)
        draft.chapterId?.let { put(KEY_CHAPTER_ID, it.value) }
        draft.planId?.let { put(KEY_PLAN_ID, it.value) }
    }

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