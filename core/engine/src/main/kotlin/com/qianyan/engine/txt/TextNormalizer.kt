package com.qianyan.engine.txt

/**
 * 确定性文本规范化（P4）。[VERSION] 参与文档 ruleVersion，保证规则演进可追溯。
 *
 * 规则（"清洗"≠"重写"，不改变正文语义、不删除非空白内容）：
 *  - 换行统一：CRLF / CR / LF → 仅以段落为单位重建（见下）；
 *  - 去除残留 BOM 字符（U+FEFF）；
 *  - 每行去除首尾空白（"明显无意义的首尾空白"）；
 *  - 空行规范化：连续空行折叠、首尾空行移除，空行仅作为段落分隔符；
 *  - 段落边界保留：每个非空行 = 一个段落，段落间以单个空行分隔。
 *
 * 规范形态：paragraphs.joinToString("\n\n")，无前导/尾随换行。
 * 因此可精确重建：normalize(input) == reconstruct(paragraphs)。
 *
 * 实现为单遍懒扫描（不一次性切出全部行数组），适合几十 MB 级别文本，无 O(n²)。
 */
object TextNormalizer {

    const val VERSION = "1"

    fun normalize(input: String): String {
        if (input.isEmpty()) return ""
        val sb = StringBuilder(input.length + 16)
        var needSeparator = false
        for (rawLine in lines(input)) {
            val line = trim(rawLine)
            if (line.isEmpty()) continue // 空行：仅作分隔符，折叠
            if (needSeparator) sb.append("\n\n")
            sb.append(line)
            needSeparator = true
        }
        return sb.toString()
    }

    /** 行 trim：去除首尾空白与残留 BOM 字符。 */
    private fun trim(line: String): String {
        val s = line.trim()
        return if (s.isNotEmpty() && s.first() == '\uFEFF') s.drop(1).trim() else s
    }

    /** 懒扫描换行：统一识别 \r\n / \r / \n，产出（可能含前导空行的）原始行序列。 */
    private fun lines(text: String): Sequence<String> = sequence {
        var start = 0
        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i]
            if (c == '\n' || c == '\r') {
                yield(text.substring(start, i))
                if (c == '\r' && i + 1 < n && text[i + 1] == '\n') i++
                i++
                start = i
            } else {
                i++
            }
        }
        if (start <= n) yield(text.substring(start))
    }
}
