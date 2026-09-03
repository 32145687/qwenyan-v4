package com.qianyan.application.usecase.writing.knowledgeupdate

import com.qianyan.model.MemoryEntryId
import com.qianyan.model.knowledge.CandidateKnowledgeChange
import com.qianyan.model.memory.MemoryEntry
import com.qianyan.model.memory.MemoryLayer
import kotlinx.datetime.Clock

/**
 * 确定性知识应用（P11.5 / P12.0 P0-2）。
 *
 * 只接收**已通过校验**的候选，将其渲染为 Memory 层沉淀条目（layer=WRITING）。
 * - 纯函数、**不调 LLM**：相同候选 → 相同 MemoryEntry；不做任何二次生成。
 * - **有效状态语义（P0-2）**：落库条目携带 `target`（事实定位键）与 `effective=true`（当前有效状态）；
 *   UPDATE/REMOVE 的"旧事实失效"由 [com.qianyan.application.usecase.writing.knowledgeupdate.KnowledgeUpdateExecutionUseCase]
 *   在事务内调用 [com.qianyan.storage.repository.MemoryRepository.deactivateByTarget] 完成 —— Applicator 本身不触碰 Repository，
 *   不产生半完成状态。历史记录（effective=false）保留可审计。
 * - 任何 *rejected*（immutable 等）候选不得进入本 Applicator。
 */
object KnowledgeApplicator {

    /** 把接受列表确定性地渲染为 [MemoryEntry] 列表（layer=WRITING）。 */
    fun apply(accepted: List<CandidateKnowledgeChange>): List<MemoryEntry> =
        accepted.map { it.toMemoryEntry() }

    /** 单条候选 → MemoryEntry（供 UseCase 在事务内逐条落地）。 */
    fun toEntry(candidate: CandidateKnowledgeChange): MemoryEntry = candidate.toMemoryEntry()

    private fun CandidateKnowledgeChange.toMemoryEntry(): MemoryEntry {
        val now = Clock.System.now()
        return MemoryEntry(
            id = MemoryEntryId(java.util.UUID.randomUUID().toString()),
            novelId = novelId,
            variantId = variantId,
            scope = scope,
            layer = MemoryLayer.WRITING,
            content = render(),
            target = target,
            effective = true,
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