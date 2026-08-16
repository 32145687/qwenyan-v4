package com.qianyan.model

import com.qianyan.model.core.EntityOverride
import com.qianyan.model.core.Novel
import com.qianyan.model.core.NovelVariant
import com.qianyan.model.core.OverrideOperation
import com.qianyan.model.core.OverridableKind
import com.qianyan.model.core.VariantContext
import com.qianyan.model.knowledge.FactLevel
import com.qianyan.model.knowledge.KnowledgeCategory
import com.qianyan.model.knowledge.KnowledgeEntry
import com.qianyan.model.story.OriginalNodeRef
import com.qianyan.model.story.StoryArc
import com.qianyan.model.story.StoryNodeKind
import com.qianyan.model.vocabulary.VocabularyEntry
import com.qianyan.model.vocabulary.VocabularyScopeLevel
import kotlinx.datetime.Instant
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** P1 Core Domain Model 测试（对应 P1 Requirement 14 的 11 项）。 */
class P1DomainModelTest {

    private val t0 = Instant.parse("2026-01-01T00:00:00Z")

    /* ---- 1. Original VariantScope 正确 ---- */
    @Test
    fun `Original 实体 scope 为 ORIGINAL 且只读`() {
        val novel = Novel(
            novelId = NovelId("n1"),
            projectId = ProjectId("p1"),
            title = "问道",
            createdAt = t0,
            updatedAt = t0,
        )
        assertEquals(VariantScope.ORIGINAL, novel.scope)
        assertTrue(novel.isOriginal)

        val knowledge = KnowledgeEntry(
            id = KnowledgeId("k1"),
            novelId = NovelId("n1"),
            category = KnowledgeCategory.WORLD_RULE,
            content = "灵石为能量之源",
            factLevel = FactLevel.EXPLICIT,
            createdAt = t0,
            updatedAt = t0,
        )
        assertEquals(VariantScope.ORIGINAL, knowledge.scope)
        assertEquals(null, knowledge.variantId)
    }

    /* ---- 2. Variant VariantScope 正确 ---- */
    @Test
    fun `Variant 实体 scope 为 VARIANT 且指向 variantId`() {
        val variantKnowledge = KnowledgeEntry(
            id = KnowledgeId("k2"),
            novelId = NovelId("n1"),
            variantId = VariantId("v1"),
            scope = VariantScope.VARIANT,
            category = KnowledgeCategory.WORLD_RULE,
            content = "星石为能量之源",
            factLevel = FactLevel.GENERATED,
            createdAt = t0,
            updatedAt = t0,
        )
        assertEquals(VariantScope.VARIANT, variantKnowledge.scope)
        assertEquals(VariantId("v1"), variantKnowledge.variantId)
    }

    /* ---- 3. VariantId 可区分不同 Variant ---- */
    @Test
    fun `VariantId 可区分不同 Variant`() {
        assertNotEquals(VariantId("v1"), VariantId("v2"))
        assertEquals(VariantId("v1"), VariantId("v1"))
    }

    /* ---- 4. Original immutable 语义可以表达 ---- */
    @Test
    fun `Original 不可变语义可表达`() {
        val original = Novel(
            novelId = NovelId("n1"),
            projectId = ProjectId("p1"),
            title = "问道",
            createdAt = t0,
            updatedAt = t0,
        )
        // Novel 领域层始终 isOriginal == true，scope == ORIGINAL（字段不可重新赋值的 val）。
        assertTrue(original.isOriginal)
        val variantDeclared = NovelVariant(
            variantId = VariantId("v1"),
            novelId = NovelId("n1"),
            baseNovelId = BaseNovelId("n1"),
            projectId = ProjectId("p1"),
            name = "爽文重构",
            createdAt = t0,
            updatedAt = t0,
        )
        // Variant 只引用 base，不修改 original 的 scope/标题。
        assertEquals("问道", original.title)
        assertEquals(BaseNovelId("n1"), variantDeclared.baseNovelId)
    }

    /* ---- 5. Override 可以表达 ADD / OVERRIDE / REMOVE ---- */
    @Test
    fun `EntityOverride 表达 INHERIT OVERRIDE ADD REMOVE`() {
        fun ov(op: OverrideOperation) = EntityOverride(
            overrideId = OverrideId("o1"),
            variantId = VariantId("v1"),
            targetKind = OverridableKind.KNOWLEDGE,
            targetId = "k1",
            operation = op,
        )
        assertEquals(OverrideOperation.INHERIT, ov(OverrideOperation.INHERIT).operation)
        assertEquals(OverrideOperation.OVERRIDE, ov(OverrideOperation.OVERRIDE).operation)
        assertEquals(OverrideOperation.ADD, ov(OverrideOperation.ADD).operation)
        assertEquals(OverrideOperation.REMOVE, ov(OverrideOperation.REMOVE).operation)
    }

    /* ---- 6. VariantContext 正确表达 Original / Variant ---- */
    @Test
    fun `VariantContext 表达 Original 与 Variant`() {
        val originalCtx = VariantContext(baseNovelId = BaseNovelId("n1"), variantId = null)
        assertEquals(VariantScope.ORIGINAL, originalCtx.scope)
        assertTrue(originalCtx.isOriginal)

        val variantCtx = VariantContext(baseNovelId = BaseNovelId("n1"), variantId = VariantId("v1"))
        assertEquals(VariantScope.VARIANT, variantCtx.scope)
        assertFalse(variantCtx.isOriginal)
        assertEquals(VariantId("v1"), variantCtx.variantId)
    }

    /* ---- 7. Vocabulary 四层 scope 可以表达 ---- */
    @Test
    fun `Vocabulary 四级作用域可表达且窄覆盖宽`() {
        val entry = VocabularyEntry(
            entryId = VocabularyEntryId("e1"),
            vocabularyId = com.qianyan.model.VocabularyId("voc1"),
            scopeLevel = VocabularyScopeLevel.NOVEL,
            canonical = "灵石",
            type = com.qianyan.model.vocabulary.VocabularyEntryType.WORLD_TERM,
        )
        assertEquals(VocabularyScopeLevel.NOVEL, entry.scopeLevel)
        // 优先级序：窄覆盖宽（Global < Novel < Variant < Task）。
        assertTrue(VocabularyScopeLevel.VARIANT.ordinal > VocabularyScopeLevel.NOVEL.ordinal)
        assertTrue(VocabularyScopeLevel.TASK.ordinal > VocabularyScopeLevel.VARIANT.ordinal)
    }

    /* ---- 8. Variant Vocabulary 可以覆盖 Novel Vocabulary ---- */
    @Test
    fun `Variant 词条覆盖 Novel 词条不修改 Original`() {
        val novelEntry = VocabularyEntry(
            entryId = VocabularyEntryId("e1"),
            vocabularyId = com.qianyan.model.VocabularyId("voc1"),
            novelId = NovelId("n1"),
            scopeLevel = VocabularyScopeLevel.NOVEL,
            canonical = "灵石",
            type = com.qianyan.model.vocabulary.VocabularyEntryType.WORLD_TERM,
        )
        val variantEntry = VocabularyEntry(
            entryId = VocabularyEntryId("e2"),
            vocabularyId = com.qianyan.model.VocabularyId("voc2"),
            novelId = NovelId("n1"),
            variantId = VariantId("v1"),
            scopeLevel = VocabularyScopeLevel.VARIANT,
            canonical = "灵石",
            replacement = "星石",
            type = com.qianyan.model.vocabulary.VocabularyEntryType.WORLD_TERM,
        )
        // Original 的 canonical 不被修改；Variant 用 replacement 表达覆盖。
        assertEquals("灵石", novelEntry.canonical)
        assertEquals("星石", variantEntry.replacement)
        assertEquals(VariantId("v1"), variantEntry.variantId)
        assertTrue(variantEntry.scopeLevel.ordinal > novelEntry.scopeLevel.ordinal)
    }

    /* ---- 9. Story Structure 可以区分 Original / Variant ---- */
    @Test
    fun `Story Structure 区分 Original 与 Variant`() {
        val originalArc = StoryArc(
            arcId = ArcId("a1"),
            novelId = NovelId("n1"),
            scope = VariantScope.ORIGINAL,
            name = "主线",
        )
        val variantArc = StoryArc(
            arcId = ArcId("a2"),
            novelId = NovelId("n1"),
            variantId = VariantId("v1"),
            scope = VariantScope.VARIANT,
            originalRef = OriginalNodeRef(kind = StoryNodeKind.ARC, id = "a1"), // 引用 Original 未改节点
            name = "重构主线",
        )
        assertEquals(VariantScope.ORIGINAL, originalArc.scope)
        assertEquals(null, originalArc.variantId)
        assertEquals(VariantScope.VARIANT, variantArc.scope)
        assertEquals(VariantId("v1"), variantArc.variantId)
        // Variant 复用 Original 未修改节点，而非复制整棵树。
        assertEquals(StoryNodeKind.ARC, variantArc.originalRef?.kind)
        assertEquals("a1", variantArc.originalRef?.id)
    }

    /* ---- 10. ID 类型不会混用 ---- */
    @Test
    fun `强类型 ID 互不混用`() {
        assertNotEquals<Any>(NovelId("x"), VariantId("x"))
        assertNotEquals<Any>(CharacterId("x"), EventId("x"))
        assertNotEquals<Any>(ArcId("x"), BeatId("x"))
        // 同一类型同值相等
        assertEquals(NovelId("x"), NovelId("x"))
        assertEquals(KnowledgeId("x"), KnowledgeId("x"))
    }

    /* ---- 11. kotlinx.serialization 序列化 / 反序列化核心模型 ---- */
    @Test
    fun `核心模型可序列化往返`() {
        val json = Json

        val ctxIn = VariantContext(baseNovelId = BaseNovelId("n1"), variantId = VariantId("v1"))
        val ctxOut = json.decodeFromString<VariantContext>(json.encodeToString(ctxIn))
        assertEquals(ctxIn, ctxOut)

        val ovIn = EntityOverride(
            overrideId = OverrideId("o1"),
            variantId = VariantId("v1"),
            targetKind = OverridableKind.KNOWLEDGE,
            targetId = "k1",
            operation = OverrideOperation.OVERRIDE,
        )
        val ovOut = json.decodeFromString<EntityOverride>(json.encodeToString(ovIn))
        assertEquals(ovIn, ovOut)

        val entryIn = VocabularyEntry(
            entryId = VocabularyEntryId("e1"),
            vocabularyId = com.qianyan.model.VocabularyId("voc1"),
            scopeLevel = VocabularyScopeLevel.VARIANT,
            canonical = "灵石",
            replacement = "星石",
        )
        val entryOut = json.decodeFromString<VocabularyEntry>(json.encodeToString(entryIn))
        assertEquals(entryIn, entryOut)

        val arcIn = StoryArc(arcId = ArcId("a1"), novelId = NovelId("n1"), name = "主线")
        val arcOut = json.decodeFromString<StoryArc>(json.encodeToString(arcIn))
        assertEquals(arcIn, arcOut)

        val opIn = OverrideOperation.REMOVE
        val opOut = json.decodeFromString<OverrideOperation>(json.encodeToString(opIn))
        assertEquals(opIn, opOut)
    }
}