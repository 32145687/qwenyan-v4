package com.qianyan.storage.repository

import com.qianyan.model.ChapterId
import com.qianyan.model.DraftId
import com.qianyan.model.NovelId
import com.qianyan.model.writing.Draft
import com.qianyan.storage.db.QianyanDb

/** [DraftRepository] 的 SQLDelight + SQLite JDBC 实现。 */
class SqliteDraftRepository(
    private val db: QianyanDb,
) : DraftRepository {

    override fun save(draft: Draft) {
        val row = StorageMappers.domainDraft(draft)
        db.draftQueries.insertDraft(
            draft_id = row.draft_id,
            novel_id = row.novel_id,
            variant_id = row.variant_id,
            scope = row.scope,
            chapter_id = row.chapter_id,
            chapter_plan_id = row.chapter_plan_id,
            previous_draft_id = row.previous_draft_id,
            content = row.content,
            status = row.status,
            source_model = row.source_model,
            created_at = row.created_at,
            updated_at = row.updated_at,
        )
    }

    override fun getById(draftId: DraftId): Draft? =
        db.draftQueries.getDraftById(draftId.value).executeAsOneOrNull()
            ?.let { StorageMappers.dbDraft(it) }

    override fun listByNovel(novelId: NovelId): List<Draft> =
        db.draftQueries.listDraftsByNovel(novelId.value).executeAsList()
            .map { StorageMappers.dbDraft(it) }

    override fun listByChapter(chapterId: ChapterId): List<Draft> =
        db.draftQueries.listDraftsByChapter(chapterId.value).executeAsList()
            .map { StorageMappers.dbDraft(it) }

    override fun latestByChapter(chapterId: ChapterId): Draft? =
        db.draftQueries.getLatestDraftByChapter(chapterId.value).executeAsOneOrNull()
            ?.let { StorageMappers.dbDraft(it) }
}