package com.qianyan.app.desktop

import com.qianyan.app.desktop.ui.pages.readerPositionLabel
import com.qianyan.app.desktop.ui.pages.readerPositionToSave
import com.qianyan.app.desktop.ui.pages.readerSingleBlockTarget
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * T-O68-F（缺陷 O-68）· 单块章节阅读位置。
 *
 * 单块（blockCount<=1）用「章内滚动千分比 0–1000」复用既有 Int position；
 * 多块（blockCount>1）保持「首个可见块序号」语义不变。
 */
class ReaderPositionTest {

    /** (a) 单块：滚动值按 maxScroll 折算千分比。 */
    @Test
    fun `single block maps scroll to per-mille position`() {
        assertEquals(0, readerPositionToSave(1, 0, 0, 1000))
        assertEquals(500, readerPositionToSave(1, 0, 500, 1000))
        assertEquals(1000, readerPositionToSave(1, 0, 1000, 1000))
        assertEquals(250, readerPositionToSave(1, 0, 500, 2000), "按 maxScroll 折算，不是原始像素")
    }

    /** (b) 单块：内容不满一屏（maxScroll=0）→ 0，不除零。 */
    @Test
    fun `single block with no scrollable range saves zero`() {
        assertEquals(0, readerPositionToSave(1, 0, 0, 0))
        assertEquals(0, readerPositionToSave(0, 0, 0, 0), "0 块与单块同语义")
    }

    /** (c) 单块：越界夹紧到 0–1000。 */
    @Test
    fun `single block clamps out of range scroll`() {
        assertEquals(1000, readerPositionToSave(1, 0, 1500, 1000))
        assertEquals(0, readerPositionToSave(1, 0, -40, 1000))
    }

    /** (d) 多块：仍返回首个可见块序号（原语义不变）。 */
    @Test
    fun `multi block keeps first visible block index`() {
        assertEquals(7, readerPositionToSave(3, 7, 500, 1000))
        assertEquals(0, readerPositionToSave(2, -3, 0, 1000), "负序号夹到 0")
        assertEquals(999, readerPositionToSave(2, 999, 100, 100), "多块忽略滚动值")
    }

    /** (e) 恢复：千分比 → 像素，越界夹紧；maxScroll=0 → 0。 */
    @Test
    fun `single block target converts per-mille back to pixels`() {
        assertEquals(400, readerSingleBlockTarget(500, 800))
        assertEquals(800, readerSingleBlockTarget(1500, 800), "saved 超过 1000 夹紧")
        assertEquals(0, readerSingleBlockTarget(-20, 800))
        assertEquals(0, readerSingleBlockTarget(500, 0), "无滚动范围 → 0")
    }

    /** (f) 页脚标签：单块显示整数百分比，多块显示块序号。 */
    @Test
    fun `position label shows percent for single block and index for multi block`() {
        assertEquals("0%", readerPositionLabel(1, 0))
        assertEquals("50%", readerPositionLabel(1, 500))
        assertEquals("100%", readerPositionLabel(1, 1000))
        assertEquals("50%", readerPositionLabel(0, 500), "0 块与单块同语义")
        assertEquals("7", readerPositionLabel(3, 7))
        assertEquals("0", readerPositionLabel(2, 0))
    }
}
