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
import com.qianyan.application.usecase.writing.WritingSnapshot
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
import com.qianyan.model.commit.CommitFormatHistory
import com.qianyan.application.usecase.story.worldmodelupdate.WorldModelUpdateException
import com.qianyan.application.usecase.story.worldmodelupdate.WorldModelUpdateUseCases
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
import com.qianyan.model.workingdraft.WorkingDraftTarget
import com.qianyan.model.writing.Draft
import com.qianyan.model.writing.DraftFormat
import com.qianyan.model.writing.DraftStatus
import com.qianyan.model.workflow.HumanDecision
import com.qianyan.model.workflow.HumanGateStatus
import com.qianyan.model.workflow.WorkflowHumanGateId
import com.qianyan.model.workflow.WorkflowStepPhase
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
    /** Phase 3 · World Model Update Pipeline（v3.1 §12）：仅在成功提交后触发；null = 未装配（既有测试路径）。 */
    private val worldModelUpdate: WorldModelUpdateUseCases? = null,
    /**
     * P0-3B D9 · 测试/诊断用后处理 seam：Commit 事务成功后的 WorkingDraft 收尾。
     * 生产默认 = I8 `discard`；注入点仅用于验证“提交已成功、收尾失败”的非致命语义，不参与事务。
     */
    private val discardWorkingDraft: (com.qianyan.model.workingdraft.WorkingDraftId) -> Unit = {
        workingDrafts.discard(it)
    },
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
        if (approval != null) requireApprovalBinding(approval, artifact)

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
                gateId = approval?.gateId,
                target = artifact.change.target,
                previousDraftId = base?.draftId,
                resultingDraftId = created.draftId,
                previousContent = base?.content.orEmpty(),
                resultingContent = draft.content,
                summary = artifact.change.summary,
                revertedHistoryId = null,
                previousFormat = base?.let { CommitFormatHistory.ofDraftFormat(it.format) }
                    ?: CommitFormatHistory.NONE,
                resultingFormat = CommitFormatHistory.ofDraftFormat(created.format),
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
        // P0-3B D9：提交事实已确立（CommitHistory 持久）；事务外收尾失败不得把成功提交误报为失败。
        // Working Draft 收尾（终态 DISCARDED）与 World Model 更新均为事务外动作，失败仅记入 postCommitWarning。
        val warnings = mutableListOf<String>()
        runCatching { discardWorkingDraft(draft.workingDraftId) }
            .onFailure { warnings.add("WorkingDraft 收尾失败: ${it.message ?: it::class.simpleName}") }
        // Phase 3：Commit 已持久化 → World Model Update Pipeline（唯一合法触发点在事务成功之后）
        runCatching { runWorldModelUpdate(result.historyEntry) }
            .onFailure { warnings.add("World Model 更新失败: ${it.message ?: it::class.simpleName}") }
        return if (warnings.isEmpty()) result else result.copy(postCommitWarning = warnings.joinToString("; "))
    }

    /* ---------------- 作者手改保存（P1-01f：保存即批准） ---------------- */

    /**
     * **作者手动保存 → Canonical**（v3.1 §4.1「作者手工编辑 → 允许直接保存」）。
     *
     * 与 [commit] 的区别：输入是**作者正在编辑的 Draft + 即将保存的正文**，而非 Change Artifact。
     * 作者点击保存 = 作者明确批准当前正文 ⇒ **不经** Working Draft / Change / Proposal / Human Gate；
     * 但 Canonical 的写入仍**只经本类**（`CommitHistory.append` 的唯一入口，不新建第二套 Canonical 入口）。
     *
     * 语义要点：
     *  - **基线取已提交的 Canonical 事实**（该章节最后一条 `CommitHistory`），**不是** `latestByChapter`
     *    —— 未提交 Draft 不等于已发生事实（R3 §23B）；
     *  - **原子性**：编辑稿保存 + History 追加在**同一 DB 事务**（复用 [WorkflowRepository.inTransaction]），
     *    不会出现"正文已保存但 Canonical 未提交"或反之；
     *  - **作者编辑稿自身就是 Canonical 正文载体**，不另建 Draft 行：载体若另建，它会成为 `latestByChapter`
     *    的返回值 ⇒ Writer 编辑区被换成新身份，且 legacy（`format = null`）章节会被静默迁移为受控 Markdown
     *    （违反 FD-1 与"保存必须复用同一 Draft 身份 / status / format"契约）。历史快照内容由
     *    [CommitHistoryEntry.resultingContent] 自身承载，不依赖载体行不被改写；
     *  - **仅限作者路径**：AI 产出**不得**经本入口进入 Canonical（AI 必须走 Change Artifact → Gate，见 [commit]）。
     *
     * @throws ApplicationError.EntityNotFound Draft 不存在。
     * @throws ApplicationError.InvalidOperation Draft 未落到 Chapter（INVALID_TARGET）。
     */
    fun commitAuthorSave(draftId: DraftId, content: String): CommitHistoryEntry =
        commitSameRowCanonical(draftId, content, summary = "作者手动保存（保存即批准）")

    /**
     * **TXT 导入初始化 → Canonical**（Phase 1 §10.12：`作者 TXT → Import → Chapter → Canonical 初始化`，不走 AI 变更链）。
     *
     * 与 [commitAuthorSave] 复用**同一类** ChapterDraft / CommitHistory “same-row 载体”语义
     * （`resultingDraftId = draftId`、`operation=COMMIT`、`artifactId=null`、`gateId=null`），
     * **不经** WorkingDraft / Change / Human Gate —— 作者已有内容导入即视为**已确认的正典初始化**。
     * 来源语义仅以 [CommitHistoryEntry.summary] 区分（“TXT 导入初始化…”）。
     *
     * 硬边界（与 [commitAuthorSave] 相同）：不新建第二套 Canonical 入口 / 不新增字段 / 不改 P19 / P2 契约 /
     * 不迁移 `format`（导入正文保持 `format = null`，不擅自转受控 Markdown）。
     *
     * @throws ApplicationError.EntityNotFound Draft 不存在。
     * @throws ApplicationError.InvalidOperation Draft 未落到 Chapter（INVALID_TARGET）。
     */
    fun commitImportedCanonical(draftId: DraftId, content: String): CommitHistoryEntry =
        commitSameRowCanonical(draftId, content, summary = "TXT 导入初始化（Canonical 初始化，不经变更链）")

    /**
     * **same-row Canonical 写入**（唯一逻辑实现）：作者手改保存与 TXT 导入初始化两路**共用**，
     * 仅 [summary] 区分来源。行为与字段语义与既有 `commitAuthorSave` 完全一致（不改契约）。
     */
    private fun commitSameRowCanonical(draftId: DraftId, content: String, summary: String): CommitHistoryEntry {
        val draft = guard { drafts.draft(draftId) }
            ?: throw ApplicationException(ApplicationError.EntityNotFound("Draft 不存在: ${draftId.value}"))
        val chapterId = draft.chapterId
            ?: throw invalid(
                CommitErrorCodes.INVALID_TARGET,
                "Draft 未落到 Chapter，不能成为 Canonical: ${draftId.value}",
            )
        val projectId = guard { projects.projectOf(draft.novelId) }.projectId

        var committed: CommitHistoryEntry? = null
        workflows.inTransaction {
            // 基线：该章节**已提交**的 Canonical 事实（`listByProject` = 插入序 ⇒ 末条为最新）
            val baseEntry = history.listByProject(projectId).lastOrNull { it.target.chapterId == chapterId }

            // 1) 载体落库（同事务）：只改 content + updatedAt，身份 / status / format 保持不变
            guard { drafts.saveContent(draftId, content) }

            // 2) Canonical 事实记录（唯一写入点）
            val now = Instant.fromEpochMilliseconds(clock.now().toEpochMilliseconds())
            val entry = CommitHistoryEntry(
                historyId = CommitHistoryId(nextId()),
                commitId = CommitId(nextId()),
                projectId = projectId,
                operation = CommitOperation.COMMIT,
                artifactId = null, // 无 Change Artifact（作者手改 / TXT 导入初始化）
                target = WorkingDraftTarget(draft.novelId, chapterId, draft.variantId),
                // 同一 Draft 重复保存不自链
                previousDraftId = baseEntry?.resultingDraftId?.takeUnless { it == draftId },
                resultingDraftId = draftId,
                previousContent = baseEntry?.resultingContent.orEmpty(),
                resultingContent = content,
                summary = summary,
                revertedHistoryId = null,
                // 格式历史：previous = 承载上一版内容的载体行 format（同 row ⇒ 本行，format 对行不可变）；
                // resulting = 本行实际 format（saveContent 不改 format）
                previousFormat = carrierFormat(baseEntry),
                resultingFormat = CommitFormatHistory.ofDraftFormat(draft.format),
                createdAt = now,
            )
            history.append(entry)
            committed = entry
        }
        // 事务成功即已赋值（失败则事务已回滚并抛出）
        val entry = committed!!
        // Phase 3：作者手改 / TXT 初始化同为成功 Commit → 触发 World Model Update
        runWorldModelUpdate(entry)
        return entry
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

    /**
     * 某 Human Gate 是否已产生**正式提交**（P1-02j B1：持久的 Gate ↔ Commit 关联）。
     *
     * 无关联（历史旧行 / REVERT / 作者手改保存）→ null。恢复路径据此判据收敛，**不**再依赖
     * "正文内容相等"启发式（该启发式在逐字相同的多轮次下会误判）。
     */
    @Synchronized
    fun committedByGate(gateId: WorkflowHumanGateId): CommitHistoryEntry? = guard { history.getByGate(gateId) }

    /**
     * 某 Change Artifact 是否已产生**正式提交**（P0-3B D9：重复 resume / 重复批准据此收敛到既有事实）。
     * 无关联 → null。
     */
    @Synchronized
    fun committedByArtifact(artifactId: ChangeArtifactId): CommitHistoryEntry? = guard { history.getByArtifact(artifactId) }

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

        // 格式契约（Phase 1）：被恢复版本的格式必须来自被回退历史的 previousFormat——不猜测、不升级。
        // UNKNOWN ⇒ 类型化拒绝；此刻尚未进入事务，未创建任何 Draft / History ⇒ 零写入。
        val restoreFormat = when (proposal.history.previousFormat) {
            CommitFormatHistory.KNOWN_LEGACY -> null
            CommitFormatHistory.NONE -> null // 首个 COMMIT：恢复目标语义明确为 legacy（空正文）
            CommitFormatHistory.KNOWN_CONTROLLED_MARKDOWN -> DraftFormat.CONTROLLED_MARKDOWN
            CommitFormatHistory.UNKNOWN -> throw invalid(
                CommitErrorCodes.FORMAT_UNKNOWN,
                "被回退历史 ${historyId.value} 缺少格式信息（previous_format 未知），拒绝猜测性恢复",
            )
        }

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
            // 格式契约（Phase 1）：恢复载体**直接携带**被恢复版本的格式（legacy ⇒ format=null），
            // 不经 stampControlledMarkdown 升级；CM 恢复则显式携带受控 Markdown（与原 stamp 结果一致）。
            val created = drafts.save(
                Draft(
                    draftId = DraftId(nextId()),
                    novelId = proposal.history.target.novelId,
                    variantId = proposal.history.target.variantId,
                    scope = scopeOf(proposal.history.target.variantId),
                    chapterId = proposal.history.target.chapterId,
                    planId = latest.planId,
                    previousDraftId = latest.draftId,
                    content = proposal.restoreContent,
                    format = restoreFormat,
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
                previousFormat = CommitFormatHistory.ofDraftFormat(latest.format),
                resultingFormat = CommitFormatHistory.ofDraftFormat(restoreFormat),
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
        val revertResult = reverted!!
        // Phase 3：Revert 本身是一条新的 Canonical Commit → 同样触发 World Model Update
        runWorldModelUpdate(revertResult.historyEntry)
        return revertResult
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

    /**
     * P0-3B D8 · 批准 Gate 的**最小绑定校验**。
     *
     * 防止：某个已批准 Gate 被用来提交另一个 Artifact / 另一个章节 / 另一个 Workflow 的候选；
     * 或同一 Gate 被复用于第二次提交。
     *
     * D8 冻结语义：
     *  - **Gate 复用检查（getByGate）对一切 Gate 生效**，绝不因缺 `stepId` 被跳过；
     *  - **缺少 WorkflowStep 绑定的 Gate 一律不得用于正式 Commit**：`GATE_TARGET_MISMATCH` 拒绝，
     *    不存在任何运行时豁免；
     *    且拒绝前仍先执行复用检查——生产校验要求不降低。
     */
    private fun requireApprovalBinding(approval: CommitApproval, artifact: ChangeArtifact) {
        val gate = guard { workflows.getGate(approval.gateId) }
            ?: throw invalid(
                CommitErrorCodes.HUMAN_APPROVAL_REQUIRED,
                "批准 Gate 不存在: ${approval.gateId.value}",
            )
        // Gate 复用检查：**先于**任何绑定分支（D8：不因缺 stepId 被跳过）。
        val alreadyCommitted = guard { history.getByGate(approval.gateId) }
        if (alreadyCommitted != null && alreadyCommitted.artifactId != artifact.artifactId) {
            throw gateMismatch(
                approval,
                artifact,
                "该 Gate 已用于提交另一个 Artifact（${alreadyCommitted.artifactId?.value}）",
            )
        }
        // D8：无 Step 绑定 ⇒ 无条件拒绝，不得用于正式 Commit。
        val step = gate.stepId?.let { guard { workflows.getStep(it) } }
            ?: throw gateMismatch(
                approval,
                artifact,
                "Gate 缺少 WorkflowStep 绑定，不得用于正式 Commit（P0-3B D8）",
            )
        val target = artifact.change.target
        if (gate.workflowId == null || step.workflowId != gate.workflowId) {
            throw gateMismatch(approval, artifact, "Gate 与 Step 的 Workflow 绑定不一致")
        }
        val wf = guard { workflows.getWorkflow(step.workflowId) }
            ?: throw invalid(
                CommitErrorCodes.HUMAN_APPROVAL_REQUIRED,
                "批准 Gate 所属 Workflow 不存在: ${step.workflowId.value}",
            )
        if (
            step.phase != WorkflowStepPhase.CONFIRMATION ||
            step.chapterId != target.chapterId ||
            (wf.activeChapterId != null && wf.activeChapterId != target.chapterId) ||
            wf.novelId != target.novelId ||
            wf.variantId != target.variantId
        ) {
            throw gateMismatch(
                approval,
                artifact,
                "Gate/Workflow（novel=${wf.novelId.value}, variant=${wf.variantId?.value}, chapter=${wf.activeChapterId?.value}）" +
                    " 与 Artifact target（novel=${target.novelId.value}, variant=${target.variantId?.value}, chapter=${target.chapterId.value}）不一致",
            )
        }
        val expectedWorkingRef = WritingSnapshot.WORKING_REF_PREFIX + artifact.workingDraftId.value
        val activeWorkingRef = guard { workflows.listSteps(wf.workflowId) }
            .lastOrNull {
                it.chapterId == target.chapterId &&
                    it.phase in WORKING_PHASES &&
                    it.resultReference?.startsWith(WritingSnapshot.WORKING_REF_PREFIX) == true
            }
            ?.resultReference
        if (activeWorkingRef != expectedWorkingRef) {
            throw gateMismatch(
                approval,
                artifact,
                "该 Gate 绑定的是工作产物 $activeWorkingRef，不是 Artifact 的 ${artifact.workingDraftId.value}",
            )
        }
    }

    private fun gateMismatch(approval: CommitApproval, artifact: ChangeArtifact, detail: String): ApplicationException =
        invalid(
            CommitErrorCodes.GATE_TARGET_MISMATCH,
            "Gate ${approval.gateId.value} 与 Artifact ${artifact.artifactId.value} 绑定不一致：$detail",
        )

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
     * [baseEntry] 所述"上一版内容"的格式历史：读其载体行（[CommitHistoryEntry.resultingDraftId]）的实际 format。
     * 依据：`Draft.format` 对行不可变（唯一写入点只作用于新建 Draft）⇒ 行现值即该行全部历史版本的格式。
     * baseEntry 为 null（章节首条历史）⇒ [CommitFormatHistory.NONE]；载体行缺失（异常状态）⇒ UNKNOWN。
     */
    /**
     * Phase 3 · Commit 成功后的 World Model Update 触发点（v3.1 §12）。
     *
     * 唯一挂载路径：四条提交路径（AI Artifact / 作者手改与 TXT 初始化 same-row / 人工门批准 Draft / Revert）
     * 均在**提交事务成功之后**调用；未提交内容（Draft / WorkingDraft / Proposal / 被拒变更）不经过这里
     * ⇒ "草稿阶段 World Model 零变化"由挂载点本身保证。
     * Pipeline 失败策略：Commit 已在先前事务中持久化，**不回滚、不报告为提交失败**；
     * 类型化的 [WorldModelUpdateException] 被捕获后经 `recordFailure` 落为一条可追溯的记录
     * （带 historyId / commitId 与失败原因）⇒ 既不静默，也不把旁路故障伪装成主链故障。
     * 其它异常（真实存储故障）不捕获，原样上抛。
     * 该策略属实现选择，§12 未定义旁路失败归属 ⇒ Architecture Closure U1/U8 待裁决；
     * 逐类失败语义与理由见 [WorldModelUpdateUseCases] 类 KDoc。
     */
    private fun runWorldModelUpdate(entry: CommitHistoryEntry) {
        val pipeline = worldModelUpdate ?: return
        try {
            pipeline.onCommit(entry)
        } catch (e: WorldModelUpdateException) {
            pipeline.recordFailure(entry, e.message ?: "world model update failed")
        }
    }

    private fun carrierFormat(baseEntry: CommitHistoryEntry?): CommitFormatHistory {
        if (baseEntry == null) return CommitFormatHistory.NONE
        val carrier = guard { drafts.draft(baseEntry.resultingDraftId) } ?: return CommitFormatHistory.UNKNOWN
        return CommitFormatHistory.ofDraftFormat(carrier.format)
    }

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

    private companion object {
        /** 产生 / 更新 AI 工作产物的 Step phase（与 WorkflowOrchestrator 的取值一致）。 */
        val WORKING_PHASES = setOf(WorkflowStepPhase.WRITING, WorkflowStepPhase.REVISION)
    }
}