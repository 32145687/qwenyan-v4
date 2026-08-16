package com.qianyan.model.task

import com.qianyan.model.CheckpointId
import com.qianyan.model.TaskId
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/*
 * Task / Checkpoint（架构 §18）。
 * TaskStatus 即架构 §23.3 的 TaskState 族（一个用户任务的生命周期）。
 * Checkpoint.snapshot 用结构化 JsonObject，避免 Map<String, Any> 充当领域模型。
 */

@Serializable
enum class TaskType { ANALYSIS, WRITING, PLANNING, KNOWLEDGE_UPDATE, IMPORT }

@Serializable
enum class TaskStatus { PENDING, RUNNING, PAUSED, CANCELLED, COMPLETED, FAILED }

/** 用户任务的启停生命周期（= 架构 §18 Task；§23.3 的 TaskState 族）。 */
@Serializable
data class Task(
    val taskId: TaskId,
    val type: TaskType,
    val status: TaskStatus = TaskStatus.PENDING,
    val progress: Float = 0f,            // 0.0-1.0
    val checkpoint: Checkpoint? = null,  // 最近检查点
    val revisionCount: Int = 0,          // 修订次数（上限 3）
    val error: String? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/** 检查点（架构 §18.2）：恢复所需上下文快照。 */
@Serializable
data class Checkpoint(
    val checkpointId: CheckpointId,
    val taskId: TaskId,
    val stage: String,                  // 恢复点：阶段标识
    val snapshot: JsonObject? = null,   // 恢复所需的上下文快照（结构化 JSON）
)