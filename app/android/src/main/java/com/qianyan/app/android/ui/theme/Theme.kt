package com.qianyan.app.android.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val LightColors = lightColorScheme(
    primary = QianyanAccent,
    onPrimary = QianyanSurface,
    background = QianyanBackground,
    onBackground = QianyanTextPrimary,
    surface = QianyanSurface,
    onSurface = QianyanTextPrimary,
    onSurfaceVariant = QianyanTextSecondary,
    secondary = QianyanTextSecondary,
    onSecondary = QianyanSurface,
    outline = QianyanDivider,
    outlineVariant = QianyanDivider,
)

/** 适度圆角：卡片与交互组件使用中等圆角，页面不铺满圆角。 */
private val QianyanShapes = Shapes(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
)

/**
 * Qianyan 主题（P7.4，Material3 + Apple-inspired 极简）。
 *
 * 当前仅提供浅色主题；Dark Mode 为后续阶段扩展（保留结构，不在本阶段实现）。
 */
@Composable
fun QianyanTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColors,
        typography = QianyanTypography,
        shapes = QianyanShapes,
        content = content,
    )
}
