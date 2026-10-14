package com.qianyan.application.usecase.writing

import com.qianyan.application.di.ApplicationContainer
import com.qianyan.application.error.ApplicationException
import com.qianyan.application.usecase.workflow.ChapterPhase
import com.qianyan.engine.markdown.MarkdownBlock
import com.qianyan.model.ChapterId
import com.qianyan.model.DraftId
import com.qianyan.model.NovelId
import com.qianyan.model.commit.CommitErrorCodes
import com.qianyan.model.workflow.HumanDecision
import com.qianyan.model.workflow.WorkflowStepPhase
import com.qianyan.model.workflow.WorkflowStepStatus
import com.qianyan.model.workingdraft.WorkingDraftStatus
import com.qianyan.model.writing.Draft
import com.qianyan.model.writing.DraftFormat
import com.qianyan.model.writing.DraftStatus
import com.qianyan.provider.ChatMessage
import com.qianyan.provider.ChatRole
import com.qianyan.provider.FinishReason
import com.qianyan.provider.ProviderRequest
import com.qianyan.provider.ProviderResponse
import com.qianyan.provider.Usage
import com.qianyan.provider.impl.MockLLMGateway
import kotlinx.datetime.Clock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P1-02c+d · AI 写作链以 **WorkingDraft** 为工作产物，经 I9 Change Artifact + I10 正式提交为 Canonical。
 *
 * 覆盖真实 Application 链路（内存库 + Mock LLM，无网络）：
 *  - 生产入口 `writerGateway.continueWriting`（PLANNING → WRITING → CRITIQUE → [REVISION] → FINALIZE
 *    → CONFIRMATION 人工门）+ `workflowFacade.approve`（Desktop/Android 同一 seam）；
 *  - **AI 产出不写章节 Draft 表**：工作产物驻留 `WorkingDraftUseCases`（进程内工作区，独立 WorkingDraftId）；
 *  - 批准 = 人工明确批准 ⇒ `ChangeUseCases.prepare` + `CommitUseCases.commit(artifactId, 既有 Gate 凭据)`
 *    产生 Canonical 载体；`CanonicalReadUseCases` / Reader 读到已提交正文；
 *  - 与 `AuthorSaveCanonicalTest`（作者手改保存，same-row 载体）互补，两者语义不混。
 */
class AiWritingCanonicalTest {

    /* ---------------- 1. AI 产出落 WorkingDraft；不写章节 Draft 表、不改 Canonical ---------------- */

    @Test
    fun `ai writing drives to the human gate without touching drafts or canonical`() {
        val seen = mutableListOf<Pair<String, String>>()
        val app = ApplicationContainer.open(analysisGateway = aiGateway(seen))
        val novelId = app.novels.createOriginal(title = "测试书")
        val chapter = app.chapters.createNextChapter(title = "第一章", novelId = novelId)
        val projectId = app.projects.projectOf(novelId).projectId

        val progress = app.writerGateway.continueWriting(novelId, null, chapter.chapterId)

        assertEquals(ChapterPhase.WAITING_CONFIRMATION, progress.phase, "AI 写作链停在人工门")
        assertTrue(progress.waitingForUser, "人工门待决")
        // 核心：AI 产出不进章节 Draft 表
        assertEquals(0, app.draftRepository.listByChapter(chapter.chapterId).size, "AI 产出不得直写章节 Draft")
        // 工作产物：独立身份 / target / base / 状态
        val working = workingProducts(app, novelId, chapter.chapterId).single()
        assertEquals(AI_BODY, working.content)
        assertEquals(chapter.chapterId, working.target.chapterId)
        assertEquals(novelId, working.target.novelId)
        assertNull(working.target.variantId)
        assertNull(working.base.baseDraftId, "章节此前无 Draft ⇒ 基线为空（不伪造）")
        assertEquals(WorkingDraftStatus.VALIDATED, working.status, "FINALIZE 已把工作产物置为 VALIDATED")
        // CRITIQUE 消费工作产物正文（不是章节最新 Draft）
        assertTrue(
            seen.any { (agent, input) -> agent == "StoryCriticAgent" && input.contains(AI_BODY) },
            "CRITIQUE 输入应包含工作产物正文",
        )
        // Canonical 未变
        assertNull(app.canonicalRead.committedContent(chapter.chapterId), "中间产出不得进入 Canonical")
        assertTrue(app.commits.history(projectId).isEmpty(), "未批准前不得写 CommitHistory")
        assertTrue(app.reading.openChapter(novelId, null, chapter.chapterId).blocks.isEmpty())
        // Human Gate 与待审对象：Gate 不绑 Draft；绑定由 Step + resultReference 承载
        val wf = assertNotNull(app.workflowRepository.getWorkflowByActiveChapter(chapter.chapterId))
        val gate = assertNotNull(app.workflowRepository.getGate(assertNotNull(wf.pendingGateId)))
        assertNull(gate.draftId, "工作产物 Gate 不得把 WorkingDraftId 冒充 DraftId")
        val confirmStep = assertNotNull(app.workflowRepository.getStep(assertNotNull(gate.stepId)))
        assertEquals(WorkflowStepPhase.CONFIRMATION, confirmStep.phase)
        assertNull(confirmStep.resultReference, "待决 Step 不得提前写 resultReference（否则会被当作已完成）")
        val workingStep = app.workflowRepository.listSteps(wf.workflowId)
            .first { it.phase == WorkflowStepPhase.WRITING }
        assertEquals(WorkflowStepStatus.COMPLETED, workingStep.status)
        assertTrue(
            workingStep.resultReference!!.startsWith("WORKING:"),
            "WRITING 结果应为类型化工作引用（不是 DraftId）：${workingStep.resultReference}",
        )
        // Writer 投影：无 Draft，但有待审工作产物正文（不伪造 DraftId）
        val context = app.writerGateway.loadContext(novelId, null, chapter.chapterId)
        assertNull(context.draft, "AI 待审期间不得投影出章节 Draft")
        assertEquals(AI_BODY, context.pendingWorkingContent)
    }

    /* ---------------- 2. 批准 → Change/Artifact → 正式 Commit → Canonical ---------------- */

    @Test
    fun `approving the gate commits the working product as canonical`() {
        val app = ApplicationContainer.open(analysisGateway = aiGateway())
        val novelId = app.novels.createOriginal(title = "测试书")
        val chapter = app.chapters.createNextChapter(title = "第一章", novelId = novelId)
        val projectId = app.projects.projectOf(novelId).projectId
        app.writerGateway.continueWriting(novelId, null, chapter.chapterId)
        val workingId = workingProducts(app, novelId, chapter.chapterId).single().workingDraftId

        app.workflowFacade.approve(chapter.chapterId)

        assertEquals(AI_BODY, app.canonicalRead.committedContent(chapter.chapterId), "批准后 Canonical = 已批准正文")
        assertEquals(
            listOf(MarkdownBlock.Paragraph(AI_BODY)),
            app.reading.openChapter(novelId, null, chapter.chapterId).blocks,
            "Reader 读到已提交正文",
        )
        val history = app.commits.history(projectId)
        assertEquals(1, history.size, "批准产生且仅产生一条 CommitHistory")
        assertNotNull(history.first().artifactId, "P1-02c+d：AI 主链提交携带 Change Artifact（经 I9 提案）")
        assertEquals(AI_BODY, history.first().resultingContent)
        // Canonical 载体是 Commit 新建的 Draft（受控 Markdown）；工作产物提交后 DISCARDED
        val carrier = assertNotNull(app.draftRepository.getById(history.first().resultingDraftId))
        assertEquals(AI_BODY, carrier.content)
        assertEquals(DraftFormat.CONTROLLED_MARKDOWN, carrier.format, "P2：新 Canonical 载体标记受控 Markdown v1")
        assertEquals(DraftStatus.CONFIRMED, carrier.status, "提交后载体标记 CONFIRMED（KU 门槛）")
        assertTrue(carrier.draftId.value != workingId.value, "载体身份独立于工作产物（不把 WorkingDraftId 当 DraftId）")
        assertEquals(WorkingDraftStatus.DISCARDED, app.workingDrafts.get(workingId).status, "提交收尾：工作产物 DISCARDED")
        // Writer 投影恢复为可编辑的 Canonical 载体
        val context = app.writerGateway.loadContext(novelId, null, chapter.chapterId)
        assertEquals(carrier.draftId, assertNotNull(context.draft).draftId, "批准后编辑区读 Canonical 载体")
        assertNull(context.pendingWorkingContent, "工作产物已收尾 → 无待审内容")
    }

    /* ---------------- 3. 拒绝 / 重复批准 / 未批准直接提交 / 过期提案 ---------------- */

    @Test
    fun `rejected gate leaves canonical unchanged and writes no draft`() {
        val app = ApplicationContainer.open(analysisGateway = aiGateway())
        val novelId = app.novels.createOriginal(title = "测试书")
        val chapter = app.chapters.createNextChapter(title = "第一章", novelId = novelId)
        val projectId = app.projects.projectOf(novelId).projectId
        app.writerGateway.continueWriting(novelId, null, chapter.chapterId)
        val wf = assertNotNull(app.workflowRepository.getWorkflowByActiveChapter(chapter.chapterId))

        app.workflowService.rejectGate(assertNotNull(wf.pendingGateId), HumanDecision.REJECTED)

        assertNull(app.canonicalRead.committedContent(chapter.chapterId), "拒绝后 Canonical 不变")
        assertTrue(app.commits.history(projectId).isEmpty(), "拒绝不产生 CommitHistory")
        assertEquals(0, app.draftRepository.listByChapter(chapter.chapterId).size, "拒绝不得把候选内容写入章节 Draft 表")
        assertEquals(1, workingProducts(app, novelId, chapter.chapterId).size, "拒绝不丢弃工作产物（可再次决定）")
    }

    @Test
    fun `repeated approval does not duplicate the commit`() {
        val app = ApplicationContainer.open(analysisGateway = aiGateway())
        val novelId = app.novels.createOriginal(title = "测试书")
        val chapter = app.chapters.createNextChapter(title = "第一章", novelId = novelId)
        val projectId = app.projects.projectOf(novelId).projectId
        app.writerGateway.continueWriting(novelId, null, chapter.chapterId)

        app.workflowFacade.approve(chapter.chapterId)
        app.workflowFacade.approve(chapter.chapterId)

        assertEquals(1, app.commits.history(projectId).size, "重复批准幂等")
        assertEquals(AI_BODY, app.canonicalRead.committedContent(chapter.chapterId))
        assertEquals(1, app.draftRepository.listByChapter(chapter.chapterId).size, "不产生第二个 Canonical 载体")
    }

    @Test
    fun `commit without an approved gate is rejected and writes nothing`() {
        val app = ApplicationContainer.open(analysisGateway = aiGateway())
        val novelId = app.novels.createOriginal(title = "测试书")
        val chapter = app.chapters.createNextChapter(title = "第一章", novelId = novelId)
        val projectId = app.projects.projectOf(novelId).projectId
        app.writerGateway.continueWriting(novelId, null, chapter.chapterId)
        val working = workingProducts(app, novelId, chapter.chapterId).single()

        // 生产提交路径：WorkingDraft → validate → Change Artifact → commit(artifactId, approval)
        app.workingDrafts.validate(working.workingDraftId)
        val artifact = app.changes.prepare(working.workingDraftId)

        // Gate 仍为 PENDING：COMMIT_CANONICAL 在 I2 恒为 NeedsHuman ⇒ 必须拒绝
        val error = assertFailsWith<ApplicationException> {
            app.commits.commit(artifact.projectId, artifact.artifactId, approval = null)
        }
        assertTrue(
            error.message.orEmpty().contains(CommitErrorCodes.HUMAN_APPROVAL_REQUIRED),
            "应为人文确认凭据缺失：${error.message}",
        )
        assertTrue(app.commits.history(projectId).isEmpty(), "拒绝时零写入（无半成品 Canonical）")
        assertNull(app.canonicalRead.committedContent(chapter.chapterId))
        assertEquals(WorkingDraftStatus.VALIDATED, app.workingDrafts.get(working.workingDraftId).status)
    }

    @Test
    fun `superseded working artifact is rejected at commit`() {
        val app = ApplicationContainer.open(analysisGateway = aiGateway())
        val novelId = app.novels.createOriginal(title = "测试书")
        val chapter = app.chapters.createNextChapter(title = "第一章", novelId = novelId)
        val projectId = app.projects.projectOf(novelId).projectId
        app.writerGateway.continueWriting(novelId, null, chapter.chapterId)
        val working = workingProducts(app, novelId, chapter.chapterId).single()

        // Artifact 生成后工作产物被改写 ⇒ 不再代表最新结果 → 不得静默提交
        app.workingDrafts.validate(working.workingDraftId)
        val artifact = app.changes.prepare(working.workingDraftId)
        app.workingDrafts.replace(working.workingDraftId, "被他人改写的正文")

        val error = assertFailsWith<ApplicationException> {
            app.commits.commit(artifact.projectId, artifact.artifactId, approval = null)
        }
        assertTrue(app.commits.history(projectId).isEmpty(), "过期提案零写入：${error.message}")
    }

    /* ---------------- 4. Revision 写回同一工作产物，不进入 Canonical ---------------- */

    @Test
    fun `ai revision writes back into the same working product and never into drafts`() {
        val app = ApplicationContainer.open(analysisGateway = revisionGateway())
        val novelId = app.novels.createOriginal(title = "测试书")
        val chapter = app.chapters.createNextChapter(title = "第一章", novelId = novelId)
        val projectId = app.projects.projectOf(novelId).projectId

        app.writerGateway.continueWriting(novelId, null, chapter.chapterId)

        assertEquals(0, app.draftRepository.listByChapter(chapter.chapterId).size, "修订中间结果不得写入章节 Draft")
        val working = workingProducts(app, novelId, chapter.chapterId).single()
        assertEquals(REVISED_BODY, working.content, "修订写回同一工作产物（不新增工作产物）")
        val wf = assertNotNull(app.workflowRepository.getWorkflowByActiveChapter(chapter.chapterId))
        assertEquals(1, app.workflowRepository.listSteps(wf.workflowId).count { it.phase == WorkflowStepPhase.REVISION })
        assertNull(app.canonicalRead.committedContent(chapter.chapterId), "修订中间结果不得进入 Canonical")

        app.workflowFacade.approve(chapter.chapterId)
        assertEquals(REVISED_BODY, app.canonicalRead.committedContent(chapter.chapterId), "提交的是最终修订稿")
        assertEquals(1, app.commits.history(projectId).size)
    }

    /* ---------------- 5. legacy Draft 不被 AI 链静默迁移 / 覆盖 ---------------- */

    @Test
    fun `ai writing leaves an existing legacy draft untouched`() {
        val app = ApplicationContainer.open(analysisGateway = aiGateway())
        val novelId = app.novels.createOriginal(title = "测试书")
        val chapter = app.chapters.createNextChapter(title = "第一章", novelId = novelId)
        val legacy = Draft(
            draftId = DraftId("d-legacy"), novelId = novelId, chapterId = chapter.chapterId,
            content = "旧正文（legacy 纯文本）", format = DraftFormat.PLAIN_TEXT, status = DraftStatus.DRAFTING,
            createdAt = Clock.System.now(), updatedAt = Clock.System.now(),
        )
        app.draftRepository.save(legacy)

        app.writerGateway.continueWriting(novelId, null, chapter.chapterId)

        val untouched = assertNotNull(app.draftRepository.getById(legacy.draftId))
        assertEquals("旧正文（legacy 纯文本）", untouched.content, "AI 链不得改写既有 Draft 内容")
        assertNull(untouched.format, "不得把 legacy Draft 静默迁移为受控 Markdown")
        assertEquals(DraftStatus.DRAFTING, untouched.status)
        assertEquals(1, app.draftRepository.listByChapter(chapter.chapterId).size, "AI 产出不新增章节 Draft")
        assertEquals(
            legacy.draftId,
            workingProducts(app, novelId, chapter.chapterId).single().base.baseDraftId,
            "工作产物以既有 Draft 为 Diff 基线（不伪造）",
        )
    }

    /* ---------------- 6. KNOWLEDGE_UPDATE 不要求 AI 产出预先成为 Draft 行 ---------------- */

    @Test
    fun `knowledge update runs on the committed canonical after approval`() {
        val app = ApplicationContainer.open(analysisGateway = aiGateway())
        val novelId = app.novels.createOriginal(title = "测试书")
        val chapter = app.chapters.createNextChapter(title = "第一章", novelId = novelId)

        app.writerGateway.continueWriting(novelId, null, chapter.chapterId)
        assertEquals(0, app.draftRepository.listByChapter(chapter.chapterId).size, "KU 前章节无 Draft 行")

        app.workflowFacade.approve(chapter.chapterId)
        val after = app.workflowFacade.advance(chapter.chapterId)

        assertEquals(ChapterPhase.COMPLETED, after.phase, "批准后 KU 完成，Workflow 收口")
        assertTrue(
            app.storyWorldContextResolver.resolve(novelId).memories.any { it.contains("已突破金丹期") },
            "KU 在**已提交 Canonical** 上执行（不要求 AI 产出预先成为 Draft 行）",
        )
    }

    /* ---------------- 7. 作者保存路径不回归（v2 §9.②） ---------------- */

    @Test
    fun `author save after an ai commit appends a new commit record`() {
        val app = ApplicationContainer.open(analysisGateway = aiGateway())
        val novelId = app.novels.createOriginal(title = "测试书")
        val chapter = app.chapters.createNextChapter(title = "第一章", novelId = novelId)
        val projectId = app.projects.projectOf(novelId).projectId
        app.writerGateway.continueWriting(novelId, null, chapter.chapterId)
        app.workflowFacade.approve(chapter.chapterId)
        val carrier = assertNotNull(app.draftRepository.latestByChapter(chapter.chapterId))

        app.writerGateway.saveContent(carrier.draftId, "作者手改后的正文")

        val history = app.commits.history(projectId)
        assertEquals(2, history.size, "已提交 Draft 再次保存必须产生新的 Commit 记录（非原地改）")
        assertEquals("作者手改后的正文", app.canonicalRead.committedContent(chapter.chapterId))
        assertEquals(AI_BODY, history[1].previousContent, "新记录的基线是上一已提交正文")
        assertEquals(carrier.draftId, history[1].resultingDraftId, "作者保存走 same-row 载体（commitAuthorSave）")
    }

    /* ---------------- helpers ---------------- */

    private fun workingProducts(app: ApplicationContainer, novelId: NovelId, chapterId: ChapterId) =
        app.workingDrafts.list(app.projects.projectOf(novelId).projectId)
            .filter { it.target.chapterId == chapterId && !it.status.isTerminal }

    /** 完整 AI 写作链网关：Planning → Writing → Critique(PASS) → KnowledgeUpdate（可记录各 Agent 输入）。 */
    private fun aiGateway(seen: MutableList<Pair<String, String>>? = null): MockLLMGateway = MockLLMGateway { req: ProviderRequest ->
        val system = req.messages.first { it.role == ChatRole.SYSTEM }.content
        val input = req.messages.last { it.role == ChatRole.USER }.content
        val agent = when {
            "StoryPlannerAgent" in system -> "StoryPlannerAgent"
            "StoryWriterAgent" in system -> "StoryWriterAgent"
            "StoryCriticAgent" in system -> "StoryCriticAgent"
            "StoryRevisionAgent" in system -> "StoryRevisionAgent"
            "KnowledgeUpdateAgent" in system -> "KnowledgeUpdateAgent"
            else -> "other"
        }
        seen?.add(agent to input)
        val body = when (agent) {
            "StoryPlannerAgent" -> """{"chapterGoal":"推进主线"}"""
            "StoryWriterAgent" -> """{"content":"$AI_BODY"}"""
            "StoryCriticAgent" -> """{"passed":true}"""
            "KnowledgeUpdateAgent" -> """{"changes":[{"changeId":"k1","operation":"ADD","target":"主角","content":"已突破金丹期"}]}"""
            else -> """{"chapterGoal":"推进主线"}"""
        }
        ProviderResponse(
            message = ChatMessage(ChatRole.ASSISTANT, buildJsonObject { put("answer", body) }.toString()),
            usage = Usage(1, 1, 2),
            finishReason = FinishReason.STOP,
        )
    }

    /** 第一轮 Critique 不通过 → 触发 REVISION（写回同一工作产物）；之后通过。 */
    private fun revisionGateway(): MockLLMGateway {
        var critiques = 0
        return MockLLMGateway { req ->
            val system = req.messages.first { it.role == ChatRole.SYSTEM }.content
            val body = when {
                "StoryPlannerAgent" in system -> """{"chapterGoal":"推进主线"}"""
                "StoryWriterAgent" in system -> """{"content":"$AI_BODY"}"""
                "StoryRevisionAgent" in system -> """{"content":"$REVISED_BODY"}"""
                "StoryCriticAgent" in system ->
                    if (critiques++ == 0) """{"passed":false,"issues":[{"field":"body","severity":"ERROR","message":"开头平淡"}]}"""
                    else """{"passed":true}"""
                "KnowledgeUpdateAgent" in system -> """{"changes":[]}"""
                else -> """{"chapterGoal":"推进主线"}"""
            }
            ProviderResponse(
                message = ChatMessage(ChatRole.ASSISTANT, buildJsonObject { put("answer", body) }.toString()),
                usage = Usage(1, 1, 2),
                finishReason = FinishReason.STOP,
            )
        }
    }

    private companion object {
        const val AI_BODY = "AI 生成的正文"
        const val REVISED_BODY = "AI 修订后的正文"
    }
}
