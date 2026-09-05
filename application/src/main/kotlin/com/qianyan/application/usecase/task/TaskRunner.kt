package com.qianyan.application.usecase.task

import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.application.error.ErrorMapper
import com.qianyan.application.usecase.UseCase
import com.qianyan.application.usecase.txt.TxtUseCases
import com.qianyan.application.usecase.writing.WritingExecutionUseCase
import com.qianyan.application.usecase.writing.critique.CritiqueExecutionUseCase
import com.qianyan.application.usecase.writing.knowledgeupdate.KnowledgeUpdateExecutionUseCase
import com.qianyan.application.usecase.writing.knowledgeupdate.KnowledgeUpdateOutcome
import com.qianyan.application.usecase.writing.planning.PlanningExecutionUseCase
import com.qianyan.application.usecase.writing.revision.RevisionExecutionUseCase
import com.qianyan.engine.txt.TxtSource
import com.qianyan.model.TaskId
import com.qianyan.model.ChapterId
import com.qianyan.model.context.UserWritingRequest
import com.qianyan.model.spec.ValidationResult
import com.qianyan.model.story.ChapterPlan
import com.qianyan.model.story.ContinuationReference
import com.qianyan.model.task.Task
import com.qianyan.model.task.TaskType
import com.qianyan.model.writing.Draft
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Task 执行驱动（P8.3）：在 P8.2 TaskManager 状态机保护下，真正驱动既有同步 UseCase 执行受管任务。
 *
 * 职责边界：
 *  - 只复用 [TaskManagerUseCases] 的生命周期 API（start / saveCheckpoint / complete / fail），
 *    不直接 `task.copy(status=...)` 或调用 Repository，状态转换一律经状态机；
 *  - 执行成功：PENDING → RUNNING → checkpoint（输入/输出上下文） → COMPLETED；
 *  - 执行失败：PENDING → RUNNING → fail → FAILED（记录错误，继续抛出类型化错误）；
 *  - 执行上下文经 [Checkpoint.snapshot]（JsonObject）保存：输入仅存元信息（title / source 显示名），
 *    不持久化 TxtSource 的 bytes（不改 core:model / 不加字段）；
 *  - 支持类型：IMPORT（字节源）、PLANNING（P11.2，经 [PlanningExecutionUseCase]）、
 *    WRITING（P11.3，经 [WritingExecutionUseCase]，专用入口 executeWriting）；
 *    ANALYSIS / KNOWLEDGE_UPDATE 仍抛类型化 [ApplicationError.UnsupportedTaskType]；
 *  - restoreCheckpoint 仍只是恢复上下文，本类不重新执行任务（P8.2 语义）。
 *
 * 明确范围外：Agent loop（在 AgentRuntime 中）/ Orchestrator / Workflow / HITL / 真实 Provider /
 * retry / 异步执行 / 取消 / 超时 —— 不属本类，也不属 P11.2 / P11.3。
 */
class TaskRunner(
    private val taskManager: TaskManagerUseCases,
    private val txtUseCases: TxtUseCases,
    private val planning: PlanningExecutionUseCase,
    private val writing: WritingExecutionUseCase,
    private val critique: CritiqueExecutionUseCase,
    private val revision: RevisionExecutionUseCase,
    private val knowledgeUpdate: KnowledgeUpdateExecutionUseCase,
    errorMapper: ErrorMapper,
) : UseCase(errorMapper) {

    /**
     * 执行字节源受管任务（当前仅 IMPORT 有真实执行能力）。
     *
     * @param taskId 已创建（PENDING）的 Task
     * @param source TXT 原始字节（仅本次执行使用；snapshot 不保存 bytes）
     * @param title  导入标题
     * @throws ApplicationException 执行失败抛类型化错误；非 IMPORT 字节源任务抛 UnsupportedTaskType
     */
    fun execute(taskId: TaskId, source: TxtSource, title: String = ""): Task {
        val task = taskManager.findById(taskId)
        return when (task.type) {
            TaskType.IMPORT -> executeImport(task, source, title)
            else -> throw ApplicationException(
                ApplicationError.UnsupportedTaskType("字节源执行入口仅支持 IMPORT 任务; ${task.type} 请走对应专用入口: ${taskId.value}"),
            )
        }
    }

    /**
     * 执行 PLANNING 受管任务（P11.2）：PENDING → start → PlanningExecutionUseCase
     * （Context Assembly → PlannerAgent → ChapterPlan → Checkpoint）→ COMPLETED / FAILED。
     * P12.1.3：可选 [continuationReference] 透传给 Planning（第一章传 null，第二章传显式来源）。
     * P12.1.7：可选 [targetChapterId] 让规划绑定到既有章节（Android 章节写作链）；null = 新建下一章。
     */
    fun executePlanning(
        taskId: TaskId,
        request: UserWritingRequest,
        continuationReference: ContinuationReference? = null,
        targetChapterId: ChapterId? = null,
    ): ChapterPlan =
        planning.execute(taskId, request, continuationReference, targetChapterId)

    /**
     * 执行 WRITING 受管任务（P11.3）：PENDING → start → WritingExecutionUseCase
     * （Context Assembly → WriterAgent → DraftParser → DraftRepository.save → WRITING Checkpoint）
     * → COMPLETED / FAILED。INPUT 为规划恢复的 [ChapterPlan]。
     */
    fun executeWriting(taskId: TaskId, request: UserWritingRequest, plan: ChapterPlan): Draft =
        writing.execute(taskId, request, plan)

    /**
     * 执行 WRITING Task 上的 Critique（P11.4）：CritiqueAgent → ValidationResult → CRITIQUE Checkpoint。
     * INPUT 为当前 [Draft]；不触发 Task 状态变迁（只读评审）。
     */
    fun executeCritique(taskId: TaskId, draft: Draft): ValidationResult =
        critique.execute(taskId, draft)

    /**
     * 执行 WRITING Task 上的 Revision（P11.4）：RevisionGate（revisionCount<3）→ RevisionAgent
     * → REVISED Draft 持久化 → REVISION Checkpoint（revisionCount+1）。INPUT 为当前 [Draft] + [ValidationResult]。
     * revision 达上限 → [ApplicationError.RevisionNotAllowed]，不调用 LLM。
     */
    fun executeRevision(taskId: TaskId, currentDraft: Draft, critiqueResult: ValidationResult): Draft =
        revision.execute(taskId, currentDraft, critiqueResult)

    /**
     * 执行 WRITING Task 上的 Knowledge Update（P11.5）：KnowledgeUpdateAgent → Parser → 确定性 Validate/Apply
     * → Memory 沉淀 → KNOWN_UPDATE Checkpoint。INPUT 为最终 [Draft]；显式单步入口，无自动串联循环。
     */
    fun executeKnowledgeUpdate(taskId: TaskId, draft: Draft): KnowledgeUpdateOutcome =
        knowledgeUpdate.execute(taskId, draft)

    // ---- IMPORT：字节源受管执行类型 ----

    /** PENDING → RUNNING → 真实执行 TxtUseCases.importTxtAsOriginal → checkpoint → COMPLETED / FAILED。 */
    private fun executeImport(task: Task, source: TxtSource, title: String): Task {
        // 1) PENDING → RUNNING（TaskManager 状态机校验；非 PENDING 在此抛出 InvalidTaskStateTransition 等）
        taskManager.start(task.taskId)
        try {
            // 2) 真实执行既有 UseCase（同步；TxtUseCases 已把 TxtException/Storage 归一为 ApplicationException）
            val output = guard { txtUseCases.importTxtAsOriginal(source, title) }
            // 3) 保存执行上下文（输入元信息 + 输出结果）
            taskManager.saveCheckpoint(
                task.taskId,
                stage = "IMPORT",
                snapshot = importSnapshot(title, source, output),
            )
            // 4) RUNNING → COMPLETED（终态）
            return taskManager.complete(task.taskId)
        } catch (e: ApplicationException) {
            // RUNNING → FAILED（记录失败原因；继续抛出原类型化错误）
            taskManager.fail(task.taskId, describe(e.error))
            throw e
        } catch (t: Throwable) {
            // 兜底：任何未归一异常经 ErrorMapper 映射后落 FAILED（不吞、不泄漏原始异常）
            val mapped = errorMapper.map(t)
            taskManager.fail(task.taskId, describe(mapped.error))
            throw mapped
        }
    }

    /** IMPORT 执行上下文最小快照：输入只存元信息（不存 bytes），输出为真实 TxtImportOutput 字段。 */
    private fun importSnapshot(title: String, source: TxtSource, output: TxtUseCases.TxtImportOutput): JsonObject =
        buildJsonObject {
            put("type", "IMPORT")
            put("input", buildJsonObject {
                put("title", title)
                put("source", source.displayName)
            })
            put("output", buildJsonObject {
                put("documentId", output.documentId.value)
                put("novelId", output.novelId.value)
                put("isDuplicate", output.isDuplicate)
                put("contentHash", output.contentHash)
                put("encoding", output.encoding.name)
                put("charCount", output.charCount)
                put("chapterCount", output.chapterCount)
                put("blockCount", output.blockCount)
            })
        }

    /** 把类型化错误渲染为 Task.error 记录文案（用于持久化失败原因；分类仍以类型为准，不经字符串判断）。 */
    private fun describe(error: ApplicationError): String = when (error) {
        is ApplicationError.UnknownStorage -> "UnknownStorage: ${error.cause.message ?: error.cause::class.simpleName}"
        else -> error.toString()
    }
}
