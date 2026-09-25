package com.qianyan.model.reading

import com.qianyan.model.ChapterId
import com.qianyan.model.NovelId
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/*
 * P20-P4 · Reader 阅读位置（FD-9：ReadingProgress 只承担阅读位置/进度职责）。
 *
 * 最小字段集合（不含阅读统计 / 时长 / 用户行为 / 云同步 / 多设备同步 / 推荐）：
 *  - novelId / chapterId：所属作品与章节（chapter 已唯一确定 Novel+Variant 作用域）；
 *  - position：阅读位置 = 正文**块序号**（0-based，指向重进时置顶显示的块）；
 *    legacy 纯文本（Draft.format=null）为单块展示 → position 恒为 0；
 *  - updatedAt：最后保存时间。
 *
 * 不复用/不新建第二套章节正文模型：正文仍来自 [com.qianyan.model.writing.Draft]，
 * 本模型只记录"读到哪"。
 */
@Serializable
data class ReadingProgress(
    val novelId: NovelId,
    val chapterId: ChapterId,
    /** 正文块序号（0-based）；超出当前块数时由 Reader 在渲染期钳制，不视为错误。 */
    val position: Int = 0,
    val updatedAt: Instant,
)