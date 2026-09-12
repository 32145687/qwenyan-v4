package com.qianyan.application.di

import app.cash.sqldelight.db.SqlDriver
import com.qianyan.application.error.ErrorMapper
import com.qianyan.application.usecase.analysis.AnalysisUseCases
import com.qianyan.application.usecase.memory.MemoryUseCases
import com.qianyan.application.usecase.novel.NovelUseCases
import com.qianyan.application.usecase.override.OverrideUseCases
import com.qianyan.application.usecase.txt.TxtUseCases
import com.qianyan.application.usecase.task.TaskManagerUseCases
import com.qianyan.application.usecase.task.TaskRunner
import com.qianyan.application.usecase.vocabulary.VocabularyUseCases
import com.qianyan.application.usecase.chapter.ChapterUseCases
import com.qianyan.application.usecase.chapter.ChapterWritingUseCases
import com.qianyan.application.usecase.workflow.ChapterWorkflowFacade
import com.qianyan.application.usecase.workflow.WorkflowOrchestrator
import com.qianyan.application.usecase.workflow.WorkflowService
import com.qianyan.application.usecase.writing.WritingUseCases
import com.qianyan.application.usecase.writing.WritingExecutionUseCase
import com.qianyan.application.usecase.writing.WriterAgent
import com.qianyan.application.usecase.writing.critique.CritiqueAgent
import com.qianyan.application.usecase.writing.critique.CritiqueExecutionUseCase
import com.qianyan.application.usecase.writing.knowledgeupdate.KnowledgeUpdateAgent
import com.qianyan.application.usecase.writing.knowledgeupdate.KnowledgeUpdateExecutionUseCase
import com.qianyan.application.usecase.story.StoryStateVariantUseCases
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
import com.qianyan.engine.analysis.AnalysisInputBuilder
import com.qianyan.engine.txt.TxtPipeline
import com.qianyan.provider.LLMGateway
import com.qianyan.provider.ModelProfile
import com.qianyan.provider.ProviderAssembler
import com.qianyan.provider.ProviderConfiguration
import com.qianyan.storage.db.QianyanDb
import com.qianyan.storage.db.QianyanDbFactory
import com.qianyan.storage.db.QianyanDbHandle
import com.qianyan.storage.repository.BackupStore
import com.qianyan.storage.repository.ChapterRepository
import com.qianyan.storage.repository.DraftRepository
import com.qianyan.storage.repository.MemoryRepository
import com.qianyan.storage.repository.NarrativeStateRepository
import com.qianyan.storage.repository.NovelRepository
import com.qianyan.storage.repository.SqliteBackupStore
import com.qianyan.storage.repository.SqliteChapterRepository
import com.qianyan.storage.repository.SqliteDraftRepository
import com.qianyan.storage.repository.SqliteMemoryRepository
import com.qianyan.storage.repository.SqliteNarrativeStateRepository
import com.qianyan.storage.repository.SqliteNovelRepository
import com.qianyan.storage.repository.SqliteTaskRepository
import com.qianyan.storage.repository.SqliteTxtRepository
import com.qianyan.storage.repository.SqliteStoryStateRepository
import com.qianyan.storage.repository.SqliteVocabularyRepository
import com.qianyan.storage.repository.SqliteWorkflowRepository
import com.qianyan.storage.repository.StoryStateRepository
import com.qianyan.storage.repository.TaskRepository
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
    private val analysisGateway: LLMGateway,
    private val analysisModel: ModelProfile = ModelProfile.MOCK,
    private val txtPipeline: TxtPipeline = TxtPipeline(),
) {

    val errorMapper: ErrorMapper = ErrorMapper

    val novels: NovelUseCases get() = NovelUseCases(novelRepository, errorMapper)
    val overrides: OverrideUseCases get() = OverrideUseCases(novelRepository, errorMapper)
    val vocabularies: VocabularyUseCases get() = VocabularyUseCases(vocabularyRepository, errorMapper)
    val memories: MemoryUseCases get() = MemoryUseCases(memoryRepository, errorMapper)
    val txts: TxtUseCases get() = TxtUseCases(txtPipeline, txtRepository, novelRepository, errorMapper)
    val analysis: AnalysisUseCases get() = AnalysisUseCases(txtRepository, vocabularyRepository, AnalysisInputBuilder, analysisGateway, errorMapper, model = analysisModel)
    val tasks: TaskManagerUseCases get() = TaskManagerUseCases(taskRepository, errorMapper)

    /** P12.1.6 Chapter 读取/创建 Use Case：UI 只经此访问真实章节（禁止直触 ChapterRepository）。 */
    val chapters: ChapterUseCases get() = ChapterUseCases(chapterRepository, novelRepository, errorMapper)

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

    /** P13 LCL-A Narrative State 叙事账本（append / project / get；Original 只读；无 LLM）。 */
    val narrativeState: NarrativeStateUseCases
        get() = NarrativeStateUseCases(narrativeStateRepository, errorMapper)

    /** P13 LCL-B ChapterContextPack 确定性窗口投影（只读编译；无 LLM；不写状态）。 */
    val chapterContextPack: ChapterContextCompileUseCases
        get() = ChapterContextCompileUseCases(storyWorldContextResolver, narrativeState, chapterRepository, errorMapper)

    /** P11.2 Planning 上下文组装（经确定性 Resolver，P11.6 接入世界上下文）。 */
    val planningContextAssembly: PlanningContextAssembly
        get() = PlanningContextAssembly(novelRepository, vocabularyRepository, storyWorldContextResolver, errorMapper)

    /** P12.1.3 Continuation 来源解析/校验：解析显式 [ContinuationReference] → source Chapter + source Final Draft。 */
    val continuationResolver: ContinuationResolver
        get() = ContinuationResolver(chapterRepository, draftRepository, errorMapper)

    /** P11.2 Planner Agent：经 AgentRuntime → LLMGateway，默认 Mock（模型经 seam 装配方注入）。 */
    val planner: PlannerAgent
        get() = PlannerAgent(analysisGateway, errorMapper, analysisModel)

    /** P11.2 Planning 执行 Use Case：Task 生命周期 + Checkpoint 保存 ChapterPlan（P0-4 绑定/创建真实 Chapter）。 */
    val planning: PlanningExecutionUseCase
        get() = PlanningExecutionUseCase(tasks, planningContextAssembly, planner, chapterRepository, continuationResolver, errorMapper)

    /** P11.3 Writer Agent：复用 AgentRuntime → LLMGateway，默认 Mock（模型经 seam 装配方注入）。 */
    val writer: WriterAgent
        get() = WriterAgent(analysisGateway, errorMapper, analysisModel)

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

        /** 由底层 [SqlDriver] 装配（测试 / 运行时注入数据库实现 + LLM 网关 + 分析模型）。 */
        fun fromDriver(
            driver: SqlDriver,
            analysisGateway: LLMGateway,
            analysisModel: ModelProfile = ModelProfile.MOCK,
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
                analysisGateway = analysisGateway,
                analysisModel = analysisModel,
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
        ): ApplicationContainer {
            val gateway = providerAssembler.assemble(configuration)
            return fromDriver(
                driver,
                analysisGateway = gateway,
                analysisModel = configuration.model ?: ModelProfile.MOCK,
            )
        }

        /** 唯一私有内存库 JDBC URL（每次调用独立，避免同 JVM 内多容器共享 `:memory:` 而串数据）。 */
        private fun freshMemoryUrl(): String =
            "jdbc:sqlite:file:qianyan-${java.util.UUID.randomUUID()}?mode=memory&cache=private"
    }
}