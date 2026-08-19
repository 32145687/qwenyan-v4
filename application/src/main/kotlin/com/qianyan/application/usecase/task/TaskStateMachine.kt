package com.qianyan.application.usecase.task

import com.qianyan.model.task.TaskStatus

/**
 * Task 生命周期操作（P8.2）。
 *
 * 与 [TaskStatus] 配合构成 Task 状态机；操作是 Application 层概念，不进入 core:model。
 * 每个操作对应 [TaskManagerUseCases] 的一个方法（start / pause / resume / cancel / complete / fail）。
 */
enum class TaskOperation { START, PAUSE, RESUME, CANCEL, COMPLETE, FAIL }

/**
 * Task 状态机（P8.2，确定性纯函数，无状态）。
 *
 * 冻结转换表（P8.2 Preflight）：
 * ```
 * PENDING ──start──▶ RUNNING ──pause──▶ PAUSED ──resume──▶ RUNNING
 *    │                 │  ├──complete──▶ COMPLETED（终态）
 *    │                 │  ├──fail──────▶ FAILED（终态）
 *    └──cancel──▶ CANCELLED ┘  └──cancel──▶ CANCELLED（终态）
 * PAUSED ──complete──▶ COMPLETED
 * PAUSED ──cancel──▶ CANCELLED
 * ```
 * 明确禁止：PENDING→PAUSED/COMPLETED/FAILED；RUNNING/PAUSED→PENDING；
 * FAILED→RUNNING/PENDING；COMPLETED→*；CANCELLED→*。
 * 失败自动 retry 属后续 Workflow 层（DEFER），本状态机不提供 FAILED→RUNNING。
 *
 * @return 目标状态；null 表示非法转换。
 */
object TaskStateMachine {

    /** 依据当前状态与操作返回目标状态；非法转换返回 null。 */
    fun transition(from: TaskStatus, op: TaskOperation): TaskStatus? = when (op) {
        TaskOperation.START -> if (from == TaskStatus.PENDING) TaskStatus.RUNNING else null
        TaskOperation.PAUSE -> if (from == TaskStatus.RUNNING) TaskStatus.PAUSED else null
        TaskOperation.RESUME -> if (from == TaskStatus.PAUSED) TaskStatus.RUNNING else null
        TaskOperation.CANCEL ->
            if (from == TaskStatus.PENDING || from == TaskStatus.RUNNING || from == TaskStatus.PAUSED) {
                TaskStatus.CANCELLED
            } else {
                null
            }
        TaskOperation.COMPLETE ->
            if (from == TaskStatus.RUNNING || from == TaskStatus.PAUSED) TaskStatus.COMPLETED else null
        TaskOperation.FAIL -> if (from == TaskStatus.RUNNING) TaskStatus.FAILED else null
    }
}
