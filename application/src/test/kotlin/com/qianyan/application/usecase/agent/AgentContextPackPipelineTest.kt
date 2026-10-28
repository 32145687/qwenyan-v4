package com.qianyan.application.usecase.agent

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.qianyan.application.di.ApplicationContainer
import com.qianyan.application.usecase.context.ContextBuildScope
import com.qianyan.application.usecase.context.ContextSource
import com.qianyan.application.usecase.context.ContextEngineUseCases
import com.qianyan.application.usecase.context.defaultContextSources
import com.qianyan.model.BaseNovelId
import com.qianyan.model.VariantId
import com.qianyan.model.context.ContextItem
import com.qianyan.model.context.ContextPriority
import com.qianyan.model.context.ContextRequest
import com.qianyan.model.context.ContextSourceKind
import com.qianyan.model.context.NovelPayload
import com.qianyan.model.core.VariantContext
import com.qianyan.model.novelagent.NovelAgentErrorCodes
import com.qianyan.model.novelagent.NovelAgentOutcome
import com.qianyan.provider.ChatMessage
import com.qianyan.provider.ChatRole
import com.qianyan.provider.FinishReason
import com.qianyan.provider.ProviderResponse
import com.qianyan.provider.Usage
import com.qianyan.provider.impl.MockLLMGateway
import com.qianyan.storage.db.QianyanDbFactory
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 4B.1 · Agent Task 的 Target Context 统一入口与 Pack 实际消费。
 *
 * 证据链：
 *  - 每个 Agent Task 只调用一次 `ContextEngine.build`（通过统计 Source.candidates 调用数）；
 *  - Planner / Writer 的真实模型请求包含**仅存在于 Pack payload** 的 marker（Repository 中不存在）；
 *  - J-3 variant 不一致时在 Context Engine 之前 fail-fast（Source 调用数为 0）。
 */
class AgentContextPackPipelineTest {

    /** 用 Pack-only marker 替换 NOVEL 来源：若仍走旧 Repository 装配，prompt 不会含该 marker。 */
    private class FakeNovelSource : ContextSource {
        override val kind: ContextSourceKind = ContextSourceKind.NOVEL

        override fun candidates(scope: ContextBuildScope, request: ContextRequest): List<ContextItem> =
            listOf(
                ContextItem(
                    itemId = "novel:pack-only",
                    source = kind,
                    content = "PACK-ONLY-MARKER",
                    priority = ContextPriority.LOW,
                    estimatedSize = "PACK-ONLY-MARKER".length,
                    payload = NovelPayload("PACK-ONLY-MARKER", emptyList(), ""),
                ),
            )
    }

    private class CountingSource(
        private val delegate: ContextSource,
        private val onCall: () -> Unit,
    ) : ContextSource {
        override val kind: ContextSourceKind get() = delegate.kind

        override fun candidates(scope: ContextBuildScope, request: ContextRequest): List<ContextItem> {
            onCall()
            return delegate.candidates(scope, request)
        }
    }

    private fun recordingGateway(prompts: MutableList<String>): MockLLMGateway = MockLLMGateway { req ->
        prompts += req.messages.joinToString("\n") { it.content }
        val system = req.messages.first { it.role == ChatRole.SYSTEM }.content
        val body = when {
            "StoryPlannerAgent" in system ->
                """{"chapterGoal":"目标","mainConflict":"冲突","expectedEvents":["事件"]}"""

            "StoryWriterAgent" in system -> buildJsonObject { put("content", "第一章正文（新写）。") }.toString()
            "StoryCriticAgent" in system -> """{"passed":true}"""
            "StoryRevisionAgent" in system -> buildJsonObject { put("content", "修订正文。") }.toString()
            else -> buildJsonObject { put("content", "占位") }.toString()
        }
        ProviderResponse(
            message = ChatMessage(ChatRole.ASSISTANT, buildJsonObject { put("answer", body) }.toString()),
            usage = Usage(10, 10, 20),
            finishReason = FinishReason.STOP,
        )
    }

    private fun engineWithSources(
        app: ApplicationContainer,
        onSourceCall: () -> Unit,
    ): ContextEngineUseCases {
        val base = defaultContextSources(
            projects = app.projects,
            novels = app.novels,
            chapters = app.chapters,
            writer = app.writerUseCases,
            vocabularyRepository = app.vocabularyRepository,
            foundations = app.storyFoundationRepository,
        )
        val sources = base.filter { it.kind != ContextSourceKind.NOVEL }
            .map { CountingSource(it, onSourceCall) } +
            CountingSource(FakeNovelSource(), onSourceCall)
        return ContextEngineUseCases(
            projects = app.projects,
            chapters = app.chapters,
            sessions = app.agentSessions,
            sources = sources,
            errorMapper = app.errorMapper,
        )
    }

    private fun agentOf(app: ApplicationContainer, engine: ContextEngineUseCases): NovelAgent = NovelAgent(
        projects = app.projects,
        chapters = app.chapters,
        sessions = app.agentSessions,
        activities = app.activities,
        toolCallLogs = app.toolCallLogs,
        skillRegistry = app.skillRegistry,
        contextEngine = engine,
        productTools = app.productTools,
        workingDrafts = app.workingDrafts,
        changes = app.changes,
        commits = app.commits,
        actionPolicy = app.actionPolicy,
        planner = app.planner,
        writer = app.writer,
        critic = app.critic,
        rewriter = app.rewriter,
        characterConsistency = app.characterConsistency,
        characterConsistencyAgent = app.characterConsistencyAgent,
        planningContexts = app.planningContextAssembly,
        reader = app.writerUseCases,
        // P0-3B：测试真实桥接路径（候选进入正式章节 Workflow）
        workflowGateway = app.workflowFacade,
        errorMapper = app.errorMapper,
    )

    @Test
    fun `agent task builds one pack and both planner and writer requests consume its payload`() {
        val handle = QianyanDbFactory.open(JdbcSqliteDriver.IN_MEMORY)
        val prompts = mutableListOf<String>()
        val app = ApplicationContainer.fromDriver(handle.driver, recordingGateway(prompts))
        val fixture = NovelAgentFixture(app, handle)
        val world = seedNovelWorld(fixture)

        var sourceCalls = 0
        val engine = engineWithSources(app) { sourceCalls++ }
        val agent = agentOf(app, engine)

        val result = agent.run(request(world, "继续写第一章"))

        assertEquals(NovelAgentOutcome.WAITING_HUMAN, result.outcome)
        assertNotNull(result.contextPackId)
        assertNotNull(result.contextPackVersion)
        assertEquals(7, sourceCalls, "一次 Agent Task 只能构建一次 ContextPack（7 个 Source 各调用一次）")

        assertTrue(prompts.any { "PACK-ONLY-MARKER" in it }, "Planner/Writer 请求必须消费 Pack payload 内容")
        assertFalse(prompts.any { "书A" in it }, "覆盖字段不得回退到 Repository 读取（旧装配路径）")
        handle.driver.close()
    }

    @Test
    fun `variant mismatch aborts before context engine build`() {
        val handle = QianyanDbFactory.open(JdbcSqliteDriver.IN_MEMORY)
        val prompts = mutableListOf<String>()
        val app = ApplicationContainer.fromDriver(handle.driver, recordingGateway(prompts))
        val fixture = NovelAgentFixture(app, handle)
        val world = seedNovelWorld(fixture)

        val variantId = VariantId("var-j3")
        app.novels.createVariant(
            context = VariantContext(baseNovelId = BaseNovelId(world.novelId.value), variantId = variantId),
            name = "改线",
            variantId = variantId,
        )
        val variantChapter = app.chapters.createNextChapter("V章", world.novelId, variantId).chapterId

        var sourceCalls = 0
        val engine = engineWithSources(app) { sourceCalls++ }
        val agent = agentOf(app, engine)

        val result = agent.run(
            request(world, "继续写第一章", activeChapterId = variantChapter, activeVariantId = null),
        )

        assertEquals(NovelAgentErrorCodes.SCOPE_MISMATCH, result.failure?.code)
        assertNull(result.contextPackId, "fail-fast 分支不得构建 ContextPack")
        assertEquals(0, sourceCalls, "variant 不一致时 Context Engine 构建次数必须为 0")
        handle.driver.close()
    }
}
