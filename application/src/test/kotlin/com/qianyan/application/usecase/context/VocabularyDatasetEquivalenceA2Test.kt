package com.qianyan.application.usecase.context

import com.qianyan.application.usecase.writing.planning.VocabularyLite
import com.qianyan.model.BaseNovelId
import com.qianyan.model.IntentType
import com.qianyan.model.PlanningScope
import com.qianyan.model.RequestId
import com.qianyan.model.VariantScope
import com.qianyan.model.VocabularyEntryId
import com.qianyan.model.context.ContextRequest
import com.qianyan.model.context.TargetKind
import com.qianyan.model.context.TargetRef
import com.qianyan.model.context.TargetRefId
import com.qianyan.model.context.UserWritingRequest
import com.qianyan.model.context.VocabularyPayload
import com.qianyan.model.vocabulary.VocabularyEntry
import com.qianyan.model.vocabulary.VocabularyEntryStatus
import com.qianyan.model.vocabulary.VocabularyEntryType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * AC-30A · Vocabulary Source Dataset 等价（完整来源数据集 A/B）。
 *
 * 同一夹具、同一 Original 查询范围：
 *  - 旧 `PlanningContextAssembly.assemble(...)` 的完整 Vocabulary 数据集；
 *  - 新 `VocabularySource.candidates(...)` 的完整数据集。
 *
 * 两边都取 APPROVED 子集做集合等价；另测非 APPROVED `VocabularyEntry`：
 * 旧路径会读到（历史行为），新 Source 必须排除。
 */
class VocabularyDatasetEquivalenceA2Test {

    private fun request(w: SeededProject) = UserWritingRequest(
        requestId = RequestId("req-vocab-a2"),
        intentType = IntentType.CONTINUE,
        target = TargetRef(TargetKind.CHAPTER, TargetRefId(w.chapter2.value)),
        planningScope = PlanningScope.CHAPTER,
        baseNovelId = BaseNovelId(w.novelId.value),
        scope = VariantScope.ORIGINAL,
    )

    private fun entry(
        f: ContextFixture,
        w: SeededProject,
        id: String,
        canonical: String,
        status: VocabularyEntryStatus,
        aliases: List<String> = emptyList(),
        replacement: String? = null,
    ) {
        val vocabularyId = f.app.vocabularies.getOrCreateNovelVocabulary(w.novelId)
        f.app.vocabularyRepository.saveEntry(
            VocabularyEntry(
                entryId = VocabularyEntryId(id),
                vocabularyId = vocabularyId,
                novelId = w.novelId,
                canonical = canonical,
                aliases = aliases,
                replacement = replacement,
                type = VocabularyEntryType.PROPER_NOUN,
                status = status,
            ),
        )
    }

    @Test
    @Suppress("DEPRECATION")
    fun `old and new datasets match on approved entries and new source excludes non approved`() {
        val f = contextFixture()
        val w = seedProject(f, "书A", "A")

        // 完整数据集：5 个 APPROVED（含别名/替换）+ 3 个非 APPROVED Entry 行。
        entry(f, w, "ve-a2-1", "A 青石", VocabularyEntryStatus.APPROVED, aliases = listOf("A 青岩"), replacement = "A 苍石")
        entry(f, w, "ve-a2-2", "A 玄门", VocabularyEntryStatus.APPROVED)
        entry(f, w, "ve-a2-3", "A 长夜", VocabularyEntryStatus.APPROVED, aliases = listOf("A 长夜城"))
        entry(f, w, "ve-a2-4", "A 归墟", VocabularyEntryStatus.APPROVED)
        entry(f, w, "ve-a2-5", "A 星图", VocabularyEntryStatus.APPROVED, replacement = "A 星盘")
        entry(f, w, "ve-a2-pending", "A 未批准", VocabularyEntryStatus.CANDIDATE)
        entry(f, w, "ve-a2-deprecated", "A 已废弃", VocabularyEntryStatus.DEPRECATED)
        entry(f, w, "ve-a2-rejected", "A 已拒绝", VocabularyEntryStatus.REJECTED)

        val approved = f.app.vocabularyRepository.findEntriesByNovel(w.novelId)
            .filter { it.status == VocabularyEntryStatus.APPROVED }
        val approvedCanonicals = approved.map { it.canonical }.toSet()

        val old = f.app.planningContextAssembly.assemble(request(w)).vocabulary
        val oldApproved = old.filter { it.canonical in approvedCanonicals }.toSet()
        val expected = approved.map {
            VocabularyLite(canonical = it.canonical, aliases = it.aliases, replacement = it.replacement)
        }.toSet()
        assertEquals(expected, oldApproved, "旧路径的 APPROVED 子集必须与新数据集一致")

        val scope = ContextBuildScope(
            projectId = w.projectId,
            novelId = w.novelId,
            activeVariantId = null,
            state = f.app.projects.state(w.projectId),
            focusChapterId = w.chapter2,
        )
        val items = VocabularySource(f.app.vocabularyRepository).candidates(
            scope,
            ContextRequest(projectId = w.projectId, purpose = IntentType.CONTINUE),
        )
        val newLites = items.map { item ->
            val payload = item.payload as VocabularyPayload
            VocabularyLite(payload.canonical, payload.aliases, payload.replacement)
        }.toSet()
        assertEquals(expected, newLites, "新 Source 的完整数据集必须与 APPROVED 条目一致")
        assertEquals(
            items.map { (it.payload as VocabularyPayload).canonical },
            items.map { (it.payload as VocabularyPayload).canonical }.sorted(),
            "新 Source 必须按 canonical 确定性排序",
        )

        // 旧路径的历史行为：会读到非 APPROVED Entry；新 Source 必须排除。
        assertTrue(old.any { it.canonical == "A 未批准" }, "旧路径无状态过滤，应包含非 APPROVED 行")
        assertFalse(newLites.any { it.canonical == "A 未批准" }, "新 Source 不得包含 CANDIDATE")
        assertFalse(newLites.any { it.canonical == "A 已废弃" }, "新 Source 不得包含 DEPRECATED")
        assertFalse(newLites.any { it.canonical == "A 已拒绝" }, "新 Source 不得包含 REJECTED")
        assertFalse(items.any { it.source != com.qianyan.model.context.ContextSourceKind.VOCABULARY })

        f.close()
    }
}
