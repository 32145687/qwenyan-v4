package com.qianyan.app.desktop.ui.workspace

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.qianyan.app.desktop.ui.DesktopAppState
import com.qianyan.application.error.ApplicationException
import com.qianyan.model.workspace.InspectorTab
import com.qianyan.model.workspace.WorkspaceErrorCodes
import com.qianyan.model.workspace.WorkspaceInspectorView
import com.qianyan.model.workspace.WorkspaceObjectCandidate
import com.qianyan.model.workspace.WorkspaceObjectKind
import com.qianyan.model.workspace.WorkspaceSelection

/**
 * I14-UI · Workspace 控制器（**UI 会话态**；不落库、不写 Canonical）。
 *
 * 职责：持有"当前对象"（[WorkspaceSelection]）与 Inspector 标签（[tab]），
 * 经既有 I14 `WorkspaceUseCases`（Application 薄 Facade）读取**只读投影**。
 *
 * LOCKED 行为：
 *  - **Selection 变化** ⇒ 三个 Tab 数据重新读取 + [tab] 重置为「上下文」（§14）；
 *  - **对象已不存在** ⇒ 清除 Selection + Inspector 进入统一空状态（§15）；
 *  - 面板折叠 / 专注模式 / Command Palette 开关在 [DesktopAppState]（纯 UI）。
 *
 * 不直接访问 Repository / SqlDriver / SQLDelight；不含任何业务判定。
 */
class WorkspaceController(private val app: DesktopAppState) {

    /** 当前工作对象（整个 Workspace 唯一的选择，§7）。 */
    var selection by mutableStateOf<WorkspaceSelection?>(null)
        private set

    /** Inspector 当前标签（**固定三个**：上下文 / 活动 / 修改，§8）。 */
    var tab by mutableStateOf(InspectorTab.CONTEXT)

    /** 最近一次 Inspector 读取结果（只读投影）。 */
    var view by mutableStateOf<WorkspaceInspectorView?>(null)
        private set

    /** 可选对象列表（来源：既有 Canonical / I9 / I10 / I12 / I13）。 */
    var candidates by mutableStateOf<List<WorkspaceObjectCandidate>>(emptyList())
        private set

    var loading by mutableStateOf(false)
        private set

    /** 读取失败原因（对象不存在时会被清空为统一空状态）。 */
    var message by mutableStateOf<String?>(null)
        private set

    /** ↑/↓ 列表导航：当前索引（由当前页面注册的列表长度决定）。 */
    var listIndex by mutableStateOf(0)

    private var listSize: Int = 0
    private var openAt: ((Int) -> Unit)? = null

    val projectId get() = app.projectId

    /** 页面注册可键盘导航的列表（章节 / 人物 / 任务 / 历史 / 候选）。 */
    fun registerList(size: Int, onOpen: (Int) -> Unit) {
        listSize = size
        openAt = onOpen
        if (listIndex >= size) listIndex = 0
    }

    fun clearList() {
        listSize = 0
        openAt = null
        listIndex = 0
    }

    /** ↑/↓：列表上下移动（非空时循环）。 */
    fun move(delta: Int) {
        if (listSize <= 0) return
        listIndex = ((listIndex + delta) % listSize + listSize) % listSize
    }

    /** Enter：打开当前对象（当前页面的列表动作）。 */
    fun openCurrent() {
        if (listSize > 0) openAt?.invoke(listIndex)
    }

    /**
     * 设置当前对象：**Selection 变化即重置 Inspector 到「上下文」**（§14）。
     */
    fun select(selection: WorkspaceSelection) {
        if (this.selection != selection) {
            this.selection = selection
            this.tab = InspectorTab.CONTEXT
            this.view = null
            this.message = null
            this.listIndex = 0
        }
    }

    fun openInspector(tab: InspectorTab) {
        this.tab = tab
        app.inspectorOpen = true
    }

    /** 清除当前对象（对象不存在时使用）。 */
    fun clearSelection() {
        selection = null
        view = null
        message = null
    }

    /** UI 默认落点：ProjectState 的当前章节 → 否则 Project。 */
    private fun defaultSelection(
        projectId: com.qianyan.model.ProjectId,
        options: List<WorkspaceObjectCandidate>,
    ): WorkspaceSelection? {
        val activeChapter = runCatching { app.container.projects.state(projectId)?.activeChapterId }.getOrNull()
        val chapter = activeChapter?.let { id ->
            options.firstOrNull { it.selection.chapterId == id && it.selection.kind == WorkspaceObjectKind.CHAPTER }
        }
        return chapter?.selection
            ?: options.firstOrNull { it.selection.kind == WorkspaceObjectKind.PROJECT }?.selection
    }

    /** 重新读取一次（作品变化 / 数据变化 / Selection 变化时调用）。 */
    suspend fun load() {
        val projectId = projectId
        if (projectId == null) {
            clearSelection()
            candidates = emptyList()
            return
        }
        loading = true
        try {
            val options = app.load { it.workspace.candidates(projectId) }
            candidates = options

            // UI 默认落点（仅当尚未选择时）：优先 ProjectState.activeChapterId 的章节，否则 Project 自身。
            // 这是**UI 初始焦点**，不是"猜测父对象"，也不写入任何状态。
            val current = selection?.takeIf { it.projectId == projectId }
                ?: defaultSelection(projectId, options)
            if (current == null) {
                view = null
                message = null
                return
            }
            if (selection != current) selection = current
            message = null
            view = app.load { it.workspace.inspector(projectId, current) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 协程被取消（页面切换 / 快速重读）不是错误：必须继续抛出，不能变成用户可见的失败。
            throw e
        } catch (e: ApplicationException) {
            val detail = e.message.orEmpty()
            if (detail.contains(WorkspaceErrorCodes.OBJECT_NOT_FOUND) || detail.contains(WorkspaceErrorCodes.PROJECT_NOT_FOUND)) {
                // LOCKED §15：对象已不存在 ⇒ 清除 Selection + 统一空状态（不保留旧数据、不猜测父对象）
                clearSelection()
            } else {
                view = null
                message = detail
            }
        } catch (e: Exception) {
            view = null
            message = e.message ?: e.javaClass.simpleName
        } finally {
            loading = false
        }
    }
}