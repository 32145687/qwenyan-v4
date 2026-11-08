package com.qianyan.application.usecase.writing.knowledgeupdate

import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import com.qianyan.model.NovelId
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
    private const val KEY_NOVEL = "novelId"

    /**
     * 把 [ValidatedKnowledgeUpdate] 编码为 Checkpoint 可见的结构化 JsonObject（含 type 标签）。
     *
     * O-08：写入 `novelId`（顶层键），供"按作品查最近一次 KU 状态"；旧快照无该键，
     * 读取侧视为 null（[decodePayload] / [decode] 均不崩）。
     */
    fun encode(result: ValidatedKnowledgeUpdate, novelId: NovelId): JsonObject = buildJsonObject {
        put(KEY_TYPE, STAGE)
        put(KEY_NOVEL, novelId.value)
        put(KEY_RESULT, json.encodeToJsonElement(SnapshotDto.serializer(), SnapshotDto.from(result)))
    }

    /** 从 Checkpoint.snapshot 解码 [ValidatedKnowledgeUpdate]。缺失 / 非该 stage / 结构非法 → null。 */
    fun decode(snapshot: JsonObject?): ValidatedKnowledgeUpdate? = decodePayload(snapshot)?.result

    /**
     * 解码完整快照（结果 + 归属作品）。
     *
     * 兼容旧格式：无 `novelId` 键 → [SnapshotPayload.novelId] 为 null，[SnapshotPayload.result] 照常解码。
     */
    fun decodePayload(snapshot: JsonObject?): SnapshotPayload? {
        if (snapshot == null) return null
        if ((snapshot[KEY_TYPE]?.jsonPrimitive?.contentOrNull ?: "") != STAGE) return null
        val el = snapshot[KEY_RESULT] ?: return null
        val result = try {
            json.decodeFromJsonElement<SnapshotDto>(el).toResult()
        } catch (e: SerializationException) {
            return null
        } catch (e: IllegalArgumentException) {
            return null
        }
        val novelId = snapshot[KEY_NOVEL]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let(::NovelId)
        return SnapshotPayload(novelId = novelId, result = result)
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

/** 解码后的 KU 快照：确定性校验结果 + 归属作品（旧快照无 novelId → null）。 */
data class SnapshotPayload(
    val novelId: NovelId?,
    val result: ValidatedKnowledgeUpdate,
)