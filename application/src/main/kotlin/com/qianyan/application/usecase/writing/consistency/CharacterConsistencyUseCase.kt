package com.qianyan.application.usecase.writing.consistency

import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.application.error.ErrorMapper
import com.qianyan.application.usecase.UseCase
import com.qianyan.application.usecase.canonical.CanonicalReadUseCases
import com.qianyan.application.usecase.chapter.ChapterUseCases
import com.qianyan.application.usecase.writing.context.StoryWorldContextResolver
import com.qianyan.model.ChapterId
import com.qianyan.model.NovelId
import com.qianyan.model.VariantId
import com.qianyan.model.VariantScope
import com.qianyan.model.characterconsistency.CharacterConsistencyMissingInput
import com.qianyan.model.characterconsistency.CharacterConsistencyResult
import com.qianyan.model.characterconsistency.CharacterConsistencyScope
import com.qianyan.model.context.ContextPack
import com.qianyan.model.context.ContextSourceKind
import com.qianyan.model.story.ChapterPlan

/**
 * CharacterConsistency 只读应用入口（T-2B-2）。
 *
 * 输入来源（D-21 / O-26）：
 *  - currentDraft：**只取 ContextPack 的 DRAFT item**（其 content 来自 DraftSource，原文不截断）；
 *  - priorCanonical：按 `listByNovel(novelId, variantId)` 取前 1–3 章，再经
 *    [CanonicalReadUseCases.committedDraft] 解析；禁止用最新 Draft 反推；
 *  - worldContext：只调用一次 [StoryWorldContextResolver.resolve]，同一实例同时传给 Agent 与 Validator。
 *
 * 只读：不调用作者保存 / Commit / Knowledge Update / Canonical 写。
 */
internal class CharacterConsistencyUseCase(
    private val chapters: ChapterUseCases,
    private val canonicalRead: CanonicalReadUseCases,
    private val storyWorldContextResolver: StoryWorldContextResolver,
    private val agent: CharacterConsistencyAgent,
    private val validator: CharacterConsistencyValidator,
    errorMapper: ErrorMapper,
) : UseCase(errorMapper) {

    fun check(
        novelId: NovelId,
        variantId: VariantId?,
        chapterId: ChapterId,
        contextPack: ContextPack?,
        plan: ChapterPlan? = null,
        scope: CharacterConsistencyScope = CharacterConsistencyScope.CURRENT_CHAPTER,
    ): CharacterConsistencyResult = try {
        validatePackScope(contextPack, novelId, variantId, chapterId)
        val current = chapters.findById(chapterId)
            ?: throw ApplicationException(ApplicationError.EntityNotFound("Chapter 不存在: ${chapterId.value}"))
        if (current.novelId != novelId) {
            throw ApplicationException(
                ApplicationError.InvalidOperation(
                    "Chapter(${chapterId.value}) 属于 Novel(${current.novelId.value})，与请求 Novel(${novelId.value}) 不一致",
                ),
            )
        }
        if (current.variantId != variantId) {
            throw ApplicationException(
                ApplicationError.VariantMismatch(
                    "Chapter(${chapterId.value}) variant=${current.variantId?.value ?: "ORIGINAL"} 与请求 variant=${variantId?.value ?: "ORIGINAL"} 不一致",
                ),
            )
        }

        val currentDraft = resolveCurrentDraft(contextPack, chapterId)
        val prior = resolvePriorCanonical(novelId, variantId, chapterId)
        val worldContext = storyWorldContextResolver.resolve(
            novelId = novelId,
            variantId = variantId,
            scope = if (variantId == null) VariantScope.ORIGINAL else VariantScope.VARIANT,
            worldSummary = "",
        )
        val coverage = CharacterConsistencyInputCoverage(
            currentDraftFromContextPack = currentDraft != null,
            planProvided = plan != null,
            priorCanonicalCount = prior.chapters.size,
            priorCanonicalGapChapterIds = prior.gaps,
        )
        val missingInputs = buildMissingInputs(currentDraft, prior, worldContext.characters.isEmpty())
        val input = CharacterConsistencyInput(
            novelId = novelId,
            variantId = variantId,
            chapterId = chapterId,
            scope = scope,
            contextPackId = contextPack?.packId,
            currentDraft = currentDraft,
            plan = plan,
            priorCanonical = prior.chapters,
            coverage = coverage,
            missingInputs = missingInputs,
        )
        val raw = agent.check(input, worldContext)
        validator.validate(input, worldContext, raw)
    } catch (e: ApplicationException) {
        throw e
    } catch (e: CharacterConsistencyException) {
        throw errorMapper.map(e)
    } catch (t: Throwable) {
        throw errorMapper.map(t)
    }

    private fun validatePackScope(
        contextPack: ContextPack?,
        novelId: NovelId,
        variantId: VariantId?,
        chapterId: ChapterId,
    ) {
        if (contextPack == null) return
        if (contextPack.novelId != novelId) {
            throw ApplicationException(
                ApplicationError.InvalidOperation(
                    "ContextPack.novelId=${contextPack.novelId.value} 与请求 novelId=${novelId.value} 不一致",
                ),
            )
        }
        if (contextPack.activeVariantId != variantId) {
            throw ApplicationException(
                ApplicationError.VariantMismatch(
                    "ContextPack.variant=${contextPack.activeVariantId?.value ?: "ORIGINAL"} 与请求 variant=${variantId?.value ?: "ORIGINAL"} 不一致",
                ),
            )
        }
        val focus = contextPack.request.focusChapterId
        if (focus != null && focus != chapterId) {
            throw ApplicationException(
                ApplicationError.InvalidOperation(
                    "ContextPack.focusChapterId=${focus.value} 与请求 chapterId=${chapterId.value} 不一致",
                ),
            )
        }
    }

    /** 当前 Draft 只从 ContextPack DRAFT item 取；无 item → null（不 fallback 最新 Draft）。 */
    private fun resolveCurrentDraft(
        contextPack: ContextPack?,
        chapterId: ChapterId,
    ): CharacterConsistencyDraft? {
        val item = contextPack?.items?.firstOrNull {
            it.source == ContextSourceKind.DRAFT && it.ref?.chapterId == chapterId
        } ?: return null
        val draftId = item.ref?.draftId
            ?: throw ApplicationException(
                ApplicationError.InvalidOperation("ContextPack DRAFT item 缺少 draftId（无法绑定当前稿）"),
            )
        return CharacterConsistencyDraft(draftId = draftId, content = item.content)
    }

    /**
     * 前章 Canonical：`listByNovel(novelId, variantId)` 提供 variant 过滤与 order；
     * 取当前章前最多 3 章，从最近向前收集**连续** Canonical 链；遇到第一处缺失即记录 gap 并停止。
     * 不做最新 Draft 反推。
     */
    private fun resolvePriorCanonical(
        novelId: NovelId,
        variantId: VariantId?,
        chapterId: ChapterId,
    ): PriorCanonicalResult {
        val timeline = chapters.listByNovel(novelId, variantId)
        val index = timeline.indexOfFirst { it.chapterId == chapterId }
        if (index < 0) {
            throw ApplicationException(
                ApplicationError.InvalidOperation("Chapter(${chapterId.value}) 不在 Novel(${novelId.value}) 的章节序列中"),
            )
        }
        val preceding = timeline.take(index).takeLast(MAX_PRIOR_CANONICAL)
        val prior = mutableListOf<CharacterConsistencyCanonicalChapter>()
        val gaps = mutableListOf<ChapterId>()
        for (chapter in preceding.asReversed()) {
            val canonical = canonicalRead.committedDraft(chapter.chapterId)
            if (canonical == null) {
                gaps += chapter.chapterId
                break
            }
            prior.add(
                0,
                CharacterConsistencyCanonicalChapter(
                    chapterId = chapter.chapterId,
                    chapterOrder = chapter.order,
                    draftId = canonical.draftId,
                    content = canonical.content,
                    variantId = chapter.variantId,
                ),
            )
        }
        return PriorCanonicalResult(chapters = prior, gaps = gaps, hadPreceding = preceding.isNotEmpty())
    }

    private fun buildMissingInputs(
        currentDraft: CharacterConsistencyDraft?,
        prior: PriorCanonicalResult,
        noCharacterProfiles: Boolean,
    ): List<CharacterConsistencyMissingInput> = buildList {
        if (currentDraft == null || currentDraft.content.isBlank()) {
            add(CharacterConsistencyMissingInput.CURRENT_DRAFT)
        }
        if (prior.hadPreceding && prior.chapters.isEmpty()) {
            add(CharacterConsistencyMissingInput.PRIOR_CANONICAL)
        }
        if (noCharacterProfiles) {
            add(CharacterConsistencyMissingInput.CHARACTER_PROFILE)
        }
    }.distinct().sortedBy { it.ordinal }

    private data class PriorCanonicalResult(
        val chapters: List<CharacterConsistencyCanonicalChapter>,
        val gaps: List<ChapterId>,
        val hadPreceding: Boolean,
    )

    private companion object {
        const val MAX_PRIOR_CANONICAL = 3
    }
}
