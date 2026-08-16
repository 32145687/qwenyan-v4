package com.qianyan.model

import kotlinx.serialization.Serializable

/*
 * 共有枚举与小型值类型。
 *
 * VariantScope 是本次 V4.2 的关键语义：
 *  - [ORIGINAL]：原文/基座，Immutable，只读（存储层写保护在 P2 落实）。
 *  - [VARIANT]：由 Original 派生，可写；修改通过 EntityOverride 表达。
 * scope 是实体属性；ID 本身不携带 scope。
 */

/** 实体作用域：隶属于 Original 基座（只读），或某个 Variant（可写）。 */
@Serializable
enum class VariantScope { ORIGINAL, VARIANT }

@Serializable
enum class ProjectSource { ORIGINAL_NOVEL, FROM_IDEA, DERIVED }

@Serializable
enum class ProjectStatus { DRAFT, ACTIVE, COMPLETED }

/** Arc 状态（StoryArc / CharacterArc 共用语义）。 */
@Serializable
enum class ArcStatus { PLANNED, ACTIVE, COMPLETED, ABANDONED }

@Serializable
enum class IntentType { CONTINUE, PLAN, REWRITE, EXPAND, ANALYZE, CUSTOM }

@Serializable
enum class PlanningScope { SCENE, CHAPTER, ARC, NOVEL }

@Serializable
enum class ParticipantRole { ACTOR, OBSERVER, VICTIM, BENEFICIARY }

@Serializable
enum class TimeType { ABSOLUTE, RELATIVE, CHAPTER_BASED, UNKNOWN }

@Serializable
enum class RevealLevel { FULL, PARTIAL, HIDDEN }

/** CharacterState 的来源标记（用于审计）。 */
@Serializable
enum class StateSource { ORIGINAL, GENERATED, INFERRED, USER }

@Serializable
enum class CharacterRoleInScene { PROTAGONIST, ANTAGONIST, SUPPORT, CROWD }

/** 场景引用的地点（值对象）。 */
@Serializable
data class LocationRef(val locationId: LocationId, val description: String = "")

/** 场景中人物及其角色。 */
@Serializable
data class CharacterRole(val characterId: CharacterId, val role: CharacterRoleInScene = CharacterRoleInScene.SUPPORT)

/** 剧情节奏描述（覆盖粒度，P1 Pacing 完整计算 TBD）。 */
@Serializable
data class PacingProfile(val style: String = "", val note: String = "")

/** 转折点（StoryArc 与 CharacterArc 共用）。 */
@Serializable
data class TurningPoint(
    val position: String = "",
    val content: String = "",
    val impact: String = "",
)