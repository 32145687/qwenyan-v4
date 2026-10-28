package com.qianyan.application.usecase.agent

import com.qianyan.agent.agents.CoreSkills
import com.qianyan.model.ChapterId
import com.qianyan.model.CharacterId
import com.qianyan.model.DraftId
import com.qianyan.model.character.Character
import com.qianyan.model.characterconsistency.CharacterConsistencyMissingInput
import com.qianyan.model.characterconsistency.CharacterConsistencyStatus
import com.qianyan.model.novelagent.NovelAgentOutcome
import com.qianyan.model.writing.Draft
import com.qianyan.model.writing.DraftStatus
import com.qianyan.provider.ChatMessage
import com.qianyan.provider.ChatRole
import com.qianyan.provider.FinishReason
import com.qianyan.provider.LLMGateway
import com.qianyan.provider.ProviderResponse
import com.qianyan.provider.Usage
import com.qianyan.provider.impl.MockLLMGateway
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** T-2B-3 · NovelAgent 全链路 E2E（V-4 / V-5 / V-6）。 */
class CharacterConsistencyNovelAgentE2ETest {

    private class ScriptedGateway {
        var answer: String = ISSUES_JSON
        val prompts = mutableListOf<String>()

        val gateway: LLMGateway = MockLLMGateway { request ->
            val system = request.messages.first { it.role == ChatRole.SYSTEM }.content
            val user = request.messages.first { it.role == ChatRole.USER }.content
            val body = when {
                "StoryCharacterConsistencyAgent" in system -> {
                    prompts += user
                    answer
                }
                "WorldModelUpdateAgent" in system -> """{"facts":[]}"""
                "StoryCriticAgent" in system -> """{"passed":true}"""
                else -> """{"content":"占位"}"""
            }
            ProviderResponse(
                message = ChatMessage(
                    ChatRole.ASSISTANT,
                    buildJsonObject { put("answer", body) }.toString(),
                ),
                usage = Usage(10, 10, 20),
                finishReason = FinishReason.STOP,
            )
        }
    }

    private class Harness(
        val f: NovelAgentFixture,
        val w: NovelWorld,
        val currentChapterId: ChapterId,
        val currentDraftId: DraftId,
    )

    private fun setup(
        script: ScriptedGateway,
        withCanonical: Boolean = true,
        extraChapter: Boolean = false,
    ): Harness {
        val f = novelAgentFixture(script.gateway)
        val w = seedNovelWorld(f, canonicalContent = "前章正典A")
        if (withCanonical) {
            f.app.commits.commitAuthorSave(w.canonicalDraftId, "前章正典A")
        }
        val currentChapterId = if (extraChapter) {
            f.app.chapters.createNextChapter("第三章", w.novelId).chapterId
        } else {
            w.secondChapterId
        }
        f.app.projects.selectChapter(w.novelId, currentChapterId)
        val currentDraftId = DraftId("current-draft")
        f.app.draftRepository.save(
            Draft(
                draftId = currentDraftId,
                novelId = w.novelId,
                chapterId = currentChapterId,
                content = CURRENT_CONTENT,
                status = DraftStatus.WRITTEN,
                createdAt = FIXED_INSTANT,
                updatedAt = FIXED_INSTANT,
            ),
        )
        f.app.storyState.saveCharacter(
            Character(
                characterId = CharacterId("c1"),
                novelId = w.novelId,
                name = "林岚",
                personality = listOf("胆小"),
                goals = listOf("活下去"),
                fears = listOf("黑暗"),
                description = "青云宗弟子",
                createdAt = FIXED_INSTANT,
                updatedAt = FIXED_INSTANT,
            ),
        )
        return Harness(f, w, currentChapterId, currentDraftId)
    }

    private fun run(h: Harness, preferred: Boolean = true): com.qianyan.model.novelagent.NovelAgentResult =
        h.f.app.novelAgent.run(
            request(
                h.w,
                "检查人物一致性",
                activeChapterId = h.currentChapterId,
                preferredSkillId = if (preferred) CoreSkills.CHARACTER_CONSISTENCY.skillId else null,
            ),
        )

    private data class Counts(
        val priorDrafts: Int,
        val currentDrafts: Int,
        val history: Int,
        val workingDrafts: Int,
        val memories: Int,
        val characters: Int,
    )

    private fun counts(h: Harness): Counts = Counts(
        priorDrafts = h.f.app.draftRepository.listByChapter(h.w.chapterId).size,
        currentDrafts = h.f.app.draftRepository.listByChapter(h.currentChapterId).size,
        history = h.f.app.commits.history(h.w.projectId).size,
        workingDrafts = h.f.app.workingDrafts.list(h.w.projectId).size,
        memories = h.f.app.memoryRepository.findEntriesByNovel(h.w.novelId).size,
        characters = h.f.app.storyState.listCharacters(h.w.novelId, null).size,
    )

    @Test
    fun `V4 readonly E2E and V5 validator summary`() {
        val script = ScriptedGateway().apply { answer = ISSUES_JSON }
        val h = setup(script)
        val before = counts(h)

        val result = run(h)

        assertEquals(NovelAgentOutcome.COMPLETED, result.outcome)
        assertEquals(CoreSkills.CHARACTER_CONSISTENCY.skillId, result.skillId)
        val consistency = assertNotNull(result.characterConsistency)
        assertEquals(CharacterConsistencyStatus.ISSUES, consistency.status, "status 必须与 mock LLM 输出一致")
        assertEquals("1 issue", consistency.summary, "summary 必须等于 Validator 通过的 summary")
        assertTrue(consistency.summary.isNotBlank())
        assertTrue(result.summary.contains("findings=1"))
        assertEquals(before, counts(h), "CharacterConsistency 全链路不得写 Draft / Commit / WorkingDraft / Knowledge")
        h.f.close()
    }

    @Test
    fun `V6 partial gap is exposed in Basis and missing semantics stay`() {
        val script = ScriptedGateway().apply { answer = INSUFFICIENT_PRIOR }
        val h = setup(script, withCanonical = true, extraChapter = true)
        assertEquals(
            h.w.canonicalDraftId,
            h.f.app.canonicalRead.committedDraft(h.w.chapterId)?.draftId,
            "更早章必须有 Canonical",
        )

        val result = run(h)
        val consistency = assertNotNull(result.characterConsistency)

        assertEquals(CharacterConsistencyStatus.INSUFFICIENT_EVIDENCE, consistency.status)
        assertTrue(
            consistency.basis.priorCanonicalGapChapterIds.contains(h.w.secondChapterId),
            "紧邻缺章必须进入 Basis.priorCanonicalGapChapterIds",
        )
        assertTrue(consistency.missingInputs.contains(CharacterConsistencyMissingInput.PRIOR_CANONICAL))
        assertTrue(consistency.basis.priorCanonicalDraftIds.isEmpty())
        h.f.close()
    }

    @Test
    fun `V6 no prior canonical still uses missing prior semantic`() {
        val script = ScriptedGateway().apply { answer = INSUFFICIENT_PRIOR }
        val h = setup(script, withCanonical = false)

        val result = run(h)
        val consistency = assertNotNull(result.characterConsistency)

        assertEquals(CharacterConsistencyStatus.INSUFFICIENT_EVIDENCE, consistency.status)
        assertTrue(consistency.missingInputs.contains(CharacterConsistencyMissingInput.PRIOR_CANONICAL))
        assertTrue(
            consistency.basis.priorCanonicalGapChapterIds.contains(h.w.chapterId),
            "无 Canonical 的前章必须进入 gap",
        )
        h.f.close()
    }

    private companion object {
        const val CURRENT_CONTENT = "当前正文：她很胆小。"

        const val ISSUES_JSON =
            """{"status":"ISSUES","scope":"CURRENT_CHAPTER","findings":[{"type":"TRAIT","severity":"WARNING","characterId":"c1","characterName":"林岚","message":"性格冲突","evidence":[{"quote":"她很胆小","sourceKind":"CURRENT_DRAFT","sourceId":"current-draft","field":"content"}],"confidence":"EXPLICIT"}],"checkedCharacters":[{"characterId":"c1","name":"林岚"}],"missingInputs":[],"summary":"1 issue"}"""

        const val INSUFFICIENT_PRIOR =
            """{"status":"INSUFFICIENT_EVIDENCE","scope":"CURRENT_CHAPTER","findings":[],"checkedCharacters":[],"missingInputs":["PRIOR_CANONICAL"],"summary":"prior canonical missing"}"""
    }
}
