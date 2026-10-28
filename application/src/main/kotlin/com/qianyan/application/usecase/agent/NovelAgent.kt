package com.qianyan.application.usecase.agent

import com.qianyan.agent.agents.CoreSkills
import com.qianyan.agent.agents.SkillRegistry
import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.application.error.ErrorMapper
import com.qianyan.application.usecase.UseCase
import com.qianyan.application.usecase.action.ActionPolicyUseCases
import com.qianyan.application.usecase.canonical.CanonicalReadUseCases
import com.qianyan.application.usecase.change.ChangeUseCases
import com.qianyan.application.usecase.chapter.ChapterUseCases
import com.qianyan.application.usecase.commit.CommitUseCases
import com.qianyan.application.usecase.context.ContextEngineUseCases
import com.qianyan.application.usecase.context.ContextPackStore
import com.qianyan.application.usecase.draft.WorkingDraftUseCases
import com.qianyan.application.usecase.log.ActivityUseCases
import com.qianyan.application.usecase.log.ToolCallLogUseCases
import com.qianyan.application.usecase.project.ProjectUseCases
import com.qianyan.application.usecase.session.AgentSessionUseCases
import com.qianyan.application.usecase.tool.ProductToolNames
import com.qianyan.application.usecase.tool.ProductToolService
import com.qianyan.application.usecase.tool.DraftView
import com.qianyan.application.usecase.workflow.ChapterWorkflowGateway
import com.qianyan.application.usecase.writing.WriterAgent
import com.qianyan.application.usecase.writing.WriterUseCases
import com.qianyan.application.usecase.writing.WritingSnapshot
import com.qianyan.application.usecase.writing.consistency.CharacterConsistencyAgent
import com.qianyan.application.usecase.writing.consistency.CharacterConsistencyUseCase
import com.qianyan.application.usecase.writing.critique.CritiqueAgent
import com.qianyan.application.usecase.writing.planning.PlannerAgent
import com.qianyan.application.usecase.writing.planning.ContinuationResolver
import com.qianyan.application.usecase.writing.planning.PlanningContextAssembly
import com.qianyan.application.usecase.writing.planning.ResolvedContinuation
import com.qianyan.application.usecase.writing.revision.RevisionAgent
import com.qianyan.model.ActivityId
import com.qianyan.model.AgentId
import com.qianyan.model.AgentSessionId
import com.qianyan.model.BaseNovelId
import com.qianyan.model.ChapterId
import com.qianyan.model.ChapterPlanId
import com.qianyan.model.DraftId
import com.qianyan.model.IntentType
import com.qianyan.model.PlanningScope
import com.qianyan.model.ProjectId
import com.qianyan.model.RequestId
import com.qianyan.model.action.ActionDecision
import com.qianyan.model.action.AgentAction
import com.qianyan.model.action.AgentActionKind
import com.qianyan.model.agent.ToolName
import com.qianyan.model.change.ChangeArtifact
import com.qianyan.model.change.ChangeArtifactId
import com.qianyan.model.change.ChangeArtifactStatus
import com.qianyan.model.characterconsistency.CharacterConsistencyResult
import com.qianyan.model.characterconsistency.CharacterConsistencyScope
import com.qianyan.model.commit.CommitApproval
import com.qianyan.model.commit.CommitId
import com.qianyan.model.commit.CommitHistoryId
import com.qianyan.model.context.ContextPack
import com.qianyan.model.context.ContextRequest
import com.qianyan.model.context.TargetKind
import com.qianyan.model.context.TargetRef
import com.qianyan.model.context.TargetRefId
import com.qianyan.model.context.UserWritingRequest
import com.qianyan.model.novelagent.NovelAgentAnalysisSummary
import com.qianyan.model.novelagent.NovelAgentApprovalState
import com.qianyan.model.novelagent.NovelAgentContinuationReasons
import com.qianyan.model.novelagent.NovelAgentErrorCodes
import com.qianyan.model.novelagent.NovelAgentFailure
import com.qianyan.model.novelagent.NovelAgentOutcome
import com.qianyan.model.novelagent.NovelAgentPostRevisionFailureCodes
import com.qianyan.model.novelagent.NovelAgentRequest
import com.qianyan.model.novelagent.NovelAgentResult
import com.qianyan.model.novelagent.NovelIntentAnalysis
import com.qianyan.model.project.Project
import com.qianyan.model.session.AgentSession
import com.qianyan.model.session.AgentSessionStatus
import com.qianyan.model.skill.Skill
import com.qianyan.model.skill.SkillId
import com.qianyan.model.spec.IssueSeverity
import com.qianyan.model.spec.ValidationResult
import com.qianyan.model.tool.ToolRequest
import com.qianyan.model.tool.ToolResult
import com.qianyan.model.workingdraft.WorkingDraftId
import com.qianyan.model.workingdraft.WorkingDraftTarget
import com.qianyan.model.workflow.HumanDecision
import com.qianyan.model.workflow.WorkflowHumanGateId
import com.qianyan.model.story.Chapter
import com.qianyan.model.story.ContinuationReference
import com.qianyan.model.writing.DraftStatus
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
class NovelAgent internal constructor(
    private val projects: ProjectUseCases,
    private val chapters: ChapterUseCases,
    private val sessions: AgentSessionUseCases,
    private val activities: ActivityUseCases,
    /** T-2C-2：执行期 observed tool 校验的事实源（按 EXECUTE Activity 读取工具名）。 */
    private val toolCallLogs: ToolCallLogUseCases,
    private val skillRegistry: SkillRegistry,
    private val contextEngine: ContextEngineUseCases,
    /** A2：任务实际 Pack 的发布点；null = 未装配（不影响任务执行，只是 Inspector 无快照）。 */
    private val contextPackStore: ContextPackStore? = null,
    private val productTools: ProductToolService,
    private val workingDrafts: WorkingDraftUseCases,
    private val changes: ChangeUseCases,
    private val commits: CommitUseCases,
    private val actionPolicy: ActionPolicyUseCases,
    private val planner: PlannerAgent,
    private val writer: WriterAgent,
    private val critic: CritiqueAgent,
    private val rewriter: RevisionAgent,
    private val characterConsistency: CharacterConsistencyUseCase,
    private val characterConsistencyAgent: CharacterConsistencyAgent,
    private val planningContexts: PlanningContextAssembly,
    /** O-42：闸紧邻前章 Canonical 来源（复用 P12.1.3 ContinuationResolver 校验契约）。 */
    private val continuationResolver: ContinuationResolver,
    /** O-42：读取前章已提交 Canonical carrier（只读；不新增正文读取路径）。 */
    private val canonicalRead: CanonicalReadUseCases,
    private val reader: WriterUseCases,
    /**
     * P0-3B D2 · 正式审批桥接 seam（可为 null = 未装配；此时正文候选以 UNBRIDGED 显式呈现，绝不伪装待审批）。
     *
     * **只听用户层 seam**（[ChapterWorkflowGateway]），不接触 WorkflowOrchestrator / Gate 内部；
     * 候选接入正式审批只复用既有 `startChapter` / `proposeRewrite`，批准 / 拒绝 / 提交仍由正式链路负责（D1）。
     */
    private val workflowGateway: ChapterWorkflowGateway? = null,
    errorMapper: ErrorMapper,
) : UseCase(errorMapper) {

    /** Phase 6：Skill 契约字段的生产读取与执行前一致性校验。 */
    private val skillExecutionGuard = SkillExecutionGuard(productTools, actionPolicy, contextEngine)

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
        try {
            val project = requireProject(request)
            session = resolveSession(request, project)
            val sessionId = session.sessionId

            step(sessionId, OrchestrationPhase.INTENT, NovelAgentErrorCodes.EXECUTION_FAILED, state, "识别用户意图") { intent }

            val skill = step(sessionId, OrchestrationPhase.SKILL, NovelAgentErrorCodes.SKILL_NOT_FOUND, state, "选择 Skill") {
                selectSkill(intent, request)
            }
            selectedSkill = skill

            // Phase 4B.1 / J-3：章节 Variant 必须与 Project active Variant 一致；
            // 不一致时在 Context Engine 构建**之前** fail-fast（该分支 build 调用次数 = 0）。
            requireVariantScope(request, project)

            // 只有真正消费 ContextPack 的 Skill 才构建/发布 Snapshot；
            // 改写/分析当前不消费 Pack，禁止生成无人消费的 Snapshot（Phase 4 审计发现）。
            val pack = if (skillConsumesContext(skill.skillId)) {
                step(sessionId, OrchestrationPhase.CONTEXT, NovelAgentErrorCodes.CONTEXT_BUILD_FAILED, state, "构建任务 Context") {
                    buildContext(request, intent, sessionId, state)
                }
            } else {
                null
            }
            builtPack = pack

            var outcome = step(sessionId, OrchestrationPhase.EXECUTE, NovelAgentErrorCodes.EXECUTION_FAILED, state, "执行 Skill") { activityId ->
                executeSkill(skill, request, project, intent, activityId, pack, state)
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
                    // D-36：初次分析结论显式入结果；PASS 直接结束，ERROR 触发唯一一次 rewrite-revision 续步。
                    state.analysis = NovelAgentAnalysisSummary(
                        passed = outcome.passed,
                        issueCount = outcome.issueCount,
                        errorCount = outcome.errorCount,
                    )
                    if (outcome.errorCount == 0) {
                        sessions.complete(sessionId)
                        return result(
                            NovelAgentOutcome.COMPLETED, request, intent, sessionId, skill, pack, state,
                            summary = "已完成 ${skill.skillId.value}：评审 passed=${outcome.passed}，" +
                                "问题 ${outcome.issueCount} 项（其中 ERROR ${outcome.errorCount}）；只读评审，未写入 Canonical",
                        )
                    }
                    val followUp = skillRegistry.get(CoreSkills.REWRITE_REVISION.skillId)
                        ?: throw RunAbort(
                            NovelAgentErrorCodes.SKILL_NOT_FOUND,
                            "续步 Skill 未注册: ${CoreSkills.REWRITE_REVISION.skillId.value}",
                        )
                    if (!followUp.enabled) {
                        throw RunAbort(
                            NovelAgentErrorCodes.SKILL_NOT_EXECUTABLE,
                            "续步 Skill 已 disabled: ${followUp.skillId.value}",
                        )
                    }
                    state.followUpSkillId = followUp.skillId
                    // T-43：初次分析 critique 就是同一基线正文的评审结论——续步直接复用它，
                    // 避免为 Revision 再次重复 critique 基线；有界总数保持「初次 + 复评」= 2。
                    val initialCritique = outcome.critique
                    // 有界：只在这里执行一次；续步后不论结果都进入既有 Review/Gate/Commit 或失败收口。
                    outcome = step(
                        sessionId,
                        OrchestrationPhase.EXECUTE,
                        NovelAgentErrorCodes.EXECUTION_FAILED,
                        state,
                        "执行续步 Skill（${followUp.skillId.value}）",
                    ) { activityId ->
                        executeSkill(followUp, request, project, intent, activityId, pack, state, initialCritique)
                    }
                    if (outcome !is SkillOutcome.Draft) {
                        throw RunAbort(
                            NovelAgentErrorCodes.EXECUTION_FAILED,
                            "续步 ${followUp.skillId.value} 未产生正文变更",
                        )
                    }
                    // T-43：对修订稿再评审一次（复用同一 critic 实例）；失败不升级为任务失败，但显式标记。
                    reviewRevisedContent((outcome as SkillOutcome.Draft).content, state)
                }

                is SkillOutcome.CharacterConsistency -> {
                    sessions.complete(sessionId)
                    return result(
                        NovelAgentOutcome.COMPLETED, request, intent, sessionId, skill, pack, state,
                        summary = "已完成 ${skill.skillId.value}：status=${outcome.result.status}，" +
                            "findings=${outcome.result.findings.size}，checked=${outcome.result.checkedCharacters.size}；" +
                            "只读检查，未写入 Canonical",
                    )
                }

                is SkillOutcome.Draft -> Unit
            }

            val draftOutcome = outcome as? SkillOutcome.Draft
                ?: throw RunAbort(NovelAgentErrorCodes.EXECUTION_FAILED, "该执行结果不产生正文变更，无法生成 Artifact")

            val artifact = step(sessionId, OrchestrationPhase.REVIEW, NovelAgentErrorCodes.ARTIFACT_FAILED, state, "校验并生成变更提案") { activityId ->
                review(draftOutcome, request, sessionId, activityId, state)
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
                    // P0-3B D2/D5：Ready ≠ 自动 Commit，也不直接等于"待审批"——必须先桥接进正式章节 Workflow。
                    // 桥接成功 → 正式 Gate 由既有 Workflow 创建（approve/reject/commit 全走正式链路，D1）；
                    // 桥接失败 → 候选保留 + 显式 UNBRIDGED（不伪装 FAILED / 待审批，不静默丢弃）。
                    val bridge = bridgeToWorkflow(request, artifact, sessionId)
                    return when (bridge) {
                        is BridgeResult.Bridged -> {
                            sessions.pause(sessionId)
                            result(
                                NovelAgentOutcome.WAITING_HUMAN, request, intent, sessionId, skill, pack, state,
                                summary = analysisSummaryPrefix(state) +
                                    "变更已就绪并接入正式审批（章节 Workflow 人工门）；批准 / 拒绝以正式 Workflow 进度为准",
                                approvalState = NovelAgentApprovalState.BRIDGED,
                            )
                        }

                        is BridgeResult.Failed -> {
                            sessions.pause(sessionId) // D5：候选保留在会话内，供查看或显式重试
                            result(
                                NovelAgentOutcome.CANDIDATE_UNBRIDGED, request, intent, sessionId, skill, pack, state,
                                summary = "候选未进入正式审批：${bridge.reason}；候选已保留，可显式重试或丢弃（不构成正式审批）",
                                approvalState = NovelAgentApprovalState.UNBRIDGED,
                                failure = NovelAgentFailure(NovelAgentErrorCodes.BRIDGE_FAILED, bridge.reason),
                            )
                        }
                    }
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
        }
    }

    /**
     * 人工批准后的续跑：**Commit**（§16 `resume → Commit`）。
     *
     * P0-3B 约束（D1/D3/D9）：
     *  - 本入口**不再是 Desktop UI 的正式审批入口**（正式批准走 `ChapterWorkflowGateway.approve`）；
     *    保留为内部兼容入口，但必须满足候选身份绑定；
     *  - `request.sessionId` 必填且必须等于候选 WorkingDraft 的会话归属——缺失时**绝不**隐式新建会话；
     *  - 幂等收敛：候选已被正式提交时，返回既有提交事实（COMPLETED），不再重复提交、也不误报失败。
     *
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
            // D3：先验身份、再看会话状态——防止 resolveSession 在 sessionId 缺失时静默新建会话。
            val requestedSessionId = request.sessionId ?: throw RunAbort(
                NovelAgentErrorCodes.SESSION_REQUIRED,
                "resume 必须携带候选所属的原 sessionId（D3）；禁止隐式新建会话操作旧候选",
            )
            val artifact = artifactOf(request, artifactId, state)
            // D9 幂等收敛：候选已有正式提交事实 ⇒ 返回既有事实，并把仍可收敛的 Session 收敛到 COMPLETED。
            val existing = commits.committedByArtifact(artifact.artifactId)
            if (existing != null) {
                // 若上次事务后收尾失败，重复 resume 时补齐 WorkingDraft 终态（幂等，不改提交事实）。
                runCatching { workingDrafts.discard(artifact.workingDraftId) }
                completeSessionIfPossible(requestedSessionId, request.projectId)
                return result(
                    NovelAgentOutcome.COMPLETED, request, intent, sessionId = requestedSessionId,
                    skill = null, pack = null, state = state,
                    summary = "候选已在此前被正式提交（幂等返回既有事实，未重复写入 Canonical）；" +
                        "commit=${existing.commitId.value}",
                    commitId = existing.commitId,
                    historyId = existing.historyId,
                    approvalGateId = existing.gateId,
                )
            }
            session = resolveSession(request, project)
            val sessionId = session.sessionId

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
     *
     * P0-3B 约束（D1/D3/D6）：
     *  - 本入口**不是**正式拒绝入口——正式拒绝必须先经 `ChapterWorkflowGateway.reject` 完成 Gate 决议；
     *    本方法只做**随后的事务性收敛**（丢弃候选 + 取消会话），且同样受候选身份绑定约束（D3）；
     *  - `request.sessionId` 必填，缺失时**绝不**隐式新建会话；
     *  - 候选已被正式提交 → 类型化拒绝（不能再丢弃已进入 Canonical 的候选）；
     *  - 幂等：会话已取消 / 候选已丢弃时返回既有 CANCELLED 事实，不重复收敛。
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
            // D3：先验身份、再看会话状态——禁止在 sessionId 缺失时静默新建会话。
            val requestedSessionId = request.sessionId ?: throw RunAbort(
                NovelAgentErrorCodes.SESSION_REQUIRED,
                "reject 必须携带候选所属的原 sessionId（D3）；禁止隐式新建会话操作旧候选",
            )
            val artifact = artifactOf(request, artifactId, state)
            // D6：已进入 Canonical 的候选不可"拒绝丢弃"（撤销属 Revert 语义，走既有 I10）。
            if (commits.committedByArtifact(artifact.artifactId) != null) {
                throw RunAbort(
                    NovelAgentErrorCodes.CANDIDATE_ALREADY_COMMITTED,
                    "候选 ${artifact.artifactId.value} 已被正式提交，不可拒绝丢弃；如需撤销请走既有 Revert 链路",
                )
            }
            // D6：正式拒绝必须先经 ChapterWorkflowGateway.reject；本入口只做后续收敛。
            requireFormalRejectFirst(artifact)
            session = try {
                resolveSession(request, project)
            } catch (e: RunAbort) {
                // 幂等：会话已取消（重复 reject）→ 返回既有 CANCELLED 事实；其它会话异常照常上抛。
                if (e.code == NovelAgentErrorCodes.SESSION_CANCELLED) {
                    return result(
                        NovelAgentOutcome.CANCELLED, request, intent, sessionId = requestedSessionId,
                        skill = null, pack = null, state = state,
                        summary = "会话已处于取消状态（幂等返回既有事实）；$reason",
                        failure = NovelAgentFailure(NovelAgentErrorCodes.SESSION_CANCELLED, reason),
                    )
                }
                throw e
            }
            workingDrafts.discard(artifact.workingDraftId)
            sessions.cancel(session.sessionId)
            return result(
                NovelAgentOutcome.CANCELLED, request, intent, session.sessionId, skill = null, pack = null, state = state,
                summary = "已拒绝并终止：$reason；Working Draft 已丢弃（DISCARDED），Canonical 未变更",
                failure = NovelAgentFailure(NovelAgentErrorCodes.SESSION_CANCELLED, reason),
            )
        } catch (e: RunAbort) {
            return result(
                NovelAgentOutcome.FAILED, request, intent, session?.sessionId ?: request.sessionId, skill = null, pack = null, state = state,
                summary = "拒绝流程中止（${e.code}）：${e.detail}",
                failure = NovelAgentFailure(e.code, e.detail),
            )
        }
    }

    /**
     * P0-3B D4/D5 · 显式重试：把**已有**候选接入正式章节 Workflow（不重新生成候选）。
     *
     * 与 [run] 的区别：不执行 INTENT/SKILL/CONTEXT/EXECUTE，不创建新的 WorkingDraft / Artifact；
     * 只复用身份校验与 [bridgeToWorkflow]。重复调用是幂等的：若同一候选已在正式 Gate 中，返回 BRIDGED。
     */
    fun bridgeCandidate(request: NovelAgentRequest, artifactId: ChangeArtifactId): NovelAgentResult {
        val intent = analyzeIntent(request.userIntent)
        val state = RunState()
        var session: AgentSession? = null
        try {
            val project = requireProject(request)
            val requestedSessionId = request.sessionId ?: throw RunAbort(
                NovelAgentErrorCodes.SESSION_REQUIRED,
                "bridgeCandidate 必须携带候选所属的原 sessionId（D3）",
            )
            val artifact = artifactOf(request, artifactId, state)
            commits.committedByArtifact(artifact.artifactId)?.let { existing ->
                runCatching { workingDrafts.discard(artifact.workingDraftId) }
                completeSessionIfPossible(requestedSessionId, request.projectId)
                return result(
                    NovelAgentOutcome.COMPLETED, request, intent, sessionId = requestedSessionId,
                    skill = null, pack = null, state = state,
                    summary = "候选已在此前被正式提交，无需再次桥接；commit=${existing.commitId.value}",
                    commitId = existing.commitId,
                    historyId = existing.historyId,
                    approvalGateId = existing.gateId,
                )
            }
            session = resolveSession(request, project)
            val bridge = bridgeToWorkflow(request, artifact, session.sessionId)
            sessions.pause(session.sessionId)
            return when (bridge) {
                is BridgeResult.Bridged -> result(
                    NovelAgentOutcome.WAITING_HUMAN, request, intent, session.sessionId,
                    skill = null, pack = null, state = state,
                    summary = "候选已接入正式审批（章节 Workflow 人工门）；批准 / 拒绝以正式 Workflow 进度为准",
                    approvalState = NovelAgentApprovalState.BRIDGED,
                )
                is BridgeResult.Failed -> result(
                    NovelAgentOutcome.CANDIDATE_UNBRIDGED, request, intent, session.sessionId,
                    skill = null, pack = null, state = state,
                    summary = "候选仍未进入正式审批：${bridge.reason}；候选已保留，可处理后重试",
                    approvalState = NovelAgentApprovalState.UNBRIDGED,
                    failure = NovelAgentFailure(NovelAgentErrorCodes.BRIDGE_FAILED, bridge.reason),
                )
            }
        } catch (e: RunAbort) {
            session?.let { runCatching { sessions.pause(it.sessionId) } }
            return result(
                outcomeOf(e.code), request, intent, session?.sessionId ?: request.sessionId,
                skill = null, pack = null, state = state,
                summary = if (e.code == NovelAgentErrorCodes.ARTIFACT_NOT_FOUND) {
                    "候选已失效（可能因进程重启）：${e.detail}；请重新发起任务"
                } else {
                    "桥接重试中止（${e.code}）：${e.detail}"
                },
                approvalState = if (e.code == NovelAgentErrorCodes.ARTIFACT_NOT_FOUND) {
                    NovelAgentApprovalState.UNBRIDGED
                } else {
                    NovelAgentApprovalState.NOT_REQUIRED
                },
                failure = NovelAgentFailure(e.code, e.detail),
            )
        } catch (t: Throwable) {
            val mapped = errorMapper.map(t)
            session?.let { settleInterrupted(it.sessionId) }
            return result(
                NovelAgentOutcome.FAILED, request, intent, session?.sessionId ?: request.sessionId,
                skill = null, pack = null, state = state,
                summary = "桥接重试失败：${mapped.error}",
                failure = NovelAgentFailure(NovelAgentErrorCodes.EXECUTION_FAILED, mapped.error.toString()),
            )
        }
    }

    /**
     * P0-3B D6/D9 · 正式决议后的**只读观察 + 生命周期协调**（不是第二套审批入口）。
     *
     * - 已有正式提交事实 → Session 收敛 COMPLETED，返回 COMPLETED；
     * - Gate 仍 PENDING → 返回 WAITING_HUMAN（BRIDGED），不改 Gate；
     * - Gate 已 REJECTED/REQUEST_REVISION → 丢弃候选 + 取消 Session（协调），返回 CANCELLED；
     * - Gate 已 APPROVED 但未观察到提交事实 → 返回可诊断失败，不丢弃候选、不重复提交；
     * - 未桥接 / 非本候选 Gate → 返回 CANDIDATE_UNBRIDGED（候选保留）。
     *
     * 本方法不创建 Gate、不批准、不拒绝、不 Commit。
     */
    fun refreshCandidate(request: NovelAgentRequest, artifactId: ChangeArtifactId): NovelAgentResult {
        val intent = analyzeIntent(request.userIntent)
        val state = RunState()
        try {
            requireProject(request)
            val requestedSessionId = request.sessionId ?: throw RunAbort(
                NovelAgentErrorCodes.SESSION_REQUIRED,
                "refreshCandidate 必须携带候选所属的原 sessionId（D3）",
            )
            val artifact = artifactOf(request, artifactId, state)
            val existing = commits.committedByArtifact(artifact.artifactId)
            if (existing != null) {
                // D9：清理上次事务后可能遗留的 WorkingDraft 终态（幂等；不影响已成立的提交事实）。
                runCatching { workingDrafts.discard(artifact.workingDraftId) }
                completeSessionIfPossible(requestedSessionId, request.projectId)
                return result(
                    NovelAgentOutcome.COMPLETED, request, intent, sessionId = requestedSessionId,
                    skill = null, pack = null, state = state,
                    summary = "候选已正式提交（commit=${existing.commitId.value}）；Session 已收敛",
                    commitId = existing.commitId,
                    historyId = existing.historyId,
                    approvalGateId = existing.gateId,
                )
            }
            val gateway = workflowGateway
                ?: return result(
                    NovelAgentOutcome.CANDIDATE_UNBRIDGED, request, intent, requestedSessionId,
                    skill = null, pack = null, state = state,
                    summary = "ChapterWorkflowGateway 未装配，无法观察正式审批状态",
                    approvalState = NovelAgentApprovalState.UNBRIDGED,
                    failure = NovelAgentFailure(NovelAgentErrorCodes.BRIDGE_FAILED, "ChapterWorkflowGateway 未装配"),
                )
            val progress = gateway.getChapterProgress(artifact.change.target.chapterId)
            val expectedRef = WritingSnapshot.WORKING_REF_PREFIX + artifact.workingDraftId.value
            if (progress.pendingWorkingRef == expectedRef) {
                when (progress.pendingGateDecision) {
                    HumanDecision.PENDING -> return result(
                        NovelAgentOutcome.WAITING_HUMAN, request, intent, requestedSessionId,
                        skill = null, pack = null, state = state,
                        summary = "候选仍在正式 Gate 待决；请在「正文」页完成批准或拒绝",
                        approvalState = NovelAgentApprovalState.BRIDGED,
                    )
                    HumanDecision.APPROVED -> return result(
                        NovelAgentOutcome.CANDIDATE_UNBRIDGED, request, intent, requestedSessionId,
                        skill = null, pack = null, state = state,
                        summary = "正式 Gate 已批准，但尚未观察到提交事实；请重试正式批准收敛",
                        approvalState = NovelAgentApprovalState.UNBRIDGED,
                        failure = NovelAgentFailure(
                            NovelAgentErrorCodes.CANDIDATE_APPROVED_NOT_COMMITTED,
                            "Gate 已批准但 CommitHistory 尚无该候选的提交事实",
                        ),
                    )
                    HumanDecision.REJECTED, HumanDecision.REQUEST_REVISION -> {
                        workingDrafts.discard(artifact.workingDraftId)
                        runCatching { sessions.cancel(requestedSessionId) }
                        return result(
                            NovelAgentOutcome.CANCELLED, request, intent, requestedSessionId,
                            skill = null, pack = null, state = state,
                            summary = "正式 Gate 已拒绝；候选已丢弃（DISCARDED），Session 已取消",
                            failure = NovelAgentFailure(
                                NovelAgentErrorCodes.SESSION_CANCELLED,
                                "正式 Gate 已 ${progress.pendingGateDecision?.name}",
                            ),
                        )
                    }
                    null -> Unit
                }
            }
            return result(
                NovelAgentOutcome.CANDIDATE_UNBRIDGED, request, intent, requestedSessionId,
                skill = null, pack = null, state = state,
                summary = "候选未进入正式审批；候选已保留，可显式重试或丢弃",
                approvalState = NovelAgentApprovalState.UNBRIDGED,
                failure = NovelAgentFailure(NovelAgentErrorCodes.BRIDGE_FAILED, "未找到该候选的正式 Gate"),
            )
        } catch (e: RunAbort) {
            return result(
                outcomeOf(e.code), request, intent, request.sessionId,
                skill = null, pack = null, state = state,
                summary = if (e.code == NovelAgentErrorCodes.ARTIFACT_NOT_FOUND) {
                    "候选已失效（可能因进程重启）：${e.detail}；请重新发起任务"
                } else {
                    "刷新审批状态中止（${e.code}）：${e.detail}"
                },
                approvalState = if (e.code == NovelAgentErrorCodes.ARTIFACT_NOT_FOUND) {
                    NovelAgentApprovalState.UNBRIDGED
                } else {
                    NovelAgentApprovalState.NOT_REQUIRED
                },
                failure = NovelAgentFailure(e.code, e.detail),
            )
        } catch (t: Throwable) {
            val mapped = errorMapper.map(t)
            return result(
                NovelAgentOutcome.FAILED, request, intent, request.sessionId,
                skill = null, pack = null, state = state,
                summary = "刷新审批状态失败：${mapped.error}",
                failure = NovelAgentFailure(NovelAgentErrorCodes.EXECUTION_FAILED, mapped.error.toString()),
            )
        }
    }

    /** D6：候选对应的正式 Gate 仍待决/已批准时，禁止直接丢弃（必须先完成正式链路）。 */
    private fun requireFormalRejectFirst(artifact: ChangeArtifact) {
        val gateway = workflowGateway ?: return
        val progress = try {
            gateway.getChapterProgress(artifact.change.target.chapterId)
        } catch (e: ApplicationException) {
            return
        }
        val expectedRef = WritingSnapshot.WORKING_REF_PREFIX + artifact.workingDraftId.value
        if (progress.pendingWorkingRef != expectedRef) return
        when (progress.pendingGateDecision) {
            HumanDecision.PENDING -> throw RunAbort(
                NovelAgentErrorCodes.CANDIDATE_PENDING_GATE,
                "候选仍在正式 Gate 待决；请先经 ChapterWorkflowGateway.reject 完成正式拒绝，再收敛会话",
            )
            HumanDecision.APPROVED -> throw RunAbort(
                NovelAgentErrorCodes.CANDIDATE_APPROVED_NOT_COMMITTED,
                "候选已获正式批准，不能丢弃；请等待/重试正式提交收敛",
            )
            HumanDecision.REJECTED, HumanDecision.REQUEST_REVISION, null -> Unit
        }
    }

    /** D9：把仍可收敛的 Session 置为 COMPLETED（PAUSED/CREATED 需先 ACTIVE；终态不变）。 */
    private fun completeSessionIfPossible(sessionId: AgentSessionId, projectId: ProjectId) {
        val session = try {
            sessions.sessionOf(sessionId)
        } catch (e: ApplicationException) {
            return
        }
        if (session.projectId != projectId) return
        when (session.status) {
            AgentSessionStatus.CREATED, AgentSessionStatus.PAUSED -> {
                runCatching { sessions.activate(sessionId) }
                runCatching { sessions.complete(sessionId) }
            }
            AgentSessionStatus.ACTIVE -> runCatching { sessions.complete(sessionId) }
            AgentSessionStatus.COMPLETED, AgentSessionStatus.FAILED, AgentSessionStatus.CANCELLED -> Unit
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

    /** Skill 选择：Registry 匹配（确定性候选集）→ 过滤无执行绑定 → 指定 Skill 或确定性偏好序 → Skill。 */
    private fun selectSkill(intent: NovelIntentAnalysis, request: NovelAgentRequest): Skill {
        val matches = skillRegistry.match(intent.intentType)
        if (matches.isEmpty()) {
            throw RunAbort(NovelAgentErrorCodes.SKILL_NOT_FOUND, "没有可处理 ${intent.intentType} 的 Skill（enabled 且声明该 purpose）")
        }
        val executable = matches.filter { expectedExecutionAgent(it.skillId) != null }
        if (executable.isEmpty()) {
            throw RunAbort(
                NovelAgentErrorCodes.SKILL_NOT_EXECUTABLE,
                "没有可执行 Skill 处理 ${intent.intentType}：" +
                    matches.joinToString("; ") { "${it.skillId.value}（${notExecutableReason(it.skillId)}）" },
            )
        }
        val preferred = request.preferredSkillId
        val chosenId = if (preferred != null) {
            val matchedId = matches.firstOrNull { it.skillId == preferred }?.skillId
                ?: throw RunAbort(
                    NovelAgentErrorCodes.SKILL_NOT_MATCHED,
                    "Skill ${preferred.value} 未声明处理 ${intent.intentType} 或已 disabled",
                )
            if (expectedExecutionAgent(matchedId) == null) {
                throw RunAbort(
                    NovelAgentErrorCodes.SKILL_NOT_EXECUTABLE,
                    "Skill ${preferred.value} 当前不可执行：${notExecutableReason(matchedId)}",
                )
            }
            matchedId
        } else {
            val characterConsistencyId = executable
                .firstOrNull { it.skillId == CoreSkills.CHARACTER_CONSISTENCY.skillId }
                ?.skillId
            if (characterConsistencyId != null && hasCharacterConsistencyIntent(request.userIntent)) {
                characterConsistencyId
            } else {
                val preference = INTENT_SKILL_PREFERENCE[intent.intentType].orEmpty()
                preference.firstNotNullOfOrNull { id -> executable.firstOrNull { it.skillId == id }?.skillId }
                    ?: executable.first().skillId
            }
        }
        return skillRegistry.get(chosenId)
            ?: throw RunAbort(NovelAgentErrorCodes.SKILL_NOT_FOUND, "Skill 未注册: ${chosenId.value}")
    }

    /** T-2C-3：无执行绑定 Skill 的解释性原因（自动选择与显式 preferred 共用）。 */
    private fun notExecutableReason(skillId: SkillId): String = when (skillId) {
        CoreSkills.KNOWLEDGE_WORLD_MODEL.skillId ->
            "无执行绑定（世界模型维护属既有 Knowledge Update 链：需 CONFIRMED Final Draft + 人工确认）"

        else -> "无执行绑定（expectedExecutionAgent = null）"
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
        // A2：发布实际构建的 Pack，供 Inspector 读取；不改变 Agent 结果契约。
        contextPackStore?.put(sessionId, request.projectId, pack, request.taskId)
        return pack
    }

    /** 写作 / 规划 / 人物一致性 Skill 消费 ContextPack；其余 Skill 不得生成未消费的 Snapshot。 */
    private fun skillConsumesContext(skillId: SkillId): Boolean =
        skillId == CoreSkills.WRITING.skillId ||
            skillId == CoreSkills.STORY_PLANNING.skillId ||
            skillId == CoreSkills.CHARACTER_CONSISTENCY.skillId

    /** J-3：章节 Variant 与 Project active Variant 不一致 ⇒ 在 Context Engine 构建前 `SCOPE_MISMATCH`。 */
    private fun requireVariantScope(request: NovelAgentRequest, project: Project) {
        if (request.activeChapterId == null) return
        val chapter = requireFocusChapter(request)
        if (chapter.variantId?.value != project.state.activeVariantId?.value) {
            throw RunAbort(
                NovelAgentErrorCodes.SCOPE_MISMATCH,
                "章节 variant=${chapter.variantId?.value ?: "ORIGINAL"} 与 Project active variant=" +
                    "${project.state.activeVariantId?.value ?: "ORIGINAL"} 不一致；拒绝构建 Context",
            )
        }
    }

    /** 按 Skill 绑定执行（既有五 Agent + 既有只读 Product Tool；不写 Canonical）。 */
    private fun executeSkill(
        skill: Skill,
        request: NovelAgentRequest,
        project: Project,
        intent: NovelIntentAnalysis,
        activityId: ActivityId,
        contextPack: ContextPack?,
        state: RunState,
        /** T-43：analysis 续步时复用初次 critique，避免对同一基线重复评审。 */
        critiqueOverride: ValidationResult? = null,
    ): SkillOutcome {
        val expectedAgentId = expectedExecutionAgent(skill.skillId)
        val violations = skillExecutionGuard.violations(skill, expectedAgentId)
        if (violations.isNotEmpty()) {
            throw RunAbort(
                NovelAgentErrorCodes.SKILL_NOT_EXECUTABLE,
                "Skill ${skill.skillId.value} 契约与执行不一致: ${violations.joinToString("; ")}",
            )
        }
        val outcome = when (skill.skillId) {
            CoreSkills.WRITING.skillId -> generateNewContent(skill, request, project, intent, activityId, requireContextPack(contextPack), state)
            CoreSkills.REWRITE_REVISION.skillId -> reviseExistingContent(skill, request, activityId, state, critiqueOverride)
            CoreSkills.STORY_PLANNING.skillId ->
                planChapter(skill, request, project, intent, activityId, requireContextPack(contextPack))
            CoreSkills.ANALYSIS_CRITIQUE.skillId -> analyzeChapter(skill, request, activityId, state)
            CoreSkills.CHARACTER_CONSISTENCY.skillId ->
                checkCharacterConsistency(skill, request, activityId, contextPack, state)
            else -> throw RunAbort(
                NovelAgentErrorCodes.SKILL_NOT_EXECUTABLE,
                "Skill ${skill.skillId.value} 在本阶段没有执行绑定" +
                    "（世界模型维护属既有 Knowledge Update 链：需 CONFIRMED Final Draft + 人工确认）",
            )
        }
        verifyObservedTools(skill, activityId)
        return outcome
    }

    /**
     * T-2C-2 / D-27：本 Skill 本次执行期实际调用的工具集合（来自 I4 ToolCallLog，工具级）必须
     * ⊆ `Skill.allowedTools`。零调用 = 空集，天然通过。
     */
    private fun verifyObservedTools(skill: Skill, activityId: ActivityId) {
        val observed = toolCallLogs.listByActivity(activityId).map { it.toolName }.toSet()
        val disallowed = observed.filter { it !in skill.allowedTools }.sortedBy { it.value }
        if (disallowed.isNotEmpty()) {
            throw RunAbort(
                NovelAgentErrorCodes.TOOL_NOT_ALLOWED,
                "Skill ${skill.skillId.value} 执行期调用了 allowedTools 之外的工具: " +
                    disallowed.joinToString(", ") { it.value },
            )
        }
    }

    /** 每个可执行 Skill 的真实执行 Agent；未绑定执行的 Skill 返回 null。 */
    private fun expectedExecutionAgent(skillId: SkillId): AgentId? = when (skillId) {
        CoreSkills.WRITING.skillId -> writer.agentId
        CoreSkills.REWRITE_REVISION.skillId -> rewriter.agentId
        CoreSkills.STORY_PLANNING.skillId -> planner.agentId
        CoreSkills.ANALYSIS_CRITIQUE.skillId -> critic.agentId
        CoreSkills.CHARACTER_CONSISTENCY.skillId -> characterConsistencyAgent.agentId
        else -> null
    }

    private fun requireContextPack(pack: ContextPack?): ContextPack = pack ?: throw RunAbort(
        NovelAgentErrorCodes.CONTEXT_BUILD_FAILED,
        "该 Skill 需要 ContextPack，但本次任务未构建",
    )

    /** 写作（CONTINUE / EXPAND / CUSTOM）：既有 Planner + Writer 产出正文 → Working Draft；不落 Canonical。 */
    private fun generateNewContent(
        skill: Skill,
        request: NovelAgentRequest,
        project: Project,
        intent: NovelIntentAnalysis,
        activityId: ActivityId,
        contextPack: ContextPack,
        state: RunState,
    ): SkillOutcome {
        val chapter = requireFocusChapter(request)
        val baseDraftId = latestCanonicalDraftId(skill, request, chapter.chapterId, activityId, state)
        // O-42：紧邻前章 CONFIRMED Canonical → 注入 Planner/Writer；缺失 / 非 CONFIRMED → 降级继续并显式记录原因。
        val continuation = resolveContinuation(request, project, chapter, intent, state)
        val context = planningContexts.assemble(
            writingRequest(request, project, chapter, intent),
            contextPack,
            continuation?.reference,
            continuation?.resolved,
        )
        // 既有 Planner → 既有 Writer（复用其 Prompt / Parser 语义）；plan.chapterId 绑定到焦点章节（纯数据，不落库、不建章）
        val plan = planner.plan(context, emptyList(), activityId, skill.allowedTools).copy(
            chapterId = chapter.chapterId,
            novelId = chapter.novelId,
            variantId = chapter.variantId,
            scope = chapter.scope,
        )
        val draft = writer.write(context, plan)
        return SkillOutcome.Draft(content = draft.content, baseDraftId = baseDraftId, chapterId = chapter.chapterId)
    }

    /** O-42：已解析的前章续写来源（reference + resolved 必须成对传回同一份 assemble）。 */
    private data class ContinuationInput(
        val reference: ContinuationReference,
        val resolved: ResolvedContinuation,
    )

    /**
     * O-42 · 解析紧邻前章 Canonical 来源。
     *
     * 语义（已裁）：
     *  - 前章 = 同 Novel + 同 Variant 章程序列中当前章的紧邻前一章（仓储按 order ASC 返回）；
     *  - 第 1 章 = 正常无前章：不构造 reference，且**不**记录降级原因（`continuationAbsentReason=null`）；
     *  - 前章存在但无 Canonical → `NO_CANONICAL`，降级继续；
     *  - 前章 carrier 非 CONFIRMED（O-23）→ `CARRIER_NOT_CONFIRMED`，降级继续；
     *  - 复用 [ContinuationResolver] 的既有硬校验契约；其类型化异常在此**只读映射**为原因，不改变 Resolver 行为，
     *    也不把失败升级为任务失败（工作流链仍保持硬拒）。
     */
    private fun resolveContinuation(
        request: NovelAgentRequest,
        project: Project,
        chapter: Chapter,
        intent: NovelIntentAnalysis,
        state: RunState,
    ): ContinuationInput? {
        val siblings = chapters.listByNovel(project.novelId, chapter.variantId)
        val index = siblings.indexOfFirst { it.chapterId == chapter.chapterId }
        if (index < 0) {
            state.continuationAbsentReason = NovelAgentContinuationReasons.NO_PREVIOUS_CHAPTER
            return null
        }
        if (index == 0) return null
        val previous = siblings[index - 1]

        val carrier = canonicalRead.committedDraft(previous.chapterId)
        if (carrier == null) {
            state.continuationAbsentReason = NovelAgentContinuationReasons.NO_CANONICAL
            return null
        }
        if (carrier.status != DraftStatus.CONFIRMED) {
            state.continuationAbsentReason = NovelAgentContinuationReasons.CARRIER_NOT_CONFIRMED
            return null
        }

        val reference = ContinuationReference(previous.chapterId, carrier.draftId)
        val resolved = try {
            continuationResolver.resolve(writingRequest(request, project, chapter, intent), reference)
        } catch (e: ApplicationException) {
            state.continuationAbsentReason = when (e.error) {
                is ApplicationError.DraftConfirmationRequired,
                is ApplicationError.EntityNotFound,
                -> NovelAgentContinuationReasons.NO_CANONICAL

                is ApplicationError.InvalidContinuationSource ->
                    if ((e.message ?: "").contains("非 CONFIRMED")) {
                        NovelAgentContinuationReasons.CARRIER_NOT_CONFIRMED
                    } else {
                        NovelAgentContinuationReasons.CONTINUATION_UNRESOLVED
                    }

                else -> NovelAgentContinuationReasons.CONTINUATION_UNRESOLVED
            }
            return null
        }
        state.continuationIncluded = true
        state.continuationSourceChapterId = previous.chapterId
        return ContinuationInput(reference, resolved)
    }

    /** 改写（REWRITE）：既有 Critique + Revision 产出修订正文 → Working Draft；不落 Canonical。 */
    private fun reviseExistingContent(
        skill: Skill,
        request: NovelAgentRequest,
        activityId: ActivityId,
        state: RunState,
        critiqueOverride: ValidationResult? = null,
    ): SkillOutcome {
        val chapter = requireFocusChapter(request)
        val baseDraftId = latestCanonicalDraftId(skill, request, chapter.chapterId, activityId, state)
            ?: throw RunAbort(NovelAgentErrorCodes.BASE_DRAFT_NOT_FOUND, "改写需要章节已有 Canonical 正文：${chapter.chapterId.value}")
        val current = reader.draft(baseDraftId)
            ?: throw RunAbort(NovelAgentErrorCodes.BASE_DRAFT_NOT_FOUND, "Canonical Draft 不存在: ${baseDraftId.value}")
        // T-43：analysis 续步时复用初次 critique（同一基线），避免重复评审；其他入口仍按既有路径。
        val critique = critiqueOverride ?: critic.critique(current)
        val revised = rewriter.revise(current, critique)
        return SkillOutcome.Draft(content = revised.content, baseDraftId = baseDraftId, chapterId = chapter.chapterId)
    }

    /** 规划（PLAN）：既有 Planner 产出 ChapterPlan；**不创建章节、不落库**（I11 不直接改 Canonical）。
     *  activityId 供 Planner 的只读工具调用走既有调度链（权限 + 审计 + 作用域）。 */
    private fun planChapter(
        skill: Skill,
        request: NovelAgentRequest,
        project: Project,
        intent: NovelIntentAnalysis,
        activityId: ActivityId,
        contextPack: ContextPack,
    ): SkillOutcome {
        val chapter = requireFocusChapter(request)
        val context = planningContexts.assemble(writingRequest(request, project, chapter, intent), contextPack)
        val plan = planner.plan(context, emptyList(), activityId, skill.allowedTools)
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
            critique = critique,
        )
    }

    /**
     * T-43 · 续步后对修订稿再评审一次（复用同一 [CritiqueAgent] 实例/入口，不新建 Agent）。
     *
     * 有界：本方法只被调用一次；复评即使仍 ERROR 也只记录结果，不触发第二轮 Revision。
     * 复评失败（非法输出 / 调用失败）不升级为任务失败：修订稿仍进入 REVIEW/Gate；
     * 但必须写入 [NovelAgentResult.postRevisionCheckFailure]，禁止静默。
     */
    private fun reviewRevisedContent(content: String, state: RunState) {
        try {
            val critique = critic.critiqueContent(content)
            state.postRevisionAnalysis = NovelAgentAnalysisSummary(
                passed = critique.passed,
                issueCount = critique.issues.size,
                errorCount = critique.issues.count { it.severity == IssueSeverity.ERROR },
            )
            state.postRevisionCheckFailure = null
        } catch (e: ApplicationException) {
            state.postRevisionAnalysis = null
            state.postRevisionCheckFailure = when (e.error) {
                is ApplicationError.InvalidCritiqueOutput -> NovelAgentPostRevisionFailureCodes.INVALID_OUTPUT
                else -> NovelAgentPostRevisionFailureCodes.CALL_FAILED
            }
        }
    }

    /**
     * 人物一致性（T-2B-3）：只读 UseCase → Result 折入 NovelAgentResult；不产生 WorkingDraft / Artifact。
     * activityId 预留给后续 Tool/Activity 扩展；本单元 UseCase 不消费。
     */
    @Suppress("UNUSED_PARAMETER")
    private fun checkCharacterConsistency(
        skill: Skill,
        request: NovelAgentRequest,
        activityId: ActivityId,
        contextPack: ContextPack?,
        state: RunState,
    ): SkillOutcome {
        if (skill.skillId != CoreSkills.CHARACTER_CONSISTENCY.skillId) {
            throw RunAbort(
                NovelAgentErrorCodes.SKILL_NOT_EXECUTABLE,
                "Skill ${skill.skillId.value} 不是 CharacterConsistency，拒绝执行",
            )
        }
        val chapter = requireFocusChapter(request)
        val result = characterConsistency.check(
            novelId = chapter.novelId,
            variantId = chapter.variantId,
            chapterId = chapter.chapterId,
            contextPack = requireContextPack(contextPack),
            plan = null,
            scope = CharacterConsistencyScope.CURRENT_CHAPTER,
        )
        state.characterConsistency = result
        return SkillOutcome.CharacterConsistency(result)
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

    /* ---------------- P0-3B D2/D4/D5 · 正式审批桥接 ---------------- */

    /** 桥接结果（内部载体）：Bridged = 候选已进入正式 Workflow 人工门；Failed = 显式未接入原因。 */
    private sealed interface BridgeResult {
        data object Bridged : BridgeResult

        data class Failed(val reason: String) : BridgeResult
    }

    /**
     * 把正文候选经**既有** [ChapterWorkflowGateway] 接入正式章节 Workflow（D2）。
     *
     * 语义（全部复用既有实现，不新建第二套 Gate / Validation / Commit）：
     *  - 候选 target 必须与焦点章节一致（novel / chapter / variant），不一致 → 显式失败；
     *  - 章节已存在**待确认**正式审批时：能证明属于同一 WorkingDraft（经 [ChapterWorkflowProgress.pendingWorkingRef]）
     *    → 幂等定位既有流程（D4）；无法证明归属 → 显式失败（不猜测，D4 保守分支）；
     *  - 章节无 Workflow → `startChapter`（幂等创建骨架）后 `proposeRewrite`；
     *  - 既有 `proposeRewrite` 自带目标 / 作用域 / in-flight / Validation 四重校验，失败原因原样透出（D5）；
     *  - 未装配 gateway（null）→ 显式失败（不伪装待审批）。
     *
     * 桥接事实记录为既有 I4 Activity（kind=BRIDGE；成功 / 失败均落）。
     */
    private fun bridgeToWorkflow(request: NovelAgentRequest, artifact: ChangeArtifact, sessionId: AgentSessionId): BridgeResult {
        val gateway = workflowGateway
            ?: return BridgeResult.Failed("ChapterWorkflowGateway 未装配，无法把候选接入正式审批（P0-3B D2）")
        val chapter = try {
            requireFocusChapter(request)
        } catch (e: RunAbort) {
            return BridgeResult.Failed(e.detail)
        }
        val target = artifact.change.target
        if (target.chapterId != chapter.chapterId || target.novelId != chapter.novelId || target.variantId != chapter.variantId) {
            return BridgeResult.Failed(
                "候选目标（novel=${target.novelId.value}, chapter=${target.chapterId.value}）与焦点章节（${chapter.chapterId.value}）不一致",
            )
        }
        val activity = activities.start(sessionId, BRIDGE_PHASE, "把候选接入正式章节 Workflow（P0-3B D2）")
        return try {
            val progress = gateway.getChapterProgress(chapter.chapterId)
            if (progress.waitingForUser) {
                val expectedRef = WritingSnapshot.WORKING_REF_PREFIX + artifact.workingDraftId.value
                if (progress.pendingWorkingRef == expectedRef) {
                    activities.complete(activity.activityId, "重复桥接：候选已在正式审批流程中（幂等定位，D4）")
                    return BridgeResult.Bridged
                }
                val reason = "章节已存在待确认的正式审批（${progress.pendingWorkingRef ?: "归属未知"}），请先在写作页处理"
                activities.fail(activity.activityId, reason)
                return BridgeResult.Failed(reason)
            }
            // 章节（可能）尚无 Workflow：startChapter 幂等（已有则直接返回进度），随后 proposeRewrite 入闸
            gateway.startChapter(chapter.novelId, chapter.variantId, chapter.chapterId)
            gateway.proposeRewrite(chapter.chapterId, artifact.workingDraftId)
            activities.complete(activity.activityId, "候选已进入正式审批（Workflow 人工门）")
            BridgeResult.Bridged
        } catch (e: ApplicationException) {
            val reason = e.message ?: "桥接失败（${e.error}）"
            activities.fail(activity.activityId, reason)
            BridgeResult.Failed(reason)
        }
    }

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
        // 即使 Artifact 已因进程重启失效，也保留请求中的身份，供 UI 展示“候选已失效”。
        state.artifactId = artifactId
        val artifact = try {
            changes.get(artifactId)
        } catch (e: ApplicationException) {
            throw RunAbort(NovelAgentErrorCodes.ARTIFACT_NOT_FOUND, "Change Artifact 不存在: ${artifactId.value}", e)
        }
        if (artifact.projectId != request.projectId) {
            throw RunAbort(NovelAgentErrorCodes.ARTIFACT_NOT_FOUND, "Change Artifact 不存在: ${artifactId.value}")
        }
        // P0-3B D3：候选（WorkingDraft）必须属于请求声明的会话——不得只校验 projectId。
        // WorkingDraft 为进程内候选：丢失（如进程重启）时显式失效，不伪造可继续状态。
        val workingDraft = try {
            workingDrafts.get(artifact.workingDraftId)
        } catch (e: ApplicationException) {
            throw RunAbort(
                NovelAgentErrorCodes.ARTIFACT_NOT_FOUND,
                "候选 Working Draft 不存在: ${artifact.workingDraftId.value}（候选可能已因进程重启失效，请重新生成）",
                e,
            )
        }
        val requestedSessionId = request.sessionId
            ?: throw RunAbort(
                NovelAgentErrorCodes.SESSION_REQUIRED,
                "该操作必须携带候选所属的原 sessionId（候选 ${artifact.artifactId.value}）；禁止隐式新建会话操作旧候选",
            )
        if (workingDraft.sessionId != requestedSessionId) {
            throw RunAbort(
                NovelAgentErrorCodes.CANDIDATE_SESSION_MISMATCH,
                "候选 ${artifact.artifactId.value} 属于会话 ${workingDraft.sessionId?.value ?: "<无>"}，" +
                    "与请求声明的会话 ${requestedSessionId.value} 不一致",
            )
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

    /** D-36 · 续步场景下保留初次分析结论的前缀；普通写作任务为空。 */
    private fun analysisSummaryPrefix(state: RunState): String {
        val analysis = state.analysis ?: return ""
        val followUp = state.followUpSkillId
            ?.let { "；已自动续步 ${it.value}" }
            ?: "；只读评审，未写入 Canonical"
        return "已完成 ${CoreSkills.ANALYSIS_CRITIQUE.skillId.value}：评审 passed=${analysis.passed}，" +
            "问题 ${analysis.issueCount} 项（其中 ERROR ${analysis.errorCount}）$followUp；"
    }

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
        approvalState: NovelAgentApprovalState = NovelAgentApprovalState.NOT_REQUIRED,
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
        characterConsistency = state.characterConsistency,
        analysis = state.analysis,
        postRevisionAnalysis = state.postRevisionAnalysis,
        postRevisionCheckFailure = state.postRevisionCheckFailure,
        followUpSkillId = state.followUpSkillId,
        workingDraftId = state.workingDraftId,
        artifactId = state.artifactId,
        artifactStatus = state.artifactStatus,
        continuationIncluded = state.continuationIncluded,
        continuationSourceChapterId = state.continuationSourceChapterId,
        continuationAbsentReason = state.continuationAbsentReason,
        approvalGateId = approvalGateId,
        approvalState = approvalState,
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
        var characterConsistency: CharacterConsistencyResult? = null
        var analysis: NovelAgentAnalysisSummary? = null
        var postRevisionAnalysis: NovelAgentAnalysisSummary? = null
        var postRevisionCheckFailure: String? = null
        var followUpSkillId: SkillId? = null
        var workingDraftId: WorkingDraftId? = null
        var artifactId: ChangeArtifactId? = null
        var artifactStatus: ChangeArtifactStatus? = null
        var continuationIncluded: Boolean = false
        var continuationSourceChapterId: ChapterId? = null
        var continuationAbsentReason: String? = null
    }

    /** 编排中止（内部信号；统一转成稳定 [NovelAgentFailure]，不向调用方抛业务异常）。 */
    private class RunAbort(val code: String, val detail: String, cause: Throwable? = null) :
        RuntimeException(detail, cause)

    /** 固定编排相位（**有限、明确、可追踪**；作为既有 Activity 的 kind，不新建 Step 模型）。 */
    private enum class OrchestrationPhase { INTENT, SKILL, CONTEXT, EXECUTE, REVIEW, GATE, COMMIT }

    /** P0-3B D2 · 桥接事实的 Activity kind（非编排相位；复用既有 I4 Activity 记录）。 */
    private val BRIDGE_PHASE: String = "BRIDGE"

    /** Skill 执行结果（编排层内部派发载体；不是领域模型）。 */
    private sealed interface SkillOutcome {
        /** 产出正文（后续进 Working Draft / Validation / Diff / Artifact）。 */
        data class Draft(val content: String, val baseDraftId: DraftId?, val chapterId: ChapterId) : SkillOutcome

        /** 产出章节规划引用（无正文变更）。 */
        data class Plan(val chapterId: ChapterId, val chapterPlanId: ChapterPlanId) : SkillOutcome

        /** 只读分析结果（无正文变更）；T-43：保留 critique 供续步复用（不在编排层重建）。 */
        data class Analysis(
            val passed: Boolean,
            val issueCount: Int,
            val errorCount: Int,
            val critique: ValidationResult,
        ) : SkillOutcome

        /** 人物一致性只读结果（无正文变更）。 */
        data class CharacterConsistency(val result: CharacterConsistencyResult) : SkillOutcome
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

        /** 窄范围人物一致性意图词；避免把普通"分析/检查"劫持到 CharacterConsistency。 */
        private val CHARACTER_CONSISTENCY_KEYWORDS: List<String> = listOf(
            "人物一致性",
            "角色一致性",
            "性格一致",
            "设定一致",
            "人物设定",
            "角色设定",
        )

        private fun hasCharacterConsistencyIntent(userIntent: String): Boolean {
            if (CHARACTER_CONSISTENCY_KEYWORDS.any { it in userIntent }) return true
            return "一致性" in userIntent &&
                listOf("人物", "角色", "性格", "设定").any { it in userIntent }
        }

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