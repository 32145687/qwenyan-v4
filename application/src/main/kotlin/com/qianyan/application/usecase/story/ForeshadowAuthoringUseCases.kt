package com.qianyan.application.usecase.story

import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.application.error.ErrorMapper
import com.qianyan.application.usecase.UseCase
import com.qianyan.application.usecase.novel.NovelUseCases
import com.qianyan.model.ChapterId
import com.qianyan.model.ForeshadowingId
import com.qianyan.model.NovelId
import com.qianyan.model.VariantScope
import com.qianyan.model.story.Foreshadow
import com.qianyan.model.story.ForeshadowLifecycleRules
import com.qianyan.model.story.ForeshadowLifecycleState
import com.qianyan.storage.repository.ChapterRepository
import com.qianyan.storage.repository.StoryStateRepository
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/**
 * T-O07-A · 作者伏笔入口（Original 作用域，生命周期元数据）。
 *
 * 定位（D-2=C-narrow）：伏笔生命周期属于**创作元数据**，允许作者在 Original 主稿上维护，但只写
 * `Foreshadow` 的生命周期列（闸门在 DB 触发器层：内容/身份列仍 ABORT，见 DatabaseInitializer）。
 *
 * 硬边界：
 *  - **零正文写入**：不依赖 Draft / Commit / Change 任何类型（编译期隔离；本文件不得引入这些 import）；
 *  - 不新增表 / 迁移；复用既有一列式状态机 [ForeshadowLifecycleRules] 与条件 UPDATE
 *    [StoryStateRepository.transitionForeshadowState]（`affected != 1` 即并发/状态已变）；
 *  - 与既有 [ForeshadowLifecycleUseCases] **并行存在**：后者保持 Variant-only 语义不变，本类不调用它；
 *  - 不触碰 NarrativeState / ContextPack / Reveal。
 */
class ForeshadowAuthoringUseCases(
    private val storyState: StoryStateRepository,
    private val chapters: ChapterRepository,
    private val novels: NovelUseCases,
    errorMapper: ErrorMapper,
) : UseCase(errorMapper) {

    /** 作者埋下一条伏笔（Original，初始 PLANTED）。 */
    fun create(novelId: NovelId, chapterId: ChapterId?, content: String): Foreshadow {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) {
            throw ApplicationException(ApplicationError.InvalidOperation("伏笔内容不能为空"))
        }
        guard { novels.getNovel(novelId) }
            ?: throw ApplicationException(ApplicationError.EntityNotFound("Novel 不存在: ${novelId.value}"))
        if (chapterId != null) {
            val chapter = guard { chapters.findById(chapterId) }
                ?: throw ApplicationException(ApplicationError.EntityNotFound("Chapter 不存在: ${chapterId.value}"))
            if (chapter.novelId != novelId) {
                throw ApplicationException(
                    ApplicationError.InvalidOperation("章节 ${chapterId.value} 不属于作品 ${novelId.value}"),
                )
            }
        }
        val now = Clock.System.now()
        val entity = Foreshadow(
            foreshadowId = ForeshadowingId(nextId()),
            novelId = novelId,
            variantId = null,
            scope = VariantScope.ORIGINAL,
            chapterId = chapterId,
            content = trimmed,
            state = ForeshadowLifecycleState.PLANTED,
            createdAt = now,
            updatedAt = now,
        )
        guard { storyState.saveForeshadow(entity) }
        return entity
    }

    /**
     * 推进生命周期（Original）。
     *
     * @param payoffChapterId 仅 `RESOLVED` 必填且必须属于该作品；其它目标一律清除。
     */
    fun transition(
        novelId: NovelId,
        foreshadowId: ForeshadowingId,
        target: ForeshadowLifecycleState,
        reason: String? = null,
        payoffChapterId: ChapterId? = null,
        occurredAt: Instant = Clock.System.now(),
    ): Foreshadow {
        val current = guard { storyState.getForeshadowById(foreshadowId) }
        if (current == null || current.novelId != novelId) {
            throw ApplicationException(ApplicationError.EntityNotFound("Foreshadow 不存在: ${foreshadowId.value}"))
        }
        if (current.state == target || !ForeshadowLifecycleRules.canTransition(current.state, target)) {
            throw ApplicationException(
                ApplicationError.InvalidOperation("非法 Foreshadow 过渡: ${current.state} -> $target"),
            )
        }
        val payoff = if (target == ForeshadowLifecycleState.RESOLVED) {
            if (payoffChapterId == null) {
                throw ApplicationException(ApplicationError.InvalidOperation("兑现伏笔必须指定兑现章节"))
            }
            val chapter = guard { chapters.findById(payoffChapterId) }
                ?: throw ApplicationException(ApplicationError.EntityNotFound("Chapter 不存在: ${payoffChapterId.value}"))
            if (chapter.novelId != novelId) {
                throw ApplicationException(
                    ApplicationError.InvalidOperation("兑现章节 ${payoffChapterId.value} 不属于作品 ${novelId.value}"),
                )
            }
            payoffChapterId
        } else {
            null
        }
        val affected = guard {
            storyState.transitionForeshadowState(
                foreshadowId = foreshadowId,
                expectedState = current.state,
                targetState = target,
                reason = reason,
                occurredAt = occurredAt,
                payoffChapterId = payoff,
            )
        }
        if (affected != 1) {
            throw ApplicationException(
                ApplicationError.InvalidOperation("Foreshadow 并发状态变化，迁移未生效，请重试（当前=${current.state} 目标=$target）"),
            )
        }
        return current.copy(
            state = target,
            updatedAt = occurredAt,
            lastTransitionReason = reason,
            payoffChapterId = payoff,
        )
    }

    /** 作者账本列表（Original 作用域；Variant 伏笔仍由 Variant 上下文管理）。 */
    fun list(novelId: NovelId): List<Foreshadow> = guard { storyState.listForeshadows(novelId, null) }
}
