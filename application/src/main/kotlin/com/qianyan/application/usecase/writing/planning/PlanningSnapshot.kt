package com.qianyan.application.usecase.writing.planning

import com.qianyan.application.usecase.decision.DecisionPolicySnapshot
import com.qianyan.model.decision.DecisionPolicy
import com.qianyan.model.story.ChapterPlan
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * PLANNING Checkpoint 快照编解码（P11.2；P20-P5 增补 DecisionPolicy 快照）。
 *
 * 复用 P8 的 [com.qianyan.model.task.Checkpoint.snapshot]（JsonObject）承载 [ChapterPlan]
 * （不做新的 Database Schema / migration）。encode 时显式落 defaults，保证 restore 可完整还原。
 *
 * P20-P5（FD-4）：可选写入本任务使用的 [DecisionPolicy] 快照（键 [DecisionPolicySnapshot.KEY_TYPE]），
 * 供 Resume 恢复政策而不重新 decide。旧 Checkpoint 无该键 → [decodePolicies] 返回 null（向后兼容）。
 */
object PlanningSnapshot {

    const val STAGE = "PLANNING"
    private const val KEY_TYPE = "type"
    private const val KEY_PLAN = "chapterPlan"

    /** 把 [ChapterPlan] 编码为 Checkpoint 可见的结构化 JsonObject（含 type 标签）。 */
    fun encode(plan: ChapterPlan): JsonObject = encode(plan, emptyList())

    /** P20-P5：编码 [ChapterPlan] + 本任务使用的 [DecisionPolicy] 快照（空列表则不写入该键）。 */
    fun encode(plan: ChapterPlan, policies: List<DecisionPolicy>): JsonObject = buildJsonObject {
        put(KEY_TYPE, STAGE)
        put(KEY_PLAN, encodeJson.encodeToJsonElement(ChapterPlan.serializer(), plan))
        if (policies.isNotEmpty()) {
            put(DecisionPolicySnapshot.KEY_TYPE, DecisionPolicySnapshot.encode(policies))
        }
    }

    /**
     * 从 [com.qianyan.model.task.Checkpoint.snapshot] 解码 [ChapterPlan]。
     * snapshot 缺失 / 非 PLANNING / 结构非法 → 返回 null（调用方决定如何处理）。
     */
    fun decode(snapshot: JsonObject?): ChapterPlan? {
        if (snapshot == null) return null
        if ((snapshot[KEY_TYPE]?.jsonPrimitive?.contentOrNull ?: "") != STAGE) return null
        val planElement = snapshot[KEY_PLAN] ?: return null
        return try {
            encodeJson.decodeFromJsonElement(ChapterPlan.serializer(), planElement)
        } catch (e: SerializationException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    private val encodeJson = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    /**
     * P20-P5：从 PLANNING Checkpoint 恢复 [DecisionPolicy] 快照。
     * @return `null` = 该 Checkpoint 早于 P5（无政策快照）；列表 = 显式政策。调用方不得据此静默重新 decide。
     */
    fun decodePolicies(snapshot: JsonObject?): List<DecisionPolicy>? = DecisionPolicySnapshot.decode(snapshot)
}