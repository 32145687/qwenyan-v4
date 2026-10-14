package com.qianyan.app.desktop.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.qianyan.app.desktop.ui.DesktopAppState
import com.qianyan.app.desktop.ui.EmptyState
import com.qianyan.app.desktop.ui.HLine
import com.qianyan.app.desktop.ui.LoadingState
import com.qianyan.app.desktop.ui.PageHeader
import com.qianyan.app.desktop.ui.SectionLabel
import com.qianyan.app.desktop.ui.Tag
import com.qianyan.app.desktop.ui.foreshadowStateLabel
import com.qianyan.app.desktop.ui.preview
import com.qianyan.app.desktop.ui.theme.QianyanCodeStyle
import com.qianyan.app.desktop.ui.theme.QianyanDimens
import com.qianyan.app.desktop.ui.theme.QianyanShapes
import com.qianyan.app.desktop.ui.theme.QianyanTheme
import com.qianyan.model.ForeshadowingId
import com.qianyan.model.TimelineEntryId
import com.qianyan.model.WorldRuleId
import com.qianyan.model.workspace.WorkspaceObjectKind
import com.qianyan.model.workspace.WorkspaceSelection

/* ---------------- 页内 Tab（下划线式，不是胶囊） ---------------- */

@Composable
private fun PageTabs(tabs: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val palette = QianyanTheme.palette
    Row(
        Modifier.fillMaxWidth().background(palette.surface)
            .padding(start = QianyanDimens.PageX, end = QianyanDimens.PageX, top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        tabs.forEachIndexed { index, label ->
            val on = index == selected
            // 下划线宽度 = 标签自身宽度（IntrinsicSize.Max），否则 fillMaxWidth 会吞掉整行
            Column(
                Modifier.clickable { onSelect(index) }.width(IntrinsicSize.Max),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (on) FontWeight.Medium else FontWeight.Normal,
                    color = if (on) palette.text else palette.text3,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
                Box(
                    Modifier.fillMaxWidth().height(2.dp)
                        .background(if (on) palette.accent else androidx.compose.ui.graphics.Color.Transparent),
                )
            }
        }
    }
    HLine()
}

/**
 * 12.3 · 故事（只有 故事基础 / 大纲 / 剧情 / 伏笔）。
 *
 * 真实来源：`storyFoundationRepository`（既有 StoryFoundation：题材 / 主题 / 冲突 / 承诺 /
 * 故事类型 / 视角 / 读者基调 / 写作规则）· Story State Event · Foreshadow。
 * 「大纲」在当前领域模型里**没有稳定 Canonical 来源** ⇒ 如实空态，不伪造结构树。
 */
@Composable
fun StoryPage(state: DesktopAppState) {
    val palette = QianyanTheme.palette
    var tab by remember { mutableStateOf(0) }
    var foundation by remember { mutableStateOf<StoryFoundationView?>(null) }
    var events by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var foreshadows by remember { mutableStateOf<List<ForeshadowRow>>(emptyList()) }
    var chapterTitles by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(state.novel?.novelId, state.refreshKey) {
        val novelId = state.novel?.novelId
        if (novelId == null) {
            loading = false
            return@LaunchedEffect
        }
        loading = true
        state.load {
            val f = it.storyFoundationRepository.getStoryFoundation(novelId)
            foundation = f?.let { found ->
                StoryFoundationView(
                    version = "v${found.version}",
                    genre = found.genre.joinToString(" · ") { g -> g.value },
                    theme = found.direction.theme,
                    conflict = found.direction.conflict,
                    promise = found.direction.promise,
                    storyType = found.direction.storyType,
                    pov = found.audience.pov,
                    readerTone = found.audience.readerTone,
                    rules = found.policy.rules,
                )
            }
            events = it.storyState.listEvents(novelId, null).map { e -> e.name to e.description }
            foreshadows = it.storyState.listForeshadows(novelId, null).map { f2 ->
                ForeshadowRow(
                    id = f2.foreshadowId.value,
                    content = f2.content,
                    state = f2.state.name,
                    chapterLabel = f2.chapterId?.value,
                )
            }
            chapterTitles = it.chapters.listByNovel(novelId, null)
                .associate { ch -> ch.chapterId.value to "第 ${ch.order} 章 ${ch.title}" }
        }
        loading = false
    }

    Column(Modifier.fillMaxSize()) {
        PageHeader("故事", meta = state.novel?.title)
        PageTabs(listOf("故事基础", "大纲", "剧情", "伏笔", "故事方向", "叙事账本", "上下文包", "滚动地平线"), tab) { tab = it }

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(QianyanDimens.PageX)) {
            when {
                state.novel == null -> EmptyState("尚未打开作品", "在「Qianyan Agent」中打开或新建作品")
                loading -> LoadingState()
                tab == 0 -> StoryFoundationBlock(foundation)
                tab == 1 -> EmptyState(
                    "大纲没有稳定来源",
                    "当前领域模型里没有 Canonical 的分卷 / 分幕结构（不伪造）。剧情节拍请见「剧情」。",
                )

                tab == 2 -> if (events.isEmpty()) {
                    EmptyState("还没有事件", "Story State 里还没有事件记录")
                } else {
                    Column(Modifier.fillMaxWidth()) {
                        events.forEach { (name, desc) ->
                            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                                Text(name.ifBlank { "未命名事件" }, style = MaterialTheme.typography.bodyLarge, color = palette.text)
                                if (desc.isNotBlank()) {
                                    Spacer(Modifier.height(3.dp))
                                    Text(desc, style = MaterialTheme.typography.bodySmall, color = palette.text2, maxLines = 4)
                                }
                            }
                            HLine()
                        }
                    }
                }

                tab == 3 -> if (foreshadows.isEmpty()) {
                    EmptyState("还没有伏笔", "Story State 里还没有伏笔记录")
                } else {
                    Column(Modifier.fillMaxWidth()) {
                        foreshadows.forEach { row ->
                            Row(
                                Modifier.fillMaxWidth()
                                    .clip(RoundedCornerShape(QianyanShapes.r))
                                    .clickable {
                                        state.workspace.select(
                                            WorkspaceSelection(
                                                projectId = state.novel!!.projectId,
                                                kind = WorkspaceObjectKind.FORESHADOW,
                                                novelId = state.novel!!.novelId,
                                                foreshadowId = ForeshadowingId(row.id),
                                            ),
                                        )
                                    }
                                    .padding(vertical = 8.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(row.content.ifBlank { row.id }, style = MaterialTheme.typography.bodyLarge, color = palette.text, maxLines = 2)
                                    val where = row.chapterLabel?.let { chapterTitles[it] }
                                    if (where != null) {
                                        Spacer(Modifier.height(2.dp))
                                        Text(where, style = QianyanCodeStyle, color = palette.text3)
                                    }
                                }
                                Spacer(Modifier.width(10.dp))
                                Tag(foreshadowStateLabel(row.state), statusTintForForeshadow(row.state))
                            }
                            HLine()
                        }
                    }
                }
                tab == 4 -> StoryDirectionTab(state)
                tab == 5 -> NarrativeLedgerTab(state)
                tab == 6 -> ContextPackTab(state)
                else -> RollingHorizonTab(state)
            }
            Spacer(Modifier.height(24.dp))
            Text(
                "以上为只读展示：这些设定是 Qianyan 写作时的约束来源。点击条目会把「当前对象」切到该对象。",
                style = MaterialTheme.typography.labelSmall,
                color = palette.text3,
            )
        }
    }
}

@Composable
private fun statusTintForForeshadow(state: String): Color = when (state) {
    "RESOLVED" -> QianyanTheme.palette.add
    "ABANDONED" -> QianyanTheme.palette.text3
    "ACTIVE" -> QianyanTheme.palette.accent
    else -> QianyanTheme.palette.text2
}

private data class StoryFoundationView(
    val version: String,
    val genre: String,
    val theme: String,
    val conflict: String,
    val promise: String,
    val storyType: String,
    val pov: String,
    val readerTone: String,
    val rules: List<String>,
)

private data class ForeshadowRow(
    val id: String,
    val content: String,
    val state: String,
    val chapterLabel: String?,
)

@Composable
private fun StoryFoundationBlock(foundation: StoryFoundationView?) {
    val palette = QianyanTheme.palette
    if (foundation == null) {
        EmptyState("故事基础尚未确认", "Story Foundation 确认后会在此显示题材、主题、冲突与写作规则")
        return
    }
    Column(Modifier.fillMaxWidth()) {
        SectionLabel("故事基础 · ${foundation.version}")
        StoryField("题材", foundation.genre)
        StoryField("主题", foundation.theme)
        StoryField("冲突", foundation.conflict)
        StoryField("承诺", foundation.promise)
        StoryField("故事类型", foundation.storyType)
        StoryField("叙事视角", foundation.pov)
        StoryField("读者基调", foundation.readerTone)

        if (foundation.rules.isNotEmpty()) {
            Spacer(Modifier.height(18.dp))
            SectionLabel("写作规则")
            foundation.rules.forEach { rule ->
                Text(
                    "· ${preview(rule, 90)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.text2,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun StoryField(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = QianyanTheme.palette.text3,
            modifier = Modifier.width(72.dp),
        )
        Text(
            value.ifBlank { "—" },
            style = MaterialTheme.typography.bodyMedium,
            color = QianyanTheme.palette.text,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * 12.4 · 世界（只有 世界规则 / 地点 / 势力 / 时间线）。
 *
 * 真实来源：Story State WorldRule · TimelineEntry。
 * 「地点」「势力」在当前领域模型里没有稳定 Canonical 来源 ⇒ 如实空态。
 */
@Composable
fun WorldPage(state: DesktopAppState) {
    val palette = QianyanTheme.palette
    var tab by remember { mutableStateOf(0) }
    var rules by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var timeline by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var chapterTitles by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(state.novel?.novelId, state.refreshKey) {
        val novelId = state.novel?.novelId
        if (novelId == null) {
            loading = false
            return@LaunchedEffect
        }
        loading = true
        state.load {
            rules = it.storyState.listWorldRules(novelId, null).map { r -> r.ruleId.value to r.content }
            timeline = it.storyState.listTimelineEntries(novelId, null)
                .map { t -> t.id.value to t.description }
            chapterTitles = it.chapters.listByNovel(novelId, null)
                .associate { ch -> ch.chapterId.value to "第 ${ch.order} 章" }
        }
        loading = false
    }

    Column(Modifier.fillMaxSize()) {
        PageHeader("世界", meta = state.novel?.title)
        PageTabs(listOf("世界规则", "地点", "势力", "时间线", "全量视图"), tab) { tab = it }

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(QianyanDimens.PageX)) {
            when {
                state.novel == null -> EmptyState("尚未打开作品", "在「Qianyan Agent」中打开或新建作品")
                loading -> LoadingState()

                tab == 0 -> if (rules.isEmpty()) {
                    EmptyState("还没有世界规则", "Story State 里还没有世界规则记录")
                } else {
                    Column(Modifier.fillMaxWidth()) {
                        rules.forEach { (id, content) ->
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    state.workspace.select(
                                        WorkspaceSelection(
                                            projectId = state.novel!!.projectId,
                                            kind = WorkspaceObjectKind.WORLD_RULE,
                                            novelId = state.novel!!.novelId,
                                            worldRuleId = WorldRuleId(id),
                                        ),
                                    )
                                }.padding(vertical = 9.dp, horizontal = 4.dp),
                            ) {
                                Text(content, style = MaterialTheme.typography.bodyLarge, color = palette.text)
                            }
                            HLine()
                        }
                    }
                }

                tab == 1 -> EmptyState("地点没有稳定来源", "当前领域模型里没有 Canonical 的地点实体（不伪造）")

                tab == 2 -> EmptyState("势力没有稳定来源", "当前领域模型里没有 Canonical 的势力实体（不伪造）")

                tab == 3 -> if (timeline.isEmpty()) {
                    EmptyState("还没有时间线条目", "Story State 里还没有时间线记录")
                } else {
                    Column(Modifier.fillMaxWidth().padding(start = 4.dp)) {
                        timeline.forEachIndexed { index, (id, desc) ->
                            Row(Modifier.fillMaxWidth()) {
                                // 时间线轴：细线 + 节点（不是彩色图形）
                                Column(Modifier.width(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Box(
                                        Modifier.width(7.dp).height(7.dp)
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(palette.lineStrong),
                                    )
                                    if (index != timeline.lastIndex) {
                                        Box(Modifier.width(1.dp).height(34.dp).background(palette.line))
                                    }
                                }
                                Spacer(Modifier.width(10.dp))
                                Column(
                                    Modifier.weight(1f).clickable {
                                        state.workspace.select(
                                            WorkspaceSelection(
                                                projectId = state.novel!!.projectId,
                                                kind = WorkspaceObjectKind.TIMELINE_ENTRY,
                                                novelId = state.novel!!.novelId,
                                                timelineEntryId = TimelineEntryId(id),
                                            ),
                                        )
                                    }.padding(bottom = 10.dp),
                                ) {
                                    Text(
                                        desc.ifBlank { id },
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = palette.text,
                                        maxLines = 3,
                                    )
                                }
                            }
                        }
                    }
                }
                else -> WorldFullViewTab(state)
            }
            Spacer(Modifier.height(24.dp))
            Text(
                "以上为只读展示：这些设定是 Qianyan 写作时的约束来源。",
                style = MaterialTheme.typography.labelSmall,
                color = palette.text3,
            )
        }
    }
}
