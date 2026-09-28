package com.qianyan.model.project

import com.qianyan.model.ChapterId
import com.qianyan.model.NovelId
import com.qianyan.model.ProjectId
import com.qianyan.model.TaskId
import com.qianyan.model.VariantId
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/*
 * Novel IDE · I1：Project 聚合入口 + Project State（运行态）。
 *
 * 设计边界（依据 docs/architecture/qianyan-novel-ide-architecture.md §4 / §8）：
 *
 *  - Project **不是**第二套 Novel：身份锚仍是 `Novel.novelId`（唯一真源）+ `Novel.projectId`（既有字段，
 *    创建 Novel 时已分配）。Project 不复制 title / genre / synopsis / status / 世界观 / 章节 / 人物等任何数据；
 *    展示元数据请经既有 `NovelUseCases` 读取。
 *  - ProjectState 表示 **IDE / Agent 运行态**，与 **Novel World Model**（人物/事件/时间线/伏笔等小说事实）
 *    严格分离：运行态永不会成为小说事实（不进 Canonical）。
 *  - ProjectState **不是**第二套 Workflow/Task 状态机：它不复制
 *    PAUSED / CANCELLED / FAILED / WAITING_HUMAN / COMPLETED 等既有生命周期语义，
 *    只持有"当前工作在哪个 Project / Variant / Chapter / Task"的引用（I1 最小边界）。
 *  - 后续阶段挂接点（I1 **不实现**）：Agent Session / Activity Log / Context Engine / Project Index /
 *    Working Draft / Validation / Diff / Artifact / Commit / History / Queue。
 */

/**
 * Novel IDE 项目级聚合入口（I1 最小契约）。
 *
 * 组成：项目身份（[projectId]）+ 身份锚（[novelId]）+ 当前运行态（[state]）。
 * 不持有任何 Novel 元数据副本；`Project` 本身**不单独持久化**（见 ProjectUseCases 文档：
 * 身份来自 `Novel`，运行态来自 `ProjectState` 表）。
 */
@Serializable
data class Project(
    val projectId: ProjectId,
    val novelId: NovelId,
    val state: ProjectState,
)

/**
 * IDE / Agent 运行态（I1 最小契约）。
 *
 * 不变式：`ProjectState.novelId` 必须等于 `Novel(state.projectId).novelId`
 * （由 [com.qianyan.application.usecase.project.ProjectUseCases] 在装配/写入时校验）。
 *
 * 持久化：表 `ProjectState`（Schema v18，additive）。
 */
@Serializable
data class ProjectState(
    val projectId: ProjectId,
    val novelId: NovelId,
    /** IDE 当前工作分支；null = Original 作用域（与既有 [com.qianyan.model.core.VariantContext] 语义一致）。 */
    val activeVariantId: VariantId? = null,
    /** IDE 当前工作章节（"正在编辑第 N 章"）。 */
    val activeChapterId: ChapterId? = null,
    /** 当前任务的**引用**（不承载 Task 生命周期状态，状态仍以 Task / Checkpoint 为准 —— FD-2）。 */
    val activeTaskId: TaskId? = null,
    val updatedAt: Instant,
) {
    /** 是否处于 Original 作用域（与 VariantContext.isOriginal 同一语义，不新造概念）。 */
    val isOriginalScope: Boolean get() = activeVariantId == null
}