package com.qianyan.application.usecase.decision

import com.qianyan.model.decision.DecisionOutcome
import com.qianyan.model.decision.DecisionPolicy
import com.qianyan.model.decision.DecisionPolicySource
import com.qianyan.model.decision.DecisionType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * P20-P5 · DecisionPolicy Checkpoint Snapshot 编解码（FD-4）。
 *
 * 目的：把 Planning/Writing 使用的 [DecisionPolicy] 快照写入 Checkpoint，使 Resume 时**恢复**政策
 * 而不是重新 `decide()`（保证恢复后行为确定性）。
 *
 * 边界：
 *  - **不修改 P19 Domain Contract**：本 codec 位于 Application 层，用枚举 `.name` 字符串承载，
 *    不给 DecisionType / DecisionOutcome / DecisionPolicy / DecisionPolicySource 添加 @Serializable。
 *  - 旧 Checkpoint 无该键 → [decode] 返回 `null`（表示"该 Checkpoint 早于 P5，无政策快照"），
 *    调用方据此**不得静默重新 decide**；显式空政策返回空列表。
 *  - 非法枚举名 → 该条丢弃；若全部非法 → 返回空列表（不抛错，不破坏旧数据读取）。
 */
object DecisionPolicySnapshot {

    const val KEY_TYPE = "decisionPolicies"
    private const val KEY_DECISION_TYPE = "decisionType"
    private const val KEY_OUTCOME = "outcome"
    private const val KEY_SOURCE = "source"

    /** 把政策列表编码为 Checkpoint 可见的 JsonArray（含来源枚举名）。 */
    fun encode(policies: List<DecisionPolicy>): JsonArray = buildJsonArray {
        policies.forEach { p ->
            add(
                buildJsonObject {
                    put(KEY_DECISION_TYPE, p.decisionType.name)
                    put(KEY_OUTCOME, p.outcome.name)
                    put(KEY_SOURCE, p.source.name)
                },
            )
        }
    }

    /** 把政策列表包装为命名 JsonObject（供 Checkpoint snapshot 顶层写入）。 */
    fun encodeAsObject(policies: List<DecisionPolicy>): JsonObject = buildJsonObject {
        put(KEY_TYPE, encode(policies))
    }

    private fun decodeOne(element: JsonObject): DecisionPolicy? {
        val type = runCatching { DecisionType.valueOf(element[KEY_DECISION_TYPE]?.jsonPrimitive?.contentOrNull ?: return null) }.getOrNull() ?: return null
        val outcome = runCatching { DecisionOutcome.valueOf(element[KEY_OUTCOME]?.jsonPrimitive?.contentOrNull ?: return null) }.getOrNull() ?: return null
        val source = runCatching { DecisionPolicySource.valueOf(element[KEY_SOURCE]?.jsonPrimitive?.contentOrNull ?: return null) }.getOrNull() ?: return null
        return DecisionPolicy(decisionType = type, outcome = outcome, source = source)
    }

    /**
     * 从 Checkpoint snapshot 恢复政策快照。
     * @return `null` = 该 snapshot 无政策快照（早于 P5 / 非本阶段产生）；列表 = 显式政策（可能为空）。
     */
    fun decode(snapshot: JsonObject?): List<DecisionPolicy>? {
        if (snapshot == null) return null
        val element = snapshot[KEY_TYPE] ?: return null
        val array = element as? JsonArray ?: return null
        return array.mapNotNull { (it as? JsonObject)?.let { o -> decodeOne(o) } }
    }
}