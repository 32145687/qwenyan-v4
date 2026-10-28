package com.qianyan.app.desktop.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import com.qianyan.app.desktop.ui.theme.QianyanTheme

/**
 * Apple 式 hover 底色：140ms 淡入淡出，只做视觉反馈、不消费点击。
 *
 * 调用方负责 clip；本 modifier 会在已有背景之上再叠一层 hover 色。
 */
@Composable
fun Modifier.hoverBackground(
    shape: Shape,
    color: Color = QianyanTheme.palette.hover,
    enabled: Boolean = true,
): Modifier {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val bg by animateColorAsState(
        targetValue = if (enabled && hovered) color else Color.Transparent,
        animationSpec = tween(140),
        label = "hover-background",
    )
    return this.background(bg, shape).hoverable(source)
}

/** 共享的 hover 状态（需要同时改边框 / 阴影 / 文字色时使用）。 */
@Composable
fun rememberHover(): Pair<MutableInteractionSource, Boolean> {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    return source to hovered
}
