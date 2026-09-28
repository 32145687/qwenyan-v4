package com.qianyan.app.desktop

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import com.qianyan.app.desktop.adapter.DesktopProviderAssembler
import com.qianyan.application.di.ApplicationContainer
import com.qianyan.provider.InMemoryProviderCredentialStore
import com.qianyan.provider.ProviderConfiguration
import com.qianyan.provider.ProviderType
import com.qianyan.provider.impl.DefaultProviderAssembler
import com.qianyan.storage.db.QianyanDbFactory
import com.qianyan.storage.db.QianyanDbHandle
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 桌面装配冒烟测试（JVM，无 GUI）：
 *  1. ApplicationContainer + DefaultProviderAssembler + SQLite 在桌面模块中可完整工作；
 *  2. 全新库直接初始化到 **schema v19**（含 P20-P4 ReadingProgress + I1 ProjectState + I3 AgentSession），P2.4 守卫触发器仍在；
 *  3. 旧 v16 库经同一入口自动迁移到 v17，旧数据保留。
 *
 * 说明：完整的 v1→v17 迁移矩阵由 `:storage` 的迁移测试覆盖（DraftMigrationTest /
 * ReadingProgressMigrationTest 等）；桌面侧只验证「用同一 QianyanDbFactory.open 入口」这一事实。
 * 持久化文件路径装配由 GUI 启动路径覆盖（DesktopGraph.create()）。
 */
class DesktopGraphSmokeTest {

    private val opened = mutableListOf<SqlDriver>()

    @org.junit.jupiter.api.AfterEach
    fun closeDrivers() {
        // Windows 下 SQLite 连接不关会导致临时目录清理失败
        opened.forEach { runCatching { it.close() } }
        opened.clear()
    }

    private fun openFileDb(prefix: String): QianyanDbHandle {
        val dir = Files.createTempDirectory(prefix)
        val handle = QianyanDbFactory.open("jdbc:sqlite:${dir.resolve("qianyan.db").toAbsolutePath()}")
        opened += handle.driver
        return handle
    }

    private fun containerOf(handle: QianyanDbHandle): ApplicationContainer = ApplicationContainer.fromDriver(
        handle.driver,
        DesktopProviderAssembler(DefaultProviderAssembler(InMemoryProviderCredentialStore())),
        ProviderConfiguration(ProviderType.MOCK),
    )

    @Test
    fun `container wires on jvm with mock provider`() {
        val c = containerOf(openFileDb("qianyan-desktop-smoke"))

        assertTrue(c.novels.listOriginals().isEmpty())
        val id = c.novels.createOriginal(title = "测试作品", genre = listOf("东方幻想"), synopsis = "冒烟测试")
        val novels = c.novels.listOriginals()
        assertEquals(1, novels.size)
        assertEquals("测试作品", novels.first().title)
        val chapter = c.chapters.createNextChapter("第一章 · 开始", id)
        assertNotNull(c.chapters.findById(chapter.chapterId))
    }

    @Test
    fun `fresh database initializes to schema v19 with reading progress project state session and guard triggers`() {
        val handle = openFileDb("qianyan-desktop-schema")

        assertEquals(19L, userVersion(handle.driver), "全新库应直接建到 schema v19（含 I1 ProjectState + I3 AgentSession）")
        assertTrue(tableExists(handle.driver, "Novel"), "v19 必须含 Novel")
        assertTrue(tableExists(handle.driver, "ChapterDraft"), "v19 必须含 ChapterDraft")
        assertTrue(tableExists(handle.driver, "ReadingProgress"), "v19 必须含 ReadingProgress（P20-P4）")
        assertTrue(tableExists(handle.driver, "ProjectState"), "v19 必须含 ProjectState（I1）")
        assertTrue(tableExists(handle.driver, "AgentSession"), "v19 必须含 AgentSession（I3）")

        // P2.4 物理写保护（PC-1 明确保留：R2 决策）
        assertTrue(triggerExists(handle.driver, "novel_original_update_protect"), "Original 改写保护必须存在")
        assertTrue(triggerExists(handle.driver, "novel_original_delete_protect"), "Original 删除保护必须存在")
        assertTrue(triggerExists(handle.driver, "variant_base_must_be_original"), "Variant base 规则必须存在")

        // 容器与 P20 seam 可用（真实业务层）
        val c = containerOf(handle)
        assertNotNull(c.reading, "P20-P4 Reader seam 必须可用")
        assertNotNull(c.readingProgressRepository, "阅读位置仓储必须可用")
        assertNotNull(c.writerGateway, "P20-P3 Writer seam 必须可用")
        assertNotNull(c.workflowFacade, "Chapter 工作流 seam 必须可用")
        assertNotNull(c.projects, "I1 Project 聚合 seam 必须可用")
        assertNotNull(c.agentSessions, "I3 Agent Session seam 必须可用")
    }

    @Test
    fun `legacy v16 database migrates to v19 without losing data`() {
        val dir = Files.createTempDirectory("qianyan-desktop-legacy")
        val url = "jdbc:sqlite:${dir.resolve("qianyan.db").toAbsolutePath()}"

        // 1) 造一个「旧库」：先建到 v19，再删掉 ReadingProgress 并把版本退回 16（模拟更早版本的旧库）
        val first = QianyanDbFactory.open(url)
        val novelId = containerOf(first).novels.createOriginal(title = "旧库作品")
        first.driver.execute(null, "DROP TABLE ReadingProgress", 0)
        first.driver.execute(null, "PRAGMA user_version = 16", 0)
        first.driver.close()

        // 2) 经同一入口重开 → 自动迁移到 v17
        val handle = QianyanDbFactory.open(url)
        opened += handle.driver

        assertEquals(19L, userVersion(handle.driver), "旧 v16 库应自动迁移到 v19")
        assertTrue(tableExists(handle.driver, "ReadingProgress"), "迁移后应重建 ReadingProgress")
        assertEquals("旧库作品", containerOf(handle).novels.getNovel(novelId)?.title, "迁移必须保留旧数据")
    }

    private fun userVersion(driver: SqlDriver): Long =
        driver.executeQuery(
            null,
            "PRAGMA user_version",
            { cursor ->
                cursor.next()
                QueryResult.Value(cursor.getLong(0) ?: 0L)
            },
            0,
        ).value

    private fun tableExists(driver: SqlDriver, table: String): Boolean =
        driver.executeQuery(
            null,
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name='$table' LIMIT 1",
            { cursor -> QueryResult.Value(cursor.next().value) },
            0,
        ).value

    private fun triggerExists(driver: SqlDriver, trigger: String): Boolean =
        driver.executeQuery(
            null,
            "SELECT 1 FROM sqlite_master WHERE type='trigger' AND name='$trigger' LIMIT 1",
            { cursor -> QueryResult.Value(cursor.next().value) },
            0,
        ).value
}