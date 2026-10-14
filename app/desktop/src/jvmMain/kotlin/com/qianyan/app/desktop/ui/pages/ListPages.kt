package com.qianyan.app.desktop.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.DisposableEffect
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
import com.qianyan.app.desktop.ui.ListRow
import com.qianyan.app.desktop.ui.LoadingState
import com.qianyan.app.desktop.ui.NavItem
import com.qianyan.app.desktop.ui.PageHeader
import com.qianyan.app.desktop.ui.PendingNote
import com.qianyan.app.desktop.ui.QianyanTabs
import com.qianyan.app.desktop.ui.SectionLabel
import com.qianyan.app.desktop.ui.SourceNote
import com.qianyan.app.desktop.ui.Tag
import com.qianyan.app.desktop.ui.chapterTitleOnly
import com.qianyan.app.desktop.ui.formatCount
import com.qianyan.app.desktop.ui.preview
import com.qianyan.app.desktop.ui.shortTime
import com.qianyan.app.desktop.ui.statusLabel
import com.qianyan.app.desktop.ui.statusTint
import com.qianyan.app.desktop.ui.taskTypeLabel
import com.qianyan.app.desktop.ui.theme.QianyanCodeStyle
import com.qianyan.app.desktop.ui.theme.QianyanDimens
import com.qianyan.app.desktop.ui.theme.QianyanShapes
import com.qianyan.app.desktop.ui.theme.QianyanTheme
import com.qianyan.app.desktop.ui.wordCount
import com.qianyan.model.CharacterId
import com.qianyan.model.character.Character
import com.qianyan.model.commit.CommitHistoryEntry
import com.qianyan.model.core.VariantContext
import com.qianyan.model.story.Chapter
import com.qianyan.model.task.Task
import com.qianyan.model.taskqueue.TaskQueueItem
import com.qianyan.model.workspace.WorkspaceObjectKind
import com.qianyan.model.workspace.WorkspaceSelection

/** 列表型页面的键盘导航开关（↑/↓/Enter）：进入页面开启，离开页面关闭。 */
@Composable
private fun EnableListKeys(state: DesktopAppState) {
    DisposableEffect(Unit) {
        state.listNavEnabled = true
        onDispose { state.listNavEnabled = false }
    }
}

/**
 * 12.5 · 章节（纯列表：编号 / 标题 / 状态 / 字数）。
 *
 * 字数与正文状态来自既有 `writerUseCases.latestDraft(chapterId)`（只读）；点击 → 当前对象=章节 并进入正文。
 */
@Composable
fun ChaptersPage(state: DesktopAppState) {
    val palette = QianyanTheme.palette
    val ws = state.workspace
    var chapters by remember { mutableStateOf<List<Chapter>>(emptyList()) }
    var draftInfo by remember { mutableStateOf<Map<String, Pair<String?, Int>>>(emptyMap()) }
    var loading by remember { mutableStateOf(true) }

    EnableListKeys(state)

    LaunchedEffect(state.novel?.novelId, state.refreshKey) {
        val novelId = state.novel?.novelId
        if (novelId == null) {
            loading = false
            return@LaunchedEffect
        }
        loading = true
        val list = runCatching { state.load { it.chapters.listByNovel(novelId, null) } }.getOrDefault(emptyList())
        chapters = list.sortedBy { it.order }
        draftInfo = runCatching {
            state.load { c ->
                list.associate { ch ->
                    val draft = c.writerUseCases.latestDraft(ch.chapterId)
                    ch.chapterId.value to (draft?.status?.name to wordCount(draft?.content.orEmpty()))
                }
            }
        }.getOrDefault(emptyMap())
        loading = false
    }

    LaunchedEffect(chapters, state.novel?.novelId) {
        ws.registerList(chapters.size) { index ->
            val ch = chapters.getOrNull(index) ?: return@registerList
            ws.select(WorkspaceSelection.chapter(state.novel!!.projectId, state.novel!!.novelId, ch.chapterId))
            state.nav = NavItem.WRITE
        }
    }

    Column(Modifier.fillMaxSize()) {
        PageHeader("章节", meta = if (chapters.isEmpty()) null else "共 ${chapters.size} 章")

        when {
            state.novel == null -> Box(Modifier.padding(QianyanDimens.PageX)) {
                EmptyState("尚未打开作品", "在「Qianyan Agent」中打开或新建作品")
            }

            loading -> Box(Modifier.padding(QianyanDimens.PageX)) { LoadingState() }

            chapters.isEmpty() -> Box(Modifier.padding(QianyanDimens.PageX)) {
                EmptyState("还没有章节", "这部作品还没有章节")
            }

            else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 4.dp)) {
                chapters.forEachIndexed { index, ch ->
                    val (draftStatus, words) = draftInfo[ch.chapterId.value] ?: (null to 0)
                    val statusName = draftStatus ?: ch.status.name
                    ListRow(
                        title = chapterTitleOnly(ch.title).ifBlank { "未命名" },
                        index = ch.order.toString().padStart(2, '0'),
                        meta = if (words > 0) "${formatCount(words)} 字" else null,
                        tag = statusLabel(statusName),
                        tagColor = statusTint(statusName),
                        selected = ws.selection?.chapterId == ch.chapterId || ws.listIndex == index,
                        onClick = {
                            ws.listIndex = index
                            ws.select(WorkspaceSelection.chapter(state.novel!!.projectId, state.novel!!.novelId, ch.chapterId))
                            state.nav = NavItem.WRITE
                        },
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "↑/↓ 选择 · Enter 打开正文",
                    style = QianyanCodeStyle,
                    color = palette.text3,
                    modifier = Modifier.padding(horizontal = QianyanDimens.PageX),
                )
            }
        }
    }
}

/**
 * 12.6 · 人物（左列表 + 右内容）。
 *
 * 真实来源：Story State `Character` + `CharacterState`（关系与出现章节来自既有字段，
 * 全部映射为**人物名 / 章节标题**，不显示内部 ID）。
 */
@Composable
fun CharactersPage(state: DesktopAppState) {
    val palette = QianyanTheme.palette
    val ws = state.workspace
    var characters by remember { mutableStateOf<List<Character>>(emptyList()) }
    var relations by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var abilities by remember { mutableStateOf<List<String>>(emptyList()) }
    var appearances by remember { mutableStateOf<List<String>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var charTab by remember { mutableStateOf(0) }

    EnableListKeys(state)

    LaunchedEffect(state.novel?.novelId, state.refreshKey) {
        val novelId = state.novel?.novelId
        if (novelId == null) {
            loading = false
            return@LaunchedEffect
        }
        loading = true
        state.load {
            val list = it.storyState.listCharacters(novelId, null)
            characters = list
            val nameOf = list.associate { c -> c.characterId to c.name }
            val chapterTitles = it.chapters.listByNovel(novelId, null)
                .associate { ch -> ch.chapterId.value to "第 ${ch.order} 章 · ${ch.title}" }
            val states = it.storyState.listCharacterStates(novelId, null)
            relations = states.flatMap { s ->
                s.relationships.map { r ->
                    (nameOf[s.characterId] ?: "未知人物") to
                        "${nameOf[r.otherCharacterId] ?: r.otherCharacterId.value}${r.relation.takeIf { t -> t.isNotBlank() }?.let { t -> "（$t）" } ?: ""}"
                }
            }.distinct()
            abilities = states.flatMap { s -> s.abilities.map { a -> a.name } }.filter { it.isNotBlank() }.distinct()
            appearances = states.mapNotNull { s -> s.chapterId?.value }
                .mapNotNull { id -> chapterTitles[id] }
                .distinct()
        }
        loading = false
    }

    val current = ws.selection?.characterId
    LaunchedEffect(characters) {
        ws.registerList(characters.size) { index ->
            characters.getOrNull(index)?.let {
                ws.select(WorkspaceSelection.character(state.novel!!.projectId, state.novel!!.novelId, it.characterId))
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        PageHeader("人物", meta = if (characters.isEmpty()) null else "共 ${characters.size} 位")
        QianyanTabs(listOf("档案", "状态快照"), charTab) { charTab = it }

        when {
            state.novel == null -> Box(Modifier.padding(QianyanDimens.PageX)) {
                EmptyState("尚未打开作品", "在「Qianyan Agent」中打开或新建作品")
            }

            loading -> Box(Modifier.padding(QianyanDimens.PageX)) { LoadingState() }

            else -> Row(Modifier.fillMaxSize()) {
                // 左：人物列表
                Column(Modifier.width(200.dp).fillMaxSize()) {
                    if (characters.isEmpty()) {
                        Box(Modifier.padding(QianyanDimens.Gutter)) {
                            EmptyState("还没有人物", "Story State 里还没有人物档案")
                        }
                    } else {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 4.dp)) {
                            characters.forEachIndexed { index, c ->
                                ListRow(
                                    title = c.name,
                                    selected = current == c.characterId || ws.listIndex == index,
                                    horizontalPadding = 10.dp,
                                    onClick = {
                                        ws.listIndex = index
                                        ws.select(WorkspaceSelection.character(state.novel!!.projectId, state.novel!!.novelId, c.characterId))
                                    },
                                )
                            }
                        }
                    }
                }
                Box(Modifier.width(1.dp).fillMaxSize().background(palette.line))

                // 右：人物内容（不是统计图表）
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(QianyanDimens.PageX)) {
                    val person = characters.firstOrNull { it.characterId == current }
                    if (person == null) {
                        EmptyState("未选择人物", "在左侧选择一位人物")
                    } else if (charTab == 1) {
                        CharacterSnapshotBlock(state, person.characterId, person.name)
                    } else {
                        Text(person.name, style = MaterialTheme.typography.displaySmall, color = palette.text)
                        Spacer(Modifier.height(14.dp))

                        SectionLabel("基本信息")
                        StoryField("简介", person.description)
                        StoryField("性格", person.personality.joinToString(" · "))
                        StoryField("目标", person.goals.joinToString(" · "))
                        StoryField("恐惧", person.fears.joinToString(" · "))

                        if (abilities.isNotEmpty()) {
                            Spacer(Modifier.height(18.dp))
                            SectionLabel("能力")
                            Text(abilities.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = palette.text2)
                        }

                        Spacer(Modifier.height(18.dp))
                        SectionLabel("人物关系")
                        if (relations.isEmpty()) {
                            EmptyState("暂无关系记录", "CharacterState 里还没有关系快照")
                        } else {
                            relations.forEach { (from, to) ->
                                Text(
                                    "$from — $to",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = palette.text2,
                                    modifier = Modifier.padding(vertical = 3.dp),
                                )
                            }
                        }

                        Spacer(Modifier.height(18.dp))
                        SectionLabel("出现章节")
                        if (appearances.isEmpty()) {
                            EmptyState("暂无出现记录", "CharacterState 里还没有关联章节")
                        } else {
                            Text(
                                appearances.joinToString(" · "),
                                style = MaterialTheme.typography.bodyMedium,
                                color = palette.text2,
                            )
                        }

                        Spacer(Modifier.height(18.dp))
                        SectionLabel("人物弧线")
                        Text(
                            "当前数据模型里没有稳定的弧线来源（不伪造）。",
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.text3,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 12.7 · 任务（IDE 任务列表：按状态分组，不做卡片墙、不做进度环）。
 *
 * 状态语义来自既有 `Task` / `TaskQueueItem`；任务类型与状态映射为用户语言。
 */
@Composable
fun TasksPage(state: DesktopAppState) {
    val palette = QianyanTheme.palette
    val ws = state.workspace
    var rows by remember { mutableStateOf<List<Pair<TaskQueueItem?, Task>>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    EnableListKeys(state)

    LaunchedEffect(state.projectId, state.refreshKey) {
        val projectId = state.projectId
        if (projectId == null) {
            loading = false
            return@LaunchedEffect
        }
        loading = true
        rows = runCatching {
            state.load { c ->
                c.taskQueue.listByProject(projectId).map { item -> item to c.tasks.findById(item.taskId) }
            }
        }.getOrDefault(emptyList())
        loading = false
    }

    LaunchedEffect(rows) {
        ws.registerList(rows.size) { index ->
            rows.getOrNull(index)?.let {
                ws.select(WorkspaceSelection.task(state.projectId!!, it.second.taskId, state.novel?.novelId))
            }
        }
    }

    // 分组顺序：先进行中，再等待，最后结束态
    val order = listOf("RUNNING", "QUEUED", "CLAIMED", "PENDING", "PAUSED", "COMPLETED", "FAILED", "CANCELLED")

    Column(Modifier.fillMaxSize()) {
        PageHeader("任务", meta = if (rows.isEmpty()) null else "共 ${rows.size} 项")

        when {
            state.projectId == null -> Box(Modifier.padding(QianyanDimens.PageX)) {
                EmptyState("尚未打开作品", "在「Qianyan Agent」中打开或新建作品")
            }

            loading -> Box(Modifier.padding(QianyanDimens.PageX)) { LoadingState() }

            rows.isEmpty() -> Box(Modifier.padding(QianyanDimens.PageX)) {
                EmptyState("没有后台任务", "当前项目在既有任务队列（I13）里没有条目")
            }

            else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 4.dp)) {
                val grouped = rows.groupBy { it.first?.status?.name ?: it.second.status.name }
                val keys = (order.filter { grouped.containsKey(it) } + grouped.keys.filterNot { it in order }).distinct()
                keys.forEach { key ->
                    val items = grouped[key].orEmpty()
                    SectionLabel("${statusLabel(key)} · ${items.size}", Modifier.padding(horizontal = QianyanDimens.PageX, vertical = 0.dp))
                    items.forEach { (item, task) ->
                        val globalIndex = rows.indexOfFirst { it.second.taskId == task.taskId }
                        ListRow(
                            title = taskTypeLabel(task.type.name),
                            meta = "尝试 ${item?.attempt ?: 0}/${item?.maxAttempts ?: 0}",
                            tag = statusLabel(item?.status?.name ?: task.status.name),
                            tagColor = statusTint(item?.status?.name ?: task.status.name),
                            selected = ws.selection?.taskId == task.taskId || ws.listIndex == globalIndex,
                            onClick = {
                                ws.listIndex = globalIndex
                                ws.select(WorkspaceSelection.task(state.projectId!!, task.taskId, state.novel?.novelId))
                            },
                        )
                        (item?.lastError ?: task.error)?.takeIf { it.isNotBlank() }?.let { err ->
                            Text(
                                preview(err, 100),
                                style = MaterialTheme.typography.bodySmall,
                                color = palette.del,
                                maxLines = 2,
                                modifier = Modifier.padding(start = QianyanDimens.PageX + 46.dp, end = QianyanDimens.PageX, bottom = 4.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }
                Spacer(Modifier.height(8.dp))
                PendingNote(
                    "tasks.start / pause / resume / cancel / complete / saveCheckpoint / restoreCheckpoint",
                    "任务生命周期操作本轮未接入（状态机校验与终态保护由后端保证）。",
                )
            }
        }
    }
}

/**
 * 12.8 · 历史（Git 风格提交时间线；使用真实 I10 CommitHistory）。
 *
 * 章节一律显示为"第 N 章 · 标题"（映射既有 Chapter），不显示内部 ID。
 */
@Composable
fun HistoryPage(state: DesktopAppState) {
    val palette = QianyanTheme.palette
    val ws = state.workspace
    var entries by remember { mutableStateOf<List<CommitHistoryEntry>>(emptyList()) }
    var chapterTitles by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var loading by remember { mutableStateOf(true) }
    var tab by remember { mutableStateOf(0) }

    EnableListKeys(state)

    LaunchedEffect(state.projectId, state.novel?.novelId, state.refreshKey) {
        val projectId = state.projectId
        val novelId = state.novel?.novelId
        if (projectId == null) {
            loading = false
            return@LaunchedEffect
        }
        loading = true
        entries = runCatching { state.load { it.commits.history(projectId) } }.getOrDefault(emptyList())
        if (novelId != null) {
            chapterTitles = runCatching {
                state.load { c ->
                    c.chapters.listByNovel(novelId, null)
                        .associate { ch -> ch.chapterId.value to "第 ${ch.order} 章 · ${chapterTitleOnly(ch.title)}" }
                }
            }.getOrDefault(emptyMap())
        }
        loading = false
    }

    LaunchedEffect(entries) {
        ws.registerList(entries.size) { index ->
            entries.getOrNull(index)?.let {
                ws.select(WorkspaceSelection.history(state.projectId!!, it.historyId, state.novel?.novelId))
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        PageHeader("历史", meta = if (entries.isEmpty()) null else "共 ${entries.size} 条提交")
        QianyanTabs(listOf("提交 · ${entries.size}", "揭示"), tab) { tab = it }

        when {
            state.projectId == null -> Box(Modifier.padding(QianyanDimens.PageX)) {
                EmptyState("尚未打开作品", "在「Qianyan Agent」中打开或新建作品")
            }

            loading -> Box(Modifier.padding(QianyanDimens.PageX)) { LoadingState() }

            tab == 1 -> Box(Modifier.padding(QianyanDimens.PageX)) { RevealSection(state) }

            entries.isEmpty() -> Box(Modifier.padding(QianyanDimens.PageX)) {
                EmptyState("还没有提交", "提交走既有 I10 原子提交；提交后在此形成时间线")
            }

            else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(QianyanDimens.PageX)) {
                entries.reversed().forEachIndexed { reverseIndex, entry ->
                    val index = entries.size - 1 - reverseIndex
                    val selected = ws.selection?.historyId == entry.historyId
                    Row(Modifier.fillMaxWidth()) {
                        // 提交轴：节点 + 竖线
                        Column(Modifier.width(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                Modifier.width(7.dp).height(7.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(if (selected) palette.accent else palette.lineStrong),
                            )
                            if (reverseIndex != entries.size - 1) {
                                Box(Modifier.width(1.dp).height(38.dp).background(palette.line))
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(
                            Modifier.weight(1f)
                                .clip(RoundedCornerShape(QianyanShapes.r))
                                .background(if (selected) palette.selection else Color.Transparent)
                                .clickable {
                                    ws.listIndex = index
                                    ws.select(WorkspaceSelection.history(state.projectId!!, entry.historyId, state.novel?.novelId))
                                }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                        ) {
                            Text(
                                entry.summary.ifBlank { statusLabel(entry.operation.name) },
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                                color = palette.text,
                                maxLines = 2,
                            )
                            Spacer(Modifier.height(3.dp))
                            Text(
                                listOfNotNull(
                                    statusLabel(entry.operation.name),
                                    shortTime(entry.createdAt),
                                    chapterTitles[entry.target.chapterId.value],
                                ).joinToString("  ·  "),
                                style = QianyanCodeStyle,
                                color = palette.text3,
                                maxLines = 1,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

/* ---------------- 人物 · 状态快照（CharacterState 只读） ---------------- */

/**
 * 人物状态快照：来自既有 `storyState.listCharacterStates`（Variant 写入路径未接入）。
 *
 * 只展示用户可理解的字段（章节 / 快照类型 / 当前目标 / 动机 / 能力），不显示内部 ID。
 */
@Composable
private fun CharacterSnapshotBlock(state: DesktopAppState, characterId: CharacterId, name: String) {
    val palette = QianyanTheme.palette
    var rows by remember { mutableStateOf<List<SnapshotRow>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(characterId, state.refreshKey) {
        val novelId = state.novel?.novelId
        if (novelId == null) {
            loading = false
            return@LaunchedEffect
        }
        loading = true
        state.load {
            val labels = it.chapters.listByNovel(novelId, null)
                .associate { ch -> ch.chapterId.value to "第 ${ch.order} 章 · ${chapterTitleOnly(ch.title)}" }
            rows = it.storyState.listCharacterStates(novelId, null)
                .filter { s -> s.characterId == characterId }
                .map { s ->
                    SnapshotRow(
                        where = s.chapterId?.let { id -> labels[id.value] } ?: "（未绑定章节）",
                        kind = statusLabel(s.snapshotType.name),
                        goal = s.currentGoal.orEmpty(),
                        motivation = s.currentMotivation.orEmpty(),
                        abilities = s.abilities.map { a -> a.name }.filter { it.isNotBlank() },
                        source = statusLabel(s.source.name),
                    )
                }
        }
        loading = false
    }

    Column(Modifier.fillMaxWidth()) {
        Text(name, style = MaterialTheme.typography.displaySmall, color = palette.text)
        Spacer(Modifier.height(14.dp))
        when {
            loading -> LoadingState()
            rows.isEmpty() -> EmptyState("还没有状态快照", "CharacterState 由章节推进写入；Variant 写入路径本轮未接入")
            else -> rows.forEach { r ->
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(r.where, style = QianyanCodeStyle, color = palette.text3, modifier = Modifier.weight(1f))
                        Tag(r.kind, palette.accent)
                    }
                    if (r.goal.isNotBlank()) {
                        Spacer(Modifier.height(3.dp))
                        Text("当前目标：${r.goal}", style = MaterialTheme.typography.bodyMedium, color = palette.text2)
                    }
                    if (r.motivation.isNotBlank()) {
                        Text("动机：${r.motivation}", style = MaterialTheme.typography.bodyMedium, color = palette.text2)
                    }
                    if (r.abilities.isNotEmpty()) {
                        Text("能力：${r.abilities.joinToString(" · ")}", style = MaterialTheme.typography.bodyMedium, color = palette.text2)
                    }
                    Spacer(Modifier.height(2.dp))
                    Text("来源 ${r.source}", style = QianyanCodeStyle, color = palette.text3)
                }
                HLine()
            }
        }
        Spacer(Modifier.height(10.dp))
        PendingNote(
            "storyStateVariant.addCharacterState / overrideCharacterState",
            "状态快照的写入与覆写（Variant-only；Original 只读）本轮未接入。",
        )
        SourceNote("来源：storyState.listCharacterStates（只读）")
    }
}

private data class SnapshotRow(
    val where: String,
    val kind: String,
    val goal: String,
    val motivation: String,
    val abilities: List<String>,
    val source: String,
)

/* ---------------- 历史 · 揭示（Reveal，只读） ---------------- */

/**
 * 揭示记录：`reveals.listByScope(VariantContext)`（只读）。
 *
 * 需要 `baseNovelId`（来自已确认的 Story Foundation）才能构造 VariantContext；
 * 未确认时如实提示。写入（`reveals.createReveal`，Reader-only / Variant-only）本轮未接入。
 */
@Composable
private fun RevealSection(state: DesktopAppState) {
    val palette = QianyanTheme.palette
    var rows by remember { mutableStateOf<List<Triple<String, String, String>>>(emptyList()) }
    var scoped by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(state.novel?.novelId, state.refreshKey) {
        val novelId = state.novel?.novelId
        if (novelId == null) {
            loading = false
            return@LaunchedEffect
        }
        loading = true
        val base = baseNovelIdOf(state, novelId)
        scoped = base != null
        if (base != null) {
            val labels = chapterLabels(state, novelId)
            state.load {
                rows = it.reveals.listByScope(VariantContext(base, state.workspace.selection?.variantId))
                    .map { r ->
                        Triple(
                            r.informationId.ifBlank { r.revealId.value },
                            r.chapterId?.let { id -> labels[id.value] } ?: "—",
                            shortTime(r.occurredAt),
                        )
                    }
            }
        }
        loading = false
    }

    Column(Modifier.fillMaxWidth()) {
        when {
            loading -> LoadingState()
            !scoped -> {
                EmptyState("无法构造揭示作用域", "Reveal 查询需要 VariantContext(baseNovelId, variantId)，baseNovelId 来自已确认的 Story Foundation")
                PendingNote("storyFoundationRepository.getStoryFoundation(novelId).baseNovelId")
            }

            rows.isEmpty() -> {
                EmptyState("还没有揭示记录", "揭示在阅读/推进时由 reveals.createReveal 写入")
                PendingNote(
                    "reveals.createReveal(ctx, entity)",
                    "写入本轮未接入（Reader-only / Variant-only，Original 作用域后端即拒绝）。",
                )
            }

            else -> {
                rows.forEach { (info, where, at) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(info, style = MaterialTheme.typography.bodyLarge, color = palette.text, modifier = Modifier.weight(1f))
                        Text("$where  ·  $at", style = QianyanCodeStyle, color = palette.text3)
                    }
                    HLine()
                }
                SourceNote("来源：reveals.listByScope(VariantContext)（只读）")
            }
        }
    }
}

/* ---------------- 共享小件 ---------------- */

@Composable
private fun StoryField(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = QianyanTheme.palette.text3,
            modifier = Modifier.width(64.dp),
        )
        Text(
            value.ifBlank { "—" },
            style = MaterialTheme.typography.bodyMedium,
            color = QianyanTheme.palette.text2,
            modifier = Modifier.weight(1f),
        )
    }
}
