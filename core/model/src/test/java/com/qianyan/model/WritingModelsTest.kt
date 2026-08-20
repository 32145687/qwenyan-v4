package com.qianyan.model

import com.qianyan.model.writing.Draft
import com.qianyan.model.writing.DraftStatus
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** P11.1 Scaffold：写作领域模型最小契约验证（合法构造 / 默认值 / 作用域推导 / 序列化往返）。 */
class WritingModelsTest {

    private val now = Instant.fromEpochMilliseconds(1_700_000_000_000L)

    @Test
    fun `draft constructs with original scope when variantId is null`() {
        val draft = Draft(
            draftId = DraftId("d1"),
            novelId = NovelId("n1"),
            scope = VariantScope.ORIGINAL,
            content = "正文",
            status = DraftStatus.WRITTEN,
            sourceModel = "mock-v1",
            createdAt = now,
            updatedAt = now,
        )
        assertEquals(VariantScope.ORIGINAL, draft.scope)
        assertEquals(DraftStatus.WRITTEN, draft.status)
        assertNull(draft.variantId)
    }

    @Test
    fun `draft scope defaults to variant when variantId present`() {
        val draft = Draft(
            draftId = DraftId("d2"),
            novelId = NovelId("n1"),
            variantId = VariantId("v1"),
            content = "变体正文",
            createdAt = now,
            updatedAt = now,
        )
        assertEquals(VariantScope.VARIANT, draft.scope)
        assertEquals(VariantId("v1"), draft.variantId)
        // 未显式设置时默认 DRAFTING，未绑定章节/规划
        assertEquals(DraftStatus.DRAFTING, draft.status)
        assertNull(draft.chapterId)
        assertNull(draft.planId)
    }

    @Test
    fun `draft serializes and round-trips`() {
        val draft = Draft(
            draftId = DraftId("d3"),
            novelId = NovelId("n1"),
            variantId = VariantId("v2"),
            chapterId = ChapterId("c9"),
            planId = ChapterPlanId("p9"),
            content = "可序列化正文",
            status = DraftStatus.REVISED,
            sourceModel = "deepseek-v4-flash",
            createdAt = now,
            updatedAt = now,
        )
        val json = Json { ignoreUnknownKeys = true }
        val restored = json.decodeFromString<Draft>(json.encodeToString(Draft.serializer(), draft))
        assertEquals(draft, restored)
        assertEquals(DraftStatus.REVISED, restored.status)
    }
}