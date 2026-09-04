package com.qianyan.application.usecase.writing.knowledgeupdate

import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.application.error.ErrorMapper
import com.qianyan.application.usecase.UseCase
import com.qianyan.application.usecase.task.TaskManagerUseCases
import com.qianyan.model.TaskId
import com.qianyan.model.memory.MemoryEntry
import com.qianyan.model.task.Checkpoint
import com.qianyan.model.task.TaskType
import com.qianyan.model.writing.Draft
import com.qianyan.model.writing.DraftStatus
import com.qianyan.storage.repository.DraftRepository
import com.qianyan.storage.repository.MemoryRepository

/**
 * Knowledge Update 执行 Use Case（P11.5 + P12.1.4 Confirmation Gate）。
 *
 * 目标链路：
 * ```
 * Task(WRITING) → 读取当前 [Draft] 现有记忆 → KnowledgeUpdateAgent(AgentRuntime → LLMGateway)
 *     → KnowledgeUpdateParser → CandidateKnowledgeChange[]
 *     → KnowledgeValidator（确定性，immutable canon 保护）
 *     → KnowledgeApplicator（确定性 → MemoryEntry layer=WRITING）
 *     → MemoryRepository.saveEntry → KNOWLEDGE_UPDATE Checkpoint
 * ```
 *
 * P12.1.4 — 最小 HITL Confirmation Gate：**Knowledge Update 不得在用户确认之前执行**。
 *  在调用 Agent / LLM 之前，先经 [DraftRepository] 校验：
 *  （1）source Draft 必须存在（保存的为真）；（2）必须属于传入 Draft 的一致 Novel/Variant 作用域；
 *  （3）必须是已 [DraftStatus.CONFIRMED] 的 Final Draft —— 否则类型化 [ApplicationError.DraftConfirmationRequired]。
 *  Gate 位于业务层，不依赖 UI；confirm() 不在方法内触发 Agent / LLM。
 *
 * 职责边界：
 *  - 校验 Task type == WRITING（非 WRITING → [ApplicationError.InvalidOperation]；不存在 → TaskNotFound）；
 *  - Agent 只依赖 LLMGateway；Repository 只在 **UseCase 层**（MemoryRepository / DraftRepository）被访问；
 *  - 确定性裁决：被 [KnowledgeValidator] 拒绝的候选（如 immutable Original 的 UPDATE/REMOVE）**不会进入
 *    Applicator / Repository** —— 它们记录在结果与 Checkpoint 中，但不改变任何世界/记忆状态（无 silent overwrite）；
 *  - 失败：Knowledge Update 可在任意 Task 状态执行（draft 可能来自已 COMPLETED 任务），失败只抛类型化
 *    [ApplicationException]（不调用 fail，避免对终态 Task 的非法状态转换）；
 *  - 不实现自动无限循环 / Workflow / HITL / 冲突解决 UI（reject 为确定性语义处理）。
 */
class KnowledgeUpdateExecutionUseCase(
    private val taskManager: TaskManagerUseCases,
    private val agent: KnowledgeUpdateAgent,
    private val memoryRepository: MemoryRepository,
    private val draftRepository: DraftRepository,
    errorMapper: ErrorMapper,
) : UseCase(errorMapper) {

    /**
     * 对 [draft] 执行一次 Knowledge Update（确定性落地 + KNOWN_UPDATE Checkpoint）。
     * Task 类型非 WRITING → InvalidOperation。
     * P12.1.4：source Draft 必须已 CONFIRMED（Final）才能执行，否则 → [ApplicationError.DraftConfirmationRequired]。
     */
    fun execute(taskId: TaskId, draft: Draft): KnowledgeUpdateOutcome {
        val task = taskManager.findById(taskId)
        // 官方 P11 完成标准（Preflight §17 验收 #2）：TaskRunner 可执行 KNOWLEDGE_UPDATE 类型 Task。
        // 允许 WRITING（创作后知识沉淀，P11.5 原有路径，行为不变）与 KNOWLEDGE_UPDATE（独立知识更新 Task）。
        if (task.type != TaskType.WRITING && task.type != TaskType.KNOWLEDGE_UPDATE) {
            throw ApplicationException(
                ApplicationError.InvalidOperation("Task ${taskId.value} 类型 ${task.type} 不是 WRITING/KNOWLEDGE_UPDATE，无法执行知识更新"),
            )
        }

        // P12.1.4 Confirmation Gate：调用 Agent 之前，以持久化的 Draft 为真（不信任内存传入对象）。
        val source = requireConfirmedFinal(draft)

        val existingMemories = guard { memoryRepository.findEntriesByNovel(source.novelId) }.map { it.content }
        val candidates = agent.propose(source, existingMemories)
        val validated = KnowledgeValidator.validate(candidates)
        // P12.0 P0-2/P0-3：确定性 Apply 在**单事务**内完成——非 ADD 先失效旧事实（effective=0，历史保留），
        // 再插入新有效事实；任一条失败整体回滚（不产生半完成状态）。rejected 候选绝不进入事务。
        val applied = KnowledgeApplicator.apply(validated.accepted)
        memoryRepository.inTransaction {
            validated.accepted.zip(applied).forEach { (c, e) ->
                if (c.operation != com.qianyan.model.knowledge.KnowledgeOperation.ADD) {
                    memoryRepository.deactivateByTarget(source.novelId, source.variantId, c.target)
                }
                memoryRepository.saveEntry(e)
            }
        }

        val outcome = KnowledgeUpdateOutcome(validated, applied)
        taskManager.saveCheckpoint(taskId, KnowledgeUpdateSnapshot.STAGE, KnowledgeUpdateSnapshot.encode(validated))
        return outcome
    }

    /** 从 KNOWN_UPDATE Checkpoint 恢复 [ValidatedKnowledgeUpdate]（只读恢复上下文，不重新执行）。 */
    fun validatedFrom(checkpoint: Checkpoint): ValidatedKnowledgeUpdate? = KnowledgeUpdateSnapshot.decode(checkpoint.snapshot)

    /**
     * P12.1.4 前置门禁：确认 source Draft（以持久化实体为准）已存在、作用域一致、且已 [DraftStatus.CONFIRMED]（Final）。
     * 任一不满足 → 类型化错误；在调用 Agent / LLM / Applicator 之前短路。
     */
    private fun requireConfirmedFinal(draft: Draft): Draft {
        val persisted = guard { draftRepository.getById(draft.draftId) }
            ?: throw ApplicationException(
                ApplicationError.EntityNotFound("Knowledge Update source Draft 不存在: ${draft.draftId.value}"),
            )
        // 作用域一致性：内存传入对象必须与持久化 Draft 同 Novel/Variant（防御，不信任调用方逐字传参）。
        val mismatches = buildList {
            if (persisted.novelId != draft.novelId) add("novel")
            if (persisted.variantId != draft.variantId) add("variant")
        }
        if (mismatches.isNotEmpty()) {
            throw ApplicationException(
                ApplicationError.InvalidOperation(
                    "Knowledge Update Draft(${draft.draftId.value}) 传入作用域与持久化不一致（不一致: ${mismatches.joinToString()}）",
                ),
            )
        }
        if (persisted.status != DraftStatus.CONFIRMED) {
            throw ApplicationException(
                ApplicationError.DraftConfirmationRequired(
                    "Knowledge Update 需要 CONFIRMED Final Draft；Draft(${draft.draftId.value}) 当前 status=${persisted.status}",
                ),
            )
        }
        return persisted
    }
}

/** Knowledge Update 的结果：确定性校验结果 + 实际落地的 Memory 沉淀条目。 */
data class KnowledgeUpdateOutcome(
    val validated: ValidatedKnowledgeUpdate,
    val applied: List<MemoryEntry>,
)