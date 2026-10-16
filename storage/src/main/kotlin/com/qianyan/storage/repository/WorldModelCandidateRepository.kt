package com.qianyan.storage.repository

import com.qianyan.model.worldmodel.CandidateFact
import com.qianyan.model.worldmodel.CandidateFactStatus

/**
 * Phase 3 · 候选事实仓储（CandidateFact，v28；v3.1 §12.8/12.9）。
 *
 * 只服务 World Model Update Pipeline：候选的持久化、按 Commit 反查（幂等重放）与人审队列查询。
 * 候选**不是** World Model 事实本体；ACCEPTED 落地由 Pipeline 经 [StoryStateRepository] 写六表。
 */
interface WorldModelCandidateRepository {

    fun save(candidate: CandidateFact)

    fun getById(candidateId: String): CandidateFact?

    /** Commit 维度反查（幂等重放判定：同一 history_id 的候选不重复抽取）。 */
    fun listByHistory(historyId: String): List<CandidateFact>

    fun listByStatus(status: CandidateFactStatus): List<CandidateFact>

    /** 生命周期迁移（PENDING → 终态；同事务内由 Pipeline 调用）。 */
    fun updateStatus(
        candidateId: String,
        status: CandidateFactStatus,
        decisionReason: String?,
        conflictWith: String?,
        resultingFactId: String?,
    )
}
