package com.qianyan.storage.repository

import com.qianyan.model.NovelId
import com.qianyan.model.ProjectId
import com.qianyan.model.project.ProjectState

/**
 * Project State 持久化仓储（I1 · FD-9）。
 *
 * 只承担"IDE / Agent 当前工作在哪个 Project 的哪个 Variant / Chapter / Task"的**运行态引用**读写。
 *
 * 明确不做（属后续阶段）：
 *  - 不承载 Workflow / Task 生命周期状态（仍在 Task / Workflow / Checkpoint 表 —— FD-2）；
 *  - 不存 Agent Session / Activity Log / Working Draft / Artifact / Commit 历史（I3 及以后）；
 *  - 不复制 Novel 元数据，不复制世界观 / 章节 / 人物数据。
 */
interface ProjectStateRepository {

    /** 保存/覆盖某 Project 的运行态（幂等：同 projectId 覆盖，不产生第二行）。 */
    fun save(state: ProjectState)

    /** 读取某 Project 的运行态；尚未建立返回 null（调用方按默认运行态处理，不伪造进度）。 */
    fun get(projectId: ProjectId): ProjectState?

    /** 按身份锚读取运行态（projectId ↔ novelId 为 1:1 聚合键；无记录返回 null）。 */
    fun getByNovel(novelId: NovelId): ProjectState?
}