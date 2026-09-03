package com.qianyan.application.usecase.writing.knowledgeupdate

import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * KNOWLEDGE_UPDATE Checkpoint 快照编解码（P11.5）。
 *
 * 复用 P8 的 [com.qianyan.model.task.Checkpoint.snapshot]（JsonObject）承载确定性的
 * [ValidatedKnowledgeUpdate]（accepted / rejected 候选），供 reopen / 审计恢复。
 * 不新增数据库表保存知识更新状态（沉淀结果落 MemoryEntry 表，校验快照落本 snapshot）。
 */
object KnowledgeUpdateSnapshot {

    const val STAGE = "KNOWLEDGE_UPDATE"

    private const val KEY_TYPE = "type"
    private const val KEY_RESULT = "result"

    /** 把 [ValidatedKnowledgeUpdate] 编码为 Checkpoint 可见的结构化 JsonObject（含 type 标签）。 */
    fun encode(result: ValidatedKnowledgeUpdate): JsonObject = buildJsonObject {
        put(KEY_TYPE, STAGE)
        put(KEY_RESULT, json.encodeToJsonElement(SnapshotDto.serializer(), SnapshotDto.from(result)))
    }

    /**
     * 从 Checkpoint.snapshot 解码 [ValidatedKnowledgeUpdate]。缺失 / 非该 stage / 结构非法 → null。
     */
    fun decode(snapshot: JsonObject?): ValidatedKnowledgeUpdate? {
        if (snapshot == null) return null
        if ((snapshot[KEY_TYPE]?.jsonPrimitive?.contentOrNull ?: "") != STAGE) return null
        val el = snapshot[KEY_RESULT] ?: return null
        return try {
            val dto = json.decodeFromJsonElement<SnapshotDto>(el)
            dto.toResult()
        } catch (e: SerializationException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    /** snapshot 持久化用的可序列化 DTO（避免把内部 Result 结构直接序列化）。 */
    @Serializable
    private data class SnapshotDto(
        val accepted: List<com.qianyan.model.knowledge.CandidateKnowledgeChange> = emptyList(),
        val rejected: List<RejectedDto> = emptyList(),
    ) {
        companion object {
            fun from(r: ValidatedKnowledgeUpdate): SnapshotDto = SnapshotDto(
                accepted = r.accepted,
                rejected = r.rejected.map { RejectedDto(it.change, it.reason) },
            )
        }

        fun toResult(): ValidatedKnowledgeUpdate = ValidatedKnowledgeUpdate(
            accepted = accepted,
            rejected = rejected.map { RejectedChange(it.change, it.reason) },
        )
    }

    @Serializable
    private data class RejectedDto(
        val change: com.qianyan.model.knowledge.CandidateKnowledgeChange,
        val reason: String,
    )
}