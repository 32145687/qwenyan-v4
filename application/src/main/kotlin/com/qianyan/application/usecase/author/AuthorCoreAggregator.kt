package com.qianyan.application.usecase.author

import com.qianyan.model.author.AuthorCoreCandidate
import com.qianyan.model.author.AuthorCoreStatus
import com.qianyan.model.author.AuthorEvidence
import com.qianyan.model.author.AuthorEvidenceType
import com.qianyan.model.author.AuthorObservationSource
import com.qianyan.model.author.Confidence
import kotlinx.datetime.Instant

/**
 * Author Core 聚合器（P17 · DEC-P17-005/006/015；P18-B · DEC-P18B-001/002/003/005）。
 *
 * 纯函数、确定性、可测试的证据聚合：
 *  - **weight（双维归一，DEC-P18B-001）**：`effectiveWeight = typeWeight × sourceWeight`。
 *    typeWeight 按 Evidence 类型 —— ADOPT=正、MODIFY=弱正、REJECT=负、PARTIAL_REWRITE=弱负、ADOPT_THEN_REVISE=净中性偏正；
 *    sourceWeight 按来源 —— FOUNDATION(高/稳定)、USER_DECISION、WRITING、REVISION、CRITIQUE(弱)。**禁止** `count * 0.1`。
 *    source 与 type 保持**独立两维**，不合并为单一枚举。
 *  - **recency**：越新越高；线性窗口 [RECENCY_WINDOW_DAYS]，下限 [RECENCY_FLOOR]。
 *  - **consistency**：正向/总数，**真正进入 confidence**（DEC-P18B-003）：consistency < 0.5（负向占多/内部不一致）时
 *    会扣减 confidence，非简单 count-based。
 *  - **contradiction**：前后主导方向相反 +1；**进入 confidence 惩罚**（DEC-P18B-003）用于候选降权/降级提示。
 *  - **confidence**：`base + weightedScore*STEP − inconsistencyPenalty − contradictionPenalty`，夹取 `[0, CEILING]`。
 *  - **min-observation**：UseCase 用 [MIN_OBSERVATION] 把关（单次行为不得直接成 Core）。
 *  - **Global 多书门槛**：[GLOBAL_MULTI_NOVEL_THRESHOLD]（DEC-P18B-005）。Global Core 晋升需要跨 ≥ 阈值本
 *    不同 Novel 的**有来源**证据；阈值在实现阶段保留可配置空间，数值标定留给 P19。
 *
 * 所有参数集中于此；权重数值标定暂缓到 P19（本类仅定义结构/规则机制）。
 */
object AuthorCoreAggregator {

    // ---- 集中参数（P17 最小可行值；P18 标定） ----
    /** 每种 Evidence 的加权贡献（正=正向，负=反向）。 */
    val TYPE_WEIGHT: Map<AuthorEvidenceType, Double> = mapOf(
        AuthorEvidenceType.ADOPT to 1.0,
        AuthorEvidenceType.MODIFY to 0.25,
        AuthorEvidenceType.REJECT to -1.0,
        AuthorEvidenceType.PARTIAL_REWRITE to -0.25,
        AuthorEvidenceType.ADOPT_THEN_REVISE to 0.5,
    )

    /** 每种来源的归一权重（DEC-P18B-001）：source 与 type 独立两维。 */
    val SOURCE_WEIGHT: Map<AuthorObservationSource, Double> = mapOf(
        AuthorObservationSource.FOUNDATION to 1.0,
        AuthorObservationSource.USER_DECISION to 0.9,
        AuthorObservationSource.WRITING to 0.8,
        AuthorObservationSource.REVISION to 0.6,
        AuthorObservationSource.CRITIQUE to 0.5,
    )

    /** 未识别来源的默认归一权重（中性，沿用旧行为）。 */
    const val DEFAULT_SOURCE_WEIGHT: Double = 1.0

    /** confidence 封顶。 */
    const val CONFIDENCE_CEILING: Double = 0.9

    /** 晋升长期 Core 所需最少观测。 */
    const val MIN_OBSERVATION: Int = 2

    /** Global Core 晋升所需最少不同 Novel 来源数（DEC-P18B-005；实现层可配置，标定留 P19）。 */
    const val GLOBAL_MULTI_NOVEL_THRESHOLD: Int = 2

    /** recency 线性衰减窗口（天）。 */
    const val RECENCY_WINDOW_DAYS: Double = 90.0

    /** recency 衰减下限。 */
    const val RECENCY_FLOOR: Double = 0.1

    /** confidence 对净加权分数的敏感度。 */
    const val CONFIDENCE_STEP: Double = 0.15

    /** 置信度基线。 */
    const val CONFIDENCE_BASE: Double = 0.5

    /** consistency < 0.5（负向占多/内部不一致）时对 confidence 的 penalty 敏感度（DEC-P18B-003；非 count-based）。 */
    const val CONSISTENCY_PENALTY: Double = 0.4

    /** 每单位 contradiction 的 confidence 惩罚（DEC-P18B-003，用于降权/降级提示）。 */
    const val CONTRADICTION_PENALTY: Double = 0.05

    /** 解析 evidence.source 字符串 → 来源迁移权重；未知来源返回空中性值。 */
    fun sourceWeightOf(source: String): Double {
        val token = source.substringAfter(':', source).trim().uppercase()
        val src = AuthorObservationSource.entries.firstOrNull { it.name == token }
        return src?.let { SOURCE_WEIGHT[it] } ?: DEFAULT_SOURCE_WEIGHT
    }

    /**
     * 把一条新 Evidence 聚合进既有 Candidate（或一个新建的空 Candidate）。
     *
     * @param existing 其余字段为空的候选壳；首条证据前由调用方创建。
     * @param observedAt 本条证据的发生时刻（用于 recency）。
     */
    fun aggregate(existing: AuthorCoreCandidate, evidence: AuthorEvidence, observedAt: Instant): AuthorCoreCandidate {
        val typeW = TYPE_WEIGHT[evidence.type] ?: 0.0
        val sourceW = sourceWeightOf(evidence.source)
        val w = typeW * sourceW

        val ageDays = maxOf(0.0, daysBetween(observedAt, evidence.observedAt))
        val recencyFactor = recencyFactor(ageDays)

        val prevScore = existing.weightedScore
        // 用既有候选 last-observed 到本条证据的间隔，对旧分数做 recency 衰减
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

        // 真正消费 consistency / contradiction（DEC-P18B-003）：
        //  - consistency < 0.5（负向占多/内部不一致）→ 扣减 confidence（非 count-based）；
        //  - 每单位 contradiction → 扣减 confidence（降权/降级信号）。
        val inconsistencyPenalty = maxOf(0.0, 0.5 - consistency) * CONSISTENCY_PENALTY
        val contradictionPenalty = contradictionCount * CONTRADICTION_PENALTY

        val confidence = Confidence(
            (CONFIDENCE_BASE + newScore * CONFIDENCE_STEP - inconsistencyPenalty - contradictionPenalty)
                .coerceIn(0.0, CONFIDENCE_CEILING),
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