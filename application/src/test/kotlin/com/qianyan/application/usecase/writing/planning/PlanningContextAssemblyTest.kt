package com.qianyan.application.usecase.writing.planning

import com.qianyan.application.di.ApplicationContainer
import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.model.BaseNovelId
import com.qianyan.model.IntentType
import com.qianyan.model.MemoryEntryId
import com.qianyan.model.NovelId
import com.qianyan.model.PlanningScope
import com.qianyan.model.RequestId
import com.qianyan.model.VariantId
import com.qianyan.model.VariantScope
import com.qianyan.model.VocabularyEntryId
import com.qianyan.model.VocabularyId
import com.qianyan.model.context.TargetKind
import com.qianyan.model.context.TargetRef
import com.qianyan.model.context.UserWritingRequest
import com.qianyan.model.core.VariantContext
import com.qianyan.model.memory.MemoryEntry
import com.qianyan.model.memory.MemoryLayer
import com.qianyan.model.vocabulary.Vocabulary
import com.qianyan.model.vocabulary.VocabularyEntry
import com.qianyan.model.vocabulary.VocabularyScopeLevel
import com.qianyan.provider.impl.MockLLMGateway
import kotlinx.datetime.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P11.2 Planning Context Assembly 测试。
 * 验证 UserWritingRequest + Novel/Variant/Memory/Vocabulary 组装为最小 [PlanningContext]；
 * 缺 baseNovelId / 缺 Novel / Variant 不匹配 → 类型化错误。
 * 真实 SQLite 内存库 + 仓储接口（非 fake）。
 */
class PlanningContextAssemblyTest {

    private fun app() = ApplicationContainer.open(analysisGateway = MockLLMGateway())

    private fun request(novelId: NovelId, variantId: VariantId? = null) = UserWritingRequest(
        requestId = RequestId("req-assembly"),
        intentType = IntentType.PLAN,
        target = TargetRef(TargetKind.CHAPTER, null),
        planningScope = PlanningScope.CHAPTER,
        baseNovelId = BaseNovelId(novelId.value),
        variantId = variantId,
    )

    /* 合法 Original：请求进入 Context，Novel 背景 + Memory + Vocabulary 正确投影 */
    @Test
    fun `original request assembles novel memory and vocabulary into context`() {
        val app = app()
        val novelId = app.novels.createOriginal(title = "星辰大陆", genre = listOf("玄幻"), synopsis = "少年陈夜探索星辰之谜。")

        // 写入 Novel 作用域下可见的 Memory + Vocabulary
        app.memoryRepository.saveEntry(
            MemoryEntry(
                id = MemoryEntryId("mem-1"), novelId = novelId,
                scope = VariantScope.ORIGINAL, layer = MemoryLayer.LONG_TERM,
                content = "陈夜已获得第一块灵石", createdAt = Clock.System.now(), updatedAt = Clock.System.now(),
            ),
        )
        val vocabId = VocabularyId("vocab-novel")
        app.vocabularyRepository.saveVocabulary(Vocabulary(vocabularyId = vocabId, novelId = novelId, scopeLevel = VocabularyScopeLevel.NOVEL, name = "NOVEL词库"))
        app.vocabularyRepository.saveEntry(
            VocabularyEntry(entryId = VocabularyEntryId("ve1"), vocabularyId = vocabId, novelId = novelId, scopeLevel = VocabularyScopeLevel.NOVEL, canonical = "灵石", aliases = listOf("灵晶"), replacement = "星石"),
        )

        val ctx = app.planningContextAssembly.assemble(request(novelId))

        assertEquals(novelId, ctx.novelId)
        assertNull(ctx.variantId)
        assertEquals(VariantScope.ORIGINAL, ctx.scope)
        assertTrue(ctx.isOriginal)
        assertEquals("星辰大陆", ctx.novelTitle)
        assertEquals(listOf("玄幻"), ctx.novelGenre)
        assertEquals("少年陈夜探索星辰之谜。", ctx.novelSynopsis)
        // 请求本体进入 Context
        assertEquals(IntentType.PLAN, ctx.request.intentType)
        assertEquals(PlanningScope.CHAPTER, ctx.request.planningScope)
        // Memory / Vocabulary 最小投影
        assertEquals(listOf("陈夜已获得第一块灵石"), ctx.memories)
        assertEquals(1, ctx.vocabulary.size)
        assertEquals("灵石", ctx.vocabulary[0].canonical)
        assertEquals(listOf("灵晶"), ctx.vocabulary[0].aliases)
        assertEquals("星石", ctx.vocabulary[0].replacement)
        // Character 无持久化仓储 → 空投影（Known Issue）
        assertTrue(ctx.characters.isEmpty())
    }

    /* Variant：正确投影 variantName / variantDirective，词典与记忆按 Variant 作用域读取 */
    @Test
    fun `variant request projects variant scope information`() {
        val app = app()
        val novelId = app.novels.createOriginal(title = "原著")
        val variantId = VariantId("v-1")
        app.novels.createVariant(
            VariantContext(baseNovelId = BaseNovelId(novelId.value), variantId = variantId),
            name = "改编版",
            variantId = variantId,
        )
        // 手动落 Variant-directed 词库（scope=VARIANT）
        val vocabId = VocabularyId("vocab-v")
        app.vocabularyRepository.saveVocabulary(Vocabulary(vocabularyId = vocabId, variantId = variantId, scopeLevel = VocabularyScopeLevel.VARIANT, name = "VARIANT词库"))
        app.vocabularyRepository.saveEntry(
            VocabularyEntry(entryId = VocabularyEntryId("ve2"), vocabularyId = vocabId, variantId = variantId, scopeLevel = VocabularyScopeLevel.VARIANT, canonical = "星石"),
        )

        val ctx = app.planningContextAssembly.assemble(request(novelId, variantId))

        assertEquals(variantId, ctx.variantId)
        assertEquals(VariantScope.VARIANT, ctx.scope)
        assertEquals("改编版", ctx.variantName)
        assertEquals("", ctx.variantDirective)
        assertEquals("星石", ctx.vocabulary.single().canonical)
    }

    /* 缺 baseNovelId → InvalidOperation（类型化） */
    @Test
    fun `missing baseNovelId fails with InvalidOperation`() {
        val app = app()
        val bad = request(app.novels.createOriginal(title = "临时")).copy(baseNovelId = null)
        val ex = assertFailsWith<ApplicationException> { app.planningContextAssembly.assemble(bad) }
        assertIs<ApplicationError.InvalidOperation>(ex.error)
    }

    /* Novel 不存在 → EntityNotFound */
    @Test
    fun `missing novel fails with EntityNotFound`() {
        val app = app()
        val ex = assertFailsWith<ApplicationException> {
            app.planningContextAssembly.assemble(request(NovelId("ghost-novel")))
        }
        assertIs<ApplicationError.EntityNotFound>(ex.error)
    }

    /* Variant 不属于该 Novel → VariantMismatch */
    @Test
    fun `variant not belonging to novel fails with VariantMismatch`() {
        val app = app()
        val novelA = app.novels.createOriginal(title = "小说A")
        val novelB = app.novels.createOriginal(title = "小说B")
        val variantB = VariantId("v-b")
        app.novels.createVariant(
            VariantContext(baseNovelId = BaseNovelId(novelB.value), variantId = variantB),
            name = "B派生",
            variantId = variantB,
        )

        val ex = assertFailsWith<ApplicationException> {
            app.planningContextAssembly.assemble(request(novelA, variantB))
        }
        assertIs<ApplicationError.VariantMismatch>(ex.error)
    }
}