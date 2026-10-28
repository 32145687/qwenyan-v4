package com.qianyan.application.usecase.agent

import com.qianyan.model.BaseNovelId
import com.qianyan.model.context.ContextSourceKind
import com.qianyan.model.foundation.NarrativeProfile
import com.qianyan.model.foundation.StoryDirection
import com.qianyan.model.foundation.StoryFoundation
import com.qianyan.model.foundation.WritingPolicy
import com.qianyan.model.GenreId
import com.qianyan.model.VocabularyEntryId
import com.qianyan.model.vocabulary.VocabularyEntry
import com.qianyan.model.vocabulary.VocabularyEntryStatus
import com.qianyan.model.vocabulary.VocabularyEntryType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * AC-03 · 结构化 Retrieval 的生产消费证据。
 *
 * 正式 Retrieval = ContextEngine 的 ContextSources（当前五类索引对象）；
 * 本测试从真实 Agent 任务出发，断言五类都进入生产 `ContextPack.items`。
 * `ProjectIndex` 是可选的结构化索引能力，不是本阶段 Retrieval 的必需入口。
 */
class AgentStructuredRetrievalTest {

    @Test
    fun `agent task retrieves all five current index types into the production pack`() {
        val f = novelAgentFixture()
        val w = seedNovelWorld(f)

        f.app.storyFoundationRepository.upsertStoryFoundation(
            StoryFoundation(
                novelId = w.novelId,
                baseNovelId = BaseNovelId(w.novelId.value),
                genre = listOf(GenreId("retrieval-genre")),
                direction = StoryDirection(theme = "检索主题", conflict = "检索冲突"),
                audience = NarrativeProfile(pov = "第三人称"),
                policy = WritingPolicy(rules = listOf("检索规则")),
                createdAt = FIXED_INSTANT,
                updatedAt = FIXED_INSTANT,
            ),
        )
        val vocabularyId = f.app.vocabularies.getOrCreateNovelVocabulary(w.novelId)
        f.app.vocabularyRepository.saveEntry(
            VocabularyEntry(
                entryId = VocabularyEntryId("ve-retrieval"),
                vocabularyId = vocabularyId,
                novelId = w.novelId,
                canonical = "检索词条",
                type = VocabularyEntryType.PROPER_NOUN,
                status = VocabularyEntryStatus.APPROVED,
            ),
        )

        val result = f.app.novelAgent.run(request(w, "继续写第一章"))
        val sessionId = assertNotNull(result.sessionId)
        val pack = assertNotNull(f.app.contextPackStore.find(sessionId, w.projectId)).pack
        val sources = pack.items.map { it.source }.toSet()

        assertEquals(
            setOf(
                ContextSourceKind.NOVEL,
                ContextSourceKind.CHAPTER,
                ContextSourceKind.DRAFT,
                ContextSourceKind.STORY_FOUNDATION,
                ContextSourceKind.VOCABULARY,
            ),
            sources intersect setOf(
                ContextSourceKind.NOVEL,
                ContextSourceKind.CHAPTER,
                ContextSourceKind.DRAFT,
                ContextSourceKind.STORY_FOUNDATION,
                ContextSourceKind.VOCABULARY,
            ),
            "五类当前索引对象都必须经生产 Retrieval 进入 ContextPack",
        )
        assertTrue(pack.items.isNotEmpty())
        f.close()
    }
}
