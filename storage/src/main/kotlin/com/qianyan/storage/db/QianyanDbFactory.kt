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
        try {
            DatabaseInitializer.initializeDatabase(driver)
        } catch (t: Throwable) {
            // P1-02h：初始化失败必须释放驱动/连接 —— 否则泄漏文件句柄（Windows 下该 .db 永久无法删除），
            // 且会让调用方 finally 中的删除异常**掩盖**真实迁移错误。
            // 实测（storage 测试探针 A/D 对比 H）：不释放 ⇒ 删除永久失败；显式释放 ⇒ 删除成功。
            runCatching { driver.getConnection().close() }
            runCatching { driver.close() }
            throw t
        }
        return QianyanDbHandle(QianyanDb(driver), driver)
    }
}

/** 数据库句柄：QianyanDb（查询/事务） + 底层 SqlDriver（原生 SQL）。 */
class QianyanDbHandle(
    val db: QianyanDb,
    val driver: app.cash.sqldelight.db.SqlDriver,
)
