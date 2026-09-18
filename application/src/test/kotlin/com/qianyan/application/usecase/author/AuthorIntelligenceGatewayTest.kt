package com.qianyan.application.usecase.author

import com.qianyan.application.di.ApplicationContainer
import com.qianyan.model.NovelId
import com.qianyan.model.author.AuthorEvidenceType
import com.qianyan.model.author.PreferenceDimension
import com.qianyan.model.author.PreferenceScope
import com.qianyan.provider.impl.MockLLMGateway
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * P16 AIL-1 · AuthorIntelligenceGateway（经 ApplicationContainer 完整 DI）测试。
 * 验证：Profile、Explicit、Evidence→Candidate→Confirm、AuthorContext 投影经 seam 全链路可用。
 * 同时隐式验证 ApplicationContainer.fromDriver 的 AuthorPreferenceRepository 装配。
 */
class AuthorIntelligenceGatewayTest {

    private fun app(): ApplicationContainer = ApplicationContainer.open(analysisGateway = MockLLMGateway())

    @Test
    fun `gateway wires profile explicit and context through container`() {
        val gateway = app().authorIntelligenceGateway

        val profile = gateway.getOrCreateAuthorProfile("作者")
        assertNotNull(profile)
        assertEquals("作者", profile.displayName)

        val pref = gateway.addExplicitPreference(
            dimension = PreferenceDimension.STRUCTURE,
            statement = "作者偏好三幕式结构",
            scope = PreferenceScope.GLOBAL,
        )
        assertTrue(pref.isStable)

        val ctx = gateway.buildAuthorContext(null)
        assertTrue(ctx.preferences.any { it.preferenceId == pref.preferenceId }, "Explicit 偏好应进入 AuthorContext")
    }

    @Test
    fun `gateway supports evidence candidate confirmation and controls`() {
        val gateway = app().authorIntelligenceGateway
        val novel = NovelId("n1")

        // Evidence → Candidate（未确认）
        val evidence = gateway.recordEvidence(
            type = AuthorEvidenceType.ADOPT,
            detail = "确认 Foundation",
            source = "p15:foundation:gate",
            novelId = novel,
        )
        assertNotNull(evidence.evidenceId)
        val candidates = gateway.listCandidates()
        assertEquals(1, candidates.size, "应产生一个未确认 Candidate")
        assertTrue(gateway.buildAuthorContext(novel).isEmpty, "未确认 Candidate 不得进入 AuthorContext")

        // 确认 → Stable → 进入 AuthorContext
        val stable = gateway.confirmPreference(candidates.first().preferenceId)
        assertTrue(stable.isStable)
        assertEquals(1, gateway.buildAuthorContext(novel).preferences.size)

        // 暂停/恢复
        gateway.pausePreference(stable.preferenceId)
        assertTrue(gateway.buildAuthorContext(novel).isEmpty)
        gateway.resumePreference(stable.preferenceId)
        assertEquals(1, gateway.buildAuthorContext(novel).preferences.size)

        // 拒绝 → 衰减失效
        val rejected = gateway.rejectPreference(stable.preferenceId)
        assertNotNull(rejected.expiry)
        assertTrue(gateway.buildAuthorContext(novel).isEmpty)
    }

    @Test
    fun `collectFoundationEvidence with no p15 returns zero`() {
        val gateway = app().authorIntelligenceGateway
        assertEquals(0, gateway.collectFoundationEvidence(NovelId("n-none")), "无 P15 信号时采集 0 条")
    }
}