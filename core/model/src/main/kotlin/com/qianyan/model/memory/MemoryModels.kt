package com.qianyan.model.memory

import com.qianyan.model.MemoryEntryId
import com.qianyan.model.NovelId
import com.qianyan.model.UserId
import com.qianyan.model.VariantId
import com.qianyan.model.VariantScope
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/**
 * Memory（最小领域模型，V4.2）。
 * 现状锚点：架构 §5 的 "四层 Memory" 是描述性分层，未定义统一 data class；
 * 这里建立**最小可用**的 MemoryEntry，能承载 scope + variantId，未来分层模型见扩展点。
 *
 * 读取语义（V4.2 决策）：
 *  - scope=ORIGINAL, variantId=null → Original Memory（只读）。
 *  - scope=VARIANT, variantId=V     → Variant Memory（可写，AI 写只进 Variant）。
 * 读取 Variant 上下文时：可读 Original 只读基座 + 本 Variant 记忆，绝不写 Original。
 */
@Serializable
data class MemoryEntry(
    val id: MemoryEntryId,
    val novelId: NovelId,
    val variantId: VariantId? = null,
    val scope: VariantScope = VariantScope.ORIGINAL,
    val layer: MemoryLayer = MemoryLayer.LONG_TERM,
    val content: String,
    val source: String = "",
    val createdBy: UserId? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/**
 * 对应架构 §5 的层级。P1 仅作为标注；"完整四层分层/存取规则"为 [TBD-4]。
 */
@Serializable
enum class MemoryLayer { CURRENT_STATE, WRITING, LONG_TERM, ORIGINAL }

/** 扩展点（Future）：真正的 Memory Store / Retriever，非本阶段。 */
@Serializable
enum class MemoryAccessMode { READ_ORIGINAL_BASE, WRITE_VARIANT_ONLY }