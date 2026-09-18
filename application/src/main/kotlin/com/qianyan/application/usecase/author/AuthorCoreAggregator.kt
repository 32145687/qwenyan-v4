package com.qianyan.application.usecase.author

import com.qianyan.model.author.AuthorCoreCandidate
import com.qianyan.model.author.AuthorCoreStatus
import com.qianyan.model.author.AuthorEvidence
import com.qianyan.model.author.AuthorEvidenceType
import com.qianyan.model.author.Confidence
import kotlinx.datetime.Instant

/**
 * Author Core 聚合器（P17 · DEC-P17-005/006/015）。
 *
 * 纯函数、确定性、可测试的证据聚合：
 *  - **weight**：按 Evidence 类型加权 —— ADOPT=正向、MODIFY=弱正(上下文相关)、REJECT=负向、
 *    PARTIAL_REWRITE=弱负、ADOPT_THEN_REVISE=净中性偏正。**禁止** `count * 0.1`。
 *  - **recency**：越新越高；用线性窗口 `1 - ageDays/window`，下限 [RECENCY_FLOOR]（简单、确定、无复杂模型）。
 *  - **consistency**：正向/总数，反映行为一致性。
 *  - **contradiction**：前后主导方向相反时 +1（DEC-006 反证）。
 *  - **confidence**：`0.5 + weightedScore*STEP`，夹取到 [0, CEILING]（封顶 0.9）。
 *  - **min-observation**：由 UseCase 用 [MIN_OBSERVATION] 门槛把关（单次行为不得直接成 Core）。
 *
 * 所有参数集中于此；权重标定暂缓到 P18，不引入复杂学习算法。
 */
object AuthorCoreAggregator {

    // ---- 集中参数（P17 最小可行值；P18 再标定） ----
    /** 每种 Evidence 的加权贡献（正=正向，负=反向；MODIFY/PARTIAL_REWRITE 不把用户行为简单当"喜欢"）。 */
    val TYPE_WEIGHT: Map<AuthorEvidenceType, Double> = mapOf(
        AuthorEvidenceType.ADOPT to 1.0,
        AuthorEvidenceType.MODIFY to 0.25,
        AuthorEvidenceType.REJECT to -1.0,
        AuthorEvidenceType.PARTIAL_REWRITE to -0.25,
        AuthorEvidenceType.ADOPT_THEN_REVISE to 0.5,
    )

    /** confidence 封顶（DEC-005）。 */
    const val CONFIDENCE_CEILING: Double = 0.9

    /** 晋升长期 Core 所需最少观测（DEC-P17-004/009：单次异常不成为核心）。 */
    const val MIN_OBSERVATION: Int = 2

    /** recency 线性衰减窗口（天）。 */
    const val RECENCY_WINDOW_DAYS: Double = 90.0

    /** recency 衰减下限（旧证据仍有残存影响，但不为零）。 */
    const val RECENCY_FLOOR: Double = 0.1

    /** confidence 对净加权分数的敏感度。 */
    const val CONFIDENCE_STEP: Double = 0.15

    /** 置信度基线。 */
    const val CONFIDENCE_BASE: Double = 0.5

    /**
     * 把一条新 Evidence 聚合进既有 Candidate（或一个新建的空 Candidate）。
     *
     * @param existing 其余字段为空的候选壳（candidateId/patternKey/scope/novelId/statement/condition/createdAt 已就绪）；
     *                首条证据前由调用方创建。
     * @param observedAt 本条证据的发生时刻（用于 recency）。
     */
    fun aggregate(existing: AuthorCoreCandidate, evidence: AuthorEvidence, observedAt: Instant): AuthorCoreCandidate {
        val w = TYPE_WEIGHT[evidence.type] ?: 0.0
        val ageDays = maxOf(0.0, daysBetween(observedAt, evidence.observedAt))
        val recencyFactor = recencyFactor(ageDays)

        val prevScore = existing.weightedScore
        // 用既有候选 last-observed 到本条证据的间隔，对旧分数做 recency 衰减（时间序内，旧的权重降低）
        val gapDays = maxOf(0.0, daysBetween(evidence.observedAt, existing.recency))
        val gapFactor = recencyFactor(gapDays)
        val decayedPrev = if (evidence.observedAt >= existing.recency) prevScore * gapFactor else prevScore
        val newScore = decayedPrev + w * recencyFactor

        val positive = existing.positiveEvidence + if (w > 0) 1 else 0
        val negative = existing.negativeEvidence + if (w < 0) 1 else 0
        val observationCount = existing.observationCount + 1
        val consistency = if (positive + negative == 0) 1.0 else positive.toDouble() / (positive + negative)

        // 反证/矛盾：旧主导方向 ∥ 新方向相反 → +1（DEC-006）
        val prevDominant = sign(prevScore)
        val contradictionIncrement =
            if (existing.observationCount > 0 && prevDominant != 0 && prevDominant != sign(w)) 1 else 0
        val contradictionCount = existing.contradictionCount + contradictionIncrement

        val confidence = Confidence(
            (CONFIDENCE_BASE + newScore * CONFIDENCE_STEP).coerceIn(0.0, CONFIDENCE_CEILING),
        )

        return existing.copy(
            positiveEvidence = positive,
            negativeEvidence = negative,
            observationCount = observationCount,
            weightedScore = newScore,
            consistency = consistency,
            recency = maxOf(existing.recency, evidence.observedAt),
            contradictionCount = contradictionCount,
            confidence = confidence,
            status = AuthorCoreStatus.CANDIDATE,
            updatedAt = observedAt,
        )
    }

    private fun recencyFactor(ageDays: Double): Double =
        (1.0 - ageDays / RECENCY_WINDOW_DAYS).coerceIn(RECENCY_FLOOR, 1.0)

    private fun daysBetween(from: Instant, to: Instant): Double =
        (from.epochSeconds - to.epochSeconds).toDouble() / 86400.0

    private fun sign(v: Double): Int = when {
        v > 0 -> 1
        v < 0 -> -1
        else -> 0
    }
}