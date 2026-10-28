package com.qianyan.app.desktop.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Qianyan Writing IDE · 主题出口（Design Token 的唯一出口）。
 *
 * 把 [QianyanPalette]（色板）与 [QianyanFonts]（bundled 字体）映射为 Material3 的
 * `ColorScheme` / `Typography`。**不在本文件之外定义颜色常量**。
 *
 * 主题模式由 [mode] 决定（跟随系统 / 深色 / 浅色）；两种模式共用同一套字段与排版，
 * 因此 Dark / Light 是同一设计语言的两面。
 */
object QianyanTheme {

    /** 当前色板（CompositionLocal；由 [QianyanTheme] 提供）。 */
    val palette: QianyanPalette
        @Composable get() = LocalQianyanPalette.current

    /** 当前是否为深色（供阴影 / 浮层强度等需要区分的场合使用）。 */
    val isDark: Boolean
        @Composable get() = LocalQianyanDark.current
}

/** 排版：UI 全部使用 **bundled** Noto Sans SC；正文衬线使用显式衬线族。 */
private fun qianyanTypography(): Typography = Typography(
    // 页面标题：22sp / SemiBold / 轻微负字距（Apple 式紧凑标题）
    displaySmall = TextStyle(
        fontFamily = QianyanFonts.ui,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.2).sp,
    ),
    titleLarge = TextStyle(fontFamily = QianyanFonts.ui, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 24.sp),
    titleMedium = TextStyle(fontFamily = QianyanFonts.ui, fontWeight = FontWeight.Medium, fontSize = 13.5.sp, lineHeight = 21.sp),
    titleSmall = TextStyle(fontFamily = QianyanFonts.ui, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 19.sp),
    bodyLarge = TextStyle(fontFamily = QianyanFonts.ui, fontSize = 13.5.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontFamily = QianyanFonts.ui, fontSize = 12.5.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = QianyanFonts.ui, fontSize = 11.5.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontFamily = QianyanFonts.ui, fontWeight = FontWeight.Medium, fontSize = 12.5.sp, lineHeight = 17.sp),
    labelMedium = TextStyle(fontFamily = QianyanFonts.ui, fontWeight = FontWeight.Medium, fontSize = 11.5.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontFamily = QianyanFonts.ui, fontSize = 10.5.sp, lineHeight = 15.sp, letterSpacing = 0.2.sp),
)

/** 正文（书稿）样式：衬线 + 行距 2.0；字号由 [QianyanDimens.EditorText] 起。 */
fun qianyanProseStyle(fontSize: androidx.compose.ui.unit.TextUnit = QianyanDimens.EditorText): TextStyle = TextStyle(
    fontFamily = QianyanFonts.serif,
    fontSize = fontSize,
    lineHeight = fontSize * 1.95f,
)

/** 等宽（字数 / 时间 / ID / Diff 行号）。 */
val QianyanCodeStyle = TextStyle(
    fontFamily = QianyanFonts.mono,
    fontSize = 11.sp,
    lineHeight = 17.sp,
)

/** 章节标题（稿纸标题层；24–30px）。 */
val QianyanChapterStyle = TextStyle(
    fontFamily = QianyanFonts.serif,
    fontWeight = FontWeight.SemiBold,
    fontSize = 25.sp,
    lineHeight = 36.sp,
)

/** 首页字标（散文体，克制的展示层）。 */
val QianyanWordmarkStyle = TextStyle(
    fontFamily = QianyanFonts.serif,
    fontSize = 30.sp,
    lineHeight = 42.sp,
    letterSpacing = 0.06.sp,
)

private fun scheme(p: QianyanPalette, dark: Boolean): ColorScheme = if (dark) {
    darkColorScheme(
        primary = p.accent,
        onPrimary = p.onAccent,
        primaryContainer = p.accentSoft,
        onPrimaryContainer = p.text,
        secondary = p.text2,
        onSecondary = p.bg,
        background = p.bg,
        onBackground = p.text,
        // Material 的浮层组件（下拉菜单等）一律使用中性容器色；
        // 不覆盖 surfaceContainer* 会落回 Material3 默认的淡紫色。
        surface = p.raised,
        onSurface = p.text,
        surfaceVariant = p.soft,
        onSurfaceVariant = p.text2,
        surfaceContainerLowest = p.surface,
        surfaceContainerLow = p.raised,
        surfaceContainer = p.raised,
        surfaceContainerHigh = p.soft,
        surfaceContainerHighest = p.soft,
        surfaceBright = p.raised,
        surfaceDim = p.surface,
        inverseSurface = p.text,
        inverseOnSurface = p.bg,
        secondaryContainer = p.soft,
        onSecondaryContainer = p.text,
        tertiaryContainer = p.soft,
        onTertiaryContainer = p.text,
        outline = p.lineStrong,
        outlineVariant = p.line,
        error = p.del,
        onError = p.bg,
        surfaceTint = Color.Transparent,
        scrim = Color(0xCC000000),
    )
} else {
    lightColorScheme(
        primary = p.accent,
        onPrimary = p.onAccent,
        primaryContainer = p.accentSoft,
        onPrimaryContainer = p.text,
        secondary = p.text2,
        onSecondary = p.surface,
        background = p.bg,
        onBackground = p.text,
        surface = p.raised,
        onSurface = p.text,
        surfaceVariant = p.soft,
        onSurfaceVariant = p.text2,
        surfaceContainerLowest = p.bg,
        surfaceContainerLow = p.raised,
        surfaceContainer = p.raised,
        surfaceContainerHigh = p.soft,
        surfaceContainerHighest = p.soft,
        surfaceBright = p.bg,
        surfaceDim = p.surface,
        inverseSurface = p.text,
        inverseOnSurface = p.bg,
        secondaryContainer = p.soft,
        onSecondaryContainer = p.text,
        tertiaryContainer = p.soft,
        onTertiaryContainer = p.text,
        outline = p.lineStrong,
        outlineVariant = p.line,
        error = p.del,
        onError = p.surface,
        surfaceTint = Color.Transparent,
        scrim = Color(0x33000000),
    )
}

@Composable
fun QianyanTheme(
    mode: QianyanThemeMode = QianyanThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val dark = when (mode) {
        QianyanThemeMode.SYSTEM -> isSystemInDarkTheme()
        QianyanThemeMode.DARK -> true
        QianyanThemeMode.LIGHT -> false
    }
    val palette = if (dark) QianyanDarkPalette else QianyanLightPalette
    CompositionLocalProvider(
        LocalQianyanPalette provides palette,
        LocalQianyanDark provides dark,
    ) {
        MaterialTheme(
            colorScheme = scheme(palette, dark),
            typography = qianyanTypography(),
            content = content,
        )
    }
}
