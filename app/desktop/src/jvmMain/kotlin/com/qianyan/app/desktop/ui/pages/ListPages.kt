package com.qianyan.app.desktop.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import com.qianyan.app.desktop.ui.ActionButton
import com.qianyan.app.desktop.ui.ActionLevel
import com.qianyan.app.desktop.ui.DesktopAppState
import com.qianyan.app.desktop.ui.EmptyState
import com.qianyan.app.desktop.ui.HLine
import com.qianyan.app.desktop.ui.ListRow
import com.qianyan.app.desktop.ui.LoadingState
import com.qianyan.app.desktop.ui.Workspace
import com.qianyan.app.desktop.ui.PageHeader
import com.qianyan.app.desktop.ui.PendingNote
import com.qianyan.app.desktop.ui.QuietField
import com.qianyan.app.desktop.ui.QianyanTabs
import com.qianyan.app.desktop.ui.SectionLabel
import com.qianyan.app.desktop.ui.SourceNote
import com.qianyan.app.desktop.ui.Tag
import com.qianyan.app.desktop.ui.UiCard
import com.qianyan.app.desktop.ui.chapterTitleOnly
import com.qianyan.app.desktop.ui.consumeClicks
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
import com.qianyan.model.WorldRuleId
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
    var creating by remember { mutableStateOf(false) }

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
            state.nav = Workspace.WRITE
        }
    }

    Column(Modifier.fillMaxSize()) {
        PageHeader(
            "章节",
            meta = if (chapters.isEmpty()) null else "共 ${chapters.size} 章",
            actions = {
                ActionButton("新建章节", ActionLevel.PRIMARY, !state.busy) { creating = true }
            },
        )

        when {
            state.novel == null -> Box(Modifier.padding(QianyanDimens.PageX)) {
                EmptyState("尚未打开作品", "从顶部「当前作品」打开或新建作品")
            }

            loading -> Box(Modifier.padding(QianyanDimens.PageX)) { LoadingState() }

            chapters.isEmpty() -> Box(Modifier.padding(QianyanDimens.PageX)) {
                EmptyState("还没有章节", "点右上角「新建章节」开始，或导入 TXT 自动建立章节")
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
                            state.nav = Workspace.WRITE
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

    if (creating) {
        CreateChapterDialog(state) { creating = false }
    }
}

/**
 * 新建章节：追加到当前作品末尾（走既有 `chapters.createNextChapter`）。
 * 只收集标题；章节内容仍在正文页生成 / 编辑。
 */
@Composable
private fun CreateChapterDialog(state: DesktopAppState, onClose: () -> Unit) {
    val palette = QianyanTheme.palette
    val novel = state.novel
    var title by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        state.modalOpen = true
        onDispose { state.modalOpen = false }
    }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize()
                .background(Color.Black.copy(alpha = if (QianyanTheme.isDark) 0.55f else 0.32f))
                .clickable { if (!working) onClose() },
        )
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                Modifier.width(420.dp)
                    .clip(RoundedCornerShape(QianyanShapes.r3))
                    .background(palette.raised)
                    .consumeClicks()
                    .padding(18.dp),
            ) {
                Text("新建章节", style = MaterialTheme.typography.titleLarge, color = palette.text)
                Spacer(Modifier.height(4.dp))
                Text(
                    "在《${novel?.title ?: "未打开作品"}》末尾追加一章；标题可留空，正文在「正文」页写。",
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.text3,
                )
                Spacer(Modifier.height(14.dp))
                QuietField(
                    value = title,
                    onValueChange = { title = it },
                    placeholder = "章节标题（可留空）",
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    ActionButton("取消", ActionLevel.TEXT, !working, onClose)
                    Spacer(Modifier.width(8.dp))
                    ActionButton("创建", ActionLevel.PRIMARY, novel != null && !working && !state.busy) {
                        val target = novel ?: return@ActionButton
                        working = true
                        state.io(
                            then = { chapter ->
                                working = false
                                onClose()
                                state.workspace.select(
                                    WorkspaceSelection.chapter(target.projectId, target.novelId, chapter.chapterId),
                                )
                                state.openWorkspace(Workspace.WRITE, 0)
                                state.toast("已创建第 ${chapter.order} 章")
                            },
                            onFailure = { working = false },
                        ) {
                            state.container.chapters.createNextChapter(
                                title = title.trim().ifBlank { "未命名" },
                                novelId = target.novelId,
                            )
                        }
                    }
                }
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
                EmptyState("尚未打开作品", "从顶部「当前作品」打开或新建作品")
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
 * 资料 · 总览（HTML 对齐版）：人物与世界规则的暖白卡片墙。
 *
 * 数据全部来自既有 Story State（人物档案 / 世界规则），点击卡片把 Workspace 当前对象
 * 切到该条目并进入对应详情页；不新增业务能力。
 */
@Composable
fun MaterialsOverview(state: DesktopAppState) {
    val palette = QianyanTheme.palette
    var characters by remember { mutableStateOf<List<Character>>(emptyList()) }
    var rules by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(state.novel?.novelId, state.refreshKey) {
        val novelId = state.novel?.novelId
        if (novelId == null) {
            loading = false
            return@LaunchedEffect
        }
        loading = true
        state.load {
            characters = it.storyState.listCharacters(novelId, null)
            rules = it.storyState.listWorldRules(novelId, null)
                .map { r -> r.ruleId.value to r.content }
                .filter { (_, content) -> content.isNotBlank() }
        }
        loading = false
    }

    Column(Modifier.fillMaxSize()) {
        PageHeader(
            "资料",
            meta = if (state.novel == null) null else "人物 ${characters.size} · 世界规则 ${rules.size}",
        )
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(QianyanDimens.PageX)) {
            when {
                state.novel == null -> EmptyState("尚未打开作品", "从顶部「当前作品」打开或新建作品")
                loading -> LoadingState()
                else -> {
                    SectionLabel("人物 · ${characters.size}")
                    if (characters.isEmpty()) {
                        EmptyState("还没有人物", "Story State 里还没有人物档案")
                    } else {
                        characters.chunked(2).forEach { pair ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(13.dp)) {
                                pair.forEach { person ->
                                    UiCard(
                                        modifier = Modifier.weight(1f),
                                        title = person.name,
                                        meta = "人物",
                                        tags = (person.personality + person.goals).filter { it.isNotBlank() }.take(2),
                                        onClick = {
                                            state.workspace.select(
                                                WorkspaceSelection.character(
                                                    state.novel!!.projectId,
                                                    state.novel!!.novelId,
                                                    person.characterId,
                                                ),
                                            )
                                            state.openWorkspace(Workspace.MATERIALS, 1)
                                        },
                                    ) {
                                        if (person.description.isNotBlank()) {
                                            Spacer(Modifier.height(9.dp))
                                            Text(
                                                person.description,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = palette.text2,
                                                maxLines = 3,
                                            )
                                        }
                                    }
                                }
                                if (pair.size == 1) Spacer(Modifier.weight(1f))
                            }
                            Spacer(Modifier.height(13.dp))
                        }
                    }

                    Spacer(Modifier.height(9.dp))
                    SectionLabel("世界观 · ${rules.size}")
                    if (rules.isEmpty()) {
                        EmptyState("还没有世界规则", "Story State 里还没有世界规则记录")
                    } else {
                        rules.chunked(2).forEach { pair ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(13.dp)) {
                                pair.forEach { (id, content) ->
                                    UiCard(
                                        modifier = Modifier.weight(1f),
                                        title = preview(content, 42),
                                        meta = "世界规则",
                                        onClick = {
                                            state.workspace.select(
                                                WorkspaceSelection(
                                                    projectId = state.novel!!.projectId,
                                                    kind = WorkspaceObjectKind.WORLD_RULE,
                                                    novelId = state.novel!!.novelId,
                                                    worldRuleId = WorldRuleId(id),
                                                ),
                                            )
                                            state.openWorkspace(Workspace.MATERIALS, 2)
                                        },
                                    ) {}
                                }
                                if (pair.size == 1) Spacer(Modifier.weight(1f))
                            }
                            Spacer(Modifier.height(13.dp))
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    Text(
                        "以上为只读展示：数据来自既有 Story State；点击卡片会把「当前对象」切到该条目。",
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.text3,
                    )
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
    var cancelTarget by remember { mutableStateOf<Task?>(null) }

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
                EmptyState("尚未打开作品", "从顶部「当前作品」打开或新建作品")
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
                            trailing = {
                                val paused = task.status.name == "PAUSED" || item?.paused == true
                                if (item != null) {
                                    if (paused) {
                                        ActionButton("恢复", ActionLevel.TEXT, !state.busy) {
                                            state.io(then = { state.toast("已恢复任务") }) {
                                                state.container.taskQueue.resume(task.taskId)
                                                Unit
                                            }
                                        }
                                    } else if (task.status.name == "RUNNING") {
                                        ActionButton("暂停", ActionLevel.TEXT, !state.busy) {
                                            state.io(then = { state.toast("已暂停任务") }) {
                                                state.container.taskQueue.pause(task.taskId)
                                                Unit
                                            }
                                        }
                                    }
                                }
                                if (task.status.name !in setOf("CANCELLED", "COMPLETED", "FAILED")) {
                                    Spacer(Modifier.width(2.dp))
                                    ActionButton("取消", ActionLevel.TEXT, !state.busy) { cancelTarget = task }
                                }
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
                    "tasks.start / complete / saveCheckpoint / restoreCheckpoint",
                    "任务启动 / 完成 / 检查点操作仍未接入；暂停、恢复、取消已接既有 TaskQueueUseCases。",
                )
            }
        }
    }

    cancelTarget?.let { target ->
        ConfirmCancelTaskDialog(state, target) { cancelTarget = null }
    }
}

/** 取消任务确认（取消是不可撤销的操作，先确认再调用既有队列状态机）。 */
@Composable
private fun ConfirmCancelTaskDialog(state: DesktopAppState, task: Task, onClose: () -> Unit) {
    val palette = QianyanTheme.palette
    var working by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        state.modalOpen = true
        onDispose { state.modalOpen = false }
    }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize()
                .background(Color.Black.copy(alpha = if (QianyanTheme.isDark) 0.55f else 0.32f))
                .clickable { if (!working) onClose() },
        )
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                Modifier.width(420.dp)
                    .clip(RoundedCornerShape(QianyanShapes.r3))
                    .background(palette.raised)
                    .consumeClicks()
                    .padding(18.dp),
            ) {
                Text("取消任务", style = MaterialTheme.typography.titleLarge, color = palette.text)
                Spacer(Modifier.height(6.dp))
                Text(
                    "任务类型：${taskTypeLabel(task.type.name)}。排队中的任务会立即取消；执行中的任务只会在执行边界收敛为取消。",
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.text3,
                )
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    ActionButton("返回", ActionLevel.TEXT, !working, onClose)
                    Spacer(Modifier.width(8.dp))
                    ActionButton("取消任务", ActionLevel.PRIMARY, !working && !state.busy) {
                        working = true
                        state.io(
                            then = { disposition ->
                                working = false
                                onClose()
                                state.toast(
                                    if (disposition.name == "CANCELLATION_REQUESTED") {
                                        "已请求取消，将在执行边界收敛"
                                    } else {
                                        "任务已取消"
                                    },
                                )
                            },
                            onFailure = { working = false },
                        ) {
                            state.container.taskQueue.cancel(task.taskId)
                        }
                    }
                }
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
                EmptyState("尚未打开作品", "从顶部「当前作品」打开或新建作品")
            }

            loading -> Box(Modifier.padding(QianyanDimens.PageX)) { LoadingState() }

            tab == 1 -> Box(Modifier.padding(QianyanDimens.PageX)) { RevealSection(state) }

            entries.isEmpty() -> Box(Modifier.padding(QianyanDimens.PageX)) {
                EmptyState("还没有提交", "提交走既有 I10 原子提交；提交后在此形成时间线")
            }

            else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(QianyanDimens.PageX)) {
                Column(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(QianyanShapes.r3))
                        .background(palette.raised)
                        .border(1.dp, palette.line, RoundedCornerShape(QianyanShapes.r3)),
                ) {
                    entries.reversed().forEachIndexed { reverseIndex, entry ->
                        val index = entries.size - 1 - reverseIndex
                        val selected = ws.selection?.historyId == entry.historyId
                        Row(
                            Modifier.fillMaxWidth()
                                .background(if (selected) palette.selection else Color.Transparent)
                                .clickable {
                                    ws.listIndex = index
                                    ws.select(WorkspaceSelection.history(state.projectId!!, entry.historyId, state.novel?.novelId))
                                }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val time = shortTime(entry.createdAt)
                            Column(Modifier.width(88.dp)) {
                                Text(time.take(10), style = QianyanCodeStyle, color = palette.text3, maxLines = 1)
                                Text(time.takeLast(5), style = QianyanCodeStyle, color = palette.text2, maxLines = 1)
                            }
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
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
                                        chapterTitles[entry.target.chapterId.value],
                                        entry.revertedHistoryId?.let { "回退提交" },
                                    ).joinToString("  ·  ").ifBlank { "项目级提交" },
                                    style = QianyanCodeStyle,
                                    color = palette.text3,
                                    maxLines = 1,
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Tag(statusLabel(entry.operation.name), palette.text2)
                        }
                        if (reverseIndex != entries.size - 1) HLine(Modifier.padding(horizontal = 16.dp))
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "提交来自既有 I10 原子提交链路；回退入口尚未接入，不在 UI 上放假按钮。",
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.text3,
                )
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
