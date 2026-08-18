package com.qianyan.app.android.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Qianyan 主题色（P7.4，Apple-inspired 极简中性色）。
 *
 * 设计取向：近系统级中性色 + 单一强调色，避免复杂渐变与大量鲜艳色。
 * 后续 Dark Mode 在此文件补充对应的暗色集合即可。
 */
val QianyanBackground = Color(0xFFF2F2F7)     // 页面背景（近白，系统分组背景）
val QianyanSurface = Color(0xFFFFFFFF)       // 卡片 / 内容面
val QianyanTextPrimary = Color(0xFF1C1C1E)   // 主要文字（近黑，高对比）
val QianyanTextSecondary = Color(0xFF8E8E93) // 次级文字（柔和灰）
val QianyanAccent = Color(0xFF007AFF)        // 强调色（iOS 系统蓝）
val QianyanDivider = Color(0xFFE5E5EA)       // 分隔 / 描边（极浅灰）
