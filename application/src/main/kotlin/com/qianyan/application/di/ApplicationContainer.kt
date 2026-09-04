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
import com.qianyan.application.usecase.writing.WritingUseCases
import com.qianyan.application.usecase.writing.WritingExecutionUseCase
import com.qianyan.application.usecase.writing.WriterAgent
import com.qianyan.application.usecase.writing.critique.CritiqueAgent
import com.qianyan.application.usecase.writing.critique.CritiqueExecutionUseCase
import com.qianyan.application.usecase.writing.knowledgeupdate.KnowledgeUpdateAgent
import com.qianyan.application.usecase.writing.knowledgeupdate.KnowledgeUpdateExecutionUseCase
import com.qianyan.application.usecase.writing.context.StoryWorldContextResolver
import com.qianyan.application.usecase.writing.planning.PlanningContextAssembly
import com.qianyan.application.usecase.writing.planning.PlanningExecutionUseCase
import com.qianyan.application.usecase.writing.planning.PlannerAgent
import com.qianyan.application.usecase.writing.revision.RevisionAgent
import com.qianyan.application.usecase.writing.revision.RevisionExecutionUseCase
import com.qianyan.engine.analysis.AnalysisInputBuilder
import com.qianyan.engine.txt.TxtPipeline
import com.qianyan.provider.LLMGateway
import com.qianyan.provider.ModelProfile
import com.qianyan.storage.db.QianyanDb
import com.qianyan.storage.db.QianyanDbFactory
import com.qianyan.storage.db.QianyanDbHandle
import com.qianyan.storage.repository.BackupStore
import com.qianyan.storage.repository.ChapterRepository
import com.qianyan.storage.repository.DraftRepository
import com.qianyan.storage.repository.MemoryRepository
import com.qianyan.storage.repository.NovelRepository
import com.qianyan.storage.repository.SqliteBackupStore
import com.qianyan.storage.repository.SqliteChapterRepository
import com.qianyan.storage.repository.SqliteDraftRepository
import com.qianyan.storage.repository.SqliteMemoryRepository
import com.qianyan.storage.repository.SqliteNovelRepository
import com.qianyan.storage.repository.SqliteTaskRepository
import com.qianyan.storage.repository.SqliteTxtRepository
import com.qianyan.storage.repository.SqliteStoryStateRepository
import com.qianyan.storage.repository.SqliteVocabularyRepository
import com.qianyan.storage.repository.StoryStateRepository
import com.qianyan.storage.repository.TaskRepository
import com.qianyan.storage.repository.TxtRepository
import com.qianyan.storage.repository.VocabularyRepository
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver

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

    /** P11.2/P11.6/P12.1.2 确定性 Story World Context 解析器（分层 + canon 优先 + 结构化 Story State）。 */
    val storyWorldContextResolver: StoryWorldContextResolver
        get() = StoryWorldContextResolver(memoryRepository, errorMapper, storyStateRepository)

    /** P12.1.1/P12.1.2 结构化 Story State 仓储（Character/WorldRule/Event/Timeline/Foreshadow），供测试与上层读取。 */
    val storyState: StoryStateRepository get() = storyStateRepository

    /** P11.2 Planning 上下文组装（经确定性 Resolver，P11.6 接入世界上下文）。 */
    val planningContextAssembly: PlanningContextAssembly
        get() = PlanningContextAssembly(novelRepository, vocabularyRepository, storyWorldContextResolver, errorMapper)

    /** P11.2 Planner Agent：经 AgentRuntime → LLMGateway，默认 Mock（模型经 seam 装配方注入）。 */
    val planner: PlannerAgent
        get() = PlannerAgent(analysisGateway, errorMapper, analysisModel)

    /** P11.2 Planning 执行 Use Case：Task 生命周期 + Checkpoint 保存 ChapterPlan（P0-4 绑定/创建真实 Chapter）。 */
    val planning: PlanningExecutionUseCase
        get() = PlanningExecutionUseCase(tasks, planningContextAssembly, planner, chapterRepository, errorMapper)

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

    /** P11.5 Knowledge Update 执行 Use Case：确定性 validate+apply → Memory 沉淀 + KNOWN_UPDATE Checkpoint。 */
    val knowledgeUpdate: KnowledgeUpdateExecutionUseCase
        get() = KnowledgeUpdateExecutionUseCase(tasks, knowledgeUpdater, memoryRepository, errorMapper)

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
                analysisGateway = analysisGateway,
                analysisModel = analysisModel,
            )
        }

        /** 直接从 JDBC URL 打开数据库并装配（默认内存库；持久化测试传 `jdbc:sqlite:<path>`）。 */
        fun open(
            url: String = JdbcSqliteDriver.IN_MEMORY,
            analysisGateway: LLMGateway,
            analysisModel: ModelProfile = ModelProfile.MOCK,
        ): ApplicationContainer = fromDriver(QianyanDbFactory.open(url).driver, analysisGateway, analysisModel)
    }
}