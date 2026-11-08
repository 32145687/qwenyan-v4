package com.qianyan.application.usecase.story.worldmodelupdate

import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.application.error.ErrorMapper
import com.qianyan.application.usecase.UseCase
import com.qianyan.application.usecase.action.ActionPolicyUseCases
import com.qianyan.application.usecase.novel.NovelUseCases
import com.qianyan.model.EventId
import com.qianyan.model.NovelId
import com.qianyan.model.action.ActionDecision
import com.qianyan.model.action.AgentAction
import com.qianyan.model.action.AgentActionKind
import com.qianyan.model.knowledge.FactLevel
import com.qianyan.model.timeline.Event
import com.qianyan.model.timeline.EventStatus
import com.qianyan.model.timeline.EventType
import com.qianyan.model.worldmodel.CandidateFact
import com.qianyan.model.worldmodel.CandidateFactStatus
import com.qianyan.model.workflow.HumanDecision
import com.qianyan.model.workflow.HumanGateStatus
import com.qianyan.model.workflow.WorkflowHumanGate
import com.qianyan.model.workflow.WorkflowHumanGateId
import com.qianyan.storage.repository.StoryStateRepository
import com.qianyan.storage.repository.WorkflowRepository
import com.qianyan.storage.repository.WorldModelCandidateRepository
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/**
 * T-O06 · World Model 冲突 → Human Gate（方案 A：独立门）。
 *
 * 设计要点（与 T-O06-D 设计报告一致）：
 *  - **零 Schema**：复用 `WorkflowHumanGate`（v23 起 workflow_id/step_id/draft_id 可空）+ `gate_key` 唯一；
 *    门键规则 `wm-conflict:<candidateId>`（可反解，且与工作流门键 `${workflowId}:${stepId}:${draftId}` 不可能碰撞）；
 *  - **不碰 workflow.pendingGateId**：独立门不参与 `WAITING_HUMAN` / O-50 计数口径；
 *  - **写入不旁路 Change Layer**：Change Layer 只管正文 Canonical；事实沿用 Pipeline 唯一写入路径
 *    [StoryStateRepository.saveEvent]（新 id 插入），**绝不 UPDATE/DELETE** 既有事实行；
 *  - **采纳语义 = 同名并存**：人审确认"与既有同名事件是两条独立事实"，插入新 Event（`factLevel=USER_CONFIRMED`）；
 *    不合并、不覆盖（Original 的 UPDATE/DELETE 触发器亦禁止）；
 *  - **单事务**：候选 / 事实 / 门三者同一事务提交，任一失败整体回滚。
 */
class WorldModelConflictUseCases(
    private val candidates: WorldModelCandidateRepository,
    private val workflowRepository: WorkflowRepository,
    private val storyState: StoryStateRepository,
    private val novels: NovelUseCases,
    private val actionPolicy: ActionPolicyUseCases,
    errorMapper: ErrorMapper,
) : UseCase(errorMapper) {

    /**
     * 为一条 CONFLICT 候选**幂等**建立独立 Human Gate（在 Pipeline 的同一事务内调用）。
     *
     * 政策单一来源：`UPDATE_WORLD_MODEL` 在 ActionPolicy 中恒为 `NeedsHuman(HIGH)`；此处只断言、不新增分支。
     * 已有同键门（含已决议）⇒ 原样返回，不重复建门；插入竞态时回读同键门。
     */
    fun ensureGate(candidate: CandidateFact): WorkflowHumanGate {
        if (candidate.status != CandidateFactStatus.CONFLICT) {
            throw ApplicationException(
                ApplicationError.InvalidOperation("只有 CONFLICT 候选才能开人工门（实际 ${candidate.status}）"),
            )
        }
        val decision = actionPolicy.evaluate(
            AgentAction(
                kind = AgentActionKind.UPDATE_WORLD_MODEL,
                target = candidate.candidateId,
                note = "World Model 冲突待人工裁决",
            ),
        )
        if (decision !is ActionDecision.NeedsHuman) {
            throw ApplicationException(
                ApplicationError.InvalidOperation("UPDATE_WORLD_MODEL 必须要求人工确认（实际 ${decision::class.simpleName}）"),
            )
        }

        val key = gateKey(candidate.candidateId)
        workflowRepository.getGateByKey(key)?.let { return it }
        val gate = WorkflowHumanGate(
            gateId = WorkflowHumanGateId(nextId()),
            workflowId = null,
            stepId = null,
            draftId = null,
            gateKey = key,
            status = HumanGateStatus.PENDING,
            decision = HumanDecision.PENDING,
            createdAt = Clock.System.now(),
        )
        try {
            workflowRepository.createGate(gate)
        } catch (t: Throwable) {
            // 并发建门：唯一键冲突后回读；仍读不到 ⇒ 原异常上抛（不吞真实存储故障）。
            workflowRepository.getGateByKey(key)?.let { return it }
            throw t
        }
        return gate
    }

    /**
     * 按作品读取待裁决冲突（MVP：`listByStatus(CONFLICT)` + 内存过滤；不新增 SQL）。
     *
     * 只返回 CONFLICT；`recordFailure` 写下的失败标记行是 REJECTED，天然被排除。
     */
    fun listForNovel(novelId: NovelId): List<WorldModelConflictView> =
        guard { candidates.listByStatus(CandidateFactStatus.CONFLICT) }
            .filter { it.novelId == novelId }
            .sortedBy { it.createdAt }
            .map { c ->
                WorldModelConflictView(
                    candidateId = c.candidateId,
                    factName = c.factName,
                    summary = c.summary,
                    excerpt = c.sourceExcerpt,
                    conflictWith = c.conflictWith,
                    commitId = c.commitId,
                    createdAt = c.createdAt,
                )
            }

    fun pendingCountForNovel(novelId: NovelId): Int = listForNovel(novelId).size

    /**
     * 裁决一条冲突（单事务：候选 + 事实 + 门）。
     *
     * 幂等：候选已非 CONFLICT 时，同决议 ⇒ no-op 返回；反向决议 ⇒ [ApplicationError.InvalidOperation]。
     *
     * @param approve true = 采纳为独立事实（同名并存）；false = 拒绝（不写事实，仅留痕）。
     */
    fun resolve(candidateId: String, approve: Boolean, resolvedBy: String = "user"): ConflictResolution {
        var result: ConflictResolution? = null
        storyState.inTransaction {
            val candidate = guard { candidates.getById(candidateId) }
                ?: throw ApplicationException(ApplicationError.EntityNotFound("候选事实不存在: $candidateId"))
            val gate = guard { workflowRepository.getGateByKey(gateKey(candidateId)) }
                ?: throw ApplicationException(ApplicationError.EntityNotFound("冲突门不存在: ${gateKey(candidateId)}"))

            val expectedDecision = if (approve) HumanDecision.APPROVED else HumanDecision.REJECTED
            if (candidate.status != CandidateFactStatus.CONFLICT) {
                if (gate.status == HumanGateStatus.RESOLVED && gate.decision == expectedDecision) {
                    result = ConflictResolution(candidate, gate, createdEventId = null, alreadyResolved = true)
                    return@inTransaction
                }
                throw ApplicationException(
                    ApplicationError.InvalidOperation(
                        "候选 $candidateId 已裁决为 ${candidate.status}（门 ${gate.decision}），不能反向决议为 $expectedDecision",
                    ),
                )
            }

            val now = Clock.System.now()
            if (approve) {
                // Variant 隔离：作用域必须真实存在（Original 也会校验 Novel 存在）。
                guard { novels.getVariantContext(candidate.novelId, candidate.variantId) }
                val event = toEvent(candidate, now)
                guard { storyState.saveEvent(event) }
                guard {
                    candidates.updateStatus(
                        candidateId = candidateId,
                        status = CandidateFactStatus.ACCEPTED,
                        decisionReason = "同名并存：与 ${candidate.conflictWith ?: "既有事实"} 同名，人审确认新增独立事实",
                        conflictWith = candidate.conflictWith,
                        resultingFactId = event.id.value,
                    )
                }
                val resolved = gate.copy(
                    status = HumanGateStatus.RESOLVED,
                    decision = HumanDecision.APPROVED,
                    resolvedAt = now,
                    resolvedBy = resolvedBy,
                )
                guard { workflowRepository.updateGate(resolved) }
                result = ConflictResolution(
                    guard { candidates.getById(candidateId) } ?: candidate,
                    resolved,
                    createdEventId = event.id.value,
                    alreadyResolved = false,
                )
            } else {
                guard {
                    candidates.updateStatus(
                        candidateId = candidateId,
                        status = CandidateFactStatus.REJECTED,
                        decisionReason = "人审拒绝：与 ${candidate.conflictWith ?: "既有事实"} 冲突，不新增事实",
                        conflictWith = candidate.conflictWith,
                        resultingFactId = null,
                    )
                }
                val resolved = gate.copy(
                    status = HumanGateStatus.RESOLVED,
                    decision = HumanDecision.REJECTED,
                    resolvedAt = now,
                    resolvedBy = resolvedBy,
                )
                guard { workflowRepository.updateGate(resolved) }
                result = ConflictResolution(
                    guard { candidates.getById(candidateId) } ?: candidate,
                    resolved,
                    createdEventId = null,
                    alreadyResolved = false,
                )
            }
        }
        return checkNotNull(result)
    }

    /** 事实写入：新 id 插入（同名并存），`factLevel=USER_CONFIRMED` 标记"人审通过"。 */
    private fun toEvent(c: CandidateFact, now: Instant) = Event(
        id = EventId(nextId()),
        novelId = c.novelId,
        variantId = c.variantId,
        scope = c.scope,
        name = c.factName,
        description = c.summary,
        type = EventType.MAIN_PLOT,
        what = c.summary,
        factLevel = FactLevel.USER_CONFIRMED,
        status = EventStatus.CONFIRMED,
        chapterId = c.chapterId,
        createdAt = now,
    )

    companion object {
        /** 冲突门键规则：`wm-conflict:<candidateId>`。 */
        fun gateKey(candidateId: String): String = "wm-conflict:$candidateId"

        /** 供 UI 守卫/诊断判断门属于冲突域。 */
        const val GATE_KEY_PREFIX: String = "wm-conflict:"
    }
}

/** 一条待裁决冲突的只读投影（供故事 › 叙事账本）。 */
data class WorldModelConflictView(
    val candidateId: String,
    val factName: String,
    val summary: String,
    val excerpt: String,
    val conflictWith: String?,
    val commitId: String,
    val createdAt: Instant,
)

/** 一次裁决结果。 */
data class ConflictResolution(
    val candidate: CandidateFact,
    val gate: WorkflowHumanGate,
    /** 采纳时新事实的 eventId；拒绝/幂等时 null。 */
    val createdEventId: String?,
    val alreadyResolved: Boolean,
)
