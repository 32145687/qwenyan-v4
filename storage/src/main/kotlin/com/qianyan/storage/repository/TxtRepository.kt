package com.qianyan.storage.repository

import com.qianyan.model.TextBlockId
import com.qianyan.model.TxtChapterId
import com.qianyan.model.TxtDocumentId
import com.qianyan.model.txt.TextBlock
import com.qianyan.model.txt.TxtChapter
import com.qianyan.model.txt.TxtDocument

/**
 * TXT Pipeline 持久化仓储（领域语义，不暴露 SQLDelight 生成类型）。
 *
 * P4 约定：
 *  - TxtDocument 为 Original Source（只读原文结构），saveImport 原子写入
 *    文档 + 章节 + 段落块；不支持增量改写（改写属于后续 Variant 创作流程）。
 *  - 查询按确定性顺序返回（chapters/blocks 按 ordinal 升序），保证读取可重建结构化文本。
 */
interface TxtRepository {

    /** 原子保存一次完整导入结果（文档 + 章节 + 段落块）。同 documentId 重复保存违反主键 → UniqueConflictException。 */
    fun saveImport(document: TxtDocument, chapters: List<TxtChapter>, blocks: List<TextBlock>)

    /** 查询 TXT 文档。不存在返回 null。 */
    fun getDocument(documentId: TxtDocumentId): TxtDocument?

    /** 按出现顺序（ordinal 升序）查询某文档的全部章节。 */
    fun getChapters(documentId: TxtDocumentId): List<TxtChapter>

    /** 按全局块序号（ordinal 升序）查询某文档的全部段落块。 */
    fun getBlocks(documentId: TxtDocumentId): List<TextBlock>

    /** 按全局块序号查询某章节内的段落块。 */
    fun getBlocksOfChapter(chapterId: TxtChapterId): List<TextBlock>

    /** 按全局块序号查询单个段落块。不存在返回 null。 */
    fun getBlock(blockId: TextBlockId): TextBlock?
}
