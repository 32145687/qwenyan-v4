package com.qianyan.app.desktop.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font as PlatformFont
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * Qianyan Writing IDE · Design Token（全站唯一视觉事实来源）。
 *
 * P0-UI · 视觉基线对齐「温润暖白」（内容优先、留白、克制）：
 *  - **浅色**：暖白画布 #FAF9F6，界面框架为暖米 #F2F0EB，浮层 / 稿纸为 #FFFFFF；
 *    层次靠暖灰级差 + 1px 极轻分隔线，不用冷灰；
 *  - **深色**：画布为舒适的深灰（#1C1C1E），框架更深（#0E0E10），浮层更亮（#2C2C2E）——不是简单反色；
 *  - 唯一强调色为暖蓝（浅色 #1769D2 / 深色 #0A84FF），只用于关键动作、焦点与当前选择；
 *  - 圆角柔和（6 / 8 / 11 / 16），按钮为胶囊形；阴影极轻，只用于浮层与主输入区；
 *  - 间距按 4 / 8 / 12 / 16 / 24 / 32 / 48 节奏。
 *
 * 层次约定：
 *  - [QianyanPalette.bg]      = 内容画布（正文 / 主工作区；视觉焦点）
 *  - [QianyanPalette.surface] = 界面框架（Sidebar / Inspector / TopBar / StatusBar）
 *  - [QianyanPalette.raised]  = 浮层（Dialog / Palette / Settings / Toast）
 */

/** 主题模式（纯 UI 显示偏好；不落库、不进领域模型）。 */
enum class QianyanThemeMode { SYSTEM, LIGHT, DARK }

/** 整套色板（Dark / Light 同一套字段名，保证两种主题共用一套设计语言）。 */
data class QianyanPalette(
    /** 内容画布（正文）。 */
    val bg: Color,
    /** 界面框架（导航 / Inspector / 顶栏 / 状态栏）。 */
    val surface: Color,
    /** 浮层（Dialog / Palette / 设置窗口）。 */
    val raised: Color,
    /** 静默填充（hover / 次级按钮 / 选区底）。 */
    val soft: Color,
    /** 指针悬停底色（Apple 式浅一档，带 140ms 过渡）。 */
    val hover: Color,
    /** 1px 分隔线（默认）。 */
    val line: Color,
    /** 结构线（输入框边框 / 需要更明确边界时）。 */
    val lineStrong: Color,
    val text: Color,
    val text2: Color,
    val text3: Color,
    /** 唯一强调色（当前选择 / 关键动作 / 焦点 / 链接）。 */
    val accent: Color,
    /** 强调色上的文字。 */
    val onAccent: Color,
    /** 强调色的极轻底色（当前选中行 / Ghost 主按钮底）。 */
    val accentSoft: Color,
    /** 列表当前项底色。 */
    val selection: Color,
    /** Diff 新增（低饱和）。 */
    val add: Color,
    val addSoft: Color,
    /** Diff 删除（低饱和）。 */
    val del: Color,
    val delSoft: Color,
)

/**
 * Dark（**暖调深色分层**：画布 #1B1B18 / 框架 #141412 / 浮层 #232320）。
 *
 * 与浅色同一套暖中性色相，避免切到深色后变成冷灰；强调色使用更亮的暖蓝。
 */
val QianyanDarkPalette = QianyanPalette(
    bg = Color(0xFF1B1B18),
    surface = Color(0xFF141412),
    raised = Color(0xFF232320),
    soft = Color(0xFF2A2A26),
    hover = Color(0xFF32322D),
    line = Color(0xFF2E2E29),
    lineStrong = Color(0xFF3B3B35),
    text = Color(0xFFF2F0EA),
    text2 = Color(0xFFC9C5BB),
    text3 = Color(0xFF928D82),
    accent = Color(0xFF4C8FF5),
    onAccent = Color(0xFFFFFFFF),
    accentSoft = Color(0x334C8FF5),
    selection = Color(0x2E4C8FF5),
    add = Color(0xFF5FBF7A),
    addSoft = Color(0x245FBF7A),
    del = Color(0xFFE06A63),
    delSoft = Color(0x24E06A63),
)

/**
 * Light（**温润暖白**：画布 #FAF9F6 / 框架 #F2F0EB / 浮层与稿纸 #FFFFFF）。
 *
 * 文字使用暖中性层级（#252722 / #777970 / #92938B）；
 * 强调色为暖蓝 #1769D2；选中态为极轻蓝底 #E4EEFF，不抢注意力。
 */
val QianyanLightPalette = QianyanPalette(
    bg = Color(0xFFFAF9F6),
    surface = Color(0xFFF2F0EB),
    raised = Color(0xFFFFFFFF),
    soft = Color(0xFFF4F2EE),
    hover = Color(0xFFEBE8E1),
    line = Color(0xFFEAE7E0),
    lineStrong = Color(0xFFE5E1D9),
    text = Color(0xFF252722),
    text2 = Color(0xFF777970),
    text3 = Color(0xFF92938B),
    accent = Color(0xFF1769D2),
    onAccent = Color(0xFFFFFFFF),
    accentSoft = Color(0xFFE4EEFF),
    selection = Color(0xFFE4EEFF),
    add = Color(0xFF2E7D51),
    addSoft = Color(0x1A2E7D51),
    del = Color(0xFFB4433C),
    delSoft = Color(0x1AB4433C),
)

/** 尺寸 Token：IDE 密度 + 正文呼吸感（间距节奏 4 / 8 / 12 / 16 / 24 / 32 / 48）。 */
object QianyanDimens {
    val TopBar: Dp = 52.dp
    val StatusBar: Dp = 28.dp
    val Sidebar: Dp = 262.dp
    val Inspector: Dp = 312.dp

    /** 正文栏最大宽度（长文本可读性上限）。 */
    val EditorWidth: Dp = 756.dp

    /** 页面内容留白。 */
    val PageX: Dp = 32.dp
    val PageY: Dp = 24.dp

    /** 面板 / 分组留白。 */
    val Gutter: Dp = 16.dp

    /** 单行高度（列表 / 导航）。 */
    val Row: Dp = 30.dp
    val RowLoose: Dp = 38.dp

    /** 正文字号（作者可调档位）。 */
    val EditorText: TextUnit = 18.sp
}

/** 圆角 Token：6 / 8 / 11 / 16（窗口）；按钮为胶囊形。 */
object QianyanShapes {
    val r: Dp = 6.dp
    val r2: Dp = 8.dp
    val r3: Dp = 11.dp
    val window: Dp = 16.dp

    /** 胶囊（ActionButton / Tag）。 */
    val pill: Dp = 999.dp
}

/**
 * 阴影 Token（极轻；Pinguo 设计系统：边框优先、阴影只用于浮层 / 主输入区）。
 *
 * 用法：`Modifier.shadow(elevation, shape, clip = false)`；
 * 静止表面一律不投影，避免把界面切成大量独立卡片。
 */
object QianyanElevation {
    /** 主输入区 / Toast：几乎不可见的抬升。 */
    val Composer: Dp = 2.dp

    /** 浮层（Command Palette / Dialog）。 */
    val Overlay: Dp = 12.dp

    /** 模态（设置 / 新建作品）。 */
    val Modal: Dp = 20.dp
}

/** Motion Token：只用于 Tab / 浮层 / Focus / Inspector 更新。 */
object QianyanMotion {
    const val DurationMillis: Int = 160
    val Easing = CubicBezierEasing(0.2f, 0.6f, 0.3f, 1f)
}

/**
 * 字体（必须使用 **bundled** 字体资源，不把系统默认字体当设计基线）。
 *
 *  - UI / 中文：**Noto Sans SC**（`app/desktop/src/jvmMain/resources/fonts/NotoSansSC-VF.ttf`，OFL 1.1）；
 *  - 正文衬线：目标为 **Noto Serif SC / Songti SC**。
 *    ⚠ 阻塞项（需用户授权）：本机没有 OFL 衬线 CJK 字体，外部字体二进制下载未获授权，
 *    因此衬线族使用**显式命名的系统衬线**探测（Noto Serif SC → Songti SC → SimSun → Serif），
 *    已授权字体文件到位后只需替换本对象，调用点不变。
 */
object QianyanFonts {

    /** 打包的 UI 字体资源路径（classpath）。 */
    const val UI_RESOURCE: String = "fonts/NotoSansSC-VF.ttf"

    val ui: FontFamily by lazy {
        FontFamily(
            PlatformFont(UI_RESOURCE, FontWeight.Normal, FontStyle.Normal),
            PlatformFont(UI_RESOURCE, FontWeight.Medium, FontStyle.Normal),
            PlatformFont(UI_RESOURCE, FontWeight.SemiBold, FontStyle.Normal),
            PlatformFont(UI_RESOURCE, FontWeight.Bold, FontStyle.Normal),
        )
    }

    /** 正文衬线族（显式命名族，按优先级探测本机可用族）。 */
    val serif: FontFamily by lazy { firstAvailable(SERIF_CANDIDATES) { FontFamily.Serif } }

    /** 等宽（ID / 字数 / Diff 行号）。 */
    val mono: FontFamily by lazy { firstAvailable(MONO_CANDIDATES) { FontFamily.Monospace } }

    private val SERIF_CANDIDATES =
        listOf("Noto Serif SC", "Songti SC", "Source Han Serif SC", "SimSun", "SimSun-ExtB", "MS PMincho")
    private val MONO_CANDIDATES = listOf("JetBrains Mono", "Consolas", "Menlo", "Courier New")

    /** 探测本机可用字体族；找不到则使用给定的通用族（不发明字体）。 */
    @OptIn(
        androidx.compose.ui.ExperimentalComposeUiApi::class,
        androidx.compose.ui.text.ExperimentalTextApi::class,
    )
    private fun firstAvailable(candidates: List<String>, fallback: () -> FontFamily): FontFamily {
        val available = runCatching {
            java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.toSet()
        }.getOrDefault(emptySet())
        return candidates.firstOrNull { it in available }?.let { FontFamily(it) } ?: fallback()
    }
}

/** 当前色板（CompositionLocal；由 QianyanTheme 提供）。 */
val LocalQianyanPalette = staticCompositionLocalOf { QianyanLightPalette }

/** 当前是否深色（CompositionLocal；由 QianyanTheme 提供）。 */
val LocalQianyanDark = staticCompositionLocalOf { false }
