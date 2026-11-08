package com.qianyan.application.usecase.writing.knowledgeupdate

import com.qianyan.application.error.ErrorMapper
import com.qianyan.application.usecase.UseCase
import com.qianyan.model.NovelId
import com.qianyan.model.task.TaskStatus
import com.qianyan.storage.repository.TaskRepository
import kotlinx.datetime.Instant

/**
 * O-08：按作品读取**最近一次**知识更新（KU）的执行状态（只读投影）。
 *
 * 数据来源：Checkpoint(stage=KNOWLEDGE_UPDATE) 的 snapshot（O-08 起写入 `novelId`）→ 该 checkpoint 的
 * Task.status / error。不新增表、不改 Schema；旧快照无 `novelId` ⇒ 无法归属到作品（跳过，不崩）。
 *
 * 边界：
 *  - 只有**已写出 Checkpoint** 的 KU 才能被定位（成功执行才写 Checkpoint；失败执行无快照 ⇒ 本视图无记录）；
 *  - 不使用 MemoryEntry 推断状态（记忆沉淀数量 ≠ 任务状态）。
 */
class KnowledgeUpdateStatusUseCases(
    private val taskRepository: TaskRepository,
    errorMapper: ErrorMapper,
) : UseCase(errorMapper) {

    /**
     * 最近一次属于 [novelId] 的 KU 记录；无记录返回 null。
     *
     * @param scanLimit 只扫描最近 N 条 KU Checkpoint（避免全表解码）；默认 100。
     */
    fun latestForNovel(novelId: NovelId, scanLimit: Long = DEFAULT_SCAN_LIMIT): LatestKnowledgeUpdate? {
        val hit = guard { taskRepository.findCheckpointsByStage(KnowledgeUpdateSnapshot.STAGE, scanLimit) }
            .asSequence()
            .mapNotNull { cp -> KnowledgeUpdateSnapshot.decodePayload(cp.snapshot)?.let { cp to it } }
            .firstOrNull { (_, payload) -> payload.novelId == novelId }
            ?: return null
        val (checkpoint, payload) = hit
        val task = guard { taskRepository.findById(checkpoint.taskId) } ?: return null
        return LatestKnowledgeUpdate(
            status = task.status,
            at = checkpoint.createdAt,
            accepted = payload.result.accepted.size,
            rejected = payload.result.rejected.size,
            error = task.error,
        )
    }

    private companion object {
        const val DEFAULT_SCAN_LIMIT = 100L
    }
}

/** 最近一次 KU 的只读摘要（状态来自 Task，采纳/拒绝数来自 Checkpoint 快照）。 */
data class LatestKnowledgeUpdate(
    val status: TaskStatus,
    val at: Instant,
    val accepted: Int,
    val rejected: Int,
    val error: String?,
)
