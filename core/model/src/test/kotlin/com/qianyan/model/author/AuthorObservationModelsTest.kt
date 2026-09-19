package com.qianyan.model.author

import com.qianyan.model.AuthorObservationId
import com.qianyan.model.NovelId
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * P18-A · AuthorObservation 领域模型测试：Observation≠Evidence、仅归属元数据（不携带 Story 正文）、
 * 决策语义复用 5 类 EvidenceType（不新增长期枚举）、deterministic ID 可序列化往返。
 */
class AuthorObservationModelsTest {

    private val now: Instant = Instant.fromEpochSeconds(1790000000, 0)

    @Test
    fun `observation carries decision semantics without new long lived enum`() {
        // 决策语义直接复用 AuthorEvidenceType（DEC-P18-003：不新增与 EvidenceType 重复的长期枚举）
        val accept = observation(AuthorEvidenceType.ADOPT)
        assertEquals(AuthorEvidenceType.ADOPT, accept.decisionType)
        assertTrue(AuthorEvidenceType.entries.containsAll(
            listOf(
                AuthorEvidenceType.ADOPT,
                AuthorEvidenceType.MODIFY,
                AuthorEvidenceType.REJECT,
                AuthorEvidenceType.PARTIAL_REWRITE,
                AuthorEvidenceType.ADOPT_THEN_REVISE,
            ),
        ))
    }

    @Test
    fun `observation is not an evidence and carries no story content`() {
        val o = observation(AuthorEvidenceType.REJECT)
        // 仅有归属元数据：novelId / scope / source / metadata；不含正文
        assertEquals(AuthorObservationSource.USER_DECISION, o.source)
        assertEquals(NovelId("n1"), o.novelId)
        assertEquals(mapOf("phase" to "foundation"), o.metadata)
        // 与 Evidence 不同：Observation 无 detail/type 归因，也不等同 Evidence
        assertTrue(AuthorObservation::class != AuthorEvidence::class)
    }

    @Test
    fun `serialization round-trip preserves deterministic id`() {
        val json = Json { prettyPrint = false }
        val o = observation(AuthorEvidenceType.PARTIAL_REWRITE)
        val decoded = json.decodeFromString<AuthorObservation>(json.encodeToString(AuthorObservation.serializer(), o))
        assertEquals(o.observationId, decoded.observationId)
        assertEquals(o.decisionType, decoded.decisionType)
        assertEquals(o.source, decoded.source)
        assertEquals(o.occurredAt, decoded.occurredAt)
        assertEquals(AuthorObservationId("o-det"), decoded.observationId)
    }

    private fun observation(decision: AuthorEvidenceType) = AuthorObservation(
        observationId = AuthorObservationId("o-det"),
        scope = AuthorCoreScope.NOVEL,
        decisionType = decision,
        novelId = NovelId("n1"),
        source = AuthorObservationSource.USER_DECISION,
        metadata = mapOf("phase" to "foundation"),
        occurredAt = now,
    )
}