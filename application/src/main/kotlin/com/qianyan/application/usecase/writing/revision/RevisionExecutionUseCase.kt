package com.qianyan.application.usecase.writing.revision

import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.application.error.ErrorMapper
import com.qianyan.application.usecase.UseCase
import com.qianyan.application.usecase.task.TaskManagerUseCases
import com.qianyan.application.usecase.writing.WritingSnapshot
import com.qianyan.model.TaskId
import com.qianyan.model.spec.ValidationResult
import com.qianyan.model.task.TaskType
import com.qianyan.model.writing.Draft
import com.qianyan.storage.repository.DraftRepository

/**
 * Revision 执行 Use Case（P11.4）。
 *
 * 目标链路：
 * ```
 * Task(WRITING, revisionCount<3) → RevisionGate → RevisionAgent(AgentRuntime → LLMGateway)
 *     → REVISED Draft → DraftRepository.save → REVISION Checkpoint → revisionCount+1
 * ```
 *
 * 职责边界：
 *  - 校验 Task type == WRITING（非 WRITING → [ApplicationError.InvalidOperation]；不存在 → TaskNotFound）；
 *  - 先经 [RevisionGate]（确定性业务规则，复用 P8 revisionCount<3）：达上限 → [ApplicationError.RevisionNotAllowed]
 *    **且不调用 LLM / WriterAgent**；允许 → 才进入修订；
 *  - 修订基于**现有 [Draft] + [ValidationResult]（Critique）**，经 [RevisionAgent] 产出新 draftId、status=REVISED 的
 *    修订 Draft；**原 Draft 不被破坏**（新 draftId 独立落 ChapterDraft），通过同一 Task 的 Checkpoint 序列追踪版本关系；
 *  - 持久化：经 [DraftRepository.save] 落新 Draft；经 P8 [TaskManagerUseCases.saveCheckpoint] 保存 REVISION
 *    Checkpoint（snapshot 复用 [WritingSnapshot] 承载 Draft），**revisionCount 由 saveCheckpoint 自动 +1，上限 3 其内强制**；
 *  - 失败：Critique / Revision 可在任意 Task 状态执行，因此失败只抛类型化 [ApplicationException]（不调用 fail，
 *    避免对终态 Task 的非法状态转换）；
 *  - 不实现自动无限修订 / Workflow / HITL / retry（每次修订都是显式、受 Gate 门控的可控阶段）。
 */
class RevisionExecutionUseCase(
    private val taskManager: TaskManagerUseCases,
    private val rewriter: RevisionAgent,
    private val draftRepository: DraftRepository,
    errorMapper: ErrorMapper,
) : UseCase(errorMapper) {

    /**
     * 依据 [currentDraft] + [critique] 执行一次 Revision，返回新 draftId、status=REVISED 的 [Draft]。
     * revision 达上限 → [ApplicationError.RevisionNotAllowed]；Task 类型非 WRITING → [ApplicationError.InvalidOperation]。
     */
    fun execute(taskId: TaskId, currentDraft: Draft, critique: ValidationResult): Draft {
        val task = taskManager.findById(taskId)
        if (task.type != TaskType.WRITING) {
            throw ApplicationException(
                ApplicationError.InvalidOperation("Task ${taskId.value} 类型 ${task.type} 不是 WRITING，无法执行修订"),
            )
        }

        // Gate：达上限 → RevisionNotAllowed，绝不进入修订/调用 LLM。
        RevisionGate.requireAllowed(task)

        val revised = rewriter.revise(currentDraft, critique)
        guard { draftRepository.save(revised) }
        // 复用 WritingSnapshot 承载 Draft；saveCheckpoint 自动 revisionCount+1（上限 3 内部兜底）。
        taskManager.saveCheckpoint(taskId, REVISION_STAGE, WritingSnapshot.encode(revised))
        return revised
    }

    companion object {
        /** Revision Checkpoint 阶段标识（distinct from WRITING / CRITIQUE）。 */
        const val REVISION_STAGE = "REVISION"
    }
}