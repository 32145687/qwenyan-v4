package com.qianyan.application.usecase.writing.planning

import com.qianyan.model.ActId
import com.qianyan.model.ArcId
import com.qianyan.model.CharacterId
import com.qianyan.model.ChapterPlanId
import com.qianyan.model.NovelId
import com.qianyan.model.StoryConflictId
import com.qianyan.model.VariantId
import com.qianyan.model.VariantScope
import com.qianyan.model.story.ChapterPlan
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * [ChapterPlan] 输出解析（P11.2）。
 *
 * 把 Planner 的 LLM 原始文本可靠映射为现有 [ChapterPlan]。
 * 规则：
 *  - 结构 ID（novelId / arcId / actId / variantId / scope / chapterPlanId）来自装配上下文，
 *    不由 LLM 生成 —— LLM 只负责创作性字段；
 *  - 空输出 / 非 JSON / 类型错误 / 拆包失败 → [PlanningException.InvalidOutput]（类型化，不经 String.contains）。
 */
object ChapterPlanParser {

    /** 解析 Planner 输出为 [ChapterPlan]。structure 提供结构 ID，raw 提供创作性字段。 */
    fun parse(raw: String, structure: ChapterPlanStructure): ChapterPlan {
        val dto = decode(raw)
        return toPlan(dto, structure)
    }

    private fun decode(raw: String): PlanDto {
        if (raw.isBlank()) {
            throw PlanningException.InvalidOutput("empty plan output")
        }
        return try {
            json.decodeFromString<PlanDto>(raw)
        } catch (e: Exception) {
            throw PlanningException.InvalidOutput("illegal json or wrong field type: ${e.message}")
        }
    }

    private fun toPlan(dto: PlanDto, s: ChapterPlanStructure): ChapterPlan {
        // 空计划防护：chapterGoal 是 PlanDto 唯一必填创作字段，缺失或为空 → 类型化错误，
        // 避免 LLM 输出无关 JSON（如默认 Mock 词汇响应）被静默接受为"空计划"。
        if (dto.chapterGoal.isBlank()) {
            throw PlanningException.InvalidOutput("plan is empty: missing chapterGoal")
        }
        return ChapterPlan(
        chapterPlanId = ChapterPlanId(s.chapterPlanId),
        chapterId = null,
        arcId = s.arcId,
        actId = s.actId,
        novelId = s.novelId,
        variantId = s.variantId,
        scope = s.scope,
        chapterGoal = dto.chapterGoal,
        mainConflict = dto.mainConflict?.takeIf { it.isNotBlank() }?.let { StoryConflictId(it) },
        characterGoals = dto.characterGoals.mapKeys { (k, _) -> CharacterId(k) },
        expectedEvents = dto.expectedEvents,
        emotionalDirection = dto.emotionalDirection,
        endingHook = dto.endingHook,
        constraints = dto.constraints,
        forbiddenEvents = dto.forbiddenEvents,
        )
    }

    private val json = Json { ignoreUnknownKeys = true }

    /** Planner JSON 的创作性字段 DTO。chapterGoal 必填（缺失 → 解码即失败）；其余可选。 */
    @Serializable
    private data class PlanDto(
        val chapterGoal: String,
        val mainConflict: String? = null,
        val characterGoals: Map<String, String> = emptyMap(),
        val expectedEvents: List<String> = emptyList(),
        val emotionalDirection: String = "",
        val endingHook: String = "",
        val constraints: List<String> = emptyList(),
        val forbiddenEvents: List<String> = emptyList(),
    )
}

/**
 * [ChapterPlan] 的结构 ID 载体：由 Planning 上下文提供（novelId / arcId / actId / variantId / scope / 新 chapterPlanId）。
 * 解析器据此把 LLM 创作性字段并合为完整 ChapterPlan，避免让 LLM 生产领域 ID。
 */
data class ChapterPlanStructure(
    val novelId: NovelId,
    val arcId: ArcId,
    val actId: ActId,
    val variantId: VariantId? = null,
    val scope: VariantScope = VariantScope.ORIGINAL,
    val chapterPlanId: String,
)