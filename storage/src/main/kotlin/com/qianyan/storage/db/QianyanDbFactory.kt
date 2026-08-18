package com.qianyan.storage.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver

/**
 * Qianyan SQLite 数据库（JVM）入口。
 *
 * 打开流程：
 * 1. 建 JdbcSqliteDriver；
 * 2. 委托 [DatabaseInitializer.initializeDatabase] 完成建表与守卫触发器（P7.0 抽离，驱动无关）。
 *
 * 守卫触发器落实 P2.4 物理写保护：
 *  - Original（scope=ORIGINAL）行禁止 UPDATE/DELETE；
 *  - NovelVariant 的 base 仅允许是 scope=ORIGINAL 的 Novel（禁止 Variant→Variant）。
 *
 * @param url JDBC URL；默认内存库；持久化测试传 `jdbc:sqlite:<path>`。
 */
object QianyanDbFactory {

    /**
     * 打开一个数据库实例。
     *
     * @param url JDBC URL，默认 `JdbcSqliteDriver.IN_MEMORY`。
     * @return 持有 QianyanDb 与底层 SqlDriver 的句柄（供事务、原始 SQL 测试使用）。
     */
    fun open(url: String = JdbcSqliteDriver.IN_MEMORY): QianyanDbHandle {
        val driver = JdbcSqliteDriver(url)
        DatabaseInitializer.initializeDatabase(driver)
        return QianyanDbHandle(QianyanDb(driver), driver)
    }
}

/** 数据库句柄：QianyanDb（查询/事务） + 底层 SqlDriver（原生 SQL）。 */
class QianyanDbHandle(
    val db: QianyanDb,
    val driver: app.cash.sqldelight.db.SqlDriver,
)
