package com.qianyan.application.usecase.taskqueue

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.qianyan.application.di.ApplicationContainer
import com.qianyan.application.usecase.taskqueue.TaskQueueUseCases.Companion.DEFAULT_RETRY_BACKOFF_MILLIS
import com.qianyan.model.ProjectId
import com.qianyan.model.TaskId
import com.qianyan.model.task.TaskType
import com.qianyan.model.taskqueue.TaskKind
import com.qianyan.model.taskqueue.TaskPriority
import com.qianyan.model.taskqueue.TaskQueueItem
import com.qianyan.provider.impl.MockLLMGateway
import com.qianyan.storage.db.QianyanDbFactory
import com.qianyan.storage.db.QianyanDbHandle
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/*
 * I13 测试夹具（真实内存 SQLite + ApplicationContainer；数据全真实）。
 *
 * 用**可推进的固定时钟**驱动队列：enqueue / lease / retry 退避全部确定性可测
 * （不依赖真实系统时间，不依赖 Map 迭代序 / random）。
 */

/** 固定起点（epoch 毫秒精度，与存储一致）。 */
internal val TASK_QUEUE_FIXED_INSTANT: Instant = Instant.parse("2026-03-01T00:00:00Z")

/** 可推进的测试时钟（确定性）。 */
internal class MutableClock(private var current: Instant = TASK_QUEUE_FIXED_INSTANT) : Clock {
    override fun now(): Instant = current

    fun advanceBy(millis: Long) {
        current = Instant.fromEpochMilliseconds(current.toEpochMilliseconds() + millis)
    }
}

internal class TaskQueueFixture(
    val app: ApplicationContainer,
    val handle: QianyanDbHandle,
    val clock: MutableClock,
    /** 具可控时钟 / 可配置退避的队列实例（与容器装配的 `taskQueue` 同构）。 */
    val queue: TaskQueueUseCases,
) {
    /** 新建一个真实 Project（Novel + ProjectState）。 */
    fun newProject(title: String = "队列之书"): ProjectId =
        app.projects.createProject(title = title).projectId

    /** 新建一个真实后台 Task（PENDING）。 */
    fun newTask(type: TaskType = TaskType.BACKGROUND): TaskId = app.tasks.create(type)

    /** 常用组合：新建 Task + 入队。 */
    fun enqueue(
        projectId: ProjectId,
        kind: TaskKind = TaskKind.PROJECT_INDEX_REBUILD,
        priority: TaskPriority = TaskPriority.NORMAL,
        maxAttempts: Int = TaskQueueItem.DEFAULT_MAX_ATTEMPTS,
    ): TaskQueueItem = queue.enqueue(newTask(), projectId, kind, priority, maxAttempts)

    fun close() = handle.driver.close()
}

internal fun taskQueueFixture(retryBackoffMillis: Long = DEFAULT_RETRY_BACKOFF_MILLIS): TaskQueueFixture {
    val handle = QianyanDbFactory.open(JdbcSqliteDriver.IN_MEMORY)
    val app = ApplicationContainer.fromDriver(handle.driver, MockLLMGateway())
    val clock = MutableClock()
    val queue = TaskQueueUseCases(
        queue = app.taskQueueRepository,
        taskManager = app.tasks,
        clock = clock,
        retryBackoffMillis = retryBackoffMillis,
        errorMapper = app.errorMapper,
    )
    return TaskQueueFixture(app, handle, clock, queue)
}

/** 行数统计（Canonical 隔离断言；不改动任何数据）。 */
internal fun countRows(handle: QianyanDbHandle, table: String): Long =
    handle.driver.executeQuery(
        null,
        "SELECT COUNT(*) FROM $table",
        { cursor ->
            cursor.next()
            QueryResult.Value(cursor.getLong(0) ?: 0L)
        },
        0,
    ).value