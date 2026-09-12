package com.qianyan.storage.repository

import com.qianyan.model.NovelId
import com.qianyan.model.VariantId
import com.qianyan.model.lcl.NarrativeDelta
import com.qianyan.model.lcl.NarrativeState
import com.qianyan.model.lcl.NarrativeStateFold
import com.qianyan.storage.db.QianyanDb

/** [NarrativeStateRepository] 的 SQLDelight + SQLite 实现（P13 LCL-A）。 */
class SqliteNarrativeStateRepository(private val db: QianyanDb) : NarrativeStateRepository {

    override fun getNarrativeState(novelId: NovelId, variantId: VariantId?): NarrativeState? =
        db.narrativeStateQueries.getStateByScope(novelId.value, variantId?.value).executeAsOneOrNull()
            ?.let { StorageMappers.dbNarrativeState(it) }

    /** 确定性折叠 + 写回快照（PK = 确定性账本 ID → INSERT OR REPLACE 幂等）。 */
    override fun project(novelId: NovelId, variantId: VariantId?): NarrativeState {
        val deltas = listNarrativeDeltas(novelId, variantId)
        val state = NarrativeStateFold.fold(deltas, novelId, variantId)
        val row = StorageMappers.dbNarrativeState(state)
        db.narrativeStateQueries.upsertState(
            narrative_state_id = row.narrative_state_id,
            novel_id = row.novel_id,
            variant_id = row.variant_id,
            scope = row.scope,
            version = row.version,
            main_goal = row.main_goal,
            current_conflict = row.current_conflict,
            open_threads = row.open_threads,
            character_stages = row.character_stages,
            relationship_deltas = row.relationship_deltas,
            foreshadow_pressures = row.foreshadow_pressures,
            current_pacing = row.current_pacing,
            last_chapter_delta = row.last_chapter_delta,
            updated_at = row.updated_at,
        )
        return state
    }

    override fun appendNarrativeDelta(delta: NarrativeDelta, expectedVersion: Long?) {
        db.transaction {
            if (expectedVersion != null) {
                val current = currentDeltaCount(delta.novelId, delta.variantId)
                check(current == expectedVersion) {
                    "Narrative Delta 乐观锁冲突：当前版本=$current，期望=$expectedVersion（并发修改已发生）"
                }
            }
            val row = StorageMappers.dbNarrativeDelta(delta)
            db.narrativeStateQueries.insertDelta(
                delta_id = row.delta_id,
                narrative_state_id = row.narrative_state_id,
                novel_id = row.novel_id,
                variant_id = row.variant_id,
                scope = row.scope,
                chapter_id = row.chapter_id,
                main_goal = row.main_goal,
                current_conflict = row.current_conflict,
                open_threads = row.open_threads,
                character_stages = row.character_stages,
                relationship_deltas = row.relationship_deltas,
                foreshadow_pressures = row.foreshadow_pressures,
                current_pacing = row.current_pacing,
                summary = row.summary,
                created_at = row.created_at,
            )
        }
    }

    override fun listNarrativeDeltas(novelId: NovelId, variantId: VariantId?): List<NarrativeDelta> =
        db.narrativeStateQueries.listDeltasByScope(novelId.value, variantId?.value).executeAsList()
            .map { StorageMappers.dbNarrativeDelta(it) }

    private fun currentDeltaCount(novelId: NovelId, variantId: VariantId?): Long =
        db.narrativeStateQueries.countDeltasByScope(novelId.value, variantId?.value).executeAsOne()
}