package com.qianyan.storage

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.qianyan.storage.db.DatabaseInitializer
import com.qianyan.storage.db.QianyanDb
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * P7.0 DatabaseInitializer 测试（storage）：
 * 驱动无关初始化入口的幂等性与守卫触发器创建。
 * 初始化逻辑与 JVM 专用 QianyanDbFactory 解耦后，Android（AndroidSqliteDriver）将复用同一入口。
 */
class DatabaseInitializerTest {

    @Test
    fun `initializeDatabase is idempotent and produces usable schema`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)

        DatabaseInitializer.initializeDatabase(driver)
        DatabaseInitializer.initializeDatabase(driver) // 幂等：重复调用不抛"表已存在"

        val db = QianyanDb(driver)
        assertTrue(db.novelQueries.getAllNovels().executeAsList().isEmpty(), "建表后 Novel 查询应可用且为空")
    }

    @Test
    fun `initializeDatabase creates the three guard triggers`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        DatabaseInitializer.initializeDatabase(driver)

        val triggerCount = driver.executeQuery(
            null,
            "SELECT count(*) FROM sqlite_master WHERE type='trigger'",
            { cursor ->
                cursor.next()
                QueryResult.Value(cursor.getLong(0) ?: 0L)
            },
            0,
        ).value

        assertEquals(3L, triggerCount, "应创建 2 个 Original 写保护触发器 + 1 个 Variant 基座守卫触发器")
    }
}
