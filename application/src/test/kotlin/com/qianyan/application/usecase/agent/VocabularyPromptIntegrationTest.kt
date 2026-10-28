package com.qianyan.application.usecase.agent

import com.qianyan.application.di.ApplicationContainer
import com.qianyan.model.DraftId
import com.qianyan.model.NovelId
import com.qianyan.model.VocabularyEntryId
import com.qianyan.model.context.ContextBudget
import com.qianyan.model.context.ContextSourceKind
import com.qianyan.model.context.VocabularyPayload
import com.qianyan.model.vocabulary.VocabularyEntry
import com.qianyan.model.vocabulary.VocabularyEntryStatus
import com.qianyan.model.vocabulary.VocabularyEntryType
import com.qianyan.model.vocabulary.VocabularyScopeLevel
import com.qianyan.model.writing.Draft
import com.qianyan.model.writing.DraftFormat
import com.qianyan.model.writing.DraftStatus
import com.qianyan.provider.ChatMessage
import com.qianyan.provider.ChatRole
import com.qianyan.provider.FinishReason
import com.qianyan.provider.ProviderResponse
import com.qianyan.provider.Usage
import com.qianyan.provider.impl.MockLLMGateway
import kotlinx.datetime.Instant
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * AC-30B · 真实 Vocabulary 经 Context 构建进入 Planner / Writer 模型请求。
 *
 * 不是 Selector 单测：捕获实际 LLMGateway 请求，按 Planner / Writer 分别断言；
 * 预算紧张时断言 omitted 词条不进入任一请求。
 */
class VocabularyPromptIntegrationTest {

    private data class PromptRecord(val system: String, val text: String)
    private data class SeededVocab(val id: VocabularyEntryId, val canonical: String)

    private fun recordingGateway(records: MutableList<PromptRecord>): MockLLMGateway = MockLLMGateway { req ->
        val system = req.messages.first { it.role == ChatRole.SYSTEM }.content
        records += PromptRecord(system, req.messages.joinToString("\n") { it.content })
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

    private fun seedVocabulary(app: ApplicationContainer, novelId: NovelId, count: Int): List<SeededVocab> {
        val vocabularyId = app.vocabularies.getOrCreateNovelVocabulary(novelId)
        return (1..count).map { index ->
            val suffix = index.toString().padStart(2, '0')
            val entryId = VocabularyEntryId("ve-ac30b-$suffix")
            val canonical = "AC30B-词条-$suffix-唯一"
            app.vocabularyRepository.saveEntry(
                VocabularyEntry(
                    entryId = entryId,
                    vocabularyId = vocabularyId,
                    novelId = novelId,
                    canonical = canonical,
                    aliases = listOf("AC30B-别名-$suffix"),
                    replacement = "AC30B-替换-$suffix",
                    type = VocabularyEntryType.PROPER_NOUN,
                    status = VocabularyEntryStatus.APPROVED,
                ),
            )
            SeededVocab(entryId, canonical)
        }
    }

    private fun selectedVocab(pack: com.qianyan.model.context.ContextPack): List<String> =
        pack.items
            .filter { it.source == ContextSourceKind.VOCABULARY }
            .map { (it.payload as VocabularyPayload).canonical }

    private fun promptFor(records: List<PromptRecord>, agent: String): String =
        records.first { agent in it.system }.text

    @Test
    fun `sufficient budget sends the full vocabulary dataset to both planner and writer`() {
        val records = mutableListOf<PromptRecord>()
        val f = novelAgentFixture(recordingGateway(records))
        val w = seedNovelWorld(f)
        val seeded = seedVocabulary(f.app, w.novelId, count = 5)

        val result = f.app.novelAgent.run(request(w, "继续写第一章"))
        val sessionId = assertNotNull(result.sessionId)
        val pack = assertNotNull(f.app.contextPackStore.find(sessionId, w.projectId)).pack

        assertEquals(seeded.map { it.canonical }.toSet(), selectedVocab(pack).toSet())
        val planner = promptFor(records, "StoryPlannerAgent")
        val writer = promptFor(records, "StoryWriterAgent")
        seeded.forEach {
            assertTrue(it.canonical in planner, "Planner 必须看到词条 ${it.canonical}")
            assertTrue(it.canonical in writer, "Writer 必须看到词条 ${it.canonical}")
        }
        f.close()
    }

    @Test
    fun `aliases are rendered in planner and writer prompts and match source content`() {
        val records = mutableListOf<PromptRecord>()
        val f = novelAgentFixture(recordingGateway(records))
        val w = seedNovelWorld(f)
        val vocabularyId = f.app.vocabularies.getOrCreateNovelVocabulary(w.novelId)

        fun save(entryId: String, canonical: String, aliases: List<String>, replacement: String?) {
            f.app.vocabularyRepository.saveEntry(
                VocabularyEntry(
                    entryId = VocabularyEntryId(entryId),
                    vocabularyId = vocabularyId,
                    novelId = w.novelId,
                    scopeLevel = VocabularyScopeLevel.NOVEL,
                    canonical = canonical,
                    aliases = aliases,
                    replacement = replacement,
                    type = VocabularyEntryType.PROPER_NOUN,
                    status = VocabularyEntryStatus.APPROVED,
                ),
            )
        }

        save("ve-v3a-main", "V3A主词", listOf("阿甲", "阿乙"), "替词")
        save("ve-v3a-no-alias", "V3A无别名", emptyList(), "旧替词")
        save("ve-v3a-empty-alias", "V3A空别名", listOf("", "有效别名"), null)

        val result = f.app.novelAgent.run(request(w, "继续写第一章"))
        val sessionId = assertNotNull(result.sessionId)
        val pack = assertNotNull(f.app.contextPackStore.find(sessionId, w.projectId)).pack
        val contentByCanonical = pack.items
            .filter { it.source == ContextSourceKind.VOCABULARY }
            .associate { (it.payload as VocabularyPayload).canonical to it.content }

        val mainContent = contentByCanonical.getValue("V3A主词")
        assertTrue("aliases=阿甲|阿乙" in mainContent, "Source content 必须包含同一条 Entry 的 aliases")
        assertTrue("replacement=替词" in mainContent)

        val planner = promptFor(records, "StoryPlannerAgent")
        val writer = promptFor(records, "StoryWriterAgent")
        val mainLine = "- V3A主词（别名：阿甲、阿乙）-> 替词"
        assertTrue(mainLine in planner, "Planner 必须按约定格式渲染 aliases: $mainLine")
        assertTrue(mainLine in writer, "Writer 必须按约定格式渲染 aliases: $mainLine")
        assertTrue("- V3A无别名 -> 旧替词" in planner, "无别名时必须保持旧格式")
        assertTrue("- V3A无别名 -> 旧替词" in writer, "无别名时必须保持旧格式")
        assertTrue("- V3A空别名（别名：有效别名）" in planner, "空字符串别名必须忽略")
        assertTrue("- V3A空别名（别名：有效别名）" in writer, "空字符串别名必须忽略")
        f.close()
    }

    @Test
    fun `tight budget omits vocabulary deterministically and omitted entries never enter prompts`() {
        val records = mutableListOf<PromptRecord>()
        val f = novelAgentFixture(recordingGateway(records))
        val w = seedNovelWorld(f)
        val fixed = Instant.parse("2026-01-01T00:00:00Z")
        f.app.draftRepository.save(
            Draft(
                draftId = DraftId("draft-ac30b-long"),
                novelId = w.novelId,
                chapterId = w.chapterId,
                content = "长".repeat(600),
                format = DraftFormat.CONTROLLED_MARKDOWN,
                status = DraftStatus.WRITTEN,
                createdAt = fixed,
                updatedAt = fixed,
            ),
        )
        val seeded = seedVocabulary(f.app, w.novelId, count = 30)
        val byItemId = seeded.associate { "vocabulary:${it.id.value}" to it.canonical }

        val result = f.app.novelAgent.run(
            request(w, "继续写第一章", budget = ContextBudget(maxTokens = 80)),
        )
        val sessionId = assertNotNull(result.sessionId)
        val pack = assertNotNull(f.app.contextPackStore.find(sessionId, w.projectId)).pack
        val selected = selectedVocab(pack)
        val omitted = pack.budgetGuard.omittedItemIds.mapNotNull { byItemId[it] }

        assertTrue(selected.size in 1 until seeded.size, "预算紧张时必须部分保留 Vocabulary: selected=${selected.size}")
        assertTrue(omitted.isNotEmpty(), "必须有 Vocabulary 被预算淘汰")
        assertEquals(seeded.map { it.canonical }.toSet(), (selected + omitted).toSet(), "selected + omitted 必须可对账")
        assertTrue(pack.budgetGuard.omittedItemIds.any { it.startsWith("draft:") }, "长 Draft 应被预算淘汰")

        val planner = promptFor(records, "StoryPlannerAgent")
        val writer = promptFor(records, "StoryWriterAgent")
        selected.forEach {
            assertTrue(it in planner, "Planner 必须看到入选词条 $it")
            assertTrue(it in writer, "Writer 必须看到入选词条 $it")
        }
        omitted.forEach {
            assertFalse(it in planner, "Planner 不得看到被淘汰词条 $it")
            assertFalse(it in writer, "Writer 不得看到被淘汰词条 $it")
        }

        // 重复执行：同一数据/预算下选中的词条集合必须相同。
        val firstCount = records.size
        val second = f.app.novelAgent.run(
            request(w, "继续写第一章", budget = ContextBudget(maxTokens = 80)),
        )
        val secondSession = assertNotNull(second.sessionId)
        val secondPack = assertNotNull(f.app.contextPackStore.find(secondSession, w.projectId)).pack
        assertEquals(selected.toSet(), selectedVocab(secondPack).toSet(), "重复执行的 Vocabulary 子集必须稳定")
        val secondPlanner = promptFor(records.drop(firstCount), "StoryPlannerAgent")
        selected.forEach { assertTrue(it in secondPlanner, "重复执行 Planner 必须看到同一词条 $it") }

        f.close()
    }

    @Test
    fun `missing vocabulary yields no vocabulary prompt section`() {
        val records = mutableListOf<PromptRecord>()
        val f = novelAgentFixture(recordingGateway(records))
        val w = seedNovelWorld(f)

        val result = f.app.novelAgent.run(request(w, "继续写第一章"))
        val sessionId = assertNotNull(result.sessionId)
        val pack = assertNotNull(f.app.contextPackStore.find(sessionId, w.projectId)).pack

        assertTrue(pack.items.none { it.source == ContextSourceKind.VOCABULARY })
        assertFalse(promptFor(records, "StoryPlannerAgent").contains("【词库】"))
        assertFalse(promptFor(records, "StoryWriterAgent").contains("【词库】"))
        f.close()
    }
}
