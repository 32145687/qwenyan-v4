package com.qianyan.app.desktop.ui.pages

/**
 * T-O68-F（缺陷 O-68）· 阅读位置的纯换算，单块章节复用既有 Int `position` 字段。
 *
 * 位置语义（不改 Schema、不加列、不迁移）：
 *  - 多块（blockCount > 1）：`position` = 首个可见块序号（原语义，完全不变）；
 *  - 单块（blockCount <= 1）：`position` = 章内滚动千分比 0–1000
 *    （legacy 纯文本整章只有 1 块，块序号恒为 0，旧实现因此永远保存/恢复 0）。
 */

/** 保存用：由当前滚动状态得到要落库的 position。 */
internal fun readerPositionToSave(
    blockCount: Int,
    firstVisibleBlock: Int,
    scrollValue: Int,
    maxScroll: Int,
): Int {
    if (blockCount > 1) return firstVisibleBlock.coerceAtLeast(0)
    if (maxScroll <= 0) return 0
    return (scrollValue.toLong() * 1000L / maxScroll).toInt().coerceIn(0, 1000)
}

/** 恢复用：把已保存的 position 换算回单块章节的滚动像素；无滚动范围 → 0。 */
internal fun readerSingleBlockTarget(saved: Int, maxScroll: Int): Int {
    if (maxScroll <= 0) return 0
    return (saved.coerceIn(0, 1000).toLong() * maxScroll / 1000L).toInt()
}

/** 页脚标签：单块显示整数百分比，多块显示块序号。 */
internal fun readerPositionLabel(blockCount: Int, position: Int): String =
    if (blockCount > 1) {
        position.toString()
    } else {
        "${(position.coerceIn(0, 1000) + 5) / 10}%"
    }
