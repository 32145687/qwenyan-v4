package com.qianyan.app.desktop.ui.workspace

import com.qianyan.provider.ProviderType
import com.qianyan.provider.ReasoningEffort

/** 第三级「思考强度」的一个可选项。 */
internal data class ReasoningChoice(val effort: ReasoningEffort, val label: String)

/**
 * 第三级控件的厂商形态（同一概念，各厂商编码不同）：
 *  - DeepSeek：`reasoning_effort` 四档（none / low / high / max，默认 high）；
 *  - MiMo：`thinking.type` 开关（关 / 开，默认开）；
 *  - 离线示意：不提供思考参数。
 */
internal fun reasoningChoices(type: ProviderType): List<ReasoningChoice> = when (type) {
    ProviderType.DEEPSEEK -> listOf(
        ReasoningChoice(ReasoningEffort.NONE, "none"),
        ReasoningChoice(ReasoningEffort.LOW, "low"),
        ReasoningChoice(ReasoningEffort.HIGH, "high"),
        ReasoningChoice(ReasoningEffort.MAX, "max"),
    )

    ProviderType.MIMO -> listOf(
        ReasoningChoice(ReasoningEffort.NONE, "关"),
        ReasoningChoice(ReasoningEffort.HIGH, "开"),
    )

    ProviderType.MOCK -> emptyList()
}

/** 离线示意不提供思考参数的诚实说明；真实 Provider 无说明。 */
internal fun reasoningNote(type: ProviderType): String? =
    if (type == ProviderType.MOCK) "离线示意模型不提供思考能力，不会发送思考参数" else null

/** MiMo 只有开关：任何非 NONE 档位在 UI 上都显示为「开」。 */
internal fun effectiveChoice(type: ProviderType, effort: ReasoningEffort): ReasoningEffort =
    if (type == ProviderType.MIMO && effort != ReasoningEffort.NONE) ReasoningEffort.HIGH else effort
