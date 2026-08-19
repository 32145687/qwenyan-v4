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
import com.qianyan.engine.analysis.AnalysisInputBuilder
import com.qianyan.engine.txt.TxtPipeline
import com.qianyan.provider.LLMGateway
import com.qianyan.storage.db.QianyanDb
import com.qianyan.storage.db.QianyanDbFactory
import com.qianyan.storage.db.QianyanDbHandle
import com.qianyan.storage.repository.BackupStore
import com.qianyan.storage.repository.MemoryRepository
import com.qianyan.storage.repository.NovelRepository
import com.qianyan.storage.repository.SqliteBackupStore
import com.qianyan.storage.repository.SqliteMemoryRepository
import com.qianyan.storage.repository.SqliteNovelRepository
import com.qianyan.storage.repository.SqliteTaskRepository
import com.qianyan.storage.repository.SqliteTxtRepository
import com.qianyan.storage.repository.SqliteVocabularyRepository
import com.qianyan.storage.repository.TaskRepository
import com.qianyan.storage.repository.TxtRepository
import com.qianyan.storage.repository.VocabularyRepository
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver

/**
 * Application 层组合根（手动 DI，P3.1）。
 *
 * 职责：把 [NovelRepository]、[VocabularyRepository]、[MemoryRepository]、[BackupStore]、
 * [TxtRepository]、[TaskRepository] 六个仓储装配进来，并暴露各 Use Case 组。外部（app / runtime / agent 调用方）
 * 只通过本容器访问 Application 能力，不直接触碰 Sqlite 实现。
 *
 * P5 演进：新增 [TxtPipeline]（core:engine 确定性解析）与 [TxtUseCases]。
 * P6 演进：Analysis 在此执行层跑通，容器注入 [LLMGateway]（Provider 契约，实现由装配方注入 ——
 * 测试/装配方提供 :provider:impl 的 Mock）。新增 [AnalysisUseCases]。装配链：
 * ApplicationContainer → AnalysisInputBuilder → LLMGateway(:provider:api) → TxtRepository → VocabularyRepository → AnalysisUseCases。
 * Analysis Use Case 只依赖 Provider 契约接口，不绑定具体实现；仓储实现始终在 `:storage`。
 * P8.2 演进：新增 [TaskRepository]（P8.1 持久化）与 [TaskManagerUseCases]（Task 状态机 / Checkpoint 管理）。
 * P8.3 演进：新增 [TaskRunner]（Task 执行驱动：受管执行 IMPORT，复用 TaskManager 生命周期）。
 */
class ApplicationContainer(
    val novelRepository: NovelRepository,
    val vocabularyRepository: VocabularyRepository,
    val memoryRepository: MemoryRepository,
    val backupStore: BackupStore,
    val txtRepository: TxtRepository,
    val taskRepository: TaskRepository,
    private val analysisGateway: LLMGateway,
    private val txtPipeline: TxtPipeline = TxtPipeline(),
) {

    val errorMapper: ErrorMapper = ErrorMapper

    val novels: NovelUseCases get() = NovelUseCases(novelRepository, errorMapper)
    val overrides: OverrideUseCases get() = OverrideUseCases(novelRepository, errorMapper)
    val vocabularies: VocabularyUseCases get() = VocabularyUseCases(vocabularyRepository, errorMapper)
    val memories: MemoryUseCases get() = MemoryUseCases(memoryRepository, errorMapper)
    val txts: TxtUseCases get() = TxtUseCases(txtPipeline, txtRepository, novelRepository, errorMapper)
    val analysis: AnalysisUseCases get() = AnalysisUseCases(txtRepository, vocabularyRepository, AnalysisInputBuilder, analysisGateway, errorMapper)
    val tasks: TaskManagerUseCases get() = TaskManagerUseCases(taskRepository, errorMapper)
    val taskRunner: TaskRunner get() = TaskRunner(tasks, txts, errorMapper)

    companion object {

        /** 由底层 [SqlDriver] 装配（测试 / 运行时注入数据库实现 + LLM 网关）。 */
        fun fromDriver(driver: SqlDriver, analysisGateway: LLMGateway): ApplicationContainer {
            val db = QianyanDb(driver)
            return ApplicationContainer(
                novelRepository = SqliteNovelRepository(db),
                vocabularyRepository = SqliteVocabularyRepository(db),
                memoryRepository = SqliteMemoryRepository(db),
                backupStore = SqliteBackupStore(QianyanDbHandle(db, driver)),
                txtRepository = SqliteTxtRepository(db),
                taskRepository = SqliteTaskRepository(db),
                analysisGateway = analysisGateway,
            )
        }

        /** 直接从 JDBC URL 打开数据库并装配（默认内存库；持久化测试传 `jdbc:sqlite:<path>`）。 */
        fun open(url: String = JdbcSqliteDriver.IN_MEMORY, analysisGateway: LLMGateway): ApplicationContainer =
            fromDriver(QianyanDbFactory.open(url).driver, analysisGateway)
    }
}