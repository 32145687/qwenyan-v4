package com.qianyan.app.desktop

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
 * R2 决策的验证（PC-1 · 替代旧 `DesktopDeletionTest`）。
 *
 * R2 Decision：保留 `novel_original_delete_protect`。PC-1 不实现 Original 真删除。
 * 作品删除/归档方案后续在 PC-7 单独决策。
 *
 * 因此本测试断言的是「当前 HEAD 的物理写保护契约」，而不是旧分支的删除语义：
 *  1. Original Novel 的 DELETE 被触发器拦截（`novel_original_delete_protect`）；
 *  2. Original Novel 的 UPDATE 被触发器拦截（`novel_original_update_protect`）；
 *  3. 拦截后数据完好（作品与章节都还在）；
 *  4. 旧分支的 `DesktopNovelDeletion` 实现**未被迁移**（该适配器在 PC-1 不存在）。
 */
class DesktopOriginalProtectionTest {

    private val opened = mutableListOf<app.cash.sqldelight.db.SqlDriver>()

    @org.junit.jupiter.api.AfterEach
    fun closeDrivers() {
        opened.forEach { runCatching { it.close() } }
        opened.clear()
    }

    private fun openDb(): Pair<ApplicationContainer, QianyanDbHandle> {
        val dir = Files.createTempDirectory("qianyan-original-protect")
        val handle = QianyanDbFactory.open("jdbc:sqlite:${dir.resolve("qianyan.db").toAbsolutePath()}")
        opened += handle.driver
        val c = ApplicationContainer.fromDriver(
            handle.driver,
            DesktopProviderAssembler(DefaultProviderAssembler(InMemoryProviderCredentialStore())),
            ProviderConfiguration(ProviderType.MOCK),
        )
        return c to handle
    }

    @Test
    fun `original novel cannot be deleted or updated at the database level`() {
        val (c, handle) = openDb()

        val novelId = c.novels.createOriginal(title = "受保护的作品", genre = listOf("东方幻想"), synopsis = "不可删不可改")
        val chapter = c.chapters.createNextChapter("第 1 章 · 起点", novelId)

        // 1) DELETE 被 novel_original_delete_protect 拦截
        val deleteRejected = runCatching {
            handle.driver.execute(null, "DELETE FROM Novel WHERE novel_id = ?", 1) { bindString(0, novelId.value) }
        }.isFailure
        assertTrue(deleteRejected, "Original 删除保护必须生效（novel_original_delete_protect）")

        // 2) 拦截后数据完好
        assertEquals("受保护的作品", c.novels.getNovel(novelId)?.title, "被拦截的删除不得影响数据")
        assertNotNull(c.chapters.findById(chapter.chapterId), "被拦截的删除不得影响关联章节")

        // 3) UPDATE 被 novel_original_update_protect 拦截
        val updateRejected = runCatching {
            handle.driver.execute(null, "UPDATE Novel SET title = '被改写' WHERE novel_id = ?", 1) { bindString(0, novelId.value) }
        }.isFailure
        assertTrue(updateRejected, "Original 改写保护必须生效（novel_original_update_protect）")
        assertEquals("受保护的作品", c.novels.getNovel(novelId)?.title, "被拦截的改写不得改变数据")
    }
}