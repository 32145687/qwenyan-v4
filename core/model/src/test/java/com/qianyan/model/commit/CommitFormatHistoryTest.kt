package com.qianyan.model.commit

import com.qianyan.model.writing.DraftFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CommitFormatHistory 值域与存储编解码契约（Phase 1 · revert 格式契约）。
 *
 * 核心不变量：**UNKNOWN 永远不能被静默转换成 legacy 的 `null`**——
 * 存储侧编解码必须保持 UNKNOWN（NULL），转换侧必须显式抛出。
 */
class CommitFormatHistoryTest {

    /* ---------------- 存储编解码：UNKNOWN ⇔ SQL NULL，绝不落 KNOWN_LEGACY ---------------- */

    @Test
    fun `storage null decodes to unknown never to known legacy`() {
        assertEquals(CommitFormatHistory.UNKNOWN, CommitFormatHistory.fromStorage(null), "SQL NULL = 未知，不得解释为 legacy")
        assertEquals(CommitFormatHistory.UNKNOWN, CommitFormatHistory.fromStorage("some-future-format"), "未识别字符串 = 未知")
        assertEquals(CommitFormatHistory.KNOWN_LEGACY, CommitFormatHistory.fromStorage("legacy"))
        assertEquals(CommitFormatHistory.KNOWN_CONTROLLED_MARKDOWN, CommitFormatHistory.fromStorage(DraftFormat.CONTROLLED_MARKDOWN))
        assertEquals(CommitFormatHistory.NONE, CommitFormatHistory.fromStorage("none"))
    }

    @Test
    fun `unknown encodes to storage null and round-trips back to unknown`() {
        assertNull(CommitFormatHistory.toStorage(CommitFormatHistory.UNKNOWN), "UNKNOWN 必须存为 SQL NULL")
        assertEquals(
            CommitFormatHistory.UNKNOWN,
            CommitFormatHistory.fromStorage(CommitFormatHistory.toStorage(CommitFormatHistory.UNKNOWN)),
            "UNKNOWN 存储往返后仍是 UNKNOWN",
        )
        assertEquals("legacy", CommitFormatHistory.toStorage(CommitFormatHistory.KNOWN_LEGACY))
        assertEquals(DraftFormat.CONTROLLED_MARKDOWN, CommitFormatHistory.toStorage(CommitFormatHistory.KNOWN_CONTROLLED_MARKDOWN))
        assertEquals("none", CommitFormatHistory.toStorage(CommitFormatHistory.NONE))
    }

    /* ---------------- Draft.format 转换：UNKNOWN 显式抛出，不得当 legacy ---------------- */

    @Test
    fun `toDraftFormat refuses unknown instead of returning legacy null`() {
        val ex = assertFailsWith<IllegalStateException> {
            CommitFormatHistory.toDraftFormat(CommitFormatHistory.UNKNOWN)
        }
        assertEquals(
            CommitFormatHistory.UNKNOWN,
            CommitFormatHistory.fromStorage(CommitFormatHistory.toStorage(CommitFormatHistory.UNKNOWN)),
            "拒绝路径不产生任何可被误读为 legacy 的值",
        )
        assertTrue(ex.message!!.contains("UNKNOWN"), "错误信息必须点名 UNKNOWN，而非 legacy：${ex.message}")
    }

    @Test
    fun `toDraftFormat maps known values without conflation`() {
        assertNull(CommitFormatHistory.toDraftFormat(CommitFormatHistory.KNOWN_LEGACY), "明确 legacy ⇒ Draft.format = null")
        assertNull(CommitFormatHistory.toDraftFormat(CommitFormatHistory.NONE), "无版本 ⇒ 恢复目标语义为 legacy（首个 COMMIT 冻结决策）")
        assertEquals(
            DraftFormat.CONTROLLED_MARKDOWN,
            CommitFormatHistory.toDraftFormat(CommitFormatHistory.KNOWN_CONTROLLED_MARKDOWN),
        )
    }

    @Test
    fun `ofDraftFormat keeps unknown formats unknown`() {
        assertEquals(CommitFormatHistory.KNOWN_LEGACY, CommitFormatHistory.ofDraftFormat(null))
        assertEquals(CommitFormatHistory.KNOWN_CONTROLLED_MARKDOWN, CommitFormatHistory.ofDraftFormat(DraftFormat.CONTROLLED_MARKDOWN))
        assertEquals(CommitFormatHistory.UNKNOWN, CommitFormatHistory.ofDraftFormat("plain:text:v2"), "未知 Draft.format 不得推断为 legacy")
    }
}
