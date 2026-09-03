package com.qianyan.application.usecase.writing.critique

import com.qianyan.model.spec.ValidationResult
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * CRITIQUE Checkpoint 快照编解码（P11.4）。
 *
 * 复用 P8 的 [com.qianyan.model.task.Checkpoint.snapshot]（JsonObject）承载 [ValidationResult]
 * （不做新的 Database Schema / migration）。encode 时显式落 defaults，保证 restore 可完整还原。
 * 不新增数据库表保存评审结果。
 */
object CritiqueSnapshot {

    const val STAGE = "CRITIQUE"

    private const val KEY_TYPE = "type"
    private const val KEY_RESULT = "result"

    /** 把 [ValidationResult] 编码为 Checkpoint 可见的结构化 JsonObject（含 type 标签）。 */
    fun encode(result: ValidationResult): JsonObject = buildJsonObject {
        put(KEY_TYPE, STAGE)
        put(KEY_RESULT, encodeJson.encodeToJsonElement(ValidationResult.serializer(), result))
    }

    /**
     * 从 [com.qianyan.model.task.Checkpoint.snapshot] 解码 [ValidationResult]。
     * snapshot 缺失 / 非 CRITIQUE / 结构非法 → 返回 null（调用方决定如何处理）。
     */
    fun decode(snapshot: JsonObject?): ValidationResult? {
        if (snapshot == null) return null
        if ((snapshot[KEY_TYPE]?.jsonPrimitive?.contentOrNull ?: "") != STAGE) return null
        val resultElement = snapshot[KEY_RESULT] ?: return null
        return try {
            encodeJson.decodeFromJsonElement(ValidationResult.serializer(), resultElement)
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
}