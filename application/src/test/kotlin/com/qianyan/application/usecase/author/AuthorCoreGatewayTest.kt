package com.qianyan.application.usecase.author

import com.qianyan.application.di.ApplicationContainer
import com.qianyan.model.AuthorEvidenceId
import com.qianyan.model.author.AuthorCoreScope
import com.qianyan.model.author.AuthorCoreStatus
import com.qianyan.model.author.AuthorEvidenceType
import com.qianyan.provider.impl.MockLLMGateway
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * P17 · AuthorCoreGateway（经 ApplicationContainer 完整 DI）测试：view/confirm/reset/pause/幂等 全链路可用。
 */
class AuthorCoreGatewayTest {

    private fun app(): ApplicationContainer = ApplicationContainer.open(analysisGateway = MockLLMGateway())

    @Test
    fun `gateway drives evidence candidate confirm and reset`() {
        val g = app().authorCoreGateway

        // Evidence → Candidate（幂等）
        assertTrue(g.recordCoreEvidence(AuthorEvidenceType.ADOPT, AuthorEvidenceId("g-1")))
        assertEquals(false, g.recordCoreEvidence(AuthorEvidenceType.ADOPT, AuthorEvidenceId("g-1")), "重复采集幂等")
        assertTrue(g.recordCoreEvidence(AuthorEvidenceType.ADOPT, AuthorEvidenceId("g-2")))

        val cand = g.viewCandidates().first()
        assertTrue(g.viewCores().isEmpty(), "确认前无长期 Core")

        val core = g.confirmCoreCandidate(cand.candidateId)
        assertEquals(AuthorCoreStatus.STABLE, core.status)
        assertTrue(g.viewCores().any { it.coreId == core.coreId })

        // pause/resume
        g.pauseCoreLearning()
        assertTrue(g.isLearningPaused())
        g.resumeCoreLearning()
        assertFalse(g.isLearningPaused())

        // reset 只清 Core 学习结果
        g.resetCore()
        assertTrue(g.viewCores().isEmpty())
        assertTrue(g.viewCandidates().isEmpty())
    }

    @Test
    fun `gateway supports novel scope and record collect from p15 absent`() {
        val g = app().authorCoreGateway
        val novel = com.qianyan.model.NovelId("n1")
        assertTrue(g.recordCoreEvidence(AuthorEvidenceType.ADOPT, AuthorEvidenceId("n-g-1"), scope = AuthorCoreScope.NOVEL, novelId = novel))
        assertEquals(1, g.viewCandidates().size)
        assertEquals(0, g.collectFoundationCoreEvidence(novel), "无 P15 信号时采集 0")
    }
}