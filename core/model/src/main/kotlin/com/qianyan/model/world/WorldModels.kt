package com.qianyan.model.world

import com.qianyan.model.NovelId
import com.qianyan.model.VariantId
import com.qianyan.model.VariantScope
import com.qianyan.model.WorldId
import com.qianyan.model.WorldRuleId
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
data class World(
    val worldId: WorldId,
    val novelId: NovelId,
    val variantId: VariantId? = null,
    val scope: VariantScope = VariantScope.ORIGINAL,
    val name: String,
    val description: String = "",
    val createdAt: Instant,
    val updatedAt: Instant,
)

@Serializable
data class WorldRule(
    val ruleId: WorldRuleId,
    val worldId: WorldId,
    val novelId: NovelId,
    val variantId: VariantId? = null,
    val scope: VariantScope = VariantScope.ORIGINAL,
    val content: String,
    val category: String = "",
)