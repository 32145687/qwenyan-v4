package com.qianyan.model.characterconsistency

import com.qianyan.model.CharacterId
import com.qianyan.model.ChapterId
import com.qianyan.model.ChapterPlanId
import com.qianyan.model.DraftId
import com.qianyan.model.memory.MemoryLayer
import com.qianyan.model.spec.IssueSeverity
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Phase 6 · CharacterConsistency 结果模型契约（T1）。 */
class CharacterConsistencyModelsTest {

    private val json = Json { encodeDefaults = true }

    private fun fullResult(): CharacterConsistencyResult = CharacterConsistencyResult(
        status = CharacterConsistencyStatus.ISSUES,
        scope = CharacterConsistencyScope.CURRENT_CHAPTER,
        findings = listOf(
            CharacterConsistencyFinding(
                type = CharacterConsistencyFindingType.TRAIT,
                severity = IssueSeverity.WARNING,
                characterId = CharacterId("c1"),
                characterName = "林岚",
                message = "性格描述冲突",
                evidence = listOf(
                    CharacterConsistencyEvidence(
                        quote = "她很胆小",
                        sourceKind = CharacterConsistencyEvidenceSource.CURRENT_DRAFT,
                        sourceId = "d1",
                        field = "content",
                        startOffset = 0,
                        endOffset = 4,
                    ),
                ),
                confidence = CharacterConsistencyConfidence.EXPLICIT,
                suggestion = "调整台词",
            ),
        ),
        checkedCharacters = listOf(CheckedCharacter(CharacterId("c1"), "林岚")),
        missingInputs = listOf(CharacterConsistencyMissingInput.CHARACTER_STATE),
        basis = CharacterConsistencyBasis(
            chapterId = ChapterId("ch1"),
            currentDraftId = DraftId("d1"),
            planId = ChapterPlanId("p1"),
            priorCanonicalDraftIds = listOf(DraftId("d0")),
            priorCanonicalGapChapterIds = listOf(ChapterId("ch-gap")),
            checkedCharacterIds = listOf(CharacterId("c1")),
            availableCharacterCount = 1,
            availableStateCount = 1,
            worldRuleCount = 1,
            eventCount = 1,
            timelineCount = 1,
            foreshadowCount = 1,
            memoryLayerCounts = listOf(
                MemoryLayerCount(MemoryLayer.ORIGINAL, 1),
                MemoryLayerCount(MemoryLayer.CURRENT_STATE, 1),
                MemoryLayerCount(MemoryLayer.LONG_TERM, 1),
                MemoryLayerCount(MemoryLayer.WRITING, 1),
            ),
            worldContextUsed = true,
        ),
        summary = "发现 1 处人物一致性问题",
        truncated = false,
    )

    @Test
    fun `result serializes and round-trips`() {
        val result = fullResult()
        val restored = json.decodeFromString<CharacterConsistencyResult>(
            json.encodeToString(CharacterConsistencyResult.serializer(), result),
        )
        assertEquals(result, restored)
        assertEquals(listOf(ChapterId("ch-gap")), restored.basis.priorCanonicalGapChapterIds)
    }

    @Test
    fun `defaults are empty and not truncated`() {
        val result = CharacterConsistencyResult(
            status = CharacterConsistencyStatus.PASS,
            scope = CharacterConsistencyScope.CURRENT_CHAPTER,
            basis = CharacterConsistencyBasis(chapterId = ChapterId("ch1")),
            summary = "未发现问题",
        )
        assertTrue(result.findings.isEmpty())
        assertTrue(result.checkedCharacters.isEmpty())
        assertTrue(result.missingInputs.isEmpty())
        assertFalse(result.truncated)
        assertTrue(result.basis.worldContextUsed)
        assertTrue(result.basis.memoryLayerCounts.isEmpty())
    }

    @Test
    fun `enum values serialize by stable names`() {
        assertEquals("\"PASS\"", json.encodeToString(CharacterConsistencyStatus.serializer(), CharacterConsistencyStatus.PASS))
        assertEquals("\"ISSUES\"", json.encodeToString(CharacterConsistencyStatus.serializer(), CharacterConsistencyStatus.ISSUES))
        assertEquals(
            "\"INSUFFICIENT_EVIDENCE\"",
            json.encodeToString(CharacterConsistencyStatus.serializer(), CharacterConsistencyStatus.INSUFFICIENT_EVIDENCE),
        )
        assertEquals(
            "\"CONTINUITY\"",
            json.encodeToString(CharacterConsistencyScope.serializer(), CharacterConsistencyScope.CONTINUITY),
        )
        assertEquals(
            "\"MEMORY\"",
            json.encodeToString(CharacterConsistencyEvidenceSource.serializer(), CharacterConsistencyEvidenceSource.MEMORY),
        )
        assertEquals(
            "\"UNRESOLVED_CHARACTER\"",
            json.encodeToString(
                CharacterConsistencyFindingType.serializer(),
                CharacterConsistencyFindingType.UNRESOLVED_CHARACTER,
            ),
        )
        assertEquals(
            "\"UNKNOWN\"",
            json.encodeToString(CharacterConsistencyConfidence.serializer(), CharacterConsistencyConfidence.UNKNOWN),
        )
    }

    @Test
    fun `unknown enum value fails decoding`() {
        assertFailsWith<SerializationException> {
            json.decodeFromString(CharacterConsistencyStatus.serializer(), "\"FATAL\"")
        }
        assertFailsWith<SerializationException> {
            json.decodeFromString(
                CharacterConsistencyEvidenceSource.serializer(),
                "\"RANDOM_SOURCE\"",
            )
        }
    }
}
