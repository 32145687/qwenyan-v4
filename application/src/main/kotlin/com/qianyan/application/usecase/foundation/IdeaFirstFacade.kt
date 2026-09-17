package com.qianyan.application.usecase.foundation

import com.qianyan.model.NovelId
import com.qianyan.model.foundation.StoryIntent

/**
 * P15-C · IdeaFirstGateway — Android / 未来 Desktop 与 Idea-first 上游之间的**用户层 Application seam**。
 *
 * 只做 Idea-first 上游入口（用户一句话想法 → StoryIntent → AI 理解 → FoundationProposal → 既有 PENDING Gate）。
 * **不复制** Foundation 决策（present/modify/reject/requestRevision/confirm 属 [FoundationDecisionGateway]）。
 * UI 只能经本 seam 触 Idea 能力，不得触 Repository / Checkpoint / Task / Gate storage / StoryFoundationRepository /
 * LLMGateway / AgentRuntime / Provider / SQLDelight / JsonObject。
 */
interface IdeaFirstGateway {

    /** 启动 Idea-first：rawIdea → StoryIntent → AI 理解 → FoundationProposal + 既有 PENDING Foundation Gate。 */
    fun startFromIdea(novelId: NovelId, rawIdea: String): IdeaFirstResult

    /** 仅理解预览（不持久化）：返回 aiSummary + FoundationProposal。 */
    fun understandIdea(novelId: NovelId, rawIdea: String): IdeaUnderstanding

    /** 读取最新 Idea 中间态（StoryIntent）；无则 null。 */
    fun presentIdeaState(novelId: NovelId): StoryIntent?
}

/**
 * P15-C · IdeaFirstFacade — [IdeaFirstGateway] 的**极薄委托**实现（镜像 FoundationDecisionFacade / ChapterWorkflowFacade）。
 * 纯转发 [StoryIntentUseCases]，不携带业务规则。
 */
class IdeaFirstFacade(
    private val useCases: StoryIntentUseCases,
) : IdeaFirstGateway {

    override fun startFromIdea(novelId: NovelId, rawIdea: String): IdeaFirstResult =
        useCases.startFromIdea(novelId, rawIdea)

    override fun understandIdea(novelId: NovelId, rawIdea: String): IdeaUnderstanding =
        useCases.understandIdea(novelId, rawIdea)

    override fun presentIdeaState(novelId: NovelId): StoryIntent? =
        useCases.presentIdeaState(novelId)
}