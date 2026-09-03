package com.qianyan.storage.repository

import com.qianyan.model.ChapterId
import com.qianyan.model.NovelId
import com.qianyan.model.VariantId
import com.qianyan.model.story.Chapter

/**
 * Chapter 持久化仓储（P12.0 / P0-4）。
 * 只承载最小章节身份/序号语义：save / findById / listByNovel / nextOrder。
 * order 是唯一章节序号；Application 层用 nextOrder = max+1 保证同 Novel(+Variant) 不重复。
 */
interface ChapterRepository {

    /** 保存一个 Chapter（幂等：同 chapterId 覆盖）。 */
    fun save(chapter: Chapter)

    /** 按 chapterId 读取；不存在返回 null。 */
    fun findById(chapterId: ChapterId): Chapter?

    /** 列出某 Novel 下全部 Chapter（按 order 升序）。 */
    fun listByNovel(novelId: NovelId): List<Chapter>

    /** 同 Novel(+Variant) 下下一个 order（max+1；variant null 为 Original 章节序列）。 */
    fun nextOrder(novelId: NovelId, variantId: VariantId?): Int
}