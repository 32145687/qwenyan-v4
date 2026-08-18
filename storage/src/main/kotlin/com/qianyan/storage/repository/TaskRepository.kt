package com.qianyan.storage.repository

import com.qianyan.model.TaskId
import com.qianyan.model.task.Checkpoint
import com.qianyan.model.task.Task

/**
 * Task / Checkpoint 持久化仓储（领域语义，不暴露 SQLDelight 生成类型）。
 *
 * P8.1 约定：
 *  - Task 表只表达当前 Task 领域模型所需信息；不提前加入 Agent / Tool / Orchestrator /
 *    Provider / Knowledge 专用字段（P8 Preflight 约束，属 P8.2+ 范围）。
 *  - Checkpoint 与 Task 显式关联（task_id + revision 唯一）；`Task.checkpoint` 为“最近检查点”
 *    的派生视图，读取时由 Checkpoint 表的最新 revision 填充，Task 表不冗余存储。
 *  - revision 上限（revisionCount <= 3）由存储层约束保证（DB CHECK + 仓储校验）；
 *    完整 Task 状态机管理属 P8.2（TaskManager），本层不实现。
 *  - create / update / saveCheckpoint / delete 均为单事务原子操作，不留半成品数据。
 */
interface TaskRepository {

    /** 创建 Task 持久化记录；若 `task.checkpoint` 非空则同事务落库。返回强类型 TaskId。 */
    fun create(task: Task): TaskId

    /** 按 ID 查询 Task（附带最近 Checkpoint）。不存在返回 null。 */
    fun findById(taskId: TaskId): Task?

    /** 更新 Task（全字段覆盖写入，含 status / progress / revisionCount / error / updatedAt）。 */
    fun update(task: Task)

    /** 删除 Task 及其全部 Checkpoint（单事务，不留孤儿 Checkpoint）。 */
    fun delete(taskId: TaskId)

    /** 保存一个 Checkpoint（校验 revision ∈ [1,3]），并同步 Task.revisionCount / updatedAt。 */
    fun saveCheckpoint(checkpoint: Checkpoint)

    /** 查询某 Task 的全部 Checkpoint，按 revision 升序。 */
    fun findCheckpoints(taskId: TaskId): List<Checkpoint>

    /** 查询某 Task 的最近 Checkpoint（revision 最大）。不存在返回 null。 */
    fun findLatestCheckpoint(taskId: TaskId): Checkpoint?
}
