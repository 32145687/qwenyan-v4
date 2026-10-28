package com.qianyan.application.usecase.workspace

import com.qianyan.model.workspace.InspectorTaskContextState
import com.qianyan.model.workspace.WorkspaceSelection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 4B.1a · Inspector 任务上下文四状态占位 + 当前事实独立只读。
 *
 * 明确边界：
 *  - 4B.1a 不提供真实 ContextPack；状态 [InspectorTaskContextState.PACK_AVAILABLE] 只能是枚举占位；
 *  - Inspector 任务上下文路径不得调用 ContextEngineUseCases.build；
 *  - 当前事实来自既有 read API，必须与任务上下文分开。
 */
class WorkspaceInspectorContextTest {

    @Test
    fun `no task selection shows no task context and keeps current facts separate`() {
        val f = workspaceFixture()
        val w = seedWorkspaceWorld(f)

        val view = f.app.workspace.inspector(w.projectId, WorkspaceSelection.project(w.projectId))
        val taskContext = assertNotNull(view.taskContext)

        assertEquals(InspectorTaskContextState.NO_TASK, taskContext.state)
        assertEquals("暂无任务上下文", taskContext.message)
        assertNull(view.context, "4B.1a 不得提供重建的 ContextPack")
        assertTrue(
            view.currentFacts.any { it.label == "作品" && it.value == "长夜行舟" },
            "当前事实应来自既有 Novel 读取：${view.currentFacts}",
        )
        f.close()
    }

    @Test
    fun `existing task without a real pack shows unavailable state`() {
        val f = workspaceFixture()
        val w = seedWorkspaceWorld(f)

        val view = f.app.workspace.inspector(w.projectId, WorkspaceSelection.task(w.projectId, w.taskId))
        val taskContext = assertNotNull(view.taskContext)

        assertEquals(InspectorTaskContextState.PACK_UNAVAILABLE, taskContext.state)
        assertEquals("上次任务上下文已失效", taskContext.message)
        assertEquals(w.taskId, taskContext.taskId)
        assertEquals(w.sessionId, taskContext.sessionId)
        assertNull(view.context)
        f.close()
    }

    @Test
    fun `running task without a real pack shows running without pack state`() {
        val f = workspaceFixture()
        val w = seedWorkspaceWorld(f)
        f.app.tasks.start(w.taskId)

        val view = f.app.workspace.inspector(w.projectId, WorkspaceSelection.task(w.projectId, w.taskId))
        val taskContext = assertNotNull(view.taskContext)

        assertEquals(InspectorTaskContextState.RUNNING_WITHOUT_PACK, taskContext.state)
        assertEquals("当前任务尚未生成上下文", taskContext.message)
        assertEquals(w.taskId, taskContext.taskId)
        assertNull(view.context)
        f.close()
    }

    @Test
    fun `real pack state is defined but never fabricated by 4B1a`() {
        val f = workspaceFixture()
        val w = seedWorkspaceWorld(f)

        val states = listOf(
            WorkspaceSelection.project(w.projectId),
            WorkspaceSelection.chapter(w.projectId, w.novelId, w.chapter1),
            WorkspaceSelection.task(w.projectId, w.taskId),
            WorkspaceSelection.change(w.projectId, w.artifactId),
        ).map { selection ->
            assertNotNull(f.app.workspace.inspector(w.projectId, selection).taskContext).state
        }

        assertTrue(InspectorTaskContextState.PACK_AVAILABLE in InspectorTaskContextState.values())
        assertFalse(states.contains(InspectorTaskContextState.PACK_AVAILABLE), "4B.1a 不得伪造真实 Pack 状态")
        f.close()
    }

    @Test
    fun `current facts are read only projections and stay separate from task context`() {
        val f = workspaceFixture()
        val w = seedWorkspaceWorld(f)

        val chapter = f.app.workspace.inspector(
            w.projectId,
            WorkspaceSelection.chapter(w.projectId, w.novelId, w.chapter1),
        )
        val chapterTaskContext = assertNotNull(chapter.taskContext)
        assertEquals(InspectorTaskContextState.NO_TASK, chapterTaskContext.state)
        assertNull(chapter.context)
        assertTrue(chapter.currentFacts.any { it.label == "作品" }, chapter.currentFacts.toString())
        assertTrue(
            chapter.currentFacts.any { it.label == "章节" && it.value.contains("第 1 章") },
            chapter.currentFacts.toString(),
        )

        val character = f.app.workspace.inspector(
            w.projectId,
            WorkspaceSelection.character(w.projectId, w.novelId, w.characterId),
        )
        assertTrue(
            character.currentFacts.any { it.label == "人物" && it.value == "沈昭" },
            character.currentFacts.toString(),
        )
        f.close()
    }

    @Test
    fun `empty project still yields valid current facts without a pack`() {
        val f = workspaceFixture()
        val bare = f.app.projects.createProject(title = "空书")

        val view = f.app.workspace.inspector(bare.projectId, WorkspaceSelection.project(bare.projectId))

        assertEquals(InspectorTaskContextState.NO_TASK, assertNotNull(view.taskContext).state)
        assertTrue(view.currentFacts.any { it.label == "作品" && it.value == "空书" })
        assertNull(view.context)
        f.close()
    }
}
