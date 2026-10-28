package com.qianyan.app.desktop.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.qianyan.app.desktop.ui.DesktopAppState
import com.qianyan.app.desktop.ui.EmptyState
import com.qianyan.app.desktop.ui.FieldRow
import com.qianyan.app.desktop.ui.HLine
import com.qianyan.app.desktop.ui.InfoBlock
import com.qianyan.app.desktop.ui.LoadingState
import com.qianyan.app.desktop.ui.PendingNote
import com.qianyan.app.desktop.ui.SectionLabel
import com.qianyan.app.desktop.ui.SourceNote
import com.qianyan.app.desktop.ui.Tag
import com.qianyan.app.desktop.ui.chapterTitleOnly
import com.qianyan.app.desktop.ui.shortTime
import com.qianyan.app.desktop.ui.statusLabel
import com.qianyan.app.desktop.ui.theme.QianyanCodeStyle
import com.qianyan.app.desktop.ui.theme.QianyanDimens
import com.qianyan.app.desktop.ui.theme.QianyanTheme
import com.qianyan.model.ChapterId
import com.qianyan.model.story.Chapter

/*
 * 故事页的扩展标签（真数据读取；写操作标注接入点）。
 *
 * 四个标签都走**只读**链路：
 *  故事方向  → storyIntentUseCases.presentIdeaState + foundationDecisions.restoreProposal
 *  叙事账本  → narrativeState.getNarrativeState + listNarrativeDeltas
 *  上下文包  → chapterContextPack.compileChapterContext
 *  滚动地平线 → 同上 pack + rollingHorizon.proposeCandidates
 */

/** 章节号 → 「第 N 章 · 标题」映射（只读，用于把 ChapterId 显示成人能读的东西）。 */
internal suspend fun chapterLabels(state: DesktopAppState, novelId: com.qianyan.model.NovelId): Map<String, String> =
    runCatching {
        state.load { c ->
            c.chapters.listByNovel(novelId, null)
                .associate { ch -> ch.chapterId.value to "第 ${ch.order} 章 · ${chapterTitleOnly(ch.title)}" }
        }
    }.getOrDefault(emptyMap())

/* ---------------- 故事方向（P14-F / P15：Idea-first 与 Foundation 提案） ---------------- */

@Composable
internal fun StoryDirectionTab(state: DesktopAppState) {
    val palette = QianyanTheme.palette
    var intent by remember { mutableStateOf<String?>(null) }
    var summary by remember { mutableStateOf<String?>(null) }
    var proposal by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var gate by remember { mutableStateOf(false) }
    var revision by remember { mutableStateOf<Long?>(null) }
    var loading by remember { mutableStateOf(true) }

    val novelId = state.novel?.novelId
    LaunchedEffect(novelId, state.refreshKey) {
        if (novelId == null) {
            loading = false
            return@LaunchedEffect
        }
        loading = true
        state.load {
            val si = it.storyIntentUseCases.presentIdeaState(novelId)
            intent = si?.rawIdea
            summary = si?.aiSummary
            val restored = runCatching { it.foundationDecisions.restoreProposal(novelId) }.getOrNull()
            revision = restored?.proposalRevision
            gate = restored?.gate != null
            proposal = restored?.let { r ->
                val p = r.proposal
                listOf(
                    "题材" to p.genre.joinToString(" · ") { g -> g.value },
                    "主题" to p.direction.theme,
                    "冲突" to p.direction.conflict,
                    "承诺" to p.direction.promise,
                    "故事类型" to p.direction.storyType,
                    "叙事视角" to p.audience.pov,
                    "读者基调" to p.audience.readerTone,
                    "写作规则" to p.policy.rules.joinToString(" / "),
                    "提案修订" to "r${r.proposalRevision}",
                )
            }.orEmpty()
        }
        loading = false
    }

    Column(Modifier.fillMaxWidth()) {
        when {
            novelId == null -> EmptyState("尚未打开作品", "从顶部「当前作品」打开或新建作品")
            loading -> LoadingState()
            else -> {
                if (intent.isNullOrBlank()) {
                    EmptyState("还没有故事意图", "从一句原始想法开始：storyIntentUseCases.startFromIdea(novelId, rawIdea)")
                } else {
                    SectionLabel("原始想法")
                    Text(intent ?: "", style = MaterialTheme.typography.bodyLarge, color = palette.text)
                    if (!summary.isNullOrBlank()) {
                        Spacer(Modifier.height(8.dp))
                        SectionLabel("AI 理解")
                        Text(summary ?: "", style = MaterialTheme.typography.bodyMedium, color = palette.text2)
                    }
                }
                Spacer(Modifier.height(16.dp))
                if (proposal.isEmpty()) {
                    EmptyState("还没有故事基础提案", "提案由 Idea-first 链路产出并停在人工门等待确认")
                } else {
                    InfoBlock("故事基础提案", proposal)
                    FieldRow("人工门", if (gate) "待确认" else "无未决人工门")
                }
                Spacer(Modifier.height(12.dp))
                PendingNote(
                    "storyIntentUseCases.startFromIdea / understandIdea · foundationDecisions.confirmFoundation / modifyProposal / rejectProposal",
                    "起意、理解、修改与确认都是写操作（确认走事务：Gate resolve + Foundation upsert），本轮未接入。",
                )
                SourceNote("来源：presentIdeaState + restoreProposal（只读）")
            }
        }
    }
}

/* ---------------- 叙事账本（Narrative State · LCL） ---------------- */

@Composable
internal fun NarrativeLedgerTab(state: DesktopAppState) {
    val palette = QianyanTheme.palette
    var stateLine by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var deltas by remember { mutableStateOf<List<Triple<String, String, String>>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    val novelId = state.novel?.novelId
    val variantId = state.workspace.selection?.variantId
    LaunchedEffect(novelId, variantId, state.refreshKey) {
        if (novelId == null) {
            loading = false
            return@LaunchedEffect
        }
        loading = true
        val labels = chapterLabels(state, novelId)
        state.load {
            val ns = it.narrativeState.getNarrativeState(novelId, variantId)
            stateLine = ns?.let { n ->
                listOf(
                    "版本" to "v${n.version}",
                    "主线目标" to n.mainGoal,
                    "当前冲突" to (n.currentConflict?.value ?: "—"),
                    "未结线索" to n.openThreads.joinToString(" / ") { t -> t.description },
                    "伏笔压力" to "${n.foreshadowPressures.size} 项",
                    "人物阶段" to "${n.characterStages.size} 位",
                    "上一章增量" to n.lastChapterDelta,
                )
            }.orEmpty()
            deltas = it.narrativeState.listNarrativeDeltas(novelId, variantId).map { d ->
                Triple(
                    d.chapterId?.let { id -> labels[id.value] } ?: "—",
                    d.summary.ifBlank { "（无摘要）" },
                    shortTime(d.createdAt),
                )
            }
        }
        loading = false
    }

    Column(Modifier.fillMaxWidth()) {
        when {
            novelId == null -> EmptyState("尚未打开作品", "从顶部「当前作品」打开或新建作品")
            loading -> LoadingState()
            stateLine.isEmpty() && deltas.isEmpty() -> {
                EmptyState(
                    "还没有叙事账本",
                    "账本由章节推进写入 NarrativeDelta；Original 作用域的账本只读（写路径要求变体作用域）",
                )
                PendingNote(
                    "narrativeState.appendNarrativeDelta(delta, expectedVersion)",
                    "账本写入本轮未接入；且 Original（variantId=null）在后端即被拒绝，需要先有变体。",
                )
            }

            else -> {
                if (stateLine.isNotEmpty()) InfoBlock("叙事状态投影", stateLine)
                Spacer(Modifier.height(14.dp))
                SectionLabel("增量历史 · ${deltas.size}")
                if (deltas.isEmpty()) {
                    EmptyState("没有增量记录")
                } else {
                    deltas.reversed().forEach { (where, summary, at) ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                            Text(summary, style = MaterialTheme.typography.bodyLarge, color = palette.text, maxLines = 3)
                            Spacer(Modifier.height(2.dp))
                            Text("$where  ·  $at", style = QianyanCodeStyle, color = palette.text3)
                        }
                        HLine()
                    }
                }
                SourceNote("来源：getNarrativeState + listNarrativeDeltas（只读）")
            }
        }
    }
}

/* ---------------- 章节上下文包（确定性、有界） ---------------- */

internal data class PackView(
    val packVersion: Long,
    val horizon: List<String>,
    val budget: Long,
    val estimated: Long,
    val truncated: Boolean,
    val omitted: Int,
    val threads: List<String>,
    val foreshadows: List<String>,
    val events: List<String>,
    val narrativeGoal: String?,
)

/** 编译一次上下文包（只读），供「上下文包」与「滚动地平线」两个标签共用。 */
internal suspend fun compilePackView(state: DesktopAppState, chapterId: ChapterId?): PackView? {
    val novelId = state.novel?.novelId ?: return null
    val variantId = state.workspace.selection?.variantId
    val labels = chapterLabels(state, novelId)
    return runCatching {
        state.load { c ->
            val pack = c.chapterContextPack.compileChapterContext(novelId, variantId, chapterId)
            PackView(
                packVersion = pack.packVersion,
                horizon = pack.horizonWindow.map { labels[it.value] ?: it.value.take(8) },
                budget = pack.tokenBudgetGuard.budget,
                estimated = pack.tokenBudgetGuard.estimatedTokens,
                truncated = pack.tokenBudgetGuard.isTruncated,
                omitted = pack.tokenBudgetGuard.omittedCount,
                threads = pack.activeThreads.map { it.description },
                foreshadows = pack.activeForeshadows.map { it.content },
                events = pack.recentEvents.map { it.name },
                narrativeGoal = pack.activeNarrative?.mainGoal,
            )
        }
    }.getOrNull()
}

@Composable
internal fun ContextPackTab(state: DesktopAppState) {
    val palette = QianyanTheme.palette
    var pack by remember { mutableStateOf<PackView?>(null) }
    var loading by remember { mutableStateOf(true) }
    val chapterId = state.workspace.selection?.chapterId
    val projectKey = state.workspace.selection?.projectId

    LaunchedEffect(projectKey, chapterId, state.refreshKey) {
        loading = true
        pack = if (state.novel == null) null else compilePackView(state, chapterId)
        loading = false
    }

    Column(Modifier.fillMaxWidth()) {
        when {
            state.novel == null -> EmptyState("尚未打开作品", "从顶部「当前作品」打开或新建作品")
            loading -> LoadingState()
            pack == null -> EmptyState("无法编译上下文包")
            else -> {
                val p = pack!!
                InfoBlock(
                    "包的构成",
                    listOf(
                        "packVersion" to "v${p.packVersion}",
                        "滚动窗口" to if (p.horizon.isEmpty()) "—" else p.horizon.joinToString(" · "),
                        "主线目标" to (p.narrativeGoal ?: "—"),
                    ),
                )
                Spacer(Modifier.height(10.dp))
                SectionLabel("Token 预算")
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f).height(3.dp).background(palette.soft)) {
                        val ratio = if (p.budget <= 0L) 0f else (p.estimated.toFloat() / p.budget).coerceIn(0f, 1f)
                        Box(Modifier.fillMaxWidth(ratio).height(3.dp).background(palette.accent))
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "${p.estimated} / ${p.budget}" + if (p.truncated) " · 裁剪 ${p.omitted}" else "",
                        style = QianyanCodeStyle,
                        color = palette.text3,
                    )
                }
                Spacer(Modifier.height(14.dp))
                PackList("未结线索", p.threads)
                PackList("相关伏笔", p.foreshadows)
                PackList("近期事件", p.events)
                PendingNote(
                    "chapterContextPack.invalidatePack(old, windowSize)",
                    "包的失效判定本轮未接入（写入与持久化不在 Reader 链路内）。",
                )
                SourceNote("来源：chapterContextPack.compileChapterContext(novelId, variantId, chapterId)（确定性、只读、不落库）")
            }
        }
    }
}

@Composable
private fun PackList(title: String, items: List<String>) {
    SectionLabel("$title · ${items.size}")
    if (items.isEmpty()) {
        EmptyState("暂无内容")
    } else {
        items.forEach { Text("· $it", style = MaterialTheme.typography.bodyMedium, color = QianyanTheme.palette.text2, modifier = Modifier.padding(vertical = 2.dp)) }
    }
    Spacer(Modifier.height(12.dp))
}

/* ---------------- 滚动地平线（候选来自上下文包，确定性投影） ---------------- */

@Composable
internal fun RollingHorizonTab(state: DesktopAppState) {
    val palette = QianyanTheme.palette
    var pack by remember { mutableStateOf<PackView?>(null) }
    var candidates by remember { mutableStateOf<List<Triple<String, String, String>>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    val chapterId = state.workspace.selection?.chapterId
    LaunchedEffect(chapterId, state.refreshKey) {
        val novelId = state.novel?.novelId
        if (novelId == null) {
            loading = false
            return@LaunchedEffect
        }
        loading = true
        val variantId = state.workspace.selection?.variantId
        val labels = chapterLabels(state, novelId)
        state.load {
            val compiled = it.chapterContextPack.compileChapterContext(novelId, variantId, chapterId)
            pack = PackView(
                packVersion = compiled.packVersion,
                horizon = compiled.horizonWindow.map { id -> labels[id.value] ?: id.value.take(8) },
                budget = compiled.tokenBudgetGuard.budget,
                estimated = compiled.tokenBudgetGuard.estimatedTokens,
                truncated = compiled.tokenBudgetGuard.isTruncated,
                omitted = compiled.tokenBudgetGuard.omittedCount,
                threads = emptyList(),
                foreshadows = emptyList(),
                events = emptyList(),
                narrativeGoal = compiled.activeNarrative?.mainGoal,
            )
            candidates = it.rollingHorizon.proposeCandidates(compiled).map { c ->
                Triple(c.directionId, c.summary, c.goal)
            }
        }
        loading = false
    }

    Column(Modifier.fillMaxWidth()) {
        when {
            state.novel == null -> EmptyState("尚未打开作品", "从顶部「当前作品」打开或新建作品")
            loading -> LoadingState()
            else -> {
                Text(
                    "候选由当前章节上下文包确定性投影得出（不调 LLM）。选择某个方向会进入规划 —— 那是写操作。",
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.text2,
                )
                Spacer(Modifier.height(12.dp))
                SectionLabel("候选方向 · ${candidates.size}")
                if (candidates.isEmpty()) {
                    EmptyState("当前没有候选方向", "上下文包里没有可投影的线索 / 伏笔 / 事件时，候选为空")
                } else {
                    candidates.forEach { (id, summary, goal) ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                            Text(summary.ifBlank { id }, style = MaterialTheme.typography.bodyLarge, color = palette.text)
                            if (goal.isNotBlank()) {
                                Spacer(Modifier.height(2.dp))
                                Text(goal, style = MaterialTheme.typography.bodySmall, color = palette.text2)
                            }
                            Spacer(Modifier.height(2.dp))
                            Text("direction $id", style = QianyanCodeStyle, color = palette.text3)
                        }
                        HLine()
                    }
                }
                Spacer(Modifier.height(12.dp))
                PendingNote(
                    "rollingHorizon.storeCandidates(taskId, candidates) → Workflow 人门选方向",
                    "「选方向并进入规划」的编排尚未实现（文档明确列为缺口），本轮只展示候选。",
                )
                SourceNote("来源：rollingHorizon.proposeCandidates(pack)（只读、确定性）")
            }
        }
    }
}

/* ---------------- 世界 · 全量视图（StoryWorldContext 只读解析） ---------------- */

@Composable
internal fun WorldFullViewTab(state: DesktopAppState) {
    val palette = QianyanTheme.palette
    var groups by remember { mutableStateOf<List<Pair<String, List<String>>>>(emptyList()) }
    var summary by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }

    val novelId = state.novel?.novelId
    val variantId = state.workspace.selection?.variantId
    LaunchedEffect(novelId, variantId, state.refreshKey) {
        if (novelId == null) {
            loading = false
            return@LaunchedEffect
        }
        loading = true
        state.load {
            val ctx = it.storyWorldContextResolver.resolve(novelId, variantId)
            summary = ctx.worldSummary
            groups = listOf(
                "设定（canon）" to ctx.canon,
                "世界状态" to ctx.worldState,
                "事实（facts）" to ctx.facts,
                "记忆（memories）" to ctx.memories,
                "人物 · ${ctx.characters.size}" to ctx.characters.map { c -> c.name },
                "世界规则 · ${ctx.worldRules.size}" to ctx.worldRules.map { r -> r.content },
                "事件 · ${ctx.events.size}" to ctx.events.map { e -> e.name },
                "时间线 · ${ctx.timelineEntries.size}" to ctx.timelineEntries.map { t -> t.description },
                "伏笔 · ${ctx.foreshadows.size}" to ctx.foreshadows.map { f -> f.content },
            ).filter { (_, v) -> v.isNotEmpty() }
        }
        loading = false
    }

    Column(Modifier.fillMaxWidth()) {
        when {
            novelId == null -> EmptyState("尚未打开作品", "从顶部「当前作品」打开或新建作品")
            loading -> LoadingState()
            groups.isEmpty() -> EmptyState("全量视图为空", "该作用域下没有可合并的世界上下文")
            else -> {
                if (summary.isNotBlank()) {
                    SectionLabel("世界摘要")
                    Text(summary, style = MaterialTheme.typography.bodyMedium, color = palette.text2)
                    Spacer(Modifier.height(14.dp))
                }
                groups.forEach { (title, values) ->
                    SectionLabel(title)
                    values.forEach { v ->
                        Text("· $v", style = MaterialTheme.typography.bodyMedium, color = palette.text2, modifier = Modifier.padding(vertical = 2.dp))
                    }
                    Spacer(Modifier.height(12.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Tag("只读合并", palette.text3)
                    Spacer(Modifier.width(8.dp))
                    Text("确定性 Entity Merge，不调 LLM、不写库", style = QianyanCodeStyle, color = palette.text3, fontWeight = FontWeight.Normal)
                }
                SourceNote("来源：storyWorldContextResolver.resolve(novelId, variantId, scope)（只读）")
            }
        }
    }
}

/* ---------------- 任务页复用的章节标签（保持 internal 供 ListPages 使用） ---------------- */

@Composable
internal fun ChapterLabelLine(chapters: List<Chapter>, chapterId: String?) {
    val label = chapters.firstOrNull { it.chapterId.value == chapterId }?.let { "第 ${it.order} 章 · ${chapterTitleOnly(it.title)}" }
    Text(label ?: "—", style = QianyanCodeStyle, color = QianyanTheme.palette.text3)
}
