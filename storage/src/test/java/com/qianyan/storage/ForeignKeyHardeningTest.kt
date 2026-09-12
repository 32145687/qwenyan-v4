package com.qianyan.storage

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.qianyan.model.BaseNovelId
import com.qianyan.model.NovelId
import com.qianyan.model.ProjectId
import com.qianyan.model.ProjectSource
import com.qianyan.model.ProjectStatus
import com.qianyan.model.VariantId
import com.qianyan.model.VariantScope
import com.qianyan.model.core.Novel
import com.qianyan.model.core.NovelVariant
import com.qianyan.storage.db.QianyanDbFactory
import com.qianyan.storage.db.QianyanDbHandle
import com.qianyan.storage.repository.SqliteNovelRepository
import com.qianyan.storage.repository.UniqueConflictException
import kotlinx.datetime.Clock
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * P12.4 Hardening：
 *  - M01：外键约束在所有连接（JVM；Android 走同一 DatabaseInitializer）运行时生效——`PRAGMA foreign_keys=1`，
 *    并拒绝引用不存在 Novel 的行；`foreign_key_check` 无违规。
 *  - M02：`SqliteNovelRepository.createVariant` 持久化失败不再被吞，而是抛出（重复 variant id → UniqueConflictException）。
 */
class ForeignKeyHardeningTest {

    private fun handle(url: String = JdbcSqliteDriver.IN_MEMORY): QianyanDbHandle = QianyanDbFactory.open(url)
    private fun randomId(prefix: String) = "$prefix-${Random.nextLong().toString(16)}"

    private fun makeNovel(id: String = randomId("nv")) = Novel(
        novelId = NovelId(id),
        projectId = ProjectId("proj-$id"),
        title = "原著",
        source = ProjectSource.ORIGINAL_NOVEL,
        scope = VariantScope.ORIGINAL,
        status = ProjectStatus.DRAFT,
        createdAt = Clock.System.now(),
        updatedAt = Clock.System.now(),
    )

    private fun makeVariant(variantId: String, baseNovelId: NovelId) = NovelVariant(
        variantId = VariantId(variantId),
        novelId = baseNovelId,
        baseNovelId = BaseNovelId(baseNovelId.value),
        projectId = ProjectId("proj-$baseNovelId"),
        name = "改线",
        status = com.qianyan.model.core.VariantStatus.ACTIVE,
        createdAt = Clock.System.now(),
        updatedAt = Clock.System.now(),
    )

    private fun foreignKeysOn(handle: QianyanDbHandle): Boolean =
        handle.driver.executeQuery(
            null,
            "PRAGMA foreign_keys",
            { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getLong(0) == 1L else false) },
            0,
        ).value

    /** M01：连接建立时已开启外键约束（PRAGMA foreign_keys=1）。 */
    @Test
    fun `foreign keys are enabled on the connection`() {
        foreignKeysOn(handle()) // 仅确保不抛
        val h = handle()
        assertTrue(foreignKeysOn(h), "PRAGMA foreign_keys 应为 1（P12.4-M01）")
    }

    /** M01：引用不存在 Novel 的行被外键拒绝（FK 真正生效）。 */
    @Test
    fun `insert row referencing missing novel is rejected by FK`() {
        val h = handle()
        val repo = SqliteNovelRepository(h.db)
        repo.createOriginal(makeNovel("fk-novel"))
        // Character 引用不存在的 novel_id → 应被外键约束拒绝
        assertFailsWith<Exception> {
            h.driver.execute(
                null,
                "INSERT INTO Character(character_id, novel_id, variant_id, scope, name, personality, created_at, updated_at) " +
                    "VALUES ('c_bad_fk', 'no_such_novel', NULL, 'ORIGINAL', 'x', '[]', 0, 0)",
                0,
            )
        }
        // foreign_key_check 无违规（正常库）
        val violations = h.driver.executeQuery(
            null,
            "PRAGMA foreign_key_check",
            { cursor -> QueryResult.Value(cursor.next().value) },
            0,
        ).value
        assertTrue(!violations, "foreign_key_check 不应有违规")
    }

    /** M02：createVariant 持久化失败必须向上传播（重复 variantId → UniqueConflictException），不得吞。 */
    @Test
    fun `createVariant propagates duplicate variant id error`() {
        val repo = SqliteNovelRepository(handle().db)
        val original = makeNovel()
        repo.createOriginal(original)
        repo.createVariant(makeVariant("variant-dup", original.novelId))
        assertFailsWith<UniqueConflictException> {
            repo.createVariant(makeVariant("variant-dup", original.novelId))
        }
        assertEquals(1, repo.getVariantsOfNovel(original.novelId).size, "重复写入不应产生第二行")
    }
}