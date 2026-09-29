package com.qianyan.application.usecase.agent

import com.qianyan.agent.agents.CoreSkills
import com.qianyan.agent.agents.SkillRegistry
import com.qianyan.model.AgentSessionId
import com.qianyan.model.IntentType
import com.qianyan.model.ProjectId
import com.qianyan.model.VariantId
import com.qianyan.model.context.ContextBudget
import com.qianyan.model.context.ContextRequest
import com.qianyan.model.log.AgentLogStatus
import com.qianyan.model.novelagent.NovelAgentErrorCodes
import com.qianyan.model.novelagent.NovelAgentOutcome
import com.qianyan.model.novelagent.NovelAgentRequest
import com.qianyan.model.session.AgentSessionStatus
import com.qianyan.model.skill.SkillId
import com.qianyan.model.workingdraft.WorkingDraftStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * I11 · Intent / Skill / Context / Tool / Session（§8 / §9 / §10 / §11 / §12 / §17 / §19）。
 *
 * 意图与 Skill 选择必须**确定性、可解释、可重复**；Tool 只能经 I5 且**必须**在该 Skill 的 `allowedTools` 内；
 * 会话生命周期一律用既有 AgentSession 语义。
 */
class NovelAgentRoutingTest {

    /* ---------------- Intent（确定性规则，无 LLM） ---------------- */

    @Test
    fun `intent analysis covers all existing intent types deterministically`() {
        val f = novelAgentFixture()
        val agent = f.app.novelAgent

        assertEquals(IntentType.CONTINUE, agent.analyzeIntent("继续写第一章").intentType)
        assertEquals(IntentType.REWRITE, agent.analyzeIntent("重写这一段").intentType)
        assertEquals(IntentType.ANALYZE, agent.analyzeIntent("分析人物关系").intentType)
        assertEquals(IntentType.PLAN, agent.analyzeIntent("规划下一卷").intentType)
        assertEquals(IntentType.EXPAND, agent.analyzeIntent("扩写这段").intentType)

        // unknown / 空意图 → CUSTOM（可解释）
        val unknown = agent.analyzeIntent("帮我看看这个")
        assertEquals(IntentType.CUSTOM, unknown.intentType)
        assertNull(unknown.matchedKeyword)
        assertTrue(unknown.reason.contains("未识别"))

        val blank = agent.analyzeIntent("   ")
        assertEquals(IntentType.CUSTOM, blank.intentType)
        assertTrue(blank.reason.contains("为空"))

        // ambiguous（多关键词命中）→ 固定优先级，结果确定且可解释
        val ambiguous = agent.analyzeIntent("重写第三章并继续写第四章")
        assertEquals(IntentType.REWRITE, ambiguous.intentType, "更具体的动作优先于泛化的继续写")
        assertEquals("重写", ambiguous.matchedKeyword)
        assertTrue(ambiguous.reason.contains("确定性规则"))

        // 确定性：同输入 ⇒ 同结果
        assertEquals(agent.analyzeIntent("扩写并分析这段"), agent.analyzeIntent("扩写并分析这段"))
        assertEquals(IntentType.EXPAND, agent.analyzeIntent("扩写并分析这段").intentType)
        f.close()
    }

    /* ---------------- Skill 选择（SkillRegistry 候选集 + 确定性偏好序） ---------------- */

    @Test
    fun `skill selection follows skill registry matches`() {
        val f = novelAgentFixture()
        val w = seedNovelWorld(f)

        assertEquals(CoreSkills.WRITING.skillId, f.app.novelAgent.run(request(w, "继续写第一章")).skillId)
        assertEquals(CoreSkills.REWRITE_REVISION.skillId, f.app.novelAgent.run(request(w, "重写第一章")).skillId)
        assertEquals(CoreSkills.ANALYSIS_CRITIQUE.skillId, f.app.novelAgent.run(request(w, "分析第一章")).skillId)
        assertEquals(CoreSkills.STORY_PLANNING.skillId, f.app.novelAgent.run(request(w, "规划第一章")).skillId)

        // CUSTOM 多命中 → 确定性偏好（skill.writing 优先），两次运行结果一致
        val custom1 = f.app.novelAgent.run(request(w, "随便写点什么"))
        val custom2 = f.app.novelAgent.run(request(w, "随便写点什么"))
        assertEquals(CoreSkills.WRITING.skillId, custom1.skillId)
        assertEquals(custom1.skillId, custom2.skillId)
        f.close()
    }

    @Test
    fun `skill selection rejects unmatched requested skill and non executable skill`() {
        val f = novelAgentFixture()
        val w = seedNovelWorld(f)

        // 指定 Skill 不在本次匹配结果内（analysis-critique 未声明 REWRITE）
        val unmatched = f.app.novelAgent.run(
            request(w, "重写第一章", preferredSkillId = CoreSkills.ANALYSIS_CRITIQUE.skillId),
        )
        assertEquals(NovelAgentOutcome.FAILED, unmatched.outcome)
        assertEquals(NovelAgentErrorCodes.SKILL_NOT_MATCHED, unmatched.failure?.code)

        // 世界模型 Skill 本阶段没有执行绑定（属既有 Knowledge Update 链）
        val notExecutable = f.app.novelAgent.run(
            request(w, "分析第一章", preferredSkillId = CoreSkills.KNOWLEDGE_WORLD_MODEL.skillId),
        )
        assertEquals(CoreSkills.KNOWLEDGE_WORLD_MODEL.skillId, notExecutable.skillId)
        assertEquals(NovelAgentErrorCodes.SKILL_NOT_EXECUTABLE, notExecutable.failure?.code)
        f.close()
    }

    @Test
    fun `disabled skill is excluded from matching and no match is a typed failure`() {
        val f = novelAgentFixture()
        val w = seedNovelWorld(f)

        // 单项 disabled：writing 不再参与匹配 → CONTINUE 落到 knowledge-world-model（证明 disabled 被排除）
        val writingDisabled = novelAgentWithRegistry(
            f,
            SkillRegistry.of(
                CoreSkills.all().map { if (it.skillId == CoreSkills.WRITING.skillId) it.copy(enabled = false) else it },
            ),
        )
        val fallback = writingDisabled.run(request(w, "继续写第一章"))
        assertEquals(CoreSkills.KNOWLEDGE_WORLD_MODEL.skillId, fallback.skillId, "disabled Skill 不得被选中")
        assertEquals(NovelAgentErrorCodes.SKILL_NOT_EXECUTABLE, fallback.failure?.code)

        // 无任何匹配 → SKILL_NOT_FOUND
        val noneMatch = novelAgentWithRegistry(
            f,
            SkillRegistry.of(
                CoreSkills.all().map {
                    if (it.skillId == CoreSkills.WRITING.skillId || it.skillId == CoreSkills.KNOWLEDGE_WORLD_MODEL.skillId) {
                        it.copy(enabled = false)
                    } else {
                        it
                    }
                },
            ),
        )
        val noSkill = noneMatch.run(request(w, "继续写第一章"))
        assertEquals(NovelAgentOutcome.FAILED, noSkill.outcome)
        assertEquals(NovelAgentErrorCodes.SKILL_NOT_FOUND, noSkill.failure?.code)
        f.close()
    }

    /* ---------------- Context（复用 I6） ---------------- */

    @Test
    fun `context is built through context engine with project scope and budget`() {
        val f = novelAgentFixture()
        val w = seedNovelWorld(f)

        // 用**既有会话**跑一次，使 ContextRequest 完全可预测 → 与 I6 直接构建的 pack 逐一比对
        val session = f.app.agentSessions.startSession(w.novelId).sessionId
        val tinyBudget = ContextBudget(maxTokens = 1)
        val result = f.app.novelAgent.run(request(w, "继续写第一章", sessionId = session, budget = tinyBudget))

        val expected = f.app.contextEngine.build(
            ContextRequest(
                projectId = w.projectId,
                purpose = IntentType.CONTINUE,
                sessionId = session,
                focusChapterId = w.chapterId,
                budget = tinyBudget,
            ),
        )
        assertEquals(expected.packVersion, result.contextPackVersion, "Context 必须由 I6 按同一请求构建（含 budget）")
        assertEquals(expected.packId, result.contextPackId)
        assertTrue(expected.budgetGuard.isTruncated, "极小预算应产生淘汰记录（预算被真正使用）")

        // 焦点章节越界（另一个 Project 的章节）→ CONTEXT_BUILD_FAILED，且不泄漏存在性
        val otherNovel = f.app.novels.createOriginal(title = "书B")
        val otherChapter = f.app.chapters.createNextChapter("B 第一章", otherNovel).chapterId
        val crossProject = f.app.novelAgent.run(request(w, "继续写第一章", activeChapterId = otherChapter))
        assertEquals(NovelAgentErrorCodes.CONTEXT_BUILD_FAILED, crossProject.failure?.code)
        f.close()
    }

    @Test
    fun `declared variant scope must match project runtime state`() {
        val f = novelAgentFixture()
        val w = seedNovelWorld(f)

        val mismatch = f.app.novelAgent.run(request(w, "继续写第一章", activeVariantId = VariantId("v-none")))
        assertEquals(NovelAgentErrorCodes.SCOPE_MISMATCH, mismatch.failure?.code)
        assertEquals(NovelAgentOutcome.FAILED, mismatch.outcome)
        f.close()
    }

    /* ---------------- Tool（只经 I5；Skill.allowedTools 强制 + I4 ToolCallLog） ---------------- */

    @Test
    fun `skill allowed tool call goes through product tool service and is logged`() {
        val f = novelAgentFixture()
        val w = seedNovelWorld(f)

        val result = f.app.novelAgent.run(request(w, "继续写第一章"))

        assertEquals(1, result.toolCallCount, "写作执行应经 I5 读取基线正文一次")
        val calls = f.app.toolCallLogs.listByProject(w.projectId)
        assertEquals(1, calls.size)
        assertEquals("get_latest_draft", calls.single().toolName.value)
        assertEquals(AgentLogStatus.COMPLETED, calls.single().status)
        // Working Draft 的基线引用 = 章节既有 Canonical 正文（经工具读到）
        val draft = assertNotNull(result.workingDraftId).let { f.app.workingDrafts.get(it) }
        assertEquals(w.canonicalDraftId, draft.base.baseDraftId)
        f.close()
    }

    @Test
    fun `skill forbidden tool is rejected without executing`() {
        val f = novelAgentFixture()
        val w = seedNovelWorld(f)
        val noTools = novelAgentWithRegistry(
            f,
            SkillRegistry.of(
                CoreSkills.all().map { if (it.skillId == CoreSkills.WRITING.skillId) it.copy(allowedTools = emptyList()) else it },
            ),
        )

        val result = noTools.run(request(w, "继续写第一章"))

        assertEquals(NovelAgentErrorCodes.TOOL_NOT_ALLOWED, result.failure?.code)
        assertTrue(result.summary.contains("allowedTools"))
        assertEquals(0, f.app.toolCallLogs.listByProject(w.projectId).size, "未授权工具不得执行，也不得留痕")
        assertTrue(f.app.draftRepository.listByChapter(w.chapterId).size == 1, "拒绝后 Canonical 不变")
        f.close()
    }

    @Test
    fun `chapter without canonical draft yields working draft without base`() {
        val f = novelAgentFixture()
        val w = seedNovelWorld(f)

        // chapter2 尚无 Canonical 正文 → 工具以稳定码 NOT_FOUND 表达"尚无草稿" ⇒ 无基线（仍可继续写）
        val result = f.app.novelAgent.run(request(w, "继续写第二章", activeChapterId = w.secondChapterId))

        assertEquals(NovelAgentOutcome.WAITING_HUMAN, result.outcome)
        assertEquals(1, result.toolCallCount)
        val draft = assertNotNull(result.workingDraftId).let { f.app.workingDrafts.get(it) }
        assertNull(draft.base.baseDraftId, "无基线 ⇒ Working Draft 不带 base reference")
        assertTrue(f.app.draftRepository.listByChapter(w.secondChapterId).isEmpty(), "不得写入 Canonical")
        f.close()
    }

    /* ---------------- Session（复用 I3；不改变其生命周期语义） ---------------- */

    @Test
    fun `session is started reused and settled with existing semantics`() {
        val f = novelAgentFixture()
        val w = seedNovelWorld(f)

        // 新建会话：ACTIVE →（等待人工）PAUSED
        val result = f.app.novelAgent.run(request(w, "继续写第一章"))
        val sessionId = assertNotNull(result.sessionId)
        assertEquals(AgentSessionStatus.PAUSED, f.app.agentSessions.sessionOf(sessionId).status)

        // 复用既有会话：PAUSED → ACTIVE
        val second = f.app.novelAgent.run(request(w, "继续写第一章", sessionId = sessionId))
        assertEquals(sessionId, second.sessionId, "复用既有会话，不新建")
        assertEquals(AgentSessionStatus.PAUSED, f.app.agentSessions.sessionOf(sessionId).status)

        // 规划类任务正常完成：ACTIVE → COMPLETED
        val plan = f.app.novelAgent.run(request(w, "规划第一章", sessionId = sessionId))
        assertEquals(NovelAgentOutcome.COMPLETED, plan.outcome)
        assertEquals(AgentSessionStatus.COMPLETED, f.app.agentSessions.sessionOf(sessionId).status)

        // 终态会话不可再运行（既有语义；不新建第二套状态机）
        val afterTerminal = f.app.novelAgent.run(request(w, "规划第一章", sessionId = sessionId))
        assertEquals(NovelAgentErrorCodes.SESSION_STATE_INVALID, afterTerminal.failure?.code)
        f.close()
    }

    @Test
    fun `session scope isolation and cancellation are handled`() {
        val f = novelAgentFixture()
        val w = seedNovelWorld(f)

        // 跨 Project 会话：统一表现为"不存在"
        val otherNovel = f.app.novels.createOriginal(title = "书B")
        val otherSession = f.app.agentSessions.startSession(otherNovel).sessionId
        val cross = f.app.novelAgent.run(request(w, "继续写第一章", sessionId = otherSession))
        assertEquals(NovelAgentErrorCodes.SESSION_NOT_FOUND, cross.failure?.code)

        // 不存在的会话
        val missing = f.app.novelAgent.run(
            request(w, "继续写第一章", sessionId = AgentSessionId("s-missing")),
        )
        assertEquals(NovelAgentErrorCodes.SESSION_NOT_FOUND, missing.failure?.code)

        // 已取消会话：outcome = CANCELLED（不是 FAILED）
        val cancelled = f.app.agentSessions.startSession(w.novelId).sessionId
        f.app.agentSessions.cancel(cancelled)
        val result = f.app.novelAgent.run(request(w, "继续写第一章", sessionId = cancelled))
        assertEquals(NovelAgentOutcome.CANCELLED, result.outcome)
        assertEquals(NovelAgentErrorCodes.SESSION_CANCELLED, result.failure?.code)
        f.close()
    }

    @Test
    fun `activity records every orchestration phase`() {
        val f = novelAgentFixture()
        val w = seedNovelWorld(f)

        val result = f.app.novelAgent.run(request(w, "继续写第一章"))

        assertEquals(6, result.activityIds.size, "INTENT → SKILL → CONTEXT → EXECUTE → REVIEW → GATE")
        val kinds = result.activityIds.map { f.app.activities.get(it).kind }
        assertEquals(listOf("INTENT", "SKILL", "CONTEXT", "EXECUTE", "REVIEW", "GATE"), kinds)
        result.activityIds.forEach { id ->
            assertEquals(AgentLogStatus.COMPLETED, f.app.activities.get(id).status)
            assertEquals(assertNotNull(result.sessionId), f.app.activities.get(id).sessionId)
        }
        f.close()
    }

    @Test
    fun `missing focus chapter is a typed failure`() {
        val f = novelAgentFixture()
        val w = seedNovelWorld(f)
        val result = f.app.novelAgent.run(request(w, "继续写第一章", activeChapterId = null))
        assertEquals(NovelAgentErrorCodes.MISSING_FOCUS_CHAPTER, result.failure?.code)
        f.close()
    }

    @Test
    fun `unknown project is a typed failure without session`() {
        val f = novelAgentFixture()
        val w = seedNovelWorld(f)
        val request = NovelAgentRequest(
            projectId = ProjectId("p-missing"),
            userIntent = "继续写第一章",
            activeChapterId = w.chapterId,
        )
        val result = f.app.novelAgent.run(request)
        assertEquals(NovelAgentErrorCodes.PROJECT_NOT_FOUND, result.failure?.code)
        assertNull(result.sessionId, "Project 不存在时不建立会话")
        f.close()
    }

    @Test
    fun `explicit skill id is honored when it matches`() {
        val f = novelAgentFixture()
        val w = seedNovelWorld(f)
        val result = f.app.novelAgent.run(
            request(w, "继续写第一章", preferredSkillId = SkillId(CoreSkills.WRITING.skillId.value)),
        )
        assertEquals(CoreSkills.WRITING.skillId, result.skillId)
        assertEquals(WorkingDraftStatus.VALIDATED, result.workingDraftId?.let { f.app.workingDrafts.get(it).status })
        f.close()
    }
}