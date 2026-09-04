package com.qianyan.model.story

import com.qianyan.model.ChapterId
import com.qianyan.model.DraftId
import kotlinx.serialization.Serializable

/**
 * 显式续篇引用（P12.1.3）。
 *
 * 表达"新章节的 Planning 基于**指定 source Chapter 的指定 source Draft** 继续"，而不依赖
 * "上一章"字符串 / Agent 猜测 / 最近查询 / 隐式排序。它是纯 reference-only 的领域语义：
 * 只负责**指向**，实际内容由 Repository / Resolver 在执行时读取（不携带 Draft 正文、
 * StoryWorldContext、Character/Timeline/Event JSON 或任何 LLM 输出）。
 *
 * 核心关系：`sourceChapterId + sourceDraftId`。Chapter / Draft 的 novelId / variantId / scope
 * 均可经各自 ID 由仓储唯一确定，故不冗余保存这些派生字段。
 *
 * `nullable`（null）表示"无续篇来源"（如第一章 Planning）。
 */
@Serializable
data class ContinuationReference(
    /** 续篇的既有来源章节。 */
    val sourceChapterId: ChapterId,
    /** 该来源章节的最终可继续 Draft（必须是系统定义的最终状态）。 */
    val sourceDraftId: DraftId,
)