package com.qianyan.application.usecase.writing.postprocessor

import com.qianyan.model.writing.Draft

/**
 * 写作输出后处理 seam（P11.1 仅建立接口位置，不实现算法）。
 *
 * 定位：属于 Application 写作编排层，**不在 provider:impl，不污染 AgentRuntime / Provider**。
 *
 * 设计意图（P11 最终目标）：
 * ```
 * Writing → LLM → Draft → 写作完成
 *   → 若当前模型 == MiMo（mimo-v2.5-pro，写作模型且存在过度解释倾向）
 *   → MiMo 专用写作后处理 seam
 *   → Final Draft
 * ```
 * P11.1 只登记该 seam；P11.5 前不实现 MiMo 算法、不修改任何模型输出。
 * 默认实现为 [PassthroughWritingPostProcessor]（直通，行为中立，无模型特判）。
 */
fun interface WritingPostProcessor {
    /** 对一次写作产物做（可能为空的）后处理。默认直通。 */
    fun postProcess(draft: Draft): Draft
}

/** 直通实现：任何模型一律原样返回，不做任何改写 / 去重 / 收紧。 */
object PassthroughWritingPostProcessor : WritingPostProcessor {
    override fun postProcess(draft: Draft): Draft = draft
}