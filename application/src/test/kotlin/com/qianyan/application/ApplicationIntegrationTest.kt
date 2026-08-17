package com.qianyan.application

import com.qianyan.application.di.ApplicationContainer
import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.application.error.ErrorMapper
import com.qianyan.model.BaseNovelId
import com.qianyan.model.NovelId
import com.qianyan.model.VariantId
import com.qianyan.model.VariantScope
import com.qianyan.model.core.OverrideOperation
import com.qianyan.model.core.OverridableKind
import com.qianyan.model.core.VariantContext
import com.qianyan.model.memory.MemoryLayer
import com.qianyan.model.vocabulary.Vocabulary
import com.qianyan.model.vocabulary.VocabularyEntry
import com.qianyan.model.vocabulary.VocabularyScopeLevel
import com.qianyan.storage.repository.OriginalImmutableException
import com.qianyan.storage.repository.UniqueConflictException
import com.qianyan.storage.repository.VariantBaseViolation
import com.qianyan.provider.impl.MockLLMGateway
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Application 层集成测试（P3.5）：覆盖 8 项最低要求。
 * 通过 [ApplicationContainer]（手动 DI 组合根）访问 Use Case，全程不触碰 Sqlite 实现。
 * 每个测试使用独立内存数据库。
 */
class ApplicationIntegrationTest {

    private fun container(): ApplicationContainer = ApplicationContainer.open(analysisGateway = MockLLMGateway())

    /* 1. 创建 Original */
    @Test
    fun `create original novel`() {
        val app = container()
        val novelId = app.novels.createOriginal(title = "原著", genre = listOf("仙侠"), synopsis = "简介")
        val read = app.novels.getNovel(novelId)
        assertNotNull(read)
        assertEquals("原著", read.title)
        assertEquals(listOf("仙侠"), read.genre)
        assertEquals(VariantScope.ORIGINAL, read.scope)
    }

    /* 2. 创建 Variant */
    @Test
    fun `create variant from original`() {
        val app = container()
        val originalId = app.novels.createOriginal(title = "原著")
        val variantId = VariantId("var-1")
        val variantContext = VariantContext(baseNovelId = BaseNovelId(originalId.value), variantId = variantId)
        val created = app.novels.createVariant(context = variantContext, name = "改线", variantId = variantId)
        assertEquals(variantId, created)
    }

    /* 2b. Original 上下文不能创建 Variant */
    @Test
    fun `original context rejects create variant`() {
        val app = container()
        val originalId = app.novels.createOriginal(title = "原著")
        val originalContext = VariantContext(baseNovelId = BaseNovelId(originalId.value), variantId = null)
        assertFailsWith<IllegalArgumentException> {
            app.novels.createVariant(context = originalContext, name = "非法")
        }
    }

    /* 3. Variant 读取 Original（基座读取） */
    @Test
    fun `variant reads through to original`() {
        val app = container()
        val originalId = app.novels.createOriginal(title = "原著", synopsis = "原简介")
        val variantId = app.novels.createVariant(
            context = VariantContext(baseNovelId = BaseNovelId(originalId.value), variantId = VariantId("var-2")),
            name = "改线",
        )
        val context = app.novels.getVariantContext(originalId, variantId)
        assertTrue(context.variantId == variantId, "Variant 上下文应携带当前 VariantId")
        val base = app.novels.getNovel(NovelId(context.baseNovelId.value))
        assertNotNull(base)
        assertEquals("原著", base.title)
        assertEquals("原简介", base.synopsis)
    }

    /* 4. Override 生效（命中覆盖值 → 回退基值） */
    @Test
    fun `override takes effect on variant entity`() {
        val app = container()
        val originalId = app.novels.createOriginal(title = "原著")
        val variantId = app.novels.createVariant(
            context = VariantContext(baseNovelId = BaseNovelId(originalId.value), variantId = VariantId("var-3")),
            name = "改线",
        )
        val context = VariantContext(baseNovelId = BaseNovelId(originalId.value), variantId = variantId)

        val base = JsonPrimitive("原值")
        // 无 Override → 回退基值
        assertEquals(base, app.overrides.resolveVariantEntity(context, "t-1", base))

        // 添加 Override → 命中覆盖值
        app.overrides.addOverride(
            context = context,
            targetKind = OverridableKind.CHARACTER,
            targetId = "t-1",
            operation = OverrideOperation.OVERRIDE,
            replacedValue = JsonPrimitive("覆盖值"),
        )
        val resolved = app.overrides.resolveVariantEntity(context, "t-1", base)
        assertEquals(JsonPrimitive("覆盖值"), resolved)

        // 撤销 Override → 重新回退基值
        app.overrides.removeOverride(context, "t-1")
        assertEquals(base, app.overrides.resolveVariantEntity(context, "t-1", base))
    }

    /* 4b. Original 上下文不能添加 Override */
    @Test
    fun `original context rejects add override`() {
        val app = container()
        val originalId = app.novels.createOriginal(title = "原著")
        val originalContext = VariantContext(baseNovelId = BaseNovelId(originalId.value), variantId = null)
        val ex = assertFailsWith<ApplicationException> {
            app.overrides.addOverride(
                context = originalContext,
                targetKind = OverridableKind.CHARACTER,
                targetId = "t-1",
                operation = OverrideOperation.OVERRIDE,
            )
        }
        assertIs<ApplicationError.InvalidOperation>(ex.error)
    }

    /* 5. Vocabulary 查询 */
    @Test
    fun `vocabulary save and query by scope`() {
        val app = container()
        val vocabId = com.qianyan.model.VocabularyId("vocab-1")
        app.vocabularies.saveVocabulary(
            Vocabulary(vocabularyId = vocabId, scopeLevel = VocabularyScopeLevel.NOVEL, name = "词库"),
        )
        val found = app.vocabularies.query(VocabularyScopeLevel.NOVEL)
        assertTrue(found.any { it.vocabularyId == vocabId })
        assertTrue(app.vocabularies.query(VocabularyScopeLevel.VARIANT).none { it.vocabularyId == vocabId })
    }

    /* 5b. Vocabulary 词条保存 */
    @Test
    fun `vocabulary entry save`() {
        val app = container()
        val vocabId = com.qianyan.model.VocabularyId("vocab-2")
        app.vocabularies.saveVocabulary(Vocabulary(vocabularyId = vocabId, scopeLevel = VocabularyScopeLevel.VARIANT))
        app.vocabularies.saveEntry(
            VocabularyEntry(
                entryId = com.qianyan.model.VocabularyEntryId("entry-1"),
                vocabularyId = vocabId,
                variantId = VariantId("var-v"),
                scopeLevel = VocabularyScopeLevel.VARIANT,
                canonical = "灵石",
                replacement = "星石",
            ),
        )
        assertTrue(app.vocabularies.query(VocabularyScopeLevel.VARIANT).any { it.vocabularyId == vocabId })
    }

    /* 6. Memory 保存（Variant 上下文 → 只写 Variant 记忆） */
    @Test
    fun `memory save and query under variant context`() {
        val app = container()
        val originalId = app.novels.createOriginal(title = "原著")
        val variantId = app.novels.createVariant(
            context = VariantContext(baseNovelId = BaseNovelId(originalId.value), variantId = VariantId("var-4")),
            name = "改线",
        )
        val context = VariantContext(baseNovelId = BaseNovelId(originalId.value), variantId = variantId)
        val memoryId = app.memories.saveEntry(context = context, content = "重要记忆", layer = MemoryLayer.LONG_TERM)
        val entries = app.memories.query(context)
        assertTrue(entries.any { it.id == memoryId })
        assertEquals("重要记忆", entries.first { it.id == memoryId }.content)
        assertEquals(VariantScope.VARIANT, entries.first { it.id == memoryId }.scope)
        assertEquals(variantId, entries.first { it.id == memoryId }.variantId)
    }

    /* 7. Repository 注入成功（四个仓储全部装配） */
    @Test
    fun `repositories are injected via container`() {
        val app = container()
        assertNotNull(app.novelRepository)
        assertNotNull(app.vocabularyRepository)
        assertNotNull(app.memoryRepository)
        assertNotNull(app.backupStore)
        assertNotNull(app.novels)
        assertNotNull(app.overrides)
        assertNotNull(app.vocabularies)
        assertNotNull(app.memories)
        // 端到端可用：创建 → 读取
        val novelId = app.novels.createOriginal(title = "注入验证")
        assertNotNull(app.novels.getNovel(novelId))
    }

    /* 8. 错误转换正确（StorageException → Application 领域错误） */
    @Test
    fun `storage exceptions map to application errors`() {
        // 直接映射
        assertIs<ApplicationError.ImmutableOriginal>(ErrorMapper.map(OriginalImmutableException()).error)
        assertIs<ApplicationError.VariantBaseInvalid>(ErrorMapper.map(VariantBaseViolation()).error)
        assertIs<ApplicationError.DuplicateTarget>(ErrorMapper.map(UniqueConflictException("dup")).error)
        assertIs<ApplicationError.UnknownStorage>(ErrorMapper.map(RuntimeException("boom")).error)

        // 经 Use Case 边界转换：唯一键冲突 → DuplicateTarget（不泄露原始异常）
        val app = container()
        val originalId = app.novels.createOriginal(title = "原著")
        val variantId = app.novels.createVariant(
            context = VariantContext(baseNovelId = BaseNovelId(originalId.value), variantId = VariantId("var-5")),
            name = "改线",
        )
        val context = VariantContext(baseNovelId = BaseNovelId(originalId.value), variantId = variantId)
        app.overrides.addOverride(context, OverridableKind.CHARACTER, "same", OverrideOperation.OVERRIDE, JsonPrimitive("1"))
        val dup = assertFailsWith<ApplicationException> {
            app.overrides.addOverride(context, OverridableKind.CHARACTER, "same", OverrideOperation.OVERRIDE, JsonPrimitive("2"))
        }
        assertIs<ApplicationError.DuplicateTarget>(dup.error)

        // Variant 基座非法（不存在的基座）→ VariantBaseInvalid
        val badContext = VariantContext(baseNovelId = BaseNovelId("no-such-original"), variantId = VariantId("var-bad"))
        assertNull(app.novels.getNovel(NovelId("no-such-original")))
        // 以不存在的 Original 为基座创建 Variant → EntityNotFound（基座不存在，非 VariantBaseViolation）
        val missing = assertFailsWith<ApplicationException> {
            app.novels.createVariant(context = badContext, name = "孤儿变体")
        }
        assertIs<ApplicationError.EntityNotFound>(missing.error)
    }
}
