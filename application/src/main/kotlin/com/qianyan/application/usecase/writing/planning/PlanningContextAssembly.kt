package com.qianyan.application.usecase.writing.planning

import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.application.error.ErrorMapper
import com.qianyan.application.usecase.UseCase
import com.qianyan.application.usecase.author.AuthorContextProjection
import com.qianyan.application.usecase.writing.context.StoryWorldContextResolver
import com.qianyan.model.NovelId
import com.qianyan.model.context.ContextItem
import com.qianyan.model.context.ContextPack
import com.qianyan.model.context.ContextPayload
import com.qianyan.model.context.ContextSourceKind
import com.qianyan.model.context.FoundationPayload
import com.qianyan.model.context.NovelPayload
import com.qianyan.model.context.UserWritingRequest
import com.qianyan.model.context.VocabularyPayload
import com.qianyan.model.story.ContinuationReference
import com.qianyan.storage.repository.NovelRepository
import com.qianyan.storage.repository.StoryFoundationRepository
import com.qianyan.storage.repository.VocabularyRepository

/**
 * Planning Context Assembly（P11.2 + P11.6；Phase 4B.1 统一入口）。
 *
 * **Phase 4B.1 正式 Agent Task 入口**（[assemble] with `ContextPack`）：
 *  - `NovelAgent` 在任务中构建**一次** `ContextPack`，并把同一个 pack 传入本类；
 *  - `NOVEL` / `STORY_FOUNDATION` / `VOCABULARY` 的结构化字段**只从 pack 的 `payload` 投影**，
 *    不再独立读取相应 Repository 生成第二份 Target Context；
 *  - `characters` / `memories` 仍由 [StoryWorldContextResolver] 提供（Phase 4A J-1/D3 明确不属于本阶段 Pack 范围）；
 *  - `variantName` / `variantDirective` 不在 Pack 覆盖范围，仍从 `NovelRepository.getVariant` 读取（未覆盖字段）；
 *  - `authorContext` / continuation 保持不变；
 *  - Prompt 文本契约不变：Planner/Writer 仍消费同一组 `PlanningContext` 字段。
 *
 * **兼容入口**（[assemble] without pack）：仅用于既有非 Agent 调用与旧测试；已 `@Deprecated`，
 * 不属于正式生产链路；`application/src/main` 不得调用（source guard 保证）。
 * 其内部实现 [assembleLegacy] 保持 4B.1 之前的完整装配语义。
 */
class PlanningContextAssembly(
    private val novelRepository: NovelRepository,
    private val vocabularyRepository: VocabularyRepository,
    private val worldContextResolver: StoryWorldContextResolver,
    private val storyFoundationRepository: StoryFoundationRepository,
    private val authorContextProjection: AuthorContextProjection,
    errorMapper: ErrorMapper,
) : UseCase(errorMapper) {

    /**
     * Phase 4B.1 正式入口：消费**同一个** `ContextPack`（由 `NovelAgent` 构建一次）。
     *
     * @param contextPack 本任务的 Target Context；其 `novelId` / `activeVariantId` 必须与 [request] 一致。
     * @throws ApplicationError.InvalidOperation Pack 作用域不一致 / 缺少必需 payload / payload 类型不匹配。
     * @throws ApplicationError.EntityNotFound Variant 不存在。
     */
    fun assemble(
        request: UserWritingRequest,
        contextPack: ContextPack,
        continuationReference: ContinuationReference? = null,
        resolved: ResolvedContinuation? = null,
    ): PlanningContext {
        val baseNovelId = request.baseNovelId
            ?: throw ApplicationException(
                ApplicationError.InvalidOperation("UserWritingRequest 缺少 baseNovelId，无法定位规划目标 Novel"),
            )
        if (contextPack.novelId.value != baseNovelId.value) {
            throw ApplicationException(
                ApplicationError.InvalidOperation(
                    "ContextPack 作用域与请求不一致：pack.novelId=${contextPack.novelId.value}, request.baseNovelId=${baseNovelId.value}",
                ),
            )
        }
        val variantId = request.variantId
        if (contextPack.activeVariantId?.value != variantId?.value) {
            throw ApplicationException(
                ApplicationError.InvalidOperation(
                    "ContextPack variant 与请求不一致：pack=${contextPack.activeVariantId?.value ?: "ORIGINAL"}, " +
                        "request=${variantId?.value ?: "ORIGINAL"}",
                ),
            )
        }

        // 未覆盖字段：Variant 元数据（Pack 无 VARIANT 来源）。Novel 元数据不再读取（由 NOVEL payload 提供）。
        val variant = variantId?.let { vid ->
            guard { novelRepository.getVariant(vid) }
                ?: throw ApplicationException(ApplicationError.EntityNotFound("Variant 不存在: ${vid.value}"))
        }

        // 覆盖字段：只从 pack 的 payload 投影（禁止解析 content）。
        val novelPayload = contextPack.items
            .firstOrNull { it.source == ContextSourceKind.NOVEL }
            ?.requirePayload<NovelPayload>(ContextSourceKind.NOVEL)
            ?: throw ApplicationException(
                ApplicationError.InvalidOperation("ContextPack 缺少 NOVEL payload（不能从 content 反解析）"),
            )
        val foundationPayload = contextPack.items
            .firstOrNull { it.source == ContextSourceKind.STORY_FOUNDATION }
            ?.requirePayload<FoundationPayload>(ContextSourceKind.STORY_FOUNDATION)
        val vocabularyPayloads = contextPack.items
            .filter { it.source == ContextSourceKind.VOCABULARY }
            .map { it.requirePayload<VocabularyPayload>(ContextSourceKind.VOCABULARY) }

        val scope = if (variantId == null) com.qianyan.model.VariantScope.ORIGINAL else com.qianyan.model.VariantScope.VARIANT
        val worldContext = worldContextResolver.resolve(
            novelId = contextPack.novelId,
            variantId = variantId,
            scope = scope,
            worldSummary = listOfNotNull(
                novelPayload.title,
                novelPayload.synopsis.takeIf { it.isNotBlank() },
            ).joinToString("\n"),
        )

        val confirmedFoundation = foundationPayload?.let { f ->
            ConfirmedStoryFoundationContext(
                confirmedGenre = f.genre,
                direction = f.direction,
                audience = f.audience,
                writingPolicy = f.policy,
            )
        }

        return PlanningContext(
            request = request,
            novelId = contextPack.novelId,
            variantId = variantId,
            novelTitle = novelPayload.title,
            novelGenre = novelPayload.genre,
            novelSynopsis = novelPayload.synopsis,
            variantName = variant?.name ?: "",
            variantDirective = listOfNotNull(
                variant?.blueprint?.note,
                variant?.scopeSpec?.directive,
            ).joinToString("\n"),
            characters = worldContext.characters.map {
                CharacterLite(
                    characterId = it.characterId,
                    name = it.name,
                    personality = it.personality,
                    goals = it.goals,
                )
            },
            memories = worldContext.orderedVisible,
            vocabulary = vocabularyPayloads.map {
                VocabularyLite(canonical = it.canonical, aliases = it.aliases, replacement = it.replacement)
            },
            worldContext = worldContext,
            continuationReference = continuationReference,
            sourceChapter = resolved?.sourceChapter,
            sourceFinalDraft = resolved?.sourceFinalDraft,
            foundation = confirmedFoundation,
            authorContext = authorContextProjection.project(contextPack.novelId),
        )
    }

    @Deprecated(
        message = "仅兼容旧调用；正式 Agent Task 必须使用 assemble(request, contextPack, ...)（Phase 4B.1）。",
        replaceWith = ReplaceWith("assemble(request, contextPack)"),
    )
    fun assemble(request: UserWritingRequest): PlanningContext = assembleLegacy(request, null, null)

    @Deprecated(
        message = "仅兼容旧调用；正式 Agent Task 必须使用 assemble(request, contextPack, ...)（Phase 4B.1）。",
        replaceWith = ReplaceWith("assemble(request, contextPack, continuationReference, resolved)"),
    )
    fun assemble(
        request: UserWritingRequest,
        continuationReference: ContinuationReference?,
        resolved: ResolvedContinuation?,
    ): PlanningContext = assembleLegacy(request, continuationReference, resolved)

    /**
     * 旧完整装配（4B.1 之前语义）：仅供上述 deprecated 重载与既有 legacy use case 使用。
     * `internal` ⇒ 不是正式生产入口；`application/src/main` 中仅允许既有 legacy use case 调用。
     */
    internal fun assembleLegacy(
        request: UserWritingRequest,
        continuationReference: ContinuationReference?,
        resolved: ResolvedContinuation?,
    ): PlanningContext {
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

        // 5) P14-F.4：实时读取用户已确认的 Original Story Foundation（只读投影；未确认 → null，不影响旧流程）。
        val confirmedFoundation = guard { storyFoundationRepository.getStoryFoundation(novel.novelId) }?.let { f ->
            ConfirmedStoryFoundationContext(
                confirmedGenre = f.genre,
                direction = f.direction,
                audience = f.audience,
                writingPolicy = f.policy,
            )
        }

        // 6) 组装最小投影
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
            characters = worldContext.characters.map {
                CharacterLite(
                    characterId = it.characterId,
                    name = it.name,
                    personality = it.personality,
                    goals = it.goals,
                )
            },
            memories = worldContext.orderedVisible,
            vocabulary = vocabulary.map { VocabularyLite(canonical = it.canonical, aliases = it.aliases, replacement = it.replacement) },
            worldContext = worldContext,
            continuationReference = continuationReference,
            sourceChapter = resolved?.sourceChapter,
            sourceFinalDraft = resolved?.sourceFinalDraft,
            foundation = confirmedFoundation,
            authorContext = authorContextProjection.project(novel.novelId),
        )
    }

    /** 消费侧 payload 类型检查（fail-fast；不降级、不解析 content）。 */
    private inline fun <reified T : ContextPayload> ContextItem.requirePayload(kind: ContextSourceKind): T {
        val value = payload
            ?: throw ApplicationException(
                ApplicationError.InvalidOperation("Context payload 缺失: itemId=$itemId, kind=$kind"),
            )
        if (value !is T) {
            throw ApplicationException(
                ApplicationError.InvalidOperation(
                    "Context payload 类型不匹配: itemId=$itemId, kind=$kind, actual=${value::class}",
                ),
            )
        }
        return value
    }
}
