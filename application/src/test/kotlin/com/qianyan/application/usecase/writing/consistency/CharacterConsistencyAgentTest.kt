package com.qianyan.application.usecase.writing.consistency

import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.application.error.ErrorMapper
import com.qianyan.model.ActId
import com.qianyan.model.ArcId
import com.qianyan.model.ChapterId
import com.qianyan.model.ChapterPlanId
import com.qianyan.model.CharacterId
import com.qianyan.model.DraftId
import com.qianyan.model.EventId
import com.qianyan.model.ForeshadowingId
import com.qianyan.model.KnowledgeId
import com.qianyan.model.LocationId
import com.qianyan.model.NovelId
import com.qianyan.model.StateId
import com.qianyan.model.TimelineEntryId
import com.qianyan.model.WorldId
import com.qianyan.model.WorldRuleId
import com.qianyan.model.character.AbilitySnapshot
import com.qianyan.model.character.Character
import com.qianyan.model.character.CharacterState
import com.qianyan.model.character.EmotionalState
import com.qianyan.model.character.PhysicalState
import com.qianyan.model.character.RelationshipSnapshot
import com.qianyan.model.character.SnapshotType
import com.qianyan.model.characterconsistency.CharacterConsistencyScope
import com.qianyan.model.characterconsistency.CharacterConsistencyStatus
import com.qianyan.model.context.StoryWorldContext
import com.qianyan.model.story.ChapterPlan
import com.qianyan.model.story.Foreshadow
import com.qianyan.model.timeline.Event
import com.qianyan.model.timeline.TimelineEntry
import com.qianyan.model.timeline.TimelinePosition
import com.qianyan.model.world.WorldRule
import com.qianyan.provider.ChatMessage
import com.qianyan.provider.ChatRole
import com.qianyan.provider.FinishReason
import com.qianyan.provider.ProviderResponse
import com.qianyan.provider.Usage
import com.qianyan.provider.impl.MockLLMGateway
import kotlinx.datetime.Instant
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** T-2B-2 · CharacterConsistencyAgent（纯 LLM → 严格 Parser；不做证据校验）。 */
class CharacterConsistencyAgentTest {

    private val now = Instant.fromEpochMilliseconds(1_700_000_000_000)
    private val novelId = NovelId("n1")
    private val chapterId = ChapterId("ch1")
    private val currentDraftId = DraftId("d1")
    private val priorDraftId = DraftId("d0")
    private val priorChapterId = ChapterId("ch0")
    private val characterId = CharacterId("c1")
    private val stateId = StateId("s1")
    private val ruleId = WorldRuleId("r1")
    private val eventId = EventId("e1")
    private val timelineId = TimelineEntryId("t1")
    private val foreshadowId = ForeshadowingId("f1")
    private val planId = ChapterPlanId("p1")
    private val currentContent = "当前正文：她很胆小。"

    private fun character(): Character = Character(
        characterId = characterId,
        novelId = novelId,
        name = "林岚",
        personality = listOf("胆小"),
        goals = listOf("活下去"),
        fears = listOf("黑暗"),
        description = "青云宗弟子",
        createdAt = now,
        updatedAt = now,
    )

    private fun state(): CharacterState = CharacterState(
        id = stateId,
        characterId = characterId,
        novelId = novelId,
        snapshotType = SnapshotType.CHAPTER_END,
        location = LocationId("loc1"),
        physicalState = PhysicalState("受伤", "左臂"),
        emotionalState = EmotionalState("恐惧", 0.8f),
        currentGoal = "找到真相",
        currentMotivation = "救妹妹",
        relationships = listOf(RelationshipSnapshot(CharacterId("c2"), "师徒")),
        knownFacts = listOf(KnowledgeId("k1")),
        unknownFacts = listOf(KnowledgeId("k2")),
        abilities = listOf(AbilitySnapshot("剑术", "御剑")),
        createdAt = now,
    )

    private fun plan(): ChapterPlan = ChapterPlan(
        chapterPlanId = planId,
        chapterId = chapterId,
        arcId = ArcId("a1"),
        actId = ActId("act1"),
        novelId = novelId,
        chapterGoal = "揭开秘密",
        characterGoals = mapOf(characterId to "保护妹妹"),
        expectedEvents = listOf("遇险"),
        emotionalDirection = "紧张",
        endingHook = "黑影出现",
        constraints = listOf("不能超自然"),
        forbiddenEvents = listOf("主角死亡"),
    )

    private fun input(): CharacterConsistencyInput = CharacterConsistencyInput(
        novelId = novelId,
        chapterId = chapterId,
        scope = CharacterConsistencyScope.CURRENT_CHAPTER,
        contextPackId = "pack-1",
        currentDraft = CharacterConsistencyDraft(currentDraftId, currentContent),
        plan = plan(),
        priorCanonical = listOf(
            CharacterConsistencyCanonicalChapter(
                chapterId = priorChapterId,
                chapterOrder = 0,
                draftId = priorDraftId,
                content = "前章正文：林岚说她不怕黑。",
            ),
        ),
        coverage = CharacterConsistencyInputCoverage(
            currentDraftFromContextPack = true,
            planProvided = true,
            priorCanonicalCount = 1,
        ),
    )

    private fun worldContext(): StoryWorldContext = StoryWorldContext(
        novelId = novelId,
        worldSummary = "测试世界",
        canon = listOf("林岚是剑修"),
        worldState = listOf("林岚在青云山"),
        facts = listOf("林岚惧怕黑暗"),
        memories = listOf("神秘人出现"),
        characters = listOf(character()),
        characterStates = listOf(state()),
        worldRules = listOf(
            WorldRule(ruleId = ruleId, worldId = WorldId("w1"), novelId = novelId, content = "修士不能飞行", category = "物理"),
        ),
        events = listOf(
            Event(
                id = eventId,
                novelId = novelId,
                name = "青云试炼",
                description = "林岚参加试炼",
                what = "试炼开始",
                who = listOf(characterId),
                createdAt = now,
            ),
        ),
        timelineEntries = listOf(
            TimelineEntry(id = timelineId, novelId = novelId, position = TimelinePosition(), description = "试炼之后"),
        ),
        foreshadows = listOf(
            Foreshadow(foreshadowId = foreshadowId, novelId = novelId, content = "黑暗中的眼睛", createdAt = now),
        ),
    )

    private fun gateway(captured: MutableList<String>, answer: String): MockLLMGateway = MockLLMGateway { request ->
        val system = request.messages.first { it.role == ChatRole.SYSTEM }.content
        if ("StoryCharacterConsistencyAgent" in system) {
            captured += request.messages.first { it.role == ChatRole.USER }.content
        }
        ProviderResponse(
            message = ChatMessage(
                ChatRole.ASSISTANT,
                buildJsonObject { put("answer", answer) }.toString(),
            ),
            usage = Usage(10, 10, 20),
            finishReason = FinishReason.STOP,
        )
    }

    @Test
    fun `agent id is stable`() {
        val agent = CharacterConsistencyAgent(gateway(mutableListOf(), PASS_JSON), ErrorMapper)
        assertEquals("story-character-consistency", agent.agentId.value)
    }

    @Test
    fun `prompt carries current draft prior canonical plan and all world evidence sources`() {
        val captured = mutableListOf<String>()
        val agent = CharacterConsistencyAgent(gateway(captured, PASS_JSON), ErrorMapper)
        val raw = agent.check(input(), worldContext())

        assertEquals(CharacterConsistencyStatus.PASS, raw.status)
        val prompt = captured.single()
        listOf(
            currentContent,
            "前章正文：林岚说她不怕黑。",
            priorDraftId.value,
            "characterId=c1", "name=林岚", "personality=[胆小]",
            "stateId=s1", "currentGoal=找到真相",
            "ruleId=r1", "修士不能飞行",
            "eventId=e1", "青云试炼",
            "timelineEntryId=t1", "试炼之后",
            "foreshadowId=f1", "黑暗中的眼睛",
            "layer=WRITING sourceIndex=0 content=神秘人出现",
            "chapterPlanId: p1", "chapterGoal: 揭开秘密",
            "【输入覆盖】", "priorCanonicalGapChapterIds=", "missingInputs=",
            "\"sourceKind\"", "\"sourceId\"",
        ).forEach { token ->
            assertTrue(token in prompt, "Agent prompt 应包含 $token")
        }
    }

    @Test
    fun `invalid llm json maps to invalid output`() {
        val agent = CharacterConsistencyAgent(gateway(mutableListOf(), "not json"), ErrorMapper)
        val ex = assertFailsWith<ApplicationException> {
            agent.check(input(), worldContext())
        }
        assertIs<ApplicationError.InvalidCharacterConsistencyOutput>(ex.error)
    }

    private companion object {
        const val PASS_JSON =
            """{"status":"PASS","scope":"CURRENT_CHAPTER","findings":[],"checkedCharacters":[],"missingInputs":[],"summary":"未发现问题"}"""
    }
}
