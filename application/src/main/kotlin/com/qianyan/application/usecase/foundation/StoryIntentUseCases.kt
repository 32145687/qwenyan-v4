package com.qianyan.application.usecase.foundation

import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.application.error.ErrorMapper
import com.qianyan.application.usecase.UseCase
import com.qianyan.application.usecase.task.TaskManagerUseCases
import com.qianyan.model.NovelId
import com.qianyan.model.TaskId
import com.qianyan.model.foundation.StoryIntent
import com.qianyan.model.foundation.StoryIntentId
import com.qianyan.model.task.Checkpoint
import com.qianyan.model.task.TaskType
import com.qianyan.storage.repository.TaskRepository
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import java.util.UUID

/**
 * P15-C · Idea-first 编排 Use Case。
 *
 * 流程：校验 rawIdea → 创建 [StoryIntent]（原文原样保留）→ [IdeaUnderstandingAgent] →
 * aiSummary + [FoundationProposal] → Idea 中间态存 workflow-local Checkpoint → 复用
 * [StoryFoundationDecisionUseCases.prepareProposal] 生成既有 PENDING Foundation Gate。
 *
 * 边界（不自行实现）：revision / Gate / gateKey / confirm / Foundation persistence 均属 P14-F / P15-A，
 * 本 UseCase 只负责【想法 → Proposal】这一上游片段。
 */
class StoryIntentUseCases(
    private val taskManager: TaskManagerUseCases,
    private val taskRepository: TaskRepository,
    private val agent: IdeaUnderstandingAgent,
    private val foundationDecisions: StoryFoundationDecisionUseCases,
    errorMapper: ErrorMapper,
) : UseCase(errorMapper) {

    private val json = Json { ignoreUnknownKeys = true }

    /** 一句话想法 →（AI 理解）→ FoundationProposal + 既有 PENDING Gate。返回 Idea 结果与当前 Proposal/Gate。 */
    fun startFromIdea(novelId: NovelId, rawIdea: String): IdeaFirstResult {
        val intent = buildIntent(novelId, rawIdea)
        val understanding = agent.understand(intent)
        val withSummary = intent.copy(aiSummary = understanding.aiSummary, updatedAt = Clock.System.now())
        saveIdea(withSummary, novelId)
        // 复用 P14-F/F.3：prepareProposal 创建/复用 foundation WRITE_NOVEL workflow + proposal Checkpoint + PENDING Gate。
        val prepared = foundationDecisions.prepareProposal(novelId, understanding.proposal)
        return IdeaFirstResult(withSummary, prepared)
    }

    /** 仅理解预览（不持久化）：返回 aiSummary + FoundationProposal，供 UI 预览。 */
    fun understandIdea(novelId: NovelId, rawIdea: String): IdeaUnderstanding {
        val intent = buildIntent(novelId, rawIdea)
        return agent.understand(intent)
    }

    /** 读取最新 Idea 中间态（workflow-local Checkpoint）；无则 null。 */
    fun presentIdeaState(novelId: NovelId): StoryIntent? {
        val taskId = ideaTaskId(novelId)
        val latest = guard { taskRepository.findLatestCheckpoint(taskId) } ?: return null
        return decodeIdea(latest)
    }

    /* ---------------- 内部 ---------------- */

    private fun buildIntent(novelId: NovelId, rawIdea: String): StoryIntent {
        if (rawIdea.isBlank()) {
            throw ApplicationException(ApplicationError.InvalidOperation("rawIdea 不能为空"))
        }
        val now = Clock.System.now()
        return StoryIntent(
            id = StoryIntentId(UUID.randomUUID().toString()),
            novelId = novelId,
            rawIdea = rawIdea, // 原文原样保留，不 trim/不覆盖
            aiSummary = null,
            createdAt = now,
            updatedAt = now,
        )
    }

    private fun saveIdea(intent: StoryIntent, novelId: NovelId) {
        val taskId = ideaTaskId(novelId)
        getOrCreateIdeaTask(taskId)
        guard { taskManager.saveCheckpoint(taskId, STORY_FOUNDATION_IDEA, snapshot(intent)) }
    }

    private fun getOrCreateIdeaTask(taskId: TaskId) {
        if (guard { taskRepository.findById(taskId) } == null) {
            guard { taskManager.create(TaskType.PLANNING, taskId) }
        }
    }

    private fun ideaTaskId(novelId: NovelId): TaskId = TaskId("story-intent-${novelId.value}")

    private fun snapshot(intent: StoryIntent): JsonObject =
        JsonObject(mapOf(IDEA_KEY to json.encodeToJsonElement(intent)))

    private fun decodeIdea(checkpoint: Checkpoint): StoryIntent {
        val element = checkpoint.snapshot?.get(IDEA_KEY)
            ?: throw ApplicationException(ApplicationError.CheckpointNotFound("Checkpoint ${checkpoint.checkpointId.value} 缺少 Idea 负载"))
        return json.decodeFromJsonElement(StoryIntent.serializer(), element)
    }

    private companion object {
        const val STORY_FOUNDATION_IDEA: String = "STORY_FOUNDATION_IDEA"
        const val IDEA_KEY: String = "idea"
    }
}

/** startFromIdea 结果：Idea（rawIdea + aiSummary）+ 当前 PreparedFoundation（workflow / proposal / gate）。 */
data class IdeaFirstResult(
    val storyIntent: StoryIntent,
    val proposal: PreparedFoundation,
)