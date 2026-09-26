package com.qianyan.app.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qianyan.app.desktop.ui.theme.QianyanColors
import com.qianyan.model.core.Novel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 顶部一级板块（与设计稿 01–07 一致，不新增一级导航）。 */
enum class Section(val no: String, val title: String, val sub: String) {
    HOME("01", "首页", "作品空间"),
    STORY("02", "故事创作", "从一个想法开始 · P14+"),
    PLAN("03", "小说规划", "把故事变成一部长篇"),
    WRITE("04", "正文创作", "数字书房"),
    MANAGE("05", "故事管理", "观察这本小说变成了什么"),
    BOOK("06", "成书", "一本正在完成的书"),
    AUTHOR("07", "作者智能", "P16+ · 千言正在理解你"),
}

/**
 * 应用级 UI 状态（PC 侧持有；业务能力全部经 [com.qianyan.application.di.ApplicationContainer]
 * 暴露的真实 UseCase 访问，不直接触碰 Repository / SqlDriver / 工作流内部对象）。
 */
class DesktopAppState(val graph: com.qianyan.app.desktop.di.DesktopGraph) {
    val scope = CoroutineScope(Dispatchers.Main)

    var section by mutableStateOf(Section.HOME)
    var novel by mutableStateOf<Novel?>(null)
    var toast by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)
    var refreshKey by mutableStateOf(0)
    var providerSettingsOpen by mutableStateOf(false)

    fun toast(msg: String) {
        toast = msg
        scope.launch {
            kotlinx.coroutines.delay(3600)
            if (toast == msg) toast = null
        }
    }

    /** 后台执行 + 统一错误提示（ApplicationError → 用户语言）。 */
    fun launchWork(toastOnDone: String? = null, block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) { block() }
                toastOnDone?.let { toast(it) }
            } catch (e: Exception) {
                toast("出错了：${e.message ?: e.javaClass.simpleName}")
            } finally {
                busy = false
                refreshKey++
            }
        }
    }

    /**
     * 读取数据：IO 线程查询，**回到主线程返回**，调用方在 LaunchedEffect 里赋值给 Compose state。
     * 不要用 launchWork 给 state 赋值——block 跑在 IO 线程，可能不触发重组
     * （症状就是「操作成功但界面不刷新」）。
     */
    suspend fun <T> load(block: (com.qianyan.application.di.ApplicationContainer) -> T): T =
        withContext(Dispatchers.IO) { block(container) }

    val container get() = graph.container
}

/** 千言卡片容器。 */
@Composable
fun QianyanCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(22.dp), content = content)
    }
}

/** 小标签（chip）。 */
@Composable
fun QianyanChip(text: String, accent: Color = MaterialTheme.colorScheme.secondaryContainer) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(accent)
            .padding(horizontal = 12.dp, vertical = 4.dp),
    )
}

/**
 * 千言 Sparkle：AI 的呼吸。
 * 设计规范要求「短、柔和、连续、有目的」——3.2s 的缩放 + 透明度往返，禁止弹跳与炫技。
 */
@Composable
fun BreathingSparkle(
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 14.sp,
    color: Color = QianyanColors.AiLight,
) {
    val transition = rememberInfiniteTransition(label = "sparkle")
    val scale by transition.animateFloat(
        initialValue = 0.86f,
        targetValue = 1.14f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "scale",
    )
    val alpha by transition.animateFloat(
        initialValue = 0.72f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "alpha",
    )
    Text(
        "✦",
        modifier = modifier.graphicsLayer {
            scaleX = scale
            scaleY = scale
            this.alpha = alpha
        },
        color = color,
        fontSize = fontSize,
    )
}

/**
 * 柔和脉冲点：表示「这里正在等待/正在发生」。
 * 对应设计稿里等待确认门的呼吸圈（柔和扩散，无粒子、无爆炸）。
 */
@Composable
fun PulsingDot(color: Color, size: Dp = 8.dp) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val ringScale by transition.animateFloat(
        initialValue = 1f,
        targetValue = 2.6f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 2000, easing = LinearEasing)),
        label = "ring",
    )
    val ringAlpha by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 2000, easing = LinearEasing)),
        label = "ringAlpha",
    )
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(size).graphicsLayer {
                scaleX = ringScale
                scaleY = ringScale
                alpha = ringAlpha
            }.clip(RoundedCornerShape(size)).background(color),
        )
        Box(Modifier.size(size).clip(RoundedCornerShape(size)).background(color))
    }
}

/** 状态标签（检查等级语义色）。 */
@Composable
fun StatusTag(text: String, color: Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 9.dp, vertical = 3.dp),
    )
}

/** 章节 / 流程阶段语义色（与设计稿的状态标签一致）。 */
fun statusColor(name: String): Color = when (name) {
    "PLANNED", "NOT_STARTED" -> QianyanColors.Ink3Light
    "DRAFTING", "PLANNING", "WRITING", "REVIEWING", "REVISING" -> QianyanColors.WatchLight
    "WRITTEN", "REVISED", "FINAL", "PENDING_CONFIRMATION", "WAITING_CONFIRMATION" -> QianyanColors.AiLight
    "CONFIRMED", "COMPLETED", "UPDATING_STORY" -> QianyanColors.OkLight
    "FAILED" -> QianyanColors.HighLight
    else -> QianyanColors.Ink3Light
}

/**
 * 「当前阶段未接入」占位：页面骨架保留设计，但不伪造尚未接线的真实能力。
 * [stage] 必须写明该能力属于哪个后续阶段，[lines] 写明届时接线的真实 UseCase。
 */
@Composable
fun ComingSoon(title: String, lines: List<String>, stage: String) {
    Column(
        Modifier.fillMaxSize().padding(40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("✦", fontSize = 34.sp, color = QianyanColors.AiLight)
        Spacer(Modifier.height(14.dp))
        Text(title, style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(stage, style = MaterialTheme.typography.labelLarge, color = QianyanColors.AiLight)
        Spacer(Modifier.height(22.dp))
        QianyanCard(Modifier.width(460.dp)) {
            lines.forEach {
                Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
                    Text("·", color = QianyanColors.AiLight, modifier = Modifier.width(14.dp))
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        Text("页面骨架已保留 · 业务能力经 ApplicationContainer 在后续阶段接入 · 当前不提供假按钮", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
    }
}

/** 空数据提示。 */
@Composable
fun EmptyHint(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.outline,
        modifier = modifier.fillMaxWidth().padding(vertical = 26.dp),
    )
}

/** 统一页头（板块编号 + 标题 + 副题 + 动作区）。 */
@Composable
fun SectionHeader(section: Section, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 34.dp, vertical = 18.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(section.title, style = MaterialTheme.typography.headlineMedium)
            Text("${section.no} · ${section.sub}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), content = actions)
    }
}