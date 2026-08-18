package com.qianyan.storage.repository

import com.qianyan.model.TaskId
import com.qianyan.model.task.Checkpoint
import com.qianyan.model.task.Task
import com.qianyan.storage.db.QianyanDb

/** [TaskRepository] 的 SQLDelight + SQLite JDBC 实现。 */
class SqliteTaskRepository(
    private val db: QianyanDb,
) : TaskRepository {

    override fun create(task: Task): TaskId {
        val row = StorageMappers.domainTask(task)
        db.transaction {
            runCatching {
                db.taskQueries.insertTask(
                    row.task_id, row.type, row.status, row.progress,
                    row.revision_count, row.error, row.created_at, row.updated_at,
                )
                task.checkpoint?.let { cp ->
                    val cpRow = StorageMappers.domainCheckpoint(cp)
                    db.taskQueries.insertCheckpoint(
                        cpRow.checkpoint_id, cpRow.task_id, cpRow.revision,
                        cpRow.stage, cpRow.snapshot, cpRow.created_at,
                    )
                }
            }.onFailure { throw mapWriteError(it) }
        }
        return task.taskId
    }

    override fun findById(taskId: TaskId): Task? {
        val row = db.taskQueries.getTaskById(taskId.value).executeAsOneOrNull() ?: return null
        val latest = findLatestCheckpoint(taskId)
        return StorageMappers.dbTask(row, latest)
    }

    override fun update(task: Task) {
        val row = StorageMappers.domainTask(task)
        db.transaction {
            runCatching {
                db.taskQueries.updateTask(
                    type = row.type, status = row.status, progress = row.progress,
                    revision_count = row.revision_count, error = row.error,
                    updated_at = row.updated_at, task_id = row.task_id,
                )
            }.onFailure { throw mapWriteError(it) }
        }
    }

    override fun delete(taskId: TaskId) {
        db.transaction {
            db.taskQueries.deleteCheckpointsByTask(taskId.value)
            db.taskQueries.deleteTask(taskId.value)
        }
    }

    override fun saveCheckpoint(checkpoint: Checkpoint) {
        if (checkpoint.revision !in 1..MAX_REVISIONS) {
            throw RevisionLimitExceededException("Checkpoint revision=${checkpoint.revision} 超出上限 $MAX_REVISIONS")
        }
        val cpRow = StorageMappers.domainCheckpoint(checkpoint)
        val taskRow = db.taskQueries.getTaskById(checkpoint.taskId.value).executeAsOneOrNull()
            ?: throw TaskNotFoundException("Task ${checkpoint.taskId.value} 不存在，无法保存 Checkpoint")
        db.transaction {
            runCatching {
                db.taskQueries.insertCheckpoint(
                    cpRow.checkpoint_id, cpRow.task_id, cpRow.revision,
                    cpRow.stage, cpRow.snapshot, cpRow.created_at,
                )
                // 同事务同步 Task.revisionCount 与 updatedAt，避免 Task 表与 Checkpoint 表不一致。
                db.taskQueries.updateTask(
                    type = taskRow.type, status = taskRow.status, progress = taskRow.progress,
                    revision_count = cpRow.revision, error = taskRow.error,
                    updated_at = cpRow.created_at, task_id = taskRow.task_id,
                )
            }.onFailure { throw mapWriteError(it) }
        }
    }

    override fun findCheckpoints(taskId: TaskId): List<Checkpoint> =
        db.taskQueries.selectCheckpointsByTask(taskId.value).executeAsList()
            .map { StorageMappers.dbCheckpoint(it) }

    override fun findLatestCheckpoint(taskId: TaskId): Checkpoint? =
        db.taskQueries.selectLatestCheckpoint(taskId.value).executeAsOneOrNull()
            ?.let { StorageMappers.dbCheckpoint(it) }

    private fun mapWriteError(e: Throwable): Throwable {
        val msg = e.message.orEmpty()
        return when {
            msg.contains("UNIQUE", ignoreCase = true) -> UniqueConflictException("违反唯一约束: $msg")
            msg.contains("constraint", ignoreCase = true) -> UniqueConflictException("违反约束: $msg")
            msg.contains("immutable", ignoreCase = true) -> OriginalImmutableException()
            msg.contains("Variant base", ignoreCase = true) -> VariantBaseViolation()
            else -> e
        }
    }

    private companion object {
        /** P8 Preflight 冻结：revisionCount <= 3。 */
        const val MAX_REVISIONS = 3
    }
}
