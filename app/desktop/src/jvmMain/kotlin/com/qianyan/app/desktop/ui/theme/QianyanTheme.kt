package com.qianyan.app.desktop.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 千言设计系统 v3 → Compose Token 精确映射。
 * 来源：qianyan-pc.html 的 :root 设计变量（暖白纸张 / 墨色 / 主棕 / 灰紫 Sparkle / 柔和琥珀）。
 */
object QianyanColors {
    // 浅色
    val BgLight = Color(0xFFF5F2EB)
    val SurfaceLight = Color(0xFFFAF8F4)
    val SurfaceVariantLight = Color(0xFFF2EEE4)
    val InkLight = Color(0xFF1D1C18)
    val Ink2Light = Color(0xFF6B6557)
    val Ink3Light = Color(0xFFA49B88)
    val LineLight = Color(0xFFE5DFD0)
    val BrownLight = Color(0xFF7A6548)
    val AiLight = Color(0xFF6B5E8A)
    val AiSoftLight = Color(0xFFECE8F1)
    val AmberLight = Color(0xFFB38B52)
    val AmberSoftLight = Color(0xFFF3EBDA)
    val OkLight = Color(0xFF7D9B76)
    val WatchLight = Color(0xFFC09A55)
    val RiskLight = Color(0xFFBF7C58)
    val HighLight = Color(0xFFA8584C)

    // 深色
    val BgDark = Color(0xFF211F1C)
    val SurfaceDark = Color(0xFF2A2723)
    val SurfaceVariantDark = Color(0xFF252220)
    val InkDark = Color(0xFFEDE8DD)
    val Ink2Dark = Color(0xFFADA496)
    val Ink3Dark = Color(0xFF7C7466)
    val LineDark = Color(0xFF3B372F)
    val BrownDark = Color(0xFFA68D68)
    val AiDark = Color(0xFFA99BCF)
    val AiSoftDark = Color(0xFF322D3C)
    val AmberDark = Color(0xFFCFA05A)
    val AmberSoftDark = Color(0xFF39311F)
    val OkDark = Color(0xFF8FAE88)
    val WatchDark = Color(0xFFD3AD6C)
    val RiskDark = Color(0xFFCF8A64)
    val HighDark = Color(0xFFC47A6C)
}

private fun lightScheme(): ColorScheme = lightColorScheme(
    primary = QianyanColors.BrownLight,
    onPrimary = Color(0xFFFAF8F4),
    primaryContainer = QianyanColors.AmberSoftLight,
    onPrimaryContainer = QianyanColors.BrownLight,
    secondary = QianyanColors.AiLight,
    onSecondary = Color.White,
    secondaryContainer = QianyanColors.AiSoftLight,
    onSecondaryContainer = QianyanColors.AiLight,
    tertiary = QianyanColors.AmberLight,
    onTertiary = Color.White,
    tertiaryContainer = QianyanColors.AmberSoftLight,
    onTertiaryContainer = QianyanColors.AmberLight,
    background = QianyanColors.BgLight,
    onBackground = QianyanColors.InkLight,
    surface = QianyanColors.SurfaceLight,
    onSurface = QianyanColors.InkLight,
    surfaceVariant = QianyanColors.SurfaceVariantLight,
    onSurfaceVariant = QianyanColors.Ink2Light,
    outline = QianyanColors.LineLight,
    outlineVariant = QianyanColors.LineLight,
    error = QianyanColors.HighLight,
    onError = Color.White,
    // ---- 以下 surface 容器色系必须显式指定：Material3 未覆盖时会回落到其默认紫基线，
    //      表现为对话框 / 卡片 / 菜单发紫。此处全部锚定 v3 暖白纸张色。 ----
    surfaceTint = Color.Transparent,
    surfaceBright = Color(0xFFFDFBF7),
    surfaceDim = Color(0xFFEAE5DA),
    surfaceContainerLowest = Color(0xFFFFFDFA),
    surfaceContainerLow = Color(0xFFFAF8F4),
    surfaceContainer = Color(0xFFF7F4ED),
    surfaceContainerHigh = Color(0xFFF4F1E9),
    surfaceContainerHighest = Color(0xFFF1EDE3),
    inverseSurface = Color(0xFF2E2B25),
    inverseOnSurface = Color(0xFFF5F2EB),
    inversePrimary = QianyanColors.BrownDark,
    scrim = Color(0xFF2A241A),
)

private fun darkScheme(): ColorScheme = darkColorScheme(
    primary = QianyanColors.BrownDark,
    onPrimary = Color(0xFF211F1C),
    primaryContainer = QianyanColors.AmberSoftDark,
    onPrimaryContainer = QianyanColors.AmberDark,
    secondary = QianyanColors.AiDark,
    onSecondary = Color(0xFF211F1C),
    secondaryContainer = QianyanColors.AiSoftDark,
    onSecondaryContainer = QianyanColors.AiDark,
    tertiary = QianyanColors.AmberDark,
    onTertiary = Color(0xFF211F1C),
    tertiaryContainer = QianyanColors.AmberSoftDark,
    onTertiaryContainer = QianyanColors.AmberDark,
    background = QianyanColors.BgDark,
    onBackground = QianyanColors.InkDark,
    surface = QianyanColors.SurfaceDark,
    onSurface = QianyanColors.InkDark,
    surfaceVariant = QianyanColors.SurfaceVariantDark,
    onSurfaceVariant = QianyanColors.Ink2Dark,
    outline = QianyanColors.LineDark,
    outlineVariant = QianyanColors.LineDark,
    error = QianyanColors.HighDark,
    onError = Color(0xFF211F1C),
    // surface 容器色系（同 light：避免 Material3 默认紫基线）
    surfaceTint = Color.Transparent,
    surfaceBright = Color(0xFF322E29),
    surfaceDim = Color(0xFF1B1917),
    surfaceContainerLowest = Color(0xFF191715),
    surfaceContainerLow = Color(0xFF252220),
    surfaceContainer = Color(0xFF2A2723),
    surfaceContainerHigh = Color(0xFF302C28),
    surfaceContainerHighest = Color(0xFF37322D),
    inverseSurface = Color(0xFFEDE8DD),
    inverseOnSurface = Color(0xFF211F1C),
    inversePrimary = QianyanColors.BrownLight,
    scrim = Color(0xFF0F0E0D),
)

/** 千言排版：界面用无衬线，正文（书稿）用衬线——数字书房的字体秩序。 */
private fun qianyanTypography(): Typography = Typography(
    headlineMedium = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold, fontSize = 26.sp, lineHeight = 36.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 12.5.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 11.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 13.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 11.sp),
)

/** 正文阅读样式（书稿衬线、宽行距）。 */
val ProseStyle = TextStyle(fontFamily = FontFamily.Serif, fontSize = 17.sp, lineHeight = 34.sp, color = Color(0xFF1D1C18))

@Composable
fun QianyanTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) darkScheme() else lightScheme(),
        typography = qianyanTypography(),
        content = content,
    )
}
