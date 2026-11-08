package com.qianyan.application.usecase.memory

import com.qianyan.application.error.ErrorMapper
import com.qianyan.application.usecase.UseCase
import com.qianyan.model.MemoryEntryId
import com.qianyan.model.NovelId
import com.qianyan.model.UserId
import com.qianyan.model.core.VariantContext
import com.qianyan.model.memory.MemoryEntry
import com.qianyan.model.memory.MemoryLayer
import com.qianyan.storage.repository.MemoryRepository
import kotlinx.datetime.Clock

/**
 * Memory 相关 Use Case（P3.2）：
 *  - SaveMemoryEntry：按调用链上下文保存一条 MemoryEntry（Original → 只读基座；Variant → 可写记忆）。
 *  - QueryMemory：在上下文下读取该 Novel 的记忆（含 Original 基座 + 该 Variant 记忆）。
 *
 * 仅持久化 P1 已实现的最小 [MemoryEntry]，不实现完整四层 Memory（P2.12 扩展点，不新增）。
 */
class MemoryUseCases(
    private val repo: MemoryRepository,
    errorMapper: ErrorMapper,
) : UseCase(errorMapper) {

    /**
     * SaveMemoryEntry：以 [context] 作为写入作用域。
     *  - Original 上下文（variantId=null）→ scope=ORIGINAL，写入基座（只读语义由 Storage 侧兜底）。
     *  - Variant 上下文           → scope=VARIANT，variantId=当前 Variant，AI 只写 Variant。
     */
    fun saveEntry(
        context: VariantContext,
        content: String,
        layer: MemoryLayer = MemoryLayer.LONG_TERM,
        source: String = "",
        createdBy: UserId? = null,
        memoryEntryId: MemoryEntryId? = null,
    ): MemoryEntryId {
        val now = Clock.System.now()
        val id = memoryEntryId ?: MemoryEntryId(nextId())
        val entry = MemoryEntry(
            id = id,
            novelId = NovelId(context.baseNovelId.value),
            variantId = context.variantId,
            scope = context.scope,
            layer = layer,
            content = content,
            source = source,
            createdBy = createdBy,
            createdAt = now,
            updatedAt = now,
        )
        guard { repo.saveEntry(entry) }
        return id
    }

    /** QueryMemory：读取该 Novel（在 context 下）的记忆条目。 */
    fun query(context: VariantContext): List<MemoryEntry> =
        guard { repo.findEntriesByNovel(NovelId(context.baseNovelId.value)) }

    /**
     * O-08：知识更新结果视图的数据源（按 Novel + layer=WRITING + effective 统计）。
     *
     * 只读投影：条数 / 最近更新时间 / 最近 2 条内容。用于桌面「知识更新」卡片；
     * 不尝试按作品追溯 KU Task 状态（Task 无作品关联，且审批链创建的 KU Task 不置 COMPLETED）。
     */
    fun writingLayerSummary(novelId: NovelId): WritingMemorySummary {
        val entries = guard { repo.findEntriesByNovel(novelId) }
            .filter { it.layer == MemoryLayer.WRITING && it.effective }
            .sortedByDescending { it.updatedAt }
        return WritingMemorySummary(
            count = entries.size,
            lastUpdatedAt = entries.firstOrNull()?.updatedAt,
            recentContents = entries.take(2).map { it.content },
        )
    }
}

/** O-08：知识更新沉淀结果的只读摘要。 */
data class WritingMemorySummary(
    val count: Int,
    val lastUpdatedAt: kotlinx.datetime.Instant?,
    val recentContents: List<String>,
)