package com.qianyan.application.di

import app.cash.sqldelight.db.SqlDriver
import com.qianyan.application.error.ErrorMapper
import com.qianyan.application.usecase.memory.MemoryUseCases
import com.qianyan.application.usecase.novel.NovelUseCases
import com.qianyan.application.usecase.override.OverrideUseCases
import com.qianyan.application.usecase.vocabulary.VocabularyUseCases
import com.qianyan.storage.db.QianyanDb
import com.qianyan.storage.db.QianyanDbFactory
import com.qianyan.storage.db.QianyanDbHandle
import com.qianyan.storage.repository.BackupStore
import com.qianyan.storage.repository.MemoryRepository
import com.qianyan.storage.repository.NovelRepository
import com.qianyan.storage.repository.SqliteBackupStore
import com.qianyan.storage.repository.SqliteMemoryRepository
import com.qianyan.storage.repository.SqliteNovelRepository
import com.qianyan.storage.repository.SqliteTxtRepository
import com.qianyan.storage.repository.SqliteVocabularyRepository
import com.qianyan.storage.repository.TxtRepository
import com.qianyan.storage.repository.VocabularyRepository
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver

/**
 * Application 层组合根（手动 DI，P3.1）。
 *
 * 职责：把 [NovelRepository]、[VocabularyRepository]、[MemoryRepository]、[BackupStore]、
 * [TxtRepository] 五个仓储装配进来，并暴露各 Use Case 组。外部（app / runtime / agent 调用方）
 * 只通过本容器访问 Application 能力，不直接触碰 Sqlite 实现。
 *
 * 仓储实现始终位于 `:storage`；本模块只做装配，不包含任何 Repository 实现。
 */
class ApplicationContainer(
    val novelRepository: NovelRepository,
    val vocabularyRepository: VocabularyRepository,
    val memoryRepository: MemoryRepository,
    val backupStore: BackupStore,
    val txtRepository: TxtRepository,
) {

    val errorMapper: ErrorMapper = ErrorMapper

    val novels: NovelUseCases get() = NovelUseCases(novelRepository, errorMapper)
    val overrides: OverrideUseCases get() = OverrideUseCases(novelRepository, errorMapper)
    val vocabularies: VocabularyUseCases get() = VocabularyUseCases(vocabularyRepository, errorMapper)
    val memories: MemoryUseCases get() = MemoryUseCases(memoryRepository, errorMapper)

    companion object {

        /** 由底层 [SqlDriver] 装配（测试 / 运行时注入数据库实现）。 */
        fun fromDriver(driver: SqlDriver): ApplicationContainer {
            val db = QianyanDb(driver)
            return ApplicationContainer(
                novelRepository = SqliteNovelRepository(db),
                vocabularyRepository = SqliteVocabularyRepository(db),
                memoryRepository = SqliteMemoryRepository(db),
                backupStore = SqliteBackupStore(QianyanDbHandle(db, driver)),
                txtRepository = SqliteTxtRepository(db),
            )
        }

        /** 直接从 JDBC URL 打开数据库并装配（默认内存库；持久化测试传 `jdbc:sqlite:<path>`）。 */
        fun open(url: String = JdbcSqliteDriver.IN_MEMORY): ApplicationContainer =
            fromDriver(QianyanDbFactory.open(url).driver)
    }
}