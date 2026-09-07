package com.qianyan.storage.repository

import com.qianyan.model.ChapterId
import com.qianyan.model.DraftId
import com.qianyan.model.NovelId
import com.qianyan.model.writing.Draft

/**
 * Draft（正文草稿）持久化仓储（P11.3）。
 *
 * 只实现写作 Pipeline 最小所需操作：
 *  - [save]：保存一次写作产物（正文）；
 *  - [getById]：按 DraftId 读取单个草稿（不存在返回 null）；
 *  - [listByNovel]：按 Novel 列出其下全部草稿（含 Variant 产物，升序）。
 *
 * 明确范围外（P11.3 不实现）：版本/多稿管理、修订历史、按 Variant/Chapter/Plan 过滤
 * （需要时随 P11.4 Revision 语义扩展）；正文不落入 MemoryEntry（语义不应混用）。
 */
interface DraftRepository {

    /** 保存一次 Draft（幂等：同 draftId 覆盖）。 */
    fun save(draft: Draft)

    /** 按 DraftId 读取；不存在返回 null。 */
    fun getById(draftId: DraftId): Draft?

    /** 列出某 Novel 下全部 Draft（按创建时间升序）。 */
    fun listByNovel(novelId: NovelId): List<Draft>

    // ---- P12.2·TD2：章节作用域查询（走 chapter_id 索引） ----

    /** 列出某 Chapter 下的全部 Draft（升序）。 */
    fun listByChapter(chapterId: ChapterId): List<Draft>

    /** 某 Chapter 最近创建的 Draft；无返回 null。 */
    fun latestByChapter(chapterId: ChapterId): Draft?
}