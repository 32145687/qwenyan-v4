package com.qianyan.application.usecase.foundation

import com.qianyan.model.NovelId
import com.qianyan.model.workflow.WorkflowHumanGateId

/**
 * P15-B · FoundationDecisionGateway — Android / 未来 Desktop 与 Story Foundation 决策闭环之间的**用户层 Application seam**。
 *
 * **不是第二套 Proposal 逻辑 / 不是第二套状态机。** 仅把用户意图转发到底层
 * [StoryFoundationDecisionUseCases]（P15-A / P14-F）。UI 只经本接口，不得触碰
 * StoryFoundationRepository / Checkpoint / WorkflowHumanGate storage / SQLDelight。
 *
 * 只暴露 P15-B 冻结的 6 个决策方法；`prepareProposal` 仍属于 [StoryFoundationDecisionUseCases]，不是本 seam 的公开分叉。
 */
interface FoundationDecisionGateway {

    /** 只读展示当前 Proposal + Gate（FoundationProposalView）。 */
    fun presentProposal(novelId: NovelId): FoundationProposalView

    /** 用户修改 → 新 Proposal Revision + 新 PENDING Gate（复用 P15-A MODIFY 语义）。 */
    fun modifyProposal(novelId: NovelId, modified: FoundationProposal, decision: FoundationDecision? = null): PreparedFoundation

    /** 拒绝当前 Proposal（正常业务决策，非异常；幂等；不写 StoryFoundation）。 */
    fun rejectProposal(gateId: WorkflowHumanGateId, by: String = "user")

    /** 请求 AI 重新生成 Proposal（幂等；不写 StoryFoundation）。 */
    fun requestFoundationRevision(gateId: WorkflowHumanGateId, by: String = "user")

    /** 确认最新 Proposal → 写出 Confirmed StoryFoundation（幂等；仅最新 revision）。 */
    fun confirmFoundation(gateId: WorkflowHumanGateId, novelId: NovelId): ConfirmedFoundation

    /** 恢复当前 Proposal / revision / Gate / Decision（只读）。 */
    fun restoreProposal(novelId: NovelId): RestoredFoundation
}

/**
 * P15-B · FoundationDecisionFacade — [FoundationDecisionGateway] 的**极薄委托**实现。
 *
 * 每个方法仅转发到 [StoryFoundationDecisionUseCases]；不携带业务规则：不计算 revision / changedFields、
 * 不建 Gate、不校验 latest、不写 StoryFoundation、不操作 Checkpoint / Repository / 事务。
 * 异常沿用 [ApplicationException]，不新增异常体系 / ErrorMapper。
 */
class FoundationDecisionFacade(
    private val foundationDecisions: StoryFoundationDecisionUseCases,
) : FoundationDecisionGateway {

    override fun presentProposal(novelId: NovelId): FoundationProposalView =
        foundationDecisions.presentProposal(novelId)

    override fun modifyProposal(
        novelId: NovelId,
        modified: FoundationProposal,
        decision: FoundationDecision?,
    ): PreparedFoundation = foundationDecisions.modifyProposal(novelId, modified, decision)

    override fun rejectProposal(gateId: WorkflowHumanGateId, by: String) {
        foundationDecisions.rejectProposal(gateId, by)
    }

    override fun requestFoundationRevision(gateId: WorkflowHumanGateId, by: String) {
        foundationDecisions.requestFoundationRevision(gateId, by)
    }

    override fun confirmFoundation(gateId: WorkflowHumanGateId, novelId: NovelId): ConfirmedFoundation =
        foundationDecisions.confirmFoundation(gateId, novelId)

    override fun restoreProposal(novelId: NovelId): RestoredFoundation =
        foundationDecisions.restoreProposal(novelId)
}