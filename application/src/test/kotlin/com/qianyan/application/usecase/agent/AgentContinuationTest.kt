package com.qianyan.application.usecase.agent

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.qianyan.application.di.ApplicationContainer
import com.qianyan.model.BaseNovelId
import com.qianyan.model.ChapterId
import com.qianyan.model.DraftId
import com.qianyan.model.NovelId
import com.qianyan.model.VariantId
import com.qianyan.model.core.VariantContext
import com.qianyan.model.novelagent.NovelAgentContinuationReasons
import com.qianyan.model.novelagent.NovelAgentOutcome
import com.qianyan.model.novelagent.NovelAgentRequest
import com.qianyan.model.novelagent.NovelAgentResult
import com.qianyan.model.writing.Draft
import com.qianyan.model.writing.DraftFormat
import com.qianyan.model.writing.DraftStatus
import com.qianyan.provider.ChatMessage
import com.qianyan.provider.ChatRole
import com.qianyan.provider.FinishReason
import com.qianyan.provider.ProviderRequest
import com.qianyan.provider.ProviderResponse
import com.qianyan.provider.Usage
import com.qianyan.provider.impl.MockLLMGateway
import com.qianyan.storage.db.QianyanDbFactory
import com.qianyan.storage.db.QianyanDbHandle
import kotlinx.datetime.Instant
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * O-42 · NL 跨章续写接入前章 Canonical（可降级、但不得静默）。
 *
 * V-1 第 1 章（回归旧行为）；V-2 第 2 章 + 前章 CONFIRMED（Planner 1200 / Writer 4000）；
 * V-3 第 2 章 + 前章无 Canonical（NO_CANONICAL，降级仍产出）；
 * V-4 第 2 章 + 前章 carrier 非 CONFIRMED（CARRIER_NOT_CONFIRMED，不得硬拒）；
 * V-5 Original / Variant 各自取本作用域前章，跨 variant 不串。
 */
class AgentContinuationTest {

    private val fixed = Instant.parse("2026-01-01T00:00:00Z")

    private class Fixture(gateway: MockLLMGateway, val records: MutableList<ProviderRequest>) {
        val handle: QianyanDbHandle = QianyanDbFactory.open(JdbcSqliteDriver.IN_MEMORY)
        val app: ApplicationContainer = ApplicationContainer.fromDriver(handle.driver, gateway)
        fun close() = handle.driver.close()
    }

    /** 记录每次 LLM 请求；按 Agent system prompt 返回合法 JSON。 */
    private fun gateway(records: MutableList<ProviderRequest>, writerContent: String = "新章正文。"): MockLLMGateway =
        MockLLMGateway { request ->
            records += request
            val system = request.messages.first { it.role == ChatRole.SYSTEM }.content
            val body = when {
                "StoryPlannerAgent" in system ->
                    """{"chapterGoal":"推进","mainConflict":"冲突","expectedEvents":["事件"]}"""

                "StoryWriterAgent" in system -> buildJsonObject { put("content", writerContent) }.toString()
                "WorldModelUpdateAgent" in system -> """{"facts":[]}"""
                "StoryCriticAgent" in system -> """{"passed":true}"""
                else -> buildJsonObject { put("content", "占位") }.toString()
            }
            ProviderResponse(
                message = ChatMessage(ChatRole.ASSISTANT, buildJsonObject { put("answer", body) }.toString()),
                usage = Usage(10, 10, 20),
                finishReason = FinishReason.STOP,
            )
        }

    private fun fixture(writerContent: String = "新章正文。"): Fixture {
        val records = mutableListOf<ProviderRequest>()
        return Fixture(gateway(records, writerContent), records)
    }

    private var seq = 0
    private fun draftId(prefix: String): DraftId = DraftId("$prefix-${seq++}")

    private fun chapter(f: Fixture, novelId: NovelId, title: String, variantId: VariantId? = null): ChapterId =
        f.app.chapters.createNextChapter(title, novelId, variantId).chapterId

    private fun draft(
        f: Fixture,
        novelId: NovelId,
        chapterId: ChapterId,
        content: String,
        status: DraftStatus,
        variantId: VariantId? = null,
        prefix: String = "d",
    ): Draft {
        val draft = Draft(
            draftId = draftId(prefix),
            novelId = novelId,
            variantId = variantId,
            chapterId = chapterId,
            content = content,
            format = DraftFormat.CONTROLLED_MARKDOWN,
            status = status,
            createdAt = fixed,
            updatedAt = fixed,
        )
        f.app.draftRepository.save(draft)
        return draft
    }

    /** 真实 Canonical（CONFIRMED 载体 + CommitHistory）。 */
    private fun confirmedCanonical(
        f: Fixture,
        novelId: NovelId,
        chapterId: ChapterId,
        content: String,
        variantId: VariantId? = null,
        prefix: String = "canonical",
    ): Draft {
        val d = draft(f, novelId, chapterId, content, DraftStatus.CONFIRMED, variantId, prefix)
        f.app.commits.commitAuthorSave(d.draftId, content)
        return f.app.draftRepository.getById(d.draftId)!!
    }

    private fun run(f: Fixture, novelId: NovelId, chapterId: ChapterId, variantId: VariantId? = null): NovelAgentResult {
        val projectId = f.app.projects.projectOf(novelId).projectId
        return f.app.novelAgent.run(
            NovelAgentRequest(
                projectId = projectId,
                userIntent = "继续写这一章，保持前文一致",
                activeChapterId = chapterId,
                activeVariantId = variantId,
            ),
        )
    }

    private fun prompt(f: Fixture, marker: String): String =
        f.records.first { req ->
            req.messages.first { it.role == ChatRole.SYSTEM }.content.contains(marker)
        }.messages.joinToString("\n") { it.content }

    /* ====================================================================
     * V-1 第 1 章：不构造 reference；字段 false/null；prompt 无摘录（回归旧行为）
     * ==================================================================== */
    @Test
    fun `v1 first chapter keeps legacy behavior without continuation`() {
        val f = fixture()
        try {
            val novelId = f.app.novels.createOriginal(title = "书-V1")
            val ch1 = chapter(f, novelId, "第一章")
            f.app.projects.selectChapter(novelId, ch1)
            f.records.clear()

            val result = run(f, novelId, ch1)

            assertEquals(NovelAgentOutcome.WAITING_HUMAN, result.outcome)
            assertFalse(result.continuationIncluded)
            assertNull(result.continuationSourceChapterId)
            assertNull(result.continuationAbsentReason, "第 1 章为正常无前章，不记降级原因")
            listOf("StoryPlannerAgent", "StoryWriterAgent").forEach { marker ->
                val p = prompt(f, marker)
                assertFalse("【前章 Canonical 摘录】" in p, "$marker 第 1 章不得出现前章摘录")
                assertFalse("【续篇来源】" in p, "$marker 第 1 章不得出现续篇来源")
            }
        } finally {
            f.close()
        }
    }

    /* ====================================================================
     * V-2 第 2 章 + 前章 CONFIRMED：两处 prompt 都含摘录与 source 身份；限额 1200/4000
     * ==================================================================== */
    @Test
    fun `v2 second chapter injects confirmed previous canonical into both prompts`() {
        val f = fixture()
        try {
            val novelId = f.app.novels.createOriginal(title = "书-V2")
            val ch1 = chapter(f, novelId, "第一章")
            val ch2 = chapter(f, novelId, "第二章")
            // 5000 code point 正文 + 首尾标记：尾部摘录必须保留尾标记、丢弃头标记
            val head = "HEAD_MARK_"
            val tail = "_TAIL_MARK"
            val filler = "甲".repeat(5000)
            val content = head + filler + tail
            val canonical = confirmedCanonical(f, novelId, ch1, content)
            f.app.projects.selectChapter(novelId, ch2)
            f.records.clear()

            val result = run(f, novelId, ch2)

            assertEquals(NovelAgentOutcome.WAITING_HUMAN, result.outcome)
            assertTrue(result.continuationIncluded)
            assertEquals(ch1, result.continuationSourceChapterId)
            assertNull(result.continuationAbsentReason)

            val planner = prompt(f, "StoryPlannerAgent")
            val writer = prompt(f, "StoryWriterAgent")
            assertTrue("【续篇来源】" in planner, "Planner prompt 必须含续篇来源")
            listOf(planner, writer).forEach { p ->
                assertTrue("【前章 Canonical 摘录】" in p)
                assertTrue(ch1.value in p, "prompt 必须含 sourceChapterId")
                assertTrue(canonical.draftId.value in p, "prompt 必须含 sourceDraftId")
                assertTrue(tail in p, "摘录必须取正文尾部")
                assertFalse(head in p, "摘录不得包含超过限额的头部内容")
            }
            assertTrue("keptCodePoints=1200" in planner, "Planner 摘录上限必须为 1200 code point")
            assertTrue("keptCodePoints=4000" in writer, "Writer 摘录上限必须为 4000 code point")
        } finally {
            f.close()
        }
    }

    /* ====================================================================
     * V-3 第 2 章 + 前章无 Canonical：降级仍产出 + NO_CANONICAL（不静默）
     * ==================================================================== */
    @Test
    fun `v3 second chapter degrades with no canonical and records reason`() {
        val f = fixture()
        try {
            val novelId = f.app.novels.createOriginal(title = "书-V3")
            val ch1 = chapter(f, novelId, "第一章")
            val ch2 = chapter(f, novelId, "第二章")
            f.app.projects.selectChapter(novelId, ch2)
            f.records.clear()

            val result = run(f, novelId, ch2)

            assertEquals(
                NovelAgentOutcome.WAITING_HUMAN,
                result.outcome,
                "前章无 Canonical 必须降级继续，而不是硬拒",
            )
            assertFalse(result.continuationIncluded)
            assertNull(result.continuationSourceChapterId)
            assertEquals(NovelAgentContinuationReasons.NO_CANONICAL, result.continuationAbsentReason)
            listOf("StoryPlannerAgent", "StoryWriterAgent").forEach { marker ->
                assertFalse("【前章 Canonical 摘录】" in prompt(f, marker))
            }
            // 非静默：原因必须可从结果读取
            assertEquals("NO_CANONICAL", result.continuationAbsentReason)
        } finally {
            f.close()
        }
    }

    /* ====================================================================
     * V-4 第 2 章 + 前章 carrier 非 CONFIRMED（FINAL + CommitHistory）：
     *     降级继续 + CARRIER_NOT_CONFIRMED，不得硬拒
     * ==================================================================== */
    @Test
    fun `v4 second chapter degrades when previous carrier is not confirmed`() {
        val f = fixture()
        try {
            val novelId = f.app.novels.createOriginal(title = "书-V4")
            val ch1 = chapter(f, novelId, "第一章")
            val ch2 = chapter(f, novelId, "第二章")
            val finalDraft = draft(f, novelId, ch1, "前章最终稿（尚未确认）", DraftStatus.FINAL, prefix = "final")
            // 有 CommitHistory，但 carrier status 仍为 FINAL（O-23 情形）
            f.app.commits.commitAuthorSave(finalDraft.draftId, finalDraft.content)
            assertEquals(
                DraftStatus.FINAL,
                f.app.canonicalRead.committedDraft(ch1)?.status,
                "夹具必须保持 carrier 非 CONFIRMED",
            )
            f.app.projects.selectChapter(novelId, ch2)
            f.records.clear()

            val result = run(f, novelId, ch2)

            assertEquals(
                NovelAgentOutcome.WAITING_HUMAN,
                result.outcome,
                "carrier 非 CONFIRMED 必须降级继续，不得硬拒",
            )
            assertFalse(result.continuationIncluded)
            assertNull(result.continuationSourceChapterId)
            assertEquals(NovelAgentContinuationReasons.CARRIER_NOT_CONFIRMED, result.continuationAbsentReason)
            listOf("StoryPlannerAgent", "StoryWriterAgent").forEach { marker ->
                assertFalse("【前章 Canonical 摘录】" in prompt(f, marker))
            }
        } finally {
            f.close()
        }
    }

    /* ====================================================================
     * V-5 Variant：原作用域与变体各自取自己的前章；跨 variant 不串
     * ==================================================================== */
    @Test
    fun `v5 original and variant each use their own previous chapter`() {
        val f = fixture()
        try {
            val novelId = f.app.novels.createOriginal(title = "书-V5")
            val originalCh1 = chapter(f, novelId, "原-第一章")
            val originalCh2 = chapter(f, novelId, "原-第二章")
            confirmedCanonical(f, novelId, originalCh1, "ORIGINAL_PREV_MARK", prefix = "o1")

            val variantId = VariantId("v-o42")
            f.app.novels.createVariant(
                context = VariantContext(baseNovelId = BaseNovelId(novelId.value), variantId = variantId),
                name = "改线",
                variantId = variantId,
            )
            val variantCh1 = chapter(f, novelId, "变-第一章", variantId)
            val variantCh2 = chapter(f, novelId, "变-第二章", variantId)
            confirmedCanonical(f, novelId, variantCh1, "VARIANT_PREV_MARK", variantId, prefix = "v1")

            // 原作用域
            f.app.projects.selectVariant(novelId, null)
            f.app.projects.selectChapter(novelId, originalCh2)
            f.records.clear()
            val originalResult = run(f, novelId, originalCh2)
            assertEquals(originalCh1, originalResult.continuationSourceChapterId)
            val originalPrompts = listOf(prompt(f, "StoryPlannerAgent"), prompt(f, "StoryWriterAgent"))
            originalPrompts.forEach { p ->
                assertTrue("ORIGINAL_PREV_MARK" in p)
                assertFalse("VARIANT_PREV_MARK" in p, "原作用域不得混入 Variant 前章")
            }

            // Variant 作用域
            f.app.projects.selectVariant(novelId, variantId)
            f.app.projects.selectChapter(novelId, variantCh2)
            f.records.clear()
            val variantResult = run(f, novelId, variantCh2, variantId)
            assertEquals(variantCh1, variantResult.continuationSourceChapterId)
            val variantPrompts = listOf(prompt(f, "StoryPlannerAgent"), prompt(f, "StoryWriterAgent"))
            variantPrompts.forEach { p ->
                assertTrue("VARIANT_PREV_MARK" in p)
                assertFalse("ORIGINAL_PREV_MARK" in p, "Variant 不得混入 Original 前章")
            }
        } finally {
            f.close()
        }
    }
}
