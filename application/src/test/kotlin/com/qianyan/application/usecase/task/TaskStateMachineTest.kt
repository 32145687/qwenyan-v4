package com.qianyan.application.usecase.task

import com.qianyan.model.task.TaskStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * P8.2 Task 状态机纯逻辑测试（确定性，无 DB / 无 IO）。
 *
 * 覆盖冻结转换表全部合法与非法路径，以及三个终态拒绝一切操作。
 */
class TaskStateMachineTest {

    private val allOperations = TaskOperation.entries
    private val allStatuses = TaskStatus.entries

    /* 合法转换：冻结表（P8.2 Preflight）中的 9 条 */
    @Test
    fun `all legal transitions return expected target`() {
        val legal = listOf(
            Triple(TaskStatus.PENDING, TaskOperation.START, TaskStatus.RUNNING),
            Triple(TaskStatus.PENDING, TaskOperation.CANCEL, TaskStatus.CANCELLED),
            Triple(TaskStatus.RUNNING, TaskOperation.PAUSE, TaskStatus.PAUSED),
            Triple(TaskStatus.RUNNING, TaskOperation.COMPLETE, TaskStatus.COMPLETED),
            Triple(TaskStatus.RUNNING, TaskOperation.FAIL, TaskStatus.FAILED),
            Triple(TaskStatus.RUNNING, TaskOperation.CANCEL, TaskStatus.CANCELLED),
            Triple(TaskStatus.PAUSED, TaskOperation.RESUME, TaskStatus.RUNNING),
            Triple(TaskStatus.PAUSED, TaskOperation.COMPLETE, TaskStatus.COMPLETED),
            Triple(TaskStatus.PAUSED, TaskOperation.CANCEL, TaskStatus.CANCELLED),
        )
        legal.forEach { (from, op, target) ->
            assertEquals(target, TaskStateMachine.transition(from, op), "应允许 $from --$op--> $target")
        }
    }

    /* 非法转换：合法集合之外的任何 (status, op) 一律拒绝 */
    @Test
    fun `every transition outside the frozen table is rejected`() {
        val legal = setOf(
            "PENDING-START", "PENDING-CANCEL",
            "RUNNING-PAUSE", "RUNNING-COMPLETE", "RUNNING-FAIL", "RUNNING-CANCEL",
            "PAUSED-RESUME", "PAUSED-COMPLETE", "PAUSED-CANCEL",
        )
        for (from in allStatuses) {
            for (op in allOperations) {
                val key = "$from-$op"
                if (key in legal) continue
                assertNull(TaskStateMachine.transition(from, op), "应拒绝 $from --$op--> ?")
            }
        }
    }

    /* 明确禁止的组合，逐一断言（对应 P8.2 禁止清单） */
    @Test
    fun `explicitly forbidden transitions are rejected`() {
        val forbidden = listOf(
            Triple(TaskStatus.PENDING, TaskOperation.PAUSE, "PENDING→PAUSED"),
            Triple(TaskStatus.PENDING, TaskOperation.COMPLETE, "PENDING→COMPLETED"),
            Triple(TaskStatus.PENDING, TaskOperation.FAIL, "PENDING→FAILED"),
            Triple(TaskStatus.RUNNING, TaskOperation.START, "RUNNING→RUNNING"),
            Triple(TaskStatus.RUNNING, TaskOperation.RESUME, "RUNNING→RUNNING"),
            Triple(TaskStatus.PAUSED, TaskOperation.START, "PAUSED→RUNNING"),
            Triple(TaskStatus.PAUSED, TaskOperation.PAUSE, "PAUSED→PAUSED"),
            Triple(TaskStatus.PAUSED, TaskOperation.FAIL, "PAUSED→FAILED"),
        )
        forbidden.forEach { (from, op, label) ->
            assertNull(TaskStateMachine.transition(from, op), "应禁止 $label")
        }
    }

    /* 终态：COMPLETED / CANCELLED / FAILED 拒绝一切操作（无 retry） */
    @Test
    fun `terminal states reject every operation`() {
        for (terminal in listOf(TaskStatus.COMPLETED, TaskStatus.CANCELLED, TaskStatus.FAILED)) {
            for (op in allOperations) {
                assertNull(TaskStateMachine.transition(terminal, op), "终态 $terminal 应拒绝 $op")
            }
        }
    }
}
