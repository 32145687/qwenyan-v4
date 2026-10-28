package com.qianyan.application.usecase.writing

import com.qianyan.application.di.ApplicationContainer
import com.qianyan.application.usecase.workflow.ChapterPhase
import com.qianyan.model.ChapterId
import com.qianyan.model.DraftId
import com.qianyan.model.NovelId
import com.qianyan.model.writing.Draft
import com.qianyan.model.writing.DraftFormat
import com.qianyan.model.workingdraft.WorkingDraftStatus
import com.qianyan.model.writing.DraftStatus
import com.qianyan.model.workflow.WorkflowStepPhase
import com.qianyan.provider.ChatMessage
import com.qianyan.provider.ChatRole
import com.qianyan.provider.FinishReason
import com.qianyan.provider.ProviderResponse
import com.qianyan.provider.Usage
import com.qianyan.provider.impl.MockLLMGateway
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * P20-P3 · Writer 用户层 seam（[WriterFacade] / [WriterUseCases]）集成测试。
 *
 * 覆盖 Android Writer 依赖的真实 Application 链路（内存库 + Mock LLM，无网络）：
 *  1. 打开上下文：章节信息 + 尚无 Draft；
 *  2. AI 继续写：驱动既有 Workflow（PLANNING → WRITING → …），产出 Draft 且标记受控 Markdown v1；
 *     并验证 DecisionPolicy 经 P5-fix durable seam（PLANJSON2）持久化（不新建第二套 Decision 路径）；
 *  3. 保存：用户编辑内容经 DraftRepository 落库（Repository 层验证），format 保持不变；
 *  4. 旧 Draft（format=null）保存后仍为 null（legacy 兼容，不静默迁移）；
 *  5. AI 改写：复用既有 Critique → Revision，产物进入 WorkingDraft + Human Gate；批准前不写 Draft / Canonical。
 */
class WriterGatewayIntegrationTest {

    private fun gateway(): MockLLMGateway = MockLLMGateway { req ->
        val system = req.messages.first { it.role == ChatRole.SYSTEM }.content
        val body = when {
            "StoryWriterAgent" in system -> """{"content":"AI 生成的正文"}"""
            "StoryRevisionAgent" in system -> """{"content":"修订后的正文"}"""
            "StoryCriticAgent" in system -> """{"passed":true}"""
            // 经人工门后 Workflow 会执行 Knowledge Update（无变更 → 空 changes），
            // 使流程能走到 COMPLETED（本文件 7 号用例需要该终态）。
            "KnowledgeUpdateAgent" in system -> """{"changes":[]}"""
            else -> """{"chapterGoal":"g"}"""
        }
        ProviderResponse(
            message = ChatMessage(ChatRole.ASSISTANT, buildJsonObject { put("answer", body) }.toString()),
            usage = Usage(1, 1, 2),
            finishReason = FinishReason.STOP,
        )
    }

    /**
     * P1-02h：fixture Draft 的时间戳固定为**过去**（确定性），避免与"测试过程中新产生的 Draft"落在同一毫秒
     * —— `latestByChapter` 使用 `created_at DESC, draft_id DESC`，同毫秒时按 draft_id 字符串比较决定顺序，
     * 曾使本类 rewrite 用例偶发失败（`"d-orig"` 被选中）。**生产排序契约不变**，仅 fixture 不再依赖"当前时刻"。
     */
    private val FIXTURE_CREATED_AT: Instant = Instant.parse("2026-01-01T00:00:00Z")

    private fun draft(novelId: NovelId, chapterId: ChapterId, id: String, content: String, format: String?) = Draft(
        draftId = DraftId(id),
        novelId = novelId,
        chapterId = chapterId,
        content = content,
        format = format,
        status = DraftStatus.WRITTEN,
        createdAt = FIXTURE_CREATED_AT,
        updatedAt = FIXTURE_CREATED_AT,
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
        // P1-02c+d：AI 产出落 WorkingDraft（进程内工作区），**不写章节 Draft 表**；
        // 受控 Markdown 标记（P2/FD-1）在人闸批准后的 Commit 载体上生效（见 AiWritingCanonicalTest）。
        assertNull(app.draftRepository.latestByChapter(chapter.chapterId), "AI 产出不得直写章节 Draft")
        val working = app.workingDrafts.list(app.projects.projectOf(novelId).projectId)
            .single { it.target.chapterId == chapter.chapterId && !it.status.isTerminal }
        assertEquals("AI 生成的正文", working.content, "AI 正文落在工作产物")
        // 该工作产物由 Workflow 推进至 FINALIZE（人闸前）→ VALIDATED
        assertEquals(WorkingDraftStatus.VALIDATED, working.status)
        // 人闸绑定的待审对象是工作产物（Gate 不冒充 DraftId）
        val pendingGate = app.workflowRepository.getGate(
            assertNotNull(app.workflowRepository.getWorkflowByActiveChapter(chapter.chapterId)?.pendingGateId),
        )
        assertNull(pendingGate?.draftId, "工作产物 Gate 不绑 Draft")

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

    /* 5. AI 改写：WorkingDraft + Human Gate；批准后由既有 Commit 产生新 Canonical 载体，lineage 指向原稿 */
    @Test
    fun `rewrite enters the human gate and approval commits the candidate`() {
        val app = ApplicationContainer.open(analysisGateway = gateway())
        val novelId = app.novels.createOriginal(title = "测试仙侠")
        app.projects.openProject(novelId)
        val chapter = app.chapters.createNextChapter(title = "第一章", novelId = novelId)
        val original = draft(novelId, chapter.chapterId, "d-orig", "原正文", DraftFormat.CONTROLLED_MARKDOWN)
        app.draftRepository.save(original)

        val progress = app.writerGateway.rewrite(novelId, null, chapter.chapterId)

        assertEquals(ChapterPhase.WAITING_CONFIRMATION, progress.phase, "改写必须停在人工门")
        assertTrue(progress.waitingForUser)
        // 批准前：不写 Draft、不改 Canonical；候选在 WorkingDraft 中
        assertEquals(1, app.draftRepository.listByChapter(chapter.chapterId).size)
        assertEquals("原正文", app.draftRepository.latestByChapter(chapter.chapterId)?.content)
        assertNull(app.canonicalRead.committedContent(chapter.chapterId))
        val pending = assertNotNull(app.writerGateway.loadContext(novelId, null, chapter.chapterId).pendingProposal)
        assertEquals("原正文", pending.baseContent)
        assertEquals("修订后的正文", pending.candidateContent)
        assertTrue(pending.diff.hasChanges)

        app.workflowFacade.approve(chapter.chapterId)

        val committed = assertNotNull(app.draftRepository.latestByChapter(chapter.chapterId))
        assertEquals("修订后的正文", committed.content)
        assertEquals(original.draftId, committed.previousDraftId, "新 Canonical 载体 lineage 指向原稿")
        assertEquals(DraftStatus.CONFIRMED, committed.status)
        assertEquals(DraftFormat.CONTROLLED_MARKDOWN, committed.format)
        assertEquals("原正文", app.draftRepository.getById(original.draftId)?.content, "原稿不被破坏")
        assertEquals("修订后的正文", app.canonicalRead.committedContent(chapter.chapterId))
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

    /* 7. 继续写在**没有产生新 Draft** 时不得把既有 legacy Draft 回溯迁移为受控 Markdown（FD-1） */
    @Test
    fun `continue writing does not restamp an existing legacy draft when no new draft is produced`() {
        val app = ApplicationContainer.open(analysisGateway = gateway())
        val novelId = app.novels.createOriginal(title = "测试仙侠")
        val chapter = app.chapters.createNextChapter(title = "第一章", novelId = novelId)

        // 驱动既有 Workflow 到 COMPLETED（人工门只**人工通过**，不自动批准）
        var guard = 0
        while (guard++ < 20 && app.workflowFacade.getChapterProgress(chapter.chapterId).phase != ChapterPhase.COMPLETED) {
            if (app.workflowFacade.getChapterProgress(chapter.chapterId).waitingForUser) {
                app.workflowFacade.approve(chapter.chapterId)
            } else {
                app.writerGateway.continueWriting(novelId, null, chapter.chapterId)
            }
        }
        val phase = app.workflowFacade.getChapterProgress(chapter.chapterId).phase
        assertEquals(ChapterPhase.COMPLETED, phase, "应能驱动到 COMPLETED，实际=$phase")

        // fixture：模拟 P20-P2 之前的库状态 —— 章节**最新** Draft 仍是 legacy（format=null）
        val legacy = draft(novelId, chapter.chapterId, "d-legacy-done", "旧正文（P14 legacy）", format = null)
            .copy(createdAt = Clock.System.now().plus(1.seconds))
        app.draftRepository.save(legacy)
        assertEquals(legacy.draftId, app.draftRepository.latestByChapter(chapter.chapterId)?.draftId)

        // COMPLETED 章节再次「继续写」：本次推进不产生新 Draft
        app.writerGateway.continueWriting(novelId, null, chapter.chapterId)

        val after = app.draftRepository.latestByChapter(chapter.chapterId)
        assertEquals(legacy.draftId, after?.draftId, "COMPLETED 章节继续写不得产生新 Draft")
        assertNull(after?.format, "无新 Draft 时不得把既有 legacy Draft 回溯迁移为受控 Markdown")
    }
}