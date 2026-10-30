package com.qianyan.app.desktop.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
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
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.draw.shadow
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
import com.qianyan.application.usecase.agent.ConvergenceOutcome
import com.qianyan.model.core.Novel
import com.qianyan.model.NovelId
import com.qianyan.model.VocabularyCandidateId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 一级工作区（已确认：创作 / 正文 / 故事 / 资料 / 历史）。
 *
 * 章节、人物、世界、词库、记忆、任务、备份、Provider 等能力
 * 一律放在所属工作区的内部导航、检查器或设置中，不新增一级导航。
 */
enum class Workspace(val no: Int, val title: String, val shortcut: Int, val hint: String) {
    CREATE(1, "创作", 1, "自然语言任务"),
    WRITE(2, "正文", 2, "写作与阅读"),
    STORY(3, "故事", 3, "结构与剧情"),
    MATERIALS(4, "资料", 4, "人物 · 世界 · 素材"),
    HISTORY(5, "历史", 5, "变更 · 任务 · 备份"),
}

/** 创作工作区里的一条对话消息（纯 UI 会话态，不落库）。 */
enum class CreationMessageRole { USER, ASSISTANT, ERROR }

data class CreationMessage(
    val role: CreationMessageRole,
    val text: String,
    val detail: String? = null,
)

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

    /** 当前 toast 的自动清除任务：同一时刻只允许一个，旧任务不得清掉新消息。 */
    private var toastJob: Job? = null

    /** Workspace（当前对象 + Inspector 三 Tab 的 UI 会话态）。 */
    val workspace = WorkspaceController(this)

    var nav by mutableStateOf(Workspace.CREATE)
    var novel by mutableStateOf<Novel?>(null)
    var toast by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)
    var refreshKey by mutableStateOf(0)
    var providerSettingsOpen by mutableStateOf(false)

    /** 各工作区的内部标签（只记 UI 选择，不参与业务判断）。 */
    var writeTab by mutableStateOf(0)
    var storyTab by mutableStateOf(0)
    var materialsTab by mutableStateOf(0)
    var historyTab by mutableStateOf(0)

    /** 创作工作区的连续会话（纯 UI；同一作品内保留，切换作品时清空）。 */
    var creationMessages by mutableStateOf<List<CreationMessage>>(emptyList())

    /** 创作输入草稿（纯 UI；提升到 App 级，切换工作区不丢失，切换作品时清空）。 */
    var creationDraft by mutableStateOf("")

    /** 面板折叠：左侧导航 / Inspector / 专注模式。 */
    var sidebarOpen by mutableStateOf(true)
    var inspectorOpen by mutableStateOf(true)
    var focusMode by mutableStateOf(false)

    /** Command Palette（Ctrl/⌘ + K）。 */
    var paletteOpen by mutableStateOf(false)
    var paletteSeed by mutableStateOf("")

    /** 状态栏（由当前页面写入展示值；见 [StatusInfo]）。 */
    var status by mutableStateOf(StatusInfo())

    /**
     * P0-3B · 最近一次 Agent 任务结果（候选标识 + 审批接入状态）。
     *
     * UI 契约（§17.2）：候选 / 会话身份必须保留；"是否可批准"以**正式 Workflow 投影**为准，
     * 本状态只用于展示任务结局与候选摘要，不作为批准按钮的依据。
     */
    var lastAgentResult by mutableStateOf<com.qianyan.model.novelagent.NovelAgentResult?>(null)

    /**
     * P0-3B · 最近一次 Agent 任务的原始请求（保留 project / chapter / session 身份，供显式重试与收敛）。
     * 只作为 UI 协调上下文；正式审批事实仍以 ChapterWorkflow 投影为准。
     */
    var lastAgentRequest by mutableStateOf<com.qianyan.model.novelagent.NovelAgentRequest?>(null)

    /** D-35 · 最近一次显式收敛评审结果（只读第二轮；不参与正式审批事实）。 */
    var lastConvergenceReview by mutableStateOf<com.qianyan.application.usecase.agent.ConvergenceReviewResult?>(null)

    /** 设置（**弹窗**：模态浮层，不替换主工作区页面，关闭后页面与 Selection 不变）。 */
    var settingsOpen by mutableStateOf(false)

    /** 新建作品弹窗（顶部项目切换与命令面板共用同一入口）。 */
    var createNovelOpen by mutableStateOf(false)

    /** 主题模式（纯 UI 显示偏好；持久化到本机，重启后恢复）。 */
    var themeMode by mutableStateOf(
        graph.readThemeMode()
            ?.let { name -> runCatching { QianyanThemeMode.valueOf(name) }.getOrNull() }
            ?: QianyanThemeMode.SYSTEM,
    )
        private set

    /** 主题切换的唯一写入口：先更新即时外观，再尝试落盘。 */
    fun updateThemeMode(mode: QianyanThemeMode) {
        themeMode = mode
        if (!graph.saveThemeMode(mode.name)) {
            toast("主题设置未能保存到本机")
        }
    }

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

    /** 切换一级工作区；可选同时落到该工作区的内部标签。 */
    fun openWorkspace(workspace: Workspace, subTab: Int? = null) {
        nav = workspace
        if (subTab != null) {
            when (workspace) {
                Workspace.CREATE -> Unit
                Workspace.WRITE -> writeTab = subTab
                Workspace.STORY -> storyTab = subTab
                Workspace.MATERIALS -> materialsTab = subTab
                Workspace.HISTORY -> historyTab = subTab
            }
        }
    }

    fun openPalette(seed: String = "") {
        paletteSeed = seed
        paletteOpen = true
    }

    fun toast(msg: String) {
        // 取消上一次的清除任务：连续触发同一文案时，旧计时器不得把新 toast 提前清掉。
        toastJob?.cancel()
        toast = msg
        toastJob = scope.launch {
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
     * 结果（COMPLETED / WAITING_HUMAN / CANDIDATE_UNBRIDGED / FAILED / CANCELLED）映射为用户可见状态，不吞、不伪装成功。
     * 未打开作品时明确拒绝（不猜项目）；同一时刻只允许一个后台任务（与导入共用 busy 闸）。
     *
     * P0-3B（§17.2 UI 契约 1/2）：
     *  - 必须携带当前有效的 Chapter 上下文（写作 / 改写 / 分析类任务都需要焦点章节）；
     *  - 保留候选标识与会话身份（`sessionId / workingDraftId / artifactId / approvalState`），
     *    供 AgentPage 与写作页基于**正式 Workflow 投影**展示状态。
     */
    fun launchAgentTask(userIntent: String): Boolean {
        val intent = userIntent.trim()
        if (intent.isEmpty()) return false
        val projectId = projectId
        if (projectId == null) {
            toast("请先新建或打开一部作品，再下达任务")
            return false
        }
        if (busy) return false
        // P0-3B：写作类任务需要焦点章节——从 Workspace 当前 Selection 取（必须属于当前 Project）。
        val chapterId = workspace.selection
            ?.takeIf { it.projectId == projectId }
            ?.chapterId
        if (chapterId == null) {
            toast("请先在左侧章节目录中选择要操作的章节，再下达任务")
            return false
        }
        val request = com.qianyan.model.novelagent.NovelAgentRequest(
            projectId = projectId,
            userIntent = intent,
            activeChapterId = chapterId,
        )
        creationMessages = creationMessages + CreationMessage(CreationMessageRole.USER, intent)
        lastConvergenceReview = null
        busy = true
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { container.novelAgent.run(request) }
                lastAgentRequest = result.sessionId?.let { request.copy(sessionId = it) } ?: request
                lastAgentResult = result
                appendAssistantMessage(result)
                toast(agentTaskToast(result))
            } catch (e: Exception) {
                val message = "出错了：${e.message ?: e.javaClass.simpleName}"
                creationMessages = creationMessages + CreationMessage(CreationMessageRole.ERROR, message)
                toast(message)
            } finally {
                busy = false
                refreshKey++
            }
        }
        return true
    }

    /** P0-3B D4/D5：显式重试把**已有**候选接入正式章节 Workflow（不重新生成候选）。 */
    fun retryAgentBridge() {
        val request = lastAgentRequest ?: return
        val artifactId = lastAgentResult?.artifactId ?: return
        runAgentFollowUp { container.novelAgent.bridgeCandidate(request, artifactId) }
    }

    /**
     * P0-3B D6/D9：正式决议后同步任务状态（只读观察 + Session/候选收敛）。
     * 不创建 Gate、不批准、不拒绝、不 Commit；正式操作仍在「正文」页完成。
     */
    fun refreshAgentCandidate() {
        val request = lastAgentRequest ?: return
        val artifactId = lastAgentResult?.artifactId ?: return
        runAgentFollowUp { container.novelAgent.refreshCandidate(request, artifactId) }
    }

    /** P0-3B D6：丢弃**未进入正式审批**或**已被正式拒绝**的候选；待决/已批准时服务端会拒绝。 */
    fun discardAgentCandidate() {
        val request = lastAgentRequest ?: return
        val artifactId = lastAgentResult?.artifactId ?: return
        runAgentFollowUp {
            container.novelAgent.reject(request, artifactId, reason = "用户丢弃候选（非正式拒绝）")
        }
    }

    /**
     * D-35 · 显式收敛评审：仅当最近结果已有正式 Commit 时，启动一次只读 CharacterConsistency 第二轮。
     * 不自动触发、不循环；结果只展示摘要，不产生 WorkingDraft / Artifact / Commit。
     */
    fun launchConvergenceReview() {
        if (busy) return
        val result = lastAgentResult ?: return
        val commitId = result.commitId ?: return
        val artifactId = result.artifactId ?: return
        val request = lastAgentRequest ?: return
        val chapterId = request.activeChapterId ?: return
        val novel = novel ?: return
        if (novel.projectId != request.projectId) {
            toast("任务所属作品与当前作品不一致，无法收敛检查")
            return
        }
        busy = true
        scope.launch {
            try {
                val review = withContext(Dispatchers.IO) {
                    container.convergenceReview.review(
                        novelId = novel.novelId,
                        variantId = request.activeVariantId,
                        chapterId = chapterId,
                        artifactId = artifactId,
                    )
                }
                lastConvergenceReview = review
                val summary = "outcome=${review.outcome.name}; error=${review.errorCount}; " +
                    "warning=${review.warningCount}; ${review.summary}"
                toast("收敛检查完成（commit=${commitId.value}）：$summary")
            } catch (e: Exception) {
                val message = "收敛检查失败：${e.message ?: e.javaClass.simpleName}"
                creationMessages = creationMessages + CreationMessage(CreationMessageRole.ERROR, message)
                toast(message)
            } finally {
                busy = false
                refreshKey++
            }
        }
    }

    /** Agent 任务后续动作：统一 busy 闸、结果保留与 toast。 */
    private fun runAgentFollowUp(block: suspend () -> com.qianyan.model.novelagent.NovelAgentResult) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { block() }
                lastAgentResult = result
                appendAssistantMessage(result)
                toast(agentTaskToast(result))
            } catch (e: Exception) {
                val message = "出错了：${e.message ?: e.javaClass.simpleName}"
                creationMessages = creationMessages + CreationMessage(CreationMessageRole.ERROR, message)
                toast(message)
            } finally {
                busy = false
                refreshKey++
            }
        }
    }

    /** 把 Agent 结果追加到创作会话：摘要作为正文，状态说明作为次级行（不显示内部 ID）。 */
    private fun appendAssistantMessage(result: com.qianyan.model.novelagent.NovelAgentResult) {
        val statusText = agentTaskToast(result)
        val text = result.summary.ifBlank { statusText }
        creationMessages = creationMessages + CreationMessage(
            role = CreationMessageRole.ASSISTANT,
            text = text,
            detail = statusText.takeIf { it != text },
        )
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
    fun <T> io(
        then: (T) -> Unit,
        onFailure: ((Exception) -> Unit)? = null,
        block: suspend () -> T,
    ) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { block() }
                then(result)
            } catch (e: Exception) {
                onFailure?.invoke(e)
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

    /**
     * 作品切换的**唯一状态重置入口**（打开 / 新建 / 重新打开必须复用）。
     *
     * 在同一转换中清除所有与旧作品绑定的**临时 UI 状态**：
     * 创作会话消息、最近任务结果与请求、Workspace Selection / 检查器投影 / 候选、状态栏。
     * **不触碰**任何持久化数据（正文 / 资料 / 历史 / 任务）。
     */
    fun applyNovelSwitch(novel: com.qianyan.model.core.Novel?) {
        this.novel = novel
        workspace.resetForNovelSwitch()
        creationMessages = emptyList()
        creationDraft = ""
        lastAgentResult = null
        lastAgentRequest = null
        lastConvergenceReview = null
        status = StatusInfo()
    }
}

/** 打开作品：建立 Project 作用域后读取 Novel；切换作品时清空上一部作品的创作会话。 */
fun openNovel(state: DesktopAppState, novelId: NovelId) {
    if (state.busy) {
        state.toast("任务进行中，暂时不能切换作品")
        return
    }
    state.io(
        then = { novel ->
            // 读取失败必须显式告知：不得静默 applyNovelSwitch(null) 让用户以为点击无效。
            if (novel == null) {
                state.toast("打开作品失败：作品不存在或已被删除")
            } else {
                state.applyNovelSwitch(novel)
                state.toast("已打开《${novel.title}》")
            }
        },
    ) {
        state.container.projects.openProject(novelId)
        state.container.novels.getNovel(novelId)
    }
}

/**
 * [com.qianyan.model.novelagent.NovelAgentResult] → 用户可见文案（internal 供桌面单测断言）。
 * 五种结局必须可区分（P0-3B §17.2：未桥接候选不得显示成待审批）：
 * 完成摘要原样透出；WAITING_HUMAN = 已进入**正式审批**（以写作页 / 章节进度为准）；
 * CANDIDATE_UNBRIDGED = 候选保留但**未进入**正式审批（显式原因）；失败带类型化 detail；取消如实呈现。
 */
internal fun agentTaskToast(result: com.qianyan.model.novelagent.NovelAgentResult): String = when (result.outcome) {
    com.qianyan.model.novelagent.NovelAgentOutcome.COMPLETED -> result.summary
    com.qianyan.model.novelagent.NovelAgentOutcome.WAITING_HUMAN ->
        "变更已就绪，已进入正式审批——请在「正文」页查看该章节的待确认提案（批准或拒绝）"
    com.qianyan.model.novelagent.NovelAgentOutcome.CANDIDATE_UNBRIDGED ->
        "候选未进入正式审批：${result.failure?.detail ?: "未知原因"}（候选已保留，可稍后重试）"
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
 * 分组小标题（Apple 式 eyebrow：小字、字距放开、中性灰）。
 *
 * 只用留白与字重建立层级，不加横线、不用彩色。
 */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, trailing: (@Composable RowScope.() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = QianyanTheme.palette.text3,
            letterSpacing = 0.6.sp,
            maxLines = 1,
        )
        if (trailing != null) {
            Spacer(Modifier.weight(1f))
            trailing()
        }
    }
}

/** 页面标题行（标题 + 可选说明 + 右侧动作）。用留白分组，不再画整宽横线。 */
@Composable
fun PageHeader(
    title: String,
    meta: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth().padding(start = QianyanDimens.PageX, end = QianyanDimens.PageX, top = 24.dp, bottom = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.displaySmall,
            color = QianyanTheme.palette.text,
            maxLines = 1,
        )
        if (meta != null) {
            Spacer(Modifier.width(12.dp))
            Text(meta, style = QianyanCodeStyle, color = QianyanTheme.palette.text3, maxLines = 1)
        }
        Spacer(Modifier.weight(1f))
        actions()
    }
}

/** 安静的状态标记（胶囊 + 极轻底色）。 */
@Composable
fun Tag(text: String, color: Color = QianyanTheme.palette.text3) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(QianyanShapes.pill))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/**
 * 暖白卡片：白底 + 1px 暖灰边 + 极轻投影（0.5 层），用于故事 / 资料总览。
 *
 * 只是展示容器：不引入任何业务语义；[onClick] 为空时是纯静态卡片。
 */
@Composable
fun UiCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    meta: String? = null,
    tags: List<String> = emptyList(),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    val palette = QianyanTheme.palette
    val shape = RoundedCornerShape(QianyanShapes.r3)
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val interactive = onClick != null
    // Apple Copy：卡片 hover 轻微抬升 + 阴影加深，不改变布局位置
    val lift by animateDpAsState(
        targetValue = if (interactive && hovered) (-1).dp else 0.dp,
        animationSpec = tween(160),
        label = "card-lift",
    )
    val elevation by animateDpAsState(
        targetValue = if (interactive && hovered) 4.dp else 1.dp,
        animationSpec = tween(160),
        label = "card-elevation",
    )
    val borderColor by animateColorAsState(
        targetValue = if (interactive && hovered) palette.lineStrong else palette.line,
        animationSpec = tween(140),
        label = "card-border",
    )
    Column(
        modifier
            .offset(y = lift)
            .shadow(elevation, shape, clip = false)
            .clip(shape)
            .background(palette.raised)
            .border(1.dp, borderColor, shape)
            .then(
                if (interactive) {
                    Modifier.clickable(interactionSource = source, indication = null) { onClick?.invoke() }
                } else {
                    Modifier.hoverable(source)
                },
            )
            .padding(horizontal = 16.dp, vertical = 15.dp),
    ) {
        if (title != null) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = palette.text,
                maxLines = 2,
            )
        }
        if (meta != null) {
            if (title != null) Spacer(Modifier.height(5.dp))
            Text(meta, style = MaterialTheme.typography.labelSmall, color = palette.text3, maxLines = 1)
        }
        content()
        if (tags.isNotEmpty()) {
            Spacer(Modifier.height(11.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                tags.take(3).forEach { Tag(it, palette.text2) }
            }
        }
    }
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
        modifier.fillMaxWidth().background(palette.bg)
            .padding(start = QianyanDimens.PageX, end = QianyanDimens.PageX, top = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        tabs.forEachIndexed { index, label ->
            val on = index == selected
            val source = remember { MutableInteractionSource() }
            val hovered by source.collectIsHoveredAsState()
            // 下划线宽度 = 标签自身宽度（IntrinsicSize.Max），否则 fillMaxWidth 会吞掉整行
            Column(
                Modifier.clickable(interactionSource = source, indication = null) { onSelect(index) }.width(IntrinsicSize.Max),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (on) FontWeight.Medium else FontWeight.Normal,
                    color = if (on || hovered) palette.text else palette.text3,
                    modifier = Modifier.padding(vertical = 9.dp),
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
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val palette = QianyanTheme.palette
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val rowBackground by animateColorAsState(
        targetValue = when {
            selected -> palette.selection
            hovered -> palette.hover
            else -> Color.Transparent
        },
        animationSpec = tween(140),
        label = "list-row-hover",
    )
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding, vertical = 1.dp)
            .clip(RoundedCornerShape(QianyanShapes.r2))
            .background(rowBackground)
            .then(
                if (onClick != null) {
                    Modifier.clickable(interactionSource = source, indication = null) { onClick() }
                } else {
                    Modifier.hoverable(source)
                },
            )
            .padding(horizontal = 9.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (index != null) {
            Text(
                index,
                style = QianyanCodeStyle,
                color = if (selected) palette.accent else palette.text3,
                modifier = Modifier.width(30.dp),
                maxLines = 1,
            )
        }
        Text(
            title.ifBlank { "未命名" },
            style = MaterialTheme.typography.bodyLarge,
            color = if (selected) palette.accent else palette.text,
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
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
}

/* ---------------- 按钮层级（全局只允许四级） ---------------- */

/**
 * 按钮层级：[ActionLevel.PRIMARY]（真正的主要动作，如 保存 / 继续写 / 生成修改）、
 * [ActionLevel.SECONDARY]（普通操作，静默浅灰填充）、[ActionLevel.GHOST]（低频，无底色）、
 * [ActionLevel.TEXT]（菜单 / 辅助）。
 *
 * P0-UI：按钮统一为**胶囊形**（Apple 式胶囊动作），一屏内 PRIMARY 至多一个；
 * 不使用发光 / 渐变 / 高饱和填充；次级操作改用浅灰填充而非再加一道边框。
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
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val textColor = when {
        !enabled -> palette.text3.copy(alpha = 0.55f)
        level == ActionLevel.PRIMARY -> palette.onAccent
        level == ActionLevel.SECONDARY -> palette.text
        level == ActionLevel.GHOST -> palette.text2
        else -> palette.text3
    }
    val shape = RoundedCornerShape(QianyanShapes.pill)
    val baseColor = when {
        !enabled -> Color.Transparent
        level == ActionLevel.PRIMARY -> palette.accent
        level == ActionLevel.SECONDARY -> palette.soft
        else -> Color.Transparent
    }
    val hoverColor = when {
        !enabled -> Color.Transparent
        level == ActionLevel.PRIMARY -> androidx.compose.ui.graphics.lerp(palette.accent, Color.Black, 0.08f)
        else -> palette.hover
    }
    val background by androidx.compose.animation.animateColorAsState(
        targetValue = if (hovered) hoverColor else baseColor,
        animationSpec = androidx.compose.animation.core.tween(140),
        label = "action-hover",
    )
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = if (level == ActionLevel.PRIMARY) FontWeight.Medium else FontWeight.Normal,
        color = textColor,
        maxLines = 1,
        modifier = Modifier.clip(shape)
            .background(background)
            .then(
                if (enabled) {
                    Modifier.clickable(interactionSource = source, indication = null) { onClick() }
                } else {
                    Modifier
                },
            )
            .padding(
                horizontal = when (level) {
                    ActionLevel.PRIMARY -> 16.dp
                    ActionLevel.SECONDARY -> 13.dp
                    else -> 6.dp
                },
                vertical = when (level) {
                    ActionLevel.PRIMARY -> 7.dp
                    ActionLevel.SECONDARY -> 6.dp
                    else -> 4.dp
                },
            ),
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
            .background(palette.soft)
            .border(1.dp, if (focused) palette.accent else Color.Transparent, shape)
            .padding(horizontal = 12.dp, vertical = 9.dp),
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
                .then(if (singleLine) Modifier.height(minHeight - 18.dp) else Modifier)
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
/** T-8-E · 收敛结果四态文案：失败态单独成句，不复用"一致"措辞。 */
fun convergenceOutcomeLabel(outcome: ConvergenceOutcome): String = when (outcome) {
    ConvergenceOutcome.CONSISTENT -> "人物一致（无问题）"
    ConvergenceOutcome.ISSUES_FOUND -> "发现人物一致性问题"
    ConvergenceOutcome.INSUFFICIENT_EVIDENCE -> "证据不足，无法完成检查"
    ConvergenceOutcome.REVIEW_FAILED -> "复审失败，未完成检查"
}

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
