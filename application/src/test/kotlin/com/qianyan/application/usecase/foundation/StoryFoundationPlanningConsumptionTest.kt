package com.qianyan.application.usecase.foundation

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.qianyan.application.di.ApplicationContainer
import com.qianyan.model.ActId
import com.qianyan.model.ArcId
import com.qianyan.model.BaseNovelId
import com.qianyan.model.ChapterPlanId
import com.qianyan.model.GenreId
import com.qianyan.model.IntentType
import com.qianyan.model.NovelId
import com.qianyan.model.PlanningScope
import com.qianyan.model.ProjectId
import com.qianyan.model.ProjectSource
import com.qianyan.model.ProjectStatus
import com.qianyan.model.RequestId
import com.qianyan.model.VariantId
import com.qianyan.model.VariantScope
import com.qianyan.model.context.TargetKind
import com.qianyan.model.context.TargetRef
import com.qianyan.model.context.UserWritingRequest
import com.qianyan.model.core.Novel
import com.qianyan.model.foundation.NarrativeProfile
import com.qianyan.model.foundation.StoryDirection
import com.qianyan.model.foundation.StoryFoundation
import com.qianyan.model.foundation.WritingPolicy
import com.qianyan.model.story.ChapterPlan
import com.qianyan.provider.ChatMessage
import com.qianyan.provider.ChatRole
import com.qianyan.provider.FinishReason
import com.qianyan.provider.ProviderResponse
import com.qianyan.provider.Usage
import com.qianyan.provider.impl.MockLLMGateway
import com.qianyan.storage.db.QianyanDbFactory
import com.qianyan.storage.db.QianyanDbHandle
import kotlinx.datetime.Clock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import kotlin.io.path.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P14-F.4 · Story Foundation Planning Consumption 测试。
 * 真实 SQLite；验证 Confirmed StoryFoundation 经 PlanningContextAssembly 进入 PlanningContext.foundation，
 * 并被 Planner / Writer Agent 实际渲染；无 Foundation 时旧行为兼容；Recovery 后从 Repository 重读；仅 Original。
 */
class StoryFoundationPlanningConsumptionTest {

    private fun gateway(captured: MutableList<String>) =
        MockLLMGateway { req ->
            val content = req.messages.joinToString("\n") { it.content }
            captured += content
            val body = if (content.contains("【本章规划】")) """{"content":"正文正文正文"}""" else """{"chapterGoal":"突破瓶颈"}"""
            ProviderResponse(
                message = ChatMessage(ChatRole.ASSISTANT, buildJsonObject { put("answer", body) }.toString()),
                usage = Usage(0, 0, 0),
                finishReason = FinishReason.STOP,
            )
        }

    private fun open(
        url: String = JdbcSqliteDriver.IN_MEMORY,
        handles: MutableList<QianyanDbHandle>? = null,
        captured: MutableList<String> = mutableListOf(),
    ): ApplicationContainer {
        val handle = QianyanDbFactory.open(url)
        handles?.add(handle)
        return ApplicationContainer.fromDriver(handle.driver, analysisGateway = gateway(captured))
    }

    private fun seedNovel(app: ApplicationContainer, novelId: String) {
        app.novelRepository.createOriginal(
            Novel(
                novelId = NovelId(novelId), projectId = ProjectId("proj-$novelId"), title = "千夜",
                source = ProjectSource.ORIGINAL_NOVEL, scope = VariantScope.ORIGINAL,
                status = ProjectStatus.DRAFT, createdAt = Clock.System.now(), updatedAt = Clock.System.now(),
            ),
        )
    }

    /** 直接以"已确认"形态写入 Original StoryFoundation（模拟 F.3 Confirm 后落库的事实）。 */
    private fun seedConfirmedFoundation(app: ApplicationContainer, novelId: String): StoryFoundation {
        val f = StoryFoundation(
            novelId = NovelId(novelId),
            baseNovelId = BaseNovelId(novelId),
            scope = VariantScope.ORIGINAL,
            version = 1L,
            genre = listOf(GenreId("romance"), GenreId("fantasy_eastern")),
            direction = StoryDirection(theme = "成长", conflict = "信念之战", promise = "逆袭", storyType = "东方玄幻"),
            audience = NarrativeProfile(pov = "第三人称", readerTone = "热血"),
            policy = WritingPolicy(listOf("不OOC", "章节紧凑")),
            createdAt = Clock.System.now(),
            updatedAt = Clock.System.now(),
        )
        app.storyFoundationRepository.upsertStoryFoundation(f)
        return f
    }

    private fun request(novelId: String) = UserWritingRequest(
        requestId = RequestId("r-$novelId"),
        intentType = IntentType.CONTINUE,
        target = TargetRef(TargetKind.CHAPTER, null),
        planningScope = PlanningScope.CHAPTER,
        baseNovelId = BaseNovelId(novelId),
        variantId = null,
        scope = VariantScope.ORIGINAL,
    )

    private fun minimalPlan(novelId: String) = ChapterPlan(
        chapterPlanId = ChapterPlanId("plan-$novelId"),
        chapterId = null,
        arcId = ArcId("arc"),
        actId = ActId("act"),
        novelId = NovelId(novelId),
        variantId = null,
        scope = VariantScope.ORIGINAL,
        chapterGoal = "目标",
    )

    private fun closeAll(handles: MutableList<QianyanDbHandle>) {
        handles.forEach { (it.driver as JdbcSqliteDriver?)?.getConnection()?.close() }
        handles.clear()
    }

    /* T1：有 Confirmed Foundation → assemble 投影正确。 */
    @Test
    fun `t1 confirmed foundation projected into planning context`() {
        val app = open(); seedNovel(app, "n1")
        val f = seedConfirmedFoundation(app, "n1")

        val ctx = app.planningContextAssembly.assemble(request("n1"))

        val p = assertNotNull(ctx.foundation, "已确认 Foundation 应投影进 PlanningContext.foundation")
        assertEquals(f.genre, p.confirmedGenre)
        assertEquals("成长", p.direction.theme)
        assertEquals("信念之战", p.direction.conflict)
        assertEquals("逆袭", p.direction.promise)
        assertEquals("东方玄幻", p.direction.storyType)
        assertEquals(NarrativeProfile(pov = "第三人称", readerTone = "热血"), p.audience)
        assertEquals(WritingPolicy(listOf("不OOC", "章节紧凑")), p.writingPolicy)
    }

    /* T2：无 Foundation → foundation == null，旧字段正常。 */
    @Test
    fun `t2 no foundation keeps legacy planning context`() {
        val app = open(); seedNovel(app, "n1") // 未确认任何 Foundation

        val ctx = app.planningContextAssembly.assemble(request("n1"))

        assertNull(ctx.foundation, "无 Foundation 时 projections 应为 null")
        assertEquals("千夜", ctx.novelTitle)
        assertTrue(ctx.scope == VariantScope.ORIGINAL)
    }

    /* T3：PlannerAgent 实际渲染渲染已确认 Foundation（经真实 LLM 路径捕获 prompt）。 */
    @Test
    fun `t3 planner agent sees confirmed foundation in rendered input`() {
        val captured = mutableListOf<String>()
        val app = open(captured = captured); seedNovel(app, "n1")
        seedConfirmedFoundation(app, "n1")

        val ctx = app.planningContextAssembly.assemble(request("n1"))
        val plan = app.planner.plan(ctx) // 经 renderInput → AgentRuntime → (capturing) LLM

        assertTrue(captured.isNotEmpty(), "Planner 应已调用 LLM")
        val prompt = captured.last()
        assertTrue(prompt.contains("已确认的故事基础"), "Planner prompt 应含 Foundation 区块")
        assertTrue(prompt.contains("confirmedGenre") && prompt.contains("romance") && prompt.contains("fantasy_eastern"), "prompt=$prompt")
        assertTrue(prompt.contains("storyDirection: theme=成长, conflict=信念之战, promise=逆袭, storyType=东方玄幻"), "prompt=$prompt")
        assertTrue(prompt.contains("audience: pov=第三人称, readerTone=热血"), "prompt=$prompt")
        assertTrue(prompt.contains("writingPolicy") && prompt.contains("不OOC") && prompt.contains("章节紧凑"), "prompt=$prompt")
        assertEquals("突破瓶颈", plan.chapterGoal, "Planner 仍能正常产出 ChapterPlan")
    }

    /* T4：WriterAgent 经 PlanningContext 看到 Foundation（Planning→Writing 不丢失）。 */
    @Test
    fun `t4 writer agent sees foundation without querying repository`() {
        val captured = mutableListOf<String>()
        val app = open(captured = captured); seedNovel(app, "n1")
        seedConfirmedFoundation(app, "n1")

        val ctx = app.planningContextAssembly.assemble(request("n1"))
        val plan = minimalPlan("n1")
        val draft = app.writer.write(ctx, plan) // 经 renderInput(context, plan)

        assertTrue(captured.isNotEmpty(), "Writer 应已调用 LLM")
        val prompt = captured.last()
        assertTrue(prompt.contains("已确认的故事基础"), "Writer prompt 应含 Foundation 区块")
        assertTrue(prompt.contains("storyDirection: theme=成长"), "Writer prompt=$prompt")
        assertTrue(prompt.contains("audience: pov=第三人称"), "Writer prompt=$prompt")
        assertEquals("正文正文正文", draft.content)
    }

    /* T5：Recovery — 持久化 Repository 重读（非内存缓存）。 */
    @Test
    fun `t5 recovery reloads foundation from repository after restart`() {
        val file = Files.createTempFile("qianyan-f4-t5", ".db").toAbsolutePath()
        val url = "jdbc:sqlite:$file"
        val handles = mutableListOf<QianyanDbHandle>()
        try {
            var app = open(url, handles); seedNovel(app, "n1")
            val f = seedConfirmedFoundation(app, "n1")
            val before = app.planningContextAssembly.assemble(request("n1")).foundation
            assertNotNull(before)
            closeAll(handles) // 模拟关闭

            app = open(url, handles) // 重建容器（重启）
            val after = app.planningContextAssembly.assemble(request("n1")).foundation
            assertNotNull(after, "重启后 Foundation 应从持久化 Repository 重读")
            assertEquals(before.confirmedGenre, after.confirmedGenre)
            assertEquals(f.direction, after.direction)
            assertEquals(f.audience, after.audience)
            assertEquals(f.policy, after.writingPolicy)
        } finally {
            closeAll(handles); Files.deleteIfExists(file)
        }
    }

    /* T6：Original-only — 不读/不写 FoundationOverride。 */
    @Test
    fun `t6 original only does not touch foundation override`() {
        val app = open(); seedNovel(app, "n1")
        seedConfirmedFoundation(app, "n1")

        val ctx = app.planningContextAssembly.assemble(request("n1"))

        assertNotNull(ctx.foundation, "Original Foundation 被消费")
        // F.4 不写/不读 Override：即使装配过，Override 表无任何行、查询任意 Variant 返回 null。
        assertFalse(app.storyFoundationRepository.existsFoundationOverride(VariantId("any-variant")))
        assertNull(app.storyFoundationRepository.getFoundationOverride(VariantId("any-variant")))
        assertEquals(ctx.foundation.confirmedGenre, listOf(GenreId("romance"), GenreId("fantasy_eastern")))
    }
}