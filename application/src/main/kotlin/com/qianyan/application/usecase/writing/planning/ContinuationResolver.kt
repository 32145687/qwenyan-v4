package com.qianyan.application.usecase.writing.planning

import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.application.error.ErrorMapper
import com.qianyan.application.usecase.UseCase
import com.qianyan.model.NovelId
import com.qianyan.model.VariantScope
import com.qianyan.model.context.UserWritingRequest
import com.qianyan.model.story.Chapter
import com.qianyan.model.story.ContinuationReference
import com.qianyan.model.writing.Draft
import com.qianyan.model.writing.DraftStatus
import com.qianyan.storage.repository.ChapterRepository
import com.qianyan.storage.repository.DraftRepository

/**
 * Continuation 来源解析与校验（P12.1.3）。
 *
 * 把显式 [ContinuationReference]（sourceChapterId + sourceDraftId）解析为真实的
 * source [Chapter] + source Final [Draft]，并在调用 PlannerAgent 之前完成全部确定性校验。
 * 任何校验失败 → 类型化业务错误（缺实体 → [ApplicationError.EntityNotFound]；其余 → 
 * [ApplicationError.InvalidContinuationSource]），绝不抛普通 RuntimeException。
 *
 * 校验顺序（除 EntityNotFound 外全部为 [ApplicationError.InvalidContinuationSource]）：
 *  1. Final Draft 校验：source Draft 必须是系统定义的最终状态（DraftStatus.FINAL）；
 *  2. Chapter/Draft identity + lineage：source Draft 必须属于 source Chapter（draft.chapterId == chapter.chapterId）；
 *  3. Novel isolation：chapter.novelId == draft.novelId == request.baseNovelId；
 *  4. Variant isolation：chapter.variantId == draft.variantId == request.variantId；
 *  5. Original / Variant scope 不得混淆：结合 request.variantId 推导的有效 scope 必须等于 source Chapter 的 scope。
 *
 * 本类只读仓储，不做任何持久化；PlannerAgent 不查询数据库、不判断 scope、不猜 continuation。
 */
class ContinuationResolver(
    private val chapterRepository: ChapterRepository,
    private val draftRepository: DraftRepository,
    errorMapper: ErrorMapper,
) : UseCase(errorMapper) {

    /**
     * 校验并解析解 [ContinuationReference]，返回已解析的 [ResolvedContinuation]。
     * 校验失败抛类型化业务错误，不在 PlannerAgent 之前留下任何未决边界。
     */
    fun resolve(request: UserWritingRequest, reference: ContinuationReference): ResolvedContinuation {
        val chapter = guard { chapterRepository.findById(reference.sourceChapterId) }
            ?: throw ApplicationException(
                ApplicationError.EntityNotFound("continuation source Chapter 不存在: ${reference.sourceChapterId.value}"),
            )
        val draft = guard { draftRepository.getById(reference.sourceDraftId) }
            ?: throw ApplicationException(
                ApplicationError.EntityNotFound("continuation source Draft 不存在: ${reference.sourceDraftId.value}"),
            )

        // 1) Final Draft 校验：只有系统定义的最终状态才可作为续篇来源
        if (draft.status != DraftStatus.FINAL) {
            throw ApplicationException(
                ApplicationError.InvalidContinuationSource(
                    "continuation source Draft(${draft.draftId.value}) 非 FINAL（当前 status=${draft.status}），不可作为续篇来源",
                ),
            )
        }

        // 2) Chapter/Draft identity + lineage：source Draft 必须属于 source Chapter
        if (draft.chapterId != chapter.chapterId) {
            throw ApplicationException(
                ApplicationError.InvalidContinuationSource(
                    "continuation source Draft(${draft.draftId.value}) 不属于 Chapter(${chapter.chapterId.value}) 的 lineage（draft.chapterId=${draft.chapterId?.value ?: "null"}）",
                ),
            )
        }

        // 3) Novel isolation：source Chapter / source Draft / request 必须是同一 Novel
        val requestNovelId = request.baseNovelId?.let { NovelId(it.value) }
        if (chapter.novelId != draft.novelId || (requestNovelId != null && chapter.novelId != requestNovelId)) {
            throw ApplicationException(
                ApplicationError.InvalidContinuationSource(
                    "continuation source 的 Novel 归属不一致（chapter=${chapter.novelId.value}, draft=${draft.novelId.value}, request=${requestNovelId?.value ?: "?"}）",
                ),
            )
        }

        // 4) Variant isolation：source Chapter / source Draft / request 必须是同一 Variant（同一为 null/同一 VariantId）
        if (chapter.variantId != draft.variantId || (request.variantId != null && chapter.variantId != request.variantId)) {
            throw ApplicationException(
                ApplicationError.InvalidContinuationSource(
                    "continuation source 的 Variant 归属不一致（chapter=${chapter.variantId?.value ?: "null"}, draft=${draft.variantId?.value ?: "null"}, request=${request.variantId?.value ?: "null"}）",
                ),
            )
        }

        // 5) Original / Variant scope 不得混淆：按 request.variantId 推导有效 scope，须与 source Chapter 一致
        val effectiveScope = if (request.variantId == null) VariantScope.ORIGINAL else VariantScope.VARIANT
        if (chapter.scope != effectiveScope) {
            throw ApplicationException(
                ApplicationError.InvalidContinuationSource(
                    "continuation source Chapter(${chapter.chapterId.value}) scope=${chapter.scope} 与 request 有效 scope=${effectiveScope} 不一致，禁止 Original/Variant 作用域混淆",
                ),
            )
        }

        return ResolvedContinuation(reference = reference, sourceChapter = chapter, sourceFinalDraft = draft)
    }
}

/**
 * 已解析的续篇来源（P12.1.3）：reference + 其指向的真实 source Chapter + source Final Draft。
 * 仅作为已组装 Context（reference-only 执行时解析），不持久化正文。
 */
data class ResolvedContinuation(
    val reference: ContinuationReference,
    val sourceChapter: Chapter,
    val sourceFinalDraft: Draft,
)