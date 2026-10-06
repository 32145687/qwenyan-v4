package com.qianyan.application.usecase.agent

import com.qianyan.agent.agents.CoreSkills
import com.qianyan.agent.agents.SkillRegistry
import com.qianyan.application.error.ApplicationException
import com.qianyan.application.error.ErrorMapper
import com.qianyan.application.usecase.UseCase
import com.qianyan.application.usecase.action.ActionPolicyUseCases
import com.qianyan.application.usecase.change.ChangeUseCases
import com.qianyan.application.usecase.chapter.ChapterUseCases
import com.qianyan.application.usecase.commit.CommitUseCases
import com.qianyan.application.usecase.context.ContextEngineUseCases
import com.qianyan.application.usecase.draft.WorkingDraftUseCases
import com.qianyan.application.usecase.log.ActivityUseCases
import com.qianyan.application.usecase.project.ProjectUseCases
import com.qianyan.application.usecase.runtimeintegration.RuntimeIntegrationUseCases
import com.qianyan.runtime.contract.RuntimeSession
import com.qianyan.application.usecase.session.AgentSessionUseCases
import com.qianyan.application.usecase.tool.ProductToolNames
import com.qianyan.application.usecase.tool.ProductToolService
import com.qianyan.application.usecase.tool.DraftView
import com.qianyan.application.usecase.writing.WriterAgent
import com.qianyan.application.usecase.writing.WriterUseCases
import com.qianyan.application.usecase.writing.critique.CritiqueAgent
import com.qianyan.application.usecase.writing.planning.PlannerAgent
import com.qianyan.application.usecase.writing.planning.PlanningContextAssembly
import com.qianyan.application.usecase.writing.revision.RevisionAgent
import com.qianyan.model.ActivityId
import com.qianyan.model.AgentSessionId
import com.qianyan.model.BaseNovelId
import com.qianyan.model.ChapterId
import com.qianyan.model.ChapterPlanId
import com.qianyan.model.DraftId
import com.qianyan.model.IntentType
import com.qianyan.model.PlanningScope
import com.qianyan.model.RequestId
import com.qianyan.model.action.ActionDecision
import com.qianyan.model.action.AgentAction
import com.qianyan.model.action.AgentActionKind
import com.qianyan.model.agent.ToolName
import com.qianyan.model.change.ChangeArtifact
import com.qianyan.model.change.ChangeArtifactId
import com.qianyan.model.change.ChangeArtifactStatus
import com.qianyan.model.commit.CommitApproval
import com.qianyan.model.commit.CommitId
import com.qianyan.model.commit.CommitHistoryId
import com.qianyan.model.context.ContextPack
import com.qianyan.model.context.ContextRequest
import com.qianyan.model.context.TargetKind
import com.qianyan.model.context.TargetRef
import com.qianyan.model.context.TargetRefId
import com.qianyan.model.context.UserWritingRequest
import com.qianyan.model.novelagent.NovelAgentErrorCodes
import com.qianyan.model.novelagent.NovelAgentFailure
import com.qianyan.model.novelagent.NovelAgentOutcome
import com.qianyan.model.novelagent.NovelAgentRequest
import com.qianyan.model.novelagent.NovelAgentResult
import com.qianyan.model.novelagent.NovelIntentAnalysis
import com.qianyan.model.project.Project
import com.qianyan.model.session.AgentSession
import com.qianyan.model.session.AgentSessionStatus
import com.qianyan.model.skill.Skill
import com.qianyan.model.skill.SkillId
import com.qianyan.model.spec.IssueSeverity
import com.qianyan.model.tool.ToolRequest
import com.qianyan.model.tool.ToolResult
import com.qianyan.model.workingdraft.WorkingDraftId
import com.qianyan.model.workingdraft.WorkingDraftTarget
import com.qianyan.model.workflow.WorkflowHumanGateId
import com.qianyan.model.story.Chapter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put

/**
 * I11 · Novel Agent（Novel IDE 的核心**编排**入口）。
 *
 * 职责（architecture §19 / §20 / §27）：**决定"下一步做什么"**，并把每一步交给既有能力执行。
 *
 * ```
 * User Intent → NovelAgent
 *   1 INTENT  确定性意图解析（规则表；无 LLM）           → 复用既有 IntentType
 *   2 SKILL   SkillRegistry.match + 确定性选择           → 复用 I7
 *   3 CONTEXT ContextEngineUseCases.build                → 复用 I6
 *   4 EXECUTE Skill 绑定的既有 Agent / 既有只读 Product Tool → 复用 P11 五 Agent + I5
 *   5 REVIEW  WorkingDraft → Validation → Diff → Artifact → 复用 I8 / I9
 *   6 GATE    ActionPolicy(COMMIT_CANONICAL)             → 复用 I2
 *   7 COMMIT  CommitUseCases.commit（唯一 Canonical 写入） → 复用 I10
 * ```
 *
 * 硬边界：
 *  - **不持有业务能力**：不碰 SQLite / Repository / ChapterDraft / Chapter / StoryFoundation / ProjectState，
 *    不调 [com.qianyan.provider.LLMGateway]，一切经既有 Application / Agent 能力完成；
 *  - **不绕过**：[ProductToolService]（工具）、[WorkingDraftUseCases]（临时产出）、[ChangeUseCases]（变更提案）、
 *    [CommitUseCases]（Canonical 写入）、[ActionPolicyUseCases]（权限）一律复用，不重造；
 *  - **五 Agent 保持**：Writer / Planner / Critic / Revision（以及 KnowledgeUpdate）**不被删除 / 重写 / 复制**；
 *    本类只把它们当作 Skill 的执行能力使用（保留其既有 Prompt / Parser / Snapshot / Revision≤3 语义）；
 *  - **不写 Canonical**：在执行 / 校验 / 生成 Artifact 阶段，Canonical Story 保持不变；
 *    唯一写入路径是 [CommitUseCases]（且需 I2 权限判定 + 人工批准凭据）；
 *  - **有限执行计划**：固定七相位、无 `while(true)`、无"LLM 决定下一步"；每相位落既有 I4 Activity（可追踪）；
 *  - **不建第二套状态机**：运行生命周期（活动 / 暂停 / 完成 / 失败 / 取消）一律用既有 AgentSession；
 *    [NovelAgentOutcome] 只是"这次 run 结束后调用方该做什么"的结果分类；
 *  - **不建第二套 Step / Plan 模型**：编排相位是**固定代码序列**（[OrchestrationPhase]），事实记录复用既有 Activity；
 *    既有 `:agent:runtime` 的 `AgentStep` 是"一次 LLM 回合的解析协议"，与本层相位语义不同，不复用也不复制；
 *  - **不涉及 P19**：不做创作决策（DecisionPolicy 不重算，按既有契约传空政策）；
 *  - **不创建 Human Gate**：需要人工确认时返回 `WAITING_HUMAN` + `artifactId`，由调用方经既有 Gate 取得批准后
 *    再调用 [resume]（Gate 的开启与决议仍属 I2 / 既有 P12 语义）。
 */
class NovelAgent(
    private val projects: ProjectUseCases,
    private val chapters: ChapterUseCases,
    private val sessions: AgentSessionUseCases,
    private val activities: ActivityUseCases,
    private val skillRegistry: SkillRegistry,
    private val contextEngine: ContextEngineUseCases,
    private val productTools: ProductToolService,
    private val workingDrafts: WorkingDraftUseCases,
    private val changes: ChangeUseCases,
    private val commits: CommitUseCases,
    private val actionPolicy: ActionPolicyUseCases,
    private val planner: PlannerAgent,
    private val writer: WriterAgent,
    private val critic: CritiqueAgent,
    private val rewriter: RevisionAgent,
    private val planningContexts: PlanningContextAssembly,
    private val reader: WriterUseCases,
    /**
     * I1 · Runtime 集成 seam（可为 null = 未装配外部 Runtime）。
     *
     * **只听契约，不认识任何 Adapter / 供应商**；且默认不介入任何相位 ——
     * 只有 [NovelAgentRequest.runtimeBacked] 显式 opt-in 时才建立一次运行时会话绑定，
     * 从而保证既有七相位、Change Layer 与 Canonical 写入路径**完全不变**。
     */
    private val runtimeIntegration: RuntimeIntegrationUseCases? = null,
    errorMapper: ErrorMapper,
) : UseCase(errorMapper) {

    /* ---------------- 意图解析（确定性、可解释、可测试） ---------------- */

    /**
     * 从用户意图原文解析最基本的任务目的（**确定性规则**，无 LLM / 无随机 / 不读时间）。
     *
     * 规则：按 [INTENT_RULES] 的固定优先级顺序扫描关键词；命中即返回；都不命中 → `CUSTOM`。
     * 优先级把"更具体的动作"排在"泛化的继续写"之前（如"重写"优先于"继续"），保证多命中时结果确定。
     */
    fun analyzeIntent(userIntent: String): NovelIntentAnalysis {
        val text = userIntent.trim()
        if (text.isEmpty()) {
            return NovelIntentAnalysis(IntentType.CUSTOM, null, "用户意图为空 → 默认 CUSTOM")
        }
        INTENT_RULES.forEach { (intent, keywords) ->
            val hit = keywords.firstOrNull { text.contains(it) }
            if (hit != null) {
                return NovelIntentAnalysis(
                    intentType = intent,
                    matchedKeyword = hit,
                    reason = "命中关键词「$hit」→ ${intent.name}（确定性规则，无 LLM）",
                )
            }
        }
        return NovelIntentAnalysis(IntentType.CUSTOM, null, "未识别到明确任务目的 → CUSTOM")
    }

    /* ---------------- 主流程 ---------------- */

    /**
     * 执行一次 Novel Agent 运行（同步、有限步、可追踪）。
     *
     * 不抛业务异常：一切失败都表达为稳定的 [NovelAgentResult]（含类型化 [NovelAgentFailure]），
     * 并已把会话收敛到既有语义的状态（失败 → `FAILED`；等待人工 → `PAUSED`；完成 → `COMPLETED`）。
     */
    fun run(request: NovelAgentRequest): NovelAgentResult {
        val intent = analyzeIntent(request.userIntent)
        val state = RunState()
        var session: AgentSession? = null
        var selectedSkill: Skill? = null
        var builtPack: ContextPack? = null
        var runtimeSession: RuntimeSession? = null
        try {
            val project = requireProject(request)
            session = resolveSession(request, project)
            val sessionId = session.sessionId
            // I1 · Runtime seam（默认关闭）：opt-in 时只建立会话绑定，不介入任何相位
            runtimeSession = openRuntimeSessionIfRequested(request, sessionId)

            step(sessionId, OrchestrationPhase.INTENT, NovelAgentErrorCodes.EXECUTION_FAILED, state, "识别用户意图") { intent }

            val skill = step(sessionId, OrchestrationPhase.SKILL, NovelAgentErrorCodes.SKILL_NOT_FOUND, state, "选择 Skill") {
                selectSkill(intent, request)
            }
            selectedSkill = skill

            val pack = step(sessionId, OrchestrationPhase.CONTEXT, NovelAgentErrorCodes.CONTEXT_BUILD_FAILED, state, "构建任务 Context") {
                buildContext(request, intent, sessionId, state)
            }
            builtPack = pack

            val outcome = step(sessionId, OrchestrationPhase.EXECUTE, NovelAgentErrorCodes.EXECUTION_FAILED, state, "执行 Skill") { activityId ->
                executeSkill(skill, request, project, intent, activityId, state)
            }

            // 规划 / 分析类任务不产生正文变更：直接完成（不改 Canonical）
            when (outcome) {
                is SkillOutcome.Plan -> {
                    state.planChapterId = outcome.chapterId
                    sessions.complete(sessionId)
                    return result(
                        NovelAgentOutcome.COMPLETED, request, intent, sessionId, skill, pack, state,
                        summary = "已完成 ${skill.skillId.value}：章节 ${outcome.chapterId.value} 规划就绪" +
                            "（ChapterPlan ${outcome.chapterPlanId.value}）；未产生正文变更，未写入 Canonical",
                    )
                }

                is SkillOutcome.Analysis -> {
                    sessions.complete(sessionId)
                    return result(
                        NovelAgentOutcome.COMPLETED, request, intent, sessionId, skill, pack, state,
                        summary = "已完成 ${skill.skillId.value}：评审 passed=${outcome.passed}，" +
                            "问题 ${outcome.issueCount} 项（其中 ERROR ${outcome.errorCount}）；只读评审，未写入 Canonical",
                    )
                }

                is SkillOutcome.Draft -> Unit
            }

            val artifact = step(sessionId, OrchestrationPhase.REVIEW, NovelAgentErrorCodes.ARTIFACT_FAILED, state, "校验并生成变更提案") { activityId ->
                review(outcome, request, sessionId, activityId, state)
            }

            when (artifact.status) {
                ChangeArtifactStatus.INVALID -> {
                    sessions.fail(sessionId)
                    return result(
                        NovelAgentOutcome.FAILED, request, intent, sessionId, skill, pack, state,
                        summary = "Working Draft 未通过 Validation（存在 ERROR），已生成不可提交的 Artifact",
                        failure = NovelAgentFailure(
                            NovelAgentErrorCodes.ARTIFACT_INVALID,
                            "Artifact ${artifact.artifactId.value} 状态 INVALID：${artifact.change.validation.issues.joinToString("; ") { "${it.severity}:${it.field}" }}",
                        ),
                    )
                }

                ChangeArtifactStatus.NO_CHANGES -> {
                    sessions.complete(sessionId)
                    return result(
                        NovelAgentOutcome.COMPLETED, request, intent, sessionId, skill, pack, state,
                        summary = "Working Draft 与基线无差异（NO_CHANGES），无需提交",
                    )
                }

                ChangeArtifactStatus.READY -> Unit
            }

            val decision = step(sessionId, OrchestrationPhase.GATE, NovelAgentErrorCodes.ACTION_DENIED, state, "检查 Canonical 写入权限") {
                evaluateCanonicalWrite(artifact)
            }
            when (decision) {
                is ActionDecision.Denied -> {
                    sessions.fail(sessionId)
                    return result(
                        NovelAgentOutcome.FAILED, request, intent, sessionId, skill, pack, state,
                        summary = "ActionPolicy 拒绝写入 Canonical：${decision.reason}",
                        failure = NovelAgentFailure(NovelAgentErrorCodes.ACTION_DENIED, decision.reason),
                    )
                }

                is ActionDecision.NeedsHuman -> {
                    // §15：Ready ≠ 自动 Commit。进入人工确认：不自动提交、不创建 Gate（Gate 属 I2 / 既有 P12 语义）
                    sessions.pause(sessionId)
                    return result(
                        NovelAgentOutcome.WAITING_HUMAN, request, intent, sessionId, skill, pack, state,
                        summary = "变更已就绪，等待人工确认（${decision.reason}）；批准后调用 resume(" +
                            "artifactId=${artifact.artifactId.value}) 提交",
                    )
                }

                is ActionDecision.Allowed -> Unit
            }

            val committed = step(sessionId, OrchestrationPhase.COMMIT, NovelAgentErrorCodes.COMMIT_FAILED, state, "正式提交 Canonical") {
                commits.commit(request.projectId, artifact.artifactId, approval = null)
            }
            sessions.complete(sessionId)
            return result(
                NovelAgentOutcome.COMPLETED, request, intent, sessionId, skill, pack, state,
                summary = "已提交 Canonical（commit=${committed.commitId.value}）；Artifact 已冻结",
                commitId = committed.commitId,
                historyId = committed.historyEntry.historyId,
            )
        } catch (e: RunAbort) {
            session?.let { settleInterrupted(it.sessionId) }
            return result(
                outcome = outcomeOf(e.code), request = request, intent = intent,
                sessionId = session?.sessionId, skill = selectedSkill, pack = builtPack, state = state,
                summary = "运行中止（${e.code}）：${e.detail}",
                failure = NovelAgentFailure(e.code, e.detail),
            )
        } catch (t: Throwable) {
            val mapped = errorMapper.map(t)
            session?.let { settleInterrupted(it.sessionId) }
            return result(
                outcome = NovelAgentOutcome.FAILED, request = request, intent = intent,
                sessionId = session?.sessionId, skill = selectedSkill, pack = builtPack, state = state,
                summary = "运行失败：${mapped.error}",
                failure = NovelAgentFailure(NovelAgentErrorCodes.EXECUTION_FAILED, mapped.error.toString()),
            )
        } finally {
            // I1 · Runtime seam：无论成败都收敛本次运行时会话（未 opt-in 时为 null，行为不变）
            closeRuntimeSession(runtimeSession, session?.sessionId)
        }
    }

    /**
     * I1 · Runtime seam：仅当请求显式 [NovelAgentRequest.runtimeBacked] 且已装配 Runtime 时，
     * 为该会话建立一次外部运行时会话绑定。
     *
     * **失败不阻断编排**：外部 Runtime 不可用只意味着"本次没有运行时绑定"，不影响既有七相位
     * （I1 只是 seam，不把外部运行时变成 NovelAgent 的硬依赖）。
     */
    private fun openRuntimeSessionIfRequested(request: NovelAgentRequest, sessionId: AgentSessionId): RuntimeSession? {
        if (!request.runtimeBacked) return null
        val runtime = runtimeIntegration ?: return null
        return runCatching { runtime.createRuntimeSession(sessionId) }.getOrNull()
    }

    /** I1 · Runtime seam：收敛绑定会话（best-effort，不抛异常；不泄漏对端会话）。 */
    private fun closeRuntimeSession(runtimeSession: RuntimeSession?, sessionId: AgentSessionId?) {
        if (runtimeSession == null || sessionId == null) return
        runCatching { runtimeIntegration?.closeSession(sessionId) }
    }

    /**
     * 人工批准后的续跑：**Commit**（§16 `resume → Commit`）。
     *
     * 不重跑前面的编排（正文与 Artifact 已就绪）；只经 I2 复核权限并由 [CommitUseCases] 执行唯一 Canonical 写入。
     * 批准凭据必须是**既有** Workflow Human Gate 的 `RESOLVED + APPROVED` 记录（由 I10 复核）。
     */
    fun resume(
        request: NovelAgentRequest,
        artifactId: ChangeArtifactId,
        approval: CommitApproval,
    ): NovelAgentResult {
        val intent = analyzeIntent(request.userIntent)
        val state = RunState()
        var session: AgentSession? = null
        try {
            val project = requireProject(request)
            session = resolveSession(request, project)
            val sessionId = session.sessionId

            val artifact = artifactOf(request, artifactId, state)
            val decision = step(sessionId, OrchestrationPhase.GATE, NovelAgentErrorCodes.ACTION_DENIED, state, "复核 Canonical 写入权限") {
                evaluateCanonicalWrite(artifact)
            }
            if (decision is ActionDecision.Denied) {
                sessions.fail(sessionId)
                return result(
                    NovelAgentOutcome.FAILED, request, intent, sessionId, skill = null, pack = null, state = state,
                    summary = "ActionPolicy 拒绝写入 Canonical：${decision.reason}",
                    failure = NovelAgentFailure(NovelAgentErrorCodes.ACTION_DENIED, decision.reason),
                )
            }

            val committed = step(sessionId, OrchestrationPhase.COMMIT, NovelAgentErrorCodes.COMMIT_FAILED, state, "正式提交 Canonical") {
                commits.commit(request.projectId, artifactId, approval)
            }
            sessions.complete(sessionId)
            return result(
                NovelAgentOutcome.COMPLETED, request, intent, sessionId, skill = null, pack = null, state = state,
                summary = "人工批准后已提交 Canonical（commit=${committed.commitId.value}，gate=${approval.gateId.value}）",
                commitId = committed.commitId,
                historyId = committed.historyEntry.historyId,
                approvalGateId = approval.gateId,
            )
        } catch (e: RunAbort) {
            session?.let { settleInterrupted(it.sessionId) }
            return result(
                outcome = outcomeOf(e.code), request = request, intent = intent,
                sessionId = session?.sessionId, skill = null, pack = null, state = state,
                summary = "续跑中止（${e.code}）：${e.detail}",
                failure = NovelAgentFailure(e.code, e.detail),
            )
        } catch (t: Throwable) {
            val mapped = errorMapper.map(t)
            session?.let { settleInterrupted(it.sessionId) }
            return result(
                outcome = NovelAgentOutcome.FAILED, request = request, intent = intent,
                sessionId = session?.sessionId, skill = null, pack = null, state = state,
                summary = "续跑失败：${mapped.error}",
                failure = NovelAgentFailure(NovelAgentErrorCodes.EXECUTION_FAILED, mapped.error.toString()),
            )
        }
    }

    /**
     * 人工拒绝后的终止（§16 `REJECTED`）：经 I8 既有能力丢弃该 Artifact 对应的 Working Draft（临时成果不再需要），
     * 并取消会话（既有语义 `PAUSED → CANCELLED`）。返回 [NovelAgentOutcome.CANCELLED]。
     */
    fun reject(
        request: NovelAgentRequest,
        artifactId: ChangeArtifactId,
        reason: String = "人工决议拒绝（REJECTED）",
    ): NovelAgentResult {
        val intent = analyzeIntent(request.userIntent)
        val state = RunState()
        var session: AgentSession? = null
        try {
            val project = requireProject(request)
            session = resolveSession(request, project)
            val artifact = artifactOf(request, artifactId, state)
            workingDrafts.discard(artifact.workingDraftId)
            sessions.cancel(session.sessionId)
            return result(
                NovelAgentOutcome.CANCELLED, request, intent, session.sessionId, skill = null, pack = null, state = state,
                summary = "已拒绝并终止：$reason；Working Draft 已丢弃（DISCARDED），Canonical 未变更",
                failure = NovelAgentFailure(NovelAgentErrorCodes.SESSION_CANCELLED, reason),
            )
        } catch (e: RunAbort) {
            return result(
                NovelAgentOutcome.FAILED, request, intent, session?.sessionId, skill = null, pack = null, state = state,
                summary = "拒绝流程中止（${e.code}）：${e.detail}",
                failure = NovelAgentFailure(e.code, e.detail),
            )
        }
    }

    /* ---------------- 各相位实现 ---------------- */

    private fun requireProject(request: NovelAgentRequest): Project {
        val project = try {
            projects.projectOf(request.projectId)
        } catch (e: ApplicationException) {
            throw RunAbort(NovelAgentErrorCodes.PROJECT_NOT_FOUND, "Project 不存在: ${request.projectId.value}", e)
        } ?: throw RunAbort(NovelAgentErrorCodes.PROJECT_NOT_FOUND, "Project 不存在: ${request.projectId.value}")
        // 作用域一致性守卫（不复制运行态，只校验调用方声明与 Project 一致）
        request.activeVariantId?.let { declared ->
            if (project.state.activeVariantId != declared) {
                throw RunAbort(
                    NovelAgentErrorCodes.SCOPE_MISMATCH,
                    "请求声明的 variantId=${declared.value} 与 Project 运行态" +
                        "（variantId=${project.state.activeVariantId?.value ?: "ORIGINAL"}）不一致",
                )
            }
        }
        return project
    }

    /** 解析 / 建立会话（复用 I3；不改变其状态语义，只做合法转换）。 */
    private fun resolveSession(request: NovelAgentRequest, project: Project): AgentSession {
        val existingId = request.sessionId
        if (existingId == null) {
            val created = try {
                sessions.startSession(project.novelId, taskId = request.taskId)
            } catch (e: ApplicationException) {
                throw RunAbort(NovelAgentErrorCodes.SESSION_START_FAILED, e.message ?: "会话创建失败", e)
            }
            return sessions.activate(created.sessionId)
        }
        val session = try {
            sessions.sessionOf(existingId)
        } catch (e: ApplicationException) {
            throw RunAbort(NovelAgentErrorCodes.SESSION_NOT_FOUND, "AgentSession 不存在: ${existingId.value}", e)
        }
        // Project 隔离：越界统一表现为"不存在"，不泄漏其他 Project 的存在性
        if (session.projectId != request.projectId) {
            throw RunAbort(NovelAgentErrorCodes.SESSION_NOT_FOUND, "AgentSession 不存在: ${existingId.value}")
        }
        return when (session.status) {
            AgentSessionStatus.CANCELLED ->
                throw RunAbort(NovelAgentErrorCodes.SESSION_CANCELLED, "AgentSession 已取消: ${existingId.value}")

            AgentSessionStatus.COMPLETED, AgentSessionStatus.FAILED ->
                throw RunAbort(
                    NovelAgentErrorCodes.SESSION_STATE_INVALID,
                    "AgentSession 处于终态（${session.status}），不可再运行: ${existingId.value}",
                )

            AgentSessionStatus.ACTIVE -> session
            AgentSessionStatus.CREATED, AgentSessionStatus.PAUSED -> sessions.activate(session.sessionId)
        }
    }

    /** Skill 选择：Registry 匹配（确定性候选集）→ 指定 Skill 或确定性偏好序 → Skill。 */
    private fun selectSkill(intent: NovelIntentAnalysis, request: NovelAgentRequest): Skill {
        val matches = skillRegistry.match(intent.intentType)
        if (matches.isEmpty()) {
            throw RunAbort(NovelAgentErrorCodes.SKILL_NOT_FOUND, "没有可处理 ${intent.intentType} 的 Skill（enabled 且声明该 purpose）")
        }
        val preferred = request.preferredSkillId
        val chosenId = if (preferred != null) {
            matches.firstOrNull { it.skillId == preferred }?.skillId
                ?: throw RunAbort(
                    NovelAgentErrorCodes.SKILL_NOT_MATCHED,
                    "Skill ${preferred.value} 未声明处理 ${intent.intentType} 或已 disabled",
                )
        } else {
            val preference = INTENT_SKILL_PREFERENCE[intent.intentType].orEmpty()
            preference.firstNotNullOfOrNull { id -> matches.firstOrNull { it.skillId == id }?.skillId }
                ?: matches.first().skillId
        }
        return skillRegistry.get(chosenId)
            ?: throw RunAbort(NovelAgentErrorCodes.SKILL_NOT_FOUND, "Skill 未注册: ${chosenId.value}")
    }

    /** Context 构建（**必须复用 I6**；不在本层拼接 Chapter / Draft / Novel / Vocabulary / StoryFoundation）。 */
    private fun buildContext(
        request: NovelAgentRequest,
        intent: NovelIntentAnalysis,
        sessionId: AgentSessionId,
        state: RunState,
    ): ContextPack {
        val pack = contextEngine.build(
            ContextRequest(
                projectId = request.projectId,
                purpose = intent.intentType,
                sessionId = sessionId,
                taskId = request.taskId,
                focusChapterId = request.activeChapterId,
                budget = request.budget,
            ),
        )
        state.contextPackId = pack.packId
        state.contextPackVersion = pack.packVersion
        return pack
    }

    /** 按 Skill 绑定执行（既有五 Agent + 既有只读 Product Tool；不写 Canonical）。 */
    private fun executeSkill(
        skill: Skill,
        request: NovelAgentRequest,
        project: Project,
        intent: NovelIntentAnalysis,
        activityId: ActivityId,
        state: RunState,
    ): SkillOutcome = when (skill.skillId) {
        CoreSkills.WRITING.skillId -> generateNewContent(skill, request, project, intent, activityId, state)
        CoreSkills.REWRITE_REVISION.skillId -> reviseExistingContent(skill, request, activityId, state)
        CoreSkills.STORY_PLANNING.skillId -> planChapter(request, project, intent)
        CoreSkills.ANALYSIS_CRITIQUE.skillId -> analyzeChapter(skill, request, activityId, state)
        else -> throw RunAbort(
            NovelAgentErrorCodes.SKILL_NOT_EXECUTABLE,
            "Skill ${skill.skillId.value} 在本阶段没有执行绑定" +
                "（世界模型维护属既有 Knowledge Update 链：需 CONFIRMED Final Draft + 人工确认）",
        )
    }

    /** 写作（CONTINUE / EXPAND / CUSTOM）：既有 Planner + Writer 产出正文 → Working Draft；不落 Canonical。 */
    private fun generateNewContent(
        skill: Skill,
        request: NovelAgentRequest,
        project: Project,
        intent: NovelIntentAnalysis,
        activityId: ActivityId,
        state: RunState,
    ): SkillOutcome {
        val chapter = requireFocusChapter(request)
        val baseDraftId = latestCanonicalDraftId(skill, request, chapter.chapterId, activityId, state)
        val context = planningContexts.assemble(writingRequest(request, project, chapter, intent))
        // 既有 Planner → 既有 Writer（复用其 Prompt / Parser 语义）；plan.chapterId 绑定到焦点章节（纯数据，不落库、不建章）
        val plan = planner.plan(context, emptyList()).copy(
            chapterId = chapter.chapterId,
            novelId = chapter.novelId,
            variantId = chapter.variantId,
            scope = chapter.scope,
        )
        val draft = writer.write(context, plan)
        return SkillOutcome.Draft(content = draft.content, baseDraftId = baseDraftId, chapterId = chapter.chapterId)
    }

    /** 改写（REWRITE）：既有 Critique + Revision 产出修订正文 → Working Draft；不落 Canonical。 */
    private fun reviseExistingContent(
        skill: Skill,
        request: NovelAgentRequest,
        activityId: ActivityId,
        state: RunState,
    ): SkillOutcome {
        val chapter = requireFocusChapter(request)
        val baseDraftId = latestCanonicalDraftId(skill, request, chapter.chapterId, activityId, state)
            ?: throw RunAbort(NovelAgentErrorCodes.BASE_DRAFT_NOT_FOUND, "改写需要章节已有 Canonical 正文：${chapter.chapterId.value}")
        val current = reader.draft(baseDraftId)
            ?: throw RunAbort(NovelAgentErrorCodes.BASE_DRAFT_NOT_FOUND, "Canonical Draft 不存在: ${baseDraftId.value}")
        val critique = critic.critique(current)
        val revised = rewriter.revise(current, critique)
        return SkillOutcome.Draft(content = revised.content, baseDraftId = baseDraftId, chapterId = chapter.chapterId)
    }

    /** 规划（PLAN）：既有 Planner 产出 ChapterPlan；**不创建章节、不落库**（I11 不直接改 Canonical）。 */
    private fun planChapter(request: NovelAgentRequest, project: Project, intent: NovelIntentAnalysis): SkillOutcome {
        val chapter = requireFocusChapter(request)
        val context = planningContexts.assemble(writingRequest(request, project, chapter, intent))
        val plan = planner.plan(context)
        return SkillOutcome.Plan(chapterId = chapter.chapterId, chapterPlanId = plan.chapterPlanId)
    }

    /** 分析（ANALYZE）：既有 Critique 只读评审 → 结果引用；不产生 Working Draft、不写 Canonical。 */
    private fun analyzeChapter(
        skill: Skill,
        request: NovelAgentRequest,
        activityId: ActivityId,
        state: RunState,
    ): SkillOutcome {
        val chapter = requireFocusChapter(request)
        val baseDraftId = latestCanonicalDraftId(skill, request, chapter.chapterId, activityId, state)
            ?: throw RunAbort(NovelAgentErrorCodes.BASE_DRAFT_NOT_FOUND, "分析需要章节已有 Canonical 正文：${chapter.chapterId.value}")
        val current = reader.draft(baseDraftId)
            ?: throw RunAbort(NovelAgentErrorCodes.BASE_DRAFT_NOT_FOUND, "Canonical Draft 不存在: ${baseDraftId.value}")
        val critique = critic.critique(current)
        return SkillOutcome.Analysis(
            passed = critique.passed,
            issueCount = critique.issues.size,
            errorCount = critique.issues.count { it.severity == IssueSeverity.ERROR },
        )
    }

    /** Review：Working Draft（I8）→ Validation（I8）→ Diff / Change / Artifact（I9）；Artifact 不可写 Canonical。 */
    private fun review(
        outcome: SkillOutcome,
        request: NovelAgentRequest,
        sessionId: AgentSessionId,
        activityId: ActivityId,
        state: RunState,
    ): ChangeArtifact {
        val draftOutcome = outcome as? SkillOutcome.Draft
            ?: throw RunAbort(NovelAgentErrorCodes.ARTIFACT_FAILED, "该执行结果不产生正文变更，无法生成 Artifact")
        val chapter = requireFocusChapter(request)
        val workingDraft = try {
            workingDrafts.create(
                projectId = request.projectId,
                target = WorkingDraftTarget(chapter.novelId, draftOutcome.chapterId, chapter.variantId),
                content = draftOutcome.content,
                sessionId = sessionId,
                activityId = activityId,
                baseDraftId = draftOutcome.baseDraftId,
            )
        } catch (e: ApplicationException) {
            throw RunAbort(NovelAgentErrorCodes.WORKING_DRAFT_FAILED, e.message ?: "Working Draft 创建失败", e)
        }
        state.workingDraftId = workingDraft.workingDraftId
        workingDrafts.validate(workingDraft.workingDraftId)
        val artifact = try {
            changes.prepare(workingDraft.workingDraftId)
        } catch (e: ApplicationException) {
            throw RunAbort(NovelAgentErrorCodes.ARTIFACT_FAILED, e.message ?: "Artifact 生成失败", e)
        }
        state.artifactId = artifact.artifactId
        state.artifactStatus = artifact.status
        return artifact
    }

    private fun evaluateCanonicalWrite(artifact: ChangeArtifact): ActionDecision = actionPolicy.evaluate(
        AgentAction(
            kind = AgentActionKind.COMMIT_CANONICAL,
            target = "${artifact.change.target.novelId.value}/${artifact.change.target.chapterId.value}",
            note = "Novel Agent 变更提案 ${artifact.artifactId.value}",
        ),
    )

    /* ---------------- 工具 / 章节 / 产物 ---------------- */

    /**
     * 章节当前 Canonical 正文载体（只读）。
     *
     * **只经 I5 Product Tool 读取**（§12）：工具必须在该 Skill 的 `allowedTools` 内，否则拒绝且**不执行**；
     * 调用事实由 I5 落到既有 I4 ToolCallLog。
     *
     * 工具以稳定错误码 `NOT_FOUND` 表达业务失败；此时"章节不存在 / 跨 Project"已被 CONTEXT 阶段的
     * ContextEngine 校验排除（焦点章节必须属于本 Project 的 Novel），因此剩下的唯一可能是"该章节尚无草稿"
     * ⇒ 返回 null（无基线）。其它失败一律类型化拒绝（不吞）。
     */
    private fun latestCanonicalDraftId(
        skill: Skill,
        request: NovelAgentRequest,
        chapterId: ChapterId,
        activityId: ActivityId,
        state: RunState,
    ): DraftId? {
        val toolName = ToolName(ProductToolNames.GET_LATEST_DRAFT)
        if (toolName !in skill.allowedTools) {
            throw RunAbort(
                NovelAgentErrorCodes.TOOL_NOT_ALLOWED,
                "工具 ${toolName.value} 不在 Skill ${skill.skillId.value} 的 allowedTools 内，拒绝执行",
            )
        }
        val result: ToolResult = try {
            productTools.invoke(
                activityId,
                ToolRequest(toolName, buildJsonObject { put("chapterId", chapterId.value) }),
            )
        } catch (e: ApplicationException) {
            throw RunAbort(NovelAgentErrorCodes.TOOL_FAILED, e.message ?: "工具调用失败", e)
        }
        state.toolCallCount += 1
        if (!result.success) {
            val error = result.error.orEmpty()
            return if (error.startsWith(NOT_FOUND_CODE)) null
            else throw RunAbort(NovelAgentErrorCodes.TOOL_FAILED, "工具 ${toolName.value} 失败: $error")
        }
        val view = try {
            toolJson.decodeFromJsonElement(DraftView.serializer(), result.output)
        } catch (t: Throwable) {
            throw RunAbort(NovelAgentErrorCodes.TOOL_FAILED, "工具 ${toolName.value} 输出无法解析: ${t.message}", t)
        }
        return view.draftId
    }

    private fun requireFocusChapter(request: NovelAgentRequest): Chapter {
        val chapterId = request.activeChapterId
            ?: throw RunAbort(NovelAgentErrorCodes.MISSING_FOCUS_CHAPTER, "该任务类型需要 activeChapterId（I11 不自行创建章节）")
        return chapters.findById(chapterId)
            ?: throw RunAbort(NovelAgentErrorCodes.MISSING_FOCUS_CHAPTER, "Chapter 不存在: ${chapterId.value}")
    }

    private fun writingRequest(
        request: NovelAgentRequest,
        project: Project,
        chapter: Chapter,
        intent: NovelIntentAnalysis,
    ): UserWritingRequest = UserWritingRequest(
        requestId = RequestId(nextId()),
        intentType = intent.intentType,
        target = TargetRef(TargetKind.CHAPTER, TargetRefId(chapter.chapterId.value)),
        planningScope = PlanningScope.CHAPTER,
        rawText = request.userIntent,
        baseNovelId = BaseNovelId(project.novelId.value),
        variantId = chapter.variantId,
        scope = chapter.scope,
    )

    private fun artifactOf(request: NovelAgentRequest, artifactId: ChangeArtifactId, state: RunState): ChangeArtifact {
        val artifact = try {
            changes.get(artifactId)
        } catch (e: ApplicationException) {
            throw RunAbort(NovelAgentErrorCodes.ARTIFACT_NOT_FOUND, "Change Artifact 不存在: ${artifactId.value}", e)
        }
        if (artifact.projectId != request.projectId) {
            throw RunAbort(NovelAgentErrorCodes.ARTIFACT_NOT_FOUND, "Change Artifact 不存在: ${artifactId.value}")
        }
        state.artifactId = artifact.artifactId
        state.artifactStatus = artifact.status
        state.workingDraftId = artifact.workingDraftId
        return artifact
    }

    /* ---------------- 编排相位 / 状态记录 ---------------- */

    /** 单相位执行：记录既有 I4 Activity（开始 → 完成 / 失败），并把失败统一为稳定错误码。 */
    private fun <T> step(
        sessionId: AgentSessionId,
        phase: OrchestrationPhase,
        failureCode: String,
        state: RunState,
        summary: String,
        block: (ActivityId) -> T,
    ): T {
        val activity = activities.start(sessionId, phase.name, summary)
        state.activityIds += activity.activityId
        return try {
            val value = block(activity.activityId)
            activities.complete(activity.activityId, summary)
            value
        } catch (e: RunAbort) {
            activities.fail(activity.activityId, e.detail)
            throw e
        } catch (e: ApplicationException) {
            activities.fail(activity.activityId, e.message ?: e.error.toString())
            throw RunAbort(failureCode, e.message ?: e.error.toString(), e)
        } catch (t: Throwable) {
            val mapped = errorMapper.map(t)
            activities.fail(activity.activityId, mapped.error.toString())
            throw RunAbort(failureCode, mapped.error.toString(), mapped)
        }
    }

    /** 中断收敛：会话仍是活动态时置 `FAILED`（终态 / 已取消会话不改）。 */
    private fun settleInterrupted(sessionId: AgentSessionId) {
        val status = try {
            sessions.sessionOf(sessionId).status
        } catch (e: ApplicationException) {
            return
        }
        if (status == AgentSessionStatus.ACTIVE || status == AgentSessionStatus.CREATED) {
            sessions.fail(sessionId)
        }
    }

    private fun outcomeOf(code: String): NovelAgentOutcome =
        if (code == NovelAgentErrorCodes.SESSION_CANCELLED) NovelAgentOutcome.CANCELLED else NovelAgentOutcome.FAILED

    private fun result(
        outcome: NovelAgentOutcome,
        request: NovelAgentRequest,
        intent: NovelIntentAnalysis,
        sessionId: AgentSessionId?,
        skill: Skill?,
        pack: ContextPack?,
        state: RunState,
        summary: String,
        failure: NovelAgentFailure? = null,
        commitId: CommitId? = null,
        historyId: CommitHistoryId? = null,
        approvalGateId: WorkflowHumanGateId? = null,
    ): NovelAgentResult = NovelAgentResult(
        outcome = outcome,
        projectId = request.projectId,
        sessionId = sessionId,
        intent = intent,
        skillId = skill?.skillId,
        contextPackId = state.contextPackId ?: pack?.packId,
        contextPackVersion = state.contextPackVersion ?: pack?.packVersion,
        activityIds = state.activityIds.toList(),
        toolCallCount = state.toolCallCount,
        planChapterId = state.planChapterId,
        workingDraftId = state.workingDraftId,
        artifactId = state.artifactId,
        artifactStatus = state.artifactStatus,
        approvalGateId = approvalGateId,
        commitId = commitId,
        historyId = historyId,
        summary = summary,
        failure = failure,
    )

    /** run 过程中的可追踪状态（仅编排事实，不含业务数据副本）。 */
    private class RunState {
        val activityIds: MutableList<ActivityId> = mutableListOf()
        var toolCallCount: Int = 0
        var contextPackId: String? = null
        var contextPackVersion: Long? = null
        var planChapterId: ChapterId? = null
        var workingDraftId: WorkingDraftId? = null
        var artifactId: ChangeArtifactId? = null
        var artifactStatus: ChangeArtifactStatus? = null
    }

    /** 编排中止（内部信号；统一转成稳定 [NovelAgentFailure]，不向调用方抛业务异常）。 */
    private class RunAbort(val code: String, val detail: String, cause: Throwable? = null) :
        RuntimeException(detail, cause)

    /** 固定编排相位（**有限、明确、可追踪**；作为既有 Activity 的 kind，不新建 Step 模型）。 */
    private enum class OrchestrationPhase { INTENT, SKILL, CONTEXT, EXECUTE, REVIEW, GATE, COMMIT }

    /** Skill 执行结果（编排层内部派发载体；不是领域模型）。 */
    private sealed interface SkillOutcome {
        /** 产出正文（后续进 Working Draft / Validation / Diff / Artifact）。 */
        data class Draft(val content: String, val baseDraftId: DraftId?, val chapterId: ChapterId) : SkillOutcome

        /** 产出章节规划引用（无正文变更）。 */
        data class Plan(val chapterId: ChapterId, val chapterPlanId: ChapterPlanId) : SkillOutcome

        /** 只读分析结果（无正文变更）。 */
        data class Analysis(val passed: Boolean, val issueCount: Int, val errorCount: Int) : SkillOutcome
    }

    private companion object {

        /** 工具业务失败稳定码前缀（I5 契约：`NOT_FOUND: ...`）。 */
        const val NOT_FOUND_CODE: String = "NOT_FOUND"

        private val toolJson: Json = Json { ignoreUnknownKeys = true }

        /**
         * 确定性意图规则（按优先级顺序扫描；更具体的动作优先于泛化的"继续写"）。
         * 命中即返回；都不命中 → `CUSTOM`。
         */
        private val INTENT_RULES: List<Pair<IntentType, List<String>>> = listOf(
            IntentType.REWRITE to listOf("重写", "改写", "润色", "修订", "修改"),
            IntentType.EXPAND to listOf("扩写", "扩展", "展开", "补写"),
            IntentType.PLAN to listOf("规划", "大纲", "分卷", "构思", "计划"),
            IntentType.ANALYZE to listOf("分析", "评审", "评价", "检查", "诊断"),
            IntentType.CONTINUE to listOf("继续", "接着", "续写", "往下写", "下一章"),
        )

        /**
         * 确定性 Skill 偏好序（**不是** if-else 路由：候选集始终来自 [SkillRegistry.match]，
         * 本表只在多命中时给出稳定、可解释的先后；表内无命中 → 取匹配结果中 `skillId` 最小者）。
         */
        private val INTENT_SKILL_PREFERENCE: Map<IntentType, List<SkillId>> = mapOf(
            IntentType.CONTINUE to listOf(CoreSkills.WRITING.skillId, CoreSkills.KNOWLEDGE_WORLD_MODEL.skillId),
            IntentType.REWRITE to listOf(CoreSkills.REWRITE_REVISION.skillId),
            IntentType.EXPAND to listOf(CoreSkills.REWRITE_REVISION.skillId, CoreSkills.WRITING.skillId),
            IntentType.PLAN to listOf(CoreSkills.STORY_PLANNING.skillId),
            IntentType.ANALYZE to listOf(CoreSkills.ANALYSIS_CRITIQUE.skillId, CoreSkills.KNOWLEDGE_WORLD_MODEL.skillId),
            IntentType.CUSTOM to listOf(
                CoreSkills.WRITING.skillId,
                CoreSkills.STORY_PLANNING.skillId,
                CoreSkills.REWRITE_REVISION.skillId,
                CoreSkills.ANALYSIS_CRITIQUE.skillId,
            ),
        )
    }
}