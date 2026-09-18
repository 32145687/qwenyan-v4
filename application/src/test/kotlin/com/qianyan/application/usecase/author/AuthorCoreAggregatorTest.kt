package com.qianyan.application.usecase.author

import com.qianyan.model.AuthorCoreCandidateId
import com.qianyan.model.AuthorEvidenceId
import com.qianyan.model.NovelId
import com.qianyan.model.author.AuthorCoreCandidate
import com.qianyan.model.author.AuthorCoreScope
import com.qianyan.model.author.AuthorCoreStatus
import com.qianyan.model.author.AuthorEvidence
import com.qianyan.model.author.AuthorEvidenceType
import com.qianyan.model.author.Confidence
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * P17 · AuthorCoreAggregator 测试（DEC-P17-005/006）：正向/反向/矛盾/recency/consistency/confidence 封顶；非 `count*0.1`。
 */
class AuthorCoreAggregatorTest {

    private val t: Instant = Instant.fromEpochSeconds(1790000000, 0)
    private val novel = NovelId("n1")

    private fun shell(now: Instant = t) = AuthorCoreCandidate(
        candidateId = AuthorCoreCandidateId("cd-1"),
        patternKey = "core:foundation",
        scope = AuthorCoreScope.GLOBAL,
        statement = "…",
        recency = now,
        confidence = Confidence.HALF,
        status = AuthorCoreStatus.CANDIDATE,
        createdAt = now,
        updatedAt = now,
    )

    private fun ev(type: AuthorEvidenceType, id: String, at: Instant = t) = AuthorEvidence(
        evidenceId = AuthorEvidenceId(id),
        novelId = novel,
        type = type,
        source = "test",
        observedAt = at,
    )

    @Test
    fun `positive evidence increases weighted score and confidence`() {
        var c = AuthorCoreAggregator.aggregate(shell(), ev(AuthorEvidenceType.ADOPT, "e1"), t)
        assertEquals(1.0, c.weightedScore)
        assertEquals(1, c.observationCount)
        assertEquals(1, c.positiveEvidence)
        assertEquals(0.65, c.confidence.value, 1e-9)
    }

    @Test
    fun `repeat adopt accumulates but no contradiction`() {
        var c = shell()
        c = AuthorCoreAggregator.aggregate(c, ev(AuthorEvidenceType.ADOPT, "e1"), t)
        c = AuthorCoreAggregator.aggregate(c, ev(AuthorEvidenceType.ADOPT, "e2"), t)
        assertEquals(2.0, c.weightedScore)
        assertEquals(0, c.contradictionCount)
        assertEquals(0.8, c.confidence.value, 1e-9)
    }

    @Test
    fun `reject is reverse evidence and increments contradiction`() {
        var c = shell()
        c = AuthorCoreAggregator.aggregate(c, ev(AuthorEvidenceType.ADOPT, "e1"), t)
        c = AuthorCoreAggregator.aggregate(c, ev(AuthorEvidenceType.REJECT, "e2"), t)
        assertEquals(1, c.contradictionCount, "方向翻转应计矛盾")
        assertEquals(1, c.negativeEvidence)
        assertEquals(0.0, c.weightedScore, 1e-9) // 1.0 * decay(0) + (-1.0)
        assertEquals(0.5, c.consistency, 1e-9)
    }

    @Test
    fun `modify is weak positive not equal to like`() {
        val c = AuthorCoreAggregator.aggregate(shell(), ev(AuthorEvidenceType.MODIFY, "e1"), t)
        assertEquals(0.25, c.weightedScore, 1e-9, "MODIFY 不能等价于 ADOPT")
    }

    @Test
    fun `old evidence is recency-decayed with floor`() {
        val old = Instant.fromEpochSeconds(t.epochSeconds - 200 * 86400, 0)
        val c = AuthorCoreAggregator.aggregate(shell(), ev(AuthorEvidenceType.ADOPT, "e-old", old), t)
        assertEquals(0.1, c.weightedScore, 1e-9, "200 天前证据应被衰减到下限")
    }

    @Test
    fun `confidence is capped at ceiling`() {
        var c = shell()
        repeat(10) { i -> c = AuthorCoreAggregator.aggregate(c, ev(AuthorEvidenceType.ADOPT, "e$i"), t) }
        assertEquals(AuthorCoreAggregator.CONFIDENCE_CEILING, c.confidence.value, 1e-9, "confidence 不得超过 0.9")
    }

    @Test
    fun `newer evidence contributes more than older`() {
        val old = Instant.fromEpochSeconds(t.epochSeconds - 90 * 86400, 0)
        var c = shell()
        c = AuthorCoreAggregator.aggregate(c, ev(AuthorEvidenceType.ADOPT, "e-old", old), t)
        c = AuthorCoreAggregator.aggregate(c, ev(AuthorEvidenceType.ADOPT, "e-new", t), t)
        assertTrue(c.weightedScore < 2.0, "存在 recency 衰减时无法达到两次满额")
        assertTrue(c.weightedScore > 1.0, "近期证据贡献应显著更高")
    }
}