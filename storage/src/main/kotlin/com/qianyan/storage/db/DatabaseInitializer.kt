package com.qianyan.storage.db

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver

/**
 * 驱动无关的数据库初始化（P7.0）。
 *
 * 把"建表 + 守卫触发器"从 JVM 专用 [QianyanDbFactory] 中抽离为独立入口，
 * 使 JVM（JdbcSqliteDriver）与 Android（AndroidSqliteDriver）共用同一初始化逻辑，
 * 而无需把任何模块迁移为 Kotlin Multiplatform。
 *
 * 幂等语义：
 *  - SQLDelight 生成的 [QianyanDb.Schema.create] 是**裸 CREATE TABLE**（不带 IF NOT EXISTS），
 *    重复执行会报"表已存在"，故先经 `sqlite_master` 判断是否已初始化，仅在首次调用时建表；
 *  - 守卫触发器一律使用 `CREATE TRIGGER IF NOT EXISTS`，天然幂等。
 * `sqlite_master` 是 SQLite 标准系统目录，JVM 与 Android 的 SQLite 实现均支持。
 */
object DatabaseInitializer {

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
     * 初始化数据库：首次调用建表 + 建守卫触发器；后续调用幂等跳过。
     *
     * @param driver 任意 SQLDelight [SqlDriver]（JVM 或 Android），不绑定 JDBC。
     */
    fun initializeDatabase(driver: SqlDriver) {
        if (!tablesExist(driver)) {
            QianyanDb.Schema.create(driver)
            GUARD_DDL.forEach { driver.execute(null, it, 0) }
        }
    }

    private fun tablesExist(driver: SqlDriver): Boolean =
        driver.executeQuery(
            null,
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name='Novel' LIMIT 1",
            { cursor -> QueryResult.Value(cursor.next().value) },
            0,
        ).value
}
