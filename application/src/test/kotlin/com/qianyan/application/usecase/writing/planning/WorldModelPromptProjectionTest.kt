package com.qianyan.application.usecase.writing.planning

import com.qianyan.application.error.ErrorMapper
import com.qianyan.application.usecase.context.ContextFixture
import com.qianyan.application.usecase.context.SeededProject
import com.qianyan.application.usecase.context.contextFixture
import com.qianyan.application.usecase.context.seedProject
import com.qianyan.application.usecase.writing.WriterAgent
import com.qianyan.model.BaseNovelId
import com.qianyan.model.CharacterId
import com.qianyan.model.ChapterId
import com.qianyan.model.EventId
import com.qianyan.model.IntentType
import com.qianyan.model.KnowledgeId
import com.qianyan.model.LocationId
import com.qianyan.model.PlanningScope
import com.qianyan.model.RequestId
import com.qianyan.model.StateId
import com.qianyan.model.VariantScope
import com.qianyan.model.WorldRuleId
import com.qianyan.model.WorldId
import com.qianyan.model.character.Character
import com.qianyan.model.character.CharacterState
import com.qianyan.model.character.EmotionalState
import com.qianyan.model.character.PhysicalState
import com.qianyan.model.character.SnapshotType
import com.qianyan.model.context.ContextPack
import com.qianyan.model.context.ContextRequest
import com.qianyan.model.context.TargetKind
import com.qianyan.model.context.TargetRef
import com.qianyan.model.context.TargetRefId
import com.qianyan.model.context.UserWritingRequest
import com.qianyan.model.timeline.Event
import com.qianyan.model.timeline.EventStatus
import com.qianyan.model.timeline.EventType
import com.qianyan.model.world.WorldRule
import com.qianyan.provider.ChatMessage
import com.qianyan.provider.ChatRole
import com.qianyan.provider.FinishReason
import com.qianyan.provider.ModelProfile
import com.qianyan.provider.ProviderRequest
import com.qianyan.provider.ProviderResponse
import com.qianyan.provider.Usage
import com.qianyan.provider.impl.MockLLMGateway
import kotlinx.datetime.Instant
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WorldModelPromptProjectionTest {

    private val fixed = Instant.parse("2026-01-01T00:00:00Z")

    private class Scene(
        val f: ContextFixture,
        val w: SeededProject,
        val pack: ContextPack,
    ) {
        fun close() = f.close()
    }

    private fun scene(): Scene {
        val f = contextFixture()
        val w = seedProject(f, "书A", "A")
        val pack = f.app.contextEngine.build(
            ContextRequest(
                projectId = w.projectId,
                purpose = IntentType.CONTINUE,
                focusChapterId = w.chapter2,
            ),
        )
        return Scene(f, w, pack)
    }

    private fun request(s: Scene): UserWritingRequest = UserWritingRequest(
        requestId = RequestId("req-world-prompt"),
        intentType = IntentType.CONTINUE,
        target = TargetRef(TargetKind.CHAPTER, TargetRefId(s.w.chapter2.value)),
        planningScope = PlanningScope.CHAPTER,
        baseNovelId = BaseNovelId(s.w.novelId.value),
        variantId = null,
        scope = VariantScope.ORIGINAL,
        rawText = "继续写这一章 3000 字，保持人物状态和前文一致。",
    )

    private fun capturePrompts(context: PlanningContext): List<ProviderRequest> {
        val records = mutableListOf<ProviderRequest>()
        val gateway = MockLLMGateway { request ->
            records += request
            val system = request.messages.first { it.role == ChatRole.SYSTEM }.content
            val body = when {
                "StoryPlannerAgent" in system -> """{"chapterGoal":"目标","mainConflict":"冲突"}"""
                "StoryWriterAgent" in system -> buildJsonObject { put("content", "新正文") }.toString()
                else -> buildJsonObject { put("content", "占位") }.toString()
            }
            ProviderResponse(
                message = ChatMessage(ChatRole.ASSISTANT, buildJsonObject { put("answer", body) }.toString()),
                usage = Usage(10, 10, 20),
                finishReason = FinishReason.STOP,
            )
        }
        val planner = PlannerAgent(gateway, ErrorMapper, ModelProfile.MOCK)
        val writer = WriterAgent(gateway, ErrorMapper, ModelProfile.MOCK)
        val plan = planner.plan(context, emptyList()).copy(
            chapterId = context.request.target.id?.let { ChapterId(it.value) },
            novelId = context.novelId,
            variantId = context.variantId,
            scope = context.scope,
        )
        writer.write(context, plan)
        return records
    }

    private fun promptText(request: ProviderRequest): String =
        request.messages.joinToString("\n") { it.content }

    private fun promptOf(records: List<ProviderRequest>, agent: String): String =
        promptText(records.first { agent in it.messages.first { m -> m.role == ChatRole.SYSTEM }.content })

    private fun seedCharacterWorld(f: ContextFixture, w: SeededProject) {
        f.app.storyState.saveCharacter(
            Character(
                characterId = CharacterId("c1"),
                novelId = w.novelId,
                name = "林岚",
                personality = listOf("冷静"),
                goals = listOf("守住秘密"),
                createdAt = fixed,
                updatedAt = fixed,
            ),
        )
        f.app.storyState.saveCharacterState(
            CharacterState(
                id = StateId("state-c1"),
                characterId = CharacterId("c1"),
                novelId = w.novelId,
                chapterId = w.chapter2,
                snapshotType = SnapshotType.CHAPTER_START,
                location = LocationId("loc-1"),
                physicalState = PhysicalState("受伤", "左臂"),
                emotionalState = EmotionalState("紧张", 0.8f),
                currentGoal = "守住秘密",
                currentMotivation = "复仇",
                knownFacts = listOf(KnowledgeId("k-1")),
                unknownFacts = listOf(KnowledgeId("k-2")),
                createdAt = fixed,
            ),
        )
        f.app.storyState.saveWorldRule(
            WorldRule(
                ruleId = WorldRuleId("rule-1"),
                worldId = WorldId("world-A"),
                novelId = w.novelId,
                content = "灵力不可外泄",
                category = "POWER",
            ),
        )
        f.app.storyState.saveEvent(
            Event(
                id = EventId("event-1"),
                novelId = w.novelId,
                name = "主角抵达北境",
                what = "主角抵达北境，风雪未停",
                type = EventType.MAIN_PLOT,
                status = EventStatus.CONFIRMED,
                createdAt = fixed,
            ),
        )
    }

    @Test
    fun `character state world rules and events reach both planner and writer prompts`() {
        val s = scene()
        try {
            seedCharacterWorld(s.f, s.w)
            val context = s.f.app.planningContextAssembly.assemble(request(s), s.pack)
            val prompts = capturePrompts(context)
            val planner = promptOf(prompts, "StoryPlannerAgent")
            val writer = promptOf(prompts, "StoryWriterAgent")
            listOf(planner, writer).forEach { prompt ->
                assertTrue("【人物档案】" in prompt)
                assertTrue("- 林岚(c1): [守住秘密]" in prompt)
                assertTrue("【人物状态】" in prompt)
                assertTrue("currentGoal=守住秘密" in prompt)
                assertTrue("currentMotivation=复仇" in prompt)
                assertTrue("location=loc-1" in prompt)
                assertTrue("physical=受伤/左臂" in prompt)
                assertTrue("emotional=紧张/0.8" in prompt)
                assertTrue("known=k-1" in prompt)
                assertTrue("unknown=k-2" in prompt)
                assertTrue("【世界规则】" in prompt)
                assertTrue("[POWER] 灵力不可外泄" in prompt)
                assertTrue("【近期事件】" in prompt)
                assertTrue("主角抵达北境: 主角抵达北境，风雪未停 [MAIN_PLOT/CONFIRMED]" in prompt)
            }
        } finally {
            s.close()
        }
    }

    @Test
    fun `event projection is capped and explicitly marked truncated`() {
        val s = scene()
        try {
            repeat(25) { index ->
                val n = (index + 1).toString().padStart(2, '0')
                s.f.app.storyState.saveEvent(
                    Event(
                        id = EventId("event-$n"),
                        novelId = s.w.novelId,
                        name = "事件$n",
                        what = "经过$n",
                        type = EventType.MAIN_PLOT,
                        status = EventStatus.CONFIRMED,
                        createdAt = fixed,
                    ),
                )
            }
            val context = s.f.app.planningContextAssembly.assemble(request(s), s.pack)
            val prompts = capturePrompts(context)
            listOf(promptOf(prompts, "StoryPlannerAgent"), promptOf(prompts, "StoryWriterAgent")).forEach { prompt ->
                val eventLines = prompt.lineSequence().count { it.startsWith("- 事件") }
                assertEquals(20, eventLines, "最多展示 20 条近期事件")
                assertTrue("truncated=true; total=25" in prompt)
                assertTrue("事件01" in prompt)
                assertFalse("事件25" in prompt)
            }
        } finally {
            s.close()
        }
    }

    @Test
    fun `empty world model renders no world sections`() {
        val s = scene()
        try {
            val context = s.f.app.planningContextAssembly.assemble(request(s), s.pack)
            val prompts = capturePrompts(context)
            listOf(promptOf(prompts, "StoryPlannerAgent"), promptOf(prompts, "StoryWriterAgent")).forEach { prompt ->
                assertFalse("【人物档案】" in prompt)
                assertFalse("【人物状态】" in prompt)
                assertFalse("【世界规则】" in prompt)
                assertFalse("【近期事件】" in prompt)
            }
        } finally {
            s.close()
        }
    }

    @Test
    fun `world model is a single assembled snapshot not re-read at render time`() {
        val s = scene()
        try {
            s.f.app.storyState.saveEvent(
                Event(
                    id = EventId("event-snapshot-a"),
                    novelId = s.w.novelId,
                    name = "快照事件A",
                    what = "A",
                    createdAt = fixed,
                ),
            )
            val context = s.f.app.planningContextAssembly.assemble(request(s), s.pack)
            assertNotNull(context.worldContext)
            s.f.app.storyState.saveEvent(
                Event(
                    id = EventId("event-snapshot-b"),
                    novelId = s.w.novelId,
                    name = "快照事件B",
                    what = "B",
                    createdAt = fixed,
                ),
            )
            val prompts = capturePrompts(context)
            listOf(promptOf(prompts, "StoryPlannerAgent"), promptOf(prompts, "StoryWriterAgent")).forEach { prompt ->
                assertTrue("快照事件A" in prompt)
                assertFalse("快照事件B" in prompt, "不得在 Agent 渲染时重新 resolve/读取仓储")
            }
        } finally {
            s.close()
        }
    }
}
