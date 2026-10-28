package com.qianyan.application.usecase.writing.planning

/** Planner 前章 Canonical 摘录上限（code point）。 */
const val PLANNER_CONTINUATION_MAX_CODE_POINTS: Int = 1_200

/** Writer 前章 Canonical 摘录上限（code point）。 */
const val WRITER_CONTINUATION_MAX_CODE_POINTS: Int = 4_000

/**
 * 前章 Canonical 正文的尾部摘录。
 *
 * 只共享**截断**语义（按 Unicode code point 截尾，不拆分代理对）；
 * Planner / Writer 各自把 [text] 渲染进自己的 prompt，禁止各自实现截断。
 */
data class ContinuationExcerpt(
    val text: String,
    val originalCodePoints: Int,
    val truncated: Boolean,
) {
    /** 确定性截断标注（供 prompt 展示与测试断言）。 */
    fun annotation(): String {
        val kept = text.codePointCount(0, text.length)
        return "truncated=$truncated; originalCodePoints=$originalCodePoints; keptCodePoints=$kept"
    }
}

/** 保留 [text] 末尾 [maxCodePoints] 个 Unicode code point；不拆分代理对。 */
fun continuationExcerpt(text: String, maxCodePoints: Int): ContinuationExcerpt {
    require(maxCodePoints >= 0) { "maxCodePoints 必须 >= 0" }
    val total = text.codePointCount(0, text.length)
    if (total <= maxCodePoints) return ContinuationExcerpt(text, total, truncated = false)
    val start = text.offsetByCodePoints(text.length, -maxCodePoints)
    return ContinuationExcerpt(text.substring(start), total, truncated = true)
}
