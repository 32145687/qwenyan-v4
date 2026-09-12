package com.qianyan.application.usecase.writing.knowledgeupdate

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.qianyan.application.di.ApplicationContainer
import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.model.ActId
import com.qianyan.model.ArcId
import com.qianyan.model.BaseNovelId
import com.qianyan.model.ChapterId
import com.qianyan.model.ChapterPlanId
import com.qianyan.model.DraftId
import com.qianyan.model.IntentType
import com.qianyan.model.NovelId
import com.qianyan.model.PlanningScope
import com.qianyan.model.ProjectId
import com.qianyan.model.ProjectSource
import com.qianyan.model.ProjectStatus
import com.qianyan.model.RequestId
import com.qianyan.model.task.TaskType
import com.qianyan.model.VariantId
import com.qianyan.model.VariantScope
import com.qianyan.model.context.TargetKind
import com.qianyan.model.context.TargetRef
import com.qianyan.model.context.UserWritingRequest
import com.qianyan.model.core.Novel
import com.qianyan.model.knowledge.KnowledgeOperation
import com.qianyan.model.story.ChapterPlan
import com.qianyan.model.writing.Draft
import com.qianyan.model.writing.DraftStatus
import com.qianyan.provider.ChatMessage
import com.qianyan.provider.ChatRole
import com.qianyan.provider.FinishReason
import com.qianyan.provider.ProviderException
import com.qianyan.provider.ProviderResponse
import com.qianyan.provider.Usage
import com.qianyan.provider.impl.MockLLMGateway
import com.qianyan.storage.db.QianyanDbFactory
import com.qianyan.storage.db.QianyanDbHandle
import kotlinx.datetime.Instant
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import kotlin.io.path.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * P11.5 Knowledge Update Execution 测试（Pipeline + Persistence + Reopen）。
 * 覆盖：合法候选落地 / Agent-Parser 失败 / Provider 失败 / immutable reject / writing→critique→knowledge update
 * / close→reopen 状态一致。全程 Mock LLM，无网络。
 */
class KnowledgeUpdateExecutionTest {

    private var llmCalls = 0

    private fun defaultCandidatesJson() =
        """{"changes":[{"changeId":"k1","operation":"ADD","target":"主角","content":"已突破金丹期"}]}"""

    private fun gateway(knowledgeJson: String = defaultCandidatesJson(), throwing: Boolean = false): MockLLMGateway =
        MockLLMGateway { req ->
            llmCalls++
            if (throwing) throw ProviderException.Timeout("timeout")
            val agent = req.messages.first { it.role == ChatRole.SYSTEM }.content
            val body = when {
                "KnowledgeUpdateAgent" in agent -> knowledgeJson
                "StoryCriticAgent" in agent -> """{"passed":true}"""
                else -> """{"content":"女主推开古碑。"}"""
            }
            ProviderResponse(
                message = ChatMessage(ChatRole.ASSISTANT, buildJsonObject { put("answer", body) }.toString()),
                usage = Usage(10, 10, 20),
                finishReason = FinishReason.STOP,
            )
        }

    private fun request(novelId: NovelId) = UserWritingRequest(
        requestId = RequestId("req-ku"),
        intentType = IntentType.CONTINUE,
        target = TargetRef(TargetKind.CHAPTER, null),
        planningScope = PlanningScope.CHAPTER,
        baseNovelId = BaseNovelId(novelId.value),
    )

    private fun plan(novelId: NovelId) = ChapterPlan(
        chapterPlanId = ChapterPlanId("plan-ku"),
        chapterId = ChapterId("ch-ku"),
        arcId = ArcId("arc-ku"),
        actId = ActId("act-ku"),
        novelId = novelId,
        scope = VariantScope.ORIGINAL,
        chapterGoal = "揭开秘辛",
    )

    private fun draft() = Draft(
        draftId = DraftId("d-ku-1"),
        novelId = NovelId("novel-ku"),
        variantId = VariantId("v-ku"),
        scope = VariantScope.VARIANT,
        content = "主角推开古碑，天地异变。",
        status = DraftStatus.WRITTEN,
        createdAt = Instant.parse("2026-01-01T00:00:00Z"),
        updatedAt = Instant.parse("2026-01-01T00:00:00Z"),
    )

    /** P12.1.4：构造一个已 CONFIRMED 并持久化的 Final Draft，供 KU 过门禁。 */
    private fun confirmedDraft(app: ApplicationContainer): Draft {
        seedNovel(app)
        val d = draft().copy(status = DraftStatus.CONFIRMED)
        app.draftRepository.save(d)
        return d
    }

    /** P12.4-M01 外键：ChapterDraft.novel_id → Novel；这些测试固定用 novel-ku，须先建父 Novel。 */
    private fun seedNovel(app: ApplicationContainer) {
        app.novelRepository.createOriginal(
            Novel(
                novelId = NovelId("novel-ku"),
                projectId = ProjectId("proj-ku"),
                title = "知识库原著",
                source = ProjectSource.ORIGINAL_NOVEL,
                scope = VariantScope.ORIGINAL,
                status = ProjectStatus.DRAFT,
                createdAt = Instant.parse("2026-01-01T00:00:00Z"),
                updatedAt = Instant.parse("2026-01-01T00:00:00Z"),
            ),
        )
    }

    /* 合法候选：确定性落地 → Memory(layer=WRITING) + KNOWN_UPDATE Checkpoint */
    @Test
    fun `valid knowledge update persists and checkpoints`() {
        val app = ApplicationContainer.open(analysisGateway = gateway())
        val id = app.tasks.create(TaskType.WRITING)
        // P12.1.4：KU 需要已 CONFIRMED 的 Final Draft（先持久化、再确认）。
        val d = confirmedDraft(app)

        val outcome = app.taskRunner.executeKnowledgeUpdate(id, d)

        assertEquals(1, outcome.validated.accepted.size)
        assertEquals(KnowledgeOperation.ADD, outcome.validated.accepted[0].operation)
        assertEquals(0, outcome.validated.rejected.size)
        assertEquals(1, outcome.applied.size)

        // Memory 已沉淀
        val memories = app.memories.query(com.qianyan.model.core.VariantContext(
            baseNovelId = BaseNovelId("novel-ku"), variantId = VariantId("v-ku"), scope = VariantScope.VARIANT))
        assertTrue(memories.any { it.content.contains("已突破金丹期") })

        // KNOWN_UPDATE Checkpoint 可恢复
        val cp = app.tasks.restoreCheckpoint(id)
        assertEquals(KnowledgeUpdateSnapshot.STAGE, cp.stage)
        val restored = app.knowledgeUpdate.validatedFrom(cp)
        assertNotNull(restored)
        assertEquals(1, restored.accepted.size)
    }

    /* immutable reject：Original scope 的 UPDATE 不落地、不写 Memory、记录在 rejected */
    @Test
    fun `immutable canon update is rejected and not applied`() {
        val ku = """{"changes":[{"changeId":"r1","operation":"UPDATE","target":"主角","content":"改境界"}]}"""
        val app = ApplicationContainer.open(analysisGateway = gateway(ku))
        val id = app.tasks.create(TaskType.WRITING)

        // P12.1.4：KU 需要已 CONFIRMED 的 Final Draft。
        seedNovel(app)
        val originalDraft = draft().copy(variantId = null, scope = VariantScope.ORIGINAL, status = DraftStatus.CONFIRMED)
        app.draftRepository.save(originalDraft)
        val outcome = app.taskRunner.executeKnowledgeUpdate(id, originalDraft)

        assertEquals(0, outcome.validated.accepted.size)
        assertEquals(1, outcome.validated.rejected.size)
        assertTrue(outcome.validated.rejected[0].reason.contains("immutable"))
        assertEquals(0, outcome.applied.size)
        // 无 Memory 沉淀（不覆盖 canon）
        val memories = app.memories.query(com.qianyan.model.core.VariantContext(
            baseNovelId = BaseNovelId("novel-ku"), variantId = null, scope = VariantScope.ORIGINAL))
        assertTrue(memories.none { it.content.contains("【知识更新·UPDATE】") || it.content.contains("改境界") })
    }

    /* 非法候选（非 JSON）→ InvalidKnowledgeUpdateOutput */
    @Test
    fun `invalid knowledge output fails typed`() {
        val app = ApplicationContainer.open(analysisGateway = gateway("这不是 JSON"))
        val id = app.tasks.create(TaskType.WRITING)
        val ex = assertFailsWith<ApplicationException> { app.taskRunner.executeKnowledgeUpdate(id, confirmedDraft(app)) }
        assertIs<ApplicationError.InvalidKnowledgeUpdateOutput>(ex.error)
    }

    /* Provider 故障 → ProviderUnavailable */
    @Test
    fun `provider failure fails typed`() {
        llmCalls = 0
        val app = ApplicationContainer.open(analysisGateway = gateway(throwing = true))
        val id = app.tasks.create(TaskType.WRITING)
        val ex = assertFailsWith<ApplicationException> { app.taskRunner.executeKnowledgeUpdate(id, confirmedDraft(app)) }
        assertIs<ApplicationError.ProviderUnavailable>(ex.error)
    }

    /* 非 WRITING Task → InvalidOperation */
    @Test
    fun `non writing task rejected`() {
        val app = ApplicationContainer.open(analysisGateway = gateway())
        val id = app.tasks.create(TaskType.PLANNING)
        val ex = assertFailsWith<ApplicationException> { app.taskRunner.executeKnowledgeUpdate(id, draft()) }
        assertIs<ApplicationError.InvalidOperation>(ex.error)
    }

    /* 全链：Writing → Critique → Knowledge Update，且 reopen 后一致 */
    @Test
    fun `writing critique knowledge update pipeline survives reopen`() {
        val tmp = Files.createTempFile("qianyan-ku", ".db").toString()
        val handles = mutableListOf<QianyanDbHandle>()
        try {
            llmCalls = 0
            val open = { containerFile("jdbc:sqlite:$tmp", handles) }
            var app = open()
            val novelId = app.novels.createOriginal(title = "测试")
            val id = app.tasks.create(TaskType.WRITING)

            // Writing: Draft v1
            val v1 = app.taskRunner.executeWriting(id, request(novelId), plan(novelId))
            assertEquals(DraftStatus.WRITTEN, v1.status)

            // Critique: ValidationResult + CRITIQUE checkpoint
            val critique = app.taskRunner.executeCritique(id, v1)
            assertNotNull(critique)

            // P12.1.4：KU 前需最终稿 + 确认（confirm 不调用 LLM，llmCalls 保持 3）。
            app.draftRepository.save(v1.copy(status = DraftStatus.FINAL))
            val confirmed = app.confirmations.confirmFinalDraft(v1.draftId, v1.novelId, v1.variantId)
            assertEquals(DraftStatus.CONFIRMED, confirmed.status)

            // Knowledge Update: 候选落地 + KNOWN_UPDATE checkpoint
            val outcome = app.taskRunner.executeKnowledgeUpdate(id, confirmed)
            assertEquals(1, outcome.applied.size)
            val stages = app.tasks.findCheckpoints(id).map { it.stage }
            assertEquals(listOf("WRITING", "CRITIQUE", "KNOWLEDGE_UPDATE"), stages)
            assertEquals("KNOWLEDGE_UPDATE", app.tasks.restoreCheckpoint(id).stage)

            // Mock LTM 调用可计数 = write + critique + knowledge_update = 3
            assertEquals(3, llmCalls)

            // reopen：Memory 沉淀与 checkpoint 一致
            app = open()
            val memories = app.memories.query(com.qianyan.model.core.VariantContext(
                baseNovelId = BaseNovelId(novelId.value), variantId = v1.variantId, scope = v1.scope))
            assertTrue(memories.any { it.content.contains("【知识更新·ADD】") })
            assertEquals("KNOWLEDGE_UPDATE", app.tasks.restoreCheckpoint(id).stage)
            val restored = app.knowledgeUpdate.validatedFrom(app.tasks.restoreCheckpoint(id))
            assertNotNull(restored)
            assertEquals(1, restored.accepted.size)
        } finally {
            handles.forEach { (it.driver as JdbcSqliteDriver?)?.getConnection()?.close() }
            Files.deleteIfExists(Path(tmp))
        }
    }

    private fun containerFile(url: String, handles: MutableList<QianyanDbHandle>, g: MockLLMGateway = gateway()): ApplicationContainer {
        val handle = QianyanDbFactory.open(url)
        handles += handle
        return ApplicationContainer.fromDriver(handle.driver, analysisGateway = g)
    }
}