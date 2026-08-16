package com.qianyan.engine.txt

/**
 * 章节检测规则集（确定性，版本 [VERSION]）。规则演进需改版本号（参与 ruleVersion）。
 *
 * 支持（不无限扩展）：
 *  - 中文章节：第…[章节回卷集部篇]（阿拉伯/中文数字，允许空格），如 第一章 / 第1章 / 第 2 章 / 第一百二十三章；
 *  - 卷：卷…（可带子章节），如 卷一 / 卷一 第一章 / 卷三；
 *  - 英文章节：Chapter / CHAPTER + 数字/罗马数字；
 *  - 特殊前后置章节：序章 / 楔子 / 序言 / 引子 / 前言 / 尾声 / 后记 / 番外 / 外传 / 终章（可带编号）。
 *
 * 判断原则：命中即切分；无法可靠判断的格式按正文处理（不强行猜测），由 Detector 生成提示。
 */
object ChapterRules {

    const val VERSION = "1"

    /** 第…[章节回卷集部篇]，数字部分允许一至多位（阿拉伯 + 中文）。 */
    private val CHAPTER_ZH = Regex("^\\s*第\\s*[0-9一二三四五六七八九十百千零两]+\\s*[章节回卷集部篇]")

    /** 卷…（可仅卷标题，也可带子章节，如 卷一 第一章）。 */
    private val VOLUME_ZH = Regex("^\\s*卷\\s*[0-9一二三四五六七八九十百千零两]+\\s*[章节回卷集部篇]?")

    /** Chapter / CHAPTER + 数字/罗马数字。 */
    private val CHAPTER_EN = Regex("^\\s*[Cc][Hh][Aa][Pp][Tt][Ee][Rr]\\s+[0-9IVXLCDM]+")

    /** 特殊前后置章节：整行匹配，可带一个编号后缀。 */
    private val SPECIAL = Regex(
        "^\\s*(序章|楔子|序言|引子|前言|尾声|后记|番外|外传|终章)([0-9一二三四五六七八九十百千零两])?\\s*$"
    )

    /** 判断一行（已去首尾空白）是否为章节标题。确定性、无副作用。 */
    fun isChapterHeading(line: String): Boolean =
        CHAPTER_ZH.containsMatchIn(line) ||
            VOLUME_ZH.containsMatchIn(line) ||
            CHAPTER_EN.containsMatchIn(line) ||
            SPECIAL.matches(line.trim())
}
