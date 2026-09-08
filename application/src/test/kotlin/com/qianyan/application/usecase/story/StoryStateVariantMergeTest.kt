package com.qianyan.application.usecase.story

import com.qianyan.application.di.ApplicationContainer
import com.qianyan.model.BaseNovelId
import com.qianyan.model.CharacterId
import com.qianyan.model.EventId
import com.qianyan.model.ForeshadowingId
import com.qianyan.model.MemoryEntryId
import com.qianyan.model.NovelId
import com.qianyan.model.StateId
import com.qianyan.model.TimelineEntryId
import com.qianyan.model.VariantId
import com.qianyan.model.VariantScope
import com.qianyan.model.WorldId
import com.qianyan.model.WorldRuleId
import com.qianyan.model.character.Character
import com.qianyan.model.character.CharacterState
import com.qianyan.model.character.SnapshotType
import com.qianyan.model.context.StoryWorldContext
import com.qianyan.model.core.OverridableKind
import com.qianyan.model.core.VariantContext
import com.qianyan.model.memory.MemoryEntry
import com.qianyan.model.memory.MemoryLayer
import com.qianyan.model.story.Foreshadow
import com.qianyan.model.timeline.Event
import com.qianyan.model.timeline.TimelineEntry
import com.qianyan.model.timeline.TimelinePosition
import com.qianyan.model.world.WorldRule
import com.qianyan.provider.impl.MockLLMGateway
import kotlinx.datetime.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * P12.3 · TD1 — Variant Structured State Merge / Override。
 *
 * 验证六类 Story State（Character / CharacterState / WorldRule / Event / TimelineEntry / Foreshadow）
 * 经 EntityOverride 实体级 merge 后的 Effective State：INHERIT 继承 / OVERRIDE 替换 / REMOVE 剔除 / ADD 追加，
 * 以及 Original / VariantA / VariantB 隔离，且不破坏 Memory 连续性。
 */
class StoryStateVariantMergeTest {

    private fun app() = ApplicationContainer.open(analysisGateway = MockLLMGateway())
    private val now = Clock.System.now()

    /** 夹具：novel + BaseNovelId + 两个 Variant（A / B）+ 上下文。 */
    private data class Seed(
        val app: ApplicationContainer,
        val novel: NovelId,
        val base: BaseNovelId,
        val va: VariantId,
        val vb: VariantId,
    ) {
        val ctxA get() = VariantContext(base, va)
        val ctxB get() = VariantContext(base, vb)
        fun resolve(novel: NovelId, variant: VariantId? = null): StoryWorldContext =
            app.storyWorldContextResolver.resolve(novel, variant)
    }

    private fun seed(): Seed {
        val c = app()
        val novel = c.novels.createOriginal(title = "N")
        val base = BaseNovelId(novel.value)
        val va = c.novels.createVariant(VariantContext(base, VariantId("va")), "A")
        val vb = c.novels.createVariant(VariantContext(base, VariantId("vb")), "B")
        return Seed(c, novel, base, va, vb)
    }

    // ---- entity builders ----
    private fun char(id: String, novel: NovelId, name: String, variant: VariantId? = null, scope: VariantScope = VariantScope.ORIGINAL) = Character(
        characterId = CharacterId(id), novelId = novel, variantId = variant, scope = scope, name = name,
        createdAt = now, updatedAt = now,
    )
    private fun state(id: String, charId: String, novel: NovelId, variant: VariantId? = null, scope: VariantScope = VariantScope.ORIGINAL) = CharacterState(
        id = StateId(id), characterId = CharacterId(charId), novelId = novel, variantId = variant, scope = scope,
        snapshotType = SnapshotType.USER_DEFINED, createdAt = now,
    )
    private fun rule(id: String, novel: NovelId, content: String, variant: VariantId? = null, scope: VariantScope = VariantScope.ORIGINAL) = WorldRule(
        ruleId = WorldRuleId(id), worldId = WorldId("w"), novelId = novel, variantId = variant, scope = scope, content = content,
    )
    private fun event(id: String, novel: NovelId, name: String, variant: VariantId? = null, scope: VariantScope = VariantScope.ORIGINAL) = Event(
        id = EventId(id), novelId = novel, variantId = variant, scope = scope, name = name, createdAt = now,
    )
    private fun tl(id: String, novel: NovelId, eventId: String?, variant: VariantId? = null, scope: VariantScope = VariantScope.ORIGINAL) = TimelineEntry(
        id = TimelineEntryId(id), novelId = novel, variantId = variant, scope = scope, position = TimelinePosition(), eventId = eventId?.let { EventId(it) },
    )
    private fun foreshadow(id: String, novel: NovelId, content: String, resolved: Boolean, variant: VariantId? = null, scope: VariantScope = VariantScope.ORIGINAL) = Foreshadow(
        foreshadowId = ForeshadowingId(id), novelId = novel, variantId = variant, scope = scope, content = content, resolved = resolved, createdAt = now,
    )

    private fun charOf(ctx: StoryWorldContext, id: String): Character = ctx.characters.first { it.characterId.value == id }

    // ---- T-OVERRIDE：替换同 identity 的 Original 实体，Original 不变 ----
    @Test
    fun `OVERRIDE 六类实体替换同一 id 的 Original，Effective 用替换值，Original 不变`() {
        val s = seed()
        // 六类 base 实体
        s.app.storyState.saveCharacter(char("c1", s.novel, "林夜"))
        s.app.storyState.saveCharacterState(state("s1", "c1", s.novel))
        s.app.storyState.saveWorldRule(rule("r1", s.novel, "修炼元力"))
        s.app.storyState.saveEvent(event("e1", s.novel, "突破金丹期"))
        s.app.storyState.saveTimelineEntry(tl("t1", s.novel, "e1"))
        s.app.storyState.saveForeshadow(foreshadow("f1", s.novel, "玉佩", false))

        // Variant A 逐一 OVERRIDE
        s.app.storyStateVariant.overrideCharacter(s.ctxA, char("c1", s.novel, "林火", s.va, VariantScope.VARIANT))
        s.app.storyStateVariant.overrideCharacterState(s.ctxA, state("s1", "c1", s.novel, s.va, VariantScope.VARIANT).copy(physicalState = com.qianyan.model.character.PhysicalState("重伤")))
        s.app.storyStateVariant.overrideWorldRule(s.ctxA, rule("r1", s.novel, "修炼灵力", s.va, VariantScope.VARIANT))
        s.app.storyStateVariant.overrideEvent(s.ctxA, event("e1", s.novel, "突破元婴期", s.va, VariantScope.VARIANT))
        s.app.storyStateVariant.overrideTimelineEntry(s.ctxA, tl("t1", s.novel, "e1", s.va, VariantScope.VARIANT).copy(description = "变体时间线"))
        s.app.storyStateVariant.overrideForeshadow(s.ctxA, foreshadow("f1", s.novel, "玉佩", true, s.va, VariantScope.VARIANT))

        val eff = s.resolve(s.novel, s.va)
        assertEquals("林火", charOf(eff, "c1").name)
        assertEquals("重伤", eff.characterStates.first { it.id.value == "s1" }.physicalState.label)
        assertEquals("修炼灵力", eff.worldRules.first { it.ruleId.value == "r1" }.content)
        assertEquals("突破元婴期", eff.events.first { it.id.value == "e1" }.name)
        assertEquals("变体时间线", eff.timelineEntries.first { it.id.value == "t1" }.description)
        assertTrue(eff.foreshadows.first { it.foreshadowId.value == "f1" }.resolved)

        // Original 不变；Variant B 不变
        val orig = s.resolve(s.novel)
        assertEquals("林夜", charOf(orig, "c1").name)
        assertEquals("修炼元力", orig.worldRules.first { it.ruleId.value == "r1" }.content)
        assertTrue(!orig.events.first { it.id.value == "e1" }.name.equals("突破元婴期"))
        val effB = s.resolve(s.novel, s.vb)
        assertEquals("林夜", charOf(effB, "c1").name)
    }

    // ---- T-REMOVE：REMOVE 剔除；无 override ≠ REMOVE ----
    @Test
    fun `REMOVE 隐藏 Original 实体，无 override 仍继承`() {
        val s = seed()
        s.app.storyState.saveCharacter(char("c1", s.novel, "林夜"))
        s.app.storyState.saveCharacter(char("c2", s.novel, "萧炎"))

        s.app.storyStateVariant.remove(s.ctxA, OverridableKind.CHARACTER, "c1")

        val effA = s.resolve(s.novel, s.va)
        assertTrue(effA.characters.none { it.characterId.value == "c1" })     // REMOVE 剔除
        assertTrue(effA.characters.any { it.characterId.value == "c2" })     // 无 override → 继承

        // 无 override 的 Variant B / Original 仍可见 c1（“Variant 无记录”≠ REMOVE）
        assertTrue(s.resolve(s.novel, s.vb).characters.any { it.characterId.value == "c1" })
        assertTrue(s.resolve(s.novel).characters.any { it.characterId.value == "c1" })
    }

    // ---- T-INHERIT：显式 INHERIT 等价于继承 ----
    @Test
    fun `INHERIT 显式继承 Original 实体`() {
        val s = seed()
        s.app.storyState.saveCharacter(char("c1", s.novel, "林夜"))
        s.app.storyStateVariant.inherit(s.ctxA, OverridableKind.CHARACTER, "c1")
        assertEquals("林夜", charOf(s.resolve(s.novel, s.va), "c1").name)
    }

    // ---- T-ADD：Variant own 实体追加，Original 不受影响 ----
    @Test
    fun `ADD 新增 Variant own 实体，叠加到 Effective，Original 无`() {
        val s = seed()
        s.app.storyState.saveCharacter(char("c1", s.novel, "林夜"))
        s.app.storyStateVariant.addCharacter(s.ctxA, char("c9", s.novel, "新角色", s.va, VariantScope.VARIANT))

        val effA = s.resolve(s.novel, s.va)
        assertTrue(effA.characters.any { it.characterId.value == "c1" })
        assertTrue(effA.characters.any { it.characterId.value == "c9" })
        assertTrue(s.resolve(s.novel).characters.none { it.characterId.value == "c9" })
        assertTrue(s.resolve(s.novel, s.vb).characters.none { it.characterId.value == "c9" })
    }

    // ---- T-RECOVER：REMOVE 之后对同一 identity 写 OVERRIDE 恢复 ----
    @Test
    fun `REMOVE 后 OVERRIDE 同一 identity 恢复`() {
        val s = seed()
        s.app.storyState.saveCharacter(char("c1", s.novel, "林夜"))
        s.app.storyStateVariant.remove(s.ctxA, OverridableKind.CHARACTER, "c1")
        assertTrue(s.resolve(s.novel, s.va).characters.none { it.characterId.value == "c1" })
        // 恢复：同一 characterId 写 OVERRIDE（replace 旧 REMOVE override）
        s.app.storyStateVariant.overrideCharacter(s.ctxA, char("c1", s.novel, "林火·重启", s.va, VariantScope.VARIANT))
        assertEquals("林火·重启", charOf(s.resolve(s.novel, s.va), "c1").name)
        assertEquals(1, s.app.novelRepository.getOverrides(s.va).count { it.targetId == "c1" })
    }

    // ---- T-ISOLATION：VariantA 修改不污染 Original / VariantB ----
    @Test
    fun `VariantA 的 OVERRIDE 与 REMOVE 与 VariantB 隔离`() {
        val s = seed()
        s.app.storyState.saveCharacter(char("c1", s.novel, "林夜"))
        s.app.storyState.saveCharacter(char("c2", s.novel, "萧炎"))
        s.app.storyStateVariant.overrideCharacter(s.ctxA, char("c1", s.novel, "林火", s.va, VariantScope.VARIANT))
        s.app.storyStateVariant.remove(s.ctxA, OverridableKind.CHARACTER, "c2")

        // A：c1 覆盖、c2 移除
        val effA = s.resolve(s.novel, s.va)
        assertEquals("林火", charOf(effA, "c1").name)
        assertTrue(effA.characters.none { it.characterId.value == "c2" })
        // B：全部继承 Original
        val effB = s.resolve(s.novel, s.vb)
        assertEquals("林夜", charOf(effB, "c1").name)
        assertTrue(effB.characters.any { it.characterId.value == "c2" })
        // Original：不变
        val orig = s.resolve(s.novel)
        assertEquals("林夜", charOf(orig, "c1").name)
        assertTrue(orig.characters.any { it.characterId.value == "c2" })
        // 底层 Original 行未被污染
        assertEquals("林夜", s.app.storyState.listCharacters(s.novel, null).first { it.characterId.value == "c1" }.name)
    }

    // ---- T-IDENTITY：不同 entity id 不互相覆盖 ----
    @Test
    fun `OVERRIDE 命中精确 identity，不同 id 不受影响`() {
        val s = seed()
        s.app.storyState.saveCharacter(char("c1", s.novel, "林夜"))
        s.app.storyState.saveCharacter(char("c2", s.novel, "萧炎"))
        s.app.storyStateVariant.overrideCharacter(s.ctxA, char("c1", s.novel, "林火", s.va, VariantScope.VARIANT))
        val eff = s.resolve(s.novel, s.va)
        assertEquals("林火", charOf(eff, "c1").name)
        assertEquals("萧炎", charOf(eff, "c2").name) // c2 不受影响
    }

    // ---- T-KU-SCOPE：Variant Memory 隔离 + REMOVE/OVERRIDE 不破坏 Memory 连续性 ----
    @Test
    fun `Variant Memory 只属于该 Variant，Story State REMOVE 不破坏 Memory`() {
        val s = seed()
        s.app.storyState.saveCharacter(char("c1", s.novel, "林夜"))
        s.app.memoryRepository.saveEntry(
            MemoryEntry(
                id = MemoryEntryId("kuA"), novelId = s.novel, variantId = s.va, scope = VariantScope.VARIANT,
                layer = MemoryLayer.WRITING, content = "林夜突破金丹期", createdAt = now, updatedAt = now,
            ),
        )
        s.app.memoryRepository.saveEntry(
            MemoryEntry(
                id = MemoryEntryId("kuOrig"), novelId = s.novel, variantId = null, scope = VariantScope.ORIGINAL,
                layer = MemoryLayer.ORIGINAL, content = "世界设定", createdAt = now, updatedAt = now,
            ),
        )
        s.app.storyStateVariant.remove(s.ctxA, OverridableKind.CHARACTER, "c1")

        val effA = s.resolve(s.novel, s.va)
        assertTrue(effA.memories.contains("林夜突破金丹期")) // Variant Memory 连续性保留
        val orig = s.resolve(s.novel)
        assertTrue(!orig.memories.contains("林夜突破金丹期")) // Original Memory 不被污染
    }

    // ---- 关联：CharacterState → Character；REMOVE 不产生崩溃/错误引用 ----
    @Test
    fun `REMOVE CharacterState 不影响 Character，OVERRIDE 可替换 - CharacterState 关联`() {
        val s = seed()
        s.app.storyState.saveCharacter(char("c1", s.novel, "林夜"))
        s.app.storyState.saveCharacterState(state("s1", "c1", s.novel))
        s.app.storyStateVariant.remove(s.ctxA, OverridableKind.CHARACTER_STATE, "s1")
        val eff = s.resolve(s.novel, s.va)
        assertTrue(eff.characterStates.none { it.id.value == "s1" })
        assertTrue(eff.characters.any { it.characterId.value == "c1" }) // 不崩溃
    }

    // ---- 关联：TimelineEntry → Event；REMOVE Event 后 TimelineEntry 保留（无崩溃） ----
    @Test
    fun `REMOVE Event 后 TimelineEntry 保留引用不崩溃`() {
        val s = seed()
        s.app.storyState.saveEvent(event("e1", s.novel, "突破金丹期"))
        s.app.storyState.saveTimelineEntry(tl("t1", s.novel, "e1"))
        s.app.storyStateVariant.remove(s.ctxA, OverridableKind.EVENT, "e1")
        val eff = s.resolve(s.novel, s.va)
        assertTrue(eff.events.none { it.id.value == "e1" })
        assertTrue(eff.timelineEntries.any { it.id.value == "t1" }) // 悬空引用容忍，不崩溃
    }

    // ---- 关联：Foreshadow override 生效 ----
    @Test
    fun `Foreshadow OVERRIDE 与 REMOVE 生效`() {
        val s = seed()
        s.app.storyState.saveForeshadow(foreshadow("f1", s.novel, "玉佩", false))
        s.app.storyStateVariant.overrideForeshadow(s.ctxA, foreshadow("f1", s.novel, "玉佩", true, s.va, VariantScope.VARIANT))
        assertTrue(s.resolve(s.novel, s.va).foreshadows.first { it.foreshadowId.value == "f1" }.resolved)
        assertTrue(!s.resolve(s.novel).foreshadows.first { it.foreshadowId.value == "f1" }.resolved)
        s.app.storyStateVariant.remove(s.ctxA, OverridableKind.FORESHADOW, "f1")
        assertTrue(s.resolve(s.novel, s.va).foreshadows.none { it.foreshadowId.value == "f1" })
    }
}