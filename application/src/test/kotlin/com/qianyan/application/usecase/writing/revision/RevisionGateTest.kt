package com.qianyan.application.usecase.writing.revision

import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.model.TaskId
import com.qianyan.model.task.Task
import com.qianyan.model.task.TaskType
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * P11.4 Revision Gate 单元测试（确定性业务规则，不依赖 LLM / DB）。
 *
 * 验证：revisionCount < 3 允许；>= 3 类型化拒绝。纯函数，可离线证明。
 * 复用 P8 revision 计数语义，不新增第二套 counter / limit。
 */
class RevisionGateTest {

    private fun taskWith(count: Int): Task = Task(
        taskId = TaskId("t-$count"),
        type = TaskType.WRITING,
        revisionCount = count,
        createdAt = Instant.fromEpochMilliseconds(0),
        updatedAt = Instant.fromEpochMilliseconds(0),
    )

    @Test
    fun `revision count 0 is allowed`() {
        val task = taskWith(0)
        assertTrue(RevisionGate.isAllowed(task))
    }

    @Test
    fun `revision count 1 is allowed`() {
        assertTrue(RevisionGate.isAllowed(taskWith(1)))
    }

    @Test
    fun `revision count 2 is allowed`() {
        assertTrue(RevisionGate.isAllowed(taskWith(2)))
    }

    @Test
    fun `revision count 3 is rejected`() {
        val task = taskWith(3)
        assertFalse(RevisionGate.isAllowed(task))
        val ex = assertFailsWith<ApplicationException> { RevisionGate.requireAllowed(task) }
        assertIs<ApplicationError.RevisionNotAllowed>(ex.error)
    }

    /* 超上限也拒绝（即使值非法，规则仍保持 <=3 单调拒绝，不产生意外分支） */
    @Test
    fun `revision count above max is rejected`() {
        assertFalse(RevisionGate.isAllowed(taskWith(4)))
        val ex = assertFailsWith<ApplicationException> { RevisionGate.requireAllowed(taskWith(9)) }
        assertIs<ApplicationError.RevisionNotAllowed>(ex.error)
    }
}