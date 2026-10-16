package com.qianyan.application.usecase.story.worldmodelupdate

import com.qianyan.model.VariantId
import com.qianyan.model.VariantScope
import com.qianyan.model.worldmodel.CandidateFactKind
import com.qianyan.model.worldmodel.CandidateFactOperation
import kotlinx.serialization.Serializable

/**
 * Agent 提取出的单条候选事实（Phase 3 · Extract 产物）。
 *
 * 结构边界由 [WorldModelUpdateParser] 保证；**裁决**（是否可写入 / 是否冲突）由
 * [WorldModelFactValidator] 与既有 World Model 索引决定——LLM 输出永不直接入库。
 * 作用域（novelId / variantId / scope / chapterId / commit 来源）由调用上下文注入，不由 LLM 生成。
 */
data class ExtractedFact(
    val factId: String,
    val kind: CandidateFactKind,
    val operation: CandidateFactOperation,
    /** 身份键（EVENT = 事件名；冲突判定用）。 */
    val name: String,
    /** 候选事实内容摘要。 */
    val summary: String,
    /** 来源正文片段（Phase 3 校验要求其包含于提交后正文）。 */
    val excerpt: String,
    /** 提取依据（仅记录）。 */
    val reason: String,
) {
    /** Agent 输出的可序列化载体（Parser ↔ DTO）。 */
    @Serializable
    data class Dto(
        val factId: String,
        val kind: String = CandidateFactKind.EVENT.name,
        val operation: String,
        val name: String,
        val summary: String,
        val excerpt: String = "",
        val reason: String = "",
    )

    @Serializable
    data class BatchDto(val facts: List<Dto> = emptyList())
}

/** 一次 onCommit 执行的上下文（来自 CommitHistory 条目；LLM 不提供任何作用域信息）。 */
data class WorldModelUpdateContext(
    val historyId: String,
    val commitId: String,
    val novelId: com.qianyan.model.NovelId,
    val variantId: VariantId?,
    val scope: VariantScope,
    val chapterId: com.qianyan.model.ChapterId?,
    /** 提交前正文。 */
    val previousContent: String,
    /** 提交后正文。 */
    val resultingContent: String,
)
