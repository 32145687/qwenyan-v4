package com.qianyan.storage.repository

import com.qianyan.model.ChapterId
import com.qianyan.model.reading.ReadingProgress

/**
 * Reader 阅读位置持久化仓储（P20-P4 · FD-9）。
 *
 * 只承担"读到哪"的最小读写：一章一条阅读位置（chapterId 为主键）。
 * 明确不做：阅读统计 / 时长 / 用户行为 / 云同步 / 多设备同步 / 推荐（后续阶段议题）。
 */
interface ReadingProgressRepository {

    /** 保存/覆盖某章节的阅读位置（幂等：同 chapterId 覆盖，不产生第二行）。 */
    fun save(progress: ReadingProgress)

    /** 读取某章节的阅读位置；尚无记录返回 null（调用方从默认位置开始，不伪造进度）。 */
    fun get(chapterId: ChapterId): ReadingProgress?
}