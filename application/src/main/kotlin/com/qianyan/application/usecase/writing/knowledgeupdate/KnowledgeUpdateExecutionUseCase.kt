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
import com.qianyan.storage.repository.MemoryRepository

/**
 * Knowledge Update 执行 Use Case（P11.5）。
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
 * 职责边界：
 *  - 校验 Task type == WRITING（非 WRITING → [ApplicationError.InvalidOperation]；不存在 → TaskNotFound）；
 *  - Agent 只依赖 LLMGateway；Repository 只在 **UseCase 层**（MemoryRepository）被访问；
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
    errorMapper: ErrorMapper,
) : UseCase(errorMapper) {

    /**
     * 对 [draft] 执行一次 Knowledge Update（确定性落地 + KNOWN_UPDATE Checkpoint）。
     * 返回 [KnowledgeUpdateOutcome]（校验结果 + applied MemoryEntry）。Task 类型非 WRITING → InvalidOperation。
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

        val existingMemories = guard { memoryRepository.findEntriesByNovel(draft.novelId) }.map { it.content }
        val candidates = agent.propose(draft, existingMemories)
        val validated = KnowledgeValidator.validate(candidates)
        // 确定性 Apply：只对通过校验的候选写 Memory（不调 LLM；被拒候选不落地）。
        val applied = KnowledgeApplicator.apply(validated.accepted)
        applied.forEach { guard { memoryRepository.saveEntry(it) } }

        val outcome = KnowledgeUpdateOutcome(validated, applied)
        taskManager.saveCheckpoint(taskId, KnowledgeUpdateSnapshot.STAGE, KnowledgeUpdateSnapshot.encode(validated))
        return outcome
    }

    /** 从 KNOWN_UPDATE Checkpoint 恢复 [ValidatedKnowledgeUpdate]（只读恢复上下文，不重新执行）。 */
    fun validatedFrom(checkpoint: Checkpoint): ValidatedKnowledgeUpdate? = KnowledgeUpdateSnapshot.decode(checkpoint.snapshot)
}

/** Knowledge Update 的结果：确定性校验结果 + 实际落地的 Memory 沉淀条目。 */
data class KnowledgeUpdateOutcome(
    val validated: ValidatedKnowledgeUpdate,
    val applied: List<MemoryEntry>,
)