package com.qianyan.app.android.ui.reader

import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import com.qianyan.engine.markdown.InlineSpan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * P20-P4 · Reader 行内强调适配测试（P2 [InlineSpan] → Compose AnnotatedString）。
 *
 * 只验证展示转换（Bold / Italic / 普通文本），解析仍由 P2 完成；不改变 P2 契约。
 */
class MarkdownAdapterTest {

    @Test
    fun `plain text maps to plain annotated string`() {
        val result = markdownInline("没有强调的普通句子")
        assertEquals("没有强调的普通句子", result.text)
        assertTrue(result.spanStyles.isEmpty())
    }

    @Test
    fun `bold and italic become span styles`() {
        val result = markdownInline("前**加粗**中*斜体*后")

        assertEquals("前加粗中斜体后", result.text, "Markdown 标记应被剥离")
        val weights = result.spanStyles.mapNotNull { it.item.fontWeight }
        val styles = result.spanStyles.mapNotNull { it.item.fontStyle }
        assertTrue(weights.contains(FontWeight.Bold), "加粗应映射为 Bold：${result.spanStyles}")
        assertTrue(styles.contains(FontStyle.Italic), "斜体应映射为 Italic：${result.spanStyles}")
        assertEquals("加粗", result.text.substring(result.spanStyles.first { it.item.fontWeight == FontWeight.Bold }.start,
            result.spanStyles.first { it.item.fontWeight == FontWeight.Bold }.end))
    }

    @Test
    fun `unclosed markers stay as plain text`() {
        val result = markdownInline("未闭合的 **加粗 标记")
        assertEquals("未闭合的 **加粗 标记", result.text)
        assertTrue(result.spanStyles.isEmpty())
    }

    @Test
    fun `span list maps bold and italic independently`() {
        val result = listOf(InlineSpan.Text("a"), InlineSpan.Bold("b"), InlineSpan.Italic("c")).toAnnotatedString()
        assertEquals("abc", result.text)
        assertEquals(2, result.spanStyles.size)
    }
}