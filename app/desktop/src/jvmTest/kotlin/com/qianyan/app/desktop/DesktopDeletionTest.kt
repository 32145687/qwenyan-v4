package com.qianyan.app.desktop

import com.qianyan.app.desktop.adapter.DesktopNovelDeletion
import com.qianyan.app.desktop.adapter.DesktopProviderAssembler
import com.qianyan.application.di.ApplicationContainer
import com.qianyan.model.BaseNovelId
import com.qianyan.model.IntentType
import com.qianyan.model.PlanningScope
import com.qianyan.model.RequestId
import com.qianyan.model.VariantScope
import com.qianyan.model.context.TargetKind
import com.qianyan.model.context.TargetRef
import com.qianyan.model.context.TargetRefId
import com.qianyan.model.context.UserWritingRequest
import com.qianyan.model.task.TaskType
import com.qianyan.provider.InMemoryProviderCredentialStore
import com.qianyan.provider.ProviderConfiguration
import com.qianyan.provider.ProviderType
import com.qianyan.provider.impl.DefaultProviderAssembler
import com.qianyan.storage.db.QianyanDbFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 作品删除验证（JVM）：
 *  1. 建作品 → 建章节 → 走创作流程（产出 Draft / Workflow / Story State 等关联数据）
 *  2. 删除前备份 → 检查备份文件真实落盘
 *  3. 级联删除 → 检查 Novel / Chapter / Draft 等全部消失，且**其它作品不受影响**
 *  4. Original 的**改写**保护仍然有效（update 触发器未被移除）
 */
class DesktopDeletionTest {

    private val opened = mutableListOf<app.cash.sqldelight.db.SqlDriver>()

    @org.junit.jupiter.api.AfterEach
    fun closeDrivers() {
        opened.forEach { runCatching { it.close() } }
        opened.clear()
    }

    private fun containerFor(path: Path): Pair<ApplicationContainer, com.qianyan.storage.db.QianyanDbHandle> {
        val handle = QianyanDbFactory.open("jdbc:sqlite:${path.toAbsolutePath()}")
        opened += handle.driver
        val c = ApplicationContainer.fromDriver(
            handle.driver,
            DesktopProviderAssembler(DefaultProviderAssembler(InMemoryProviderCredentialStore())),
            ProviderConfiguration(ProviderType.MOCK),
        )
        return c to handle
    }

    @Test
    fun `delete cascades all related data, keeps backup, and spares other novels`() {
        val dir = Files.createTempDirectory("qianyan-deletion-test")
        val (c, handle) = containerFor(dir.resolve("qianyan.db"))

        // ---- 造数据：目标作品（含章节 + 创作流程产出）与一个旁观作品 ----
        val victimId = c.novels.createOriginal(title = "要删掉的作品", genre = listOf("东方幻想"), synopsis = "将被删除")
        val victimChapter = c.chapters.createNextChapter("第 1 章 · 起点", victimId)
        c.workflowFacade.startChapter(victimId, null, victimChapter.chapterId)
        // 推进到任一稳定状态（Mock Provider 下会产出 Draft）
        runCatching {
            var p = c.workflowFacade.getChapterProgress(victimChapter.chapterId)
            var guard = 0
            while (!p.waitingForUser && guard++ < 15) p = c.workflowFacade.advance(victimChapter.chapterId)
        }
        // 触发一次 Planning，确保有 ChapterPlan 之外的关联写入路径被覆盖
        runCatching {
            val taskId = c.tasks.create(TaskType.PLANNING)
            c.planning.execute(
                taskId,
                UserWritingRequest(
                    requestId = RequestId("req-del-1"),
                    intentType = IntentType.PLAN,
                    target = TargetRef(kind = TargetKind.CHAPTER, id = TargetRefId(victimChapter.chapterId.value)),
                    planningScope = PlanningScope.CHAPTER,
                    rawText = "将被删除的作品",
                    baseNovelId = BaseNovelId(victimId.value),
                    scope = VariantScope.ORIGINAL,
                ),
                null,
                victimChapter.chapterId,
            )
        }

        val keeperId = c.novels.createOriginal(title = "要保留的作品", synopsis = "不能受影响")
        val keeperChapter = c.chapters.createNextChapter("第 1 章 · 保留", keeperId)

        // 删除前的关联数据量（确认真有东西可删）
        val draftsBefore = c.draftRepository.listByChapter(victimChapter.chapterId).size
        val victimChaptersBefore = c.chapters.listByNovel(victimId).size
        assertTrue(victimChaptersBefore > 0, "victim should have chapters before deletion")

        // ---- 备份 + 删除 ----
        val deletion = DesktopNovelDeletion(db = handle.db, driver = handle.driver, appDir = dir)
        val backupDir = deletion.backup(
            title = "要删掉的作品",
            synopsis = "将被删除",
            chapters = c.chapters.listByNovel(victimId).map { ch ->
                DesktopNovelDeletion.ChapterBackup(
                    order = ch.order,
                    title = ch.title,
                    content = c.draftRepository.latestByChapter(ch.chapterId)?.content ?: "",
                )
            },
        )
        assertTrue(backupDir != null, "backup directory must be created")
        assertTrue(Files.exists(backupDir!!), "backup directory must exist on disk")
        assertTrue(Files.list(backupDir).count() >= 2, "backup must contain meta file + at least one chapter file")

        val counts = deletion.deleteCascade(victimId.value)

        // ---- 断言：目标作品相关数据全部消失 ----
        assertEquals(null, c.novels.getNovel(victimId), "victim novel must be gone")
        assertTrue(c.chapters.listByNovel(victimId).isEmpty(), "victim chapters must be gone")
        assertTrue(c.draftRepository.listByChapter(victimChapter.chapterId).isEmpty(), "victim drafts must be gone")
        assertTrue(counts.containsKey("Novel"), "Novel row must have been deleted")
        assertTrue(counts.containsKey("Chapter"), "Chapter rows must have been deleted")
        if (draftsBefore > 0) assertTrue(counts.containsKey("ChapterDraft"), "ChapterDraft rows must have been deleted")

        // ---- 断言：旁观作品完全不受影响 ----
        assertEquals("要保留的作品", c.novels.getNovel(keeperId)?.title, "other novel must survive")
        assertEquals(1, c.chapters.listByNovel(keeperId).size, "other novel's chapters must survive")
        assertEquals(1, c.novels.listOriginals().size, "only one novel should remain")

        // ---- 断言：Original 改写保护仍在（只放行了删除） ----
        val updateRejected = runCatching {
            handle.driver.execute(
                null,
                "UPDATE Novel SET title = '被改写' WHERE novel_id = ?",
                1,
            ) { bindString(0, keeperId.value) }
        }.isFailure
        assertTrue(updateRejected, "Original update protection must still be active")
    }
}
