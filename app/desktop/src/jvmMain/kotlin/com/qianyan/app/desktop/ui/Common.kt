package com.qianyan.app.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qianyan.app.desktop.ui.theme.QianyanCodeStyle
import com.qianyan.app.desktop.ui.theme.QianyanDimens
import com.qianyan.app.desktop.ui.theme.QianyanShapes
import com.qianyan.app.desktop.ui.theme.QianyanTheme
import com.qianyan.app.desktop.ui.theme.QianyanThemeMode
import com.qianyan.app.desktop.ui.workspace.WorkspaceController
import com.qianyan.model.core.Novel
import com.qianyan.model.NovelId
import com.qianyan.model.VocabularyCandidateId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 一级导航。
 *
 * 前 9 项（AGENT … SETTINGS）是已实现并锁定的 IA，**顺序 / 编号 / 文案不变**；
 * 其后为本轮按规划文档补出的能力版面（阅读 / 词库 / 记忆 / 作者智能 / 备份），
 * 不给数字键，仅作为从属分组出现。`Ctrl/⌘+1…8` 的映射只认 [shortcut]，与顺序无关。
 *
 * [group] 仅用于侧栏分组小标题，不参与任何业务判断。
 */
enum class NavItem(val no: String?, val title: String, val group: String?) {
    AGENT("1", "Qianyan Agent", "创作"),
    WRITE("2", "正文", "创作"),
    READ(null, "阅读", "创作"),
    STORY("3", "故事", "结构"),
    CHAPTERS("4", "章节", "结构"),
    CHARACTERS("5", "人物", "结构"),
    WORLD("6", "世界", "结构"),
    VOCABULARY(null, "词库", "素材"),
    MEMORY(null, "记忆", "素材"),
    AUTHOR(null, "作者智能", "智能"),
    TASKS("7", "任务", "运行"),
    HISTORY("8", "历史", "运行"),
    BACKUP(null, "备份", "运行"),
    SETTINGS(null, "设置", null),
    ;

    val shortcut: Int? get() = no?.toIntOrNull()
}

/** 面板折叠（⌘B / ⌘\ / ⌘⇧E）。 */
enum class PanelToggle { SIDEBAR, INSPECTOR, FOCUS }

/** 正文字号档位（**纯显示偏好**，不落库、不进领域模型）。 */
enum class ProseSize(val label: String, val size: Int) {
    SMALL("小", 16),
    MEDIUM("中", 18),
    LARGE("大", 21),
    ;

    companion object {
        val Default = MEDIUM
    }
}

/**
 * 状态栏信息（章节 / 字数 / 待确认 / 任务 / 保存）。
 *
 * 只承载**当前页面投影出来的展示值**（由各页面写入）；不缓存业务对象、不落库。
 */
data class StatusInfo(
    val chapter: String = "—",
    val words: String = "—",
    val pending: String = "0",
    val task: String = "空闲",
    val save: String = "—",
)

/**
 * 应用级 UI 状态（PC 侧持有）。
 *
 * 业务能力一律经 [com.qianyan.application.di.ApplicationContainer] 的既有 UseCase 访问；
 * 本类**不**直连 Repository / SQLite / Workflow 内部对象，也不缓存业务对象（除当前作品引用）。
 */
class DesktopAppState(val graph: com.qianyan.app.desktop.di.DesktopGraph) {

    val scope = CoroutineScope(Dispatchers.Main)

    /** Workspace（当前对象 + Inspector 三 Tab 的 UI 会话态）。 */
    val workspace = WorkspaceController(this)

    var nav by mutableStateOf(NavItem.AGENT)
    var novel by mutableStateOf<Novel?>(null)
    var toast by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)
    var refreshKey by mutableStateOf(0)
    var providerSettingsOpen by mutableStateOf(false)

    /** 面板折叠：左侧导航 / Inspector / 专注模式。 */
    var sidebarOpen by mutableStateOf(true)
    var inspectorOpen by mutableStateOf(true)
    var focusMode by mutableStateOf(false)

    /** Command Palette（Ctrl/⌘ + K）。 */
    var paletteOpen by mutableStateOf(false)
    var paletteSeed by mutableStateOf("")

    /** 状态栏（由当前页面写入展示值；见 [StatusInfo]）。 */
    var status by mutableStateOf(StatusInfo())

    /** 设置（**弹窗**：模态浮层，不替换主工作区页面，关闭后页面与 Selection 不变）。 */
    var settingsOpen by mutableStateOf(false)

    /** 新建作品弹窗（Qianyan Agent 与命令面板共用同一入口）。 */
    var createNovelOpen by mutableStateOf(false)

    /** 主题模式（纯 UI 显示偏好；用户在「设置 · 外观 · 主题」中切换）。 */
    var themeMode by mutableStateOf(QianyanThemeMode.SYSTEM)

    /** 正文字号档位（纯显示偏好；用户在「设置 · 写作 · 排版」或正文 ··· 菜单中切换）。 */
    var proseSize by mutableStateOf(ProseSize.Default)

    /**
     * 列表键盘导航开关（↑ / ↓ / Enter）。
     *
     * **只有列表型页面开启**：编辑器 / 命令面板 / 弹窗打开时关闭，
     * 否则窗口级 `onPreviewKeyEvent` 会抢走正文编辑器的 Enter 与光标上下键。
     */
    var listNavEnabled by mutableStateOf(false)

    /** 弹窗（对话框）是否打开：打开时暂停列表键盘导航，避免抢走输入焦点。 */
    var modalOpen by mutableStateOf(false)

    val projectId get() = novel?.projectId

    fun toggle(which: PanelToggle) {
        when (which) {
            PanelToggle.SIDEBAR -> sidebarOpen = !sidebarOpen
            PanelToggle.INSPECTOR -> inspectorOpen = !inspectorOpen
            PanelToggle.FOCUS -> {
                focusMode = !focusMode
                if (focusMode) {
                    sidebarOpen = false
                    inspectorOpen = false
                } else {
                    sidebarOpen = true
                    inspectorOpen = true
                }
            }
        }
    }

    fun openPalette(seed: String = "") {
        paletteSeed = seed
        paletteOpen = true
    }

    fun toast(msg: String) {
        toast = msg
        scope.launch {
            kotlinx.coroutines.delay(3200)
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
     * U2：TXT 导入专用后台执行。用户可见状态区分三种结果，不落入通用"出错了"模糊报错：
     *  - 完整成功：导入 + 章节初始化计数；
     *  - 已导入但初始化未完成（[ApplicationError.TxtCanonicalInitIncomplete]，含具体原因，重新导入同一文件可补齐）；
     *  - 导入本身失败（解析 / 编码 / 存储等，各自的具体文案）。
     */
    fun launchTxtImport(fileName: String, bytes: ByteArray) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                val out = withContext(Dispatchers.IO) {
                    container.txts.importTxtAsOriginal(com.qianyan.engine.txt.TxtSource(bytes, fileName), fileName)
                }
                val init = out.canonicalInit
                toast(
                    when {
                        init == null -> "已导入 ${fileName}"
                        init.skippedChapterCount == 0 -> "已导入 ${fileName}，已生成 ${init.initializedChapterCount} 章正文"
                        else -> "已导入 ${fileName}（新初始化 ${init.initializedChapterCount} 章，已有 ${init.skippedChapterCount} 章跳过）"
                    },
                )
            } catch (e: com.qianyan.application.error.ApplicationException) {
                toast(com.qianyan.app.desktop.ui.write.messageFor(e))
            } catch (e: Exception) {
                toast("出错了：${e.message ?: e.javaClass.simpleName}")
            } finally {
                busy = false
                refreshKey++
            }
        }
    }

    /**
     * 新执行链路（任务 A）：自然语言任务入口 —— AgentPage 输入 → **既有** NovelAgent 七相位编排
     * （INTENT 确定性解析 → SKILL → CONTEXT → EXECUTE → REVIEW → GATE → COMMIT；不新建第二套 Agent Loop）。
     * 结果（COMPLETED / WAITING_HUMAN / FAILED / CANCELLED）映射为用户可见状态，不吞、不伪装成功。
     * 未打开作品时明确拒绝（不猜项目）；同一时刻只允许一个后台任务（与导入共用 busy 闸）。
     */
    fun launchAgentTask(userIntent: String) {
        val intent = userIntent.trim()
        if (intent.isEmpty()) return
        val projectId = projectId
        if (projectId == null) {
            toast("请先新建或打开一部作品，再下达任务")
            return
        }
        if (busy) return
        busy = true
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    container.novelAgent.run(
                        com.qianyan.model.novelagent.NovelAgentRequest(projectId = projectId, userIntent = intent),
                    )
                }
                toast(agentTaskToast(result))
            } catch (e: Exception) {
                toast("出错了：${e.message ?: e.javaClass.simpleName}")
            } finally {
                busy = false
                refreshKey++
            }
        }
    }

    /**
     * 任务 C：TXT → 写作能力资产（词库候选）。复用既有 analysis.analyzeTxtOriginal + getOrCreateNovelVocabulary；
     * 产出 PENDING 候选，确认/拒绝在词库页进行（不自动 APPROVED）。
     */
    fun launchTxtAnalysis(novelId: NovelId) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                val summary = withContext(Dispatchers.IO) {
                    val document = container.txts.findDocumentsByNovel(novelId).firstOrNull()
                        ?: throw com.qianyan.application.error.ApplicationException(
                            com.qianyan.application.error.ApplicationError.EntityNotFound("该作品没有已导入的 TXT 文本，无法分析"),
                        )
                    val vocabularyId = container.vocabularies.getOrCreateNovelVocabulary(novelId)
                    val context = com.qianyan.model.core.VariantContext(
                        baseNovelId = com.qianyan.model.BaseNovelId(novelId.value),
                    )
                    val out = container.analysis.analyzeTxtOriginal(document.documentId, vocabularyId, context)
                    "分析完成：${out.chapterCount} 章 / ${out.blockCount} 段，生成 ${out.candidateIds.size} 条候选词（待在词库页确认）"
                }
                toast(summary)
            } catch (e: com.qianyan.application.error.ApplicationException) {
                toast(com.qianyan.app.desktop.ui.write.messageFor(e))
            } catch (e: Exception) {
                toast("分析失败：${e.message ?: e.javaClass.simpleName}")
            } finally {
                busy = false
                refreshKey++
            }
        }
    }

    /** 任务 C：词库候选处置（PENDING → APPROVED / REJECTED，复用既有 VocabularyUseCases，幂等由后端保证）。 */
    fun launchCandidateDecision(novelId: NovelId, candidateId: VocabularyCandidateId, confirm: Boolean) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                val canonical = withContext(Dispatchers.IO) {
                    if (confirm) container.vocabularies.confirmCandidate(candidateId, novelId).suggested.canonical
                    else container.vocabularies.rejectCandidate(candidateId, novelId).suggested.canonical
                }
                toast(if (confirm) "已确认词条「$canonical」" else "已拒绝候选「$canonical」")
            } catch (e: com.qianyan.application.error.ApplicationException) {
                toast(com.qianyan.app.desktop.ui.write.messageFor(e))
            } catch (e: Exception) {
                toast("操作失败：${e.message ?: e.javaClass.simpleName}")
            } finally {
                busy = false
                refreshKey++
            }
        }
    }

    /**
     * 后台执行并把结果**回到主线程交付**（Compose state 必须在主线程写）。
     *
     * 与 [launchWork] 的区别：`then` 收到的结果可用于赋值 `DesktopAppState` 的 state 字段。
     */
    fun <T> io(then: (T) -> Unit, block: suspend () -> T) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { block() }
                then(result)
            } catch (e: Exception) {
                toast("出错了：${e.message ?: e.javaClass.simpleName}")
            } finally {
                busy = false
                refreshKey++
            }
        }
    }

    /** 读取数据：IO 线程查询，**回到主线程返回**（Compose state 赋值必须在主线程）。 */
    suspend fun <T> load(block: (com.qianyan.application.di.ApplicationContainer) -> T): T =
        withContext(Dispatchers.IO) { block(container) }

    val container get() = graph.container
}

/**
 * [com.qianyan.model.novelagent.NovelAgentResult] → 用户可见文案（internal 供桌面单测断言）。
 * 四种结局必须可区分：完成摘要原样透出；等待人工必须明说"等待确认写入"；失败带类型化 detail。
 */
internal fun agentTaskToast(result: com.qianyan.model.novelagent.NovelAgentResult): String = when (result.outcome) {
    com.qianyan.model.novelagent.NovelAgentOutcome.COMPLETED -> result.summary
    com.qianyan.model.novelagent.NovelAgentOutcome.WAITING_HUMAN -> "任务已就绪，等待你确认写入正文（在写作页批准或拒绝）"
    com.qianyan.model.novelagent.NovelAgentOutcome.FAILED ->
        "任务失败：${result.failure?.detail ?: result.summary}"
    com.qianyan.model.novelagent.NovelAgentOutcome.CANCELLED -> "任务已取消"
}

/* ---------------- 基元：分隔线 / 标签 / 文本 ---------------- */

/** 1px 水平分隔线（层次靠线与留白，不靠阴影）。 */
@Composable
fun HLine(modifier: Modifier = Modifier, color: Color = QianyanTheme.palette.line) {
    Box(modifier.fillMaxWidth().height(1.dp).background(color))
}

/** 1px 垂直分隔线。 */
@Composable
fun VLine(color: Color = QianyanTheme.palette.line) {
    Box(Modifier.fillMaxHeight().width(1.dp).background(color))
}

/**
 * 分组小标题（字距放开的细体；IDE 的 section label）。
 *
 * 不用彩色、不用大写转换以外的一切装饰。
 */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, trailing: (@Composable RowScope.() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = QianyanTheme.palette.text3,
            letterSpacing = 0.14.sp,
            maxLines = 1,
        )
        if (trailing != null) {
            Spacer(Modifier.weight(1f))
            trailing()
        }
    }
}

/** 页面标题行（标题 + 可选说明 + 右侧动作），下方一条分隔线。 */
@Composable
fun PageHeader(
    title: String,
    meta: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth().padding(start = QianyanDimens.PageX, end = QianyanDimens.PageX, top = 16.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.displaySmall, color = QianyanTheme.palette.text, maxLines = 1)
        if (meta != null) {
            Spacer(Modifier.width(10.dp))
            Text(meta, style = QianyanCodeStyle, color = QianyanTheme.palette.text3, maxLines = 1)
        }
        Spacer(Modifier.weight(1f))
        actions()
    }
    HLine()
}

/** 安静的状态标记（等宽小字 + 极轻底色）。 */
@Composable
fun Tag(text: String, color: Color = QianyanTheme.palette.text3) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(QianyanShapes.r))
            .background(color.copy(alpha = 0.10f))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

/** 键值行（Inspector / 设置；左标签右值，等宽可选）。 */
@Composable
fun FieldRow(label: String, value: String, mono: Boolean = false, labelWidth: Dp = 74.dp) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = QianyanTheme.palette.text3,
            modifier = Modifier.width(labelWidth),
            maxLines = 1,
        )
        Text(
            value.ifBlank { "—" },
            style = if (mono) QianyanCodeStyle else MaterialTheme.typography.bodyMedium,
            color = QianyanTheme.palette.text,
            maxLines = 4,
            modifier = Modifier.weight(1f),
        )
    }
}

/* ---------------- 页内 Tab（下划线式；宽度 = 标签自身宽度） ---------------- */

@Composable
fun QianyanTabs(
    tabs: List<String>,
    selected: Int,
    modifier: Modifier = Modifier,
    onSelect: (Int) -> Unit,
) {
    val palette = QianyanTheme.palette
    Row(
        modifier.fillMaxWidth().background(palette.surface)
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
                        .background(if (on) palette.accent else Color.Transparent),
                )
            }
        }
    }
    HLine()
}

/* ---------------- 骨架页 / 只读来源标注（不伪造数据、不放假按钮） ---------------- */

/**
 * 「当前阶段未接入」提示。
 *
 * 规则（README「骨架页」约定）：未接线的能力必须写明**届时应接入的 UseCase / 方法**，
 * 不提供假按钮、不写死虚假数据。本函数是这条规则的统一出口。
 */
@Composable
fun PendingNote(useCase: String, detail: String? = null, modifier: Modifier = Modifier) {
    val palette = QianyanTheme.palette
    Column(modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(
            "当前阶段未接入 · 接入点：$useCase",
            style = QianyanCodeStyle,
            color = palette.text3,
        )
        if (detail != null) {
            Spacer(Modifier.height(3.dp))
            Text(detail, style = MaterialTheme.typography.bodySmall, color = palette.text3)
        }
    }
}

/** 只读来源标注（说明这块内容是真数据、来自哪条链路）。 */
@Composable
fun SourceNote(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = QianyanTheme.palette.text3,
        modifier = modifier.fillMaxWidth().padding(top = 10.dp),
    )
}

/** 分组信息块（标题 + 若干键值行，无卡片背景）。 */
@Composable
fun InfoBlock(title: String, rows: List<Pair<String, String>>, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        SectionLabel(title)
        rows.forEach { (k, v) -> FieldRow(k, v) }
    }
}

/* ---------------- 列表行 ---------------- */

/**
 * 统一列表行：`[序号] 标题 …… [次要信息] [状态标记]`。
 *
 * 选中态只用一层极轻底色 + 文字颜色变化；不使用卡片 / 边框 / 阴影。
 */
@Composable
fun ListRow(
    title: String,
    index: String? = null,
    meta: String? = null,
    tag: String? = null,
    tagColor: Color = QianyanTheme.palette.text3,
    selected: Boolean = false,
    horizontalPadding: Dp = QianyanDimens.PageX,
    onClick: (() -> Unit)? = null,
) {
    val palette = QianyanTheme.palette
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding, vertical = 1.dp)
            .clip(RoundedCornerShape(QianyanShapes.r))
            .background(if (selected) palette.selection else Color.Transparent)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (index != null) {
            Text(
                index,
                style = QianyanCodeStyle,
                color = if (selected) palette.text2 else palette.text3,
                modifier = Modifier.width(30.dp),
                maxLines = 1,
            )
        }
        Text(
            title.ifBlank { "未命名" },
            style = MaterialTheme.typography.bodyLarge,
            color = palette.text,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        if (meta != null) {
            Spacer(Modifier.width(10.dp))
            Text(meta, style = QianyanCodeStyle, color = palette.text3, maxLines = 1)
        }
        if (tag != null) {
            Spacer(Modifier.width(10.dp))
            Tag(tag, tagColor)
        }
    }
}

/* ---------------- 按钮层级（全局只允许四级） ---------------- */

/**
 * 按钮层级：[ActionLevel.PRIMARY]（真正的主要动作，如 保存 / 继续写 / 生成修改）、
 * [ActionLevel.SECONDARY]（普通操作，1px 细边）、[ActionLevel.GHOST]（低频，无边框）、
 * [ActionLevel.TEXT]（菜单 / 辅助）。
 *
 * 一屏内 PRIMARY 至多一个；不使用发光 / 渐变 / 高饱和填充。
 */
enum class ActionLevel { PRIMARY, SECONDARY, GHOST, TEXT }

@Composable
fun ActionButton(
    label: String,
    level: ActionLevel = ActionLevel.SECONDARY,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val palette = QianyanTheme.palette
    val textColor = when {
        !enabled -> palette.text3.copy(alpha = 0.55f)
        level == ActionLevel.PRIMARY -> palette.onAccent
        level == ActionLevel.SECONDARY -> palette.text2
        else -> palette.text3
    }
    val shape = RoundedCornerShape(QianyanShapes.r2)
    var base = Modifier.clip(shape)
    base = when {
        level == ActionLevel.PRIMARY && enabled -> base.background(palette.accent)
        level == ActionLevel.SECONDARY && enabled -> base.border(1.dp, palette.lineStrong, shape)
        else -> base
    }
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = if (level == ActionLevel.PRIMARY) FontWeight.Medium else FontWeight.Normal,
        color = textColor,
        maxLines = 1,
        modifier = base
            .then(if (enabled) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = if (level == ActionLevel.TEXT) 4.dp else 10.dp, vertical = 4.dp),
    )
}

/* ---------------- 输入（安静的单行 / 多行输入） ---------------- */

/**
 * 安静输入框：画布底色 + 1px 细边；聚焦时边框转为 accent。
 * 不使用 Material 默认的填充胶囊与彩色下划线。
 *
 * [onSubmit] 存在时，聚焦状态下按 Enter 触发（返回 true 表示已消费）。
 */
@Composable
fun QuietField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    singleLine: Boolean = true,
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    minHeight: Dp = 30.dp,
    onKeyEvent: ((KeyEvent) -> Boolean)? = null,
    onSubmit: (() -> Unit)? = null,
) {
    val palette = QianyanTheme.palette
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(QianyanShapes.r3)
    Box(
        modifier
            .clip(shape)
            .background(palette.bg)
            .border(1.dp, if (focused) palette.accent else palette.lineStrong, shape)
            .padding(horizontal = 9.dp, vertical = 7.dp),
    ) {
        if (value.isEmpty() && placeholder.isNotEmpty()) {
            Text(placeholder, style = textStyle, color = palette.text3, maxLines = 1)
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            textStyle = textStyle.copy(color = palette.text),
            cursorBrush = SolidColor(palette.accent),
            visualTransformation = visualTransformation,
            modifier = Modifier
                .fillMaxWidth()
                .then(if (singleLine) Modifier.height(minHeight - 14.dp) else Modifier)
                .onFocusChanged { focused = it.isFocused }
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    if (event.key == Key.Enter && onSubmit != null) {
                        onSubmit()
                        return@onPreviewKeyEvent true
                    }
                    onKeyEvent?.invoke(event) ?: false
                },
        )
    }
}

/* ---------------- Empty / Loading / Error（LOCKED：极简，不做大卡片与插图） ---------------- */

@Composable
fun EmptyState(text: String = "暂无内容", hint: String? = null, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(vertical = 26.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = QianyanTheme.palette.text3)
        if (hint != null) {
            Spacer(Modifier.height(4.dp))
            Text(hint, style = MaterialTheme.typography.bodySmall, color = QianyanTheme.palette.text3)
        }
    }
}

@Composable
fun LoadingState(text: String = "正在读取…", modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = QianyanTheme.palette.text3,
        modifier = modifier.padding(vertical = 16.dp, horizontal = 4.dp),
    )
}

@Composable
fun ErrorState(message: String? = null, onRetry: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(vertical = 14.dp, horizontal = 4.dp)) {
        Text("暂时无法读取", style = MaterialTheme.typography.bodyMedium, color = QianyanTheme.palette.del)
        if (message != null) {
            Spacer(Modifier.height(4.dp))
            Text(message, style = MaterialTheme.typography.bodySmall, color = QianyanTheme.palette.text3, maxLines = 4)
        }
        if (onRetry != null) {
            Spacer(Modifier.height(8.dp))
            ActionButton("重试", ActionLevel.SECONDARY, true, onRetry)
        }
    }
}

/* ---------------- 语义标签（真实枚举 → 用户语言） ---------------- */

/**
 * 状态 → 用户语言标签。
 *
 * 未知值**原样返回**（不猜测、不伪造）；本函数不做任何业务判断。
 */
fun statusLabel(name: String?): String = when (name) {
    null, "" -> "—"
    // 章节（ChapterStatus）
    "PLANNED" -> "待写"
    "DRAFTING" -> "起草中"
    // 正文（DraftStatus）
    "WRITTEN" -> "已写完"
    "REVISED" -> "已修订"
    "FINAL" -> "定稿"
    "PENDING_CONFIRMATION" -> "待确认"
    "CONFIRMED" -> "已确认"
    // 工作流阶段（ChapterPhase）
    "NOT_STARTED" -> "未开始"
    "PLANNING" -> "规划中"
    "WRITING" -> "写作中"
    "REVIEWING" -> "审校中"
    "REVISING" -> "修订中"
    "WAITING_CONFIRMATION" -> "等待人工确认"
    "UPDATING_STORY" -> "更新故事状态"
    // 任务（TaskStatus / TaskQueue 状态）
    "PENDING" -> "待处理"
    "QUEUED" -> "排队中"
    "CLAIMED" -> "已领取"
    "RUNNING" -> "运行中"
    "PAUSED" -> "已暂停"
    "COMPLETED" -> "已完成"
    "CANCELLED" -> "已取消"
    "FAILED" -> "已失败"
    // 活动 / 工具（AgentLogStatus）
    // （RUNNING / COMPLETED / FAILED / CANCELLED 同上）
    // 变更（ChangeKind / ChangeArtifactStatus）
    "UNCHANGED" -> "无变化"
    "ADDED" -> "新增"
    "REMOVED" -> "删除"
    "MODIFIED" -> "修改"
    "READY" -> "可确认"
    "NO_CHANGES" -> "无改动"
    "INVALID" -> "不通过校验"
    // 提交（CommitOperation）
    "COMMIT" -> "提交"
    "REVERT" -> "回退"
    // 作品（ProjectStatus）
    "ACTIVE" -> "进行中"
    "IDLE" -> "空闲"
    // 词库 / 记忆作用域
    "GLOBAL" -> "全局"
    "NOVEL" -> "本书"
    "VARIANT" -> "变体"
    "TASK" -> "任务级"
    // 词库候选来源 / 状态
    "APPROVED" -> "已采纳"
    "REJECTED" -> "已拒绝"
    "MERGED" -> "已合并"
    // 决策结果（P19）
    "PROGRESSIVE_REVEAL" -> "渐进揭示"
    "DIRECT_PRESENTATION" -> "直接呈现"
    "SHORT_DOMINANT" -> "短句主导"
    "LONG_DOMINANT" -> "长句主导"
    "RULE_MATRIX" -> "规则矩阵"
    "DETERMINISTIC_DEFAULT" -> "确定性默认"
    else -> name
}

/** 任务类型（TaskType）→ 用户语言。 */
fun taskTypeLabel(name: String?): String = when (name) {
    "ANALYSIS" -> "分析"
    "WRITING" -> "写作"
    "PLANNING" -> "规划"
    "KNOWLEDGE_UPDATE" -> "故事状态更新"
    "IMPORT" -> "导入"
    "BACKGROUND" -> "后台任务"
    null, "" -> "—"
    else -> name
}

/** 伏笔生命周期（ForeshadowLifecycleState）→ 用户语言（与 [statusLabel] 分开，避免 ACTIVE 语义串台）。 */
fun foreshadowStateLabel(name: String?): String = when (name) {
    "PLANTED" -> "已埋"
    "ACTIVE" -> "推进中"
    "RESOLVED" -> "已回收"
    "ABANDONED" -> "已废弃"
    null, "" -> "—"
    else -> name
}

/** 状态标签的语义色（低饱和：只用 accent / add / del / 次级灰）。 */
@Composable
fun statusTint(name: String?): Color = when (name) {
    "WRITTEN", "REVISED", "FINAL", "CONFIRMED", "COMPLETED" -> QianyanTheme.palette.add
    "FAILED", "CANCELLED", "INVALID" -> QianyanTheme.palette.del
    "RUNNING", "WRITING", "DRAFTING", "REVISING", "REVIEWING", "PLANNING", "QUEUED", "CLAIMED" ->
        QianyanTheme.palette.accent
    "WAITING_CONFIRMATION", "PENDING_CONFIRMATION", "READY", "PENDING" -> QianyanTheme.palette.accent
    else -> QianyanTheme.palette.text3
}

/* ---------------- 工具函数 ---------------- */

/** 人类可读时间（epoch → 本地简写；不引入新依赖）。 */
fun shortTime(instant: kotlinx.datetime.Instant): String =
    instant.toString().replace('T', ' ').take(16)

/** 千分位字数（展示用）。 */
fun formatCount(n: Int): String = n.toString().reversed().chunked(3).joinToString(",").reversed()

/** 字数统计（中文按字符计，用于状态栏，不做分词）。 */
fun wordCount(text: String): Int = text.count { !it.isWhitespace() }

/** 平台修饰键显示（macOS ⌘ / 其他 Ctrl；不把 ⌘ 写死）。 */
fun isMac(): Boolean = System.getProperty("os.name").orEmpty().startsWith("Mac", ignoreCase = true)

fun shortcutLabel(): String = if (isMac()) "⌘" else "Ctrl"

/** 正文段落预览（列表 / Inspector 展示用；只截断，不改内容）。 */
fun preview(text: String, limit: Int = 60): String {
    val one = text.replace('\n', ' ').trim()
    return if (one.length <= limit) one else one.take(limit) + "…"
}

/**
 * 章节标题去前缀（**纯展示**）。
 *
 * 真实数据里 Chapter.title 常自带「第 1 章 ·」前缀（导入与建章时的写法），
 * 而列表已有独立序号列、稿纸也有独立章节行 ⇒ 展示时去掉重复前缀。不匹配则原样返回。
 */
fun chapterTitleOnly(title: String): String {
    val trimmed = title.trim()
    val stripped = trimmed.replaceFirst(Regex("^第\\s*[0-9一二三四五六七八九十百零]+\\s*章\\s*[·・.、:：—\\-–]*\\s*"), "")
    return stripped.ifBlank { trimmed }
}

/** 面板容器的通用内边距（IDE 密度）。 */
@Composable
fun PanelColumn(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.padding(QianyanDimens.Gutter), content = content)
}

/**
 * 吞掉落在该区域内的点击（浮层面板用；避免点击面板时穿透到背后的遮罩而误关闭）。
 */
@Composable
fun Modifier.consumeClicks(): Modifier = this.clickable(
    interactionSource = remember { MutableInteractionSource() },
    indication = null,
) { }
