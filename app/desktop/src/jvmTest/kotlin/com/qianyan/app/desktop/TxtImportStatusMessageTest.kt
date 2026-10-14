package com.qianyan.app.desktop

import com.qianyan.app.desktop.ui.write.messageFor
import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.model.TxtDocumentId
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * U2 · Desktop 用户可见状态：TXT 导入失败与"已导入但 Canonical 初始化未完成"必须可区分，
 * F2 冲突原因必须类型化透出（不得统一成模糊的"操作失败/出错了"）。
 * 文案经桌面统一的 [messageFor]（WriterController，internal）断言。
 */
class TxtImportStatusMessageTest {

    @Test
    fun `init incomplete message names the import fact the concrete cause`() {
        val msg = messageFor(
            ApplicationException(
                ApplicationError.TxtCanonicalInitIncomplete(
                    TxtDocumentId("doc-1"),
                    ApplicationError.ChapterSourceConflict("Novel n 已有 1 个不属于目标集合的章节"),
                ),
            ),
        )
        assertTrue(msg.contains("TXT 已导入"), "必须先说明 TXT 已导入：$msg")
        assertTrue(msg.contains("章节初始化未完成"), msg)
        assertTrue(msg.contains("其他来源的章节"), "F2 冲突必须显示具体原因：$msg")
    }

    @Test
    fun `init incomplete with storage cause stays distinguishable from import failure`() {
        val msg = messageFor(
            ApplicationException(
                ApplicationError.TxtCanonicalInitIncomplete(
                    TxtDocumentId("doc-1"),
                    ApplicationError.UnknownStorage(RuntimeException("boom")),
                ),
            ),
        )
        assertTrue(msg.contains("TXT 已导入"), msg)
        assertTrue(msg.contains("存储错误"), msg)
    }

    @Test
    fun `plain import failure does not claim the file was imported`() {
        val msg = messageFor(ApplicationException(ApplicationError.EmptyDocument("文件内容为空")))
        assertFalse(msg.contains("TXT 已导入"), "导入失败不得声称已导入：$msg")
    }
}
