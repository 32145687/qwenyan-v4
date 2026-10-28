package com.qianyan.application.usecase.writing

import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.application.error.ErrorMapper
import com.qianyan.application.usecase.UseCase
import com.qianyan.application.usecase.context.ContextEngineUseCases
import com.qianyan.application.usecase.draft.WorkingDraftUseCases
import com.qianyan.application.usecase.project.ProjectUseCases
import com.qianyan.application.usecase.task.TaskManagerUseCases
import com.qianyan.application.usecase.writing.planning.ContinuationResolver
import com.qianyan.application.usecase.writing.planning.PlanningContextAssembly
import com.qianyan.model.TaskId
import com.qianyan.model.context.ContextRequest
import com.qianyan.model.context.UserWritingRequest
import com.qianyan.model.decision.DecisionPolicy
import com.qianyan.model.story.ChapterPlan
import com.qianyan.model.story.ContinuationReference
import com.qianyan.model.task.Checkpoint
import com.qianyan.model.task.TaskType
import com.qianyan.model.workingdraft.WorkingDraft
import com.qianyan.model.workingdraft.WorkingDraftTarget
import com.qianyan.model.writing.Draft
import com.qianyan.storage.repository.DraftRepository

/**
 * Writing 执行 Use Case（P11.3）。
 *
 * 目标链路：
 * ```
 * Task → WRITING → RUNNING → context assembly → WriterAgent(AgentRuntime → LLMGateway → Mock)
 *     → DraftParser → DraftRepository.save → WRITING Checkpoint → COMPLETED / FAILED
 * ```
 *
 * 职责边界：
 *  - 校验 Task type == WRITING（非 WRITING → [ApplicationError.InvalidOperation]；不存在 → TaskNotFound）；
 *  - 复用 P8.2 [TaskManagerUseCases] 生命周期（start / saveCheckpoint / complete / fail），不经状态机直改状态；
 *  - 接收 taskId + [UserWritingRequest] + [ChapterPlan]（ChapterPlan 由 PLANNING Checkpoint 恢复而来）；
 *  - 组装 [com.qianyan.application.usecase.writing.planning.PlanningContext]（复用 Planning 上下文，不创建第二套）；
 *  - 调用 [WriterAgent] → [DraftParser] → [DraftRepository.save] → saveCheckpoint("WRITING", WritingSnapshot)
 *    → complete；
 *  - 成功：PENDING → RUNNING → WRITING → Checkpoint → COMPLETED；
 *    失败：RUNNING → FAILED（保存类型化 Task error）→ 继续抛类型化错误；绝不伪造 Draft。
 *  - 不修改 TaskStateMachine；不实现 Agent loop / Workflow / HITL / retry（属 P11.4+）。
 */
class WritingExecutionUseCase(
    private val taskManager: TaskManagerUseCases,
    private val contextAssembly: PlanningContextAssembly,
    private val writer: WriterAgent,
    private val draftRepository: DraftRepository,
    private val workingDrafts: WorkingDraftUseCases,
    private val projects: ProjectUseCases,
    private val contextEngine: ContextEngineUseCases,
    private val continuationResolver: ContinuationResolver,
    errorMapper: ErrorMapper,
) : UseCase(errorMapper) {

    /**
     * **P1-02c+d · AI 写作链的生产入口**：AI 产出写入 **WorkingDraft**（I8 工作区），**不写章节 Draft 表**。
     *
     * ```
     * writer.write（内存产出）
     *   → WorkingDraftUseCases.create（独立 WorkingDraftId；base = 章节当前 Draft，只读取得 → Diff 基线）
     *   → WRITING Checkpoint（类型化工作引用：workingDraftId，**不是** draftId）
     * ```
     * 目标类型与 Project 归属全部来自既有 [ChapterPlan]（novel / chapter / variant）与既有 ProjectUseCases；
     * 不新建模型、不伪造 DraftId、不改 Canonical。后续 CRITIQUE / FINALIZE / CONFIRMATION / COMMIT
     * 都以本 WorkingDraft 为工作产物（见 [com.qianyan.application.usecase.workflow.WorkflowOrchestrator]）。
     *
     * @throws ApplicationError.EntityNotFound Project / Chapter / 基线 Draft 不存在或越界。
     */
    fun executeIntoWorkingDraft(
        taskId: TaskId,
        request: UserWritingRequest,
        plan: ChapterPlan,
        decisionPolicies: List<DecisionPolicy> = emptyList(),
        continuationReference: ContinuationReference? = null,
    ): WorkingDraft {
        val task = taskManager.findById(taskId)
        if (task.type != TaskType.WRITING) {
            throw ApplicationException(
                ApplicationError.InvalidOperation("Task ${taskId.value} 类型 ${task.type} 不是 WRITING，无法执行写作"),
            )
        }
        requirePlanMatchesRequest(request, plan)
        // P1-02c+d：AI 工作产物必须落到具体章节（WorkingDraft.target.chapterId 非空）；未绑定 → 类型化拒绝（不伪造）。
        val chapterId = plan.chapterId
            ?: throw ApplicationException(
                ApplicationError.InvalidOperation(
                    "ChapterPlan ${plan.chapterPlanId.value} 未绑定 Chapter，无法建立 AI 工作产物（WorkingDraft）",
                ),
            )

        taskManager.start(taskId)
        try {
            // 续篇：在产生任何 AI 产出之前解析/校验；失败 → Task FAILED，不调用 LLM。
            val resolved = continuationReference?.let { continuationResolver.resolve(request, it) }
            // 工作产物归属需要 Project 运行态（WorkingDraftUseCases.create 按 ProjectId 校验 Project）；
            // openProject 幂等（已存在则不写），与 I1「IDE 打开项目」语义一致。
            val project = guard { projects.openProject(plan.novelId) }
            val pack = contextEngine.build(
                ContextRequest(
                    projectId = project.projectId,
                    purpose = request.intentType,
                    taskId = taskId,
                    focusChapterId = chapterId,
                ),
                plan.variantId,
            )
            val context = contextAssembly.assemble(request, pack, continuationReference, resolved)
            // writer 产出仍是内存 Draft 值（正文来源），**不落 DraftRepository**（P1-02c+d）。
            val produced = writer.write(context, plan, decisionPolicies)
            val baseDraftId = guard { draftRepository.latestByChapter(chapterId) }?.draftId
            val working = guard {
                workingDrafts.create(
                    projectId = project.projectId,
                    target = WorkingDraftTarget(plan.novelId, chapterId, plan.variantId),
                    content = produced.content,
                    baseDraftId = baseDraftId,
                )
            }
            taskManager.saveCheckpoint(
                taskId,
                WritingSnapshot.STAGE,
                WritingSnapshot.encodeWorking(working, decisionPolicies),
            )
            taskManager.complete(taskId)
            return working
        } catch (e: ApplicationException) {
            taskManager.fail(taskId, describe(e.error))
            throw e
        } catch (t: Throwable) {
            val mapped = errorMapper.map(t)
            taskManager.fail(taskId, describe(mapped.error))
            throw mapped
        }
    }

    /**
     * P1-02c+d：从 WRITING Checkpoint 恢复 AI 工作产物（**只认类型化工作引用**；无该键 → null）。
     * 工作区是进程内存储：引用已失效（如进程重启）→ 调用方必须按"引用失效"处理，不得当作恢复成功。
     */
    fun workingDraftFrom(checkpoint: Checkpoint): WorkingDraft? {
        val id = WritingSnapshot.decodeWorkingReference(checkpoint.snapshot) ?: return null
        return try {
            workingDrafts.get(id)
        } catch (e: ApplicationException) {
            null
        }
    }

    /** P12.0.1 P3：Request 与 Plan 的 novel/variant/scope 必须一致；不一致即跨实体污染 → 类型化拒绝（在调 LLM 前）。 */
    private fun requirePlanMatchesRequest(request: UserWritingRequest, plan: ChapterPlan) {
        val requestNovelId = request.baseNovelId?.value
        val mismatches = buildList {
            if (requestNovelId != null && requestNovelId != plan.novelId.value) add("novel")
            if (request.variantId != plan.variantId) add("variant")
            if (request.scope != plan.scope) add("scope")
        }
        if (mismatches.isNotEmpty()) {
            throw ApplicationException(
                ApplicationError.VariantMismatch(
                    "UserWritingRequest 与 ChapterPlan(${plan.chapterPlanId.value}) 作用域不一致（不一致: ${mismatches.joinToString()}）",
                ),
            )
        }
    }

    /** 从 WRITING Checkpoint 恢复 [Draft]（P1-2：经 draftId → DraftRepository，正文单一来源，不复制进 Checkpoint）。 */
    fun draftFrom(checkpoint: Checkpoint): Draft? {
        val id = WritingSnapshot.decodeReference(checkpoint.snapshot) ?: return null
        return guard { draftRepository.getById(id) }
    }

    /**
     * P20-P5：从 WRITING Checkpoint 恢复 [com.qianyan.model.decision.DecisionPolicy] 快照。
     * @return `null` = 该 Checkpoint 无政策快照（早于 P5）。
     */
    fun decisionPoliciesFrom(checkpoint: Checkpoint): List<com.qianyan.model.decision.DecisionPolicy>? =
        WritingSnapshot.decodePolicies(checkpoint.snapshot)

    private fun describe(error: ApplicationError): String = when (error) {
        is ApplicationError.UnknownStorage -> "UnknownStorage: ${error.cause.message ?: error.cause::class.simpleName}"
        else -> error.toString()
    }
}