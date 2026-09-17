package com.qianyan.application.usecase.foundation

import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.application.error.ErrorMapper
import com.qianyan.application.usecase.UseCase
import com.qianyan.application.usecase.task.TaskManagerUseCases
import com.qianyan.application.usecase.workflow.WorkflowService
import com.qianyan.model.BaseNovelId
import com.qianyan.model.GenreId
import com.qianyan.model.NovelId
import com.qianyan.model.TaskId
import com.qianyan.model.VariantScope
import com.qianyan.model.foundation.NarrativeProfile
import com.qianyan.model.foundation.StoryDirection
import com.qianyan.model.foundation.StoryFoundation
import com.qianyan.model.foundation.WritingPolicy
import com.qianyan.model.task.Checkpoint
import com.qianyan.model.task.TaskType
import com.qianyan.model.workflow.HumanDecision
import com.qianyan.model.workflow.HumanGateStatus
import com.qianyan.model.workflow.Workflow
import com.qianyan.model.workflow.WorkflowHumanGate
import com.qianyan.model.workflow.WorkflowHumanGateId
import com.qianyan.model.workflow.WorkflowId
import com.qianyan.model.workflow.WorkflowKind
import com.qianyan.model.workflow.WorkflowStatus
import com.qianyan.storage.repository.StoryFoundationRepository
import com.qianyan.storage.repository.TaskRepository
import com.qianyan.storage.repository.WorkflowRepository
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement

/**
 * P14-F.3 · Story Foundation Confirmation Flow（Application 边界，最小入口）。
 *
 * 已冻结架构（D1 / D2 / D2b）：
 *  - **Proposal** 是 workflow-scoped、临时、可改/可拒/可重生成的过程性产物，绝不直接写入 StoryFoundation；
 *    存入 **Task Checkpoint**（stage = [STORY_FOUNDATION_PROPOSAL]），不新增 Proposal 表。
 *  - **Decision** 复用 **WorkflowHumanGate**（APPROVED / REJECTED / REQUEST_REVISION），不新增任何 Gate 类型 /
 *    WorkflowKind / WorkflowStepPhase。
 *  - D1：MODIFY = 新 Proposal Revision（新 checkpoint revision + 新 PENDING Gate）；旧 Proposal 保留，
 *    旧 Gate 不作用于新 Proposal。核心规则：**一个 Human Decision 只能作用于它绑定的 Proposal Revision**。
 *  - D2：Foundation 确认承载于 `WorkflowKind.WRITE_NOVEL` durable container，是既有 PLANNING 的**前置确认**，
 *    不是新 Workflow 阶段 / 不修改状态机。
 *  - D2b：Original StoryFoundation 一旦 Confirm 即 SEALED；本阶段**不允许再次覆盖**。version 字段保留但不用于
 *    重复覆盖语义（未来独立阶段设计 Foundation Revision/Editing）。
 *
 * 原子性：**Gate resolve 与 Confirmed StoryFoundation upsert 在同一数据库事务内**（[confirmFoundation]），
 * 避免"Gate APPROVED 但 Foundation 未写入"的崩溃不一致。
 */
class StoryFoundationDecisionUseCases(
    private val workflowRepository: WorkflowRepository,
    private val taskManager: TaskManagerUseCases,
    private val taskRepository: TaskRepository,
    private val storyFoundationRepository: StoryFoundationRepository,
    private val workflowService: WorkflowService,
    errorMapper: ErrorMapper,
) : UseCase(errorMapper) {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * prepare：创建/获取 WRITE_NOVEL container → 保存 Proposal 到 Checkpoint → 创建 PENDING Gate。
     * **幂等**：若当前关键已存在且等于入参 Proposal 且 Gate 仍 PENDING，则直接返回既有产物，不重复建 Gate。
     */
    fun prepareProposal(novelId: NovelId, proposal: FoundationProposal): PreparedFoundation {
        val workflow = getOrCreateFoundationWorkflow(novelId)
        val taskId = foundationTaskId(workflow)
        getOrCreateFoundationTask(taskId)
        // 幂等：最新 Checkpoint 已承载相同 Proposal 且其 Gate 仍 PENDING → 复用。
        val latest = taskRepository.findLatestCheckpoint(taskId)
        if (latest != null) {
            val existing = decodeProposal(latest)
            val gate = workflowRepository.getGateByKey(gateKey(workflow.workflowId, existing.proposalRevision))
            if (sameContent(existing, proposal) && gate != null && gate.status == HumanGateStatus.PENDING) {
                return PreparedFoundation(workflow, existing, gate)
            }
        }
        return stageNext(workflow, novelId, proposal)
    }

    /** MODIFY（D1=B，D-A）：总是写入新的 Proposal Revision + 新的 PENDING Gate；旧 Proposal 保留、旧 Gate 失效。
     *  可选 [decision] 记录用户本次修改的结构化信息（sourceRevision=当前 revision；缺省时由其自动推导字段 diff）。 */
    fun modifyProposal(novelId: NovelId, modified: FoundationProposal): PreparedFoundation =
        modifyProposal(novelId, modified, null)

    fun modifyProposal(novelId: NovelId, modified: FoundationProposal, decision: FoundationDecision?): PreparedFoundation {
        val workflow = getOrCreateFoundationWorkflow(novelId)
        val taskId = foundationTaskId(workflow)
        getOrCreateFoundationTask(taskId)
        val storedDecision = decision ?: autoDecision(novelId, modified)
        return stageNext(workflow, novelId, modified, storedDecision)
    }

    /**
     * ACCEPT / CONFIRM：Gate(Request(Revision 匹配当前最新 Proposal)) PENDING→APPROVED，并在**同一事务**内写入
     * Confirmed StoryFoundation（scope=ORIGINAL，SEALED）。幂等：重复 ACCEPT（同 Gate）→ NO-OP 返回既有确认。
     *
     * 拒绝：Gate 不存在 / 已 resolve 为其它决策 / Gate 绑定的 Revision ≠ 当前 Proposal Revision（旧 Gate 批新 Proposal）/
     * StoryFoundation 已确认（D2b 不允许覆盖）。
     */
    fun confirmFoundation(gateId: WorkflowHumanGateId, novelId: NovelId): ConfirmedFoundation {
        val gate = requireGate(gateId)
        val workflow = requireWorkflow(gate.workflowId)

        if (gate.status == HumanGateStatus.RESOLVED) {
            return when (gate.decision) {
                // 幂等 NO-OP：同 Gate 重复确认返回既有已写 Foundation。
                HumanDecision.APPROVED -> {
                    val existing = storyFoundationRepository.getStoryFoundation(novelId)
                        ?: throw Operation("Gate ${gate.gateId.value} 已 APPROVED 但 StoryFoundation 缺失（不一致状态）")
                    ConfirmedFoundation(existing, gate)
                }
                HumanDecision.REJECTED, HumanDecision.REQUEST_REVISION -> throw Operation(
                    "Gate ${gate.gateId.value} 已 ${gate.decision}，不能再 APPROVE",
                )
                HumanDecision.PENDING -> throw Operation("Gate ${gate.gateId.value} 决策状态异常")
            }
        }

        // Gate 仍 PENDING：校验 Gate 绑定的 Revision 就是当前最新 Proposal Revision（旧 Gate 不得批新 Proposal）。
        val revision = revisionOf(gate.gateKey)
        val taskId = foundationTaskId(workflow)
        val latest = taskRepository.findLatestCheckpoint(taskId)
            ?: throw AppCheckpointNotFound("Workflow ${workflow.workflowId.value} 无 Proposal Checkpoint，无法确认")
        if (revision != latest.revision.toLong()) {
            throw Operation(
                "Gate ${gate.gateId.value} 绑定 Proposal Revision=$revision，但当前 Proposal 为 Revision=${latest.revision}（旧 Gate 不能批准新 Proposal）",
            )
        }

        // D2b：Original Foundation 已确认 → 拒绝覆盖（SEALED）。
        if (storyFoundationRepository.getStoryFoundation(novelId) != null) {
            throw Operation(
                "Original Novel(${novelId.value}) 的 StoryFoundation 已确认（SEALED），P14-F.3 不允许再次覆盖",
            )
        }

        val proposal = decodeProposal(latest)
        val confirmed = StoryFoundation(
            novelId = novelId,
            baseNovelId = BaseNovelId(novelId.value),
            scope = VariantScope.ORIGINAL,
            version = 1L,
            genre = proposal.genre,
            direction = proposal.direction,
            audience = proposal.audience,
            policy = proposal.policy,
            createdAt = Clock.System.now(),
            updatedAt = Clock.System.now(),
        )

        // 原子：Gate resolve + Foundation upsert 同事务（同一 SQLite 连接）。
        workflowRepository.inTransaction {
            val g = workflowRepository.getGate(gate.gateId)
                ?: throw AppEntityNotFound("Gate ${gate.gateId.value} 事务内消失")
            if (g.status == HumanGateStatus.PENDING) {
                workflowRepository.updateGate(
                    g.copy(
                        status = HumanGateStatus.RESOLVED,
                        decision = HumanDecision.APPROVED,
                        resolvedAt = Clock.System.now(),
                        resolvedBy = "user",
                    ),
                )
                workflowRepository.updateWorkflow(
                    workflow.copy(
                        status = WorkflowStatus.RUNNING,
                        pendingGateId = g.gateId,
                        updatedAt = Clock.System.now(),
                    ),
                )
                storyFoundationRepository.upsertStoryFoundation(confirmed)
            }
            // 并发竞态：若其它调用已 resolve，则本调用不重复写入（at-most-once）。
        }

        val resolvedGate = workflowRepository.getGate(gate.gateId)!!
        val persisted = storyFoundationRepository.getStoryFoundation(novelId)
            ?: throw Operation("Gate ${gate.gateId.value} 已 APPROVED 但 StoryFoundation 未写入")
        return ConfirmedFoundation(persisted, resolvedGate)
    }

    /** REJECT：正常业务决策（非 FAILED / 非异常）。幂等。复用现有 [WorkflowService.rejectGate]。 */
    fun rejectProposal(gateId: WorkflowHumanGateId, by: String = "user") {
        workflowService.rejectGate(gateId, HumanDecision.REJECTED, by)
    }

    /** REQUEST_REVISION：幂等。复用现有 [WorkflowService.rejectGate]（REQUEST_REVISION）。 */
    fun requestFoundationRevision(gateId: WorkflowHumanGateId, by: String = "user") {
        workflowService.rejectGate(gateId, HumanDecision.REQUEST_REVISION, by)
    }

    /* ---------------- 内部 ---------------- */

    /** 幂等地把最新 Proposal（当前 revision）+ 其 Gate + 其 Decision 装载出来（Checkpoint 恢复，只读不重跑）。 */
    fun restoreProposal(novelId: NovelId): RestoredFoundation {
        val workflow = getOrCreateFoundationWorkflow(novelId)
        val taskId = foundationTaskId(workflow)
        val latest = taskRepository.findLatestCheckpoint(taskId)
            ?: throw AppCheckpointNotFound("Novel(${novelId.value}) 无 Proposal Checkpoint")
        val proposal = decodeProposal(latest)
        val gate = workflowRepository.getGateByKey(gateKey(workflow.workflowId, proposal.proposalRevision))
        return RestoredFoundation(workflow, proposal, proposal.proposalRevision, gate, decodeDecision(latest))
    }

    /**
     * P15-A · 只读 Presentation：把当前 Proposal + Gate 组装为 UI 可读/可比较的 [FoundationProposalView]。
     * 纯组合 [restoreProposal]，不新建查询体系；UI 不接触 Checkpoint / Gate storage / StoryFoundationRepository。
     * 不存在 Proposal → CheckpointNotFound（与 [restoreProposal] 一致）。
     */
    fun presentProposal(novelId: NovelId): FoundationProposalView {
        val r = restoreProposal(novelId)
        val gate = r.gate
        val pending = gate != null && gate.status == HumanGateStatus.PENDING
        return FoundationProposalView(
            proposalId = "foundation:${r.workflow.workflowId.value}:${r.proposalRevision}",
            revision = r.proposalRevision,
            genre = r.proposal.genre,
            direction = r.proposal.direction,
            audience = r.proposal.audience,
            writingPolicy = r.proposal.policy,
            gateStatus = gate?.status,
            gateDecision = gate?.decision,
            canModify = pending,
            canReject = pending,
            canRequestRevision = pending,
            canConfirm = pending,
        )
    }

    /** 写一个新 Proposal Revision 的 Checkpoint + 新 PENDING Gate（携带可选 [decision]）。 */
    private fun stageNext(workflow: Workflow, novelId: NovelId, proposal: FoundationProposal, decision: FoundationDecision? = null): PreparedFoundation {
        val taskId = foundationTaskId(workflow)
        val task = taskManager.findById(taskId)
        val nextRevision = (task.revisionCount + 1).toLong()
        val staged = proposal.copy(proposalRevision = nextRevision)
        taskManager.saveCheckpoint(taskId, STORY_FOUNDATION_PROPOSAL, snapshot(staged, decision))
        val gate = createFoundationGate(workflow, novelId, nextRevision)
        return PreparedFoundation(workflow, staged, gate)
    }

    /** 自动推导缺省 Decision：sourceRevision=当前 revision，changedFields=当前与待写 Proposal 的字段 diff。 */
    private fun autoDecision(novelId: NovelId, modified: FoundationProposal): FoundationDecision {
        val current = restoreProposal(novelId).proposal
        return FoundationDecision(
            sourceRevision = current.proposalRevision,
            changedFields = changedFields(current, modified),
            userReason = null,
            modifiedAt = Clock.System.now(),
        )
    }

    private fun changedFields(before: FoundationProposal, after: FoundationProposal): Set<FoundationDecisionField> = buildSet {
        if (before.genre != after.genre) add(FoundationDecisionField.GENRE)
        if (before.direction != after.direction) add(FoundationDecisionField.DIRECTION)
        if (before.audience != after.audience) add(FoundationDecisionField.AUDIENCE)
        if (before.policy != after.policy) add(FoundationDecisionField.POLICY)
    }

    private fun getOrCreateFoundationWorkflow(novelId: NovelId): Workflow {
        val id = WorkflowId("foundation-${novelId.value}")
        workflowRepository.getWorkflow(id)?.let { return it }
        val now = Clock.System.now()
        val wf = Workflow(
            workflowId = id,
            novelId = novelId,
            variantId = null,
            kind = WorkflowKind.WRITE_NOVEL,
            definitionVersion = "P12.2-v1",
            status = WorkflowStatus.RUNNING,
            activeChapterId = null,
            currentStepId = null,
            pendingGateId = null,
            createdAt = now,
            updatedAt = now,
        )
        guard { workflowRepository.createWorkflow(wf) }
        return wf
    }

    private fun getOrCreateFoundationTask(taskId: TaskId) {
        if (taskRepository.findById(taskId) == null) {
            guard { taskManager.create(TaskType.PLANNING, taskId) }
        }
    }

    private fun createFoundationGate(workflow: Workflow, novelId: NovelId, revision: Long): WorkflowHumanGate {
        val key = gateKey(workflow.workflowId, revision)
        workflowRepository.getGateByKey(key)?.let {
            if (it.status == HumanGateStatus.PENDING) return it // 幂等：同一 Proposal Revision 不再重复建 Gate
        }
        val now = Clock.System.now()
        val gate = WorkflowHumanGate(
            gateId = WorkflowHumanGateId(java.util.UUID.randomUUID().toString()),
            workflowId = workflow.workflowId,
            stepId = null,
            draftId = null,
            gateKey = key,
            status = HumanGateStatus.PENDING,
            decision = HumanDecision.PENDING,
            createdAt = now,
        )
        guard { workflowRepository.createGate(gate) }
        guard {
            workflowRepository.updateWorkflow(
                workflow.copy(
                    status = WorkflowStatus.WAITING_HUMAN,
                    pendingGateId = gate.gateId,
                    updatedAt = now,
                ),
            )
        }
        return gate
    }

    private fun foundationTaskId(workflow: Workflow): TaskId = TaskId("foundation-${workflow.workflowId.value}")

    /** Gate 唯一键：`FOUNDATION:<workflowId>:<novelId>:<proposalRevision>`。与既有关节式章节 Gate（workflowId:stepId:draftId）不冲突。 */
    private fun gateKey(workflowId: WorkflowId, revision: Long): String =
        "FOUNDATION:${workflowId.value}:${revision}"

    /** 从 gateKey 解析绑定的 Proposal Revision。 */
    private fun revisionOf(gateKey: String): Long =
        gateKey.substringAfterLast(':').toLong()

    private fun snapshot(proposal: FoundationProposal, decision: FoundationDecision? = null): JsonObject {
        val fields = mutableMapOf(PROPOSAL_KEY to json.encodeToJsonElement(proposal))
        if (decision != null) fields[DECISION_KEY] = json.encodeToJsonElement(decision)
        return JsonObject(fields)
    }

    private fun decodeDecision(checkpoint: Checkpoint): FoundationDecision? {
        val element = checkpoint.snapshot?.get(DECISION_KEY) ?: return null
        return json.decodeFromJsonElement(FoundationDecision.serializer(), element)
    }

    /** 幂等判定：仅比较 Proposal 内容字段（proposalRevision 为运行时字段，不参与内容相等）。 */
    private fun sameContent(a: FoundationProposal, b: FoundationProposal): Boolean =
        a.genre == b.genre &&
            a.direction == b.direction &&
            a.audience == b.audience &&
            a.policy == b.policy

    private fun decodeProposal(checkpoint: Checkpoint): FoundationProposal {
        val element = checkpoint.snapshot?.get(PROPOSAL_KEY)
            ?: throw AppCheckpointNotFound("Checkpoint ${checkpoint.checkpointId.value} 缺少 Proposal 负载")
        return json.decodeFromJsonElement(FoundationProposal.serializer(), element)
    }

    private fun requireGate(id: WorkflowHumanGateId): WorkflowHumanGate =
        workflowRepository.getGate(id)
            ?: throw AppEntityNotFound("Gate 不存在: ${id.value}")

    private fun requireWorkflow(id: WorkflowId): Workflow =
        workflowRepository.getWorkflow(id)
            ?: throw AppEntityNotFound("Workflow 不存在: ${id.value}")

    private fun Operation(detail: String): ApplicationException =
        ApplicationException(ApplicationError.InvalidOperation(detail))

    private fun AppEntityNotFound(detail: String): ApplicationException =
        ApplicationException(ApplicationError.EntityNotFound(detail))

    private fun AppCheckpointNotFound(detail: String): ApplicationException =
        ApplicationException(ApplicationError.CheckpointNotFound(detail))

    private companion object {
        const val STORY_FOUNDATION_PROPOSAL: String = "STORY_FOUNDATION_PROPOSAL"
        const val PROPOSAL_KEY: String = "proposal"
        const val DECISION_KEY: String = "decision"
    }
}

/** Story Foundation Proposal（workflow-scoped 临时过程产物；不直接成为 Confirmed Fact）。 */
@Serializable
data class FoundationProposal(
    val proposalRevision: Long = 0L,
    val genre: List<GenreId> = emptyList(),
    val direction: StoryDirection = StoryDirection(),
    val audience: NarrativeProfile = NarrativeProfile(),
    val policy: WritingPolicy = WritingPolicy(),
)

/** prepare/modify 结果：workflow container + 已保存 Proposal + 其 PENDING Gate。 */
data class PreparedFoundation(
    val workflow: Workflow,
    val proposal: FoundationProposal,
    val gate: WorkflowHumanGate,
)

/** 恢复结果：workflow + 最新 Proposal + 其 revision + 绑定的 Gate + 产生该 revision 的 Decision（若来自 MODIFY）。 */
data class RestoredFoundation(
    val workflow: Workflow,
    val proposal: FoundationProposal,
    val proposalRevision: Long,
    val gate: WorkflowHumanGate?,
    val decision: FoundationDecision? = null,
)

/** confirm 成功结果：SEALED Confirmed Foundation + resolved Gate + 可进入既有 PLANNING。 */
data class ConfirmedFoundation(
    val foundation: StoryFoundation,
    val gate: WorkflowHumanGate,
    val canEnterPlanning: Boolean = true,
)

/** P15-A · 用户修改的具体内容（D-A：结构化 Decision Payload，不修改 WorkflowHumanGate 表结构）。
 *  `sourceRevision` = 修改前的 Proposal Revision N；oldValue 可由 N 的 Checkpoint 恢复，故不复制明细。 */
@Serializable
data class FoundationDecision(
    val sourceRevision: Long,
    val changedFields: Set<FoundationDecisionField>,
    val userReason: String? = null,
    val modifiedAt: Instant,
)

/** 用户主动修改的 Foundation 字段（仅这四类，不扩展为通用 Decision 元数据）。 */
@Serializable
enum class FoundationDecisionField { GENRE, DIRECTION, AUDIENCE, POLICY }

/**
 * P15-A · Proposal 只读 Presentation Model（UI 可读/可比较，不承载状态转换）。
 * 不直接暴露 StoryFoundation / Repository / Checkpoint JSON / Gate storage；`can*` 由当前 Gate 状态确定性派生。
 */
@Serializable
data class FoundationProposalView(
    val proposalId: String,
    val revision: Long,
    val genre: List<GenreId>,
    val direction: StoryDirection,
    val audience: NarrativeProfile,
    val writingPolicy: WritingPolicy,
    val gateStatus: HumanGateStatus?,
    val gateDecision: HumanDecision?,
    val canModify: Boolean,
    val canReject: Boolean,
    val canRequestRevision: Boolean,
    val canConfirm: Boolean,
)