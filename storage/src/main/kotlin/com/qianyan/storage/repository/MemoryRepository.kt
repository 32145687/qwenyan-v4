package com.qianyan.storage.repository

import com.qianyan.model.NovelId
import com.qianyan.model.VariantId
import com.qianyan.model.memory.MemoryEntry

/**
 * Memory 最小持久化仓储（P2.12）。
 * 只持久化 P1 已实现的最小 [MemoryEntry]；不实现完整四层 Memory。
 * [com.qianyan.model.memory.MemoryAccessMode] 继续作为扩展点，本阶段不新增。
 */
interface MemoryRepository {

    /** 保存一条 MemoryEntry。 */
    fun saveEntry(entry: MemoryEntry)

    /** 按 Novel 查询其下全部 Memory（含 Original 只读基座与会话 Variant 记忆）。 */
    fun findEntriesByNovel(novelId: NovelId): List<MemoryEntry>

    /** 查询某 Novel 下某 Variant 的 Memory。 */
    fun findEntriesByVariant(novelId: NovelId, variantId: VariantId): List<MemoryEntry>
}