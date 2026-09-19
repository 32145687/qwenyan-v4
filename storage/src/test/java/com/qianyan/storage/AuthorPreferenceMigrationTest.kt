package com.qianyan.storage

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.qianyan.storage.db.DatabaseInitializer
import com.qianyan.storage.db.QianyanDbFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * P16 AIL-1 · v10 → v11 migration 测试（Author Intelligence 独立存储）。
 * 构建真实 v10 schema（上游全量表 + StoryFoundation / FoundationOverride；**无 Author 三表**；user_version=10），
 * 写入代表性 v10 数据（Novel / StoryFoundation），初始化后验证：
 *   1) AuthorProfile / AuthorPreference / AuthorEvidence 三表已创建；
 *   2) `PRAGMA user_version` 同步为 11；
 *   3) 既有 v10 数据（Novel / StoryFoundation）仍可读取，迁移未触碰既有表；
 *   4) 重复执行初始化幂等安全。
 */
class AuthorPreferenceMigrationTest {

    /** 真实 v10 schema：v9 全量表 + StoryFoundation + FoundationOverride（P14-F.2）。 */
    private val V10_MINIMAL_DDL: List<String> = v9MinimalDdl() + listOf(
        """CREATE TABLE StoryFoundation (novel_id TEXT NOT NULL PRIMARY KEY, base_novel_id TEXT NOT NULL, scope TEXT NOT NULL, version INTEGER NOT NULL DEFAULT 0, genre TEXT NOT NULL DEFAULT '[]', direction TEXT NOT NULL DEFAULT '{}', audience TEXT NOT NULL DEFAULT '{}', policy TEXT NOT NULL DEFAULT '{}', created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, FOREIGN KEY (novel_id) REFERENCES Novel(novel_id))""",
        """CREATE TABLE FoundationOverride (variant_id TEXT NOT NULL PRIMARY KEY, genre TEXT, direction TEXT, audience TEXT, policy TEXT, updated_at INTEGER NOT NULL, FOREIGN KEY (variant_id) REFERENCES NovelVariant(variant_id))""",
    )

    private fun JdbcSqliteDriver.exec(sql: String) { execute(null, sql, 0) }

    private fun tableExists(driver: JdbcSqliteDriver, table: String): Boolean =
        driver.executeQuery(null, "SELECT 1 FROM sqlite_master WHERE type='table' AND name='$table' LIMIT 1",
            { cursor -> QueryResult.Value(cursor.next().value) }, 0).value

    private fun userVersion(driver: JdbcSqliteDriver): Long =
        driver.executeQuery(null, "PRAGMA user_version",
            { cursor -> cursor.next(); QueryResult.Value(cursor.getLong(0) ?: 0L) }, 0).value

    private fun scalar(driver: JdbcSqliteDriver, sql: String): String? =
        driver.executeQuery(null, sql,
            { cursor -> cursor.next(); QueryResult.Value(cursor.getString(0)) }, 0).value

    @Test
    fun `v10 to v11 migration adds author tables and preserves v10 data`() {
        val file = java.nio.file.Files.createTempFile("qianyan_author_v11_test", ".db").toAbsolutePath()
        val url = "jdbc:sqlite:$file"
        try {
            val v10 = JdbcSqliteDriver(url)
            V10_MINIMAL_DDL.forEach { v10.exec(it) }
            v10.exec("PRAGMA user_version = 10")
            v10.exec(
                "INSERT INTO Novel(novel_id, project_id, title, source, genre, synopsis, scope, status, created_at, updated_at) " +
                    "VALUES ('n-legacy', 'p-legacy', '旧书', 'ORIGINAL_NOVEL', '[\"romance\"]', '', 'ORIGINAL', 'DRAFT', 1730000000000, 1730000000000)",
            )
            v10.exec(
                "INSERT INTO StoryFoundation(novel_id, base_novel_id, scope, version, genre, created_at, updated_at) " +
                    "VALUES ('n-legacy', 'n-legacy', 'ORIGINAL', 1, '[\"romance\"]', 1730000001000, 1730000001000)",
            )
            assertFalse(tableExists(v10, "AuthorProfile"), "v10 库不应有 AuthorProfile")
            v10.getConnection().close()

            val h = QianyanDbFactory.open(url)
            val driver = h.driver as JdbcSqliteDriver
            try {
                assertTrue(tableExists(driver, "AuthorProfile"), "migration 后应存在 AuthorProfile（P16 AIL-1）")
                assertTrue(tableExists(driver, "AuthorPreference"), "migration 后应存在 AuthorPreference（P16 AIL-1）")
                assertTrue(tableExists(driver, "AuthorEvidence"), "migration 后应存在 AuthorEvidence（P16 AIL-1）")
                assertEquals(14L, userVersion(driver), "migration 后 user_version 应为最新版本 13（P18-B Schema v14）")

                // 既有 v10 数据仍可读取
                assertEquals("旧书", scalar(driver, "SELECT title FROM Novel WHERE novel_id='n-legacy'"))
                assertEquals("n-legacy", scalar(driver, "SELECT novel_id FROM StoryFoundation WHERE novel_id='n-legacy'"))

                // 幂等
                DatabaseInitializer.initializeDatabase(driver)
                DatabaseInitializer.initializeDatabase(driver)
                assertTrue(tableExists(driver, "AuthorPreference"), "重复初始化后 AuthorPreference 表仍存在")
            } finally {
                driver.getConnection().close()
            }
        } finally {
            java.nio.file.Files.deleteIfExists(file)
        }
    }
}