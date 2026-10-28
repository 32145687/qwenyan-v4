package com.qianyan.application.usecase.agent

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Phase 4 审计修正：只有真正消费 ContextPack 的 Skill 才构建/发布 Snapshot。
 *
 * 改写/分析当前不消费 Pack，因此不得生成无人消费的 Snapshot，也不得发布到 Store。
 */
class NovelAgentContextConsumptionTest {

    @Test
    fun `rewrite and analyze do not build or publish an unused pack`() {
        val f = novelAgentFixture()
        val w = seedNovelWorld(f)

        val rewrite = f.app.novelAgent.run(request(w, "重写第一章"))
        val rewriteSession = assertNotNull(rewrite.sessionId)
        assertNull(rewrite.contextPackId, "改写不消费 Pack，不得构建")
        assertNull(f.app.contextPackStore.find(rewriteSession, w.projectId), "改写不得发布未消费 Pack")

        val analyze = f.app.novelAgent.run(request(w, "分析人物关系"))
        val analyzeSession = assertNotNull(analyze.sessionId)
        assertNull(analyze.contextPackId, "分析不消费 Pack，不得构建")
        assertNull(f.app.contextPackStore.find(analyzeSession, w.projectId), "分析不得发布未消费 Pack")

        f.close()
    }

    @Test
    fun `writing still publishes exactly the consumed pack`() {
        val f = novelAgentFixture()
        val w = seedNovelWorld(f)

        val result = f.app.novelAgent.run(request(w, "继续写第一章"))
        val sessionId = assertNotNull(result.sessionId)
        val stored = assertNotNull(f.app.contextPackStore.find(sessionId, w.projectId))
        assertNotNull(result.contextPackId)
        assertNotNull(result.contextPackVersion)
        kotlin.test.assertEquals(result.contextPackId, stored.pack.packId)
        kotlin.test.assertEquals(result.contextPackVersion, stored.pack.packVersion)

        f.close()
    }
}
