package com.qianyan.app.desktop.ui.manage

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.sp
import com.qianyan.app.desktop.ui.BreathingSparkle
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.qianyan.app.desktop.ui.DesktopAppState
import com.qianyan.app.desktop.ui.EmptyHint
import com.qianyan.app.desktop.ui.QianyanCard
import com.qianyan.app.desktop.ui.QianyanChip
import com.qianyan.app.desktop.ui.Section
import com.qianyan.app.desktop.ui.SectionHeader
import com.qianyan.app.desktop.ui.StatusTag
import com.qianyan.app.desktop.ui.theme.QianyanColors
import com.qianyan.model.ChapterId
import com.qianyan.model.core.Novel
import com.qianyan.model.story.Chapter

/**
 * 05 故事管理（只读观察面板）。
 * 真实能力（全部只读展示）：
 *  - Story State 六类读取：storyState.listCharacters / listWorldRules / listEvents / listTimelineEntries / listForeshadows
 *  - Narrative State：narrativeState.getNarrativeState（叙事账本投影）
 *  - Chapter Context Pack：chapterContextPack.compileChapterContext（确定性上下文包预览）
 *  - Reveal：reveals.listByScope（揭示记录）
 * 写入类能力（Story State Variant Override / Foreshadow 生命周期流转 / Reveal 创建）为 Variant-only，
 * Original 只读——面板给出说明，不做假按钮。
 */
@Composable
fun ManageScreen(state: DesktopAppState) {
    val novel: Novel? = state.novel
    Column(Modifier.fillMaxSize()) {
        SectionHeader(Section.MANAGE)
        if (novel == null) {
            EmptyHint("尚未打开作品——回到「首页」打开一本书，这里展示它此刻的故事生命状态。")
            return
        }
        ManageTabs(state, novel)
    }
}

private enum class ManageTab(val label: String) {
    // 「故事现在走到哪里了」放首位——这是 05 的核心视角
    NARRATIVE("故事生命状态"),
    FORESHADOW("伏笔"),
    KNOWLEDGE("知识边界"),
    CHARACTERS("人物"),
    WORLD("世界规则"),
    EVENTS("事件"),
    TIMELINE("时间线"),
    PACK("上下文包"),
}

@Composable
private fun ManageTabs(state: DesktopAppState, novel: Novel) {
    var tab by remember { mutableStateOf(ManageTab.NARRATIVE) }
    Column(Modifier.fillMaxSize().padding(horizontal = 34.dp)) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            ManageTab.entries.forEachIndexed { i, t ->
                SegmentedButton(
                    selected = tab == t,
                    onClick = { tab = t },
                    shape = SegmentedButtonDefaults.itemShape(index = i, count = ManageTab.entries.size),
                ) { Text(t.label, style = MaterialTheme.typography.labelMedium) }
            }
        }
        Spacer(Modifier.height(14.dp))
        when (tab) {
            ManageTab.NARRATIVE -> NarrativePane(state, novel)
            ManageTab.FORESHADOW -> ForeshadowPane(state, novel)
            ManageTab.KNOWLEDGE -> KnowledgeBoundaryPane(state, novel)
            ManageTab.CHARACTERS -> CharactersPane(state, novel)
            ManageTab.WORLD -> WorldPane(state, novel)
            ManageTab.EVENTS -> EventsPane(state, novel)
            ManageTab.TIMELINE -> TimelinePane(state, novel)
            ManageTab.PACK -> PackPane(state, novel)
        }
    }
}

@Composable
private fun CharactersPane(state: DesktopAppState, novel: Novel) {
    var items by remember(novel.novelId, state.refreshKey) { mutableStateOf(emptyList<com.qianyan.model.character.Character>()) }
    LaunchedEffect(novel.novelId, state.refreshKey) {
        items = state.load { it.storyState.listCharacters(novel.novelId, null) }
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (items.isEmpty()) item { EmptyHint("还没有人物——他们会在创作流程的知识更新阶段进入故事事实（Knowledge Update）。") }
        items(items) { c ->
            QianyanCard {
                Text(c.name, style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Serif)
                if (c.description.isNotBlank()) {
                    Text(c.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun WorldPane(state: DesktopAppState, novel: Novel) {
    var items by remember(novel.novelId, state.refreshKey) { mutableStateOf(emptyList<com.qianyan.model.world.WorldRule>()) }
    LaunchedEffect(novel.novelId, state.refreshKey) {
        items = state.load { it.storyState.listWorldRules(novel.novelId, null) }
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (items.isEmpty()) item { EmptyHint("还没有世界规则。") }
        items(items) { r ->
            QianyanCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text(r.content, style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Serif, modifier = Modifier.weight(1f))
                    if (r.category.isNotBlank()) QianyanChip(r.category)
                }
            }
        }
    }
}

@Composable
private fun EventsPane(state: DesktopAppState, novel: Novel) {
    var items by remember(novel.novelId, state.refreshKey) { mutableStateOf(emptyList<com.qianyan.model.timeline.Event>()) }
    LaunchedEffect(novel.novelId, state.refreshKey) {
        items = state.load { it.storyState.listEvents(novel.novelId, null) }
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (items.isEmpty()) item { EmptyHint("还没有事件。") }
        items(items) { e ->
            QianyanCard {
                Text(e.name, style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Serif)
                if (e.description.isNotBlank()) {
                    Text(e.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun TimelinePane(state: DesktopAppState, novel: Novel) {
    var items by remember(novel.novelId, state.refreshKey) { mutableStateOf(emptyList<com.qianyan.model.timeline.TimelineEntry>()) }
    LaunchedEffect(novel.novelId, state.refreshKey) {
        items = state.load { it.storyState.listTimelineEntries(novel.novelId, null) }
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (items.isEmpty()) item { EmptyHint("还没有时间线条目。") }
        items(items) { t ->
            QianyanCard { Text(t.toString(), style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

@Composable
private fun ForeshadowPane(state: DesktopAppState, novel: Novel) {
    var items by remember(novel.novelId, state.refreshKey) { mutableStateOf(emptyList<com.qianyan.model.story.Foreshadow>()) }
    var orderOfChapter by remember(novel.novelId, state.refreshKey) { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var latestOrder by remember(novel.novelId, state.refreshKey) { mutableStateOf(0) }
    LaunchedEffect(novel.novelId, state.refreshKey) {
        items = state.load { it.storyState.listForeshadows(novel.novelId, null) }
        val chs = state.load { it.chapters.listByNovel(novel.novelId) }
        orderOfChapter = chs.associate { it.chapterId.value to it.order }
        latestOrder = chs.maxOfOrNull { it.order } ?: 0
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        QianyanCard(Modifier.padding(bottom = 12.dp)) {
            Text("伏笔的生命线", style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Serif)
            Text(
                "后端状态机：PLANTED → ACTIVE → RESOLVED / ABANDONED（非法过渡与同态转换会被拒绝）。" +
                    "下面的分组与「已 N 章未推进」都由真实数据算出；流转操作是 Variant-only，Original 只读。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf(
                Triple("PLANTED", "已埋下", QianyanColors.Ink3Light),
                Triple("ACTIVE", "推进中", QianyanColors.AmberLight),
                Triple("RESOLVED", "已回收", QianyanColors.OkLight),
                Triple("ABANDONED", "已放弃", QianyanColors.Ink3Light),
            ).forEach { (stateName, label, color) ->
                val group = items.filter { it.state.name == stateName }
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        Box(Modifier.size(8.dp).clip(RoundedCornerShape(8.dp)).background(color))
                        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${group.size}", style = MaterialTheme.typography.labelMedium, color = color)
                    }
                    Spacer(Modifier.height(8.dp))
                    if (group.isEmpty()) {
                        Text("—", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(start = 4.dp))
                    }
                    group.forEach { f ->
                        val plantedOrder = f.chapterId?.let { orderOfChapter[it.value] }
                        val quiet = if (plantedOrder != null && stateName == "PLANTED" && latestOrder > plantedOrder) latestOrder - plantedOrder else null
                        Column(
                            Modifier.fillMaxWidth().padding(bottom = 10.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surface)
                                .padding(12.dp),
                        ) {
                            Text(f.content, style = MaterialTheme.typography.bodyLarge, fontFamily = FontFamily.Serif)
                            val meta = buildString {
                                if (plantedOrder != null) append("埋于第 $plantedOrder 章")
                                f.payoffChapterId?.let { pid -> orderOfChapter[pid.value]?.let { append(" · 回收于第 $it 章") } }
                                if (quiet != null) append(" · 已 $quiet 章未推进")
                            }
                            if (meta.isNotBlank()) {
                                Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 3.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NarrativePane(state: DesktopAppState, novel: Novel) {
    var ns by remember(novel.novelId, state.refreshKey) { mutableStateOf<com.qianyan.model.lcl.NarrativeState?>(null) }
    var chapterCount by remember(novel.novelId, state.refreshKey) { mutableStateOf(0) }
    var waitingCount by remember(novel.novelId, state.refreshKey) { mutableStateOf(0) }
    var foreshadowSummary by remember(novel.novelId, state.refreshKey) { mutableStateOf<List<String>>(emptyList()) }

    LaunchedEffect(novel.novelId, state.refreshKey) {
        ns = state.load { it.narrativeState.getNarrativeState(novel.novelId, null) }
        val loaded = state.load { c ->
            val chs = c.chapters.listByNovel(novel.novelId)
            val waiting = chs.count { c.workflowFacade.getChapterProgress(it.chapterId).waitingForUser }
            val fs = c.storyState.listForeshadows(novel.novelId, null)
            Triple(chs.size, waiting, fs.groupBy { it.state.name }.map { (k, v) -> "$k ${v.size}" })
        }
        chapterCount = loaded.first
        waitingCount = loaded.second
        foreshadowSummary = loaded.third
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        QianyanCard {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                LifeOrb(
                    mainGoal = ns?.mainGoal,
                    version = ns?.version ?: 0,
                    chapterCount = chapterCount,
                )
                Column(Modifier.weight(1f)) {
                    Text("故事现在走到哪里了", style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Serif)
                    Text(
                        "Story State 说「世界发生了什么」；Narrative State 说「故事现在走到哪里了」。" +
                            "下面是这本书此刻的呼吸——不是进度条。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Spacer(Modifier.height(14.dp))
                    val n = ns
                    if (n == null) {
                        Text(
                            "还没有叙事账本——它由创作流程（Knowledge Update → Narrative Delta）逐章折叠生成。写完并确认一章后回来看这里。",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline,
                        )
                    } else {
                        SpineItem("主线目标", n.mainGoal.ifBlank { "尚未写入（等下一章的叙事增量）" })
                        SpineItem("叙事版本", "v${n.version} · 已折叠 ${n.version} 个叙事增量")
                        SpineItem("未收束线索", if (n.openThreads.isEmpty()) "无" else n.openThreads.joinToString("；") { it.description })
                        if (n.characterStages.isNotEmpty()) {
                            SpineItem("人物成长阶段", n.characterStages.entries.take(5).joinToString("；") { "${it.key.value.take(8)}: ${it.value}" })
                        }
                    }
                }
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            QianyanCard(Modifier.weight(1f)) {
                Text("此刻的体征", style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Serif)
                Spacer(Modifier.height(10.dp))
                SpineItem("章节规模", "$chapterCount 章")
                SpineItem("等待你确认", if (waitingCount > 0) "$waitingCount 章停在人工确认门" else "无待确认章节")
                SpineItem("伏笔分布", if (foreshadowSummary.isEmpty()) "尚未埋下伏笔" else foreshadowSummary.joinToString(" · "))
            }
            QianyanCard(Modifier.weight(1f)) {
                Text("这本书的下一口气", style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Serif)
                Spacer(Modifier.height(10.dp))
                Text(
                    if (waitingCount > 0) "有 $waitingCount 章在等你确认——确认之后，变化才会写入故事事实（Story State）。"
                    else "没有待确认的变化。可以继续写下一章，或在正文页用「AI 重写本章」按新方向再来一版。",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "上下文包（本章 AI 会读取什么）在「上下文包」页可实时编译预览。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

/** 故事生命状态的呼吸球：光晕呼吸 + 外环缓慢旋转（设计规范：柔和、连续、无炫技）。 */
@Composable
private fun LifeOrb(mainGoal: String?, version: Long, chapterCount: Int, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "lifeOrb")
    val breath by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(tween(3200, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breath",
    )
    val spin by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(45000, easing = LinearEasing)),
        label = "spin",
    )
    val soft = MaterialTheme.colorScheme.secondaryContainer
    val line = QianyanColors.AiLight

    Box(modifier.size(196.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val radius = size.minDimension / 2f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(soft.copy(alpha = 0.9f), soft.copy(alpha = 0.15f), Color.Transparent),
                    center = center,
                    radius = radius,
                ),
                radius = radius * breath,
                center = center,
            )
            rotate(spin) {
                drawCircle(
                    color = line.copy(alpha = 0.5f),
                    radius = radius * 0.97f,
                    center = center,
                    style = Stroke(width = 1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(7f, 9f), 0f)),
                )
            }
            drawCircle(color = line.copy(alpha = 0.55f), radius = radius * 0.78f, center = center, style = Stroke(width = 1.2f))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "NARRATIVE STATE",
                style = MaterialTheme.typography.labelMedium,
                letterSpacing = 2.sp,
                color = QianyanColors.AiLight,
            )
            Text(
                if (mainGoal.isNullOrBlank()) "尚未成型" else "已成型",
                style = MaterialTheme.typography.titleLarge,
                fontFamily = FontFamily.Serif,
            )
            Text("v$version · $chapterCount 章", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }
    }
}

/** 脊线条目：左侧渐变竖线 + 节点圆点（原型里的 spine，表达「生长」）。 */
@Composable
private fun SpineItem(label: String, value: String) {
    Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
        Canvas(Modifier.padding(top = 4.dp).size(width = 14.dp, height = 14.dp)) {
            drawCircle(color = QianyanColors.AiLight, radius = size.minDimension / 4.5f)
        }
        Column {
            Text(label, style = MaterialTheme.typography.labelMedium, color = QianyanColors.AiLight)
            Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

/**
 * 知识边界：「谁知道什么？」——悬疑的心脏。
 * 读者视角用**真实 Reveal 记录**（RevealUseCases.listByScope）；
 * 角色视角后端目前没有「角色认知模型」，如实标注而不编造。
 */
@Composable
private fun KnowledgeBoundaryPane(state: DesktopAppState, novel: Novel) {
    var reveals by remember(novel.novelId, state.refreshKey) { mutableStateOf<List<com.qianyan.model.story.Reveal>>(emptyList()) }
    var characters by remember(novel.novelId, state.refreshKey) { mutableStateOf<List<com.qianyan.model.character.Character>>(emptyList()) }
    var failed by remember(novel.novelId, state.refreshKey) { mutableStateOf(false) }

    LaunchedEffect(novel.novelId, state.refreshKey) {
        characters = state.load { it.storyState.listCharacters(novel.novelId, null) }
        val r = runCatching {
            state.load { it.reveals.listByScope(state.ctx(novel.novelId)) }
        }
        failed = r.isFailure
        reveals = r.getOrDefault(emptyList())
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        QianyanCard {
            Text("谁知道什么？", style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Serif)
            Text(
                "信息差是小说的资产。这里记录「读者已经知道什么」——来自真实的 Reveal 记录" +
                    "（Reader-only，不进叙事状态与上下文包，避免 AI 把读者已知当成角色已知）。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        QianyanCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BreathingSparkle(fontSize = 13.sp)
                Text("读者视角（真实数据）", style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Serif)
                QianyanChip("${reveals.size} 条揭示", QianyanColors.AiSoftLight)
            }
            Spacer(Modifier.height(10.dp))
            when {
                failed -> Text("Reveal 列表读取失败（该能力为 Variant-only，Original 作用域下可能不可用）。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                reveals.isEmpty() -> Text(
                    "还没有揭示记录。Reveal 是「读者刚刚知道了什么」的记账——由写作流程在揭示发生时写入。",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline,
                )
                else -> reveals.forEach { r ->
                    Row(Modifier.padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("✓", color = QianyanColors.OkLight)
                        Text(r.toString(), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }

        QianyanCard {
            Text("角色视角", style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Serif)
            Spacer(Modifier.height(8.dp))
            Text(
                "后端当前只有人物的静态属性（名字 / 性格 / 目标 / 恐惧），**没有「角色认知模型**」" +
                    "——也就是无法表达「沈昭不知道苏清的真实身份」这类信息。",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            Text("现有的人物（可作为未来认知边界的锚点）：", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            if (characters.isEmpty()) {
                Text("（还没有人物）", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                    characters.take(8).forEach { QianyanChip(it.name, MaterialTheme.colorScheme.secondaryContainer) }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "要做成原型里那种「作者知道 / 读者知道 / 角色 A 不知道」的四视角切换，需要后端补一层角色认知模型（角色 × 事实 × 是否已知）" +
                    "——这属于新能力，我没有用假数据填这个界面。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
private fun PackPane(state: DesktopAppState, novel: Novel) {
    var chapters by remember(novel.novelId, state.refreshKey) { mutableStateOf<List<Chapter>>(emptyList()) }
    var selected by remember(novel.novelId) { mutableStateOf<String?>(null) }
    var pack by remember(novel.novelId, state.refreshKey) { mutableStateOf<com.qianyan.model.lcl.ChapterContextPack?>(null) }

    LaunchedEffect(novel.novelId, state.refreshKey) {
        chapters = state.load { it.chapters.listByNovel(novel.novelId) }
    }
    LaunchedEffect(selected, state.refreshKey) {
        val cid = selected ?: return@LaunchedEffect
        pack = state.load { it.chapterContextPack.compileChapterContext(novel.novelId, null, ChapterId(cid)) }
    }

    Column(Modifier.fillMaxSize()) {
        QianyanCard(Modifier.padding(bottom = 12.dp)) {
            Text("Chapter Context Pack · 本章 AI 正在读取什么", style = MaterialTheme.typography.titleMedium)
            Text(
                "确定性、有界的章节上下文包（窗口 + 预算）。选择一章即可预览真实编译结果——这正是写作 Agent 的输入。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (chapters.isEmpty()) { EmptyHint("还没有章节。"); return }
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            chapters.take(6).forEachIndexed { i, ch ->
                SegmentedButton(
                    selected = selected == ch.chapterId.value,
                    onClick = { selected = ch.chapterId.value },
                    shape = SegmentedButtonDefaults.itemShape(index = i, count = minOf(chapters.size, 6)),
                ) { Text("第${ch.order}章", style = MaterialTheme.typography.labelMedium) }
            }
        }
        Spacer(Modifier.height(12.dp))
        val p = pack
        if (p == null) EmptyHint("选择一章编译上下文包。") else {
            QianyanCard {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QianyanChip("pack v${p.packVersion}")
                    QianyanChip("窗口 ${p.horizonWindow.size} 章", QianyanColors.AmberSoftLight)
                    QianyanChip("活动伏笔 ${p.activeForeshadows.size}")
                }
                Spacer(Modifier.height(10.dp))
                p.activeNarrative?.let { an ->
                    Text("内置 Narrative State：${an.mainGoal.ifBlank { "（未设定）" }}", style = MaterialTheme.typography.bodyMedium)
                }
                if (p.activeThreads.isNotEmpty()) {
                    Text("活动线索：${p.activeThreads.joinToString("；") { it.description }}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (p.recentEvents.isNotEmpty()) {
                    Text("近期事件：${p.recentEvents.joinToString("；") { it.name }}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
