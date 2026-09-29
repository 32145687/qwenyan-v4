package com.qianyan.storage.repository

import com.qianyan.model.ProjectId
import com.qianyan.model.TaskId
import com.qianyan.model.taskqueue.TaskQueueItem
import com.qianyan.model.taskqueue.TaskQueueItemId
import com.qianyan.model.taskqueue.TaskQueueItemStatus
import com.qianyan.storage.db.QianyanDb
import kotlinx.datetime.Instant

/** [TaskQueueRepository] 的 SQLDelight + SQLite 实现（I13）。 */
class SqliteTaskQueueRepository(
    private val db: QianyanDb,
) : TaskQueueRepository {

    override fun insert(item: TaskQueueItem) {
        val row = StorageMappers.domainTaskQueueItem(item)
        db.transaction {
            runCatching {
                db.taskQueueQueries.insertTaskQueueItem(
                    queue_item_id = row.queue_item_id,
                    task_id = row.task_id,
                    project_id = row.project_id,
                    kind = row.kind,
                    priority = row.priority,
                    status = row.status,
                    queued_at = row.queued_at,
                    available_at = row.available_at,
                    attempt = row.attempt,
                    max_attempts = row.max_attempts,
                    paused = row.paused,
                    cancel_requested = row.cancel_requested,
                    claimed_by = row.claimed_by,
                    claimed_at = row.claimed_at,
                    last_error = row.last_error,
                )
            }.onFailure { throw mapWriteError(it) }
        }
    }

    override fun getById(itemId: TaskQueueItemId): TaskQueueItem? =
        db.taskQueueQueries.getTaskQueueItemById(itemId.value).executeAsOneOrNull()
            ?.let { StorageMappers.dbTaskQueueItem(it) }

    override fun getByTask(taskId: TaskId): TaskQueueItem? =
        db.taskQueueQueries.getTaskQueueItemByTask(taskId.value).executeAsOneOrNull()
            ?.let { StorageMappers.dbTaskQueueItem(it) }

    override fun listByProject(projectId: ProjectId): List<TaskQueueItem> =
        db.taskQueueQueries.listTaskQueueItemsByProject(projectId.value).executeAsList()
            .map { StorageMappers.dbTaskQueueItem(it) }

    /**
     * 原子领取：单条条件 UPDATE（仅 status=QUEUED 且 paused=0 时生效，SQLite 语句级原子），
     * 随后读回校验 `claimed_by` 确为本 Worker ⇒ 并发下失败者不会覆盖成功者的 claim。
     */
    override fun tryClaim(itemId: TaskQueueItemId, workerId: String, claimedAt: Instant): TaskQueueItem? =
        db.transactionWithResult {
            db.taskQueueQueries.claimTaskQueueItem(
                claimed_status = TaskQueueItemStatus.CLAIMED.name,
                worker_id = workerId,
                claimed_at = claimedAt.toEpochMilliseconds(),
                queue_item_id = itemId.value,
                queued_status = TaskQueueItemStatus.QUEUED.name,
            )
            val row = db.taskQueueQueries.getTaskQueueItemById(itemId.value).executeAsOneOrNull()
                ?: return@transactionWithResult null
            val item = StorageMappers.dbTaskQueueItem(row)
            if (item.status == TaskQueueItemStatus.CLAIMED && item.claimedBy == workerId) item else null
        }

    override fun updateScheduling(item: TaskQueueItem) {
        val row = StorageMappers.domainTaskQueueItem(item)
        db.transaction {
            runCatching {
                db.taskQueueQueries.updateTaskQueueItemScheduling(
                    priority = row.priority,
                    status = row.status,
                    available_at = row.available_at,
                    attempt = row.attempt,
                    max_attempts = row.max_attempts,
                    paused = row.paused,
                    cancel_requested = row.cancel_requested,
                    claimed_by = row.claimed_by,
                    claimed_at = row.claimed_at,
                    last_error = row.last_error,
                    queue_item_id = row.queue_item_id,
                )
            }.onFailure { throw mapWriteError(it) }
        }
    }

    override fun delete(itemId: TaskQueueItemId) {
        db.transaction {
            db.taskQueueQueries.deleteTaskQueueItem(itemId.value)
        }
    }

    override fun listClaimedBefore(threshold: Instant): List<TaskQueueItem> =
        db.taskQueueQueries.listClaimedBefore(
            claimed_status = TaskQueueItemStatus.CLAIMED.name,
            threshold = threshold.toEpochMilliseconds(),
        ).executeAsList().map { StorageMappers.dbTaskQueueItem(it) }

    private fun mapWriteError(e: Throwable): Throwable {
        val msg = e.message.orEmpty()
        return when {
            msg.contains("UNIQUE", ignoreCase = true) -> UniqueConflictException("违反唯一约束: $msg")
            msg.contains("constraint", ignoreCase = true) -> UniqueConflictException("违反约束: $msg")
            msg.contains("immutable", ignoreCase = true) -> OriginalImmutableException()
            else -> e
        }
    }
}