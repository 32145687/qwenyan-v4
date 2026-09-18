package com.qianyan.application.usecase.author

import com.qianyan.application.usecase.foundation.FoundationDecision
import com.qianyan.model.AuthorEvidenceId
import com.qianyan.model.NovelId
import com.qianyan.model.TaskId
import com.qianyan.model.author.AuthorEvidence
import com.qianyan.model.author.AuthorEvidenceType
import com.qianyan.model.workflow.HumanDecision
import com.qianyan.model.workflow.WorkflowId
import com.qianyan.storage.repository.TaskRepository
import com.qianyan.storage.repository.WorkflowRepository
import kotlinx.serialization.json.Json

/**
 * P16 AIL-1 · Foundation Evidence Source —— 把已有 P15 信号作为 Author Intelligence Evidence 的**只读**来源。
 *
 * 边界（P16 AIL-0 DEC-012）：
 *  - **只读取** P15 已产生的 Checkpoint（[FoundationDecision]）与 WorkflowHumanGate 决策；
 *  - **绝不修改 P15**（不写 Checkpoint / Gate / 任何 P15 表）；P15-C 是 SEALED。
 *  - 输出为 [AuthorEvidence]（MODIFY / ADOPT / REJECT / PARTIAL_REWRITE / ADOPT_THEN_REVISE），
 *    交由 Author Intelligence 主存储（独立 boundary）持久化。
 */
interface FoundationEvidenceSource {
    /** 读取某 Novel 在 P15（Foundation Decision 闭环）中各信号，转换为 [AuthorEvidence]。 */
    fun foundationSignals(novelId: NovelId): List<AuthorEvidence>
}

/**
 * P15 Foundation 决策信号的只读实现。
 *
 * 读取方式（与 [com.qianyan.application.usecase.foundation.StoryFoundationDecisionUseCases] 的布局一致）：
 *  - Foundation 决策工作流 id = `foundation-<novelId>`；其 Task id = `foundation-<workflowId>`；
 *  - 每个 Proposal Revision 产生一个 PENDING/已 resolve 的 Gate（key = `FOUNDATION:<workflowId>:<revision>`）；
 *  - 每个 Checkpoint 的 snapshot 可含 [FoundationDecision]（来自 MODIFY）；
 *  - 信号→Evidence 映射：MODIFY(decision)、ADOPT(首版确认)、ADOPT_THEN_REVISE(修改后确认)、
 *    REJECT、PARTIAL_REWRITE(REQUEST_REVISION)。
 */
class P15FoundationEvidenceSource(
    private val workflowRepository: WorkflowRepository,
    private val taskRepository: TaskRepository,
) : FoundationEvidenceSource {

    private val json = Json { ignoreUnknownKeys = true }

    override fun foundationSignals(novelId: NovelId): List<AuthorEvidence> {
        val workflow = workflowRepository.getWorkflow(WorkflowId("foundation-${novelId.value}"))
            ?: return emptyList() // 尚未进行 Foundation 决策，无 P15 信号可采集
        val taskId = TaskId("foundation-${workflow.workflowId.value}")
        val task = taskRepository.findById(taskId) ?: return emptyList()
        val signals = mutableListOf<AuthorEvidence>()

        // 1) Checkpoint 中显式记录的 FoundationDecision（来自 MODIFY）
        for (cp in taskRepository.findCheckpoints(taskId)) {
            val decision = cp.snapshot?.get(DECISION_KEY)?.let {
                json.decodeFromJsonElement(FoundationDecision.serializer(), it)
            }
            if (decision != null) {
                signals += AuthorEvidence(
                    evidenceId = AuthorEvidenceId("p15-${cp.checkpointId.value}"),
                    novelId = novelId,
                    type = AuthorEvidenceType.MODIFY,
                    detail = decision.changedFields.joinToString(","),
                    source = "p15:foundation:modify",
                    observedAt = decision.modifiedAt,
                )
            }
        }

        // 2) 每个 Proposal Revision 绑定的 Gate 决策（ADOPT / ADOPT_THEN_REVISE / REJECT / PARTIAL_REWRITE）
        for (revision in 1L..task.revisionCount.toLong()) {
            val gate = workflowRepository.getGateByKey(gateKey(workflow.workflowId, revision)) ?: continue
            val ev = when (gate.decision) {
                HumanDecision.APPROVED ->
                    if (revision > 1) AuthorEvidenceType.ADOPT_THEN_REVISE else AuthorEvidenceType.ADOPT
                HumanDecision.REJECTED -> AuthorEvidenceType.REJECT
                HumanDecision.REQUEST_REVISION -> AuthorEvidenceType.PARTIAL_REWRITE
                HumanDecision.PENDING -> null
            } ?: continue
            signals += AuthorEvidence(
                evidenceId = AuthorEvidenceId("p15-gate-${gate.gateId.value}"),
                novelId = novelId,
                type = ev,
                detail = "foundation proposal revision=$revision",
                source = "p15:foundation:gate",
                observedAt = gate.resolvedAt ?: gate.createdAt,
            )
        }
        return signals
    }

    /** Gate 唯一键：与 [com.qianyan.application.usecase.foundation.StoryFoundationDecisionUseCases.gateKey] 一致。 */
    private fun gateKey(workflowId: WorkflowId, revision: Long): String =
        "FOUNDATION:${workflowId.value}:$revision"

    private companion object {
        const val DECISION_KEY: String = "decision"
    }
}