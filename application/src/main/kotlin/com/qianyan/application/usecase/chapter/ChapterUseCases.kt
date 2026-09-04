package com.qianyan.application.usecase.chapter

import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.application.error.ErrorMapper
import com.qianyan.application.usecase.UseCase
import com.qianyan.model.ChapterId
import com.qianyan.model.NovelId
import com.qianyan.model.VariantId
import com.qianyan.model.VariantScope
import com.qianyan.model.story.Chapter
import com.qianyan.model.story.ChapterStatus
import com.qianyan.storage.repository.ChapterRepository
import com.qianyan.storage.repository.NovelRepository
import java.util.UUID
import kotlinx.datetime.Clock

/**
 * Chapter 阅读 / 创建 Use Case（P12.1.6）。
 *
 * 为 Android（及任何上层）提供受控的章节读取与创建入口，UI 不直接触碰 [ChapterRepository]。
 * 分层：Compose → ViewModel → [ChapterUseCases] → [ChapterRepository] → Storage。
 *
 * 严格遵循 Domain 语义：
 *  - [listByNovel]：按 `novelId + variantId` 作用域查询（`ORDER BY order_no ASC`），隔离由 Storage 层保证，
 *    不在此重写一套 Android 过滤逻辑；
 *  - [createNextChapter]：经 [ChapterRepository.createNextChapter] **单事务** `MAX(order_no)+1 → INSERT`
 *    （复用 P12.0.1 的原子 order 保证，禁止上层自行 max+1）；
 *  - scope：`variantId == null → ORIGINAL`，否则 `VARIANT`（与领域一致）；
 *  - 不修改 Domain / order / Variant 语义。
 */
class ChapterUseCases(
    private val chapterRepository: ChapterRepository,
    private val novelRepository: NovelRepository,
    errorMapper: ErrorMapper,
) : UseCase(errorMapper) {

    /** 列出某 Novel(+Variant) 下全部 Chapter（order_no ASC）。variantId=null → Original 章节。 */
    fun listByNovel(novelId: NovelId, variantId: VariantId? = null): List<Chapter> =
        guard { chapterRepository.listByNovel(novelId, variantId) }

    /** 按 chapterId 读取单个 Chapter；不存在返回 null。 */
    fun findById(chapterId: ChapterId): Chapter? =
        guard { chapterRepository.findById(chapterId) }

    /**
     * 创建下一章（真实 persisting）。title 空白时使用占位「未命名章节」。
     * @throws [ApplicationError.EntityNotFound] 目标 Novel 不存在。
     */
    fun createNextChapter(title: String, novelId: NovelId, variantId: VariantId? = null): Chapter {
        val novel = guard { novelRepository.getNovel(novelId) }
            ?: throw ApplicationException(
                ApplicationError.EntityNotFound("Chapter 创建目标 Novel 不存在: ${novelId.value}"),
            )
        // Novel 存在但 Variant 若指定，需属于该 Novel（基础 scope 校验，复用领域边界）。
        if (variantId != null) {
            val variant = guard { novelRepository.getVariant(variantId) }
            if (variant == null || variant.novelId.value != novelId.value) {
                throw ApplicationException(
                    ApplicationError.VariantMismatch("Variant(${variantId.value}) 不属于 Novel(${novelId.value})，无法在该作用域创建 Chapter"),
                )
            }
        }
        val now = Clock.System.now()
        val chapter = Chapter(
            chapterId = ChapterId(UUID.randomUUID().toString()),
            novelId = novelId,
            variantId = variantId,
            scope = if (variantId == null) VariantScope.ORIGINAL else VariantScope.VARIANT,
            title = title.trim().take(MAX_TITLE_LENGTH).ifEmpty { DEFAULT_TITLE },
            order = 0, // createNextChapter 在事务内赋真实 order
            status = ChapterStatus.PLANNED,
            createdAt = now,
            updatedAt = now,
        )
        // 复用 P12.0.1 原子 order：单事务 MAX(order)+1 → INSERT（不在 Android 重算）。
        return guard { chapterRepository.createNextChapter(chapter) }
    }

    private companion object {
        const val MAX_TITLE_LENGTH: Int = 100
        const val DEFAULT_TITLE: String = "未命名章节"
    }
}