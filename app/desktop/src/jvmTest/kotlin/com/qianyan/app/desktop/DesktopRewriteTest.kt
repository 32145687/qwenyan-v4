package com.qianyan.app.desktop

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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 验证正文页「AI 重写本章」用的是真实链路：
 *   tasks.create(WRITING) → planning.execute（真实产出 ChapterPlan）→ writingExecution.execute（真实产出 Draft）
 *
 * 断言落到持久化：ChapterPlan 非空、Draft 有正文、chapterId 正确绑定、Task 状态被真实推进。
 */
class DesktopRewriteTest {

    private val opened = mutableListOf<app.cash.sqldelight.db.SqlDriver>()

    @org.junit.jupiter.api.AfterEach
    fun closeDrivers() {
        opened.forEach { runCatching { it.close() } }
        opened.clear()
    }

    @Test
    fun `ai rewrite runs real planning then writing and persists a draft`() {
        val dir = Files.createTempDirectory("qianyan-rewrite-test")
        val handle = QianyanDbFactory.open("jdbc:sqlite:${dir.resolve("qianyan.db").toAbsolutePath()}")
        opened += handle.driver
        val c: ApplicationContainer = ApplicationContainer.fromDriver(
            handle.driver,
            DesktopProviderAssembler(DefaultProviderAssembler(InMemoryProviderCredentialStore())),
            ProviderConfiguration(ProviderType.MOCK),
        )

        val novelId = c.novels.createOriginal(title = "重写测试", genre = listOf("东方幻想"))
        val chapter = c.chapters.createNextChapter("第 1 章 · 试写", novelId)

        // === 与 RewriteDialog 中完全一致的调用序列 ===
        val planTaskId = c.tasks.create(TaskType.PLANNING)
        val request = UserWritingRequest(
            requestId = RequestId("req-rewrite-test"),
            intentType = IntentType.REWRITE,
            target = TargetRef(kind = TargetKind.CHAPTER, id = TargetRefId(chapter.chapterId.value)),
            planningScope = PlanningScope.CHAPTER,
            rawText = "让结尾更冷一些",
            baseNovelId = BaseNovelId(novelId.value),
            variantId = null,
            scope = VariantScope.ORIGINAL,
        )
        val plan = c.planning.execute(planTaskId, request, null, chapter.chapterId)
        val writeTaskId = c.tasks.create(TaskType.WRITING)
        val draft = c.writingExecution.execute(writeTaskId, request, plan)

        // === 断言：真实产出并落库 ===
        assertNotNull(plan, "planning 必须返回 ChapterPlan")
        assertTrue(plan.chapterGoal.isNotBlank(), "ChapterPlan 必须有非空的 chapterGoal")

        assertTrue(draft.content.isNotBlank(), "Draft 必须有正文")
        assertEquals(chapter.chapterId, draft.chapterId, "Draft 必须绑定到目标章节")
        assertEquals(novelId, draft.novelId, "Draft 必须绑定到作品")

        // 从仓储读回，证明真的写了库（不是内存对象）
        val persisted = c.draftRepository.latestByChapter(chapter.chapterId)
        assertNotNull(persisted, "Draft 必须能从仓储读回")
        assertTrue(persisted.content.isNotBlank(), "读回的 Draft 必须有正文")

        // Task 被真实使用过（WRITING Task 状态已离开 PENDING）
        val writeTask = c.tasks.findById(writeTaskId)
        assertTrue(writeTask.status.name != "PENDING", "WRITING Task 状态应被真实推进，实际=" + writeTask.status)
    }
}
