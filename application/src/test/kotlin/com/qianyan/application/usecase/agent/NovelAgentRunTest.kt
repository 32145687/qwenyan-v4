package com.qianyan.application.usecase.agent

import com.qianyan.model.ChapterId
import com.qianyan.model.change.ChangeArtifactStatus
import com.qianyan.model.change.ChangeKind
import com.qianyan.model.commit.CommitApproval
import com.qianyan.model.novelagent.NovelAgentApprovalState
import com.qianyan.model.novelagent.NovelAgentErrorCodes
import com.qianyan.model.novelagent.NovelAgentOutcome
import com.qianyan.model.session.AgentSessionStatus
import com.qianyan.model.workingdraft.WorkingDraftLimits
import com.qianyan.model.workingdraft.WorkingDraftStatus
import com.qianyan.model.workflow.HumanGateStatus
import com.qianyan.model.workflow.WorkflowStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * I11 · 编排结果（§13 / §14 / §15 / §16 / §19 / §25）。
 *
 * 覆盖：Working Draft / Validation / Diff / Artifact、Human Gate（等待 / 批准 / 拒绝）、
 * Commit（成功 / 过期 / 重复 / 批准无效）、Canonical 隔离、以及完整闭环端到端。
 */
class NovelAgentRunTest {

    private val writerContent = "第一章正文（新写）。"

    /* ---------------- Working Draft / Validation / Artifact ---------------- */

    @Test
    fun `writing run produces working draft artifact and waits for human approval without touching canonical`() {
        val f = novelAgentFixture()
        val w = seedNovelWorld(f)
        val before = canonicalCounts(f)

        val result = f.app.novelAgent.run(request(w, "继续写第一章"))

        assertEquals(NovelAgentOutcome.WAITING_HUMAN, result.outcome)
        assertEquals(ChangeArtifactStatus.READY, result.artifactStatus)
        assertNotNull(result.artifactId)
        assertNotNull(result.workingDraftId)

        // Working Draft（I8）：真实产出、状态 VALIDATED、归属正确、可追踪
        val draft = f.app.workingDrafts.get(assertNotNull(result.workingDraftId))
        assertEquals(writerContent, draft.content)
        assertEquals(w.projectId, draft.projectId)
        assertEquals(w.chapterId, draft.target.chapterId)
        assertEquals(assertNotNull(result.sessionId), draft.sessionId)
        assertEquals(WorkingDraftStatus.VALIDATED, draft.status)
        assertEquals(w.canonicalDraftId, draft.base.baseDraftId, "基线 = 章节既有 Canonical 正文载体")

        // Artifact（I9）：Diff 形状正确、不可写 Canonical
        val artifact = f.app.changes.get(assertNotNull(result.artifactId))
        assertEquals(ChangeKind.MODIFIED, artifact.change.kind)
        assertEquals(w.canonicalDraftId, artifact.change.base.baseDraftId)
        assertTrue(artifact.change.summary.isNotBlank())

        // Canonical 隔离（§25）：未提交前 Canonical 完全不变；桥接只新增 Workflow/Gate
        val after = canonicalCounts(f)
        listOf("Chapter", "ChapterDraft", "ProjectState", "StoryFoundation", "Task", "CommitHistory").forEach {
            assertEquals(before[it], after[it], "$it 不得变化")
        }
        assertEquals(before["Workflow"]!! + 1, after["Workflow"], "桥接必须创建正式章节 Workflow")
        assertEquals(NovelAgentApprovalState.BRIDGED, result.approvalState, "候选必须报告已进入正式审批")
        val wf = assertNotNull(f.app.workflowRepository.getWorkflowByActiveChapter(w.chapterId))
        assertEquals(WorkflowStatus.WAITING_HUMAN, wf.status)
        val gate = assertNotNull(f.app.workflowRepository.getGate(assertNotNull(wf.pendingGateId)))
        assertEquals(HumanGateStatus.PENDING, gate.status)
        val expectedWorkingRef = "WORKING:${assertNotNull(result.workingDraftId).value}"
        assertEquals(
            expectedWorkingRef,
            f.app.workflowFacade.getChapterProgress(w.chapterId).pendingWorkingRef,
            "进度投影必须给出该候选的 WORKING 引用",
        )
        assertEquals(
            expectedWorkingRef,
            f.app.workflowRepository.listSteps(wf.workflowId)
                .last { it.phase == com.qianyan.model.workflow.WorkflowStepPhase.REVISION }
                .resultReference,
            "Workflow Step 必须持久化该候选的 WORKING 引用",
        )
        assertEquals("第一章旧正文。", f.app.draftRepository.getById(w.canonicalDraftId)?.content)
        assertEquals(1, f.app.draftRepository.listByChapter(w.chapterId).size)
        f.close()
    }

    @Test
    fun `no changes artifact completes without commit`() {
        val f = novelAgentFixture()
        // Canonical 内容 == Writer 产出 ⇒ NO_CHANGES
        val w = seedNovelWorld(f, canonicalContent = writerContent)
        val before = canonicalCounts(f)

        val result = f.app.novelAgent.run(request(w, "继续写第一章"))

        assertEquals(NovelAgentOutcome.COMPLETED, result.outcome)
        assertEquals(ChangeArtifactStatus.NO_CHANGES, result.artifactStatus)
        assertNull(result.commitId)
        assertTrue(result.summary.contains("NO_CHANGES"))
        assertEquals(before, canonicalCounts(f))
        f.close()
    }

    @Test
    fun `invalid artifact fails without any canonical write`() {
        val f = novelAgentFixture(routingGateway(writerContent = "甲".repeat(WorkingDraftLimits.MAX_CONTENT_CHARS + 1)))
        val w = seedNovelWorld(f)
        val before = canonicalCounts(f)
        val expectedContent = "甲".repeat(WorkingDraftLimits.MAX_CONTENT_CHARS + 1)

        val result = f.app.novelAgent.run(request(w, "继续写第一章"))

        assertEquals(NovelAgentOutcome.FAILED, result.outcome)
        assertEquals(NovelAgentErrorCodes.ARTIFACT_INVALID, result.failure?.code)
        assertEquals(ChangeArtifactStatus.INVALID, result.artifactStatus, "Artifact 仍应返还给调用方（含校验结论）")
        assertNotNull(result.artifactId)
        // Working Draft 未被"错误修改"：内容保持 Writer 产出，仅是状态由 Validation 记录
        val draft = f.app.workingDrafts.get(assertNotNull(result.workingDraftId))
        assertEquals(expectedContent, draft.content)
        assertEquals(WorkingDraftStatus.INVALID, draft.status)
        // 失败时 Canonical 与 History 均不变
        assertEquals(before, canonicalCounts(f))
        assertEquals(0, f.app.commits.history(w.projectId).size)
        // 会话收敛为既有 FAILED 语义
        assertEquals(
            AgentSessionStatus.FAILED,
            f.app.agentSessions.sessionOf(assertNotNull(result.sessionId)).status,
        )
        f.close()
    }

    @Test
    fun `rewrite run uses canonical draft as base and revision agent output`() {
        val f = novelAgentFixture()
        val w = seedNovelWorld(f)

        val result = f.app.novelAgent.run(request(w, "重写第一章"))

        assertEquals(NovelAgentOutcome.WAITING_HUMAN, result.outcome)
        val draft = f.app.workingDrafts.get(assertNotNull(result.workingDraftId))
        assertEquals("第一章正文（修订）。", draft.content, "改写必须使用既有 Revision Agent 的产出")
        assertEquals(w.canonicalDraftId, draft.base.baseDraftId)
        assertEquals(1, f.app.draftRepository.listByChapter(w.chapterId).size, "改写过程不得写 Canonical")
        f.close()
    }

    @Test
    fun `analysis run is read only and completes without artifact`() {
        val f = novelAgentFixture()
        val w = seedNovelWorld(f)
        val before = canonicalCounts(f)

        val result = f.app.novelAgent.run(request(w, "分析第一章"))

        assertEquals(NovelAgentOutcome.COMPLETED, result.outcome)
        assertNull(result.workingDraftId, "分析不产生 Working Draft")
        assertNull(result.artifactId, "分析不产生变更提案")
        assertTrue(result.summary.contains("未写入 Canonical"))
        assertEquals(before, canonicalCounts(f))
        f.close()
    }

    @Test
    fun `planning run does not create chapters or drafts`() {
        val f = novelAgentFixture()
        val w = seedNovelWorld(f)
        val before = canonicalCounts(f)
        val chaptersBefore = f.app.chapters.listByNovel(w.novelId).map { it.chapterId }

        val result = f.app.novelAgent.run(request(w, "规划第一章"))

        assertEquals(NovelAgentOutcome.COMPLETED, result.outcome)
        assertEquals(w.chapterId, result.planChapterId, "规划绑定既有焦点章节（I11 不自行建章）")
        assertEquals(chaptersBefore, f.app.chapters.listByNovel(w.novelId).map { it.chapterId })
        assertEquals(before, canonicalCounts(f))
        f.close()
    }

    /* ---------------- Human Gate（等待 / 批准 / 拒绝） ---------------- */

    @Test
    fun `approved resume commits canonical and appends history`() {
        val f = novelAgentFixture()
        val w = seedNovelWorld(f)
        val before = canonicalCounts(f)

        val waiting = f.app.novelAgent.run(request(w, "继续写第一章"))
        assertEquals(NovelAgentOutcome.WAITING_HUMAN, waiting.outcome)
        assertEquals(NovelAgentApprovalState.BRIDGED, waiting.approvalState)
        val sessionId = assertNotNull(waiting.sessionId)
        val artifactId = assertNotNull(waiting.artifactId)
        val gateId = pendingGateId(f, w.chapterId)
        assertEquals(AgentSessionStatus.PAUSED, f.app.agentSessions.sessionOf(sessionId).status)

        // 正式批准 = 既有 ChapterWorkflow 链路（不是 NovelAgent.resume）
        val progress = f.app.workflowFacade.approve(w.chapterId)
        assertFalse(progress.waitingForUser)

        // Session 由协调入口收敛（不产生第二次提交）
        val refreshed = f.app.novelAgent.refreshCandidate(
            request(w, "继续写第一章", sessionId = sessionId),
            artifactId,
        )
        assertEquals(NovelAgentOutcome.COMPLETED, refreshed.outcome)
        assertEquals(AgentSessionStatus.COMPLETED, f.app.agentSessions.sessionOf(sessionId).status)

        // Canonical 已更新：新增版本链 head（既有 Draft 保留）
        val latest = assertNotNull(f.app.writerUseCases.latestDraft(w.chapterId))
        assertEquals(writerContent, latest.content)
        assertEquals(w.canonicalDraftId, latest.previousDraftId)
        assertEquals(2, f.app.draftRepository.listByChapter(w.chapterId).size)
        assertEquals("第一章旧正文。", f.app.draftRepository.getById(w.canonicalDraftId)?.content)

        // History（I10）：新增一条 COMMIT
        val entries = f.app.commits.history(w.projectId)
        assertEquals(1, entries.size)
        assertEquals(artifactId, entries.single().artifactId)
        assertEquals(gateId, entries.single().gateId, "CommitHistory 必须记录正式 Gate")

        // Working Draft 已由 I8 既有能力收尾
        assertEquals(WorkingDraftStatus.DISCARDED, f.app.workingDrafts.get(assertNotNull(waiting.workingDraftId)).status)

        // 只有 ChapterDraft / CommitHistory / 正式 Workflow 增长；其余 Canonical 与流程表不变
        val after = canonicalCounts(f)
        assertEquals(before["ChapterDraft"]!! + 1, after["ChapterDraft"])
        assertEquals(before["CommitHistory"]!! + 1, after["CommitHistory"])
        assertEquals(before["Workflow"]!! + 1, after["Workflow"], "桥接创建一个正式 Workflow")
        listOf("Chapter", "ProjectState", "StoryFoundation", "Task").forEach {
            assertEquals(before[it], after[it], "$it 不得变化")
        }

        // 重复正式批准幂等：不重复提交
        f.app.workflowFacade.approve(w.chapterId)
        assertEquals(1, f.app.commits.history(w.projectId).size)

        // D9：重复 resume 返回既有提交事实，不重复写 Canonical
        val resumed = f.app.novelAgent.resume(
            request(w, "继续写第一章", sessionId = sessionId),
            artifactId,
            CommitApproval(gateId),
        )
        assertEquals(NovelAgentOutcome.COMPLETED, resumed.outcome)
        assertEquals(entries.single().historyId, resumed.historyId)
        assertEquals(1, f.app.commits.history(w.projectId).size)
        f.close()
    }

    @Test
    fun `end to end intent to commit closes the loop`() {
        val f = novelAgentFixture()
        val w = seedNovelWorld(f)

        // 1) Intent → Skill → Context
        val waiting = f.app.novelAgent.run(request(w, "继续写第一章"))
        assertEquals("CONTINUE", waiting.intent.intentType.name)
        assertEquals("skill.writing", assertNotNull(waiting.skillId).value)
        assertNotNull(waiting.contextPackId)
        assertEquals(6, waiting.activityIds.size)
        assertEquals(1, waiting.toolCallCount, "Tool / Agent 执行：经 I5 读取基线")

        // 2) Working Draft → Validation → Diff → Artifact
        assertEquals(ChangeArtifactStatus.READY, waiting.artifactStatus)
        val artifact = f.app.changes.get(assertNotNull(waiting.artifactId))
        assertTrue(artifact.change.diff.hasChanges)
        assertEquals(ChangeKind.MODIFIED, artifact.change.kind)

        // 3) 正式 Human Gate（桥接后由 ChapterWorkflow 创建）
        assertEquals(NovelAgentOutcome.WAITING_HUMAN, waiting.outcome)
        assertEquals(NovelAgentApprovalState.BRIDGED, waiting.approvalState)
        assertNotNull(pendingGateId(f, w.chapterId))
        assertEquals(1, f.app.draftRepository.listByChapter(w.chapterId).size, "提交前 Canonical 未变")

        // 4) 正式批准 → Commit（唯一 Canonical 写入路径）
        f.app.workflowFacade.approve(w.chapterId)
        val refreshed = f.app.novelAgent.refreshCandidate(
            request(w, "继续写第一章", sessionId = assertNotNull(waiting.sessionId)),
            artifact.artifactId,
        )
        assertEquals(NovelAgentOutcome.COMPLETED, refreshed.outcome)
        assertEquals(writerContent, f.app.writerUseCases.latestDraft(w.chapterId)?.content)
        assertEquals(1, f.app.commits.history(w.projectId).size)
        f.close()
    }

    @Test
    fun `rejected run is cancelled and working draft is discarded`() {
        val f = novelAgentFixture()
        val w = seedNovelWorld(f)
        val before = canonicalCounts(f)

        val waiting = f.app.novelAgent.run(request(w, "继续写第一章"))
        val sessionId = assertNotNull(waiting.sessionId)
        val artifactId = assertNotNull(waiting.artifactId)
        val gateId = pendingGateId(f, w.chapterId)

        // D6：正式 Gate 仍待决时，NovelAgent.reject 不得绕过正式拒绝直接丢弃候选
        val early = f.app.novelAgent.reject(
            request(w, "继续写第一章", sessionId = sessionId),
            artifactId,
            reason = "用户不同意这个方向",
        )
        assertEquals(NovelAgentOutcome.FAILED, early.outcome)
        assertEquals(NovelAgentErrorCodes.CANDIDATE_PENDING_GATE, early.failure?.code)
        assertEquals(AgentSessionStatus.PAUSED, f.app.agentSessions.sessionOf(sessionId).status)
        assertEquals(WorkingDraftStatus.VALIDATED, f.app.workingDrafts.get(assertNotNull(waiting.workingDraftId)).status)
        assertEquals(HumanGateStatus.PENDING, f.app.workflowRepository.getGate(gateId)?.status)

        // 正式拒绝先经 ChapterWorkflow Gate
        f.app.workflowFacade.reject(w.chapterId)
        assertEquals(HumanGateStatus.RESOLVED, f.app.workflowRepository.getGate(gateId)?.status)

        // 随后由协调入口收敛 Session 与候选
        val rejected = f.app.novelAgent.reject(
            request(w, "继续写第一章", sessionId = sessionId),
            artifactId,
            reason = "用户不同意这个方向",
        )
        assertEquals(NovelAgentOutcome.CANCELLED, rejected.outcome)
        assertEquals(AgentSessionStatus.CANCELLED, f.app.agentSessions.sessionOf(sessionId).status)
        assertEquals(
            WorkingDraftStatus.DISCARDED,
            f.app.workingDrafts.get(assertNotNull(waiting.workingDraftId)).status,
            "拒绝后临时成果经 I8 既有能力收尾",
        )

        // 重复拒绝幂等
        val repeated = f.app.novelAgent.reject(
            request(w, "继续写第一章", sessionId = sessionId),
            artifactId,
            reason = "重复拒绝",
        )
        assertEquals(NovelAgentOutcome.CANCELLED, repeated.outcome)

        // Canonical / History 不变；桥接只新增 Workflow
        val after = canonicalCounts(f)
        listOf("Chapter", "ChapterDraft", "ProjectState", "StoryFoundation", "Task", "CommitHistory").forEach {
            assertEquals(before[it], after[it], "$it 不得变化")
        }
        assertEquals(before["Workflow"]!! + 1, after["Workflow"])
        assertEquals(0, f.app.commits.history(w.projectId).size)

        // 拒绝是终局：已取消会话不可再续跑
        val afterReject = f.app.novelAgent.resume(
            request(w, "继续写第一章", sessionId = sessionId),
            artifactId,
            approvedCommitGate(
                f,
                w,
                workingDraftId = assertNotNull(waiting.workingDraftId),
                gateId = "gate-i11-after-reject",
            ),
        )
        assertEquals(NovelAgentOutcome.CANCELLED, afterReject.outcome)
        assertEquals(NovelAgentErrorCodes.SESSION_CANCELLED, afterReject.failure?.code)
        f.close()
    }

    @Test
    fun `resume rejects unapproved gate`() {
        val f = novelAgentFixture()
        val w = seedNovelWorld(f)
        val waiting = f.app.novelAgent.run(request(w, "继续写第一章"))
        val gateId = pendingGateId(f, w.chapterId)
        val before = canonicalCounts(f)

        val result = f.app.novelAgent.resume(
            request(w, "继续写第一章", sessionId = assertNotNull(waiting.sessionId)),
            assertNotNull(waiting.artifactId),
            CommitApproval(gateId),
        )

        assertEquals(NovelAgentErrorCodes.COMMIT_FAILED, result.failure?.code)
        val failure = assertNotNull(result.failure)
        assertTrue(failure.detail.contains("HUMAN_APPROVAL_REQUIRED"), failure.detail)
        assertEquals(before, canonicalCounts(f))
        assertEquals(0, f.app.commits.history(w.projectId).size)
        f.close()
    }

    /* ---------------- Commit 失败路径 ---------------- */

    @Test
    fun `stale artifact cannot be committed after canonical changed`() {
        val f = novelAgentFixture()
        val w = seedNovelWorld(f)
        val waiting = f.app.novelAgent.run(request(w, "继续写第一章"))
        val approval = approvedCommitGate(f, w, workingDraftId = assertNotNull(waiting.workingDraftId))
        // Artifact 就绪后 Canonical 被其他操作修改 → 禁止静默覆盖
        f.app.writerUseCases.saveContent(w.canonicalDraftId, "第一章旧正文（他人改写）。")

        val result = f.app.novelAgent.resume(
            request(w, "继续写第一章", sessionId = assertNotNull(waiting.sessionId)),
            assertNotNull(waiting.artifactId),
            approval,
        )

        assertEquals(NovelAgentErrorCodes.COMMIT_FAILED, result.failure?.code)
        val failure = assertNotNull(result.failure)
        assertTrue(failure.detail.contains("STALE_ARTIFACT"), failure.detail)
        assertEquals("第一章旧正文（他人改写）。", f.app.draftRepository.getById(w.canonicalDraftId)?.content)
        assertEquals(1, f.app.draftRepository.listByChapter(w.chapterId).size, "过期提交不得写入 Canonical")
        assertEquals(0, f.app.commits.history(w.projectId).size)
        f.close()
    }

    @Test
    fun `duplicate commit is rejected`() {
        val f = novelAgentFixture()
        val w = seedNovelWorld(f)
        val waiting = f.app.novelAgent.run(request(w, "继续写第一章"))
        val artifactId = assertNotNull(waiting.artifactId)
        val sessionId = assertNotNull(waiting.sessionId)
        val gateId = pendingGateId(f, w.chapterId)

        f.app.workflowFacade.approve(w.chapterId)
        val draftsAfterCommit = f.app.draftRepository.listByChapter(w.chapterId).map { it.draftId }

        // 重复正式批准 → 幂等，不产生重复提交
        f.app.workflowFacade.approve(w.chapterId)
        // 重复 resume 同 Artifact → 返回既有提交事实（D9），不重复提交
        val again = f.app.novelAgent.resume(
            request(w, "继续写第一章", sessionId = sessionId),
            artifactId,
            CommitApproval(gateId),
        )

        assertEquals(NovelAgentOutcome.COMPLETED, again.outcome)
        assertEquals(1, f.app.commits.history(w.projectId).size)
        assertEquals(draftsAfterCommit, f.app.draftRepository.listByChapter(w.chapterId).map { it.draftId })
        f.close()
    }

    @Test
    fun `resume rejects artifact of another project`() {
        val f = novelAgentFixture()
        val w = seedNovelWorld(f)
        val waiting = f.app.novelAgent.run(request(w, "继续写第一章"))
        val approval = approvedCommitGate(f, w, workingDraftId = assertNotNull(waiting.workingDraftId))

        val otherProject = f.app.projects.createProject(title = "书B")
        val otherRequest = request(w, "继续写第一章", sessionId = waiting.sessionId)
            .copy(projectId = otherProject.projectId)

        val result = f.app.novelAgent.resume(
            otherRequest,
            assertNotNull(waiting.artifactId),
            approval,
        )
        assertEquals(NovelAgentErrorCodes.ARTIFACT_NOT_FOUND, result.failure?.code)
        f.close()
    }

    // ---- helpers ----

    private fun pendingGateId(f: NovelAgentFixture, chapterId: ChapterId) =
        assertNotNull(
            assertNotNull(f.app.workflowRepository.getWorkflowByActiveChapter(chapterId)).pendingGateId,
        )

    private fun canonicalCounts(f: NovelAgentFixture): Map<String, Long> = listOf(
        "Chapter", "ChapterDraft", "ProjectState", "StoryFoundation",
        "Workflow", "Task", "CommitHistory",
    ).associateWith { countRows(f.handle, it) }
}