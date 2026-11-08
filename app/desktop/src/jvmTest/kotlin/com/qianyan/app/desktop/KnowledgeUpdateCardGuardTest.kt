package com.qianyan.app.desktop

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** O-08：「知识更新」卡片接线守卫（源码级；不提供手动触发）。 */
class KnowledgeUpdateCardGuardTest {

    private val page = File("src/jvmMain/kotlin/com/qianyan/app/desktop/ui/pages/WritePage.kt").readText()

    @Test
    fun `stale knowledge-update placeholder is gone`() {
        assertFalse("知识更新 · 未接入" in page, "过期占位必须零残留")
        assertFalse("knowledgeUpdate.execute" in page, "桌面 UI 不得直接调用 KU 执行入口")
    }

    @Test
    fun `card surfaces memory results and states automatic execution`() {
        assertTrue("KnowledgeUpdateCard(" in page, "正文页必须渲染知识更新卡片")
        assertTrue("审批通过后自动执行，无需手动触发" in page, "必须说明自动执行、非手动")
        assertTrue("writingLayerSummary" in page, "卡片数据源必须来自按作品/层的记忆摘要")
        assertTrue("已沉淀" in page, "必须展示沉淀条数")
        assertTrue("最近更新" in page, "必须展示最近更新时间")
        assertFalse("MenuEntry(\"知识更新" in page, "不得新增手动触发菜单项")
    }

    @Test
    fun `card shows latest knowledge-update status from persisted checkpoints`() {
        assertTrue("latestForNovel" in page, "状态必须来自按作品的 KU 记录查询")
        assertTrue("最近一次：" in page, "必须展示最近一次 KU 状态")
        assertTrue("采纳 " in page && "拒绝 " in page, "必须展示采纳/拒绝数")
        assertTrue("失败原因：" in page, "FAILED 必须附任务错误摘要")
    }
}
