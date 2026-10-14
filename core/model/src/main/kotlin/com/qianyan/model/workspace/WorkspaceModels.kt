package com.qianyan.model.workspace

import com.qianyan.model.ChapterId
import com.qianyan.model.CharacterId
import com.qianyan.model.DraftId
import com.qianyan.model.ForeshadowingId
import com.qianyan.model.NovelId
import com.qianyan.model.ProjectId
import com.qianyan.model.TaskId
import com.qianyan.model.TimelineEntryId
import com.qianyan.model.VariantId
import com.qianyan.model.VocabularyCandidateId
import com.qianyan.model.WorldRuleId
import com.qianyan.model.change.ChangeArtifactId
import com.qianyan.model.commit.CommitHistoryId
import kotlinx.serialization.Serializable

/*
 * I14 · Workspace 契约（纯领域：无 storage / provider / agent runtime / UI 依赖）。
 *
 * 定位（architecture §8 / §25；direction D8）：
 *   Project（长期）→ **Workspace（当前工作会话：当前对象 + 视图状态）**
 *        ↓
 *   Context Inspector（上下文 / 活动 / 修改 —— 只读投影）
 *
 * 严格边界：
 *  - **ProjectState ≠ WorkspaceState**：`ProjectState` 是**持久化**的项目工作状态
 *    （activeVariantId / activeChapterId / activeTaskId，见 I1）；本文件的 [WorkspaceSelection] 是
 *    **UI / 当前工作会话**状态（谁被选中、Inspector 看哪个 tab），**默认不落库**（§5）；
 *  - **不是第二套业务模型**：选择只保存**引用**（既有强类型 ID），不复制 Novel / Chapter /
 *    Draft / Character / Task / Change / History 的任何字段；
 *  - **不是第二套 Context / Activity / Change / Task / StateMachine**：全部展示数据来自既有
 *    I6 ContextPack / I4 Activity·ToolCallLog / I9 ChangeArtifact / I10 CommitHistory / I13 TaskQueue；
 *  - **不伪造**：当前没有稳定 Canonical 来源的对象（如 Place / Faction / Plot / Scene）
 *    **不**定义选择类型（§6）。
 */

/**
 * 当前工作对象的类型（**只包含本阶段有真实来源的对象**，§6 / §16）。
 *
 * 刻意**不**包含 PLACE / FACTION / PLOT / SCENE —— 它们在当前仓库没有稳定 Canonical 模型，
 * 定义它们等于伪造（§6「不要为了 I14 新建完整领域模型」）。
 */
@Serializable
enum class WorkspaceObjectKind {
    /** Project（I1：Project + ProjectState）。 */
    PROJECT,

    /** Novel 作品（既有 Novel）。 */
    NOVEL,

    /** 章节（既有 Chapter）。 */
    CHAPTER,

    /** 章节正文（既有 ChapterDraft / Draft）。 */
    DRAFT,

    /** 人物（既有 Story State Character）。 */
    CHARACTER,

    /** 世界规则（既有 Story State WorldRule）。 */
    WORLD_RULE,

    /** 伏笔（既有 Story State Foreshadow）。 */
    FORESHADOW,

    /** 时间线条目（既有 Story State TimelineEntry）。 */
    TIMELINE_ENTRY,

    /** 故事基础（既有 StoryFoundation，I12 同源）。 */
    STORY_FOUNDATION,

    /** 词汇候选（既有 VocabularyCandidate）。 */
    VOCABULARY,

    /** 任务（既有 Task + I13 TaskQueueItem）。 */
    TASK,

    /** 待确认修改（I9 ChangeArtifact）。 */
    CHANGE,

    /** 已提交历史（I10 CommitHistory）。 */
    HISTORY,
}

/**
 * 当前工作会话的**唯一**选择（§7：整个 Workspace 只有一个当前选择）。
 *
 * 只保存引用；具体标题 / 内容 / 状态一律在 Inspector 投影时从既有来源读取（不复制、不缓存事实）。
 * 未使用的引用字段为 null —— 由 [kind] 决定哪些字段有意义（由 Facade 校验并规范化）。
 */
@Serializable
data class WorkspaceSelection(
    val projectId: ProjectId,
    val kind: WorkspaceObjectKind,
    val novelId: NovelId? = null,
    val variantId: VariantId? = null,
    val chapterId: ChapterId? = null,
    val draftId: DraftId? = null,
    val characterId: CharacterId? = null,
    val worldRuleId: WorldRuleId? = null,
    val foreshadowId: ForeshadowingId? = null,
    val timelineEntryId: TimelineEntryId? = null,
    val vocabularyCandidateId: VocabularyCandidateId? = null,
    val taskId: TaskId? = null,
    val changeArtifactId: ChangeArtifactId? = null,
    val historyId: CommitHistoryId? = null,
) {
    companion object {
        /** Project 级选择（Workspace 的默认落点）。 */
        fun project(projectId: ProjectId, novelId: NovelId? = null, variantId: VariantId? = null): WorkspaceSelection =
            WorkspaceSelection(projectId = projectId, kind = WorkspaceObjectKind.PROJECT, novelId = novelId, variantId = variantId)

        fun novel(projectId: ProjectId, novelId: NovelId, variantId: VariantId? = null): WorkspaceSelection =
            WorkspaceSelection(projectId = projectId, kind = WorkspaceObjectKind.NOVEL, novelId = novelId, variantId = variantId)

        fun chapter(projectId: ProjectId, novelId: NovelId, chapterId: ChapterId, variantId: VariantId? = null): WorkspaceSelection =
            WorkspaceSelection(
                projectId = projectId, kind = WorkspaceObjectKind.CHAPTER,
                novelId = novelId, variantId = variantId, chapterId = chapterId,
            )

        fun draft(projectId: ProjectId, novelId: NovelId, chapterId: ChapterId, draftId: DraftId, variantId: VariantId? = null): WorkspaceSelection =
            WorkspaceSelection(
                projectId = projectId, kind = WorkspaceObjectKind.DRAFT,
                novelId = novelId, variantId = variantId, chapterId = chapterId, draftId = draftId,
            )

        fun character(projectId: ProjectId, novelId: NovelId, characterId: CharacterId, variantId: VariantId? = null): WorkspaceSelection =
            WorkspaceSelection(
                projectId = projectId, kind = WorkspaceObjectKind.CHARACTER,
                novelId = novelId, variantId = variantId, characterId = characterId,
            )

        fun task(projectId: ProjectId, taskId: TaskId, novelId: NovelId? = null): WorkspaceSelection =
            WorkspaceSelection(projectId = projectId, kind = WorkspaceObjectKind.TASK, novelId = novelId, taskId = taskId)

        fun change(projectId: ProjectId, artifactId: ChangeArtifactId, novelId: NovelId? = null): WorkspaceSelection =
            WorkspaceSelection(projectId = projectId, kind = WorkspaceObjectKind.CHANGE, novelId = novelId, changeArtifactId = artifactId)

        fun history(projectId: ProjectId, historyId: CommitHistoryId, novelId: NovelId? = null): WorkspaceSelection =
            WorkspaceSelection(projectId = projectId, kind = WorkspaceObjectKind.HISTORY, novelId = novelId, historyId = historyId)
    }
}

/**
 * Inspector 的固定标签（§8：**永远只有这三个**）。
 *
 * 禁止按页面派生不同 Inspector（章节 Inspector / 人物 Inspector / …）——
 * 一切页面共用同一个 Inspector，只是数据来源不同。
 */
@Serializable
enum class InspectorTab { CONTEXT, ACTIVITY, CHANGES }

/** 一个可被选为"当前对象"的候选（供 Workspace 选择器展示；纯引用 + 展示标签）。 */
@Serializable
data class WorkspaceObjectCandidate(
    val selection: WorkspaceSelection,
    val label: String,
    val detail: String = "",
)

/**
 * Workspace 的稳定错误码（承载在既有 `ApplicationError` 的 detail 中；**不新建错误体系**）。
 */
object WorkspaceErrorCodes {

    /** 指定的 Project / Novel 不存在（越界统一表现为不存在，不泄漏存在性）。 */
    const val PROJECT_NOT_FOUND: String = "PROJECT_NOT_FOUND"

    /** 选择引用的对象不存在（或不属于该 Project，越界统一表现为不存在）。 */
    const val OBJECT_NOT_FOUND: String = "OBJECT_NOT_FOUND"

    /** 选择缺少该 [WorkspaceObjectKind] 必需的引用字段。 */
    const val SELECTION_INCOMPLETE: String = "SELECTION_INCOMPLETE"
}