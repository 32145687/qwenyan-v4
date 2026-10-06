package com.qianyan.application.di

import app.cash.sqldelight.db.SqlDriver
import com.qianyan.application.error.ErrorMapper
import com.qianyan.application.usecase.analysis.AnalysisUseCases
import com.qianyan.application.usecase.action.ActionPolicyUseCases
import com.qianyan.application.usecase.memory.MemoryUseCases
import com.qianyan.application.usecase.novel.NovelUseCases
import com.qianyan.application.usecase.project.ProjectUseCases
import com.qianyan.application.usecase.runtimeintegration.RuntimeIntegrationUseCases
import com.qianyan.application.usecase.runtimeintegration.RuntimeSessionBindingUseCases
import com.qianyan.application.usecase.session.AgentSessionUseCases
import com.qianyan.application.usecase.log.ActivityUseCases
import com.qianyan.application.usecase.log.ToolCallLogUseCases
import com.qianyan.application.usecase.tool.ProductToolService
import com.qianyan.application.usecase.tool.readOnlyProductTools
import com.qianyan.application.usecase.context.ContextEngineUseCases
import com.qianyan.application.usecase.context.defaultContextSources
import com.qianyan.agent.agents.SkillRegistry
import com.qianyan.application.usecase.draft.WorkingDraftUseCases
import com.qianyan.application.usecase.draft.WorkingDraftValidator
import com.qianyan.application.usecase.change.ChangeUseCases
import com.qianyan.application.usecase.commit.CommitUseCases
import com.qianyan.application.usecase.agent.NovelAgent
import com.qianyan.application.usecase.index.ProjectIndexUseCases
import com.qianyan.application.usecase.genre.GenreTaxonomyUseCases
import com.qianyan.application.usecase.override.OverrideUseCases
import com.qianyan.application.usecase.txt.TxtUseCases
import com.qianyan.application.usecase.task.TaskManagerUseCases
import com.qianyan.application.usecase.task.TaskRunner
import com.qianyan.application.usecase.taskqueue.TaskExecutor
import com.qianyan.application.usecase.taskqueue.TaskQueueUseCases
import com.qianyan.application.usecase.taskqueue.TaskWorker
import com.qianyan.model.taskqueue.TaskKind
import com.qianyan.application.usecase.vocabulary.VocabularyUseCases
import com.qianyan.application.usecase.chapter.ChapterUseCases
import com.qianyan.application.usecase.chapter.ChapterWritingUseCases
import com.qianyan.application.usecase.workflow.ChapterWorkflowFacade
import com.qianyan.application.usecase.workflow.WorkflowOrchestrator
import com.qianyan.application.usecase.workflow.WorkflowService
import com.qianyan.application.usecase.foundation.FoundationDecisionFacade
import com.qianyan.application.usecase.foundation.FoundationDecisionGateway
import com.qianyan.application.usecase.foundation.IdeaFirstFacade
import com.qianyan.application.usecase.foundation.IdeaFirstGateway
import com.qianyan.application.usecase.foundation.IdeaUnderstandingAgent
import com.qianyan.application.usecase.foundation.StoryIntentUseCases
import com.qianyan.application.usecase.foundation.StoryFoundationDecisionUseCases
import com.qianyan.application.usecase.author.AuthorContextProjection
import com.qianyan.application.usecase.author.AuthorCoreFacade
import com.qianyan.application.usecase.author.AuthorCoreGateway
import com.qianyan.application.usecase.author.AuthorCoreUseCases
import com.qianyan.application.usecase.author.AuthorDnaFacade
import com.qianyan.application.usecase.author.AuthorDnaGateway
import com.qianyan.application.usecase.author.AuthorDnaUseCases
import com.qianyan.application.usecase.author.AuthorIntelligenceFacade
import com.qianyan.application.usecase.author.AuthorIntelligenceGateway
import com.qianyan.application.usecase.author.AuthorPreferenceUseCases
import com.qianyan.application.usecase.author.P15FoundationEvidenceSource
import com.qianyan.application.usecase.reading.ReadingUseCases
import com.qianyan.application.usecase.writing.WritingUseCases
import com.qianyan.application.usecase.writing.WritingExecutionUseCase
import com.qianyan.application.usecase.writing.WriterAgent
import com.qianyan.application.usecase.writing.WriterFacade
import com.qianyan.application.usecase.writing.WriterUseCases
import com.qianyan.application.usecase.writing.critique.CritiqueAgent
import com.qianyan.application.usecase.writing.critique.CritiqueExecutionUseCase
import com.qianyan.application.usecase.writing.knowledgeupdate.KnowledgeUpdateAgent
import com.qianyan.application.usecase.writing.knowledgeupdate.KnowledgeUpdateExecutionUseCase
import com.qianyan.application.usecase.story.StoryStateVariantUseCases
import com.qianyan.application.usecase.story.ForeshadowLifecycleUseCases
import com.qianyan.application.usecase.story.RevealUseCases
import com.qianyan.application.usecase.writing.context.StoryWorldContextResolver
import com.qianyan.application.usecase.writing.confirmation.ConfirmationExecutionUseCase
import com.qianyan.application.usecase.writing.planning.ContinuationResolver
import com.qianyan.application.usecase.writing.planning.PlanningContextAssembly
import com.qianyan.application.usecase.writing.planning.PlanningExecutionUseCase
import com.qianyan.application.usecase.writing.planning.PlannerAgent
import com.qianyan.application.usecase.writing.revision.RevisionAgent
import com.qianyan.application.usecase.writing.revision.RevisionExecutionUseCase
import com.qianyan.application.usecase.lcl.ChapterContextCompileUseCases
import com.qianyan.application.usecase.lcl.NarrativeStateUseCases
import com.qianyan.application.usecase.lcl.RollingHorizonUseCases
import com.qianyan.engine.analysis.AnalysisInputBuilder
import com.qianyan.engine.txt.TxtPipeline
import com.qianyan.provider.LLMGateway
import com.qianyan.provider.ModelProfile
import com.qianyan.provider.ProviderAssembler
import com.qianyan.provider.ProviderConfiguration
import com.qianyan.storage.db.QianyanDb
import com.qianyan.storage.db.QianyanDbFactory
import com.qianyan.storage.db.QianyanDbHandle
import com.qianyan.application.usecase.author.ObservationCollector
import com.qianyan.application.usecase.decision.DecisionModelFacade
import com.qianyan.application.usecase.decision.DecisionModelGateway
import com.qianyan.application.usecase.decision.DecisionModelUseCases
import com.qianyan.storage.repository.AgentSessionRepository
import com.qianyan.storage.repository.ActivityRepository
import com.qianyan.storage.repository.ToolCallLogRepository
import com.qianyan.storage.repository.CommitHistoryRepository
import com.qianyan.storage.repository.SqliteCommitHistoryRepository
import com.qianyan.storage.repository.AuthorCoreRepository
import com.qianyan.storage.repository.AuthorDnaRepository
import com.qianyan.storage.repository.AuthorObservationRepository
import com.qianyan.storage.repository.AuthorPreferenceRepository
import com.qianyan.storage.repository.BackupStore
import com.qianyan.storage.repository.ChapterRepository
import com.qianyan.storage.repository.DraftRepository
import com.qianyan.storage.repository.MemoryRepository
import com.qianyan.storage.repository.NarrativeStateRepository
import com.qianyan.storage.repository.NovelRepository
import com.qianyan.storage.repository.ProjectStateRepository
import com.qianyan.storage.repository.ReadingProgressRepository
import com.qianyan.storage.repository.SqliteAgentSessionRepository
import com.qianyan.storage.repository.RuntimeSessionRefRepository
import com.qianyan.storage.repository.SqliteRuntimeSessionRefRepository
import com.qianyan.runtime.contract.AgentRuntimeGateway
import com.qianyan.storage.repository.SqliteActivityRepository
import com.qianyan.storage.repository.SqliteToolCallLogRepository
import com.qianyan.storage.repository.SqliteAuthorCoreRepository
import com.qianyan.storage.repository.SqliteAuthorDnaRepository
import com.qianyan.storage.repository.SqliteAuthorObservationRepository
import com.qianyan.storage.repository.SqliteAuthorPreferenceRepository
import com.qianyan.storage.repository.SqliteBackupStore
import com.qianyan.storage.repository.SqliteChapterRepository
import com.qianyan.storage.repository.SqliteDraftRepository
import com.qianyan.storage.repository.SqliteMemoryRepository
import com.qianyan.storage.repository.SqliteNarrativeStateRepository
import com.qianyan.storage.repository.SqliteNovelRepository
import com.qianyan.storage.repository.SqliteProjectStateRepository
import com.qianyan.storage.repository.SqliteReadingProgressRepository
import com.qianyan.storage.repository.SqliteTaskRepository
import com.qianyan.storage.repository.SqliteTaskQueueRepository
import com.qianyan.storage.repository.SqliteTxtRepository
import com.qianyan.storage.repository.SqliteStoryStateRepository
import com.qianyan.storage.repository.SqliteVocabularyRepository
import com.qianyan.storage.repository.SqliteWorkflowRepository
import com.qianyan.storage.repository.StoryStateRepository
import com.qianyan.storage.repository.StoryFoundationRepository
import com.qianyan.storage.repository.SqliteStoryFoundationRepository
import com.qianyan.storage.repository.TaskRepository
import com.qianyan.storage.repository.TaskQueueRepository
import com.qianyan.storage.repository.TxtRepository
import com.qianyan.storage.repository.VocabularyRepository
import com.qianyan.storage.repository.WorkflowRepository

/**
 * Application 层组合根（手动 DI，P3.1）。
 *
 * 职责：把 [NovelRepository]、[VocabularyRepository]、[MemoryRepository]、[BackupStore]、
 * [TxtRepository]、[TaskRepository]、[DraftRepository] 七个仓储装配进来，并暴露各 Use Case 组。外部（app / runtime / agent 调用方）
 * 只通过本容器访问 Application 能力，不直接触碰 Sqlite 实现。
 *
 * P5 演进：新增 [TxtPipeline]（core:engine 确定性解析）与 [TxtUseCases]。
 * P6 演进：Analysis 在此执行层跑通，容器注入 [LLMGateway]（Provider 契约，实现由装配方注入 ——
 * 测试/装配方提供 :provider:impl 的 Mock）。新增 [AnalysisUseCases]。装配链：
 * ApplicationContainer → AnalysisInputBuilder → LLMGateway(:provider:api) → TxtRepository → VocabularyRepository → AnalysisUseCases。
 * Analysis Use Case 只依赖 Provider 契约接口，不绑定具体实现；仓储实现始终在 `:storage`。
 * P8.2 演进：新增 [TaskRepository]（P8.1 持久化）与 [TaskManagerUseCases]（Task 状态机 / Checkpoint 管理）。
 * P8.3 演进：新增 [TaskRunner]（Task 执行驱动：受管执行 IMPORT，复用 TaskManager 生命周期）。
 * P9 演进：真实 Provider 接入。容器仅暴露 [LLMGateway] 契约 seam；装配方注入 DeepSeek / MiMo / Mock 实现，
 * 并通过 [analysisModel] 选择 Analysis 请求的模型（默认 MOCK，保持既有行为；真实模型见 :provider:impl）。
 */
class ApplicationContainer(
    val novelRepository: NovelRepository,
    val vocabularyRepository: VocabularyRepository,
    val memoryRepository: MemoryRepository,
    val backupStore: BackupStore,
    val txtRepository: TxtRepository,
    val taskRepository: TaskRepository,
    val draftRepository: DraftRepository,
    val chapterRepository: ChapterRepository,
    private val storyStateRepository: StoryStateRepository,
    val workflowRepository: WorkflowRepository,
    private val narrativeStateRepository: NarrativeStateRepository,
    val storyFoundationRepository: StoryFoundationRepository,
    val authorPreferenceRepository: AuthorPreferenceRepository,
    val authorCoreRepository: AuthorCoreRepository,
    val authorObservationRepository: AuthorObservationRepository,
    val authorDnaRepository: AuthorDnaRepository,
    /** P20-P4 · Reader 阅读位置仓储（FD-9：只承担阅读位置 / 进度）。 */
    val readingProgressRepository: ReadingProgressRepository,
    /** I1 · Project State 仓储（IDE/Agent 运行态引用；不承载小说事实与 Task/Workflow 生命周期）。 */
    private val projectStateRepository: ProjectStateRepository,
    /** I3 · Agent Session 仓储（会话身份 + Project 归属 + Workflow/Task 引用；不承载其状态与小说事实）。 */
    private val agentSessionRepository: AgentSessionRepository,
    /** I4 · Activity / ToolCallLog 仓储（已发生行为的事实记录；是记录，不是控制器）。 */
    private val activityRepository: ActivityRepository,
    private val toolCallLogRepository: ToolCallLogRepository,
    /** I10 · Commit History 仓储（Canonical Commit / Revert 的不可变审计记录）。 */
    private val commitHistoryRepository: CommitHistoryRepository,
    /** I13 · 后台任务队列仓储（Task 的**调度条目**；不承载 Task 生命周期）。 */
    val taskQueueRepository: TaskQueueRepository,
    /** I1 · Runtime Session 绑定仓储（Qianyan 会话 ↔ 外部运行时会话的**旁路引用**；不承载 transcript）。 */
    private val runtimeSessionRefRepository: RuntimeSessionRefRepository,
    private val analysisGateway: LLMGateway,
    private val analysisModel: ModelProfile = ModelProfile.MOCK,
    private val txtPipeline: TxtPipeline = TxtPipeline(),
    /**
     * I1 · 外部 Agent Runtime 契约（null = 本容器未启用外部 Runtime）。
     *
     * **只按契约注入**：容器（Application 层）不认识任何 Adapter / 供应商实现；
     * 具体实现由组合根（app 装配根）构造后传入，与 Provider 的注入方式同构。
     */
    private val runtimeGateway: AgentRuntimeGateway? = null,
) {

    val errorMapper: ErrorMapper = ErrorMapper

    val novels: NovelUseCases get() = NovelUseCases(novelRepository, errorMapper)

    /**
     * I1 · Project 聚合入口 + Project State（Novel IDE 第一阶段）。
     *
     * 只做聚合与运行态读写：Project 身份来自 `Novel.projectId`，运行态来自 `ProjectState` 表；
     * 不复制 Novel 元数据、不建立第二套状态机、不承载小说世界事实（见 architecture §4 / §8）。
     */
    val projects: ProjectUseCases
        get() = ProjectUseCases(novels, novelRepository, chapterRepository, projectStateRepository, errorMapper)

    /**
     * I2 · Action Policy（Agent 行动权限）+ 既有 Human Gate 复用。
     *
     * 只做"动作是否允许"的确定性判断，并把需要人工确认的动作接到**既有** Workflow Human Gate；
     * 不改 Workflow 生命周期语义、不新建权限状态机、与 P19 创作决策无关
     * （见 docs/architecture/qianyan-novel-ide-architecture.md §16 / §27）。
     */
    val actionPolicy: ActionPolicyUseCases
        get() = ActionPolicyUseCases(workflowRepository, workflowService, errorMapper)

    /**
     * I3 · Agent Session（一次持续 Agent 工作的可持久化会话边界）。
     *
     * 只做会话身份 / Project 归属 / 既有 Workflow·Task 引用 / 会话自身生命周期 / 可恢复身份查询；
     * 不建立第二套 Workflow·Task 状态机、不实现 Resume Engine、不改 AgentRuntime
     * （见 docs/architecture/qianyan-novel-ide-architecture.md §13 / §41）。
     */
    val agentSessions: AgentSessionUseCases
        get() = AgentSessionUseCases(novelRepository, agentSessionRepository, workflowRepository, taskRepository, errorMapper)

    /**
     * I4 · Activity / Tool Call Log（Agent 工作过程的事实记录）。
     *
     * 只记录"发生了什么活动 / 调用了什么 Tool"，是观察记录而非控制器：
     * 不创建或改写 AgentSession / Workflow / Task 状态，不执行 Tool，不含 Tool Registry / Skill / Context
     * （见 docs/architecture/qianyan-novel-ide-architecture.md §15）。
     */
    val activities: ActivityUseCases
        get() = ActivityUseCases(agentSessionRepository, activityRepository, errorMapper)

    val toolCallLogs: ToolCallLogUseCases
        get() = ToolCallLogUseCases(activityRepository, toolCallLogRepository, errorMapper)

    /**
     * I5 · 只读 Product Tool 调度入口（Novel Agent 读取项目的统一接口）。
     *
     * 只注册**只读**工具（READ / SEARCH），不注册任何写能力；执行经既有 `ToolRegistry` / `ToolExecutor`，
     * 放行判定复用 I2 [actionPolicy]，调用事实落到 I4 [toolCallLogs]
     * （见 docs/architecture/qianyan-novel-ide-architecture.md §12）。
     */
    val productTools: ProductToolService
        get() = ProductToolService(
            tools = readOnlyProductTools(projects, novels, chapters, writerUseCases, vocabularies),
            activities = activities,
            toolCallLogs = toolCallLogs,
            actionPolicy = actionPolicy,
        )

    /**
     * I6 · Context Engine（任务级 Context 构建；Novel IDE 第 6 阶段）。
     *
     * 把"当前任务"翻译成 `ContextPack`：只读已有 Project / Novel / Chapter / Draft / StoryFoundation / Vocabulary，
     * 经确定性 Selection / Priority / Budget 产出可审计的冻结快照；不写状态、不调 LLM、不建第二套长期数据
     * （见 docs/architecture/qianyan-novel-ide-architecture.md §9 / §25）。
     */
    val contextEngine: ContextEngineUseCases
        get() = ContextEngineUseCases(
            projects = projects,
            chapters = chapters,
            sessions = agentSessions,
            sources = defaultContextSources(
                projects = projects,
                novels = novels,
                chapters = chapters,
                writer = writerUseCases,
                vocabularies = vocabularies,
                foundations = storyFoundationRepository,
            ),
            errorMapper = errorMapper,
        )

    /**
     * I7 · Skill Registry（专业创作方法 / 能力的注册与发现；Novel IDE 第 7 阶段）。
     *
     * 纯内存不可变注册表（无数据库、无迁移）；只回答问题"这类任务有哪些 Skill 可处理"，
     * **不执行 Skill / 不调用 LLM / 不执行 Tool / 不推进 Workflow / 不判定权限**
     * （见 docs/architecture/qianyan-novel-ide-architecture.md §11）。
     */
    val skillRegistry: SkillRegistry
        get() = SkillRegistry.default()

    /**
     * I8 · Working Draft 工作区（Agent / Skill 的临时成果区；Novel IDE 第 8 阶段）。
     *
     * **有意持有单一实例**（应用级内存工作区）：Working Draft 是"一次任务中的临时产物"，
     * 本阶段不落库、不加表、不加迁移；校验复用既有 `ValidationResult`，且只读 Canonical 数据
     * （见 docs/architecture/qianyan-novel-ide-architecture.md §17 / §18）。
     */
    val workingDrafts: WorkingDraftUseCases = WorkingDraftUseCases(
        projects = projects,
        chapters = chapters,
        drafts = writerUseCases,
        validator = WorkingDraftValidator(
            projects = projects,
            chapters = chapters,
            sessions = agentSessions,
            activities = activities,
            drafts = writerUseCases,
            errorMapper = errorMapper,
        ),
        errorMapper = errorMapper,
    )

    /**
     * I9 · Change Review（Diff / Change / Artifact；Novel IDE 第 9 阶段）。
     *
     * 在 I8 Working Draft 之上生成**变更审查载体**；**有意持有单一实例**（应用级内存 Artifact Store）：
     * 不落库、不加表、不加迁移，且只读 Canonical 数据、不执行 Commit
     * （见 docs/architecture/qianyan-novel-ide-architecture.md §19 / §26）。
     */
    val changes: ChangeUseCases = ChangeUseCases(
        workingDrafts = workingDrafts,
        validator = WorkingDraftValidator(
            projects = projects,
            chapters = chapters,
            sessions = agentSessions,
            activities = activities,
            drafts = writerUseCases,
            errorMapper = errorMapper,
        ),
        drafts = writerUseCases,
        errorMapper = errorMapper,
    )

    /**
     * I10 · Canonical Commit + History / Revert（Novel IDE 第 10 阶段）。
     *
     * 唯一的 Canonical 正文写入入口：输入必须是 I9 Change Artifact；原子写入复用既有
     * `WorkflowRepository.inTransaction`（真实跨仓储 DB 事务），放行判定复用 I2 [actionPolicy]，
     * Working Draft 收尾复用 I8 [workingDrafts]。**有意持有单一实例**（无自身状态，与 [changes] 同构）。
     */
    val commits: CommitUseCases = CommitUseCases(
        projects = projects,
        chapters = chapters,
        workingDrafts = workingDrafts,
        changes = changes,
        drafts = writerUseCases,
        history = commitHistoryRepository,
        workflows = workflows,
        actionPolicy = actionPolicy,
        errorMapper = errorMapper,
    )

    /**
     * I1 · Runtime Session 绑定（Qianyan AgentSession ↔ 外部 Runtime Session，`1 : N`）。
     *
     * 只读写**旁路引用**：不改 AgentSession 模型 / 表语义，不承载 Runtime transcript。
     */
    val runtimeSessionBindings: RuntimeSessionBindingUseCases
        get() = RuntimeSessionBindingUseCases(agentSessionRepository, runtimeSessionRefRepository, errorMapper)

    /**
     * I1 · Runtime Integration（生命周期桥接 seam）。
     *
     * 只按契约使用外部 Runtime：装配 / 未装配都不影响既有能力；**有意持有单一实例**
     * （内部持有载体句柄状态，必须与容器同生命周期）。
     */
    val runtimeIntegration: RuntimeIntegrationUseCases = RuntimeIntegrationUseCases(
        gateway = runtimeGateway,
        sessions = agentSessions,
        bindings = runtimeSessionBindings,
        activities = activities,
        errorMapper = errorMapper,
    )

    /**
     * I11 · Novel Agent（Novel IDE 核心**编排**入口；第 11 阶段）。
     *
     * 决定"下一步做什么"，把每一步交给既有能力：I7 SkillRegistry（选择）→ I6 ContextEngine（上下文）→
     * 既有五 Agent + I5 ProductToolService（执行）→ I8 WorkingDraft/Validation → I9 Diff/Artifact →
     * I2 ActionPolicy/Human Gate → I10 Commit。**不直接写 Canonical**，也不重造 Tool / Skill / Context /
     * Draft / Validation / Policy / Gate / Workflow。**有意持有单一实例**（无自身状态）。
     */
    val novelAgent: NovelAgent = NovelAgent(
        projects = projects,
        chapters = chapters,
        sessions = agentSessions,
        activities = activities,
        skillRegistry = skillRegistry,
        contextEngine = contextEngine,
        productTools = productTools,
        workingDrafts = workingDrafts,
        changes = changes,
        commits = commits,
        actionPolicy = actionPolicy,
        planner = planner,
        writer = writer,
        critic = critic,
        rewriter = rewriter,
        planningContexts = planningContextAssembly,
        reader = writerUseCases,
        runtimeIntegration = runtimeIntegration,
        errorMapper = errorMapper,
    )

    /**
     * I12 · Project Index（项目内容的**派生**索引：可定位 / 可搜索 / 可重建；第 12 阶段）。
     *
     * 只经既有 Canonical 读取能力（ProjectUseCases / NovelUseCases / ChapterUseCases / WriterUseCases /
     * VocabularyUseCases / 既有 `StoryFoundationRepository`）从 Canonical 数据重建；
     * **有意持有单一实例**（应用级内存派生存储，不落库、不加表、不加迁移），可整体丢弃并重建。
     * 不写 Canonical、不做 Context 选择、不建立第二套搜索基础设施。
     */
    val projectIndex: ProjectIndexUseCases = ProjectIndexUseCases(
        projects = projects,
        novels = novels,
        chapters = chapters,
        writer = writerUseCases,
        vocabularies = vocabularies,
        foundations = storyFoundationRepository,
        errorMapper = errorMapper,
    )

    /**
     * I13 · 后台任务队列（第 13 阶段）：只做**调度**（入队 / 原子领取 / 完成 / 失败 / 有限重试 /
     * 取消 / 暂停恢复 / 崩溃恢复），Task 生命周期仍由既有 [tasks] 承载。
     *
     * 无自身状态（状态在 Task + TaskQueueItem 表），故每次访问都新建（与其他无状态 Use Case 一致）。
     */
    val taskQueue: TaskQueueUseCases
        get() = TaskQueueUseCases(queue = taskQueueRepository, taskManager = tasks, errorMapper = errorMapper)

    /**
     * I13 · 后台 Worker（`claim → execute → complete/fail`）：执行能力经 [taskExecutors] 映射注入，
     * Worker 本身不决定 Skill / Context / Workflow / Commit，也不直接访问数据库。
     */
    val taskWorker: TaskWorker
        get() = TaskWorker(workerId = LOCAL_WORKER_ID, queue = taskQueue, executors = taskExecutors())

    /**
     * I13 · 后台执行能力映射：**只注册当前真实需要的 kind**（不提前加入未来功能，§10）。
     *
     *  - [TaskKind.PROJECT_INDEX_REBUILD] → 既有 I12 `ProjectIndexUseCases.rebuild(...)`（只调用，不重写）；
     *  - [TaskKind.NOVEL_AGENT] → 本阶段只保留调度键 seam（NovelAgent 的真实请求不入队，
     *    禁止把七相位或完整业务输入复制进 Task，§9 / §21）。
     */
    fun taskExecutors(): Map<TaskKind, TaskExecutor> = mapOf(
        TaskKind.PROJECT_INDEX_REBUILD to TaskExecutor { item -> projectIndex.rebuild(item.projectId) },
    )

    /** P14-A Genre Taxonomy（受控目录 + 确定性校验；Confirmed-Genre 写入见 BLOCKER 说明）。 */
    val genres: GenreTaxonomyUseCases get() = GenreTaxonomyUseCases(errorMapper)
    val overrides: OverrideUseCases get() = OverrideUseCases(novelRepository, errorMapper)
    val vocabularies: VocabularyUseCases get() = VocabularyUseCases(vocabularyRepository, errorMapper)
    val memories: MemoryUseCases get() = MemoryUseCases(memoryRepository, errorMapper)
    val txts: TxtUseCases get() = TxtUseCases(txtPipeline, txtRepository, novelRepository, errorMapper)
    val analysis: AnalysisUseCases get() = AnalysisUseCases(txtRepository, vocabularyRepository, AnalysisInputBuilder, analysisGateway, errorMapper, model = analysisModel)
    val tasks: TaskManagerUseCases get() = TaskManagerUseCases(taskRepository, errorMapper)

    /** P12.1.6 Chapter 读取/创建 Use Case：UI 只经此访问真实章节（禁止直触 ChapterRepository）。 */
    val chapters: ChapterUseCases get() = ChapterUseCases(chapterRepository, novelRepository, errorMapper)

    /**
     * P20-P4 · Reader 阅读 Use Case（FD-7）：既有 Chapter + Draft 正文（经 P2 Controlled Markdown 解析）
     * + 阅读位置；只读 + 位置保存，不新建第二套章节正文模型。
     */
    val reading: ReadingUseCases
        get() = ReadingUseCases(chapterRepository, draftRepository, readingProgressRepository, errorMapper)

    /** P12.1.7 Chapter 写作链编排（Planning→Writing→Critique→Revision→Finalize→Confirm→KnowledgeUpdate），复用既有 UseCases/Task。 */
    val chapterWriting: ChapterWritingUseCases
        get() = ChapterWritingUseCases(
            taskManager = tasks,
            planning = planning,
            writing = writingExecution,
            critique = critique,
            revision = revision,
            confirmation = confirmations,
            knowledgeUpdate = knowledgeUpdate,
            draftRepository = draftRepository,
            errorMapper = errorMapper,
        )

    /** P12.2 Durable Workflow 仓储（Workflow/Step/Attempt/Gate/Continuation）——流程持久化基础。 */
    val workflows: WorkflowRepository get() = workflowRepository

    /** P12.2 Workflow 最小服务：ResultReference 恢复 + HumanGate 幂等批准（Recovery/HITL 地基）。 */
    val workflowService: WorkflowService
        get() = WorkflowService(
            workflowRepository = workflowRepository,
            draftRepository = draftRepository,
            taskManager = tasks,
            confirmation = confirmations,
            knowledgeUpdate = knowledgeUpdate,
            errorMapper = errorMapper,
        )

    /** P12.2 M1-M2 · 章节创作用户层 Facade：Android/未来 Desktop 只经此访问 Durable Workflow（薄转换/进度投影）。 */
    val workflowFacade: com.qianyan.application.usecase.workflow.ChapterWorkflowGateway
        get() = ChapterWorkflowFacade(
            workflowRepository = workflowRepository,
            draftRepository = draftRepository,
            orchestrator = workflowOrchestrator,
            errorMapper = errorMapper,
        )

    /** P12.2 薄 Orchestrator：runForward 驱动 LogicalStep（WRITING + Attempt/retry），复用既有 UseCases。 */
    val workflowOrchestrator: WorkflowOrchestrator
        get() = WorkflowOrchestrator(
            workflowRepository = workflowRepository,
            taskManager = tasks,
            writing = writingExecution,
            planning = planning,
            critique = critique,
            revision = revision,
            confirmation = confirmations,
            knowledgeUpdate = knowledgeUpdate,
            draftRepository = draftRepository,
            chapterRepository = chapterRepository,
            errorMapper = errorMapper,
        )

    /** P11.2/P11.6/P12.1.2/P12.3 确定性 Story World Context 解析器（分层 + canon 优先 + 结构化 Story State + EntityOverride 实体级 merge）。 */
    val storyWorldContextResolver: StoryWorldContextResolver
        get() = StoryWorldContextResolver(memoryRepository, errorMapper, storyStateRepository, novelRepository)

    /** P12.1.1/P12.1.2 结构化 Story State 仓储（Character/WorldRule/Event/Timeline/Foreshadow），供测试与上层读取。 */
    val storyState: StoryStateRepository get() = storyStateRepository

    /** P12.3 Story State Variant 修改入口（ADD / OVERRIDE / REMOVE / INHERIT；Original 拒绝；Variant 隔离）。 */
    val storyStateVariant: StoryStateVariantUseCases
        get() = StoryStateVariantUseCases(storyStateRepository, novelRepository, errorMapper)

    /** P13 LCL-C Foreshadow 生命周期（Variant-only 状态机迁移；不触碰 NarrativeState）。 */
    val foreshadowLifecycle: ForeshadowLifecycleUseCases
        get() = ForeshadowLifecycleUseCases(storyStateRepository, errorMapper)

    /** P13 LCL-D Reveal（Reader-only Story State fact；Variant-only；不触碰 NarrativeState/ContextPack）。 */
    val reveals: RevealUseCases
        get() = RevealUseCases(storyStateRepository, errorMapper)

    /** P13 LCL-D Rolling Horizon（bounded 候选投影 + Task Checkpoint 承载；复用 WorkflowHumanGate 语义）。 */
    val rollingHorizon: RollingHorizonUseCases
        get() = RollingHorizonUseCases(tasks, errorMapper)

    /** P13 LCL-A Narrative State 叙事账本（append / project / get；Original 只读；无 LLM）。 */
    val narrativeState: NarrativeStateUseCases
        get() = NarrativeStateUseCases(narrativeStateRepository, errorMapper)

    /** P13 LCL-B ChapterContextPack 确定性窗口投影（只读编译；无 LLM；不写状态）。 */
    val chapterContextPack: ChapterContextCompileUseCases
        get() = ChapterContextCompileUseCases(storyWorldContextResolver, narrativeState, chapterRepository, errorMapper)

    /** P14-F.3 Story Foundation Confirmation Flow（Proposal→Gate→Confirm→Confirmed；既有 PLANNING 前置确认，Plan A）。 */
    val foundationDecisions: StoryFoundationDecisionUseCases
        get() = StoryFoundationDecisionUseCases(
            workflowRepository = workflowRepository,
            taskManager = tasks,
            taskRepository = taskRepository,
            storyFoundationRepository = storyFoundationRepository,
            workflowService = workflowService,
            errorMapper = errorMapper,
        )

    /** P15-B · Android/Desktop 共用的 Foundation 决策 Application seam（极薄委托，不复刻业务规则）。 */
    val foundationDecisionGateway: FoundationDecisionGateway
        get() = FoundationDecisionFacade(foundationDecisions)

    /** P15-C · Idea Understanding Agent（复用 AgentRuntime/LLMGateway；无新 Provider/Runtime）。 */
    val ideaUnderstandingAgent: IdeaUnderstandingAgent
        get() = IdeaUnderstandingAgent(analysisGateway, errorMapper, analysisModel)

    /** P15-C · Idea-first 编排（rawIdea→StoryIntent→AI理解→FoundationProposal→既有 PENDING Gate）。 */
    val storyIntentUseCases: StoryIntentUseCases
        get() = StoryIntentUseCases(tasks, taskRepository, ideaUnderstandingAgent, foundationDecisions, errorMapper)

    /** P15-C · Android/Desktop 共用的 Idea-first Application seam（极薄委托）。 */
    val ideaFirstGateway: IdeaFirstGateway
        get() = IdeaFirstFacade(storyIntentUseCases)

    /** P16 AIL-1 · P15 信号 → AuthorEvidence 的**只读**来源（读取 P15 Checkpoint/Gate，不改 P15）。 */
    val p15FoundationEvidenceSource: P15FoundationEvidenceSource
        get() = P15FoundationEvidenceSource(workflowRepository, taskRepository)

    /** P16 AIL-1 · AuthorContext 最小只读投影（仅稳定且激活偏好；Planner/Writer 唯一 Author 入口）。P17 并入 Core Lite，P18-C 并入 DnaLite。 */
    val authorContextProjection: AuthorContextProjection
        get() = AuthorContextProjection(authorPreferenceRepository, authorCoreRepository, authorDnaRepository, errorMapper)

    /** P16 AIL-1 · Author Preference Use Cases（Explicit/Inferred、Confirmation Gate、User Control）。 */
    val authorPreferenceUseCases: AuthorPreferenceUseCases
        get() = AuthorPreferenceUseCases(authorPreferenceRepository, authorContextProjection, p15FoundationEvidenceSource, errorMapper)

    /** P16 AIL-1 · Android/Desktop 共用 Author Intelligence Application seam（极薄委托）。 */
    val authorIntelligenceGateway: AuthorIntelligenceGateway
        get() = AuthorIntelligenceFacade(authorPreferenceUseCases)

    /** P17 · Author Core Use Cases（长期创作决策倾向；聚合 / 确认 / lifecycle / User Control）。 */
    val authorCoreUseCases: AuthorCoreUseCases
        get() = AuthorCoreUseCases(authorCoreRepository, p15FoundationEvidenceSource, errorMapper)

    /** P17 · Android/Desktop 共用 Author Core Application seam（极薄委托；DEC-P17-012/014）。 */
    val authorCoreGateway: AuthorCoreGateway
        get() = AuthorCoreFacade(authorCoreUseCases)

    /** P18-A · Android/Desktop 共用的 Observation Collector（Application 层 Feedback Loop seam；DEC-P18-002）。 */
    val observationCollector: ObservationCollector
        get() = ObservationCollector(authorObservationRepository, authorCoreUseCases)

    /** P18-C · Author DNA Use Cases（TXT → Analysis → AuthorDNA；Full Rebuild / Confirm / Reject）。 */
    val authorDnaUseCases: AuthorDnaUseCases
        get() = AuthorDnaUseCases(authorDnaRepository, txtRepository, AnalysisInputBuilder, analysisGateway, errorMapper = errorMapper, model = analysisModel)

    /** P18-C · Android/Desktop 共用 Author DNA Application seam（极薄委托；DEC-P18C-014/015）。 */
    val authorDnaGateway: AuthorDnaGateway
        get() = AuthorDnaFacade(authorDnaUseCases)

    /** P19 · Decision Model Use Cases（确定性转译：AuthorContext → DecisionPolicy；stateless）。 */
    val decisionModelUseCases: DecisionModelUseCases
        get() = DecisionModelUseCases()

    /** P19 · Android/Desktop 共用 Decision Application seam（极薄委托；Orchestrator 不涉及）。 */
    val decisionModelGateway: DecisionModelGateway
        get() = DecisionModelFacade(decisionModelUseCases)

    /** P11.2 Planning 上下文组装（经确定性 Resolver，P11.6 接入世界上下文；P14-F.4 接入已确认 Story Foundation）。 */
    val planningContextAssembly: PlanningContextAssembly
        get() = PlanningContextAssembly(novelRepository, vocabularyRepository, storyWorldContextResolver, storyFoundationRepository, authorContextProjection, errorMapper)

    /** P12.1.3 Continuation 来源解析/校验：解析显式 [ContinuationReference] → source Chapter + source Final Draft。 */
    val continuationResolver: ContinuationResolver
        get() = ContinuationResolver(chapterRepository, draftRepository, errorMapper)

    /** P11.2 Planner Agent：经 AgentRuntime → LLMGateway，默认 Mock（模型经 seam 装配方注入）。 */
    val planner: PlannerAgent
        get() = PlannerAgent(analysisGateway, errorMapper, analysisModel)

    /** P11.2 Planning 执行 Use Case：Task 生命周期 + Checkpoint 保存 ChapterPlan（P0-4 绑定/创建真实 Chapter）。
     *  P20-P5：经 [decisionModelGateway] 在 Application orchestration 决定 DecisionPolicy（Planner 只消费）。 */
    val planning: PlanningExecutionUseCase
        get() = PlanningExecutionUseCase(tasks, planningContextAssembly, planner, chapterRepository, continuationResolver, errorMapper, decisionModelGateway)

    /** P11.3 Writer Agent：复用 AgentRuntime → LLMGateway，默认 Mock（模型经 seam 装配方注入）。 */
    val writer: WriterAgent
        get() = WriterAgent(analysisGateway, errorMapper, analysisModel)

    /** P20-P3 · Writer（章节正文编辑/保存）Use Case：只经 DraftRepository 读写正文，不 Decision。 */
    val writerUseCases: WriterUseCases
        get() = WriterUseCases(draftRepository, errorMapper)

    /** P20-P3 · Android Writer 用户层 seam（极薄编排；AI 继续写 → 既有 Workflow，AI 改写 → 既有 Critique/Revision）。 */
    val writerGateway: com.qianyan.application.usecase.writing.WriterGateway
        get() = WriterFacade(
            chapters = chapters,
            novelRepository = novelRepository,
            drafts = writerUseCases,
            workflow = workflowFacade,
            critique = critique,
            revision = revision,
            taskManager = tasks,
            errorMapper = errorMapper,
        )

    /** P11.3 Writing 执行 Use Case：Task 生命周期 + Draft 持久化 + WRITING Checkpoint。 */
    val writingExecution: WritingExecutionUseCase
        get() = WritingExecutionUseCase(tasks, planningContextAssembly, writer, draftRepository, errorMapper)

    /** P11.4 Critic Agent：复用 AgentRuntime → LLMGateway，默认 Mock（模型经 seam 装配方注入）。 */
    val critic: CritiqueAgent
        get() = CritiqueAgent(analysisGateway, errorMapper, analysisModel)

    /** P11.4 Critique 执行 Use Case：Task 校验 + CRITIQUE Checkpoint 承载 ValidationResult。 */
    val critique: CritiqueExecutionUseCase
        get() = CritiqueExecutionUseCase(tasks, critic, errorMapper)

    /** P11.4 Revision Agent：复用 AgentRuntime → LLMGateway + DraftParser，默认 Mock。 */
    val rewriter: RevisionAgent
        get() = RevisionAgent(analysisGateway, errorMapper, analysisModel)

    /** P11.4 Revision 执行 Use Case：RevisionGate + 修订 Draft 持久化 + REVISION Checkpoint。 */
    val revision: RevisionExecutionUseCase
        get() = RevisionExecutionUseCase(tasks, rewriter, draftRepository, errorMapper)

    /** P11.5 Knowledge Update Agent：复用 AgentRuntime → LLMGateway，默认 Mock。 */
    val knowledgeUpdater: KnowledgeUpdateAgent
        get() = KnowledgeUpdateAgent(analysisGateway, errorMapper, analysisModel)

    /** P11.5 Knowledge Update 执行 Use Case：确定性 validate+apply → Memory 沉淀 + KNOWN_UPDATE Checkpoint。
     *  P12.1.4：前置 HITL Confirmation Gate（source Draft 必须已 CONFIRMED）。 */
    val knowledgeUpdate: KnowledgeUpdateExecutionUseCase
        get() = KnowledgeUpdateExecutionUseCase(tasks, knowledgeUpdater, memoryRepository, draftRepository, errorMapper)

    /** P12.1.4 最小 HITL 确认闸门：confirm a Final Draft（FINAL/PENDING → CONFIRMED，幂等，作用域隔离）。 */
    val confirmations: ConfirmationExecutionUseCase
        get() = ConfirmationExecutionUseCase(draftRepository, errorMapper)

    val taskRunner: TaskRunner get() =
        TaskRunner(tasks, txts, planning, writingExecution, critique, revision, knowledgeUpdate, errorMapper)

    /** P11.1 写作 Use Case 骨架：真实创作属 P11.2+；postProcessDraft seam 本阶段即生效（默认直通）。 */
    val writing: WritingUseCases get() = WritingUseCases(errorMapper)

    companion object {

        /** I13 · 本地 Worker 标识（§8：最小 workerId；本地优先应用，单进程 Worker）。 */
        const val LOCAL_WORKER_ID: String = "local-worker"

        /** 由底层 [SqlDriver] 装配（测试 / 运行时注入数据库实现 + LLM 网关 + 分析模型）。 */
        fun fromDriver(
            driver: SqlDriver,
            analysisGateway: LLMGateway,
            analysisModel: ModelProfile = ModelProfile.MOCK,
            runtimeGateway: AgentRuntimeGateway? = null,
        ): ApplicationContainer {
            val db = QianyanDb(driver)
            return ApplicationContainer(
                novelRepository = SqliteNovelRepository(db),
                vocabularyRepository = SqliteVocabularyRepository(db),
                memoryRepository = SqliteMemoryRepository(db),
                backupStore = SqliteBackupStore(QianyanDbHandle(db, driver)),
                txtRepository = SqliteTxtRepository(db),
                taskRepository = SqliteTaskRepository(db),
                draftRepository = SqliteDraftRepository(db),
                chapterRepository = SqliteChapterRepository(db),
                storyStateRepository = SqliteStoryStateRepository(db),
                workflowRepository = SqliteWorkflowRepository(db),
                narrativeStateRepository = SqliteNarrativeStateRepository(db),
                storyFoundationRepository = SqliteStoryFoundationRepository(db),
                authorPreferenceRepository = SqliteAuthorPreferenceRepository(db),
                authorCoreRepository = SqliteAuthorCoreRepository(db),
                authorObservationRepository = SqliteAuthorObservationRepository(db),
                authorDnaRepository = SqliteAuthorDnaRepository(db),
                readingProgressRepository = SqliteReadingProgressRepository(db),
                projectStateRepository = SqliteProjectStateRepository(db),
                agentSessionRepository = SqliteAgentSessionRepository(db),
                activityRepository = SqliteActivityRepository(db),
                toolCallLogRepository = SqliteToolCallLogRepository(db),
                commitHistoryRepository = SqliteCommitHistoryRepository(db),
                taskQueueRepository = SqliteTaskQueueRepository(db),
                runtimeSessionRefRepository = SqliteRuntimeSessionRefRepository(db),
                analysisGateway = analysisGateway,
                analysisModel = analysisModel,
                runtimeGateway = runtimeGateway,
            )
        }

        /** 直接从 JDBC URL 打开数据库并装配（默认**每个调用独立的内存库**，保证测试/多容器互不串据；持久化测试传 `jdbc:sqlite:<path>`）。 */
        fun open(
            url: String = freshMemoryUrl(),
            analysisGateway: LLMGateway,
            analysisModel: ModelProfile = ModelProfile.MOCK,
        ): ApplicationContainer = fromDriver(QianyanDbFactory.open(url).driver, analysisGateway, analysisModel)

        // ---- P12.1.5：Provider Configuration 驱动的 DI ----
        // 配置 → ProviderAssembler → LLMGateway → Application。Application 只依赖 provider:api 抽象，
        // 不直接触碰 DeepSeek/MiMo client 细节；缺 credential 由 assembler 在配置期抛 ProviderCredentialMissing。

        /** 经 [ProviderAssembler] 从 [ProviderConfiguration] 组装 LLMGateway 后装配容器（同 in-memory 数据库）。 */
        fun open(
            url: String = freshMemoryUrl(),
            providerAssembler: ProviderAssembler,
            configuration: ProviderConfiguration,
        ): ApplicationContainer {
            val gateway = providerAssembler.assemble(configuration)
            return fromDriver(
                QianyanDbFactory.open(url).driver,
                analysisGateway = gateway,
                analysisModel = configuration.model ?: ModelProfile.MOCK,
            )
        }

        /** 经 [ProviderAssembler] 从 [ProviderConfiguration] 组装 LLMGateway 后装配容器（JDBC URL 驱动）。 */
        fun fromDriver(
            driver: SqlDriver,
            providerAssembler: ProviderAssembler,
            configuration: ProviderConfiguration,
            runtimeGateway: AgentRuntimeGateway? = null,
        ): ApplicationContainer {
            val gateway = providerAssembler.assemble(configuration)
            return fromDriver(
                driver,
                analysisGateway = gateway,
                analysisModel = configuration.model ?: ModelProfile.MOCK,
                runtimeGateway = runtimeGateway,
            )
        }

        /** 唯一私有内存库 JDBC URL（每次调用独立，避免同 JVM 内多容器共享 `:memory:` 而串数据）。 */
        private fun freshMemoryUrl(): String =
            "jdbc:sqlite:file:qianyan-${java.util.UUID.randomUUID()}?mode=memory&cache=private"
    }
}