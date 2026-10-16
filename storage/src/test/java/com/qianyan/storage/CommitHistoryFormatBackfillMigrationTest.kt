package com.qianyan.storage

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.qianyan.model.commit.CommitFormatHistory
import com.qianyan.model.commit.CommitHistoryId
import com.qianyan.storage.db.DatabaseInitializer
import com.qianyan.storage.db.QianyanDb
import com.qianyan.storage.db.QianyanDbFactory
import com.qianyan.storage.repository.SqliteCommitHistoryRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 1 · revert 格式契约 · Schema v25 → v26（25.sqm）**值级**回填验证。
 *
 * 用真实 v25 形态数据库（真实建库 → 播种旧形态行 → 真实摘除两列 → user_version=25 → 重开触发迁移），
 * 逐行断言回填出的存储值（`'legacy'` / `'markdown:controlled:v1'` / `'none'` / SQL NULL）：
 *  - 可证明侧（载体行存在）按该行 format 回填（COALESCE 处理 format=NULL ⇒ 'legacy'）；
 *  - 首个 COMMIT（previous_draft_id IS NULL 且 previous_content=''）⇒ previous_format = 'none'；
 *  - 同 row 旧行（previous_draft_id IS NULL 且正文非空）⇒ previous_format 保持 NULL，**不**按当前载体行推断；
 *  - 载体行缺失（异常状态）⇒ 保持 NULL，不错误回填为 'legacy'。
 * 同时验证领域读侧（fromStorage）不把 NULL 混淆为 KNOWN_LEGACY，以及迁移幂等。
 */
class CommitHistoryFormatBackfillMigrationTest {

    @Test
    fun `v25 to v26 migration backfills provable formats and keeps unprovable sides unknown`() {
        val file = java.nio.file.Files.createTempFile("qianyan_fmt_backfill_v25", ".db").toAbsolutePath()
        val url = "jdbc:sqlite:$file"
        try {
            // 1) 真实建到最新版本（当前 v28），播种 v25 时代形态的旧行（两列 format 值留空）
            val first = QianyanDbFactory.open(url)
            val d1 = first.driver as JdbcSqliteDriver
            seedOldRows(d1)
            assertTrue(
                columnExists(d1, "CommitHistory", "previous_format") && columnExists(d1, "CommitHistory", "resulting_format"),
                "前置：全新库含两列（播种行未写值 ⇒ NULL）",
            )
            assertStorageFormats(d1, "h-r1", null, null, "播种后迁移前两列应为 NULL")
            d1.getConnection().close()

            // 2) 真实回退为 v25 形态：摘除两列 + user_version = 25
            val down = QianyanDbFactory.open(url)
            val dd = down.driver as JdbcSqliteDriver
            dd.execute(null, "ALTER TABLE CommitHistory DROP COLUMN previous_format", 0)
            dd.execute(null, "ALTER TABLE CommitHistory DROP COLUMN resulting_format", 0)
            // P2 v27（26.sqm）：Character/CharacterState/Event 新列不幂等，降级前必须真实摘除
            dd.dropWorldModelV27Columns()
            // P3 v28（27.sqm）：CandidateFact 由迁移建出，降级前必须真实摘除（CREATE TABLE 不幂等）
            dd.execute(null, "DROP TABLE IF EXISTS CandidateFact", 0)
            dd.execute(null, "PRAGMA user_version = 25", 0)
            assertEquals(25L, userVersion(dd), "前置：模拟库应为 v25")
            assertTrue(!columnExists(dd, "CommitHistory", "previous_format"), "前置：v25 形态无 previous_format")
            assertTrue(!columnExists(dd, "CommitHistory", "resulting_format"), "前置：v25 形态无 resulting_format")
            dd.getConnection().close()

            // 3) 重开 → DatabaseInitializer 执行 v25 → v26 迁移（含 25.sqm 的两条可证明回填）
            val second = QianyanDbFactory.open(url)
            val d2 = second.driver as JdbcSqliteDriver
            try {
                assertEquals(28L, userVersion(d2), "v25 库应迁移到最新版本")
                assertTrue(columnExists(d2, "CommitHistory", "previous_format"), "迁移后应含 previous_format")
                assertTrue(columnExists(d2, "CommitHistory", "resulting_format"), "迁移后应含 resulting_format")

                // 4) 值级断言（存储字符串）
                // r1：previous=d-p1（legacy 行）⇒ 'legacy'；resulting=d-r1（legacy 行）⇒ 'legacy'（场景 1、3）
                assertStorageFormats(d2, "h-r1", "legacy", "legacy", "场景1/3：载体行 format=NULL ⇒ 'legacy'")
                // r2：previous=d-p2（CM 行）⇒ 'markdown:controlled:v1'；resulting=d-r2（CM 行）⇒ 同（场景 2、4）
                assertStorageFormats(
                    d2, "h-r2", DraftFormatText.CM, DraftFormatText.CM,
                    "场景2/4：载体行 format='markdown:controlled:v1' ⇒ 原样回填",
                )
                // r3：首个 COMMIT（previous_draft_id IS NULL 且 previous_content=''）⇒ 'none'（场景 5）
                assertStorageFormats(d2, "h-r3", "none", DraftFormatText.CM, "场景5：首次 COMMIT 的 previous 侧 = 'none'")
                // r4：同 row 旧行（previous_draft_id IS NULL 且正文非空）⇒ 保持 NULL（场景 6；
                //     载体行 d-r4 是 CM —— 若实现按当前 Draft 推断会得到 CM，必须失败）
                assertStorageFormats(d2, "h-r4", null, DraftFormatText.CM, "场景6：同 row 旧行 previous 侧保持 NULL，不按载体行推断")
                // r5：resulting_draft_id 指向不存在的行 ⇒ 保持 NULL，不得错误回填为 'legacy'（场景 7）
                assertStorageFormats(d2, "h-r5", "legacy", null, "场景7：载体行缺失 ⇒ 保持 NULL（未知）")

                // 5) 正文内容必须原样保留
                val db = QianyanDb(d2)
                assertEquals("新正文一", db.commitHistoryQueries.getCommitHistoryById("h-r1").executeAsOne().resulting_content)
                assertEquals("同row旧文", db.commitHistoryQueries.getCommitHistoryById("h-r4").executeAsOne().previous_content)

                // 6) 领域读侧：NULL ⇒ UNKNOWN，绝不混淆为 KNOWN_LEGACY
                val repo = SqliteCommitHistoryRepository(db)
                assertEquals(CommitFormatHistory.KNOWN_LEGACY, repo.getById(CommitHistoryId("h-r1"))!!.previousFormat)
                assertEquals(
                    CommitFormatHistory.KNOWN_CONTROLLED_MARKDOWN,
                    repo.getById(CommitHistoryId("h-r2"))!!.resultingFormat,
                )
                assertEquals(CommitFormatHistory.NONE, repo.getById(CommitHistoryId("h-r3"))!!.previousFormat)
                assertEquals(CommitFormatHistory.UNKNOWN, repo.getById(CommitHistoryId("h-r4"))!!.previousFormat, "NULL 必须读为 UNKNOWN")
                assertEquals(CommitFormatHistory.UNKNOWN, repo.getById(CommitHistoryId("h-r5"))!!.resultingFormat, "载体缺失必须读为 UNKNOWN")

                // 7) 幂等：重复初始化不得改动已回填的值
                DatabaseInitializer.initializeDatabase(d2)
                DatabaseInitializer.initializeDatabase(d2)
                assertEquals(28L, userVersion(d2))
                assertStorageFormats(d2, "h-r4", null, DraftFormatText.CM, "幂等重跑后同 row 旧行仍为 NULL")
                assertStorageFormats(d2, "h-r2", DraftFormatText.CM, DraftFormatText.CM, "幂等重跑后可证明值不变")
            } finally {
                d2.getConnection().close()
            }
        } finally {
            java.nio.file.Files.deleteIfExists(file)
        }
    }

    /* ---------------- 播种 ---------------- */

    /** v25 时代形态：CommitHistory 行不含两列 format 值；ChapterDraft 行含真实 format（NULL=legacy / CM）。 */
    private fun seedOldRows(driver: JdbcSqliteDriver) {
        driver.execute(
            null,
            "INSERT INTO Novel(novel_id, project_id, title, source, genre, synopsis, scope, status, created_at, updated_at) " +
                "VALUES ('n-bf','p-bf','回填测试书','ORIGINAL','[]','','ORIGINAL','ACTIVE',1,1)",
            0,
        )
        // (draft_id, format)：NULL = legacy，'markdown:controlled:v1' = 受控 Markdown
        val drafts = listOf(
            "d-p1" to null,
            "d-r1" to null,
            "d-p2" to DraftFormatText.CM,
            "d-r2" to DraftFormatText.CM,
            "d-r3" to DraftFormatText.CM,
            "d-r4" to DraftFormatText.CM,
            "d-p5" to null,
        )
        for ((id, format) in drafts) {
            driver.execute(
                null,
                "INSERT INTO ChapterDraft(draft_id, novel_id, variant_id, scope, chapter_id, chapter_plan_id, " +
                    "previous_draft_id, content, format, status, source_model, created_at, updated_at) " +
                    "VALUES ('$id','n-bf',NULL,'ORIGINAL',NULL,NULL,NULL,'旧稿',${if (format == null) "NULL" else "'$format'"},'WRITTEN','',1,1)",
                0,
            )
        }
        // (history_id, previous_draft_id, resulting_draft_id, previous_content, resulting_content)
        val histories = listOf(
            listOf("h-r1", "'d-p1'", "'d-r1'", "旧文一", "新正文一"),
            listOf("h-r2", "'d-p2'", "'d-r2'", "旧文二", "新正文二"),
            listOf("h-r3", "NULL", "'d-r3'", "", "新正文三"),
            listOf("h-r4", "NULL", "'d-r4'", "同row旧文", "新正文四"),
            listOf("h-r5", "'d-p5'", "'d-missing'", "旧文五", "新正文五"),
        )
        for ((i, h) in histories.withIndex()) {
            driver.execute(
                null,
                "INSERT INTO CommitHistory(history_id, commit_id, project_id, operation, artifact_id, gate_id, novel_id, " +
                    "chapter_id, variant_id, previous_draft_id, resulting_draft_id, previous_content, resulting_content, " +
                    "summary, reverted_history_id, created_at) " +
                    "VALUES ('${h[0]}','c-${h[0]}','p-bf','COMMIT',NULL,NULL,'n-bf','ch-${i + 1}',NULL,${h[1]},${h[2]},'${h[3]}','${h[4]}','提交',NULL,${i + 1})",
                0,
            )
        }
    }

    /* ---------------- helpers ---------------- */

    /** 读某条历史的 (previous_format, resulting_format) 存储原值。 */
    private fun storageFormats(driver: JdbcSqliteDriver, historyId: String): Pair<String?, String?> =
        driver.executeQuery(
            null,
            "SELECT previous_format, resulting_format FROM CommitHistory WHERE history_id='$historyId'",
            { cursor ->
                cursor.next().value
                QueryResult.Value(cursor.getString(0) to cursor.getString(1))
            },
            0,
        ).value as Pair<String?, String?>

    private fun assertStorageFormats(
        driver: JdbcSqliteDriver,
        historyId: String,
        expectedPrevious: String?,
        expectedResulting: String?,
        message: String,
    ) {
        val (p, r) = storageFormats(driver, historyId)
        assertEquals(expectedPrevious, p, "$message [previous_format @ $historyId]")
        assertEquals(expectedResulting, r, "$message [resulting_format @ $historyId]")
    }

    private fun columnExists(driver: JdbcSqliteDriver, table: String, column: String): Boolean =
        driver.executeQuery(
            null, "SELECT 1 FROM pragma_table_info('$table') WHERE name='$column' LIMIT 1",
            { cursor -> QueryResult.Value(cursor.next().value) }, 0,
        ).value

    private fun userVersion(driver: JdbcSqliteDriver): Long =
        driver.executeQuery(
            null, "PRAGMA user_version",
            { cursor -> cursor.next(); QueryResult.Value(cursor.getLong(0) ?: 0L) }, 0,
        ).value
}

/** 存储侧常量（与 CommitFormatHistory 的编码一致；测试内只做字面量对照）。 */
private object DraftFormatText {
    const val CM: String = "markdown:controlled:v1"
}
