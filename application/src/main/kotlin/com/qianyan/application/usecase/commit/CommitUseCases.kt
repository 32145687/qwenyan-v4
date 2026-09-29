package com.qianyan.application.usecase.commit

import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.application.error.ErrorMapper
import com.qianyan.application.usecase.UseCase
import com.qianyan.application.usecase.action.ActionPolicyUseCases
import com.qianyan.application.usecase.change.ChangeUseCases
import com.qianyan.application.usecase.chapter.ChapterUseCases
import com.qianyan.application.usecase.draft.WorkingDraftUseCases
import com.qianyan.application.usecase.project.ProjectUseCases
import com.qianyan.application.usecase.writing.WriterUseCases
import com.qianyan.model.DraftId
import com.qianyan.model.ProjectId
import com.qianyan.model.VariantScope
import com.qianyan.model.action.ActionDecision
import com.qianyan.model.action.AgentAction
import com.qianyan.model.action.AgentActionKind
import com.qianyan.model.change.ChangeArtifact
import com.qianyan.model.change.ChangeArtifactId
import com.qianyan.model.change.ChangeArtifactStatus
import com.qianyan.model.change.TextDiffer
import com.qianyan.model.commit.CommitApproval
import com.qianyan.model.commit.CommitErrorCodes
import com.qianyan.model.commit.CommitHistoryEntry
import com.qianyan.model.commit.CommitHistoryId
import com.qianyan.model.commit.CommitId
import com.qianyan.model.commit.CommitOperation
import com.qianyan.model.commit.CommitProposal
import com.qianyan.model.commit.CommitResult
import com.qianyan.model.commit.RevertProposal
import com.qianyan.model.commit.RevertResult
import com.qianyan.model.workingdraft.WorkingDraft
import com.qianyan.model.workingdraft.WorkingDraftBase
import com.qianyan.model.writing.Draft
import com.qianyan.model.writing.DraftStatus
import com.qianyan.model.workflow.HumanDecision
import com.qianyan.model.workflow.HumanGateStatus
import com.qianyan.storage.repository.CommitHistoryRepository
import com.qianyan.storage.repository.WorkflowRepository
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/**
 * I10 · Canonical Commit + History / Revert（Qianyan 的正式提交闭环）。
 *
 * 唯一 Canonical 提交入口（architecture §19 / §20 / §26）：
 * ```
 * Change Artifact（I9）
 *      ↓  prepareCommit / commit
 * ActionPolicy（I2）+ 既有 Human Gate（人工决定）
 *      ↓
 * **Atomic Commit**（单 DB 事务：Canonical 正文载体 + Commit History 一起成功或一起回滚）
 *      ↓
 * Canonical Story（ChapterDraft 版本链：新 Draft.previousDraftId = 旧 Draft）
 *      ↓
 * Commit History（不可变审计记录）
 *      ↓  prepareRevert / revert
 * **Atomic Revert**（同样是一次**新的** Commit；历史只增不删）
 * ```
 *
 * 硬边界：
 *  - **唯一入口**：输入必须是 I9 [ChangeArtifactId]，不接受调用方直接构造正文；Artifact 本身**冻结**（提交后不改它）；
 *  - **原子性**：复用既有 [WorkflowRepository.inTransaction]（`storage` 中所有 `Sqlite*` 仓储由**同一个**
 *    `QianyanDb(driver)` 构造 ⇒ 真实跨仓储 DB 事务），不新建第二套事务系统 / Storage 抽象；
 *  - **复用 I2**：放行判定委托 [ActionPolicyUseCases]（`COMMIT_CANONICAL` / `MODIFY_CANONICAL`）；
 *    需要人工时，凭据必须是**既有** Workflow Human Gate 的 `RESOLVED + APPROVED` 记录（[CommitApproval]）——
 *    不新建 Gate、不新建 Policy、不修改 P19 DecisionPolicy；
 *  - **不复用也不重造生命周期**：Working Draft 收尾复用 I8 `discard`；不改 AgentSession / Activity / Workflow / Task 状态；
 *  - **不是 Git**：History 只记录"Canonical 为何变化"（含 Revert 恢复所需的最小前后快照），
 *    不做 Branch / Merge / CRDT / Event Sourcing / 完整版本控制。
 */
class CommitUseCases(
    private val projects: ProjectUseCases,
    private val chapters: ChapterUseCases,
    private val workingDrafts: WorkingDraftUseCases,
    private val changes: ChangeUseCases,
    private val drafts: WriterUseCases,
    private val history: CommitHistoryRepository,
    private val workflows: WorkflowRepository,
    private val actionPolicy: ActionPolicyUseCases,
    private val clock: Clock = Clock.System,
    errorMapper: ErrorMapper,
) : UseCase(errorMapper) {

    /* ---------------- 权限：完全委托 I2（不新建第二套策略） ---------------- */

    /** 提交该 Artifact 需要的行动权限（返回 I2 的 [ActionDecision]，本类不做第二套判定）。 */
    fun evaluateCommit(artifact: ChangeArtifact): ActionDecision = actionPolicy.evaluate(
        AgentAction(
            kind = AgentActionKind.COMMIT_CANONICAL,
            target = "${artifact.change.target.novelId.value}/${artifact.change.target.chapterId.value}",
            note = "Change Artifact ${artifact.artifactId.value}",
        ),
    )

    /** 回退该历史需要的行动权限（回退是对 Canonical 的修改 ⇒ `MODIFY_CANONICAL`）。 */
    fun evaluateRevert(entry: CommitHistoryEntry): ActionDecision = actionPolicy.evaluate(
        AgentAction(
            kind = AgentActionKind.MODIFY_CANONICAL,
            target = "${entry.target.novelId.value}/${entry.target.chapterId.value}",
            note = "Revert Commit History ${entry.historyId.value}",
        ),
    )

    /* ---------------- Commit ---------------- */

    /**
     * 预备提交：完整校验 + 汇总"将写入什么"（**只读**，不改动任何数据；供 UI / Human Gate 审查）。
     *
     * @throws ApplicationError.EntityNotFound 越界（Project / Artifact / Working Draft / Chapter）
     * @throws ApplicationError.InvalidOperation 见 [CommitErrorCodes]
     */
    @Synchronized
    fun prepareCommit(projectId: ProjectId, artifactId: ChangeArtifactId): CommitProposal {
        val artifact = changes.get(artifactId)
        val draft = validateForCommit(projectId, artifact)
        val base = requireCurrentCanonical(artifact)
        return CommitProposal(
            artifactId = artifact.artifactId,
            projectId = projectId,
            workingDraftId = artifact.workingDraftId,
            target = artifact.change.target,
            summary = artifact.change.summary,
            previousDraftId = base?.draftId,
            previousContent = base?.content.orEmpty(),
            resultingContent = draft.content,
        )
    }

    /**
     * 执行一次 **Atomic Commit**：Canonical 正文载体与 Commit History 在同一 DB 事务内
     * 一起成功或一起回滚（不允许半提交）。
     *
     * 成功后再经 I8 既有能力把 Working Draft 置为终态（`DISCARDED`）；该收尾不参与 Canonical 事务，
     * 也不修改 AgentSession / Workflow / Task 状态。
     *
     * @param approval I2 判定为 `NeedsHuman` 时必须提供的**既有** Human Gate 批准凭据。
     */
    @Synchronized
    fun commit(
        projectId: ProjectId,
        artifactId: ChangeArtifactId,
        approval: CommitApproval? = null,
    ): CommitResult {
        val artifact = changes.get(artifactId)
        val draft = validateForCommit(projectId, artifact)
        requireApproval(evaluateCommit(artifact), approval, "Commit Artifact ${artifactId.value}")

        var committed: CommitResult? = null
        workflows.inTransaction {
            // 事务内重检 1：并发下同一 Artifact 仍不可能被提交两次
            if (history.getByArtifact(artifact.artifactId) != null) {
                throw invalid(CommitErrorCodes.ALREADY_COMMITTED, "Change Artifact 已被成功提交: ${artifact.artifactId.value}")
            }
            // 事务内重检 2：Canonical 在"校验 → 写入"之间若发生变化，一律拒绝（不静默覆盖）
            val base = requireCurrentCanonical(artifact)
            if (TextDiffer.diff(base?.content.orEmpty(), draft.content) != artifact.change.diff) {
                throw stale(artifact.artifactId, "校验与写入之间 Canonical 基线发生了变化")
            }

            val now = nextCreatedAt(base)
            val created = drafts.stampControlledMarkdown(
                newCanonicalDraft(
                    artifact = artifact,
                    base = base,
                    content = draft.content,
                    createdAt = now,
                ),
            )
            val entry = CommitHistoryEntry(
                historyId = CommitHistoryId(nextId()),
                commitId = CommitId(nextId()),
                projectId = projectId,
                operation = CommitOperation.COMMIT,
                artifactId = artifact.artifactId,
                target = artifact.change.target,
                previousDraftId = base?.draftId,
                resultingDraftId = created.draftId,
                previousContent = base?.content.orEmpty(),
                resultingContent = draft.content,
                summary = artifact.change.summary,
                revertedHistoryId = null,
                createdAt = now,
            )
            history.append(entry)
            committed = CommitResult(
                commitId = entry.commitId,
                artifactId = artifact.artifactId,
                projectId = projectId,
                target = artifact.change.target,
                previousDraftId = base?.draftId,
                resultingDraftId = created.draftId,
                createdAt = now,
                historyEntry = entry,
            )
        }
        // 事务成功即已赋值（失败则事务已回滚并抛出），故此处必然非空
        val result = committed!!
        // 提交成功后经 I8 既有能力收尾 Working Draft（终态 DISCARDED；不改 AgentSession / Workflow / Task）
        workingDrafts.discard(draft.workingDraftId)
        return result
    }

    /* ---------------- History ---------------- */

    /** Project 内的 Commit History（时序 = 插入序，确定性；越界 Project 返回空，不泄漏其他 Project）。 */
    @Synchronized
    fun history(projectId: ProjectId): List<CommitHistoryEntry> = guard { history.listByProject(projectId) }

    /**
     * 读取单条历史（**严格 Project 作用域**：跨 Project 统一表现为"不存在"，不泄漏存在性）。
     *
     * @throws ApplicationError.EntityNotFound 历史不存在或不属于该 Project。
     */
    @Synchronized
    fun historyEntry(projectId: ProjectId, historyId: CommitHistoryId): CommitHistoryEntry {
        val entry = guard { history.getById(historyId) }
        if (entry == null || entry.projectId != projectId) {
            throw ApplicationException(ApplicationError.EntityNotFound("Commit History 不存在: ${historyId.value}"))
        }
        return entry
    }

    /* ---------------- Revert ---------------- */

    /**
     * 预备回退：确认当前 Canonical 仍与该历史记录的"修改后"状态一致（**只读**，不改动任何数据）。
     *
     * 冲突（当前内容已被新的修改改变）→ [CommitErrorCodes.REVERT_CONFLICT]，禁止静默覆盖用户的新内容。
     *
     * @throws ApplicationError.EntityNotFound 历史不存在或不属于该 Project。
     */
    @Synchronized
    fun prepareRevert(projectId: ProjectId, historyId: CommitHistoryId): RevertProposal {
        val entry = historyEntry(projectId, historyId)
        if (entry.operation != CommitOperation.COMMIT) {
            throw invalid(
                CommitErrorCodes.INVALID_REVERT_TARGET,
                "仅 COMMIT 历史可被回退（history=${historyId.value} operation=${entry.operation.name}）",
            )
        }
        val latest = guard { drafts.latestDraft(entry.target.chapterId) }
            ?: throw conflict(historyId, "当前 Canonical 已不存在该章节正文载体")
        if (latest.content != entry.resultingContent) {
            throw conflict(historyId, "当前 Canonical 正文已不是该历史记录的提交结果（存在更新的修改）")
        }
        return RevertProposal(
            projectId = projectId,
            history = entry,
            currentDraftId = latest.draftId,
            currentContent = latest.content,
            restoreContent = entry.previousContent,
            summary = "Revert ${historyId.value}：恢复为「${entry.previousDraftId?.value ?: "无正文"}」内容",
        )
    }

    /**
     * 执行一次 **Atomic Revert**：**一次新的 Canonical Commit**（产生新 Draft + 新 History；
     * 原历史与既有 Canonical 正文**一律保留**，绝不删除）。
     *
     * 内部强制"先 prepare（含冲突检测）→ 再权限判定 → 才原子写入"，因此不存在绕过审查的直接回退。
     */
    @Synchronized
    fun revert(
        projectId: ProjectId,
        historyId: CommitHistoryId,
        approval: CommitApproval? = null,
    ): RevertResult {
        val proposal = prepareRevert(projectId, historyId)
        requireApproval(evaluateRevert(proposal.history), approval, "Revert History ${historyId.value}")

        var reverted: RevertResult? = null
        workflows.inTransaction {
            // 事务内重检（TOCTOU）：当前 Canonical 必须仍是该历史的"修改后"状态
            val latest = guard { drafts.latestDraft(proposal.history.target.chapterId) }
                ?: throw conflict(historyId, "当前 Canonical 已不存在该章节正文载体")
            if (latest.draftId != proposal.currentDraftId || latest.content != proposal.currentContent) {
                throw conflict(historyId, "校验与写入之间 Canonical 发生了变化")
            }

            val now = nextCreatedAt(latest)
            val created = drafts.stampControlledMarkdown(
                Draft(
                    draftId = DraftId(nextId()),
                    novelId = proposal.history.target.novelId,
                    variantId = proposal.history.target.variantId,
                    scope = scopeOf(proposal.history.target.variantId),
                    chapterId = proposal.history.target.chapterId,
                    planId = latest.planId,
                    previousDraftId = latest.draftId,
                    content = proposal.restoreContent,
                    status = DraftStatus.WRITTEN,
                    sourceModel = "",
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            val entry = CommitHistoryEntry(
                historyId = CommitHistoryId(nextId()),
                commitId = CommitId(nextId()),
                projectId = projectId,
                operation = CommitOperation.REVERT,
                artifactId = null,
                target = proposal.history.target,
                previousDraftId = latest.draftId,
                resultingDraftId = created.draftId,
                previousContent = latest.content,
                resultingContent = proposal.restoreContent,
                summary = proposal.summary,
                revertedHistoryId = proposal.history.historyId,
                createdAt = now,
            )
            history.append(entry)
            reverted = RevertResult(
                commitId = entry.commitId,
                projectId = projectId,
                target = proposal.history.target,
                revertedHistoryId = proposal.history.historyId,
                previousDraftId = latest.draftId,
                resultingDraftId = created.draftId,
                createdAt = now,
                historyEntry = entry,
            )
        }
        // 事务成功即已赋值（失败则事务已回滚并抛出），故此处必然非空
        return reverted!!
    }

    /* ---------------- internals ---------------- */

    /**
     * Commit 前置校验（§5 的十项硬条件；任一不满足一律拒绝，绝不自动覆盖冲突）。
     *
     * 顺序刻意固定：Project 作用域 → 重复提交 → Working Draft → target 合法 → Artifact 状态
     * → 过期（指纹）→ 基线 vs 当前 Canonical。
     */
    private fun validateForCommit(projectId: ProjectId, artifact: ChangeArtifact): WorkingDraft {
        // 1) Project 作用域（越界统一为"不存在"，不泄漏其他 Project 的存在性）
        if (artifact.projectId != projectId) {
            throw ApplicationException(ApplicationError.EntityNotFound("Change Artifact 不存在: ${artifact.artifactId.value}"))
        }
        guard { projects.projectOf(projectId) }
            ?: throw ApplicationException(ApplicationError.EntityNotFound("Project 不存在: ${projectId.value}"))

        // 2) 重复提交（存储级事实；先于其余校验，保证同一 Artifact 第二次提交稳定返回 ALREADY_COMMITTED）
        if (guard { history.getByArtifact(artifact.artifactId) } != null) {
            throw invalid(CommitErrorCodes.ALREADY_COMMITTED, "Change Artifact 已被成功提交: ${artifact.artifactId.value}")
        }

        // 3) Working Draft 必须存在、属于本 Project、对应同一 target，且未处于终态
        val draft = {
            val found = guard { workingDrafts.get(artifact.workingDraftId) }
            if (found.projectId != projectId || found.target != artifact.change.target) {
                throw ApplicationException(ApplicationError.EntityNotFound("Working Draft 不存在: ${artifact.workingDraftId.value}"))
            }
            found
        }()
        if (draft.status.isTerminal) {
            throw invalid(
                CommitErrorCodes.DISCARDED_WORKING_DRAFT,
                "Working Draft 已处于终态（${draft.status.name}），不可提交: ${draft.workingDraftId.value}",
            )
        }

        // 4) target 合法性（章节存在、属于 target Novel、作用域一致）——请求本身的合法性，先于"世界是否已变化"判定
        val chapter = guard { chapters.findById(artifact.change.target.chapterId) }
        if (
            chapter == null ||
            chapter.novelId != artifact.change.target.novelId ||
            chapter.variantId != artifact.change.target.variantId
        ) {
            throw invalid(
                CommitErrorCodes.INVALID_TARGET,
                "target 非法：chapter=${artifact.change.target.chapterId.value}",
            )
        }

        // 5) Artifact 事实状态（NO_CHANGES / INVALID 一律不可提交）
        when (artifact.status) {
            ChangeArtifactStatus.NO_CHANGES ->
                throw invalid(CommitErrorCodes.NO_CHANGES, "Artifact 与基线无任何变化: ${artifact.artifactId.value}")

            ChangeArtifactStatus.INVALID ->
                throw invalid(CommitErrorCodes.INVALID_ARTIFACT, "Artifact 未通过 Validation（存在 ERROR）: ${artifact.artifactId.value}")

            ChangeArtifactStatus.READY -> Unit
        }

        // 6) 过期判定：Working Draft 内容 / target / 基线元数据 / Validation 已不再与 Artifact 一致
        if (guard { changes.isSuperseded(artifact.artifactId) }) {
            throw stale(artifact.artifactId, "Working Draft 或其基线已被后续变化取代")
        }

        // 7) 基线 vs 当前 Canonical（内容级一致性；不依赖时间戳精度）
        val base = requireCurrentCanonical(artifact)
        if (TextDiffer.diff(base?.content.orEmpty(), draft.content) != artifact.change.diff) {
            throw stale(artifact.artifactId, "Artifact 记录的 Diff 与当前 Canonical 基线不一致")
        }
        return draft
    }

    /**
     * 当前 Canonical 正文载体（章节最新 Draft）与 Artifact 记录的基线是否仍然一致；
     * 不一致 → [CommitErrorCodes.STALE_ARTIFACT]。
     *
     * 有意以**内容**（Diff 重算，由调用方比对）而非 `updatedAt` 判定基线是否变化：
     * 内容才是语义基线，时间戳只有毫秒精度、会产生假阳性。
     */
    private fun requireCurrentCanonical(artifact: ChangeArtifact): Draft? {
        val recorded: WorkingDraftBase = artifact.change.base
        val target = artifact.change.target
        val latest = guard { drafts.latestDraft(target.chapterId) }

        val baseDraftId = recorded.baseDraftId
        if (baseDraftId == null) {
            // 生成 Artifact 时该章节尚无 Canonical Draft；一旦出现即视为基线已被改变
            if (latest != null) {
                throw stale(artifact.artifactId, "基线 Draft 从「无」变为 ${latest.draftId.value}")
            }
            return null
        }
        if (latest == null || latest.draftId != baseDraftId) {
            throw stale(
                artifact.artifactId,
                "基线 Draft ${baseDraftId.value} 已不是该章节的 Canonical 正文载体" +
                    "（当前：${latest?.draftId?.value ?: "无"}）",
            )
        }
        if (latest.status != recorded.baseDraftStatus) {
            throw stale(
                artifact.artifactId,
                "基线 Draft 状态已变化：${recorded.baseDraftStatus?.name} → ${latest.status.name}",
            )
        }
        val chapter = guard { chapters.findById(target.chapterId) }
        if (chapter == null || chapter.status != recorded.baseChapterStatus) {
            throw stale(
                artifact.artifactId,
                "Chapter 状态已变化：${recorded.baseChapterStatus?.name} → ${chapter?.status?.name}",
            )
        }
        return latest
    }

    /** I2 判定复用：`Denied` → 拒绝；`NeedsHuman` → 必须有**既有** Human Gate 的批准凭据。 */
    private fun requireApproval(decision: ActionDecision, approval: CommitApproval?, subject: String) {
        when (decision) {
            is ActionDecision.Denied -> throw invalid(
                CommitErrorCodes.ACTION_DENIED,
                "动作被 ActionPolicy（I2）拒绝：${decision.reason}；$subject",
            )

            is ActionDecision.Allowed -> Unit

            is ActionDecision.NeedsHuman -> {
                val gate = approval?.let { guard { workflows.getGate(it.gateId) } }
                val approved = gate != null &&
                    gate.status == HumanGateStatus.RESOLVED &&
                    gate.decision == HumanDecision.APPROVED
                if (!approved) {
                    throw invalid(
                        CommitErrorCodes.HUMAN_APPROVAL_REQUIRED,
                        "动作需要人工确认（${decision.reason}），须先经既有 Workflow Human Gate 批准；$subject",
                    )
                }
            }
        }
    }

    /** 新建 Canonical 正文载体（版本链 head：`previousDraftId` = 基线；不修改 / 不删除既有 Draft）。 */
    private fun newCanonicalDraft(
        artifact: ChangeArtifact,
        base: Draft?,
        content: String,
        createdAt: Instant,
    ): Draft = Draft(
        draftId = DraftId(nextId()),
        novelId = artifact.change.target.novelId,
        variantId = artifact.change.target.variantId,
        scope = scopeOf(artifact.change.target.variantId),
        chapterId = artifact.change.target.chapterId,
        planId = base?.planId,
        previousDraftId = base?.draftId,
        content = content,
        status = DraftStatus.WRITTEN,
        sourceModel = "",
        createdAt = createdAt,
        updatedAt = createdAt,
    )

    private fun scopeOf(variantId: com.qianyan.model.VariantId?): VariantScope =
        if (variantId == null) VariantScope.ORIGINAL else VariantScope.VARIANT

    /**
     * 新 Canonical 正文载体的时间戳：**严格晚于**当前正文载体。
     *
     * 必要性：`latestByChapter` 按 `created_at DESC, draft_id DESC` 取最新，同毫秒平局会让"刚提交的正文"
     * 不被视为章节最新正文（提交结果必须成为章节最新 Canonical 正文）。
     */
    private fun nextCreatedAt(after: Draft?): Instant {
        val now = Instant.fromEpochMilliseconds(clock.now().toEpochMilliseconds())
        val bound = after?.createdAt?.toEpochMilliseconds()?.plus(1)?.let { Instant.fromEpochMilliseconds(it) }
        return if (bound != null && bound > now) bound else now
    }

    private fun invalid(code: String, detail: String): ApplicationException =
        ApplicationException(ApplicationError.InvalidOperation("[$code] $detail"))

    private fun stale(artifactId: ChangeArtifactId, detail: String): ApplicationException =
        invalid(CommitErrorCodes.STALE_ARTIFACT, "Artifact ${artifactId.value} 已过期（基线/内容不一致）：$detail")

    private fun conflict(historyId: CommitHistoryId, detail: String): ApplicationException =
        invalid(CommitErrorCodes.REVERT_CONFLICT, "无法回退 ${historyId.value}：$detail")
}