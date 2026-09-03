package com.qianyan.application.usecase.writing.knowledgeupdate

import com.qianyan.model.MemoryEntryId
import com.qianyan.model.knowledge.CandidateKnowledgeChange
import com.qianyan.model.memory.MemoryEntry
import com.qianyan.model.memory.MemoryLayer
import kotlinx.datetime.Clock

/**
 * 确定性知识应用（P11.5,Apply）。
 *
 * 只接收**已通过校验**的候选，将其渲染为 Memory 层沉淀条目（layer=WRITING）。
 * - 纯函数、**不调 LLM**：相同候选 → 相同 MemoryEntry；不做任何二次生成。
 * - 每次变更以追加式 WRITING 记忆记录（不覆盖既有 MemoryEntry / canon），原记忆保持不变。
 * - 任何 *rejected*（immutable 等）候选不得进入本 Applicator。
 */
object KnowledgeApplicator {

    /** 把接受列表确定性地渲染为 [MemoryEntry] 列表（layer=WRITING）。 */
    fun apply(accepted: List<CandidateKnowledgeChange>): List<MemoryEntry> =
        accepted.map { it.toMemoryEntry() }

    private fun CandidateKnowledgeChange.toMemoryEntry(): MemoryEntry {
        val now = Clock.System.now()
        return MemoryEntry(
            id = MemoryEntryId(java.util.UUID.randomUUID().toString()),
            novelId = novelId,
            variantId = variantId,
            scope = scope,
            layer = MemoryLayer.WRITING,
            content = render(),
            source = source.ifBlank { SOURCE_TAG },
            createdAt = now,
            updatedAt = now,
        )
    }

    /** 结构化渲染变化内容，供人类/未来检索读取。 */
    private fun CandidateKnowledgeChange.render(): String =
        "【知识更新·$operation】${target.ifBlank { "(无目标)" }}: $content" +
            (reason.takeIf { it.isNotBlank() }?.let { " （依据: $it）" } ?: "")

    private const val SOURCE_TAG = "knowledge-update"
}