package com.qianyan.model.context

import com.qianyan.model.GenreId
import com.qianyan.model.IntentType
import com.qianyan.model.NovelId
import com.qianyan.model.ProjectId
import com.qianyan.model.foundation.NarrativeProfile
import com.qianyan.model.foundation.StoryDirection
import com.qianyan.model.foundation.WritingPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** Phase 4B.1 · canonical-v1 规范化编码（Addendum §E.2）。 */
class ContextPayloadCanonicalTest {

    private fun encode(payload: ContextPayload?): String = ContextPayloadCanonical.encode(payload)

    @Test
    fun `S uses utf8 byte length and empty string is zero length`() {
        // "汉字" 的 UTF-8 为 6 字节（不是 Kotlin String.length = 2）
        assertEquals("PN6:汉字A0:0:", encode(NovelPayload("汉字", emptyList(), "")))
        assertEquals("PN0:A0:0:", encode(NovelPayload("", emptyList(), "")))
    }

    @Test
    fun `null payload and empty novel payload are distinct`() {
        assertEquals("P0", encode(null))
        assertNotEquals(encode(null), encode(NovelPayload("", emptyList(), "")))
    }

    @Test
    fun `replacement tri state encodings are pairwise distinct`() {
        val nullReplacement = encode(VocabularyPayload("c", emptyList(), null))
        val emptyReplacement = encode(VocabularyPayload("c", emptyList(), ""))
        val valueReplacement = encode(VocabularyPayload("c", emptyList(), "abc"))

        assertEquals("PV1:cA0:0:", nullReplacement)
        assertEquals("PV1:cA0:1:0:", emptyReplacement)
        assertEquals("PV1:cA0:1:3:abc", valueReplacement)
        assertEquals(3, setOf(nullReplacement, emptyReplacement, valueReplacement).size)
    }

    @Test
    fun `nested foundation models use stable tags and declaration order`() {
        val payload = FoundationPayload(
            genre = listOf(GenreId("g")),
            direction = StoryDirection(theme = "t", conflict = "c", promise = "p", storyType = "s"),
            audience = NarrativeProfile(pov = "o", readerTone = "r"),
            policy = WritingPolicy(rules = listOf("rule")),
        )
        assertEquals("PFA1:1:gSD1:t1:c1:p1:sNP1:o1:rWPA1:4:rule", encode(payload))
    }

    @Test
    fun `list count and order are part of the encoding`() {
        assertEquals("PN0:A2:1:a1:b0:", encode(NovelPayload("", listOf("a", "b"), "")))
        assertEquals("PN0:A1:2:ab1:c", encode(NovelPayload("", listOf("ab"), "c")))
        assertNotEquals(
            encode(NovelPayload("", listOf("a", "b"), "")),
            encode(NovelPayload("", listOf("b", "a"), "")),
        )
    }

    @Test
    fun `different payload types never share the same encoding`() {
        val encodings = setOf(
            encode(NovelPayload("x", emptyList(), "")),
            encode(FoundationPayload(emptyList(), StoryDirection(), NarrativeProfile(), WritingPolicy())),
            encode(VocabularyPayload("x", emptyList(), null)),
        )
        assertEquals(3, encodings.size)
    }

    @Test
    fun `encoding is deterministic`() {
        val payload = VocabularyPayload("c", listOf("a"), "r")
        assertEquals(encode(payload), encode(payload))
    }

    @Test
    fun `pack version responds to payload changes and is stable for null payload`() {
        val request = ContextRequest(projectId = ProjectId("p-a"), purpose = IntentType.CONTINUE)
        val novelId = NovelId("n-a")

        fun selection(payload: ContextPayload?): ContextSelection = ContextSelection(
            items = listOf(
                ContextItem(
                    itemId = "novel:n-a",
                    source = ContextSourceKind.NOVEL,
                    content = "same content",
                    priority = ContextPriority.LOW,
                    estimatedSize = "same content".length,
                    payload = payload,
                ),
            ),
            budgetGuard = ContextBudgetGuard(budgetTokens = 1200, estimatedTokens = 3, isTruncated = false),
        )

        val base = selection(NovelPayload("书", emptyList(), ""))
        val changed = selection(NovelPayload("书", emptyList(), "简介"))

        assertEquals(
            ContextPackVersion.of(request, novelId, null, base),
            ContextPackVersion.of(request, novelId, null, selection(NovelPayload("书", emptyList(), ""))),
            "同 request/content/payload ⇒ 同版本指纹",
        )
        assertNotEquals(
            ContextPackVersion.of(request, novelId, null, base),
            ContextPackVersion.of(request, novelId, null, changed),
            "仅 payload 变化 ⇒ 版本指纹必须变化",
        )
        assertEquals(
            ContextPackVersion.of(request, novelId, null, selection(null)),
            ContextPackVersion.of(request, novelId, null, selection(null)),
            "null payload 的版本指纹稳定",
        )
    }
}
