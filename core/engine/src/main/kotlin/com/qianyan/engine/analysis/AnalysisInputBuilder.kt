package com.qianyan.engine.analysis

import com.qianyan.model.analysis.AnalysisBlockInput
import com.qianyan.model.analysis.AnalysisChapterInput
import com.qianyan.model.analysis.AnalysisInput
import com.qianyan.model.txt.TextBlock
import com.qianyan.model.txt.TxtChapter
import com.qianyan.model.txt.TxtDocument

/**
 * 确定性 TXT → AnalysisInput 构建器（P6.4）。
 *
 * 规则（确定性约束 §14）：
 *  - **不调用 LLM**、不访问网络/数据库/Repository；
 *  - 章节按 `ordinal` 升序，段块按各自 `ordinal` 升序 → 顺序稳定，多次读取一致；
 *  - 保留每个段块的 `sourceLocation`，可回溯到原 TXT；
 *  - 纯函数：相同输入必定返回相同 [AnalysisInput]。
 *
 * P6 按**章节粒度**分析：每章聚合其下全部段块作为该章输入。
 */
object AnalysisInputBuilder {

    /**
     * 由 TXT 结构构建 Analysis 输入。
     *
     * @throws IllegalArgumentException 当 [TxtDocument.novelId] 为 null（未绑定 Novel，无法构建按 Novel 归属的分析输入）。
     */
    fun build(
        document: TxtDocument,
        chapters: List<TxtChapter>,
        blocks: List<TextBlock>,
    ): AnalysisInput {
        val novelId = document.novelId
            ?: throw IllegalArgumentException("TXT 文档未绑定 Novel，无法构建 AnalysisInput（document=${document.documentId.value}）")

        val byChapter = blocks.groupBy { it.chapterId }
        val chapterInputs = chapters
            .filter { it.documentId == document.documentId }   // 仅本文档章节
            .sortedBy { it.ordinal }                           // 确定性顺序
            .map { ch ->
                val blockInputs = byChapter[ch.chapterId].orEmpty()
                    .sortedBy { it.ordinal }
                    .map { b ->
                        AnalysisBlockInput(
                            blockId = b.blockId,
                            ordinal = b.ordinal,
                            text = b.text,
                            sourceLocation = b.sourceLocation,
                        )
                    }
                AnalysisChapterInput(
                    chapterId = ch.chapterId,
                    ordinal = ch.ordinal,
                    title = ch.title,
                    blocks = blockInputs,
                )
            }

        return AnalysisInput(
            novelId = novelId,
            documentId = document.documentId,
            title = document.title,
            chapters = chapterInputs,
        )
    }
}