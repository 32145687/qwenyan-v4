package com.qianyan.app.desktop

import com.qianyan.app.desktop.ui.write.WriterUiState
import com.qianyan.model.workflow.HumanDecision
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * O-90（T-O90-F）：拒绝后文案误导——桌面状态投影新增 `rejectedRound`。
 *
 * 真值表（ChapterPhase.WAITING_CONFIRMATION 投影语义不动，纯派生属性）：
 *  - waitingForUser=true  + pendingGateDecision=PENDING   → rejectedRound=false（仍在待审）
 *  - waitingForUser=false + pendingGateDecision=REJECTED  → rejectedRound=true（上一轮已拒绝）
 *  - waitingForUser=false + pendingGateDecision=APPROVED/null → rejectedRound=false
 */
class WriterRejectedRoundTest {

    private val base = WriterUiState(isLoaded = true)

    @Test
    fun `pending gate waiting for user is not a rejected round`() {
        val pending = base.copy(
            waitingForUser = true,
            pendingGateDecision = HumanDecision.PENDING,
        )
        assertFalse(pending.rejectedRound, "待审中的 PENDING 决议不得判定为已拒绝轮次")
    }

    @Test
    fun `rejected decision after gate closes marks rejected round`() {
        val rejected = base.copy(
            waitingForUser = false,
            pendingGateDecision = HumanDecision.REJECTED,
        )
        assertTrue(rejected.rejectedRound, "Gate 已关闭且决议为 REJECTED → 上一轮已拒绝")
    }

    @Test
    fun `approved or null decision is not a rejected round`() {
        val approved = base.copy(
            waitingForUser = false,
            pendingGateDecision = HumanDecision.APPROVED,
        )
        assertFalse(approved.rejectedRound, "已批准不属于拒绝轮次")

        val noDecision = base.copy(
            waitingForUser = false,
            pendingGateDecision = null,
        )
        assertFalse(noDecision.rejectedRound, "无 Gate 决议（null）不属于拒绝轮次")
    }

    @Test
    fun `not loaded state never reports rejected round`() {
        val unloaded = WriterUiState(
            isLoaded = false,
            waitingForUser = false,
            pendingGateDecision = HumanDecision.REJECTED,
        )
        assertFalse(unloaded.rejectedRound, "未加载完成不得显示拒绝提示")
    }

    @Test
    fun `request revision decision is not rejected`() {
        val requestRevision = base.copy(
            waitingForUser = false,
            pendingGateDecision = HumanDecision.REQUEST_REVISION,
        )
        assertFalse(requestRevision.rejectedRound, "REQUEST_REVISION ≠ REJECTED，不判定为拒绝轮次")
    }
}
