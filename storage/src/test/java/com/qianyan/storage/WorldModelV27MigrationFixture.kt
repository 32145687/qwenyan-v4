package com.qianyan.storage

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver

/**
 * Phase 2（26.sqm，v26 → v27）在 Character / CharacterState / Event 上新增的 17 列。
 *
 * `ALTER TABLE ADD COLUMN` 不幂等：旧形态降级夹具（"建到最新 → 摘列 → 降 user_version → 重开"）
 * 在设置 user_version 之前必须真实摘除这些列，否则重开时 26.sqm 会报 duplicate column。
 */
internal val WORLD_MODEL_V27_COLUMNS: List<Pair<String, String>> = listOf(
    "Character" to "goals",
    "Character" to "fears",
    "Character" to "description",
    "CharacterState" to "location",
    "CharacterState" to "current_motivation",
    "CharacterState" to "relationships",
    "CharacterState" to "known_facts",
    "CharacterState" to "unknown_facts",
    "CharacterState" to "abilities",
    "CharacterState" to "source",
    "Event" to "what",
    "Event" to "where_json",
    "Event" to "cause",
    "Event" to "consequence",
    "Event" to "participants",
    "Event" to "evidence",
    "Event" to "fact_level",
)

/** 真实摘除 v27 新增列（列 / 表不存在则跳过，兼容更旧形态的夹具）。 */
internal fun SqlDriver.dropWorldModelV27Columns() {
    for ((tbl, col) in WORLD_MODEL_V27_COLUMNS) {
        val exists = executeQuery(
            null, "SELECT 1 FROM pragma_table_info('$tbl') WHERE name='$col' LIMIT 1",
            { cursor -> QueryResult.Value(cursor.next().value) }, 0,
        ).value
        if (exists) execute(null, "ALTER TABLE $tbl DROP COLUMN $col", 0)
    }
}
