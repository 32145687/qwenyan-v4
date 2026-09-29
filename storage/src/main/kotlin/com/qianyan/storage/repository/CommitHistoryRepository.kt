package com.qianyan.storage.repository

import com.qianyan.model.ProjectId
import com.qianyan.model.change.ChangeArtifactId
import com.qianyan.model.commit.CommitHistoryEntry
import com.qianyan.model.commit.CommitHistoryId

/**
 * I10 · Commit History 仓储（Canonical Commit / Revert 的**不可变**审计记录）。
 *
 * 职责：**只追加 + 只读**。刻意不提供 update / delete —— 历史不可变，
 * Revert 也必须产生**新**记录而不是修改 / 删除旧记录（§10）。
 *
 * 跨表原子性：本仓储的单表读写与 Canonical 正文写入必须处于**同一事务**，
 * 由上层 [com.qianyan.application.usecase.commit.CommitUseCases] 复用既有
 * [WorkflowRepository.inTransaction]（同一 `QianyanDb(driver)` ⇒ 真实跨仓储 DB 事务）编排。
 */
interface CommitHistoryRepository {

    /** 追加一条历史（只插入；`history_id` 为主键）。 */
    fun append(entry: CommitHistoryEntry)

    /** 按 historyId 读取；不存在返回 null。 */
    fun getById(historyId: CommitHistoryId): CommitHistoryEntry?

    /** 按来源 Artifact 读取（重复 Commit 检测）；无返回 null。 */
    fun getByArtifact(artifactId: ChangeArtifactId): CommitHistoryEntry?

    /** 某 Project 的全部历史（**插入序** = 真实发生时序；确定性，依赖 rowid 而非时间戳精度）。 */
    fun listByProject(projectId: ProjectId): List<CommitHistoryEntry>
}