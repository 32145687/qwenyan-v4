package com.qianyan.application.usecase.agent

import com.qianyan.agent.agents.CoreSkills
import com.qianyan.agent.agents.SkillRegistry
import com.qianyan.model.novelagent.NovelAgentApprovalState
import com.qianyan.model.novelagent.NovelAgentErrorCodes
import com.qianyan.model.novelagent.NovelAgentOutcome
import com.qianyan.model.workflow.HumanGateStatus
import com.qianyan.provider.ChatMessage
import com.qianyan.provider.ChatRole
import com.qianyan.provider.FinishReason
import com.qianyan.provider.ProviderRequest
import com.qianyan.provider.ProviderResponse
import com.qianyan.provider.Usage
import com.qianyan.provider.impl.MockLLMGateway
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T-7-3 · D-36 决策点：同一 Task 的 analysis Result 驱动下一步。
 *
 * X（PASS）→ 结束，只读，不产生 WorkingDraft / Artifact；
 * Y（有 ERROR）→ 同一 session 内自动执行一次 rewrite-revision → WorkingDraft → Artifact → 正式 Gate。
 */
class DecisionPointAnalysisFollowUpTest {

    private val analysisIntent = "分析第一章"
    private val revisionContent = "第一章修订正文（来自 Revision）。"

    private data class Counts(
        val drafts: Int,
        val working: Int,
        val artifacts: Int,
        val history: Int,
    )

    private fun systemOf(request: ProviderRequest): String =
        request.messages.first { it.role == ChatRole.SYSTEM }.content

    private fun gateway(
        criticBody: String,
        records: MutableList<ProviderRequest>,
    ): MockLLMGateway = MockLLMGateway { request ->
        records += request
        val system = systemOf(request)
        val body = when {
            "StoryCriticAgent" in system -> criticBody
            "StoryRevisionAgent" in system -> buildJsonObject { put("content", revisionContent) }.toString()
            else -> buildJsonObject { put("content", "占位") }.toString()
        }
        ProviderResponse(
            message = ChatMessage(ChatRole.ASSISTANT, buildJsonObject { put("answer", body) }.toString()),
            usage = Usage(10, 10, 20),
            finishReason = FinishReason.STOP,
        )
    }

    private fun fixture(
        criticBody: String,
        records: MutableList<ProviderRequest> = mutableListOf(),
    ): NovelAgentFixture = novelAgentFixture(gateway(criticBody, records))

    private fun counts(f: NovelAgentFixture, w: NovelWorld): Counts = Counts(
        drafts = f.app.draftRepository.listByChapter(w.chapterId).size,
        working = f.app.workingDrafts.list(w.projectId).size,
        artifacts = f.app.changes.list(w.projectId).size,
        history = f.app.commits.history(w.projectId).size,
    )

    @Test
    fun `V1 PASS analysis ends the task without rewrite and stays read only`() {
        val records = mutableListOf<ProviderRequest>()
        val f = fixture(PASS_JSON, records)
        try {
            val w = seedNovelWorld(f)
            val before = counts(f, w)
            val canonicalBefore = f.app.canonicalRead.committedDraft(w.chapterId)?.draftId

            val result = f.app.novelAgent.run(request(w, analysisIntent))

            assertEquals(NovelAgentOutcome.COMPLETED, result.outcome)
            assertEquals(CoreSkills.ANALYSIS_CRITIQUE.skillId, result.skillId)
            val analysis = assertNotNull(result.analysis, "结果必须显式携带初次分析结论")
            assertTrue(analysis.passed)
            assertEquals(0, analysis.issueCount)
            assertEquals(0, analysis.errorCount)
            assertNull(result.followUpSkillId, "PASS 不得触发续步")
            assertNull(result.workingDraftId)
            assertNull(result.artifactId)
            assertEquals(NovelAgentApprovalState.NOT_REQUIRED, result.approvalState)

            val sessionId = assertNotNull(result.sessionId)
            val activities = f.app.activities.listBySession(sessionId)
            assertEquals(1, activities.count { it.kind == "EXECUTE" }, "PASS 只能有分析一次执行")
            assertTrue(activities.none { it.summary?.contains("续步") == true })
            assertTrue(records.none { "StoryRevisionAgent" in systemOf(it) }, "PASS 不得调用 Revision")

            // V-4：前后四类计数与 Canonical 均不变
            assertEquals(before, counts(f, w))
            assertEquals(canonicalBefore, f.app.canonicalRead.committedDraft(w.chapterId)?.draftId)
        } finally {
            f.close()
        }
    }

    @Test
    fun `V2 ERROR analysis automatically runs one rewrite revision and waits for gate`() {
        val records = mutableListOf<ProviderRequest>()
        val f = fixture(ERROR_JSON, records)
        try {
            val w = seedNovelWorld(f)

            val result = f.app.novelAgent.run(request(w, analysisIntent))

            assertEquals(NovelAgentOutcome.WAITING_HUMAN, result.outcome)
            assertEquals(NovelAgentApprovalState.BRIDGED, result.approvalState)
            assertEquals(CoreSkills.ANALYSIS_CRITIQUE.skillId, result.skillId, "初次 Skill 仍是 analysis")
            val analysis = assertNotNull(result.analysis)
            assertFalse(analysis.passed)
            assertEquals(1, analysis.issueCount)
            assertEquals(1, analysis.errorCount)
            assertEquals(CoreSkills.REWRITE_REVISION.skillId, result.followUpSkillId, "ERROR 必须触发 rewrite-revision")

            val workingDraftId = assertNotNull(result.workingDraftId)
            val artifactId = assertNotNull(result.artifactId)
            assertEquals(revisionContent, f.app.workingDrafts.get(workingDraftId).content)
            val artifact = f.app.changes.get(artifactId)
            assertEquals(workingDraftId, artifact.workingDraftId)
            assertTrue(artifact.change.diff.hasChanges)
            assertEquals(0, f.app.commits.history(w.projectId).size, "续步不得自动提交")

            val pendingGateId = assertNotNull(
                f.app.workflowRepository.getWorkflowByActiveChapter(w.chapterId)?.pendingGateId,
                "续步产物必须进入正式 Gate",
            )
            assertEquals(HumanGateStatus.PENDING, f.app.workflowRepository.getGate(pendingGateId)?.status)

            assertTrue("passed=false" in result.summary)
            assertTrue("问题 1 项" in result.summary)
            assertTrue("ERROR 1" in result.summary)
            assertTrue("已自动续步 skill.rewrite-revision" in result.summary)

            val sessionId = assertNotNull(result.sessionId)
            val activities = f.app.activities.listBySession(sessionId)
            assertEquals(2, activities.count { it.kind == "EXECUTE" }, "分析 + 续步各一次 EXECUTE")
            assertTrue(activities.all { it.sessionId == sessionId }, "续步必须发生在同一 session")
            assertEquals(2, records.count { "StoryCriticAgent" in systemOf(it) }, "分析 + 续步内部 critique")
            assertEquals(1, records.count { "StoryRevisionAgent" in systemOf(it) }, "只允许一次 Revision")
        } finally {
            f.close()
        }
    }

    @Test
    fun `V3 follow up is bounded to one extra execute step`() {
        val records = mutableListOf<ProviderRequest>()
        val f = fixture(ERROR_JSON, records)
        try {
            val w = seedNovelWorld(f)
            val result = f.app.novelAgent.run(request(w, analysisIntent))
            assertEquals(NovelAgentOutcome.WAITING_HUMAN, result.outcome)

            val sessionId = assertNotNull(result.sessionId)
            val activities = f.app.activities.listBySession(sessionId)
            val execute = activities.filter { it.kind == "EXECUTE" }
            assertEquals(2, execute.size, "即使续步内部 critique 仍为 ERROR，也只能有 2 个 EXECUTE")
            assertTrue(execute[1].summary?.contains("续步") == true, "第二个 EXECUTE 必须是续步")
            assertTrue(execute[1].summary?.contains(CoreSkills.REWRITE_REVISION.skillId.value) == true)
            assertTrue(activities.size <= 7, "Activity 数量必须有上限；实际=${activities.map { it.kind }}")
            assertEquals(2, records.count { "StoryCriticAgent" in systemOf(it) }, "不得出现第三轮评审")
            assertEquals(1, records.count { "StoryRevisionAgent" in systemOf(it) }, "不得出现第二次 Revision")
        } finally {
            f.close()
        }
    }

    @Test
    fun `V5 follow up still goes through the skill execution guard`() {
        val brokenRegistry = SkillRegistry.of(
            CoreSkills.all().map { skill ->
                if (skill.skillId == CoreSkills.REWRITE_REVISION.skillId) {
                    skill.copy(bindings = emptyList())
                } else {
                    skill
                }
            },
        )
        val f = fixture(ERROR_JSON)
        try {
            val w = seedNovelWorld(f)
            val agent = novelAgentWithRegistry(f, brokenRegistry)

            val result = agent.run(request(w, analysisIntent))

            assertEquals(NovelAgentOutcome.FAILED, result.outcome)
            assertEquals(NovelAgentErrorCodes.SKILL_NOT_EXECUTABLE, result.failure?.code)
            assertTrue(result.failure?.detail?.contains("bindings") == true, "必须由 SkillExecutionGuard 拒绝")
            assertEquals(CoreSkills.ANALYSIS_CRITIQUE.skillId, result.skillId)
            assertEquals(CoreSkills.REWRITE_REVISION.skillId, result.followUpSkillId)
            assertNull(result.workingDraftId)
            assertNull(result.artifactId)
            assertEquals(0, f.app.workingDrafts.list(w.projectId).size)
            assertEquals(0, f.app.changes.list(w.projectId).size)
            assertEquals(1, f.app.draftRepository.listByChapter(w.chapterId).size, "失败续步不得写 Draft/Canonical")
        } finally {
            f.close()
        }
    }

    private companion object {
        const val PASS_JSON: String = """{"passed":true,"issues":[]}"""

        const val ERROR_JSON: String =
            """{"passed":false,"issues":[{"field":"正文","severity":"ERROR","message":"需要修改"}]}"""
    }
}
