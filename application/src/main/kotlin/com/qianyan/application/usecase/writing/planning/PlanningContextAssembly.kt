package com.qianyan.application.usecase.writing.planning

import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.application.error.ErrorMapper
import com.qianyan.application.usecase.UseCase
import com.qianyan.application.usecase.writing.context.StoryWorldContextResolver
import com.qianyan.model.NovelId
import com.qianyan.model.context.UserWritingRequest
import com.qianyan.model.story.ContinuationReference
import com.qianyan.storage.repository.NovelRepository
import com.qianyan.storage.repository.VocabularyRepository

/**
 * Planning Context Assembly（P11.2 + P11.6）。
 *
 * 把用户创作要求 + 必要的既有领域信息组装为最小 [PlanningContext]，供 Planner Agent 使用。
 * 只收集 Planning 真正需要的信息：请求本体 + Novel / Variant 背景 + 当前作用域下可见的
 * Memory / Vocabulary（Character 无持久化仓储，保持空投影，见 Known Issue）。
 *
 * P11.6：Memory / Story World 经确定性 [StoryWorldContextResolver] 解析为 canon 优先、按 layer 分层的
 * [com.qianyan.model.context.StoryWorldContext]，并以确定性顺序投影到 [PlanningContext.memories]；
 * PlannerAgent 仍只拿组装好的 Context，**不直读 Repository**。
 *
 * 复用已有仓储契约（[NovelRepository] / [VocabularyRepository]）与 [MemoryRepository]（经 Resolver），
 * 不新增仓储、不触碰 SQLDelight。错误类型化：缺 Novel / Variant → EntityNotFound；
 * 请求缺 baseNovelId → InvalidOperation；Variant 与 Novel 不匹配 → VariantMismatch。
 */
class PlanningContextAssembly(
    private val novelRepository: NovelRepository,
    private val vocabularyRepository: VocabularyRepository,
    private val worldContextResolver: StoryWorldContextResolver,
    errorMapper: ErrorMapper,
) : UseCase(errorMapper) {

    /** 无续篇来源组装（第一章 / Writing 复用入口）：continuation 三个字段均为 null。 */
    fun assemble(request: UserWritingRequest): PlanningContext = assemble(request, null, null)

    /**
     * P12.1.3：带显式 [ContinuationReference] 组装。
     * [resolved] 为 [ContinuationResolver] 已校验并解析的源头（sourceChapter + sourceFinalDraft），
     * Assembly 只做**投影**，不重新查询数据库；reference-only，不复制正文。
     */
    fun assemble(request: UserWritingRequest, continuationReference: ContinuationReference?, resolved: ResolvedContinuation?): PlanningContext {
        // 1) 定位目标 Novel：baseNovelId 必填（P11.2 Planning 必须有明确作用域）
        val baseNovelId = request.baseNovelId
            ?: throw ApplicationException(
                ApplicationError.InvalidOperation("UserWritingRequest 缺少 baseNovelId，无法定位规划目标 Novel"),
            )
        val novel = guard { novelRepository.getNovel(NovelId(baseNovelId.value)) }
            ?: throw ApplicationException(
                ApplicationError.EntityNotFound("Novel 不存在: ${baseNovelId.value}"),
            )

        // 2) Variant（可选）：存在则校验属于该 Novel
        val variantId = request.variantId
        val variant = variantId?.let { vid ->
            guard { novelRepository.getVariant(vid) }
                ?: throw ApplicationException(ApplicationError.EntityNotFound("Variant 不存在: ${vid.value}"))
        }
        if (variant != null && variant.novelId.value != novel.novelId.value) {
            throw ApplicationException(
                ApplicationError.VariantMismatch("Variant ${variant.variantId.value} 不属于 Novel ${novel.novelId.value}"),
            )
        }

        // 3) 当前作用域可见 Vocabulary
        val vocabulary = if (variantId != null) {
            guard { vocabularyRepository.findEntriesByVariant(variantId) }
        } else {
            guard { vocabularyRepository.findEntriesByNovel(novel.novelId) }
        }

        // 4) P11.6：确定性 Story World Context（canon/layer 分层，canon 优先），并投影到 memories
        val scope = if (variantId == null) com.qianyan.model.VariantScope.ORIGINAL else com.qianyan.model.VariantScope.VARIANT
        val worldContext = worldContextResolver.resolve(
            novelId = novel.novelId,
            variantId = variantId,
            scope = scope,
            worldSummary = listOfNotNull(novel.title, novel.synopsis.takeIf { it.isNotBlank() }).joinToString("\n"),
        )

        // 5) 组装最小投影（Character 无持久化仓储 → 空列表，见 Known Issue）
        return PlanningContext(
            request = request,
            novelId = novel.novelId,
            variantId = variantId,
            novelTitle = novel.title,
            novelGenre = novel.genre,
            novelSynopsis = novel.synopsis,
            variantName = variant?.name ?: "",
            variantDirective = listOfNotNull(
                variant?.blueprint?.note,
                variant?.scopeSpec?.directive,
            ).joinToString("\n"),
            characters = emptyList(),
            memories = worldContext.orderedVisible,
            vocabulary = vocabulary.map { VocabularyLite(canonical = it.canonical, aliases = it.aliases, replacement = it.replacement) },
            worldContext = worldContext,
            continuationReference = continuationReference,
            sourceChapter = resolved?.sourceChapter,
            sourceFinalDraft = resolved?.sourceFinalDraft,
        )
    }
}