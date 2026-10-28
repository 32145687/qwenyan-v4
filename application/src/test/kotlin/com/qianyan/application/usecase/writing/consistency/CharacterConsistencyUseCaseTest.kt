package com.qianyan.application.usecase.writing.consistency

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.qianyan.application.di.ApplicationContainer
import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.model.BaseNovelId
import com.qianyan.model.ChapterId
import com.qianyan.model.CharacterId
import com.qianyan.model.DraftId
import com.qianyan.model.IntentType
import com.qianyan.model.NovelId
import com.qianyan.model.ProjectId
import com.qianyan.model.VariantId
import com.qianyan.model.WorldId
import com.qianyan.model.WorldRuleId
import com.qianyan.model.character.Character
import com.qianyan.model.characterconsistency.CharacterConsistencyMissingInput
import com.qianyan.model.characterconsistency.CharacterConsistencyStatus
import com.qianyan.model.context.ContextPack
import com.qianyan.model.context.ContextRequest
import com.qianyan.model.context.ContextSourceKind
import com.qianyan.model.core.VariantContext
import com.qianyan.model.story.Chapter
import com.qianyan.model.world.WorldRule
import com.qianyan.model.writing.Draft
import com.qianyan.model.writing.DraftStatus
import com.qianyan.provider.ChatMessage
import com.qianyan.provider.ChatRole
import com.qianyan.provider.FinishReason
import com.qianyan.provider.LLMGateway
import com.qianyan.provider.ProviderResponse
import com.qianyan.provider.Usage
import com.qianyan.provider.impl.MockLLMGateway
import com.qianyan.storage.db.QianyanDbFactory
import java.io.File
import kotlinx.datetime.Instant
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** T-2B-2 · CharacterConsistencyUseCase（CanonicalRead + ContextPack DRAFT + 同快照 + 只读）。 */
class CharacterConsistencyUseCaseTest {

    private val now = Instant.fromEpochMilliseconds(1_700_000_000_000)
    private val characterId = CharacterId("c1")

    /* ---------------- scripted gateway ---------------- */

    private class ScriptedGateway {
        val prompts = mutableListOf<String>()
        var answer: String = PASS_JSON

        val gateway: LLMGateway = MockLLMGateway { request ->
            val system = request.messages.first { it.role == ChatRole.SYSTEM }.content
            val user = request.messages.first { it.role == ChatRole.USER }.content
            if ("StoryCharacterConsistencyAgent" in system) {
                prompts += user
                response(answer)
            } else {
                // 唯一一次 setup 的 WorldModelUpdate：空 facts 合法，且不产生 World Model 事实。
                response("""{"facts":[]}""")
            }
        }

        private fun response(body: String): ProviderResponse = ProviderResponse(
            message = ChatMessage(
                ChatRole.ASSISTANT,
                buildJsonObject { put("answer", body) }.toString(),
            ),
            usage = Usage(10, 10, 20),
            finishReason = FinishReason.STOP,
        )
    }

    private fun open(script: ScriptedGateway): ApplicationContainer =
        ApplicationContainer.open(analysisGateway = script.gateway)

    private data class Seed(
        val novelId: NovelId,
        val projectId: ProjectId,
        val prior: Chapter,
        val current: Chapter,
        val priorCanonical: Draft?,
        val currentDraft: Draft,
    )

    private fun seedNovel(
        app: ApplicationContainer,
        withCanonical: Boolean = true,
        currentContent: String = "当前正文C",
        addCharacter: Boolean = true,
    ): Seed {
        val novelId = app.novels.createOriginal("仙侠")
        val project = app.projects.openProject(novelId)
        return seedChapters(
            app = app,
            novelId = novelId,
            projectId = project.projectId,
            variantId = null,
            priorCanonicalId = DraftId("prior-canon"),
            currentDraftId = DraftId("current-draft"),
            priorContent = "前章正典A",
            currentContent = currentContent,
            withCanonical = withCanonical,
            addCharacter = addCharacter,
        )
    }

    private fun seedChapters(
        app: ApplicationContainer,
        novelId: NovelId,
        projectId: ProjectId,
        variantId: VariantId?,
        priorCanonicalId: DraftId,
        currentDraftId: DraftId,
        priorContent: String,
        currentContent: String,
        withCanonical: Boolean,
        addCharacter: Boolean,
    ): Seed {
        val prior = app.chapters.createNextChapter("第一章", novelId, variantId)
        val current = app.chapters.createNextChapter("第二章", novelId, variantId)
        val canonical = if (withCanonical) {
            val draft = Draft(
                draftId = priorCanonicalId,
                novelId = novelId,
                variantId = variantId,
                chapterId = prior.chapterId,
                content = priorContent,
                status = DraftStatus.CONFIRMED,
                createdAt = now,
                updatedAt = now,
            )
            app.draftRepository.save(draft)
            app.commits.commitAuthorSave(draft.draftId, priorContent)
            app.draftRepository.getById(draft.draftId)!!
        } else {
            null
        }
        val currentDraft = Draft(
            draftId = currentDraftId,
            novelId = novelId,
            variantId = variantId,
            chapterId = current.chapterId,
            content = currentContent,
            status = DraftStatus.WRITTEN,
            createdAt = now,
            updatedAt = now,
        )
        app.draftRepository.save(currentDraft)
        if (addCharacter) addCharacter(app, novelId, variantId)
        return Seed(novelId, projectId, prior, current, canonical, currentDraft)
    }

    private fun addCharacter(app: ApplicationContainer, novelId: NovelId, variantId: VariantId? = null) {
        val character = Character(
            characterId = characterId,
            novelId = novelId,
            variantId = variantId,
            scope = if (variantId == null) com.qianyan.model.VariantScope.ORIGINAL else com.qianyan.model.VariantScope.VARIANT,
            name = "林岚",
            personality = listOf("胆小"),
            goals = listOf("活下去"),
            fears = listOf("黑暗"),
            description = "青云宗弟子",
            createdAt = now,
            updatedAt = now,
        )
        if (variantId == null) {
            app.storyState.saveCharacter(character)
        } else {
            app.storyStateVariant.addCharacter(VariantContext(BaseNovelId(novelId.value), variantId), character)
        }
    }

    private fun addRule(app: ApplicationContainer, novelId: NovelId) {
        val rule = WorldRule(
            ruleId = WorldRuleId("r1"),
            worldId = WorldId("w1"),
            novelId = novelId,
            content = "修士不能飞行",
        )
        app.storyState.saveWorldRule(rule)
    }

    private fun addNewerUncommitted(app: ApplicationContainer, seed: Seed): Draft {
        val newer = Draft(
            draftId = DraftId("prior-uncommitted"),
            novelId = seed.novelId,
            chapterId = seed.prior.chapterId,
            content = "未提交B",
            status = DraftStatus.WRITTEN,
            createdAt = Instant.fromEpochMilliseconds(now.toEpochMilliseconds() + 1_000),
            updatedAt = Instant.fromEpochMilliseconds(now.toEpochMilliseconds() + 1_000),
        )
        app.draftRepository.save(newer)
        return newer
    }

    private fun pack(
        app: ApplicationContainer,
        projectId: ProjectId,
        chapterId: ChapterId,
        variantId: VariantId? = null,
    ): ContextPack = app.contextEngine.build(
        ContextRequest(
            projectId = projectId,
            purpose = IntentType.ANALYZE,
            focusChapterId = chapterId,
        ),
        variantId,
    )

    /* ---------------- V-1 ---------------- */

    @Test
    fun `V1 prior canonical comes from canonical read not latest draft`() {
        val script = ScriptedGateway().apply { answer = INSUFFICIENT_PLAN }
        val app = open(script)
        val seed = seedNovel(app)
        val canonical = seed.priorCanonical!!
        val newer = addNewerUncommitted(app, seed)
        assertEquals(newer.draftId, app.draftRepository.latestByChapter(seed.prior.chapterId)!!.draftId)

        val result = app.characterConsistency.check(
            novelId = seed.novelId,
            variantId = null,
            chapterId = seed.current.chapterId,
            contextPack = pack(app, seed.projectId, seed.current.chapterId),
        )

        val prompt = script.prompts.last()
        assertTrue(prompt.contains("前章正典A"), "应包含 Canonical 正文")
        assertFalse(prompt.contains("未提交B"), "不得包含 latestByChapter 的未提交稿")
        assertEquals(listOf(canonical.draftId), result.basis.priorCanonicalDraftIds)
        assertEquals(CharacterConsistencyStatus.INSUFFICIENT_EVIDENCE, result.status)
    }

    /* ---------------- V-2 ---------------- */

    @Test
    fun `V2 current draft comes from context pack and canonical gap is explicit`() {
        val longContent = "当前正文原文-" + "不截断".repeat(200)
        val script = ScriptedGateway().apply { answer = INSUFFICIENT_PRIOR }
        val app = open(script)
        val seed = seedNovel(app, withCanonical = false, currentContent = longContent)
        val contextPack = pack(app, seed.projectId, seed.current.chapterId)

        val draftItem = contextPack.items.firstOrNull {
            it.source == ContextSourceKind.DRAFT && it.ref?.chapterId == seed.current.chapterId
        }
        assertNotNull(draftItem, "ContextPack 必须包含当前章 DRAFT item")
        assertEquals(longContent, draftItem.content, "DRAFT item content 必须是原文且不截断")

        val result = app.characterConsistency.check(
            novelId = seed.novelId,
            variantId = null,
            chapterId = seed.current.chapterId,
            contextPack = contextPack,
        )

        val prompt = script.prompts.last()
        assertTrue(prompt.contains(longContent), "Agent prompt 必须包含完整当前正文")
        assertTrue(
            prompt.contains("priorCanonicalGapChapterIds=${seed.prior.chapterId.value}"),
            "gapChapterIds 必须进入 coverage",
        )
        assertTrue(prompt.contains("missingInputs=PRIOR_CANONICAL"), "missingInputs 必须包含 PRIOR_CANONICAL")
        assertTrue(result.missingInputs.contains(CharacterConsistencyMissingInput.PRIOR_CANONICAL))
        assertTrue(result.basis.priorCanonicalDraftIds.isEmpty())
    }

    /* ---------------- V-3 ---------------- */

    @Test
    fun `V3 world context is resolved once and the same variable feeds agent and validator`() {
        val script = ScriptedGateway().apply { answer = INSUFFICIENT_PLAN }
        val app = open(script)
        val seed = seedNovel(app)
        addRule(app, seed.novelId)

        val result = app.characterConsistency.check(
            novelId = seed.novelId,
            variantId = null,
            chapterId = seed.current.chapterId,
            contextPack = pack(app, seed.projectId, seed.current.chapterId),
        )

        val prompt = script.prompts.last()
        assertTrue(prompt.contains("林岚"), "Agent prompt 应含同一快照的 Character")
        assertTrue(prompt.contains("修士不能飞行"), "Agent prompt 应含同一快照的 WorldRule")
        assertEquals(1, result.basis.availableCharacterCount)
        assertEquals(1, result.basis.worldRuleCount)
        assertTrue(result.basis.worldContextUsed)

        val source = File(
            "src/main/kotlin/com/qianyan/application/usecase/writing/consistency/CharacterConsistencyUseCase.kt",
        ).readText()
        assertEquals(
            1,
            Regex("storyWorldContextResolver\\.resolve\\s*\\(").findAll(source).count(),
            "UseCase 必须只 resolve 一次",
        )
        assertTrue(source.contains("agent.check(input, worldContext)"), "Agent 必须接收 worldContext")
        assertTrue(source.contains("validator.validate(input, worldContext, raw)"), "Validator 必须接收同一 worldContext")
    }

    /* ---------------- V-4 ---------------- */

    @Test
    fun `V4 no draft commit world model or working draft writes`() {
        val script = ScriptedGateway().apply { answer = INSUFFICIENT_PLAN }
        val app = open(script)
        val seed = seedNovel(app)
        val contextPack = pack(app, seed.projectId, seed.current.chapterId)

        val currentDraftsBefore = app.draftRepository.listByChapter(seed.current.chapterId).size
        val priorDraftsBefore = app.draftRepository.listByChapter(seed.prior.chapterId).size
        val historyBefore = app.commits.history(seed.projectId).size
        val workingBefore = app.workingDrafts.list(seed.projectId).size

        app.characterConsistency.check(
            novelId = seed.novelId,
            variantId = null,
            chapterId = seed.current.chapterId,
            contextPack = contextPack,
        )

        assertEquals(currentDraftsBefore, app.draftRepository.listByChapter(seed.current.chapterId).size)
        assertEquals(priorDraftsBefore, app.draftRepository.listByChapter(seed.prior.chapterId).size)
        assertEquals(historyBefore, app.commits.history(seed.projectId).size)
        assertEquals(workingBefore, app.workingDrafts.list(seed.projectId).size)

        val useCaseSource = File(
            "src/main/kotlin/com/qianyan/application/usecase/writing/consistency/CharacterConsistencyUseCase.kt",
        ).readText()
        listOf(
            "DraftRepository",
            "CommitUseCases",
            "WorkingDraft",
            "KnowledgeUpdate",
            "draftRepository",
            "latestByChapter",
            ".save(",
            "runWorldModelUpdate",
        ).forEach { token ->
            assertFalse(token in useCaseSource, "UseCase 不得包含写路径 $token")
        }
        val agentSource = File(
            "src/main/kotlin/com/qianyan/application/usecase/writing/consistency/CharacterConsistencyAgent.kt",
        ).readText()
        listOf(
            "Repository",
            "CommitUseCases",
            "WorkingDraft",
            "KnowledgeUpdate",
            ".save(",
            "runWorldModelUpdate",
        ).forEach { token ->
            assertFalse(token in agentSource, "Agent 不得包含写路径 $token")
        }
    }

    /* ---------------- V-5 ---------------- */

    @Test
    fun `V5 prior canonical is filtered by variant`() {
        val script = ScriptedGateway().apply { answer = INSUFFICIENT_PLAN }
        val app = open(script)
        val novelId = app.novels.createOriginal("仙侠")
        val project = app.projects.openProject(novelId)
        val projectId = project.projectId
        val va = app.novels.createVariant(VariantContext(BaseNovelId(novelId.value), VariantId("va")), "Variant A")
        val vb = app.novels.createVariant(VariantContext(BaseNovelId(novelId.value), VariantId("vb")), "Variant B")
        val seedA = seedChapters(
            app = app, novelId = novelId, projectId = projectId, variantId = va,
            priorCanonicalId = DraftId("a-canon"), currentDraftId = DraftId("a-current"),
            priorContent = "A正典", currentContent = "A当前", withCanonical = true, addCharacter = false,
        )
        seedChapters(
            app = app, novelId = novelId, projectId = projectId, variantId = vb,
            priorCanonicalId = DraftId("b-canon"), currentDraftId = DraftId("b-current"),
            priorContent = "B正典", currentContent = "B当前", withCanonical = true, addCharacter = false,
        )

        val result = app.characterConsistency.check(
            novelId = novelId,
            variantId = va,
            chapterId = seedA.current.chapterId,
            contextPack = pack(app, projectId, seedA.current.chapterId, va),
        )

        val prompt = script.prompts.last()
        assertTrue(prompt.contains("A正典"), "应包含目标 Variant 的前章 Canonical")
        assertFalse(prompt.contains("B正典"), "不得包含其他 Variant 的前章 Canonical")
        assertEquals(listOf(seedA.priorCanonical!!.draftId), result.basis.priorCanonicalDraftIds)
    }

    /* ---------------- V-6 ---------------- */

    @Test
    fun `V6 invalid llm output and invalid result map to distinct typed errors`() {
        val script = ScriptedGateway()
        val app = open(script)
        val seed = seedNovel(app)
        val contextPack = pack(app, seed.projectId, seed.current.chapterId)

        script.answer = "not json"
        val invalidOutput = assertFailsWith<ApplicationException> {
            app.characterConsistency.check(seed.novelId, null, seed.current.chapterId, contextPack)
        }
        assertIs<ApplicationError.InvalidCharacterConsistencyOutput>(invalidOutput.error)

        script.answer = UNKNOWN_STATUS_JSON
        val unknownStatus = assertFailsWith<ApplicationException> {
            app.characterConsistency.check(seed.novelId, null, seed.current.chapterId, contextPack)
        }
        assertIs<ApplicationError.InvalidCharacterConsistencyOutput>(unknownStatus.error)

        script.answer = MISSING_FIELDS_JSON
        val missingFields = assertFailsWith<ApplicationException> {
            app.characterConsistency.check(seed.novelId, null, seed.current.chapterId, contextPack)
        }
        assertIs<ApplicationError.InvalidCharacterConsistencyOutput>(missingFields.error)

        script.answer = PASS_WITH_FINDING
        val invalidResult = assertFailsWith<ApplicationException> {
            app.characterConsistency.check(seed.novelId, null, seed.current.chapterId, contextPack)
        }
        assertIs<ApplicationError.InvalidCharacterConsistencyResult>(invalidResult.error)
    }

    /* ---------------- V-7 ---------------- */

    @Test
    fun `V7 valid mock output returns validator result`() {
        val script = ScriptedGateway().apply { answer = ISSUES_JSON }
        val app = open(script)
        val novelId = app.novels.createOriginal("仙侠")
        val project = app.projects.openProject(novelId)
        val projectId = project.projectId
        val current = app.chapters.createNextChapter("第一章", novelId)
        val currentDraft = Draft(
            draftId = DraftId("current-draft"),
            novelId = novelId,
            chapterId = current.chapterId,
            content = "当前正文：她很胆小。",
            status = DraftStatus.WRITTEN,
            createdAt = now,
            updatedAt = now,
        )
        app.draftRepository.save(currentDraft)
        addCharacter(app, novelId)

        val result = app.characterConsistency.check(
            novelId = novelId,
            variantId = null,
            chapterId = current.chapterId,
            contextPack = pack(app, projectId, current.chapterId),
        )

        assertEquals(CharacterConsistencyStatus.ISSUES, result.status)
        assertEquals(1, result.findings.size)
        assertFalse(result.truncated)
        assertEquals(characterId, result.checkedCharacters.single().characterId)
        assertEquals(currentDraft.draftId.value, result.findings[0].evidence[0].sourceId)
    }

    @Test
    fun `F4 context pack without draft returns insufficient evidence without writes or fallback`() {
        val script = ScriptedGateway().apply {
            answer = """{"status":"INSUFFICIENT_EVIDENCE","scope":"CURRENT_CHAPTER","findings":[],"checkedCharacters":[],"missingInputs":[],"summary":"current draft unavailable"}"""
        }
        val handle = QianyanDbFactory.open(JdbcSqliteDriver.IN_MEMORY)
        try {
            val app = ApplicationContainer.fromDriver(handle.driver, script.gateway)
            val seed = seedNovel(app)
            val completePack = pack(app, seed.projectId, seed.current.chapterId)
            assertTrue(completePack.items.any { it.source == ContextSourceKind.DRAFT })
            val contextPack = completePack.copy(
                items = completePack.items.filterNot { it.source == ContextSourceKind.DRAFT },
            )
            assertTrue(contextPack.items.none { it.source == ContextSourceKind.DRAFT })
            assertNotNull(app.writerUseCases.latestDraft(seed.current.chapterId))
            script.prompts.clear()

            val currentDraftsBefore = app.draftRepository.listByChapter(seed.current.chapterId)
            val priorDraftsBefore = app.draftRepository.listByChapter(seed.prior.chapterId)
            val historyBefore = app.commits.history(seed.projectId)
            val workingBefore = app.workingDrafts.list(seed.projectId)
            fun totalChanges(): Long = handle.driver.executeQuery(
                null,
                "SELECT total_changes()",
                { cursor ->
                    cursor.next()
                    QueryResult.Value(cursor.getLong(0)!!)
                },
                0,
            ).value
            val writesBefore = totalChanges()

            val result = app.characterConsistency.check(
                novelId = seed.novelId,
                variantId = null,
                chapterId = seed.current.chapterId,
                contextPack = contextPack,
            )

            assertEquals(CharacterConsistencyStatus.INSUFFICIENT_EVIDENCE, result.status)
            assertEquals(listOf(CharacterConsistencyMissingInput.CURRENT_DRAFT), result.missingInputs)
            assertEquals(null, result.basis.currentDraftId)
            assertEquals(listOf(assertNotNull(seed.priorCanonical).draftId), result.basis.priorCanonicalDraftIds)
            assertTrue(result.findings.isEmpty())
            assertFalse(script.prompts.single().contains(seed.currentDraft.content))
            assertEquals(currentDraftsBefore.size, app.draftRepository.listByChapter(seed.current.chapterId).size)
            assertEquals(priorDraftsBefore.size, app.draftRepository.listByChapter(seed.prior.chapterId).size)
            assertEquals(historyBefore.size, app.commits.history(seed.projectId).size)
            assertEquals(workingBefore.size, app.workingDrafts.list(seed.projectId).size)
            assertEquals(currentDraftsBefore, app.draftRepository.listByChapter(seed.current.chapterId))
            assertEquals(priorDraftsBefore, app.draftRepository.listByChapter(seed.prior.chapterId))
            assertEquals(historyBefore, app.commits.history(seed.projectId))
            assertEquals(workingBefore, app.workingDrafts.list(seed.projectId))
            assertEquals(writesBefore, totalChanges(), "UseCase 不得写入任何数据库表")
        } finally {
            handle.driver.close()
        }
    }

    private companion object {
        const val PASS_JSON =
            """{"status":"PASS","scope":"CURRENT_CHAPTER","findings":[],"checkedCharacters":[],"missingInputs":[],"summary":"ok"}"""

        const val UNKNOWN_STATUS_JSON =
            """{"status":"UNKNOWN","scope":"CURRENT_CHAPTER","findings":[],"checkedCharacters":[],"missingInputs":[],"summary":"bad"}"""

        const val MISSING_FIELDS_JSON =
            """{"status":"PASS","scope":"CURRENT_CHAPTER","findings":[],"checkedCharacters":[],"summary":"bad"}"""

        const val INSUFFICIENT_PLAN =
            """{"status":"INSUFFICIENT_EVIDENCE","scope":"CURRENT_CHAPTER","findings":[],"checkedCharacters":[],"missingInputs":["PLAN"],"summary":"plan missing"}"""

        const val INSUFFICIENT_PRIOR =
            """{"status":"INSUFFICIENT_EVIDENCE","scope":"CURRENT_CHAPTER","findings":[],"checkedCharacters":[],"missingInputs":["PRIOR_CANONICAL"],"summary":"prior canonical missing"}"""

        const val PASS_WITH_FINDING =
            """{"status":"PASS","scope":"CURRENT_CHAPTER","findings":[{"type":"TRAIT","severity":"WARNING","characterId":"c1","characterName":"林岚","message":"m","evidence":[],"confidence":"EXPLICIT"}],"checkedCharacters":[],"missingInputs":[],"summary":"bad"}"""

        const val ISSUES_JSON =
            """{"status":"ISSUES","scope":"CURRENT_CHAPTER","findings":[{"type":"TRAIT","severity":"WARNING","characterId":"c1","characterName":"林岚","message":"性格冲突","evidence":[{"quote":"她很胆小","sourceKind":"CURRENT_DRAFT","sourceId":"current-draft","field":"content"}],"confidence":"EXPLICIT"}],"checkedCharacters":[{"characterId":"c1","name":"林岚"}],"missingInputs":[],"summary":"1 issue"}"""
    }
}
