package com.qianyan.application.usecase.writing

import com.qianyan.model.writing.Draft
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * WRITING Checkpoint 快照编解码（P11.3）。
 *
 * 复用 P8 的 [com.qianyan.model.task.Checkpoint.snapshot]（JsonObject）承载 [Draft]
 * （不做新的 Database Schema / migration）。encode 时显式落 defaults，保证 restore 可完整还原。
 * 保存 Draft 必要字段；正文不依赖 MemoryEntry，也不新增数据库表保存 checkpoint。
 */
object WritingSnapshot {

    const val STAGE = "WRITING"
    private const val KEY_TYPE = "type"
    private const val KEY_DRAFT = "draft"

    /** 把 [Draft] 编码为 Checkpoint 可见的结构化 JsonObject（含 type 标签）。 */
    fun encode(draft: Draft): JsonObject = buildJsonObject {
        put(KEY_TYPE, STAGE)
        put(KEY_DRAFT, encodeJson.encodeToJsonElement(Draft.serializer(), draft))
    }

    /**
     * 从 [com.qianyan.model.task.Checkpoint.snapshot] 解码 [Draft]。
     * snapshot 缺失 / 非 WRITING / 结构非法 → 返回 null（调用方决定如何处理）。
     */
    fun decode(snapshot: JsonObject?): Draft? {
        if (snapshot == null) return null
        if ((snapshot[KEY_TYPE]?.jsonPrimitive?.contentOrNull ?: "") != STAGE) return null
        val draftElement = snapshot[KEY_DRAFT] ?: return null
        return try {
            encodeJson.decodeFromJsonElement(Draft.serializer(), draftElement)
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