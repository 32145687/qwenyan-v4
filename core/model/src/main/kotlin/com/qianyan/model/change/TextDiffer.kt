package com.qianyan.model.change

/*
 * I9 · 最小确定性文本 Diff（纯函数：无 LLM / 无随机 / 无时间 / 无外部状态 / 无第三方库）。
 *
 * 为什么不用第三方 diff 库：项目内不存在 diff 依赖，且 I9 只需要"章节正文相对基线的实际变化"，
 * 完整 LCS/Myers 对齐属过度设计（§2 "不要在 I9 建复杂 IDE Patch 系统"）。
 *
 * 算法（O(n)）：行级**公共前缀 / 公共后缀裁剪**，中间不匹配段作为"删除段 + 新增段"。
 *  - 同位置无关的重复行不会被对齐（不做 LCS）⇒ 结果**偏粗但完全确定、稳定、可复现**；
 *  - 单处变更区域（含原位修改 / 中间插入 / 中间删除）都能被正确表达；
 *  - 多处分散变更会被合并为一个变更区域（后续如需更细粒度，属增强而非本阶段范围）。
 *
 * 换行规范（显式定义，§3）：
 *  - `\r\n` 与 `\r` 一律归一为 `\n`；
 *  - 末尾单个 `\n` 不产生额外空行（"a\n" 与 "a" 等价 ⇒ UNCHANGED）；
 *  - 双方均为空白（空串 / 仅空白）⇒ 视作"无内容"，UNCHANGED 且无 hunk。
 */
object TextDiffer {

    /** 归一化换行（CRLF / CR → LF）。 */
    fun normalizeNewlines(text: String): String = text.replace("\r\n", "\n").replace('\r', '\n')

    /** 归一化后的行序列（空白文本 ⇒ 空列表；末尾换行不产生额外空行）。 */
    fun lines(text: String): List<String> {
        val normalized = normalizeNewlines(text)
        if (normalized.isEmpty()) return emptyList()
        val body = if (normalized.endsWith("\n")) normalized.dropLast(1) else normalized
        return body.split("\n")
    }

    /**
     * 计算 [workingText] 相对 [baseText] 的文本变化（确定性）。
     *
     * op 由 hunk 形状派生：仅新增 ⇒ [ChangeKind.ADDED]；仅删除 ⇒ [ChangeKind.REMOVED]；
     * 兼有 ⇒ [ChangeKind.MODIFIED]；无 hunk ⇒ [ChangeKind.UNCHANGED]。
     */
    fun diff(baseText: String, workingText: String): TextDiff {
        // 空白 ⇒ 无内容（§3 "空文本行为明确"）
        val base = if (baseText.isBlank()) emptyList() else lines(baseText)
        val working = if (workingText.isBlank()) emptyList() else lines(workingText)

        // 公共前缀 / 后缀（前缀与后缀不重叠）
        var prefix = 0
        while (prefix < base.size && prefix < working.size && base[prefix] == working[prefix]) prefix++
        var suffix = 0
        while (
            suffix < base.size - prefix &&
            suffix < working.size - prefix &&
            base[base.size - 1 - suffix] == working[working.size - 1 - suffix]
        ) {
            suffix++
        }

        val removed = base.subList(prefix, base.size - suffix)
        val added = working.subList(prefix, working.size - suffix)

        val hunks = mutableListOf<DiffHunk>()
        if (prefix > 0) {
            hunks += DiffHunk(
                kind = DiffLineKind.UNCHANGED,
                lines = base.subList(0, prefix).toList(),
                baseStartLine = 1,
                workingStartLine = 1,
            )
        }
        if (removed.isNotEmpty()) {
            hunks += DiffHunk(
                kind = DiffLineKind.REMOVED,
                lines = removed.toList(),
                baseStartLine = prefix + 1,
            )
        }
        if (added.isNotEmpty()) {
            hunks += DiffHunk(
                kind = DiffLineKind.ADDED,
                lines = added.toList(),
                workingStartLine = prefix + 1,
            )
        }
        if (suffix > 0) {
            hunks += DiffHunk(
                kind = DiffLineKind.UNCHANGED,
                lines = base.subList(base.size - suffix, base.size).toList(),
                baseStartLine = base.size - suffix + 1,
                workingStartLine = working.size - suffix + 1,
            )
        }

        val op = when {
            removed.isEmpty() && added.isEmpty() -> ChangeKind.UNCHANGED
            removed.isEmpty() -> ChangeKind.ADDED
            added.isEmpty() -> ChangeKind.REMOVED
            else -> ChangeKind.MODIFIED
        }

        return TextDiff(
            op = op,
            hunks = hunks,
            unchangedLines = prefix + suffix,
            addedLines = added.size,
            removedLines = removed.size,
        )
    }
}