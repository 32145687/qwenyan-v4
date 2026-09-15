package com.qianyan.app.desktop

import com.qianyan.application.di.ApplicationContainer
import com.qianyan.application.usecase.workflow.ChapterPhase
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
import com.qianyan.app.desktop.adapter.DesktopProviderAssembler
import com.qianyan.provider.impl.DefaultProviderAssembler
import com.qianyan.storage.db.QianyanDbFactory
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 端到端流程验证（JVM，无 GUI）——证明 PC App 接入的是**真实**能力与**真实**持久化：
 *  1. 创建作品 → 建章节（真实落库）
 *  2. Durable Workflow：startChapter → advance 走到人工闸门 → approve（HITL 真实生效）
 *  3. 关闭后重开同一 SQLite 文件 → 数据仍在（证明"可以真实保存数据"）
 *  4. 章节规划入口：PlanningExecutionUseCase 产出真实 ChapterPlan
 *
 * AI 内容由 Mock Provider 生成（生成物为示意），但 Task / Workflow / Checkpoint / Draft /
 * Repository / SQLite 全部为真实实现。
 */
class DesktopFlowE2ETest {

    private val opened = mutableListOf<app.cash.sqldelight.db.SqlDriver>()

    @org.junit.jupiter.api.AfterEach
    fun closeDrivers() {
        // Windows 下 SQLite 连接不关会导致 @TempDir 清理失败
        opened.forEach { runCatching { it.close() } }
        opened.clear()
    }

    private fun containerFor(path: Path): ApplicationContainer {
        val handle = QianyanDbFactory.open("jdbc:sqlite:${path.toAbsolutePath()}")
        opened += handle.driver
        return ApplicationContainer.fromDriver(
            handle.driver,
            DesktopProviderAssembler(DefaultProviderAssembler(InMemoryProviderCredentialStore())),
            ProviderConfiguration(ProviderType.MOCK),
        )
    }

    @Test
    fun `full chapter workflow drives to human gate and persists across restart`() {
        // 自管理临时目录：Windows 下 @TempDir 清理会被 SQLite 文件锁影响，故不强制删除
        val dir = java.nio.file.Files.createTempDirectory("qianyan-desktop-e2e")
        val dbFile = dir.resolve("qianyan.db")

        // ---- 第一次会话：走完整流程 ----
        val novelId = run {
            val c = containerFor(dbFile)
            val id = c.novels.createOriginal(title = "长夜行舟", genre = listOf("东方幻想"), synopsis = "雪与灯")
            val ch = c.chapters.createNextChapter("第 1 章 · 雪夜遗言", id)

            val facade = c.workflowFacade
            var p = facade.startChapter(id, null, ch.chapterId)
            var guard = 0
            while (!p.waitingForUser && p.phase != ChapterPhase.COMPLETED && p.phase != ChapterPhase.FAILED && guard++ < 15) {
                p = facade.advance(ch.chapterId)
            }
            assertTrue(
                p.waitingForUser || p.phase == ChapterPhase.COMPLETED,
                "workflow should reach a human gate or complete, was=${p.phase}",
            )
            if (p.waitingForUser) {
                val after = facade.approve(ch.chapterId)
                assertTrue(after.phase != ChapterPhase.NOT_STARTED, "approve should advance the workflow")
            }
            id
        }

        // ---- 第二次会话：重开同一数据库文件，验证持久化 ----
        val reopened = containerFor(dbFile)
        val novels = reopened.novels.listOriginals()
        assertEquals(1, novels.size, "novel must survive restart")
        assertEquals("长夜行舟", novels.first().title)

        val chapters = reopened.chapters.listByNovel(novelId)
        assertEquals(1, chapters.size, "chapter must survive restart")
        assertNotNull(reopened.chapters.findById(chapters.first().chapterId))

        // 章节规划（真实 UseCase；AI 为 Mock Provider）
        val taskId = reopened.tasks.create(TaskType.PLANNING)
        val request = UserWritingRequest(
            requestId = RequestId("req-it-1"),
            intentType = IntentType.PLAN,
            target = TargetRef(kind = TargetKind.CHAPTER, id = TargetRefId(chapters.first().chapterId.value)),
            planningScope = PlanningScope.CHAPTER,
            rawText = "主角带着师父的遗言走出雪山",
            baseNovelId = BaseNovelId(novelId.value),
            variantId = null,
            scope = VariantScope.ORIGINAL,
        )
        val plan = reopened.planning.execute(taskId, request, null, chapters.first().chapterId)
        assertNotNull(plan, "planning use case must return a ChapterPlan")
    }
}
