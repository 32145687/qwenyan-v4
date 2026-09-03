package com.qianyan.application.usecase.writing.revision

import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.model.task.Task

/**
 * Revision Gate（P11.4）：确定性的修订门控规则。
 *
 * **不是 Agent、不交给 LLM 判断**：`revisionCount < 3` 才允许修订，否则类型化拒绝。
 *
 * 复用 P8 revision 语义：
 *  - revision 计数唯一来源是 [Task.revisionCount]（P8 Domain / TaskManager / DB CHECK 三重强制，上限 3）；
 *  - 本 Gate 只**读取**该计数并前置短路（达上限时在调用 LLM 之前就拒绝），**不新增第二套 counter / 上限**；
 *  - 真正的 upper bound 兜底由 [com.qianyan.application.usecase.task.TaskManagerUseCases.saveCheckpoint]
 *    （`nextRevision = revisionCount + 1`，`> MAX_REVISIONS` 抛 [ApplicationError.RevisionLimitExceeded]）保证，
 *    本 Gate 的阈值与 P8 `MAX_REVISIONS = 3` 严格对齐。
 *
 * 达上限后必须类型化失败，绝不进入无限自动修订循环。
 */
object RevisionGate {

    /** 与 P8 TaskManagerUseCases.MAX_REVISIONS 对齐（单一语义：revision 上限 = 3）。 */
    const val MAX_REVISIONS = 3

    /** 当前 [task] 是否仍允许继续修订。 */
    fun isAllowed(task: Task): Boolean = task.revisionCount < MAX_REVISIONS

    /**
     * 达上限则抛 [ApplicationError.RevisionNotAllowed]；否则返回（允许修订）。
     * 达上限时**不调用** WriterAgent / LLM。
     */
    fun requireAllowed(task: Task) {
        val count = task.revisionCount
        if (count >= MAX_REVISIONS) {
            throw ApplicationException(
                ApplicationError.RevisionNotAllowed(
                    "Task ${task.taskId.value} revisionCount=$count 已达上限 $MAX_REVISIONS，不能再修订",
                ),
            )
        }
    }
}