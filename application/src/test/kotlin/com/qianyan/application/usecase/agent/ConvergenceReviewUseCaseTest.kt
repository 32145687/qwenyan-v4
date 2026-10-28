package com.qianyan.application.usecase.agent

import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.model.CharacterId
import com.qianyan.model.character.Character
import com.qianyan.model.characterconsistency.CharacterConsistencyMissingInput
import com.qianyan.model.characterconsistency.CharacterConsistencyStatus
import com.qianyan.model.novelagent.NovelAgentOutcome
import com.qianyan.model.session.AgentSessionStatus
import com.qianyan.model.spec.IssueSeverity
import com.qianyan.provider.ChatMessage
import com.qianyan.provider.ChatRole
import com.qianyan.provider.FinishReason
import com.qianyan.provider.ProviderRequest
import com.qianyan.provider.ProviderResponse
import com.qianyan.provider.Usage
import com.qianyan.provider.impl.MockLLMGateway
import kotlinx.datetime.Instant
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * D-35 · 显式收敛评审轮：Commit 后一次只读 CharacterConsistency 第二轮。
 *
 * T-8-E · 复审结果契约（16→17）：四种 outcome 显式映射 + 扁平化 findings/counts/summary。
 * 继续覆盖只读、单轮、新会话、未提交拒绝、prompt 可读到第一轮 Event。
 */
class ConvergenceReviewUseCaseTest {

    private val fixed = Instant.parse("2026-01-01T00:00:00Z")
    private val writerContent = "新章：主角抵达北境，风雪未停。"
    private val factExcerpt = "主角抵达北境"

    private class Fixture(
        val agent: NovelAgentFixture,
        val records: MutableList<ProviderRequest>,
    ) {
        val app get() = agent.app
        fun close() = agent.close()
    }

    private fun fixture(ccBody: () -> String = { PASS_JSON }): Fixture {
        val records = mutableListOf<ProviderRequest>()
        return Fixture(novelAgentFixture(scriptedGateway(records, ccBody)), records)
    }

    private fun scriptedGateway(
        records: MutableList<ProviderRequest>,
        ccBody: () -> String,
    ): MockLLMGateway = MockLLMGateway { request ->
        records += request
        val system = request.messages.first { it.role == ChatRole.SYSTEM }.content
        val allText = request.messages.joinToString("\n") { it.content }
        val body = when {
            "StoryPlannerAgent" in system ->
                """{"chapterGoal":"推进北境","mainConflict":"风雪阻拦","expectedEvents":["主角抵达北境"]}"""

            "StoryWriterAgent" in system -> buildJsonObject { put("content", writerContent) }.toString()

            "WorldModelUpdateAgent" in system ->
                if (writerContent in allText) factsJson() else """{"facts":[]}"""

            "StoryCharacterConsistencyAgent" in system -> ccBody()

            else -> buildJsonObject { put("content", "占位") }.toString()
        }
        ProviderResponse(
            message = ChatMessage(ChatRole.ASSISTANT, buildJsonObject { put("answer", body) }.toString()),
            usage = Usage(10, 10, 20),
            finishReason = FinishReason.STOP,
        )
    }

    private fun factsJson(): String =
        """{"facts":[{"factId":"f1","kind":"EVENT","operation":"ADD","name":"$factExcerpt","summary":"$factExcerpt","excerpt":"$factExcerpt","reason":"来自提交稿"}]}"""

    private fun seed(f: Fixture): NovelWorld {
        val w = seedNovelWorld(f.agent)
        f.app.storyState.saveCharacter(
            Character(
                characterId = CharacterId("c1"),
                novelId = w.novelId,
                name = "林岚",
                createdAt = fixed,
                updatedAt = fixed,
            ),
        )
        return w
    }

    private fun commitFirstRun(f: Fixture, w: NovelWorld): First {
        val first = f.app.novelAgent.run(request(w, "继续写第一章"))
        assertEquals(NovelAgentOutcome.WAITING_HUMAN, first.outcome)
        val artifactId = assertNotNull(first.artifactId)
        f.app.workflowFacade.approve(w.chapterId)
        val refreshed = f.app.novelAgent.refreshCandidate(
            request(w, "继续写第一章", sessionId = assertNotNull(first.sessionId)),
            artifactId,
        )
        assertEquals(NovelAgentOutcome.COMPLETED, refreshed.outcome)
        val history = assertNotNull(f.app.commits.history(w.projectId).lastOrNull { it.artifactId == artifactId })
        return First(first, artifactId, history.commitId)
    }

    private data class First(
        val runResult: com.qianyan.model.novelagent.NovelAgentResult,
        val artifactId: com.qianyan.model.change.ChangeArtifactId,
        val commitId: com.qianyan.model.commit.CommitId,
    )

    @Test
    fun `review maps PASS to CONSISTENT with explicit contract fields`() {
        val f = fixture()
        try {
            val w = seed(f)
            val first = commitFirstRun(f, w)
            val review = f.app.convergenceReview.review(w.novelId, null, w.chapterId, first.artifactId)

            assertEquals(ConvergenceOutcome.CONSISTENT, review.outcome)
            assertTrue(review.findings.isEmpty(), "PASS 不得携带扁平化 findings")
            assertEquals(0, review.errorCount)
            assertEquals(0, review.warningCount)
            assertEquals("pass", review.summary)
            assertEquals(NovelAgentOutcome.COMPLETED, review.result.outcome)

            // V-5 契约完整性：身份字段必须显式可读，不再只藏在嵌套 result 里。
            assertEquals(w.novelId, review.novelId)
            assertEquals(w.chapterId, review.chapterId)
            assertEquals(first.commitId, review.sourceCommitId)
            assertEquals(first.artifactId, review.sourceArtifactId)
            assertEquals(2, review.round)
            assertEquals(review.result.sessionId, review.reviewSessionId)
            assertNotNull(review.reviewSessionId)

            val prompt = f.records.last { "StoryCharacterConsistencyAgent" in it.messages.first { m -> m.role == ChatRole.SYSTEM }.content }
                .messages.joinToString("\n") { it.content }
            assertTrue("name=$factExcerpt" in prompt, "第二轮 prompt 必须含第一轮 Commit 的 Event")
            assertTrue("what=$factExcerpt" in prompt, "第二轮 prompt 必须含 Event 的 what")
        } finally {
            f.close()
        }
    }

    @Test
    fun `review maps ISSUES to ISSUES_FOUND with flattened findings and counts`() {
        val f = fixture { ISSUES_JSON }
        try {
            val w = seed(f)
            val first = commitFirstRun(f, w)
            val review = f.app.convergenceReview.review(w.novelId, null, w.chapterId, first.artifactId)

            assertEquals(ConvergenceOutcome.ISSUES_FOUND, review.outcome)
            assertEquals(2, review.findings.size)
            assertEquals(1, review.errorCount)
            assertEquals(1, review.warningCount)
            assertEquals(review.findings.count { it.severity == IssueSeverity.ERROR }, review.errorCount)
            assertEquals(review.findings.count { it.severity == IssueSeverity.WARNING }, review.warningCount)
            assertEquals(review.result.characterConsistency?.findings, review.findings)
            assertEquals("2 issues", review.summary)
        } finally {
            f.close()
        }
    }

    @Test
    fun `review maps INSUFFICIENT_EVIDENCE and keeps missing inputs in nested result`() {
        val f = fixture { INSUFFICIENT_JSON }
        try {
            val w = seed(f)
            val first = commitFirstRun(f, w)
            val review = f.app.convergenceReview.review(w.novelId, null, w.chapterId, first.artifactId)

            assertEquals(ConvergenceOutcome.INSUFFICIENT_EVIDENCE, review.outcome)
            assertTrue(review.findings.isEmpty())
            assertEquals(0, review.errorCount)
            assertEquals(0, review.warningCount)
            assertEquals("plan missing", review.summary)

            val nested = assertNotNull(review.result.characterConsistency)
            assertEquals(CharacterConsistencyStatus.INSUFFICIENT_EVIDENCE, nested.status)
            assertTrue(CharacterConsistencyMissingInput.PLAN in nested.missingInputs, "missingInputs 仍必须在嵌套 result 中可查")
        } finally {
            f.close()
        }
    }

    @Test
    fun `review maps a failed second round to REVIEW_FAILED not CONSISTENT`() {
        val f = fixture { "not-a-json" }
        try {
            val w = seed(f)
            val first = commitFirstRun(f, w)
            val review = f.app.convergenceReview.review(w.novelId, null, w.chapterId, first.artifactId)

            assertEquals(ConvergenceOutcome.REVIEW_FAILED, review.outcome)
            assertNotEquals(ConvergenceOutcome.CONSISTENT, review.outcome, "复审没跑成不得映射为一致")
            assertEquals(NovelAgentOutcome.FAILED, review.result.outcome)
            assertTrue(review.findings.isEmpty())
            assertEquals(0, review.errorCount)
            assertEquals(0, review.warningCount)
            val failure = assertNotNull(review.result.failure)
            assertTrue(failure.code in review.summary, "失败摘要必须包含失败码")
        } finally {
            f.close()
        }
    }

    @Test
    fun `convergence outcome mapping never treats a missing review result as consistent`() {
        val completedWithoutReview = com.qianyan.model.novelagent.NovelAgentResult(
            outcome = NovelAgentOutcome.COMPLETED,
            projectId = com.qianyan.model.ProjectId("p1"),
            intent = com.qianyan.model.novelagent.NovelIntentAnalysis(
                intentType = com.qianyan.model.IntentType.ANALYZE,
                reason = "test",
            ),
        )
        assertEquals(ConvergenceOutcome.REVIEW_FAILED, convergenceOutcomeOf(completedWithoutReview))
    }

    @Test
    fun `review is read only for commit draft working draft and artifact stores`() {
        val f = fixture()
        try {
            val w = seed(f)
            val first = commitFirstRun(f, w)
            val historyBefore = f.app.commits.history(w.projectId).size
            val draftsBefore = f.app.draftRepository.listByChapter(w.chapterId).size
            val workingBefore = f.app.workingDrafts.list(w.projectId).size
            val artifactsBefore = f.app.changes.list(w.projectId).size
            val canonicalBefore = f.app.canonicalRead.committedDraft(w.chapterId)?.draftId
            assertEquals(1, historyBefore, "夹具：一次正式提交")
            assertEquals(2, draftsBefore, "夹具：seed Draft + Commit 新 Draft")
            assertEquals(1, workingBefore, "夹具：已 DISCARDED 的 WorkingDraft 仍可计数")
            assertEquals(1, artifactsBefore, "夹具：第一轮 Artifact")

            val review = f.app.convergenceReview.review(w.novelId, null, w.chapterId, first.artifactId)
            assertNotNull(review.result.characterConsistency)

            assertEquals(historyBefore, f.app.commits.history(w.projectId).size)
            assertEquals(draftsBefore, f.app.draftRepository.listByChapter(w.chapterId).size)
            assertEquals(workingBefore, f.app.workingDrafts.list(w.projectId).size)
            assertEquals(artifactsBefore, f.app.changes.list(w.projectId).size)
            assertEquals(canonicalBefore, f.app.canonicalRead.committedDraft(w.chapterId)?.draftId)
        } finally {
            f.close()
        }
    }

    @Test
    fun `review rejects an uncommitted artifact before any llm call`() {
        val f = fixture()
        try {
            val w = seed(f)
            val first = f.app.novelAgent.run(request(w, "继续写第一章"))
            val artifactId = assertNotNull(first.artifactId)
            val callsBefore = f.records.size

            val error = assertFailsWith<ApplicationException> {
                f.app.convergenceReview.review(w.novelId, null, w.chapterId, artifactId)
            }
            assertIs<ApplicationError.EntityNotFound>(error.error)
            assertEquals(callsBefore, f.records.size, "未提交拒绝不得触发 LLM")
        } finally {
            f.close()
        }
    }

    @Test
    fun `review uses a new session and does not rewrite the first session`() {
        val f = fixture()
        try {
            val w = seed(f)
            val first = commitFirstRun(f, w)
            val firstSessionId = assertNotNull(first.runResult.sessionId)
            val firstStatusBefore = f.app.agentSessions.sessionOf(firstSessionId).status
            assertEquals(AgentSessionStatus.COMPLETED, firstStatusBefore)

            val review = f.app.convergenceReview.review(w.novelId, null, w.chapterId, first.artifactId)
            val secondSessionId = assertNotNull(review.result.sessionId)

            assertNotEquals(firstSessionId, secondSessionId, "第二轮必须创建新会话")
            assertEquals(secondSessionId, review.reviewSessionId, "复审会话必须显式可追溯")
            assertEquals(firstStatusBefore, f.app.agentSessions.sessionOf(firstSessionId).status, "第一轮会话不得被改写")
        } finally {
            f.close()
        }
    }

    private companion object {
        val PASS_JSON: String =
            """{"status":"PASS","scope":"CURRENT_CHAPTER","findings":[],"checkedCharacters":[{"characterId":"c1","name":"林岚"}],"missingInputs":[],"summary":"pass"}"""

        val ISSUES_JSON: String =
            """{"status":"ISSUES","scope":"CURRENT_CHAPTER","findings":[""" +
                """{"type":"TRAIT","severity":"ERROR","characterId":"c1","characterName":"林岚","message":"性格冲突","evidence":[{"quote":"林岚","sourceKind":"CHARACTER_PROFILE","sourceId":"c1","field":"name"}],"confidence":"EXPLICIT"},""" +
                """{"type":"GOAL","severity":"WARNING","characterId":"c1","characterName":"林岚","message":"目标偏离","evidence":[{"quote":"林岚","sourceKind":"CHARACTER_PROFILE","sourceId":"c1","field":"name"}],"confidence":"INFERRED"}""" +
                """],"checkedCharacters":[{"characterId":"c1","name":"林岚"}],"missingInputs":[],"summary":"2 issues"}"""

        val INSUFFICIENT_JSON: String =
            """{"status":"INSUFFICIENT_EVIDENCE","scope":"CURRENT_CHAPTER","findings":[],"checkedCharacters":[],"missingInputs":["PLAN"],"summary":"plan missing"}"""
    }
}
