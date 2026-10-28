package com.qianyan.model.context

import com.qianyan.model.GenreId
import com.qianyan.model.foundation.NarrativeProfile
import com.qianyan.model.foundation.StoryDirection
import com.qianyan.model.foundation.WritingPolicy
import kotlinx.serialization.Serializable

/*
 * Phase 4B.1 · Context 条目的类型化载荷（Addendum canonical-v1，Phase 4A D2 的 additive 补充）。
 *
 * 设计约束（冻结）：
 *  - 封闭类型体系（sealed），每种载荷只对应一个 SourceKind；
 *  - 不承载 `Any` / `Map<String, Any>` / JSON 字符串 / 通用桶；
 *  - 不参与 `(source, refKey)` 唯一性判定；
 *  - 结构化字段只能由 Source 从同一领域对象构造，**禁止**从 `ContextItem.content` 反解析。
 */

@Serializable
sealed interface ContextPayload

/** NOVEL：与 `Novel.title / genre / synopsis` 同源（目标字段 `PlanningContext.novelTitle/novelGenre/novelSynopsis`）。 */
@Serializable
data class NovelPayload(
    val title: String,
    val genre: List<String>,
    val synopsis: String,
) : ContextPayload

/** STORY_FOUNDATION：与 `StoryFoundation.genre / direction / audience / policy` 同源。 */
@Serializable
data class FoundationPayload(
    val genre: List<GenreId>,
    val direction: StoryDirection,
    val audience: NarrativeProfile,
    val policy: WritingPolicy,
) : ContextPayload

/** VOCABULARY：J-1=A，与正式 `VocabularyEntry`（approved）字段同源。 */
@Serializable
data class VocabularyPayload(
    val canonical: String,
    val aliases: List<String>,
    val replacement: String?,
) : ContextPayload

/**
 * canonical-v1 规范化编码器（Addendum §E.2）。
 *
 * 规则（全部以 UTF-8 字节为准）：
 *  - `S(x)` = `<decLen>:<utf8(x)>`（decLen 为 UTF-8 字节数；十进制、无前导零）；
 *  - `N` = null；`A(xs)` = `A<count>:` + 依序元素；空列表 `A0:`；
 *  - `M` = `<TAG>` + 按 Kotlin 声明顺序的字段（边界由 TAG + 固定字段数 + 自定界编码确定）；
 *  - `VocabularyPayload.replacement`：null → `0:`；空串 → `1:S("")`；非空 → `1:S(value)`；
 *  - 禁止 `toString()` / 调试文本 / `content` 反解析。
 */
object ContextPayloadCanonical {

    fun encode(payload: ContextPayload?): String = buildString { appendPayload(this, payload) }

    private fun appendPayload(sb: StringBuilder, payload: ContextPayload?) {
        when (payload) {
            null -> sb.append("P0")
            is NovelPayload -> {
                sb.append("PN")
                appendS(sb, payload.title)
                appendList(sb, payload.genre)
                appendS(sb, payload.synopsis)
            }
            is FoundationPayload -> {
                sb.append("PF")
                appendList(sb, payload.genre.map { it.value })
                appendDirection(sb, payload.direction)
                appendAudience(sb, payload.audience)
                appendPolicy(sb, payload.policy)
            }
            is VocabularyPayload -> {
                sb.append("PV")
                appendS(sb, payload.canonical)
                appendList(sb, payload.aliases)
                val replacement = payload.replacement
                if (replacement == null) {
                    sb.append("0:")
                } else {
                    sb.append("1:")
                    appendS(sb, replacement)
                }
            }
        }
    }

    private fun appendDirection(sb: StringBuilder, direction: StoryDirection) {
        sb.append("SD")
        appendS(sb, direction.theme)
        appendS(sb, direction.conflict)
        appendS(sb, direction.promise)
        appendS(sb, direction.storyType)
    }

    private fun appendAudience(sb: StringBuilder, audience: NarrativeProfile) {
        sb.append("NP")
        appendS(sb, audience.pov)
        appendS(sb, audience.readerTone)
    }

    private fun appendPolicy(sb: StringBuilder, policy: WritingPolicy) {
        sb.append("WP")
        appendList(sb, policy.rules)
    }

    /** `S(x)`：UTF-8 字节长度前缀 + 原文；内容含任意分隔符/Unicode 均不影响唯一性。 */
    private fun appendS(sb: StringBuilder, value: String) {
        sb.append(value.encodeToByteArray().size).append(':').append(value)
    }

    /** `A(xs)`：元素数量 + 依序的元素编码；空列表 = `A0:`。 */
    private fun appendList(sb: StringBuilder, values: List<String>) {
        sb.append('A').append(values.size).append(':')
        values.forEach { appendS(sb, it) }
    }
}
