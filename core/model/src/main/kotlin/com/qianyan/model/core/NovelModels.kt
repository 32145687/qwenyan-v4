package com.qianyan.model.core

import com.qianyan.model.BaseNovelId
import com.qianyan.model.ConfirmationId
import com.qianyan.model.NovelId
import com.qianyan.model.OverrideId
import com.qianyan.model.ProjectId
import com.qianyan.model.ProjectSource
import com.qianyan.model.ProjectStatus
import com.qianyan.model.UserId
import com.qianyan.model.VariantId
import com.qianyan.model.VariantScope
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/*
 * Novel / NovelVariant 领域模型（V4.2 Hybrid 决策落地）。
 *
 * 核心语义：
 *  - Novel = Original 基座（Immutable，只读）。scope=ORIGINAL。
 *  - NovelVariant = 从 Original 派生（可写）。默认继承 Original。
 *  - 修改通过 EntityOverride 表达（INHERIT / OVERRIDE / ADD / REMOVE）。
 *  - v1 只允许 Variant 从 Original 创建；禁止 Variant→Variant（深度限制见 VariantScopeSpec 注释）。
 */

/** Original 基座作品。Immutable：其数据（字符/知识/结构）scope=ORIGINAL。 */
@Serializable
data class Novel(
    val novelId: NovelId,
    val projectId: ProjectId,
    val title: String,
    val source: ProjectSource = ProjectSource.ORIGINAL_NOVEL,
    val genre: List<String> = emptyList(),
    val synopsis: String = "",
    val scope: VariantScope = VariantScope.ORIGINAL,
    val status: ProjectStatus = ProjectStatus.DRAFT,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    /** Novel 作为基座始终只读。 */
    val isOriginal: Boolean get() = true
}

/** Variant 状态。 */
@Serializable
enum class VariantStatus { DRAFT, ACTIVE, COMPLETED, ARCHIVED, ABANDONED }

/**
 * Variant 由 Original 派生的可写分支。
 * baseNovelId 指向被作为基准的 Original（Immutable，不被修改）。
 */
@Serializable
data class NovelVariant(
    val variantId: VariantId,
    val novelId: NovelId,
    val baseNovelId: BaseNovelId,
    val projectId: ProjectId,
    val name: String,
    val status: VariantStatus = VariantStatus.DRAFT,
    val blueprint: VariantBlueprint? = null,
    val scopeSpec: VariantScopeSpec? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/**
 * Variant 的故事结构蓝图（增量表达，非整树复制）——
 * 由 Variant 自有结构 + 指向 Original 未改节点的引用组成。
 */
@Serializable
data class VariantBlueprint(
    /** Variant 自有的结构根节点引用（Arc 级）。 */
    val arcRefs: List<StructureRef> = emptyList(),
    val note: String = "",
)

/** 对某结构节点的一处引用（可指向 Original 未改节点，或 Variant 自有节点）。 */
@Serializable
data class StructureRef(
    val nodeRef: com.qianyan.model.story.OriginalNodeRef,
    val isOwned: Boolean = false, // false = 引用 Original 未改节点；true = Variant 自有
)

/** 重构范围规格：用户要求保留 / 重写哪些。P1 仅承载声明，解析器在后续 Phase。 */
@Serializable
data class VariantScopeSpec(
    val keepEntityIds: List<String> = emptyList(),
    val rewriteEntityIds: List<String> = emptyList(),
    val directive: String = "",
) {
    // 限制注释：v1 仅支持 Original → Variant 单层；Variant→Variant 为本阶段 [TBD-6] 明确禁止。
}

/** Override 操作：表达 "继承/覆盖/新增/删除"。 */
@Serializable
enum class OverrideOperation { INHERIT, OVERRIDE, ADD, REMOVE }

/** Override 可作用的实体种类。 */
@Serializable
enum class OverridableKind {
    CHARACTER, CHARACTER_STATE, CHARACTER_ARC, RELATIONSHIP,
    WORLD, WORLD_RULE, KNOWLEDGE, EVENT, TIMELINE_ENTRY,
    STORY_ARC, ACT, CHAPTER, CHAPTER_PLAN, SCENE, SCENE_PLAN, BEAT,
    VOCABULARY_ENTRY, MEMORY,
}

/**
 * 实体级 Override 载体（V4.2 Hybrid 的核心）。
 * 逻辑唯一键：(targetId, variantId)。
 * 读取规则：override 命中 → 用 Override；否则读穿透 Original。
 * 字段级 Override 为 [TBD-1]；这里采用实体级。replacedValue 用结构化 JSON，避免 Map<String,Any> 充当领域模型。
 */
@Serializable
data class EntityOverride(
    val overrideId: OverrideId,
    val variantId: VariantId,
    val targetKind: OverridableKind,
    /** 目标全局唯一 ID；ADD 时为占位/待分配 ID。 */
    val targetId: String,
    val operation: OverrideOperation,
    /** OVERRIDE / ADD 时的新值（实体级）；字段级见 TBD-1。 */
    val replacedValue: JsonElement? = null,
    val note: String = "",
)

/**
 * 当前 Variant 作用域载体：Agent / Tool / Context Pipeline 在调用链中携带。
 * - Original：variantId = null，scope = ORIGINAL。
 * - Variant：variantId = 当前 Variant，scope = VARIANT。
 * Agent 不应自行判断属于哪个 Variant。
 */
@Serializable
data class VariantContext(
    val baseNovelId: BaseNovelId,
    val variantId: VariantId? = null,
    val scope: VariantScope =
        if (variantId == null) VariantScope.ORIGINAL else VariantScope.VARIANT,
) {
    val isOriginal: Boolean get() = scope == VariantScope.ORIGINAL
}

/* ---- Creative Project / Manifest / Backup（V1 既有，保持并纳入 variant 关系） ---- */

@Serializable
data class CreativeProject(
    val projectId: ProjectId,
    val manifest: ProjectManifest,
    val schemaVersion: String,
    val source: ProjectSource,
    val originalNovelId: NovelId? = null,
    val rootVariantId: VariantId? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
)

@Serializable
data class ProjectManifest(
    val title: String,
    val genre: List<String> = emptyList(),
    val pov: String? = null,
    val synopsis: String = "",
    val status: ProjectStatus = ProjectStatus.DRAFT,
)

@Serializable
data class BackupPackage(
    val schemaVersion: String,
    val packageType: String = "BACKUP",
    val exportedAt: Instant,
    val exportedBy: UserId? = null,
    val content: JsonElement,
)