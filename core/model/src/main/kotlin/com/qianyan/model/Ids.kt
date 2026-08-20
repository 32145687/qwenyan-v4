package com.qianyan.model

import kotlinx.serialization.Serializable

/*
 * 全局唯一强类型 ID。
 *
 * 决策（V4.2，[DECIDED]）：
 *  - ID 全局唯一（UUID 风格），不是 scope + localId。
 *  - scope 是实体属性（VariantScope），不是 ID 的一部分。
 *  - Override 以 (targetEntityId, variantId) 作为逻辑唯一键，targetEntityId 即这里的全局 ID。
 */

/** 小说（Original 基座）ID */
@JvmInline
@Serializable
value class NovelId(val value: String)

/** Variant 唯一 ID */
@JvmInline
@Serializable
value class VariantId(val value: String)

/** 基准原文 ID（Variant 派生来源），值通常等于其 Original 的 NovelId */
@JvmInline
@Serializable
value class BaseNovelId(val value: String)

@JvmInline
@Serializable
value class ProjectId(val value: String)

@JvmInline
@Serializable
value class CharacterId(val value: String)

@JvmInline
@Serializable
value class StateId(val value: String) // CharacterState

@JvmInline
@Serializable
value class CharacterArcId(val value: String)

@JvmInline
@Serializable
value class CharacterArcProgressId(val value: String)

@JvmInline
@Serializable
value class RelationshipId(val value: String)

@JvmInline
@Serializable
value class WorldId(val value: String)

@JvmInline
@Serializable
value class WorldRuleId(val value: String)

@JvmInline
@Serializable
value class LocationId(val value: String)

@JvmInline
@Serializable
value class EventId(val value: String)

@JvmInline
@Serializable
value class TimelineEntryId(val value: String)

@JvmInline
@Serializable
value class KnowledgeId(val value: String)

@JvmInline
@Serializable
value class EvidenceId(val value: String)

@JvmInline
@Serializable
value class MemoryEntryId(val value: String)

@JvmInline
@Serializable
value class ArcId(val value: String) // StoryArc

@JvmInline
@Serializable
value class ActId(val value: String)

@JvmInline
@Serializable
value class ChapterId(val value: String)

@JvmInline
@Serializable
value class ChapterPlanId(val value: String)

@JvmInline
@Serializable
value class SceneId(val value: String)

@JvmInline
@Serializable
value class ScenePlanId(val value: String)

@JvmInline
@Serializable
value class BeatId(val value: String)

@JvmInline
@Serializable
value class StoryConflictId(val value: String)

@JvmInline
@Serializable
value class ForeshadowingId(val value: String)

@JvmInline
@Serializable
value class PayoffId(val value: String)

@JvmInline
@Serializable
value class VocabularyId(val value: String)

@JvmInline
@Serializable
value class VocabularyEntryId(val value: String)

@JvmInline
@Serializable
value class VocabularyRuleId(val value: String)

@JvmInline
@Serializable
value class VocabularyCandidateId(val value: String)

@JvmInline
@Serializable
value class OverrideId(val value: String)

@JvmInline
@Serializable
value class TaskId(val value: String)

@JvmInline
@Serializable
value class CheckpointId(val value: String)

@JvmInline
@Serializable
value class UserId(val value: String)

@JvmInline
@Serializable
value class ConfirmationId(val value: String)

@JvmInline
@Serializable
value class ConflictId(val value: String)

@JvmInline
@Serializable
value class AgentId(val value: String)

@JvmInline
@Serializable
value class RequestId(val value: String)

@JvmInline
@Serializable
value class BackupId(val value: String)

@JvmInline
@Serializable
value class SchemaVersion(val value: String)

/* ---- P11 创作写作（正文草稿）；与 TXT 原始文本结构不同源 ---- */

/** 创作草稿正文 ID（P11 写作 Pipeline 产物；≠ TXT 原始文本结构）。 */
@JvmInline
@Serializable
value class DraftId(val value: String)

/* ---- P4 TXT Pipeline（原始文本结构聚合，与创作规划 Chapter 不同源） ---- */

/** TXT 文档 ID（导入后作为 Original Source 持久化）。 */
@JvmInline
@Serializable
value class TxtDocumentId(val value: String)

/** TXT 结构化章节 ID（原始文本切分结果；≠ 创作规划的 ChapterId）。 */
@JvmInline
@Serializable
value class TxtChapterId(val value: String)

/** TXT 段落块 ID（章节内的稳定文本顺序单元）。 */
@JvmInline
@Serializable
value class TextBlockId(val value: String)