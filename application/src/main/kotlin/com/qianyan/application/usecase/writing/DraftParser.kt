package com.qianyan.application.usecase.writing

import com.qianyan.model.ChapterId
import com.qianyan.model.ChapterPlanId
import com.qianyan.model.DraftId
import com.qianyan.model.NovelId
import com.qianyan.model.VariantId
import com.qianyan.model.VariantScope
import com.qianyan.model.writing.Draft
import com.qianyan.model.writing.DraftStatus
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * [Draft] 输出解析（P11.3）。
 *
 * 把 Writer 的 LLM 原始文本可靠映射为现有 [Draft]（复用 core:model，不新建重复模型）。
 * 规则：
 *  - 结构 ID（draftId / novelId / variantId / scope / chapterId / planId / sourceModel / 时间）
 *    来自装配上下文 / [ChapterPlan]，不由 LLM 生成 —— LLM 只负责正文 content；
 *  - 空输出 / 非 JSON / 缺 content / content 类型错误 / 空白正文 → [WritingException.InvalidOutput]
 *    （类型化，不经 String.contains）；
 *  - 绝不把解析失败伪装成"成功 Draft"。
 */
object DraftParser {

    /** 解析 Writer 输出为 [Draft]。structure 提供结构信息，raw 提供正文。 */
    fun parse(raw: String, structure: DraftStructure): Draft {
        val dto = decode(raw)
        if (dto.content.isBlank()) {
            throw WritingException.InvalidOutput("draft content is empty")
        }
        return Draft(
            draftId = DraftId(structure.draftId),
            novelId = structure.novelId,
            variantId = structure.variantId,
            scope = structure.scope,
            chapterId = structure.chapterId,
            planId = structure.planId,
            content = dto.content,
            status = DraftStatus.WRITTEN,
            sourceModel = structure.sourceModel,
            createdAt = structure.now,
            updatedAt = structure.now,
        )
    }

    private fun decode(raw: String): DraftDto {
        if (raw.isBlank()) {
            throw WritingException.InvalidOutput("empty draft output")
        }
        return try {
            json.decodeFromString<DraftDto>(raw)
        } catch (e: Exception) {
            // 非 JSON / 缺 content / content 类型错误 统一走类型化解析错误。
            throw WritingException.InvalidOutput("illegal json or wrong field type: ${e.message}")
        }
    }

    private val json = Json { ignoreUnknownKeys = true }

    /** Writer JSON 的正文 DTO。content 为必填 String（缺失/类型错误 → 解码即失败）。 */
    @Serializable
    private data class DraftDto(
        val content: String,
    )
}

/**
 * [Draft] 的结构信息载体：由 Writing 上下文 / [ChapterPlan] 提供（draftId / novelId / variantId /
 * scope / chapterId / planId / sourceModel / 时间戳）。
 * 解析器据此把 LLM 正文并合为完整 [Draft]，避免让 LLM 生产领域 ID / 时间。
 */
data class DraftStructure(
    val draftId: String,
    val novelId: NovelId,
    val variantId: VariantId? = null,
    val scope: VariantScope = if (variantId == null) VariantScope.ORIGINAL else VariantScope.VARIANT,
    val chapterId: ChapterId? = null,
    val planId: ChapterPlanId? = null,
    val sourceModel: String = "",
    val now: Instant,
)