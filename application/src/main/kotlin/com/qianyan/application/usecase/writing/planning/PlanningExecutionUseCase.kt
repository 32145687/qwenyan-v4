package com.qianyan.application.usecase.writing.planning

import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.application.error.ErrorMapper
import com.qianyan.application.usecase.UseCase
import com.qianyan.application.usecase.task.TaskManagerUseCases
import com.qianyan.model.ChapterId
import com.qianyan.model.TaskId
import com.qianyan.model.context.UserWritingRequest
import com.qianyan.model.story.Chapter
import com.qianyan.model.story.ChapterPlan
import com.qianyan.model.story.ChapterStatus
import com.qianyan.model.story.ContinuationReference
import com.qianyan.model.task.Checkpoint
import com.qianyan.model.task.TaskType
import com.qianyan.storage.repository.ChapterRepository
import kotlinx.datetime.Clock

/**
 * Planning 执行 Use Case（P11.2 + P12.0 P0-4）。
 *
 * 目标链路：
 * ```
 * Task → PLANNING → RUNNING → PlannerAgent(AgentRuntime → LLMGateway) → ChapterPlan
 *     → 绑定/创建 Chapter（chapterId 不再长期为 null）→ Checkpoint → COMPLETED / FAILED
 * ```
 *
 * 职责边界：
 *  - 复用 P8.2 [TaskManagerUseCases] 生命周期（start / saveCheckpoint / complete / fail），不经状态机直改状态；
 *  - Checkpoint 复用现有 [com.qianyan.model.task.Checkpoint]（snapshot 承载 ChapterPlan，不加新表）；
 *  - P0-4：PlannerAgent 只**提出** ChapterPlan；**真正 Chapter 持久化由本 Application 层完成**（经
 *    [ChapterRepository]）：plan.chapterId 为空 → 创建真实 Chapter（order = nextOrder，同 Novel+Variant 防重）；
 *    非空 → 校验存在。返回的 ChapterPlan.chapterId 非空，供 Writing/ChapterDraft 关联。
 *  - 失败：start 后任何失败 → fail（记录类型化原因）→ 继续抛类型化错误；
 *  - 不实现 Agent loop / Workflow / HITL / retry（属 P11.3+）。
 */
class PlanningExecutionUseCase(
    private val taskManager: TaskManagerUseCases,
    private val assembly: PlanningContextAssembly,
    private val planner: PlannerAgent,
    private val chapterRepository: ChapterRepository,
    private val continuationResolver: ContinuationResolver,
    errorMapper: ErrorMapper,
    /** P20-P5：Decision 政策入口（Application orchestration 调用；Planner 只消费，不重算）。 */
    private val decisionModel: com.qianyan.application.usecase.decision.DecisionModelGateway? = null,
) : UseCase(errorMapper) {

    /**
     * 执行一个 PLANNING Task 到 COMPLETED / FAILED，并返回产出 [ChapterPlan]（chapterId 非空）。
     * Task 类型非 PLANNING → [ApplicationError.InvalidOperation]；不存在 → TaskNotFound。
     *
     * P12.1.3：可选 [continuationReference] 表达"从指定 source Chapter 的指定 source Final Draft 继续"。
     *  - null → 无续篇来源（第一章）；
     *  - 非 null → 在调用 PlannerAgent 之前经 [ContinuationResolver] 确定性校验/解析（FINAL Draft、
     *    Chapter/Draft lineage、Novel / Variant / Original-Variant 隔离），校验失败 → 类型化错误，绝不执行 Agent。
     *
     * P12.1.7：可选 [targetChapterId] 让规划绑定到**已存在**的 Chapter（Android 章节写作链对既有章节规划）。
     *  - null → 维持既有行为（plan.chapterId 为空时经 bindChapter 新建下一章）；
     *  - 非 null → bindChapter 复用该既有 Chapter（校验归属/scope，不新建），确保 plan 与既有章节 identity 一致。
     */
    fun execute(
        taskId: TaskId,
        request: UserWritingRequest,
        continuationReference: ContinuationReference? = null,
        targetChapterId: ChapterId? = null,
        /**
         * P20-P5（FD-4）：本任务使用的 [com.qianyan.model.decision.DecisionPolicy]。
         *  - null → 新 Task/Attempt：经 [decisionModel] 决定一次并快照（无 gateway 时为空政策）；
         *  - 非 null → **恢复/复用**（Resume）：绝不重新 decide。
         */
        decisionPolicies: List<com.qianyan.model.decision.DecisionPolicy>? = null,
    ): ChapterPlan {
        val task = taskManager.findById(taskId)
        if (task.type != TaskType.PLANNING) {
            throw ApplicationException(
                ApplicationError.InvalidOperation("Task ${taskId.value} 类型 ${task.type} 不是 PLANNING，无法执行规划"),
            )
        }

        taskManager.start(taskId)
        try {
            val resolved = continuationReference?.let { continuationResolver.resolve(request, it) }
            val context = assembly.assemble(request, continuationReference, resolved)
            // P20-P5：Resume → 复用传入政策；新 Task → decide 一次（不重复决定）。
            val policies = decisionPolicies ?: decidePolicy(context)
            val plan = planner.plan(context, policies)
            val planWithChapter = bindChapter(plan, targetChapterId)
            taskManager.saveCheckpoint(taskId, PlanningSnapshot.STAGE, PlanningSnapshot.encode(planWithChapter, policies))
            taskManager.complete(taskId)
            return planWithChapter
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
     * P20-P5：为本次规划决定政策（仅在新 Task/新 Attempt 调用）。输入来自 [PlanningContext.authorContext]
     * （唯一 Author 入口）；未装配 gateway 或缺 AuthorContext → 空政策（Planner 自行渲染为空）。
     */
    private fun decidePolicy(context: PlanningContext): List<com.qianyan.model.decision.DecisionPolicy> {
        val gateway = decisionModel ?: return emptyList()
        val authorContext = context.authorContext ?: return gateway.decide(com.qianyan.model.author.AuthorContext())
        return gateway.decide(
            authorContext,
            com.qianyan.model.decision.DecisionType.STORY_DIRECTION,
            com.qianyan.model.decision.DecisionType.WRITING_STYLE,
        )
    }

    /**
     * P0-4/P12.0.1/P12.1.7：把 [ChapterPlan] 绑定到真实 Chapter。
     *  - [targetChapterId] 非 null → 校验并复用该既有 Chapter（不新建）；
     *  - plan.chapterId 存在 → 校验该 Chapter 归属（novel/variant/scope 必须与 plan 一致，否则跨实体污染 → 类型化拒绝）；
     *  - 否则 → 经 [ChapterRepository.createNextChapter] **原子创建**（事务内计算 order，避免并发重复 order）。
     * 返回的 ChapterPlan.chapterId 非空。
     */
    private fun bindChapter(plan: ChapterPlan, targetChapterId: ChapterId? = null): ChapterPlan {
        val requestedId = targetChapterId ?: plan.chapterId
        val existing = requestedId?.let { guard { chapterRepository.findById(it) } }
        val chapter = existing?.also { ensureOwnership(plan, it) } ?: run {
            val now = Clock.System.now()
            val created = Chapter(
                chapterId = ChapterId(nextId()),
                novelId = plan.novelId,
                variantId = plan.variantId,
                scope = plan.scope,
                title = plan.chapterGoal.take(CHAPTER_TITLE_LIMIT),
                order = 0, // createNextChapter 在事务内赋值
                status = ChapterStatus.PLANNED,
                createdAt = now,
                updatedAt = now,
            )
            guard { chapterRepository.createNextChapter(created) }
        }
        return if (plan.chapterId == chapter.chapterId) plan else plan.copy(chapterId = chapter.chapterId)
    }

    /** P12.0.1 P2：plan 与 chapter 的作用域/归属必须一致；不一致即跨实体污染，类型化拒绝。 */
    private fun ensureOwnership(plan: ChapterPlan, chapter: com.qianyan.model.story.Chapter) {
        val mismatches = buildList {
            if (chapter.novelId != plan.novelId) add("novel")
            if (chapter.variantId != plan.variantId) add("variant")
            if (chapter.scope != plan.scope) add("scope")
        }
        if (mismatches.isNotEmpty()) {
            throw ApplicationException(
                ApplicationError.VariantMismatch(
                    "Chapter(${chapter.chapterId.value}) 不属于 Plan(${plan.chapterPlanId.value}) 的作用域（不一致: ${mismatches.joinToString()}）",
                ),
            )
        }
    }

    /** 从 PLANNING Checkpoint 恢复 [ChapterPlan]（只读恢复上下文，不重新执行）。 */
    fun chapterPlanFrom(checkpoint: Checkpoint): ChapterPlan? = PlanningSnapshot.decode(checkpoint.snapshot)

    /**
     * P20-P5：从 PLANNING Checkpoint 恢复 [com.qianyan.model.decision.DecisionPolicy] 快照。
     * @return `null` = 该 Checkpoint 无政策快照（早于 P5）→ Resume 调用方**不得静默重新 decide**。
     */
    fun decisionPoliciesFrom(checkpoint: Checkpoint): List<com.qianyan.model.decision.DecisionPolicy>? =
        PlanningSnapshot.decodePolicies(checkpoint.snapshot)

    private fun describe(error: ApplicationError): String = when (error) {
        is ApplicationError.UnknownStorage -> "UnknownStorage: ${error.cause.message ?: error.cause::class.simpleName}"
        else -> error.toString()
    }

    private companion object {
        /** Chapter 标题由章节目标截取（不引入第二套命名）。 */
        const val CHAPTER_TITLE_LIMIT: Int = 40
    }
}