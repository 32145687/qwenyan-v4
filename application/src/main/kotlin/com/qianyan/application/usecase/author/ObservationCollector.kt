package com.qianyan.application.usecase.author

import com.qianyan.model.AuthorEvidenceId
import com.qianyan.model.AuthorObservationId
import com.qianyan.model.author.AuthorCoreScope
import com.qianyan.model.author.AuthorEvidenceType
import com.qianyan.model.author.AuthorObservation
import com.qianyan.storage.repository.AuthorObservationRepository

/**
 * P18-A · AuthorObservation Collector —— Author Intelligence Feedback Loop 的**Application 层观测 seam**（DEC-P18-002）。
 *
 * 边界（DEC-P18-001/002/006）：
 *  - **不属于 Agent / Orchestrator / Repository / UI**；位于 Application。生产路径（Writer/Revision/Critique/Foundation）
 *    在各自的 Application 决策边界处通过本 Collector 提交 Observation，而这些 Agent **不直接依赖任何 AuthorRepository**。
 *  - 严格 `Observation ≠ Evidence`：本 Collector 先持久化 Observation，再派生 **0..N** 条 AuthorEvidence
 *    （本阶段默认 1:1：一个观测决策 → 一条同语义 Evidence，证据 ID 由 observationId 确定性推导）。
 *  - **幂等**：observationId 为 deterministic 键——重复提交同一 Observation 不重复持久化、不重复聚合、不产生新学习结果。
 *    二次防线：派生 Evidence 的 evidenceId 确定性由 observationId 推导，即使绕过 observation 判重，也会被
 *    [AuthorCoreUseCases.recordCoreEvidence] 的 evidenceId 去重拦截（P17-015）。
 *  - **闸门保留**：本 Collector 只自动推进 `Observation → Evidence → Candidate`；**不得自动晋升 Stable AuthorCore**
 *    （晋升仍须 [AuthorCoreUseCases.confirmCoreCandidate] 的 User Confirmation，DEC-P18-006）。复用 P17
 *    [AuthorCoreAggregator]，本阶段编写**不**包含、也不重建第二套学习系统。
 *  - scope 约束：仅 GLOBAL / NOVEL 进入长期学习聚合；CONTEXT Observation 仅持久化、不聚合（不固化，DEC-P17-008）。
 *  - Observation **不携带 Story 正文/StoryState/Foundation/NovelKnowledge**，仅保存归属元数据。
 */
class ObservationCollector constructor(
    private val observations: AuthorObservationRepository,
    private val coreUseCases: AuthorCoreUseCases,
) {

    /**
     * 记录一次作者创作决策观测并派生证据聚合进 Core Candidate。
     *
     * @return [Result]：是否新观测 + 本次实际新增应用的证据数。重复提交返回 `newObservation=false, applied=0`。
     */
    fun record(observation: AuthorObservation): Result {
        // 幂等判重（deterministic ID）
        if (observations.exists(observation.observationId)) {
            return Result(newObservation = false, appliedEvidence = 0)
        }
        observations.upsert(observation)

        val derived = deriveEvidence(observation)
        var applied = 0
        for (e in derived) {
            val ok = coreUseCases.recordCoreEvidence(
                type = e.type,
                scope = observation.scope,
                novelId = observation.novelId,
                evidenceId = e.evidenceId,
                detail = e.detail,
                source = "observation:" + observation.source.name.lowercase(),
            )
            if (ok) applied++
        }
        return Result(newObservation = true, appliedEvidence = applied)
    }

    /** 从单个 Observation 派生 0..N 条 Evidence。本阶段默认 1:1；CONTEXT scope 不派生（不进入长期学习）。 */
    private fun deriveEvidence(observation: AuthorObservation): List<DerivedEvidence> {
        if (observation.scope == AuthorCoreScope.CONTEXT) return emptyList()
        return listOf(
            DerivedEvidence(
                // 确定性 evidenceId：observationId 派生（幂等二次防线）
                evidenceId = AuthorEvidenceId("obs-" + observation.observationId.value + "-0"),
                type = observation.decisionType,
                detail = "observation:" + observation.source.name.lowercase(),
            ),
        )
    }

    /** 采集结果。 */
    data class Result(
        val newObservation: Boolean,
        val appliedEvidence: Int,
    )

    private data class DerivedEvidence(
        val evidenceId: AuthorEvidenceId,
        val type: AuthorEvidenceType,
        val detail: String,
    )
}