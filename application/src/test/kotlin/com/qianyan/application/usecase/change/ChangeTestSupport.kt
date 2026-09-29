package com.qianyan.application.usecase.change

import com.qianyan.application.usecase.draft.FIXED_INSTANT
import com.qianyan.application.usecase.draft.WorkingDraftFixture
import com.qianyan.application.usecase.draft.WorkingDraftUseCases
import com.qianyan.application.usecase.draft.validatorOf
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/**
 * I9 测试辅助：固定时钟的 Change Review（复用 I8 测试夹具与同一确定性 Validator）。
 *
 * [workspaces] 必须是**同一个** Working Draft 工作区实例（Change Review 只读它）。
 */
internal fun fixedClockChanges(
    f: WorkingDraftFixture,
    workspaces: WorkingDraftUseCases,
    instant: Instant = FIXED_INSTANT,
): ChangeUseCases = ChangeUseCases(
    workingDrafts = workspaces,
    validator = validatorOf(f),
    drafts = f.app.writerUseCases,
    clock = object : Clock {
        override fun now(): Instant = instant
    },
    errorMapper = f.app.errorMapper,
)