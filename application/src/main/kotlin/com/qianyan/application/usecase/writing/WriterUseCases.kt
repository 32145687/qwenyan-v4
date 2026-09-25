package com.qianyan.application.usecase.writing

import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.application.error.ErrorMapper
import com.qianyan.application.usecase.UseCase
import com.qianyan.model.ChapterId
import com.qianyan.model.DraftId
import com.qianyan.model.writing.Draft
import com.qianyan.model.writing.DraftFormat
import com.qianyan.storage.repository.DraftRepository
import kotlinx.datetime.Clock

/**
 * P20-P3 · Writer（章节正文编辑 / 保存）Use Case。
 *
 * 为 Android Writer 提供**最小** Draft 读取 / 保存入口（UI 不直接触碰 [DraftRepository]）：
 *  - [latestDraft]：章节最新 Draft（只读；无 → null）；
 *  - [saveContent]：保存用户编辑后的正文（身份 / lineage / status / format 全部不变，仅 content + updatedAt）；
 *  - [stampControlledMarkdown]：把**新产生**的 Draft 标记为受控 Markdown v1（P2/FD-1 冻结：新创建 =
 *    [DraftFormat.CONTROLLED_MARKDOWN]）。
 *
 * 边界（架构硬约束）：
 *  - **不重建写作 Pipeline**：AI 生成复用既有 Planning / Writing / Critique / Revision Use Case（见 [WriterFacade]）；
 *  - **不 Decision**：本类无 DecisionModel / DecisionPolicy 依赖；不重新计算决策；
 *  - 旧 Draft（`format=null`）保持兼容：保存时**不静默迁移**格式；
 *  - 不触碰 SQLDelight（只经 [DraftRepository] 接口）。
 */
class WriterUseCases(
    private val draftRepository: DraftRepository,
    errorMapper: ErrorMapper,
) : UseCase(errorMapper) {

    /**
     * 章节最新 Draft；不存在返回 null。
     * UI 据此判定编辑区是否可保存（尚无 Draft → 需先由 AI 生成初稿，不伪造 Draft）。
     */
    fun latestDraft(chapterId: ChapterId): Draft? = guard { draftRepository.latestByChapter(chapterId) }

    /** 按 draftId 读取单个 Draft；不存在返回 null。 */
    fun draft(draftId: DraftId): Draft? = guard { draftRepository.getById(draftId) }

    /**
     * 保存用户编辑后的正文：**只改** content + updatedAt。
     * draftId / novelId / variantId / scope / chapterId / planId / previousDraftId / status / format 全部保持，
     * 旧 Draft（format=null）保持 null（legacy 兼容，不静默迁移受控 Markdown）。
     *
     * @throws [ApplicationError.EntityNotFound] Draft 不存在。
     */
    fun saveContent(draftId: DraftId, content: String): Draft {
        val current = guard { draftRepository.getById(draftId) }
            ?: throw ApplicationException(ApplicationError.EntityNotFound("Draft 不存在: ${draftId.value}"))
        val saved = current.copy(content = content, updatedAt = Clock.System.now())
        guard { draftRepository.save(saved) }
        return saved
    }

    /**
     * P2/FD-1：**新产生**的 Draft 标记为受控 Markdown v1（`markdown:controlled:v1`）。
     * 已有 format 的 Draft 原样返回（幂等，不覆盖既有格式）。
     */
    fun stampControlledMarkdown(draft: Draft): Draft {
        if (draft.format != null) return draft
        val stamped = draft.copy(format = DraftFormat.CONTROLLED_MARKDOWN, updatedAt = Clock.System.now())
        guard { draftRepository.save(stamped) }
        return stamped
    }
}