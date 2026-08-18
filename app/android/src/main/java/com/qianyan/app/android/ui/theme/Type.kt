package com.qianyan.app.android.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Qianyan 字体层级（P7.4）。
 *
 * 视觉层级参考 Apple 的清晰层级：超大标题 → 副标题 → 内容 → 辅助信息，
 * 通过字号与字重拉开对比，而非让所有文字大小一致。
 */
val QianyanTypography = Typography(
    headlineLarge = TextStyle(
        fontSize = 34.sp,
        fontWeight = FontWeight.Bold,
        lineHeight = 41.sp,
        letterSpacing = 0.sp,
    ),
    headlineMedium = TextStyle(
        fontSize = 22.sp,
        fontWeight = FontWeight.SemiBold,
        lineHeight = 28.sp,
    ),
    titleLarge = TextStyle(
        fontSize = 17.sp,
        fontWeight = FontWeight.SemiBold,
        lineHeight = 22.sp,
    ),
    bodyLarge = TextStyle(
        fontSize = 17.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 22.sp,
    ),
    bodyMedium = TextStyle(
        fontSize = 15.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 20.sp,
    ),
    labelSmall = TextStyle(
        fontSize = 13.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 18.sp,
    ),
)
