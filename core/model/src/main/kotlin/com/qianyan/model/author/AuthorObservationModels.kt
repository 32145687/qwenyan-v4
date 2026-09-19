package com.qianyan.model.author

import com.qianyan.model.AuthorObservationId
import com.qianyan.model.NovelId
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/*
 * P18-A · AuthorObservation 领域模型（纯领域，无 storage / provider / agent / UI 依赖）。
 *
 * 冻结语义（DEC-P18-001/003/006）：
 *  - **Observation ≠ Evidence**。Observation 是一次真实作者创作**决策**（accept / rewrite / reject / 局部修改 /
 *    先采纳后修改）的持久化对象；一个 Observation 可派生 **0..N** 条 Evidence（本阶段默认 1:1）。
 *  - **不携带 Story 内容**：禁止保存正文 / StoryState / StoryFoundation / NarrativeState / NovelKnowledge；
 *    仅可保存定位/归属元数据（novelId、scope、source、decision metadata）。
 *  - **不复用新的长期枚举体系**：决策语义直接复用 [AuthorEvidenceType] 的 5 类（MODIFY/ADOPT/REJECT/
 *    PARTIAL_REWRITE/ADOPT_THEN_REVISE），区分来源用独立的 [AuthorObservationSource]（DEC-P18-003 允许 source 维度）。
 *  - **deterministic ID**：observationId 为稳定键，提交幂等；重复提交不允许重复产生学习结果。
 *  - observation 仅自动产出 Evidence → Candidate；**不得自动晋升 Stable AuthorCore**（DEC-P18-006）。
 */

/** 观察来源阶段（用于区分多源证据归属，不新增 EvidenceType）。 */
@Serializable
enum class AuthorObservationSource {
    /** P15 Foundation 决策。 */
    FOUNDATION,

    /** Writing / 正文接受。 */
    WRITING,

    /** Revision / 修订。 */
    REVISION,

    /** Critique / 评审。 */
    CRITIQUE,

    /** 其他显式用户创作决策。 */
    USER_DECISION,
}

/**
 * 一次真实的作者创作决策观测。
 *
 * @param observationId 确定性 ID（幂等键；同一用户行为仅允许存在一份）。
 * @param scope 作者作用域（GLOBAL / NOVEL；CONTEXT 不进入长期学习）。
 * @param decisionType 该观测的决策语义，直接复用 [AuthorEvidenceType] 5 类。
 * @param novelId NOVEL scope 时该书 ID；GLOBAL 为 null。
 * @param source 来源阶段（FOUNDATION/WRITING/REVISION/CRITIQUE/USER_DECISION）。
 * @param metadata 仅非内容性归属元数据（如 revisionCount、task 提示词长度等），禁止正文。
 * @param occurredAt 用户决策发生时间。
 * @param createdAt 记录入库时间。
 */
@Serializable
data class AuthorObservation(
    val observationId: AuthorObservationId,
    val scope: AuthorCoreScope,
    val decisionType: AuthorEvidenceType,
    val novelId: NovelId? = null,
    val source: AuthorObservationSource,
    val metadata: Map<String, String> = emptyMap(),
    val occurredAt: Instant,
    val createdAt: Instant = occurredAt,
)