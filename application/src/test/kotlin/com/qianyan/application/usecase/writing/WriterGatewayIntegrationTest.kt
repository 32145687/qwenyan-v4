package com.qianyan.application.usecase.writing

import com.qianyan.application.di.ApplicationContainer
import com.qianyan.model.ChapterId
import com.qianyan.model.DraftId
import com.qianyan.model.NovelId
import com.qianyan.model.writing.Draft
import com.qianyan.model.writing.DraftFormat
import com.qianyan.model.writing.DraftStatus
import com.qianyan.model.workflow.WorkflowStepPhase
import com.qianyan.provider.ChatMessage
import com.qianyan.provider.ChatRole
import com.qianyan.provider.FinishReason
import com.qianyan.provider.ProviderResponse
import com.qianyan.provider.Usage
import com.qianyan.provider.impl.MockLLMGateway
import kotlinx.datetime.Clock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P20-P3 · Writer 用户层 seam（[WriterFacade] / [WriterUseCases]）集成测试。
 *
 * 覆盖 Android Writer 依赖的真实 Application 链路（内存库 + Mock LLM，无网络）：
 *  1. 打开上下文：章节信息 + 尚无 Draft；
 *  2. AI 继续写：驱动既有 Workflow（PLANNING → WRITING → …），产出 Draft 且标记受控 Markdown v1；
 *     并验证 DecisionPolicy 经 P5-fix durable seam（PLANJSON2）持久化（不新建第二套 Decision 路径）；
 *  3. 保存：用户编辑内容经 DraftRepository 落库（Repository 层验证），format 保持不变；
 *  4. 旧 Draft（format=null）保存后仍为 null（legacy 兼容，不静默迁移）；
 *  5. AI 改写：复用既有 Critique → Revision，产出 status=REVISED 的新 Draft，lineage 指向原稿、原稿不被破坏。
 */
class WriterGatewayIntegrationTest {

    private fun gateway(): MockLLMGateway = MockLLMGateway { req ->
        val system = req.messages.first { it.role == ChatRole.SYSTEM }.content
        val body = when {
            "StoryWriterAgent" in system -> """{"content":"AI 生成的正文"}"""
            "StoryRevisionAgent" in system -> """{"content":"修订后的正文"}"""
            "StoryCriticAgent" in system -> """{"passed":true}"""
            else -> """{"chapterGoal":"g"}"""
        }
        ProviderResponse(
            message = ChatMessage(ChatRole.ASSISTANT, buildJsonObject { put("answer", body) }.toString()),
            usage = Usage(1, 1, 2),
            finishReason = FinishReason.STOP,
        )
    }

    private fun draft(novelId: NovelId, chapterId: ChapterId, id: String, content: String, format: String?) = Draft(
        draftId = DraftId(id),
        novelId = novelId,
        chapterId = chapterId,
        content = content,
        format = format,
        status = DraftStatus.WRITTEN,
        createdAt = Clock.System.now(),
        updatedAt = Clock.System.now(),
    )

    /* 1. 打开上下文：真实章节信息 + 尚无 Draft（不伪造草稿） */
    @Test
    fun `load context returns chapter info and no draft for fresh chapter`() {
        val app = ApplicationContainer.open(analysisGateway = gateway())
        val novelId = app.novels.createOriginal(title = "测试仙侠")
        val chapter = app.chapters.createNextChapter(title = "第一章", novelId = novelId)

        val context = app.writerGateway.loadContext(novelId, null, chapter.chapterId)

        assertEquals("测试仙侠", context.novelTitle)
        assertEquals("第一章", context.chapterTitle)
        assertNull(context.draft, "全新章节尚无 Draft，UI 不伪造")
        assertEquals("NOT_STARTED", context.phase.name)
    }

    /* 2. AI 继续写：既有 Workflow 产出 Draft + 受控 Markdown；DecisionPolicy 走 P5-fix durable seam */
    @Test
    fun `continue writing drives workflow and produces controlled markdown draft`() {
        val app = ApplicationContainer.open(analysisGateway = gateway())
        val novelId = app.novels.createOriginal(title = "测试仙侠")
        val chapter = app.chapters.createNextChapter(title = "第一章", novelId = novelId)

        val progress = app.writerGateway.continueWriting(novelId, null, chapter.chapterId)

        assertEquals("WAITING_CONFIRMATION", progress.phase.name, "驱动至既有 Workflow 的人闸阶段")
        val draft = app.draftRepository.latestByChapter(chapter.chapterId)
        assertNotNull(draft, "AI 写作应产出真实 Draft")
        assertEquals(DraftFormat.CONTROLLED_MARKDOWN, draft.format, "新产生的 Draft 标记为受控 Markdown v1")
        // 该 Draft 由 Workflow 继续推进至 FINALIZE（人闸前），状态为 FINAL
        assertEquals(DraftStatus.FINAL, draft.status)

        // DecisionPolicy 经 P5-fix 的 durable PLANNING seam 持久化（未新建第二套 Decision 路径）
        val wf = app.workflowRepository.getWorkflowByActiveChapter(chapter.chapterId)
        assertNotNull(wf)
        val planRef = app.workflowRepository.listSteps(wf.workflowId)
            .first { it.phase == WorkflowStepPhase.PLANNING }.resultReference
        assertNotNull(planRef)
        assertTrue(planRef.startsWith("PLANJSON2:"), "PLANNING 结果含 DecisionPolicy 快照（P5-fix seam）")
    }

    /* 3. 保存：编辑内容真实落库（Repository 层验证）；format 不变 */
    @Test
    fun `save content persists edited text and keeps format`() {
        val app = ApplicationContainer.open(analysisGateway = gateway())
        val novelId = app.novels.createOriginal(title = "测试仙侠")
        val chapter = app.chapters.createNextChapter(title = "第一章", novelId = novelId)
        val draft = draft(novelId, chapter.chapterId, "d-manual", "初稿正文", DraftFormat.CONTROLLED_MARKDOWN)
        app.draftRepository.save(draft)

        val saved = app.writerGateway.saveContent(draft.draftId, "用户编辑后的正文")

        assertEquals("用户编辑后的正文", saved.content)
        assertEquals(DraftFormat.CONTROLLED_MARKDOWN, saved.format, "保存不得改写 format")
        assertEquals(draft.draftId, saved.draftId, "draftId 身份不变")
        // Repository 层回读验证（真实落库）
        assertEquals("用户编辑后的正文", app.draftRepository.getById(draft.draftId)?.content)
    }

    /* 4. 旧 Draft（format=null）保存后仍为 null：legacy 兼容，不静默迁移 */
    @Test
    fun `save content keeps legacy null format`() {
        val app = ApplicationContainer.open(analysisGateway = gateway())
        val novelId = app.novels.createOriginal(title = "测试仙侠")
        val chapter = app.chapters.createNextChapter(title = "第一章", novelId = novelId)
        val legacy = draft(novelId, chapter.chapterId, "d-legacy", "旧正文", format = null)
        app.draftRepository.save(legacy)

        val saved = app.writerGateway.saveContent(legacy.draftId, "旧正文（编辑后）")

        assertNull(saved.format, "legacy Draft 的 format=null 必须保持（不静默迁移受控 Markdown）")
        assertNull(app.draftRepository.getById(legacy.draftId)?.format)
        assertEquals("旧正文（编辑后）", app.draftRepository.getById(legacy.draftId)?.content)
    }

    /* 5. AI 改写：复用既有 Critique → Revision；新 draftId / REVISED / lineage 指向原稿，原稿不破坏 */
    @Test
    fun `rewrite produces revised draft keeping lineage and original`() {
        val app = ApplicationContainer.open(analysisGateway = gateway())
        val novelId = app.novels.createOriginal(title = "测试仙侠")
        val chapter = app.chapters.createNextChapter(title = "第一章", novelId = novelId)
        val original = draft(novelId, chapter.chapterId, "d-orig", "原正文", DraftFormat.CONTROLLED_MARKDOWN)
        app.draftRepository.save(original)

        val revised = app.writerGateway.rewrite(novelId, null, chapter.chapterId)

        assertEquals(DraftStatus.REVISED, revised.status)
        assertEquals(original.draftId, revised.previousDraftId, "修订稿 lineage 指向原稿")
        assertEquals(DraftFormat.CONTROLLED_MARKDOWN, revised.format, "新产生的修订稿标记为受控 Markdown v1")
        assertEquals("修订后的正文", revised.content)
        // 原稿不被破坏，且成为章节最新稿
        assertEquals("原正文", app.draftRepository.getById(original.draftId)?.content)
        assertEquals(revised.draftId, app.draftRepository.latestByChapter(chapter.chapterId)?.draftId)
    }

    /* 6. 无 Draft 时改写 → 类型化拒绝（不伪造草稿） */
    @Test
    fun `rewrite without draft is rejected`() {
        val app = ApplicationContainer.open(analysisGateway = gateway())
        val novelId = app.novels.createOriginal(title = "测试仙侠")
        val chapter = app.chapters.createNextChapter(title = "第一章", novelId = novelId)

        val ex = kotlin.test.assertFailsWith<com.qianyan.application.error.ApplicationException> {
            app.writerGateway.rewrite(novelId, null, chapter.chapterId)
        }
        assertTrue(ex.error is com.qianyan.application.error.ApplicationError.EntityNotFound)
        assertNull(app.draftRepository.latestByChapter(chapter.chapterId))
    }
}