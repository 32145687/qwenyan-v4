package com.qianyan.application.usecase.writing.context

import com.qianyan.application.di.ApplicationContainer
import com.qianyan.application.usecase.writing.planning.PlanningContextAssembly
import com.qianyan.model.CharacterId
import com.qianyan.model.EventId
import com.qianyan.model.ForeshadowingId
import com.qianyan.model.IntentType
import com.qianyan.model.NovelId
import com.qianyan.model.PlanningScope
import com.qianyan.model.RequestId
import com.qianyan.model.StateId
import com.qianyan.model.TimelineEntryId
import com.qianyan.model.VariantId
import com.qianyan.model.VariantScope
import com.qianyan.model.WorldId
import com.qianyan.model.WorldRuleId
import com.qianyan.model.character.Character
import com.qianyan.model.character.CharacterState
import com.qianyan.model.character.SnapshotType
import com.qianyan.model.context.TargetKind
import com.qianyan.model.context.TargetRef
import com.qianyan.model.context.UserWritingRequest
import com.qianyan.model.BaseNovelId
import com.qianyan.model.story.Foreshadow
import com.qianyan.model.timeline.Event
import com.qianyan.model.timeline.TimelineEntry
import com.qianyan.model.timeline.TimelinePosition
import com.qianyan.model.world.WorldRule
import com.qianyan.provider.impl.MockLLMGateway
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * P12.1.2：StoryStateContextResolver（经 StoryWorldContextResolver）读取结构化 Story State 的隔离/逐类型/集成测试。
 */
class StoryStateContextResolverTest {

    private fun app() = ApplicationContainer.open(analysisGateway = MockLLMGateway())

    private fun character(novelId: NovelId, variantId: VariantId?, id: String, name: String) = Character(
        characterId = CharacterId(id), novelId = novelId, variantId = variantId,
        scope = if (variantId == null) VariantScope.ORIGINAL else VariantScope.VARIANT,
        name = name, createdAt = Clock.System.now(), updatedAt = Clock.System.now(),
    )

    @Test
    fun `empty story state yields empty structures`() {
        val app = app()
        val novelId = app.novels.createOriginal(title = "N")
        val ctx = app.storyWorldContextResolver.resolve(novelId)
        assertTrue(ctx.characters.isEmpty())
        assertTrue(ctx.characterStates.isEmpty())
        assertTrue(ctx.worldRules.isEmpty())
        assertTrue(ctx.events.isEmpty())
        assertTrue(ctx.timelineEntries.isEmpty())
        assertTrue(ctx.foreshadows.isEmpty())
    }

    @Test
    fun `resolver reads six structured state types with preserved timestamps`() {
        val app = app()
        val novelId = app.novels.createOriginal(title = "N")
        val now = Instant.parse("2026-01-01T00:00:00Z")
        app.storyState.saveCharacter(character(novelId, null, "c1", "绫"))
        app.storyState.saveCharacterState(
            CharacterState(id = StateId("s1"), characterId = CharacterId("c1"), novelId = novelId, snapshotType = SnapshotType.CHAPTER_END, currentGoal = "复仇", createdAt = now),
        )
        app.storyState.saveWorldRule(WorldRule(ruleId = WorldRuleId("r1"), worldId = WorldId("w1"), novelId = novelId, content = "灵力由内丹驱动", category = "设定"))
        app.storyState.saveEvent(
            Event(id = EventId("e1"), novelId = novelId, name = "初遇", createdAt = now),
        )
        app.storyState.saveTimelineEntry(
            TimelineEntry(id = TimelineEntryId("t1"), novelId = novelId, position = TimelinePosition(), description = "清晨", )
        )
        app.storyState.saveForeshadow(
            Foreshadow(foreshadowId = ForeshadowingId("f1"), novelId = novelId, chapterId = null, content = "断剑", resolved = false, createdAt = now),
        )

        val ctx = app.storyWorldContextResolver.resolve(novelId)
        assertEquals(listOf("绫"), ctx.characters.map { it.name })
        assertEquals("复仇", ctx.characterStates.single().currentGoal)
        assertEquals("灵力由内丹驱动", ctx.worldRules.single().content)
        assertEquals("e1", ctx.events.single().id.value)
        assertEquals(now, ctx.events.single().createdAt) // real persisted timestamp, no placeholder
        assertEquals("清晨", ctx.timelineEntries.single().description)
        assertEquals(false, ctx.foreshadows.single().resolved)
    }

    @Test
    fun `variant isolation - own variant only plus original base`() {
        val app = app()
        val novelX = app.novels.createOriginal(title = "X")
        // Original 基座
        app.storyState.saveCharacter(character(novelX, null, "c-base", "原主"))
        // Variant A
        app.storyState.saveCharacter(character(novelX, VariantId("A"), "c-a", "阿甲"))
        // Variant B
        app.storyState.saveCharacter(character(novelX, VariantId("B"), "c-b", "阿乙"))

        val ctxA = app.storyWorldContextResolver.resolve(novelX, VariantId("A"), VariantScope.VARIANT)
        // A 看到：自身(阿甲) + Original 基座(原主)；看不到 B(阿乙)
        assertEquals(setOf("阿甲", "原主"), ctxA.characters.map { it.name }.toSet())
        assertTrue(ctxA.characters.none { it.name == "阿乙" })

        val ctxB = app.storyWorldContextResolver.resolve(novelX, VariantId("B"), VariantScope.VARIANT)
        assertEquals(setOf("阿乙", "原主"), ctxB.characters.map { it.name }.toSet())
        assertTrue(ctxB.characters.none { it.name == "阿甲" })
    }

    @Test
    fun `novel isolation`() {
        val app = app()
        val novel1 = app.novels.createOriginal(title = "N1")
        val novel2 = app.novels.createOriginal(title = "N2")
        app.storyState.saveCharacter(character(novel1, null, "c1", "一"))
        app.storyState.saveCharacter(character(novel2, null, "c2", "二"))

        assertEquals(listOf("一"), app.storyWorldContextResolver.resolve(novel1).characters.map { it.name })
        assertEquals(listOf("二"), app.storyWorldContextResolver.resolve(novel2).characters.map { it.name })
    }

    @Test
    fun `planning context carries resolved structured story state`() {
        val app = app()
        val novelId = app.novels.createOriginal(title = "N")
        app.storyState.saveWorldRule(WorldRule(ruleId = WorldRuleId("r1"), worldId = WorldId("w1"), novelId = novelId, content = "关键规则", category = "设定"))

        val request = UserWritingRequest(
            requestId = RequestId("rq"),
            intentType = IntentType.PLAN,
            target = TargetRef(TargetKind.CHAPTER, null),
            planningScope = PlanningScope.CHAPTER,
            baseNovelId = BaseNovelId(novelId.value),
        )
        val assembly: PlanningContextAssembly = app.planningContextAssembly
        val planCtx = assembly.assemble(request)
        assertNotNull(planCtx.worldContext)
        assertEquals(listOf("关键规则"), planCtx.worldContext!!.worldRules.map { it.content })
    }
}