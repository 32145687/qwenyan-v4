package com.qianyan.storage.repository

import com.qianyan.model.ProjectId
import com.qianyan.model.TaskId
import com.qianyan.model.taskqueue.TaskQueueItem
import com.qianyan.model.taskqueue.TaskQueueItemId
import kotlinx.datetime.Instant

/**
 * I13 · 后台任务队列持久化仓储（**调度条目**，不是第二个 Task）。
 *
 * 职责边界：
 *  - 只持久化调度元数据（status = QUEUED / CLAIMED、priority、attempt、lease、取消请求）；
 *    Task 的生命周期仍由既有 [TaskRepository] / `TaskManagerUseCases` 承载；
 *  - [tryClaim] 必须是**原子**的：仅当条目仍为 QUEUED 且未暂停时才能被领取，
 *    失败返回 null（绝不覆盖其他 Worker 的 claim，§7 / §27）；
 *  - 不承载业务数据（不复制 Novel / Chapter / Draft）；输入一律由上层按引用查询。
 */
interface TaskQueueRepository {

    /** 插入一个调度条目（`task_id` 唯一 ⇒ 同一 Task 至多一条，重复入队由存储层兜底）。 */
    fun insert(item: TaskQueueItem)

    /** 按条目 ID 查询；不存在返回 null。 */
    fun getById(itemId: TaskQueueItemId): TaskQueueItem?

    /** 按 Task 查询调度条目（重复入队检测）；不存在返回 null。 */
    fun getByTask(taskId: TaskId): TaskQueueItem?

    /** 某 Project 的全部调度条目（确定性排序由上层 [com.qianyan.model.taskqueue.TaskQueueOrdering] 决定）。 */
    fun listByProject(projectId: ProjectId): List<TaskQueueItem>

    /**
     * **原子**领取：仅当条目仍为 QUEUED 且未暂停时置为 CLAIMED 并记录 worker / lease。
     *
     * @return 领取成功返回最新条目；若已被他人领取 / 已暂停 / 不存在则返回 null。
     */
    fun tryClaim(itemId: TaskQueueItemId, workerId: String, claimedAt: Instant): TaskQueueItem?

    /** 仅更新调度字段（task_id / project_id / kind / queued_at 插入后不可变）。 */
    fun updateScheduling(item: TaskQueueItem)

    /** 删除调度条目（出队：完成 / 终止失败 / 立即取消）。 */
    fun delete(itemId: TaskQueueItemId)

    /** 崩溃恢复候选：仍处于 CLAIMED 且 lease 不晚于 [threshold] 的条目（§25）。 */
    fun listClaimedBefore(threshold: Instant): List<TaskQueueItem>
}