package com.qianyan.model.author

import com.qianyan.model.AuthorEvidenceId
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * P17 · AuthorCore 领域模型测试（纯领域）：Core 生命周期 / Scope / Pattern 结构化 / Candidate 聚合字段 / EvidenceLink 引用。
 */
class AuthorCoreModelsTest {

    private val t: Instant = Instant.fromEpochSeconds(1790000000, 0)

    @Test
    fun `authorCore carries lifecycle scope and confidence without story data`() {
        val core = AuthorCore(
            coreId = com.qianyan.model.AuthorCoreId("c-1"),
            version = 1L,
            scope = AuthorCoreScope.GLOBAL,
            status = AuthorCoreStatus.STABLE,
            confirmed = true,
            confidence = Confidence(0.8),
            corePatternKey = "foundation:direction",
            createdAt = t,
            updatedAt = t,
        )
        assertEquals(AuthorCoreStatus.STABLE, core.status)
        assertNull(core.supersededBy)
        assertEquals("foundation:direction", core.corePatternKey)
    }

    @Test
    fun `pattern is structured with stable string key not a habit enum`() {
        val pattern = AuthorCorePattern(
            patternId = com.qianyan.model.AuthorCorePatternId("p-1"),
            patternKey = "foundation:direction",
            statement = "作者在长期创作中稳定地逐步揭示关键信息",
            condition = "当故事类型为悬疑时",
            direction = "延迟揭示关键线索",
            scope = AuthorCoreScope.GLOBAL,
            evidenceRefs = listOf(AuthorEvidenceId("e1"), AuthorEvidenceId("e2")),
            createdAt = t,
            updatedAt = t,
        )
        assertEquals("foundation:direction", pattern.patternKey, "patternKey 应为稳定字符串键，非 enum")
        assertEquals(2, pattern.evidenceRefs.size)
    }

    @Test
    fun `candidate separates positive negative weighted and contradiction`() {
        val cand = AuthorCoreCandidate(
            candidateId = com.qianyan.model.AuthorCoreCandidateId("cd-1"),
            patternKey = "foundation:direction",
            scope = AuthorCoreScope.GLOBAL,
            statement = "…",
            positiveEvidence = 3,
            negativeEvidence = 1,
            observationCount = 4,
            weightedScore = 2.4,
            consistency = 0.75,
            recency = t,
            contradictionCount = 1,
            confidence = Confidence(0.6),
            createdAt = t,
            updatedAt = t,
        )
        assertEquals(4, cand.observationCount)
        assertEquals(1, cand.contradictionCount)
        assertEquals(3, cand.positiveEvidence)
    }

    @Test
    fun `evidenceLink stores only references not content`() {
        val link = AuthorCoreEvidenceLink(
            linkId = com.qianyan.model.AuthorCoreEvidenceLinkId("l-1"),
            corePatternKey = "foundation:direction",
            evidenceId = AuthorEvidenceId("e1"),
            createdAt = t,
        )
        assertEquals("e1", link.evidenceId.value)
        assertEquals("foundation:direction", link.corePatternKey)
    }

    @Test
    fun `three scope layering global novel context`() {
        assertEquals(3, AuthorCoreScope.values().size)
        assertEquals(
            listOf(AuthorCoreScope.GLOBAL, AuthorCoreScope.NOVEL, AuthorCoreScope.CONTEXT),
            AuthorCoreScope.values().toList(),
        )
    }

    @Test
    fun `core lite projection is minimal and leak-free`() {
        val lite = AuthorContext.AuthorCoreLite(
            patternKey = "foundation:direction",
            statement = "逐步揭示关键信息",
            confidence = Confidence(0.8),
            scope = AuthorCoreScope.GLOBAL,
            condition = null,
        )
        assertEquals("foundation:direction", lite.patternKey)
        assertEquals("逐步揭示关键信息", lite.statement)
        assertNull(lite.condition)
    }
}