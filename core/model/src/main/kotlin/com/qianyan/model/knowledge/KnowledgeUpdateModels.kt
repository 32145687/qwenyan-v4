package com.qianyan.model.knowledge

import com.qianyan.model.NovelId
import com.qianyan.model.VariantId
import com.qianyan.model.VariantScope
import kotlinx.serialization.Serializable

/**
 * 候选知识变化（P11.5）。
 *
 * **不是已固化的知识**：仅表达"LLM 提议从写作产物中提取、可能应用到 Story World / Memory 状态的知识变化"。
 * 它是**候选**，必须经确定性 Validation / Apply 后才能沉淀；绝不作为 canon 事实直接采纳。
 *
 * 与既有领域模型的分工：
 *  - [KnowledgeEntry]：已固化的知识事实（无 operation，表达静态知识）。本模型补足"变化 + 受控 operation"的候选语义。
 *  - [com.qianyan.model.memory.MemoryEntry]：已持久化的记忆条目。本模型在 Apply 前是待定候选，不经入库。
 *
 * operation 使用受控枚举 [KnowledgeOperation]（ADD / UPDATE / REMOVE），禁止自由字符串。
 * target 为不稳定字符串标识（当前无实体 ID 体系），由确定性校验在 Apply 前判定；scope/variantId 携带作用域，
 * 用于 immutable canon 保护（对 Original 的 UPDATE/REMOVE 应被确定性拒绝）。
 */
@Serializable
data class CandidateKnowledgeChange(
    val changeId: String,
    val novelId: NovelId,
    val variantId: VariantId? = null,
    val scope: VariantScope = VariantScope.ORIGINAL,
    val operation: KnowledgeOperation,
    /** 目标主题：人物 / 地点 / 事件 / 世界规则等的人类可读标识。 */
    val target: String,
    val category: KnowledgeCategory = KnowledgeCategory.WORLD_RULE,
    /** 变化内容（ADD 为新增内容；UPDATE 为新值；REMOVE 为待移除目标的描述）。 */
    val content: String,
    val confidence: Float = 0.5f,
    /** 变化来源（Draft / Revision 的出处）。 */
    val source: String = "",
    /** LLM 给出的理由 / 依据（候选证据，非最终裁决）。 */
    val reason: String = "",
)

/** 候选知识变化的受控操作类型（P11.5）。 */
@Serializable
enum class KnowledgeOperation { ADD, UPDATE, REMOVE }