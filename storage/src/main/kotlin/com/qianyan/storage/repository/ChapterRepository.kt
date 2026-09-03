package com.qianyan.storage.repository

import com.qianyan.model.ChapterId
import com.qianyan.model.NovelId
import com.qianyan.model.VariantId
import com.qianyan.model.story.Chapter

/**
 * Chapter 持久化仓储（P12.0 / P0-4 + P12.0.1）。
 *
 * 只承载最小章节身份/序号语义：
 *  - [save] / [findById]：基础 CRUD。
 *  - [listByNovel]：**严格按 Variant 作用域查询**（variantId 必传；null = Original 章节，指定值 = 该 Variant 章节），
 *    不提供"混 scope"的按 Novel 全量列表，避免调用方在 Application 层猜 scope（P12.0.1 P0）。
 *  - [nextOrder]：同 Novel(+Variant) 下一个 order（max+1）——供只读计算。
 *  - [createNextChapter]：**原子创建下一章**（同一 SQLite 事务内计算 next order 并 INSERT，避免并发重复 order；
 *    SQLite 对 variant_id IS NULL 的 UNIQUE 组合不强制，故 order 唯一性由本原子操作保证，P12.0.1 P1）。
 */
interface ChapterRepository {

    /** 保存一个 Chapter（幂等：同 chapterId 覆盖）。 */
    fun save(chapter: Chapter)

    /** 按 chapterId 读取；不存在返回 null。 */
    fun findById(chapterId: ChapterId): Chapter?

    /** 某 Novel 下指定 Variant 作用域的全部 Chapter（order 升序）。variantId=null → Original 章节。 */
    fun listByNovel(novelId: NovelId, variantId: VariantId?): List<Chapter>

    /** 同 Novel(+Variant) 下下一个 order（max+1；variant null 为 Original 章节序列）。 */
    fun nextOrder(novelId: NovelId, variantId: VariantId?): Int

    /** 原子创建下一章：单事务内计算 next order 并保存（order 由本方法赋值）。 */
    fun createNextChapter(chapter: Chapter): Chapter
}