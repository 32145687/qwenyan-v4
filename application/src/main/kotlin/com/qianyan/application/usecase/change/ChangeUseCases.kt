package com.qianyan.application.usecase.change

import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.application.error.ErrorMapper
import com.qianyan.application.usecase.UseCase
import com.qianyan.application.usecase.draft.WorkingDraftUseCases
import com.qianyan.application.usecase.draft.WorkingDraftValidator
import com.qianyan.application.usecase.writing.WriterUseCases
import com.qianyan.model.ProjectId
import com.qianyan.model.change.Change
import com.qianyan.model.change.ChangeArtifact
import com.qianyan.model.change.ChangeArtifactId
import com.qianyan.model.change.ChangeArtifacts
import com.qianyan.model.change.ChangeFingerprint
import com.qianyan.model.change.TextDiffer
import com.qianyan.model.workingdraft.WorkingDraft
import com.qianyan.model.workingdraft.WorkingDraftId
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/**
 * I9 · Change Review（Diff → Change → Artifact 的变更审查层）。
 *
 * 能力：`prepare`（从当前 Working Draft 生成 Diff / Change / Artifact）、`get`、`list`、`isSuperseded`。
 *
 * 数据流（architecture §19 / §26）：
 * ```
 * Working Draft（I8 临时成果）
 *      → Diff（相对 base reference 的确定性文本变化）
 *      → Change（可审查的结构化变更）
 *      → Change Artifact（提案载体；供未来 UI / Human Gate 使用）
 * ```
 *
 * **存储决策（§8）：内存 Artifact Store，不落库、不加表、不加迁移。**
 * Artifact 是"当前任务中的审查载体"，不要求重启恢复；若未来 I10 需要持久化 History，再按 FD-9 additive 另立边界。
 *
 * 硬边界：
 *  - **只读 Canonical**：只经既有 Application 能力读取 Working Draft / 基线 Draft；绝不写
 *    Chapter / ChapterDraft / ProjectState / StoryFoundation / AgentSession / Activity / Working Draft 状态；
 *  - **不执行 Commit**：不写 Canonical、不建 History、不建 Revert / Undo、不做版本快照持久化（全部属 I10）；
 *  - **不重造 Validation**：直接复用 I8 的确定性 [WorkingDraftValidator] 与既有 `ValidationResult`；
 *    校验在 `prepare` 时**对当前 Working Draft 重新执行**（同输入 ⇒ 同结果），因此 Artifact 携带的校验**永不是过期快照**；
 *  - **不重造 Human Gate / ActionPolicy**：只派生"能否进入后续人工确认流程"这一事实（`READY`），不做任何决议；
 *  - **不调用 LLM**、**不执行 Tool**、**不构建 Context**、**不是 Novel Agent**。
 */
class ChangeUseCases(
    private val workingDrafts: WorkingDraftUseCases,
    private val validator: WorkingDraftValidator,
    private val drafts: WriterUseCases,
    private val clock: Clock = Clock.System,
    errorMapper: ErrorMapper,
) : UseCase(errorMapper) {

    /** 应用级内存 Artifact Store（进程内；不落库）。 */
    private val store: MutableMap<ChangeArtifactId, ChangeArtifact> = LinkedHashMap()

    /**
     * 从当前 Working Draft 生成一份变更审查 Artifact（确定性；不修改 Working Draft 与任何 Canonical 数据）。
     *
     * @throws ApplicationError.EntityNotFound Working Draft 不存在。
     */
    @Synchronized
    fun prepare(workingDraftId: WorkingDraftId): ChangeArtifact {
        val draft = workingDrafts.get(workingDraftId)
        val change = computeChange(draft)
        val artifact = ChangeArtifact(
            artifactId = ChangeArtifactId(nextId()),
            change = change,
            status = ChangeArtifacts.statusOf(change),
            fingerprint = ChangeFingerprint.of(change),
            createdAt = now(),
        )
        store[artifact.artifactId] = artifact
        return artifact
    }

    /** 读取 Artifact；不存在 → [ApplicationError.EntityNotFound]（不伪造）。 */
    @Synchronized
    fun get(artifactId: ChangeArtifactId): ChangeArtifact = store[artifactId]
        ?: throw ApplicationException(ApplicationError.EntityNotFound("Change Artifact 不存在: ${artifactId.value}"))

    /** Project 内的 Artifact 列表（按 `artifactId` 升序；确定性，不依赖插入顺序 / Map 迭代序）。 */
    @Synchronized
    fun list(projectId: ProjectId): List<ChangeArtifact> = store.values
        .filter { it.projectId == projectId }
        .sortedBy { it.artifactId.value }

    /**
     * 该 Artifact 是否已被更新取代：按**当前** Working Draft 重算指纹并比对。
     *
     * Artifact 本身是**冻结**的（不因底层变化而悄悄改变）；本查询只回答"它是否仍代表最新结果"（§9 / §12）。
     * Working Draft 已不存在时视为被取代（true）。
     */
    @Synchronized
    fun isSuperseded(artifactId: ChangeArtifactId): Boolean {
        val artifact = get(artifactId)
        val draft = readOrNull { workingDrafts.get(artifact.workingDraftId) } ?: return true
        return ChangeFingerprint.of(computeChange(draft)) != artifact.fingerprint
    }

    // ---- internals ----

    /** 由 Working Draft 确定性构造 Change（唯一入口；`prepare` 与 `isSuperseded` 共用）。 */
    private fun computeChange(draft: WorkingDraft): Change {
        // 基线正文（只读既有 Draft；无基线 ⇒ 空 ⇒ Diff 侧为"新增"）
        val baseContent = draft.base.baseDraftId
            ?.let { baseDraftId -> readOrNull { drafts.draft(baseDraftId) }?.content }
            .orEmpty()
        return Change(
            projectId = draft.projectId,
            workingDraftId = draft.workingDraftId,
            target = draft.target,
            base = draft.base,
            diff = TextDiffer.diff(baseContent, draft.content),
            validation = validator.validate(draft),
            workingDraftStatus = draft.status,
        )
    }

    /** 只读查询；缺失/异常一律表达为 null（查询必须返回结果，不因缺失而抛异常）。 */
    private fun <T> readOrNull(block: () -> T?): T? = try {
        guard(block)
    } catch (e: ApplicationException) {
        null
    }

    /** 时间戳精度与存储一致（epoch 毫秒）；时钟可注入，保证测试确定性。 */
    private fun now(): Instant = Instant.fromEpochMilliseconds(clock.now().toEpochMilliseconds())
}