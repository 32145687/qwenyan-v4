package com.qianyan.model.foundation

import com.qianyan.model.NovelId
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/**
 * P15-C · StoryIntent — 作者"我想写什么"的最小领域表达（Idea-first 上游）。
 *
 * 边界（冻结）：
 *  - 只承载**作者原文**与**AI 简短理解为 [aiSummary]，两者分离（[rawIdea] 永不被动过）**；
 *  - **不是事实**：不直接成为 StoryFoundation（须经 FoundationProposal → 用户确认 → StoryFoundation）；
 *  - 不放 genre / storyType / theme / conflict / promise / pov / readerTone / policy —— 这些归属
 *    [com.qianyan.model.foundation.StoryFoundation] 及其 Proposal；
 *  - MVP **不落 Novel durable schema**（存 workflow-local Checkpoint；永久保存属未来扩展）；
 *  - immutable，无 Android / Application / Storage / LLM 依赖，不含 Gate / Proposal / Repository。
 */
@JvmInline
@Serializable
value class StoryIntentId(val value: String)

/** 用户的一句话故事想法（原文保留 + AI 简短摘要）。 */
@Serializable
data class StoryIntent(
    val id: StoryIntentId,
    val novelId: NovelId,
    /** 作者原文，必须原样保留、不透传被 AI 修改。 */
    val rawIdea: String,
    /** AI 对作者意图的简短理解；理解前为 null。 */
    val aiSummary: String? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
)