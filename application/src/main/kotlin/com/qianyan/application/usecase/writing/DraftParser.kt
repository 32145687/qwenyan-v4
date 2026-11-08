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
 * [Draft] 输出解析（P11.3 + O-75）。
 *
 * 把 Writer 的 LLM 原始文本可靠映射为现有 [Draft]（复用 core:model，不新建重复模型）。
 * 规则：
 *  - 结构 ID（draftId / novelId / variantId / scope / chapterId / planId / sourceModel / 时间）
 *    来自装配上下文 / [ChapterPlan]，不由 LLM 生成 —— LLM 只负责正文 content；
 *  - **O-75 形态判定（两条路径）**：
 *      1) 以 `{` / `[` 开头 → 结构化路径：必须是 `{"content":"…"}`；
 *         解析失败 / 缺 content / 类型错误 → [WritingException.InvalidOutput]，**绝不回落成正文**
 *         （避免把 tool 包、结构化残片当成合法正文）；
 *      2) 其余非空文本 → 自由正文路径：原样作为 content（保留换行与中文引号）。
 *         运行时 `answer` 已经承载正文，正文即语义；
 *  - 空输出 / 纯空白 / Markdown 围栏包裹（```）→ [WritingException.InvalidOutput]；
 *  - 本类只做**形态**判定，不做品味判断：是否贴题由 CRITIQUE 与人工门负责，候选不会自动入 Canonical；
 *  - 绝不把解析失败伪装成"成功 Draft"。
 */
object DraftParser {

    /** 解析 Writer 输出为 [Draft]。structure 提供结构信息，raw 提供正文。 */
    fun parse(raw: String, structure: DraftStructure): Draft {
        val content = extractContent(raw)
        return Draft(
            draftId = DraftId(structure.draftId),
            novelId = structure.novelId,
            variantId = structure.variantId,
            scope = structure.scope,
            chapterId = structure.chapterId,
            planId = structure.planId,
            content = content,
            status = DraftStatus.WRITTEN,
            sourceModel = structure.sourceModel,
            createdAt = structure.now,
            updatedAt = structure.now,
        )
    }

    /** O-75：形态判定（结构化 or 自由正文）；拒绝空 / 围栏 / 结构化残片。 */
    private fun extractContent(raw: String): String {
        val text = raw.trim()
        if (text.isEmpty()) {
            throw WritingException.InvalidOutput("Writer 输出为空")
        }
        if (text.startsWith("```")) {
            throw WritingException.InvalidOutput("Writer 输出被 Markdown 围栏包裹")
        }
        if (text.startsWith("{") || text.startsWith("[")) {
            val dto = decodeStructured(text)
            if (dto.content.isBlank()) {
                throw WritingException.InvalidOutput("draft content is empty")
            }
            return dto.content
        }
        return text
    }

    /** 结构化路径：非 JSON / 缺 content / content 类型错误 统一走类型化解析错误（不回落正文）。 */
    private fun decodeStructured(text: String): DraftDto =
        try {
            json.decodeFromString<DraftDto>(text)
        } catch (e: Exception) {
            throw WritingException.InvalidOutput("illegal json or wrong field type: ${e.message}")
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