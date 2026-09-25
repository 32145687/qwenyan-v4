package com.qianyan.app.android.ui.reader

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import com.qianyan.engine.markdown.ControlledMarkdown
import com.qianyan.engine.markdown.InlineSpan

/**
 * P20-P4 · Reader 最小适配层：P2 [InlineSpan] → Compose [AnnotatedString]。
 *
 * 目的：P2 的 `MarkdownRenderer` 契约返回 String（文本渲染，非平台 UI），不适合 Compose 直接消费；
 * 本适配层只做**展示转换**，不修改 P2 核心契约、不重新实现解析（解析仍由 [ControlledMarkdown] 完成）。
 *
 * 仅用于受控 Markdown（`format = markdown:controlled:v1`）的段落 / 列表项 / 引用行内强调；
 * legacy（`format = null`）正文按纯文本展示，**不**经本函数。
 */
fun markdownInline(text: String): AnnotatedString =
    ControlledMarkdown.inline(text).toAnnotatedString()

/** 行内 span → AnnotatedString（Bold / Italic / 普通文本）。 */
fun List<InlineSpan>.toAnnotatedString(): AnnotatedString = buildAnnotatedString {
    this@toAnnotatedString.forEach { span ->
        when (span) {
            is InlineSpan.Text -> append(span.value)
            is InlineSpan.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(span.value) }
            is InlineSpan.Italic -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(span.value) }
        }
    }
}