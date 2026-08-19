package com.qianyan.application.usecase.task

import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.application.error.ErrorMapper
import com.qianyan.application.usecase.UseCase
import com.qianyan.application.usecase.txt.TxtUseCases
import com.qianyan.engine.txt.TxtSource
import com.qianyan.model.TaskId
import com.qianyan.model.task.Task
import com.qianyan.model.task.TaskType
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
 *  - P8.3 仅支持 TaskType.IMPORT；ANALYSIS / WRITING / PLANNING / KNOWLEDGE_UPDATE 抛类型化
 *    [ApplicationError.UnsupportedTaskType]（不靠字符串判断错误）；
 *  - restoreCheckpoint 仍只是恢复上下文，本类不重新执行任务（P8.2 语义）。
 *
 * 明确范围外（P8.3 不做）：Agent / Tool / Orchestrator / Workflow / HITL / 真实 Provider /
 * retry / 异步执行 / 取消 / 超时 —— 属 P8.4+。
 */
class TaskRunner(
    private val taskManager: TaskManagerUseCases,
    private val txtUseCases: TxtUseCases,
    errorMapper: ErrorMapper,
) : UseCase(errorMapper) {

    /**
     * 执行受管任务（当前仅 IMPORT 有真实执行能力）。
     *
     * @param taskId 已创建（PENDING）的 Task
     * @param source TXT 原始字节（仅本次执行使用；snapshot 不保存 bytes）
     * @param title  导入标题
     * @return 执行结束后的 Task（COMPLETED / FAILED）
     * @throws ApplicationException 执行失败抛类型化错误；不支持的 TaskType 抛 UnsupportedTaskType
     */
    fun execute(taskId: TaskId, source: TxtSource, title: String = ""): Task {
        val task = taskManager.findById(taskId)
        return when (task.type) {
            TaskType.IMPORT -> executeImport(task, source, title)
            TaskType.ANALYSIS,
            TaskType.WRITING,
            TaskType.PLANNING,
            TaskType.KNOWLEDGE_UPDATE,
            -> throw ApplicationException(
                ApplicationError.UnsupportedTaskType("P8.3 尚不支持执行 ${task.type} 类型任务: ${taskId.value}"),
            )
        }
    }

    // ---- IMPORT：P8.3 唯一受管执行类型 ----

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
