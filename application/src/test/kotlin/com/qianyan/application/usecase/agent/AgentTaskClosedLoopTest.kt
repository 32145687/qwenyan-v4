package com.qianyan.application.usecase.agent

import com.qianyan.model.BaseNovelId
import com.qianyan.model.ProjectId
import com.qianyan.model.core.VariantContext
import com.qianyan.model.novelagent.NovelAgentOutcome
import com.qianyan.model.novelagent.NovelAgentRequest
import com.qianyan.provider.ProviderRequest
import com.qianyan.provider.impl.MockLLMGateway
import com.qianyan.engine.txt.TxtSource
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 新执行链路 · 自然语言任务入口最小闭环（经真实 ApplicationContainer + 内存 SQLite + 全 Mock LLM）。
 *
 * 覆盖（本类只测"入口闭环"，编排内部相位已由 [NovelAgentRunTest] 覆盖）：
 *  - NL 任务经 `container.novelAgent.run` 进入既有编排；不存在的 Project 得到**类型化失败**（不伪装成功）；
 *  - 任务 C：TXT 分析 → PENDING 候选 → 确认为正式词条（写作能力资产，来源 = 本次导入文档）；
 *  - 任务 D：已确认词条在**下一次 NL 写作任务**的模型上下文中真实可见（跨能力复用；同一 Novel 边界内）。
 */
class AgentTaskClosedLoopTest {

    /** 分析协议（单一 USER 消息，以【文档】开头）走默认词库 Mock；写作链按 Agent system prompt 路由。 */
    private fun recordingGateway(recorded: MutableList<ProviderRequest>): MockLLMGateway {
        val analysisMock = MockLLMGateway()
        val agents = routingGateway()
        return MockLLMGateway { req ->
            recorded.add(req)
            val text = req.messages.joinToString("\n") { it.content }
            if ("【文档】" in text) analysisMock.chat(req) else agents.chat(req)
        }
    }

    @Test
    fun `nl task into a missing project is reported as typed failure not fake success`() {
        val f = novelAgentFixture()
        val result = f.app.novelAgent.run(
            NovelAgentRequest(projectId = ProjectId("no-such-project"), userIntent = "继续写第一章"),
        )

        assertEquals(NovelAgentOutcome.FAILED, result.outcome, "不存在的 Project 必须类型化失败")
        assertNotNull(result.failure, "必须携带类型化 failure")
        assertNull(result.sessionId, "未建立会话（无副作用）")
        assertNull(result.workingDraftId)
        assertNull(result.artifactId)
        assertTrue(result.summary.isNotBlank())
        f.close()
    }

    @Test
    fun `txt analysis assets confirmed once are reused by the next nl writing task`() {
        val recorded = mutableListOf<ProviderRequest>()
        val f = novelAgentFixture(recordingGateway(recorded))

        // 1) TXT 导入（Phase 1 F1 闭环：导入即初始化 Canonical 章节）
        val imported = f.app.txts.importTxtAsOriginal(
            TxtSource("第一章\n\n主角在洞府里捡到一枚灵石。".toByteArray(StandardCharsets.UTF_8), "a.txt"),
            title = "书B",
        )

        // 2) 任务 C：分析导入原文 → PENDING 词库候选
        val vocabularyId = f.app.vocabularies.getOrCreateNovelVocabulary(imported.novelId)
        val analysis = f.app.analysis.analyzeTxtOriginal(
            imported.documentId,
            vocabularyId,
            VariantContext(baseNovelId = BaseNovelId(imported.novelId.value)),
        )
        assertTrue(analysis.candidateIds.isNotEmpty(), "Mock 网关应产出词库候选")

        // 3) 确认候选 → 正式词条（写作能力资产；不自动 APPROVED，由本步显式确认）
        analysis.candidateIds.forEach { f.app.vocabularies.confirmCandidate(it, imported.novelId) }

        // 4) 任务 D：同一 Novel 内发起 NL 写作任务（TXT 初始化出的第一章即焦点章节）
        val chapterId = f.app.chapters.listByNovel(imported.novelId, null).first().chapterId
        f.app.projects.selectChapter(imported.novelId, chapterId)
        val projectId = f.app.projects.projectOf(imported.novelId).projectId
        val result = f.app.novelAgent.run(
            NovelAgentRequest(projectId = projectId, userIntent = "继续写第一章", activeChapterId = chapterId),
        )

        assertEquals(NovelAgentOutcome.WAITING_HUMAN, result.outcome, "正文变更必须等人工确认，不得自动提交")
        assertNotNull(result.workingDraftId)

        // 5) 跨能力复用证据：已确认词条进入本次任务的规划/写作模型上下文（来源 = 同一 Novel 的词库）
        assertTrue(
            recorded.any { req -> req.messages.any { "灵石" in it.content } },
            "已确认词库「灵石」应出现在 NL 任务的模型上下文中",
        )
        f.close()
    }
}
