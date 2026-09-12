package com.qianyan.application.usecase.lcl

import com.qianyan.application.error.ErrorMapper
import com.qianyan.application.usecase.UseCase
import com.qianyan.application.usecase.writing.context.StoryWorldContextResolver
import com.qianyan.model.ChapterId
import com.qianyan.model.NovelId
import com.qianyan.model.VariantId
import com.qianyan.model.VariantScope
import com.qianyan.model.lcl.ChapterContextPack
import com.qianyan.model.lcl.ChapterContextProjector
import com.qianyan.model.lcl.NarrativeStateFold
import com.qianyan.storage.repository.ChapterRepository

/**
 * P13 LCL-B · ChapterContextPack 编译 Use Case。
 *
 * 职责：**确定性窗口投影**。读取（只读）StoryWorldContextResolver + NarrativeState（LCL-A）+ Chapter 窗口，
 * 交给纯投影器 [ChapterContextProjector] 产出 [ChapterContextPack]。
 *
 * 约束：
 *  - 只读，**不写任何状态**（不改 NarrativeState / StoryWorldContext / Story State / 不持久化 pack）；
 *  - 不调用 LLM、不加 Agent、不改 `StoryWorldContextResolver` 内部（仅调用其 resolve 作为全量基准）；
 *  - 派生数据不作为事实源；可重新编译派生结果。
 */
class ChapterContextCompileUseCases(
    private val worldContextResolver: StoryWorldContextResolver,
    private val narrativeStateUseCases: NarrativeStateUseCases,
    private val chapterRepository: ChapterRepository,
    errorMapper: ErrorMapper,
) : UseCase(errorMapper) {

    /**
     * 编译最近窗口的章节上下文包（确定性；只读，不写库）。
     *
     * @param novelId 目标小说。
     * @param variantId 目标 Variant（null=Original 作用域；只读）。
     * @param chapterId 目标章节；null 时取最近窗口最后一章。
     * @param windowSize 最近章节窗口数（默认 [ChapterContextProjector.DEFAULT_WINDOW]）。
     * @param budget 估算 token 预算（默认 [ChapterContextProjector.DEFAULT_BUDGET_TOKENS]）。
     */
    fun compileChapterContext(
        novelId: NovelId,
        variantId: VariantId?,
        chapterId: ChapterId? = null,
        windowSize: Int = ChapterContextProjector.DEFAULT_WINDOW,
        budget: Int = ChapterContextProjector.DEFAULT_BUDGET_TOKENS,
    ): ChapterContextPack {
        val scope = if (variantId == null) VariantScope.ORIGINAL else VariantScope.VARIANT
        // 只读全量基准（不改 Resolver 内部）。
        val worldContext = worldContextResolver.resolve(novelId, variantId, scope)
        // 只读折叠当前叙事状态（不持久化快照；确定性 fold 纯函数）。
        val narrative = NarrativeStateFold.fold(
            narrativeStateUseCases.listNarrativeDeltas(novelId, variantId),
            novelId,
            variantId,
        )
        // 最近窗口章节（order 升序，取末 windowSize）。
        val allChapters = guard { chapterRepository.listByNovel(novelId, variantId) }
            .sortedBy { it.order }
        val recent = allChapters.takeLast(windowSize).map { it.chapterId }
        val targetChapter = chapterId ?: recent.lastOrNull()

        return ChapterContextProjector.compile(
            worldContext = worldContext,
            narrative = narrative,
            chapterId = targetChapter,
            recentChapterIds = recent,
            budget = budget,
        )
    }

    /**
     * 失效判定 seam：重编一次并比对 packVersion。输入变化 ⇒ 版本变化 ⇒ 判定失效（旧 pack 已过期）。
     * 派生结果本身不缓存，调用方可据此决定是否复用 / 丢弃旧 pack。
     */
    fun invalidatePack(old: ChapterContextPack, windowSize: Int = ChapterContextProjector.DEFAULT_WINDOW): Boolean {
        val fresh = compileChapterContext(old.novelId, old.variantId, old.chapterId, windowSize, old.tokenBudgetGuard.budget.toInt())
        return old.packVersion != fresh.packVersion
    }
}