package com.qianyan.application.usecase.vocabulary

import com.qianyan.application.di.ApplicationContainer
import com.qianyan.model.ActId
import com.qianyan.model.ArcId
import com.qianyan.model.BaseNovelId
import com.qianyan.model.ChapterPlanId
import com.qianyan.model.DraftId
import com.qianyan.model.IntentType
import com.qianyan.model.NovelId
import com.qianyan.model.PlanningScope
import com.qianyan.model.RequestId
import com.qianyan.model.VariantScope
import com.qianyan.model.VocabularyId
import com.qianyan.model.VocabularyRuleId
import com.qianyan.model.context.TargetKind
import com.qianyan.model.context.TargetRef
import com.qianyan.model.context.UserWritingRequest
import com.qianyan.model.story.ChapterPlan
import com.qianyan.model.task.TaskType
import com.qianyan.model.vocabulary.Vocabulary
import com.qianyan.model.vocabulary.VocabularyRule
import com.qianyan.model.vocabulary.VocabularyRuleSkipReason
import com.qianyan.model.vocabulary.VocabularyScopeLevel
import com.qianyan.model.writing.Draft
import com.qianyan.model.writing.DraftFormat
import com.qianyan.model.writing.DraftStatus
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
import kotlin.test.assertTrue

/**
 * T-O37-A · 词库规则进入两条 AI 生产路径（继续写 / 改写）。
 *
 * 断言的是**接线**而非引擎语义（引擎语义见 [VocabularyRuleEngineTest]）：
 *  - 有规则：写入 WorkingDraft 的正文已是替换后文本，命中随候选携带；
 *  - 无规则：正文逐字节不变（不引入额外空白/归一化）；
 *  - 改写：候选投影与批准后的 Canonical 都是替换后文本（Change Layer 只接收应用后内容）；
 *  - 归属：其他作品的规则不得跨作品生效。
 */
class VocabularyRuleWiringTest {

    /* ---------- 继续写 ---------- */

    @Test
    fun `continue writing applies rules before creating the working draft`() {
        val app = container()
        val novelId = app.novels.createOriginal(title = "测试仙侠")
        seedRule(app, novelId, id = "rule-gu", from = "古碑", to = "断碑")
        val id = app.tasks.create(TaskType.WRITING)

        val working = app.writingExecution.executeIntoWorkingDraft(id, request(novelId), plan(app, novelId))

        assertEquals("女主推开断碑，雷劫降临。", working.content)
        val hit = working.ruleHits.single()
        assertEquals(VocabularyRuleId("rule-gu"), hit.ruleId)
        assertEquals("古碑", hit.from)
        assertEquals("断碑", hit.to)
        assertEquals(1, hit.count)
        assertEquals(4, hit.firstIndex)
    }

    @Test
    fun `continue writing without rules keeps the writer output byte identical`() {
        val app = container()
        val novelId = app.novels.createOriginal(title = "测试仙侠")
        // 另一作品的同名规则：归属过滤后不得生效。
        val otherNovel = app.novels.createOriginal(title = "别的书")
        seedRule(app, otherNovel, id = "rule-other", from = "古碑", to = "断碑")
        val id = app.tasks.create(TaskType.WRITING)

        val working = app.writingExecution.executeIntoWorkingDraft(id, request(novelId), plan(app, novelId))

        assertEquals("女主推开古碑，雷劫降临。", working.content)
        assertTrue(working.ruleHits.isEmpty())
    }

    /* ---------- 改写 ---------- */

    @Test
    fun `rewrite applies rules to the candidate and the approved canonical`() {
        val app = ApplicationContainer.open(analysisGateway = gateway())
        val (novelId, chapterId) = seedRewrite(app)
        seedRule(app, novelId, id = "rule-revise", from = "修订", to = "润色")

        app.writerGateway.rewrite(novelId, null, chapterId)

        val proposal = assertNotNull(app.writerGateway.loadContext(novelId, null, chapterId).pendingProposal)
        assertEquals("AI 润色正文 v1", proposal.candidateContent)
        val hit = proposal.ruleHits.single()
        assertEquals("修订", hit.from)
        assertEquals("润色", hit.to)
        assertEquals(1, hit.count)

        // 批准后 Canonical = 规则应用后的候选（Change Layer 不二次加工）。
        app.workflowFacade.approve(chapterId)
        assertEquals("AI 润色正文 v1", app.canonicalRead.committedContent(chapterId))
    }

    @Test
    fun `rewrite without rules keeps the revision output unchanged`() {
        val app = ApplicationContainer.open(analysisGateway = gateway())
        val (novelId, chapterId) = seedRewrite(app)

        app.writerGateway.rewrite(novelId, null, chapterId)

        val proposal = assertNotNull(app.writerGateway.loadContext(novelId, null, chapterId).pendingProposal)
        assertEquals("AI 修订正文 v1", proposal.candidateContent)
        assertTrue(proposal.ruleHits.isEmpty())
    }

    @Test
    fun `manual author save never goes through vocabulary rules`() {
        val app = ApplicationContainer.open(analysisGateway = gateway())
        val (novelId, _) = seedRewrite(app)
        seedRule(app, novelId, id = "rule-save", from = "原正文", to = "不该出现")

        val saved = app.writerGateway.saveContent(DraftId("wiring-base"), "作者手存原正文")

        assertEquals("作者手存原正文", saved.content, "作者手存是 Change Layer 入口，不经词库规则")
        assertTrue(app.vocabularyRules.activeFor(novelId, null).isNotEmpty(), "规则确实存在于该作品")
    }

    /* ---------- 跳过可见（T-O37-A2） ---------- */

    @Test
    fun `non deterministic rule is visible as a skip and keeps the writer output unchanged`() {
        val app = container()
        val novelId = app.novels.createOriginal(title = "测试仙侠")
        seedRule(app, novelId, id = "rule-nondet", from = "古碑", to = "断碑", deterministicOnly = false)
        val id = app.tasks.create(TaskType.WRITING)

        val working = app.writingExecution.executeIntoWorkingDraft(id, request(novelId), plan(app, novelId))

        assertEquals("女主推开古碑，雷劫降临。", working.content, "非确定性规则不执行，正文不变")
        assertTrue(working.ruleHits.isEmpty())
        val skip = working.ruleSkips.single()
        assertEquals(VocabularyRuleId("rule-nondet"), skip.ruleId, "跳过的是规则本身，不是命中")
        assertEquals(VocabularyRuleSkipReason.NON_DETERMINISTIC, skip.reason)
    }

    @Test
    fun `global rule enters the candidate set and is visible as out of scope`() {
        val app = container()
        val novelId = app.novels.createOriginal(title = "测试仙侠")
        seedRule(
            app,
            novelId = null,
            id = "rule-global",
            from = "古碑",
            to = "断碑",
            scopeLevel = VocabularyScopeLevel.GLOBAL,
        )
        // 先证明：GLOBAL 行确实进入了候选集（不是被 activeFor 静默过滤后再猜）
        assertTrue(
            app.vocabularyRules.activeFor(novelId, null).any { it.ruleId == VocabularyRuleId("rule-global") },
            "GLOBAL 规则必须进入候选集",
        )
        val id = app.tasks.create(TaskType.WRITING)

        val working = app.writingExecution.executeIntoWorkingDraft(id, request(novelId), plan(app, novelId))

        assertEquals("女主推开古碑，雷劫降临。", working.content, "OUT_OF_SCOPE 不执行，正文不变")
        assertTrue(working.ruleHits.isEmpty())
        assertEquals(VocabularyRuleSkipReason.OUT_OF_SCOPE, working.ruleSkips.single().reason)
    }

    @Test
    fun `chain risk rule is visible as a skip`() {
        val app = container()
        val novelId = app.novels.createOriginal(title = "测试仙侠")
        seedRule(app, novelId, id = "rule-chain-a", from = "古碑", to = "断碑")
        seedRule(app, novelId, id = "rule-chain-b", from = "断碑", to = "残碑")
        val id = app.tasks.create(TaskType.WRITING)

        val working = app.writingExecution.executeIntoWorkingDraft(id, request(novelId), plan(app, novelId))

        assertEquals("女主推开古碑，雷劫降临。", working.content, "链式风险规则不执行，正文不变")
        val skip = working.ruleSkips.single()
        assertEquals(VocabularyRuleId("rule-chain-a"), skip.ruleId, "a.to 命中 b.from")
        assertEquals(VocabularyRuleSkipReason.CHAIN_RISK, skip.reason)
    }

    @Test
    fun `pending proposal carries the same rule skips as the working draft`() {
        val app = ApplicationContainer.open(analysisGateway = gateway())
        val (novelId, chapterId) = seedRewrite(app)
        seedRule(app, novelId, id = "rule-nondet-rewrite", from = "修订", to = "润色", deterministicOnly = false)

        app.writerGateway.rewrite(novelId, null, chapterId)

        val projectId = app.projects.projectOf(novelId).projectId
        val working = app.workingDrafts.list(projectId).single()
        val proposal = assertNotNull(app.writerGateway.loadContext(novelId, null, chapterId).pendingProposal)
        assertEquals(working.ruleSkips, proposal.ruleSkips, "提案与工作产物的跳过记录必须一致")
        assertEquals(VocabularyRuleSkipReason.NON_DETERMINISTIC, proposal.ruleSkips.single().reason)
    }

    /* ---------- fixtures ---------- */

    private fun seedRule(
        app: ApplicationContainer,
        novelId: NovelId?,
        id: String,
        from: String,
        to: String,
        scopeLevel: VocabularyScopeLevel = VocabularyScopeLevel.NOVEL,
        deterministicOnly: Boolean = true,
    ) {
        val vocabularyId = VocabularyId("vocab-$id")
        app.vocabularyRepository.saveVocabulary(
            Vocabulary(
                vocabularyId = vocabularyId,
                novelId = novelId,
                scopeLevel = scopeLevel,
                name = "接线测试词库",
            ),
        )
        app.vocabularyRepository.saveRule(
            VocabularyRule(
                ruleId = VocabularyRuleId(id),
                vocabularyId = vocabularyId,
                novelId = novelId,
                scopeLevel = scopeLevel,
                from = from,
                to = to,
                deterministicOnly = deterministicOnly,
            ),
        )
    }

    private fun container(): ApplicationContainer {
        val content = buildJsonObject {
            put("answer", """{"content":"女主推开古碑，雷劫降临。"}""")
        }.toString()
        val gateway = MockLLMGateway { _ ->
            ProviderResponse(
                message = ChatMessage(ChatRole.ASSISTANT, content),
                usage = Usage(10, 10, 20),
                finishReason = FinishReason.STOP,
            )
        }
        return ApplicationContainer.open(analysisGateway = gateway)
    }

    private fun request(novelId: NovelId) = UserWritingRequest(
        requestId = RequestId("req-write"),
        intentType = IntentType.CONTINUE,
        target = TargetRef(TargetKind.CHAPTER, null),
        planningScope = PlanningScope.CHAPTER,
        baseNovelId = BaseNovelId(novelId.value),
    )

    private fun plan(app: ApplicationContainer, novelId: NovelId): ChapterPlan {
        val chapterId = app.chapters.createNextChapter("第一章", novelId).chapterId
        return ChapterPlan(
            chapterPlanId = ChapterPlanId("plan-1"),
            chapterId = chapterId,
            arcId = ArcId("arc-1"),
            actId = ActId("act-1"),
            novelId = novelId,
            scope = VariantScope.ORIGINAL,
            chapterGoal = "救下女主并揭开秘辛",
            expectedEvents = listOf("发现古碑"),
            endingHook = "门被敲响",
        )
    }

    private fun seedRewrite(app: ApplicationContainer): Pair<NovelId, com.qianyan.model.ChapterId> {
        val novelId = app.novels.createOriginal(title = "改写接线测试书")
        app.projects.openProject(novelId)
        val chapter = app.chapters.createNextChapter(title = "第一章", novelId = novelId)
        val now = Clock.System.now()
        app.draftRepository.save(
            Draft(
                draftId = DraftId("wiring-base"),
                novelId = novelId,
                variantId = null,
                scope = VariantScope.ORIGINAL,
                chapterId = chapter.chapterId,
                content = "原正文",
                format = DraftFormat.CONTROLLED_MARKDOWN,
                status = DraftStatus.DRAFTING,
                createdAt = now,
                updatedAt = now,
            ),
        )
        return novelId to chapter.chapterId
    }

    private fun gateway(): MockLLMGateway = MockLLMGateway { req ->
        val system = req.messages.first { it.role == ChatRole.SYSTEM }.content
        val body = when {
            "StoryWriterAgent" in system -> """{"content":"AI 生成正文"}"""
            "StoryRevisionAgent" in system -> """{"content":"AI 修订正文 v1"}"""
            "StoryCriticAgent" in system -> """{"passed":true}"""
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
