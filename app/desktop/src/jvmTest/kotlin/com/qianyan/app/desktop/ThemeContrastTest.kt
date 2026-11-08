package com.qianyan.app.desktop

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import com.qianyan.app.desktop.ui.theme.QianyanDarkPalette
import com.qianyan.app.desktop.ui.theme.QianyanLightPalette
import com.qianyan.app.desktop.ui.theme.QianyanPalette
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * T-O77 · 主题对比度审计（纯函数，不依赖渲染）。
 *
 * 口径（本单元）：正文（text）≥ 4.5；次要（text2 / text3）与控件标签（onAccent/accent）≥ 3.0。
 * 颜色来自生产 palettes（`QianyanDarkPalette` / `QianyanLightPalette`），不复制常量。
 */
class ThemeContrastTest {

    private fun channel(v: Float): Double {
        val s = v.toDouble()
        return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
    }

    private fun luminance(c: Color): Double = 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)

    private fun contrast(a: Color, b: Color): Double {
        val l1 = maxOf(luminance(a), luminance(b))
        val l2 = minOf(luminance(a), luminance(b))
        return (l1 + 0.05) / (l2 + 0.05)
    }

    private fun hex(c: Color): String {
        fun p(v: Float) = (v * 255f).toInt().coerceIn(0, 255).toString(16).padStart(2, '0').uppercase()
        return "#${p(c.red)}${p(c.green)}${p(c.blue)}"
    }

    private fun audit(name: String, p: QianyanPalette) {
        val rows = listOf(
            Triple("text/bg", p.text, p.bg),
            Triple("text/raised", p.text, p.raised),
            Triple("text/surface", p.text, p.surface),
            Triple("text2/surface", p.text2, p.surface),
            Triple("text3/surface", p.text3, p.surface),
            Triple("text3/bg", p.text3, p.bg),
            Triple("onAccent/accent", p.onAccent, p.accent),
        )
        println("== 对比度 $name ==")
        rows.forEach { (pair, fg, bg) ->
            println("  $pair: ${"%.2f".format(contrast(fg, bg))}  (${hex(fg)} on ${hex(bg)})")
        }
        val selection = p.accent.copy(alpha = 0.4f).compositeOver(p.bg)
        println(
            "  selection=accent@40% over bg: ${hex(selection)}; text/selection=${"%.2f".format(contrast(p.text, selection))}; " +
                "selection/bg=${"%.2f".format(contrast(selection, p.bg))}",
        )

        // 正文：WCAG AA 4.5
        assertTrue(contrast(p.text, p.bg) >= 4.5, "$name text/bg 必须 ≥ 4.5")
        assertTrue(contrast(p.text, p.raised) >= 4.5, "$name text/raised 必须 ≥ 4.5")
        assertTrue(contrast(p.text, p.surface) >= 4.5, "$name text/surface 必须 ≥ 4.5")
        // 次要 / 控件标签：≥ 3.0
        assertTrue(contrast(p.text2, p.surface) >= 3.0, "$name text2/surface 必须 ≥ 3.0")
        assertTrue(contrast(p.text3, p.surface) >= 3.0, "$name text3/surface 必须 ≥ 3.0")
        assertTrue(contrast(p.text3, p.bg) >= 3.0, "$name text3/bg 必须 ≥ 3.0")
        assertTrue(contrast(p.onAccent, p.accent) >= 3.0, "$name onAccent/accent 必须 ≥ 3.0")
        // 选区：必须与背景可区分，且选中文字仍可读（≥ 4.5）
        assertNotEquals(p.bg, selection, "$name 选区色不得与背景同色")
        assertTrue(contrast(p.text, selection) >= 4.5, "$name 选中文字/选区 必须 ≥ 4.5")
        println()
    }

    @Test
    fun `dark palette meets contrast thresholds`() = audit("DARK", QianyanDarkPalette)

    @Test
    fun `light palette meets contrast thresholds`() = audit("LIGHT", QianyanLightPalette)
}
