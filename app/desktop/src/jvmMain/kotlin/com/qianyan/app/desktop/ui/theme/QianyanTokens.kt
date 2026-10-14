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
 * 视觉基调：**安静的编辑器**，不是 Dashboard。
 *  - 低饱和中性色；accent 只是灰阶里的一个"深一点/亮一点"的层次，不做彩色强调；
 *  - 层次靠空间、分隔线与背景级差，不靠阴影与卡片；
 *  - 圆角 2 / 3 / 4（窗口 6），编辑区与列表 0；
 *  - 阴影只用于浮层（Command Palette / Dialog / Settings Window）。
 *
 * 层次约定：
 *  - [QianyanPalette.bg]      = 内容画布（正文编辑区；视觉焦点）
 *  - [QianyanPalette.surface] = 界面框架（Sidebar / Inspector / TopBar / StatusBar）
 *  - [QianyanPalette.raised]  = 浮层（Dialog / Palette / 设置窗口）
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
 * Dark（**纯黑**：画布与所有面板 / 浮层同为 #000000）。
 *
 * 层次**只靠 1px 分隔线**（line / lineStrong）区分，不靠背景色；
 * soft 只用于 hover / 列表选中 / 当前对象高亮。
 *
 * 字段对应（设计规格 → 本类字段）：
 *  bg→bg · panel→surface · panel2→raised · soft→soft · line→line · line2→lineStrong
 *  · accent→accent · accentInk→accent（强调色文字直接用 accent）· selection→selection
 *  · add→add · addBg→addSoft · del→del · delBg→delSoft
 *  onAccent = 强调填充上的文字
 */
val QianyanDarkPalette = QianyanPalette(
    bg = Color(0xFF000000),
    surface = Color(0xFF000000),
    raised = Color(0xFF000000),
    soft = Color(0xFF1A1A1A),
    line = Color(0xFF262626),
    lineStrong = Color(0xFF333333),
    text = Color(0xFFF0F0F0),
    text2 = Color(0xFFA0A0A0),
    text3 = Color(0xFF6A6A6A),
    accent = Color(0xFFDDA56C),
    onAccent = Color(0xFF1A1A1A),
    accentSoft = Color(0x3DDDA56C),
    selection = Color(0x3DDDA56C),
    add = Color(0xFF4ADE80),
    addSoft = Color(0x244ADE80),
    del = Color(0xFFF87171),
    delSoft = Color(0x24F87171),
)

/**
 * Light（**纯白**：画布与所有面板 / 浮层同为 #FFFFFF）。
 *
 * 三栏层次**只靠 1px 分隔线**（line / lineStrong）区分，不靠背景色；
 * soft 只用于 hover / 列表选中 / 当前对象高亮，普通列表项背景为纯白。
 * 字段对应同 Dark。onAccent = 白（与原型 .btn.primary 一致）。
 */
val QianyanLightPalette = QianyanPalette(
    bg = Color(0xFFFFFFFF),
    surface = Color(0xFFFFFFFF),
    raised = Color(0xFFFFFFFF),
    soft = Color(0xFFF5F5F5),
    line = Color(0xFFE5E5E5),
    lineStrong = Color(0xFFD0D0D0),
    text = Color(0xFF1A1A1A),
    text2 = Color(0xFF666666),
    text3 = Color(0xFF999999),
    accent = Color(0xFFB87333),
    onAccent = Color(0xFFFFFFFF),
    accentSoft = Color(0x1FB87333),
    selection = Color(0x1FB87333),
    add = Color(0xFF16A34A),
    addSoft = Color(0x1A16A34A),
    del = Color(0xFFDC2626),
    delSoft = Color(0x14DC2626),
)

/** 尺寸 Token：IDE 密度 + 正文呼吸感。 */
object QianyanDimens {
    val TopBar: Dp = 44.dp
    val StatusBar: Dp = 24.dp
    val Sidebar: Dp = 216.dp
    val Inspector: Dp = 300.dp

    /** 正文栏最大宽度（长文本可读性上限）。 */
    val EditorWidth: Dp = 700.dp

    /** 页面内容留白。 */
    val PageX: Dp = 28.dp
    val PageY: Dp = 22.dp

    /** 面板 / 分组留白。 */
    val Gutter: Dp = 14.dp

    /** 单行高度（列表 / 导航）。 */
    val Row: Dp = 28.dp
    val RowLoose: Dp = 34.dp

    /** 正文字号（作者可调档位）。 */
    val EditorText: TextUnit = 18.sp
}

/** 圆角 Token：2 / 3 / 4（窗口 6）；编辑区与列表 0。 */
object QianyanShapes {
    val r: Dp = 2.dp
    val r2: Dp = 3.dp
    val r3: Dp = 4.dp
    val window: Dp = 6.dp
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
