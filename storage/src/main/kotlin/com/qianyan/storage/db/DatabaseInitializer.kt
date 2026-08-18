package com.qianyan.storage.db

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver

/**
 * 驱动无关的数据库初始化（P7.0；P8.1 扩展 v1→v2 迁移）。
 *
 * 把"建表 + 守卫触发器 + 版本迁移"从 JVM 专用 [QianyanDbFactory] 中抽离为独立入口，
 * 使 JVM（JdbcSqliteDriver）与 Android（AndroidSqliteDriver）共用同一初始化逻辑，
 * 而无需把任何模块迁移为 Kotlin Multiplatform。
 *
 * 幂等语义：
 *  - SQLDelight 生成的 [QianyanDb.Schema.create] / [QianyanDb.Schema.migrate] 是**裸 DDL**
 *    （不带 IF NOT EXISTS），重复执行会报"表已存在"，故先经 `sqlite_master` 判断当前状态：
 *    全新库（无 Novel）→ `Schema.create` 建出完整 v2 schema（含 Task / Checkpoint）；
 *    旧 v1 库（有 Novel 无 Task）→ `Schema.migrate(1, 2)` 应用 `1.sqm` 迁移（仅新增
 *    Task / Checkpoint，不删除/修改既有业务表，旧数据原样保留）；
 *    已 v2 → 跳过建表/迁移。
 *  - 每次建表/迁移都在单事务内执行并同步 `PRAGMA user_version`（与 AndroidSqliteDriver
 *    由 SQLiteOpenHelper 管理版本一致），保证原子性 + 版本簿记正确（供后续 P8.x 迁移）。
 *  - 守卫触发器一律使用 `CREATE TRIGGER IF NOT EXISTS`，天然幂等，每次初始化都执行，
 *    保证 JVM 与 Android 两平台守卫一致性。
 * `sqlite_master` 是 SQLite 标准系统目录，JVM 与 Android 的 SQLite 实现均支持。
 */
object DatabaseInitializer {

    /** v1 起始版本（迁移调用起点）。 */
    private const val V1 = 1L

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
     * 初始化数据库（幂等）：
     *  - 全新库：事务内 `Schema.create` 建出当前 schema（含 v2 的 Task / Checkpoint）并记录版本；
     *  - 旧 v1 库（有 Novel 无 Task）：事务内 `Schema.migrate` 应用 1.sqm 迁移，旧数据原样保留，
     *    并同步版本；
     *  - 已 v2：仅幂等确保守卫 DDL 存在。
     * 任意路径后均执行守卫 DDL（IF NOT EXISTS，安全幂等）。
     *
     * @param driver 任意 SQLDelight [SqlDriver]（JVM 或 Android），不绑定 JDBC。
     */
    fun initializeDatabase(driver: SqlDriver) {
        when {
            !tableExists(driver, "Novel") -> withTransaction(driver) {
                QianyanDb.Schema.create(driver)
                setVersion(driver, QianyanDb.Schema.version)
            }

            !tableExists(driver, "Task") -> withTransaction(driver) {
                // v1 → v2：仅新增 Task / Checkpoint；不删除/修改既有业务表，旧数据原样保留。
                QianyanDb.Schema.migrate(driver, V1, QianyanDb.Schema.version)
                setVersion(driver, QianyanDb.Schema.version)
            }
        }
        GUARD_DDL.forEach { driver.execute(null, it, 0) }
    }

    /** 单事务执行建表/迁移 + 版本簿记，任一步失败整体回滚，不留半成品 schema。 */
    private fun withTransaction(driver: SqlDriver, block: () -> Unit) {
        // 复用 SQLDelight Transacter 的事务（BEGIN/COMMIT/ROLLBACK），驱动无关（JVM / Android）。
        QianyanDb(driver).transaction {
            block()
        }
    }

    private fun tableExists(driver: SqlDriver, table: String): Boolean =
        driver.executeQuery(
            null,
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name='$table' LIMIT 1",
            { cursor -> QueryResult.Value(cursor.next().value) },
            0,
        ).value

    /** 同步 schema 版本（与 AndroidSqliteDriver / SQLiteOpenHelper 的版本簿记语义一致）。 */
    private fun setVersion(driver: SqlDriver, version: Long) {
        driver.execute(null, "PRAGMA user_version = $version", 0)
    }
}
