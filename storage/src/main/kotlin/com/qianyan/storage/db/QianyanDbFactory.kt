package com.qianyan.storage.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.qianyan.storage.db.QianyanDb

/**
 * Qianyan SQLite 数据库（JVM）入口。
 *
 * 打开流程：
 * 1. 建 JdbcSqliteDriver；首次打开时由 SQLDelight `QianyanDb.Schema` 建表（initial migration，版本 1）。
 * 2. 通过原生 SQL 建立守卫触发器，落实 P2.4 物理写保护（SQLDelight 不解析触发器体，故在 schema 外创建）：
 *    - Original（scope=ORIGINAL）行禁止 UPDATE/DELETE；
 *    - NovelVariant 的 base 仅允许是 scope=ORIGINAL 的 Novel（禁止 Variant→Variant）。
 *
 * @param url JDBC URL；默认内存库；持久化测试传 `jdbc:sqlite:<path>`。
 */
object QianyanDbFactory {

    /** Schema 建好后仍需追加执行的守卫 DDL（每项一个完整语句）。 */
    private val GUARD_DDL: List<String> = listOf(
        """
        CREATE TRIGGER IF NOT EXISTS novel_original_update_protect
        BEFORE UPDATE ON Novel
        WHEN OLD.scope = 'ORIGINAL'
        BEGIN
            SELECT RAISE(ABORT, 'Original Novel is immutable');
        END;
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS novel_original_delete_protect
        BEFORE DELETE ON Novel
        WHEN OLD.scope = 'ORIGINAL'
        BEGIN
            SELECT RAISE(ABORT, 'Original Novel is immutable');
        END;
        """.trimIndent(),
        // 禁止 "Variant → Variant"：base 必须以 scope=ORIGINAL 的 Novel 为基座。
        // base 缺失时子查询返回 NULL，`NULL IS NOT 'ORIGINAL'` 亦为真，同样被拦截。
        """
        CREATE TRIGGER IF NOT EXISTS variant_base_must_be_original
        BEFORE INSERT ON NovelVariant
        WHEN (SELECT scope FROM Novel WHERE novel_id = NEW.base_novel_id) IS NOT 'ORIGINAL'
        BEGIN
            SELECT RAISE(ABORT, 'Variant base must be an Original Novel');
        END;
        """.trimIndent(),
    )

    /**
     * 打开一个数据库实例。
     *
     * @param url JDBC URL，默认 `JdbcSqliteDriver.IN_MEMORY`。
     * @return 持有 QianyanDb 与底层 SqlDriver 的句柄（供事务、原始 SQL 测试使用）。
     */
    fun open(url: String = JdbcSqliteDriver.IN_MEMORY): QianyanDbHandle {
        val driver = JdbcSqliteDriver(url)
        // 幂等初始化：仅首次打开（表不存在）时建表 + 建触发器。
        // 重开已有文件库时直接复用现有表与触发器，避免裸 CREATE TABLE 报“表已存在”（P2.9 可重复打开）。
        val conn = driver.getConnection()
        val tablesExist = runCatching {
            conn.createStatement().use { st ->
                st.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='Novel'").use { rs -> rs.next() }
            }
        }.getOrDefault(false)
        if (!tablesExist) {
            QianyanDb.Schema.create(driver)
            GUARD_DDL.forEach { driver.execute(null, it, 0) }
        }
        return QianyanDbHandle(QianyanDb(driver), driver)
    }
}

/** 数据库句柄：QianyanDb（查询/事务） + 底层 SqlDriver（原生 SQL）。 */
class QianyanDbHandle(
    val db: QianyanDb,
    val driver: app.cash.sqldelight.db.SqlDriver,
)