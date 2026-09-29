package com.qianyan.application.usecase.draft

import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.application.error.ErrorMapper
import com.qianyan.application.usecase.UseCase
import com.qianyan.application.usecase.chapter.ChapterUseCases
import com.qianyan.application.usecase.project.ProjectUseCases
import com.qianyan.application.usecase.writing.WriterUseCases
import com.qianyan.model.ActivityId
import com.qianyan.model.AgentSessionId
import com.qianyan.model.DraftId
import com.qianyan.model.ProjectId
import com.qianyan.model.spec.ValidationResult
import com.qianyan.model.workingdraft.WorkingDraft
import com.qianyan.model.workingdraft.WorkingDraftBase
import com.qianyan.model.workingdraft.WorkingDraftId
import com.qianyan.model.workingdraft.WorkingDraftStatus
import com.qianyan.model.workingdraft.WorkingDraftTarget
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/**
 * I8 · Working Draft 工作区（Application 层）。
 *
 * 能力：`create` / `get` / `list` / `replace` / `edit` / `discard` / `validate`。
 * 语义（architecture §17）：Agent / Skill 的**临时成果区** —— 未确认产出留在这里，**不污染 Canonical Story**。
 *
 * **存储决策（§8 / §22）：内存工作区，不落库、不加表、不加迁移。**
 *  1. Working Draft 是"一次任务中的临时产物"（§4），本阶段不要求 App 重启后恢复；
 *  2. canonical 路径已有持久化载体（`ChapterDraft`）；若把临时产物写进该表，会改变既有
 *     `latestByChapter` 的结果，污染既有 Writer / Reader 流程（见 `WorkingDraftModels` 的说明）；
 *  3. 由 `ApplicationContainer` 持有**单一实例**（应用级生命周期），不跨进程共享。
 *  若未来 I9/I10 要求跨会话恢复，再按 FD-9 additive 另立存储边界 —— 本阶段不提前建库。
 *
 * 硬边界：
 *  - **只读 Canonical**：本类只经既有 Application 能力**读取** Project / Chapter / Draft 用于校验；
 *    绝不写 Chapter / ChapterDraft / StoryFoundation / ProjectState / World Model；
 *  - **不推进 Agent 生命周期**：不改 AgentSession / Activity / Workflow / Task 状态（归属关系只是引用）；
 *  - **不调用 LLM**、**不执行 Tool**、**不构建 Context**、**不做权限判定**、**不 Commit**（I9/I10 才处理 Diff/Change/Commit）。
 */
class WorkingDraftUseCases(
    private val projects: ProjectUseCases,
    private val chapters: ChapterUseCases,
    private val drafts: WriterUseCases,
    private val validator: WorkingDraftValidator,
    private val clock: Clock = Clock.System,
    errorMapper: ErrorMapper,
) : UseCase(errorMapper) {

    /** 应用级内存工作区（进程内；不落库）。 */
    private val store: MutableMap<WorkingDraftId, WorkingDraft> = LinkedHashMap()

    /**
     * 创建一份 Working Draft（状态 `WORKING`）。
     *
     * 硬校验（身份 / 目标 / 基线；越界一律按"不存在"拒绝，不泄漏其他 Project 的存在性）：
     *  - `Project` 必须存在且 `projectId` 解析出的 Novel 等于 `target.novelId`；
     *  - `target.chapterId` 必须存在、属于 `target.novelId`、且作用域与 `target.variantId` 一致；
     *  - 若给出 `baseDraftId`，基线 Draft 必须存在且属于同一 target 章节。
     *
     * 归属（`sessionId` / `activityId`）只作为引用记录：其合法性由 [validate] 判断（`SESSION_SCOPE_MISMATCH` /
     * `ACTIVITY_SCOPE_MISMATCH`），本方法**不**写入任何 Session / Activity 状态。
     *
     * @throws ApplicationError.EntityNotFound Project / Chapter / 基线 Draft 不存在或越界。
     */
    @Synchronized
    fun create(
        projectId: ProjectId,
        target: WorkingDraftTarget,
        content: String,
        sessionId: AgentSessionId? = null,
        activityId: ActivityId? = null,
        baseDraftId: DraftId? = null,
    ): WorkingDraft {
        val project = guard { projects.projectOf(projectId) }
        if (project == null || project.novelId != target.novelId) {
            throw ApplicationException(ApplicationError.EntityNotFound("Project 不存在: ${projectId.value}"))
        }
        val chapter = guard { chapters.findById(target.chapterId) }
        if (chapter == null || chapter.novelId != target.novelId || chapter.variantId != target.variantId) {
            throw ApplicationException(ApplicationError.EntityNotFound("Chapter 不存在: ${target.chapterId.value}"))
        }
        val baseDraft = baseDraftId?.let { id ->
            guard { drafts.draft(id) }
                ?: throw ApplicationException(ApplicationError.EntityNotFound("Draft 不存在: ${id.value}"))
        }
        if (baseDraft != null && (baseDraft.novelId != target.novelId || baseDraft.chapterId != target.chapterId)) {
            throw ApplicationException(ApplicationError.EntityNotFound("Draft 不存在: ${baseDraft.draftId.value}"))
        }

        val now = now()
        val draft = WorkingDraft(
            workingDraftId = WorkingDraftId(nextId()),
            projectId = projectId,
            target = target,
            base = WorkingDraftBase(
                baseDraftId = baseDraft?.draftId,
                baseDraftStatus = baseDraft?.status,
                baseDraftUpdatedAt = baseDraft?.updatedAt,
                baseChapterStatus = chapter.status,
            ),
            content = content,
            status = WorkingDraftStatus.WORKING,
            sessionId = sessionId,
            activityId = activityId,
            createdAt = now,
            updatedAt = now,
        )
        store[draft.workingDraftId] = draft
        return draft
    }

    /** 读取一份 Working Draft；不存在 → [ApplicationError.EntityNotFound]（不伪造）。 */
    @Synchronized
    fun get(workingDraftId: WorkingDraftId): WorkingDraft = store[workingDraftId]
        ?: throw ApplicationException(ApplicationError.EntityNotFound("Working Draft 不存在: ${workingDraftId.value}"))

    /** Project 内的 Working Draft 列表（按 `workingDraftId` 升序；确定性，不依赖插入顺序 / Map 迭代序）。 */
    @Synchronized
    fun list(projectId: ProjectId): List<WorkingDraft> = store.values
        .filter { it.projectId == projectId }
        .sortedBy { it.workingDraftId.value }

    /** 整体替换正文（状态回到 `WORKING`：内容变化即需重新校验）。 */
    @Synchronized
    fun replace(workingDraftId: WorkingDraftId, content: String): WorkingDraft {
        val current = requireMutable(workingDraftId)
        val updated = current.copy(content = content, status = WorkingDraftStatus.WORKING, updatedAt = now())
        store[workingDraftId] = updated
        return updated
    }

    /** 局部编辑正文（对当前正文做确定性变换；状态回到 `WORKING`）。 */
    @Synchronized
    fun edit(workingDraftId: WorkingDraftId, mutate: (String) -> String): WorkingDraft {
        val current = requireMutable(workingDraftId)
        val updated = current.copy(
            content = mutate(current.content),
            status = WorkingDraftStatus.WORKING,
            updatedAt = now(),
        )
        store[workingDraftId] = updated
        return updated
    }

    /** 丢弃（`DISCARDED` 为终态；记录保留以便审计，幂等）。 */
    @Synchronized
    fun discard(workingDraftId: WorkingDraftId): WorkingDraft {
        val current = get(workingDraftId)
        if (current.status.isTerminal) return current
        val updated = current.copy(status = WorkingDraftStatus.DISCARDED, updatedAt = now())
        store[workingDraftId] = updated
        return updated
    }

    /**
     * 对 Working Draft 执行确定性校验，并把结果记入其状态（`VALIDATED` / `INVALID`；幂等）。
     * 已丢弃（终态）的 Working Draft 只返回结果，不改状态。
     */
    @Synchronized
    fun validate(workingDraftId: WorkingDraftId): ValidationResult {
        val current = get(workingDraftId)
        val result = validator.validate(current)
        val target = if (result.passed) WorkingDraftStatus.VALIDATED else WorkingDraftStatus.INVALID
        if (!current.status.isTerminal && current.status != target) {
            store[workingDraftId] = current.copy(status = target, updatedAt = now())
        }
        return result
    }

    private fun requireMutable(workingDraftId: WorkingDraftId): WorkingDraft {
        val current = get(workingDraftId)
        if (current.status.isTerminal) {
            throw ApplicationException(
                ApplicationError.InvalidOperation("Working Draft 已丢弃（${current.status.name}），不可修改: ${workingDraftId.value}"),
            )
        }
        return current
    }

    /** 时间戳精度与存储一致（epoch 毫秒）；时钟可注入，保证测试确定性（§14 / §24）。 */
    private fun now(): Instant = Instant.fromEpochMilliseconds(clock.now().toEpochMilliseconds())
}