package com.qianyan.application.usecase.workspace

import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.application.error.ErrorMapper
import com.qianyan.application.usecase.UseCase
import com.qianyan.application.usecase.change.ChangeUseCases
import com.qianyan.application.usecase.chapter.ChapterUseCases
import com.qianyan.application.usecase.commit.CommitUseCases
import com.qianyan.application.usecase.context.ContextEngineUseCases
import com.qianyan.application.usecase.index.ProjectIndexUseCases
import com.qianyan.application.usecase.log.ActivityUseCases
import com.qianyan.application.usecase.log.ToolCallLogUseCases
import com.qianyan.application.usecase.novel.NovelUseCases
import com.qianyan.application.usecase.project.ProjectUseCases
import com.qianyan.application.usecase.session.AgentSessionUseCases
import com.qianyan.application.usecase.task.TaskManagerUseCases
import com.qianyan.application.usecase.taskqueue.TaskQueueUseCases
import com.qianyan.application.usecase.writing.WriterUseCases
import com.qianyan.model.ChapterId
import com.qianyan.model.IntentType
import com.qianyan.model.NovelId
import com.qianyan.model.ProjectId
import com.qianyan.model.context.ContextBudget
import com.qianyan.model.context.ContextPack
import com.qianyan.model.context.ContextRequest
import com.qianyan.model.project.Project
import com.qianyan.model.projectindex.ProjectIndexEntryType
import com.qianyan.model.taskqueue.TaskQueueItem
import com.qianyan.model.workspace.InspectorActivityView
import com.qianyan.model.workspace.InspectorChangeView
import com.qianyan.model.workspace.InspectorContextView
import com.qianyan.model.workspace.InspectorHistoryView
import com.qianyan.model.workspace.WorkspaceErrorCodes
import com.qianyan.model.workspace.WorkspaceInspectorView
import com.qianyan.model.workspace.WorkspaceObjectCandidate
import com.qianyan.model.workspace.WorkspaceObjectKind
import com.qianyan.model.workspace.WorkspaceSelection
import com.qianyan.storage.repository.StoryStateRepository
import kotlinx.datetime.Clock

/**
 * I14 · Workspace Use Cases（三栏 Workspace 的 **Application 层薄 Facade**，§20）。
 *
 * 职责（**只编排，不重实现**）：
 * ```
 * WorkspaceSelection（当前对象，UI 会话态）
 *     ↓ select / candidates
 * 既有应用能力：I1 Project · I6 ContextEngine · I12 ProjectIndex · I4 Activity ·
 *              I9 Change · I10 Commit · I13 TaskQueue · I3 AgentSession · I8 Draft
 *     ↓ WorkspaceProjections（纯映射）
 * WorkspaceInspectorView（只读投影）
 * ```
 *
 * 硬边界：
 *  - **只读投影**：不写 Canonical（Chapter / ChapterDraft / StoryFoundation / ProjectState /
 *    CommitHistory），不建 Working Draft / Change / Commit；Inspector 永不写库（§17）；
 *  - **不复用第二套引擎**：Context 一律来自 I6 `ContextEngineUseCases.build`（本类不选择 / 不裁剪 / 不排序条目，§3 / §10）；
 *    Activity 来自 I4，Change / History 来自 I9 / I10，Task 状态来自 I13；
 *  - **不是第二套状态机**：本类不定义任何新状态机（§21）；`WorkspaceSelection` 是 UI 会话态，
 *    **不落库**，与 I1 `ProjectState` 严格分离（§5）；
 *  - **不伪造**：没有稳定来源的关联（如"某人物出现的章节"）如实写入 notices（§16）；
 *  - **Project 隔离**：越界一律表现为"不存在"，不泄漏其他 Project 的存在性（§23）。
 */
class WorkspaceUseCases(
    private val projects: ProjectUseCases,
    private val novels: NovelUseCases,
    private val chapters: ChapterUseCases,
    private val writer: WriterUseCases,
    private val index: ProjectIndexUseCases,
    private val contextEngine: ContextEngineUseCases,
    private val activities: ActivityUseCases,
    private val toolCallLogs: ToolCallLogUseCases,
    private val changes: ChangeUseCases,
    private val commits: CommitUseCases,
    private val tasks: TaskManagerUseCases,
    private val taskQueue: TaskQueueUseCases,
    private val sessions: AgentSessionUseCases,
    private val storyState: StoryStateRepository,
    errorMapper: ErrorMapper,
) : UseCase(errorMapper) {

    /* ---------------- 当前对象：候选 ---------------- */

    /**
     * 列出当前 Project 中可被选为"当前对象"的候选（供 Workspace 选择器）。
     *
     * 章节 / 正文 / 故事基础 / 词汇的位置与标签来自 **既有 I12 Project Index**（§4：用于当前对象定位）；
     * 人物 / 世界规则 / 伏笔 / 时间线来自既有 Story State；任务 / 修改 / 历史来自 I13 / I9 / I10。
     */
    fun candidates(projectId: ProjectId): List<WorkspaceObjectCandidate> {
        val project = readProject(projectId)
        val novel = readNovel(project.novelId, projectId)
        val variantId = project.state.activeVariantId
        val out = mutableListOf<WorkspaceObjectCandidate>()

        out += WorkspaceObjectCandidate(
            selection = WorkspaceSelection.project(projectId, novel.novelId, variantId),
            label = novel.title,
            detail = "项目",
        )

        // 结构类对象：经 I12 Project Index 定位（只读派生，不改 I12 语义）
        val entries = guard { index.rebuild(projectId) }.entries
        entries.forEach { entry ->
            when (entry.entryType) {
                ProjectIndexEntryType.CHAPTER -> out += WorkspaceObjectCandidate(
                    selection = WorkspaceSelection.chapter(
                        projectId = projectId,
                        novelId = entry.reference.novelId,
                        chapterId = entry.reference.chapterId ?: return@forEach,
                        variantId = entry.reference.variantId,
                    ),
                    label = "第 ${entry.ordinal} 章 · ${entry.label}",
                    detail = "章节",
                )

                ProjectIndexEntryType.DRAFT -> out += WorkspaceObjectCandidate(
                    selection = WorkspaceSelection.draft(
                        projectId = projectId,
                        novelId = entry.reference.novelId,
                        chapterId = entry.reference.chapterId ?: return@forEach,
                        draftId = entry.reference.draftId ?: return@forEach,
                        variantId = entry.reference.variantId,
                    ),
                    label = entry.label,
                    detail = "正文",
                )

                else -> Unit // NOVEL / STORY_FOUNDATION / VOCABULARY 由下方各自来源提供
            }
        }

        // 结构类：故事基础 / 词汇（索引条目的存在即"有稳定来源"）
        entries.firstOrNull { it.entryType == ProjectIndexEntryType.STORY_FOUNDATION }?.let {
            out += WorkspaceObjectCandidate(
                selection = WorkspaceSelection(
                    projectId = projectId, kind = WorkspaceObjectKind.STORY_FOUNDATION, novelId = novel.novelId,
                ),
                label = it.label,
                detail = "故事基础",
            )
        }
        entries.filter { it.entryType == ProjectIndexEntryType.VOCABULARY }.forEach { entry ->
            val candidateId = entry.reference.vocabularyCandidateId ?: return@forEach
            out += WorkspaceObjectCandidate(
                selection = WorkspaceSelection(
                    projectId = projectId, kind = WorkspaceObjectKind.VOCABULARY,
                    novelId = novel.novelId, variantId = entry.reference.variantId,
                    vocabularyCandidateId = candidateId,
                ),
                label = entry.label,
                detail = "词汇",
            )
        }

        // Story State：人物 / 世界规则 / 伏笔 / 时间线（既有 Canonical 事实；只读）
        guard { storyState.listCharacters(novel.novelId, variantId) }.forEach {
            out += WorkspaceObjectCandidate(
                selection = WorkspaceSelection.character(projectId, novel.novelId, it.characterId, variantId),
                label = it.name, detail = "人物",
            )
        }
        guard { storyState.listWorldRules(novel.novelId, variantId) }.forEach {
            out += WorkspaceObjectCandidate(
                selection = WorkspaceSelection(
                    projectId = projectId, kind = WorkspaceObjectKind.WORLD_RULE,
                    novelId = novel.novelId, variantId = it.variantId, worldRuleId = it.ruleId,
                ),
                label = it.content.take(24), detail = "世界规则",
            )
        }
        guard { storyState.listForeshadows(novel.novelId, variantId) }.forEach {
            out += WorkspaceObjectCandidate(
                selection = WorkspaceSelection(
                    projectId = projectId, kind = WorkspaceObjectKind.FORESHADOW,
                    novelId = novel.novelId, variantId = it.variantId, foreshadowId = it.foreshadowId,
                ),
                label = it.content.take(24), detail = "伏笔",
            )
        }
        guard { storyState.listTimelineEntries(novel.novelId, variantId) }.forEach {
            out += WorkspaceObjectCandidate(
                selection = WorkspaceSelection(
                    projectId = projectId, kind = WorkspaceObjectKind.TIMELINE_ENTRY,
                    novelId = novel.novelId, variantId = it.variantId, timelineEntryId = it.id,
                ),
                label = it.description.ifBlank { it.id.value }, detail = "时间线",
            )
        }

        // 任务（I13）：队列条目即"有真实后台任务"
        guard { taskQueue.listByProject(projectId) }.forEach { item ->
            val task = runCatching { tasks.findById(item.taskId) }.getOrNull() ?: return@forEach
            out += WorkspaceObjectCandidate(
                selection = WorkspaceSelection.task(projectId, item.taskId, novel.novelId),
                label = "${task.type.name} · ${task.status.name}", detail = "任务",
            )
        }

        // 待确认修改（I9）
        changes.list(projectId).forEach { artifact ->
            out += WorkspaceObjectCandidate(
                selection = WorkspaceSelection.change(projectId, artifact.artifactId, novel.novelId),
                label = artifact.change.summary, detail = "修改",
            )
        }

        // 已提交历史（I10）
        guard { commits.history(projectId) }.forEach { entry ->
            out += WorkspaceObjectCandidate(
                selection = WorkspaceSelection.history(projectId, entry.historyId, novel.novelId),
                label = entry.summary, detail = "历史",
            )
        }
        return out
    }

    /* ---------------- 当前对象：校验 / 规范化 ---------------- */

    /**
     * 校验并规范化一个选择：确认 Project 归属与该对象真实存在（越界统一表现为"不存在"）。
     *
     * **只读**：不写 ProjectState、不写 Canonical（§5 / §23）。
     */
    fun select(projectId: ProjectId, selection: WorkspaceSelection): WorkspaceSelection {
        if (selection.projectId != projectId) throw projectNotFound(projectId)
        val project = readProject(projectId)
        val novelId = project.novelId
        return when (selection.kind) {
            WorkspaceObjectKind.PROJECT ->
                selection.copy(novelId = novelId, variantId = selection.variantId ?: project.state.activeVariantId)

            WorkspaceObjectKind.NOVEL -> {
                readNovel(selection.novelId ?: novelId, projectId)
                selection.copy(novelId = novelId)
            }

            WorkspaceObjectKind.CHAPTER -> {
                val chapter = requireChapter(selection.chapterId, novelId)
                selection.copy(novelId = novelId, chapterId = chapter.chapterId, variantId = chapter.variantId)
            }

            WorkspaceObjectKind.DRAFT -> {
                val draftId = selection.draftId ?: throw incomplete(selection, "draftId")
                val draft = guard { writer.draft(draftId) } ?: throw objectNotFound("Draft", draftId.value)
                if (draft.novelId != novelId) throw objectNotFound("Draft", draftId.value)
                selection.copy(novelId = novelId, chapterId = draft.chapterId, variantId = draft.variantId)
            }

            WorkspaceObjectKind.CHARACTER -> {
                val characterId = selection.characterId ?: throw incomplete(selection, "characterId")
                val character = guard { storyState.getCharacterById(characterId) }
                    ?: throw objectNotFound("Character", characterId.value)
                if (character.novelId != novelId) throw objectNotFound("Character", characterId.value)
                selection.copy(novelId = novelId, variantId = character.variantId)
            }

            WorkspaceObjectKind.WORLD_RULE -> {
                val ruleId = selection.worldRuleId ?: throw incomplete(selection, "worldRuleId")
                val rule = guard { storyState.getWorldRuleById(ruleId) } ?: throw objectNotFound("WorldRule", ruleId.value)
                if (rule.novelId != novelId) throw objectNotFound("WorldRule", ruleId.value)
                selection.copy(novelId = novelId, variantId = rule.variantId)
            }

            WorkspaceObjectKind.FORESHADOW -> {
                val id = selection.foreshadowId ?: throw incomplete(selection, "foreshadowId")
                val foreshadow = guard { storyState.getForeshadowById(id) } ?: throw objectNotFound("Foreshadow", id.value)
                if (foreshadow.novelId != novelId) throw objectNotFound("Foreshadow", id.value)
                selection.copy(novelId = novelId, variantId = foreshadow.variantId)
            }

            WorkspaceObjectKind.TIMELINE_ENTRY -> {
                val id = selection.timelineEntryId ?: throw incomplete(selection, "timelineEntryId")
                val entry = guard { storyState.getTimelineEntryById(id) } ?: throw objectNotFound("TimelineEntry", id.value)
                if (entry.novelId != novelId) throw objectNotFound("TimelineEntry", id.value)
                selection.copy(novelId = novelId, variantId = entry.variantId)
            }

            WorkspaceObjectKind.STORY_FOUNDATION -> {
                requireIndexEntry(projectId, ProjectIndexEntryType.STORY_FOUNDATION, null)
                selection.copy(novelId = novelId)
            }

            WorkspaceObjectKind.VOCABULARY -> {
                val candidateId = selection.vocabularyCandidateId ?: throw incomplete(selection, "vocabularyCandidateId")
                requireIndexEntry(projectId, ProjectIndexEntryType.VOCABULARY, candidateId.value)
                selection.copy(novelId = novelId)
            }

            WorkspaceObjectKind.TASK -> {
                val taskId = selection.taskId ?: throw incomplete(selection, "taskId")
                requireTask(taskId)
                selection.copy(novelId = novelId)
            }

            WorkspaceObjectKind.CHANGE -> {
                val artifactId = selection.changeArtifactId ?: throw incomplete(selection, "changeArtifactId")
                requireChange(artifactId, projectId)
                selection.copy(novelId = novelId)
            }

            WorkspaceObjectKind.HISTORY -> {
                val historyId = selection.historyId ?: throw incomplete(selection, "historyId")
                requireHistory(historyId, projectId)
                selection.copy(novelId = novelId)
            }
        }
    }

    /* ---------------- Inspector：三个 tab（§8 固定结构） ---------------- */

    /** 一次读取 Inspector 的全部投影（三个 tab 的数据；UI 只负责切 tab）。 */
    fun inspector(projectId: ProjectId, selection: WorkspaceSelection): WorkspaceInspectorView {
        val normalized = select(projectId, selection)
        val (title, detail) = describe(projectId, normalized)
        val contextView = context(projectId, normalized)
        val taskView = normalized.taskId?.let { taskId ->
            val task = runCatching { tasks.findById(taskId) }.getOrNull()
            task?.let { WorkspaceProjections.taskView(it, guard { taskQueue.findByTask(taskId) }) }
        }
        return WorkspaceInspectorView(
            selection = normalized,
            title = title,
            detail = detail,
            context = contextView,
            task = taskView,
            activities = activities(projectId, normalized),
            changes = changes(projectId, normalized),
            history = history(projectId, normalized),
            notices = contextView.notices,
        )
    }

    /**
     * Context Tab：**调用既有 I6 `ContextEngineUseCases.build`**，把 `ContextPack` 投影为 Inspector 视图。
     *
     * 本类不选择 / 不排序 / 不裁剪 Context 条目（复用 I6 `ContextSelector` + `ContextBudget`，§10）。
     */
    fun context(projectId: ProjectId, selection: WorkspaceSelection): InspectorContextView {
        val normalized = select(projectId, selection)
        val project = readProject(projectId)
        val novel = readNovel(project.novelId, projectId)

        val request = ContextRequest(
            projectId = projectId,
            purpose = purposeOf(normalized.kind),
            sessionId = sessionIdOf(projectId, normalized),
            taskId = normalized.taskId,
            focusChapterId = normalized.chapterId,
            budget = ContextBudget(),
        )
        val pack: ContextPack = guard { contextEngine.build(request) }
        return WorkspaceProjections.contextView(
            pack = pack,
            selection = normalized,
            focusLabel = focusLabel(projectId, normalized) ?: novel.title,
            novelTitle = novel.title,
            notices = noticesFor(normalized, pack),
        )
    }

    /** Activity Tab：复用 I4 `ActivityUseCases` / `ToolCallLogUseCases`（不重建 Activity 模型，§11）。 */
    fun activities(projectId: ProjectId, selection: WorkspaceSelection): List<InspectorActivityView> {
        val normalized = select(projectId, selection)
        // TASK 选择：经 I3 会话发现真实关联（session.taskId 是既有事实链），只取该会话的活动。
        val sessionId = sessionIdOf(projectId, normalized)
        val logs = if (normalized.kind == WorkspaceObjectKind.TASK && sessionId != null) {
            activities.listBySession(sessionId)
        } else {
            activities.listByProject(projectId)
        }
        return logs
            .sortedWith(compareBy({ it.startedAt }, { it.activityId.value }))
            .map { activity ->
                WorkspaceProjections.activityView(
                    activity = activity,
                    toolCalls = guard { toolCallLogs.listByActivity(activity.activityId) },
                    title = activity.summary?.takeIf { it.isNotBlank() } ?: activity.kind,
                )
            }
    }

    /** Changes Tab（修改）：复用 I9 `ChangeUseCases`（不重做 Diff / Change / Commit，§12）。 */
    fun changes(projectId: ProjectId, selection: WorkspaceSelection): List<InspectorChangeView> {
        val normalized = select(projectId, selection)
        val artifacts = when (normalized.kind) {
            WorkspaceObjectKind.CHANGE -> listOf(changes.get(normalized.changeArtifactId!!))
            // 章节 / 正文：按既有 target.chapterId 关联（真实链路，不做字符串猜测）
            WorkspaceObjectKind.CHAPTER, WorkspaceObjectKind.DRAFT ->
                changes.list(projectId).filter { it.change.target.chapterId == normalized.chapterId }

            else -> changes.list(projectId)
        }
        return artifacts
            .sortedWith(compareBy({ it.createdAt }, { it.artifactId.value }))
            .map(WorkspaceProjections::changeView)
    }

    /** Changes Tab（历史）：复用 I10 `CommitUseCases.history`（只读；Revert 仍走 I10）。 */
    fun history(projectId: ProjectId, selection: WorkspaceSelection): List<InspectorHistoryView> {
        val normalized = select(projectId, selection)
        val entries = guard { commits.history(projectId) }
            .filter { normalized.chapterId == null || it.target.chapterId == normalized.chapterId }
        return entries
            .sortedWith(compareBy({ it.createdAt }, { it.historyId.value }))
            .map(WorkspaceProjections::historyView)
    }

    /* ---------------- internals：读取 / 校验 ---------------- */

    private fun readProject(projectId: ProjectId): Project =
        guard { projects.projectOf(projectId) } ?: throw projectNotFound(projectId)

    private fun readNovel(novelId: NovelId, projectId: ProjectId): com.qianyan.model.core.Novel {
        val novel = guard { novels.getNovel(novelId) } ?: throw projectNotFound(projectId)
        if (novel.projectId != projectId) throw projectNotFound(projectId)
        return novel
    }

    private fun requireChapter(chapterId: ChapterId?, novelId: NovelId): com.qianyan.model.story.Chapter {
        val id = chapterId ?: throw incomplete(null, "chapterId")
        val chapter = guard { chapters.findById(id) } ?: throw objectNotFound("Chapter", id.value)
        if (chapter.novelId != novelId) throw objectNotFound("Chapter", id.value)
        return chapter
    }

    /** 结构类对象（故事基础 / 词汇）以 I12 索引条目存在为准（索引本身派生自 Canonical）。 */
    private fun requireIndexEntry(projectId: ProjectId, type: ProjectIndexEntryType, primaryKey: String?) {
        val found = guard { index.rebuild(projectId) }.entries.any { entry ->
            entry.entryType == type && (primaryKey == null || entry.entryId.value.endsWith(":$primaryKey"))
        }
        if (!found) throw objectNotFound(type.name, primaryKey ?: projectId.value)
    }

    private fun requireTask(taskId: com.qianyan.model.TaskId) {
        runCatching { tasks.findById(taskId) }
            .onFailure { throw objectNotFound("Task", taskId.value) }
    }

    private fun requireChange(artifactId: com.qianyan.model.change.ChangeArtifactId, projectId: ProjectId) {
        val artifact = runCatching { changes.get(artifactId) }.getOrNull()
            ?: throw objectNotFound("ChangeArtifact", artifactId.value)
        if (artifact.projectId != projectId) throw objectNotFound("ChangeArtifact", artifactId.value)
    }

    private fun requireHistory(historyId: com.qianyan.model.commit.CommitHistoryId, projectId: ProjectId) {
        runCatching { commits.historyEntry(projectId, historyId) }
            .onFailure { throw objectNotFound("CommitHistory", historyId.value) }
    }

    /** 选择缺陷（缺少必需引用字段）。 */
    private fun incomplete(selection: WorkspaceSelection?, field: String): ApplicationException = ApplicationException(
        ApplicationError.InvalidOperation(
            "[${WorkspaceErrorCodes.SELECTION_INCOMPLETE}] 选择缺少必需字段 $field" +
                (selection?.let { "（kind=${it.kind}）" } ?: ""),
        ),
    )

    private fun objectNotFound(label: String, id: String): ApplicationException = ApplicationException(
        ApplicationError.EntityNotFound("[${WorkspaceErrorCodes.OBJECT_NOT_FOUND}] $label 不存在: $id"),
    )

    private fun projectNotFound(projectId: ProjectId): ApplicationException = ApplicationException(
        ApplicationError.EntityNotFound("[${WorkspaceErrorCodes.PROJECT_NOT_FOUND}] Project 不存在: ${projectId.value}"),
    )

    /* ---------------- internals：投影辅助 ---------------- */

    /** 任务目的：**复用既有** `IntentType`，不新造平行词汇（I6 §7）。 */
    private fun purposeOf(kind: WorkspaceObjectKind): IntentType = when (kind) {
        WorkspaceObjectKind.CHAPTER, WorkspaceObjectKind.DRAFT -> IntentType.REWRITE
        WorkspaceObjectKind.CHARACTER, WorkspaceObjectKind.WORLD_RULE,
        WorkspaceObjectKind.FORESHADOW, WorkspaceObjectKind.TIMELINE_ENTRY,
        -> IntentType.ANALYZE

        WorkspaceObjectKind.TASK -> IntentType.CUSTOM
        else -> IntentType.PLAN
    }

    /** 只有 TASK 选择有真实会话链路（`AgentSession.taskId`）时携带 sessionId；其余为 null（不猜测）。 */
    private fun sessionIdOf(projectId: ProjectId, selection: WorkspaceSelection): com.qianyan.model.AgentSessionId? {
        val taskId = selection.taskId ?: return null
        return guard { sessions.sessionsOfProject(projectId) }
            .firstOrNull { it.taskId == taskId }
            ?.sessionId
    }

    private fun focusLabel(projectId: ProjectId, selection: WorkspaceSelection): String? =
        when (selection.kind) {
            WorkspaceObjectKind.CHAPTER, WorkspaceObjectKind.DRAFT -> selection.chapterId?.let { id ->
                guard { chapters.findById(id) }?.let { "第 ${it.order} 章 · ${it.title}" }
            }

            else -> null
        }

    /** 对象标题 / 种类说明（来自既有来源；取不到就如实回落到 id）。 */
    private fun describe(projectId: ProjectId, selection: WorkspaceSelection): Pair<String, String> = when (selection.kind) {
        WorkspaceObjectKind.PROJECT, WorkspaceObjectKind.NOVEL -> {
            val project = readProject(projectId)
            (readNovel(project.novelId, projectId).title) to "项目"
        }

        WorkspaceObjectKind.CHAPTER -> {
            val id = selection.chapterId
            val chapter = id?.let { guard { chapters.findById(it) } }
            ((chapter?.let { "第 ${it.order} 章 · ${it.title}" }) ?: id?.value ?: "章节") to "章节"
        }

        WorkspaceObjectKind.DRAFT -> {
            val id = selection.draftId
            val draft = id?.let { guard { writer.draft(it) } }
            ((draft?.let { "正文 · ${it.status.name}" }) ?: id?.value ?: "正文") to "正文"
        }

        WorkspaceObjectKind.CHARACTER -> {
            val id = selection.characterId
            val character = id?.let { guard { storyState.getCharacterById(it) } }
            ((character?.name) ?: id?.value ?: "人物") to "人物"
        }

        WorkspaceObjectKind.WORLD_RULE -> {
            val id = selection.worldRuleId
            val rule = id?.let { guard { storyState.getWorldRuleById(it) } }
            ((rule?.content?.take(40)) ?: id?.value ?: "世界规则") to "世界规则"
        }

        WorkspaceObjectKind.FORESHADOW -> {
            val id = selection.foreshadowId
            val foreshadow = id?.let { guard { storyState.getForeshadowById(it) } }
            ((foreshadow?.content?.take(40)) ?: id?.value ?: "伏笔") to "伏笔"
        }

        WorkspaceObjectKind.TIMELINE_ENTRY -> {
            val id = selection.timelineEntryId
            val entry = id?.let { guard { storyState.getTimelineEntryById(it) } }
            ((entry?.description?.ifBlank { null }) ?: id?.value ?: "时间线") to "时间线"
        }

        WorkspaceObjectKind.STORY_FOUNDATION -> "故事基础（已确认）" to "故事基础"

        WorkspaceObjectKind.VOCABULARY -> {
            val candidateId = selection.vocabularyCandidateId
            val label = candidateId?.let { key ->
                guard { index.rebuild(projectId) }.entries
                    .firstOrNull { it.reference.vocabularyCandidateId == key }
                    ?.label
            }
            (label ?: candidateId?.value ?: "词汇") to "词汇"
        }

        WorkspaceObjectKind.TASK -> {
            val id = selection.taskId
            val task = id?.let { runCatching { tasks.findById(it) }.getOrNull() }
            ("任务 · ${task?.type?.name ?: id?.value ?: "未知"}") to "任务"
        }

        WorkspaceObjectKind.CHANGE -> {
            val id = selection.changeArtifactId
            val artifact = id?.let { key -> changes.list(projectId).firstOrNull { it.artifactId == key } }
            ((artifact?.change?.summary) ?: id?.value ?: "待确认修改") to "待确认修改"
        }

        WorkspaceObjectKind.HISTORY -> {
            val id = selection.historyId
            val entry = id?.let { key -> guard { commits.history(projectId) }.firstOrNull { it.historyId == key } }
            ((entry?.summary) ?: id?.value ?: "历史") to "历史"
        }
    }

    /**
     * 如实说明本次 Context 的边界（§16：**没有稳定来源就不伪造**）。
     */
    private fun noticesFor(selection: WorkspaceSelection, pack: ContextPack): List<String> = buildList {
        when (selection.kind) {
            WorkspaceObjectKind.CHAPTER, WorkspaceObjectKind.DRAFT -> Unit // 章节焦点是 I6 原生能力
            WorkspaceObjectKind.PROJECT, WorkspaceObjectKind.NOVEL,
            WorkspaceObjectKind.STORY_FOUNDATION, WorkspaceObjectKind.VOCABULARY,
            -> Unit

            WorkspaceObjectKind.CHARACTER, WorkspaceObjectKind.WORLD_RULE,
            WorkspaceObjectKind.FORESHADOW, WorkspaceObjectKind.TIMELINE_ENTRY,
            -> add("该对象级 Context 尚无专用来源：以下为所属作品的 Context（I6 目前支持 Project / 章节焦点）。")

            WorkspaceObjectKind.TASK ->
                add("任务级 Context 复用 I6（不新建任务级来源）；任务状态见上方任务投影。")

            WorkspaceObjectKind.CHANGE, WorkspaceObjectKind.HISTORY ->
                add("修改 / 历史不参与 Context 选择；以下为所属作品的 Context。")
        }
        if (pack.items.isEmpty()) add("本次 Context 为空：该项目当前没有可进入 Context 的来源。")
        if (pack.budgetGuard.isTruncated) {
            add("预算裁剪：已淘汰 ${pack.budgetGuard.omittedCount} 条（Budget ${pack.budgetGuard.budgetTokens}）。")
        }
    }
}