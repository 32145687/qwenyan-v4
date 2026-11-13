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
import com.qianyan.model.VocabularyEntryId
import com.qianyan.model.VocabularyId
import com.qianyan.model.core.Novel
import com.qianyan.model.core.NovelVariant
import com.qianyan.model.core.VariantStatus
import com.qianyan.model.vocabulary.Vocabulary
import com.qianyan.model.vocabulary.VocabularyEntry
import com.qianyan.model.vocabulary.VocabularyEntryStatus
import com.qianyan.model.vocabulary.VocabularyEntryType
import com.qianyan.model.vocabulary.VocabularyScopeLevel
import com.qianyan.storage.db.QianyanDbFactory
import com.qianyan.storage.db.QianyanDbHandle
import com.qianyan.storage.repository.SqliteNovelRepository
import com.qianyan.storage.repository.SqliteVocabularyRepository
import java.nio.file.Files
import kotlinx.datetime.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A-36 a2=B（O-39b-FK）· VocabularyEntry 的数据库级归属守卫（触发器）。
 *
 * 目标：直接调用 `repo.saveEntry` 也不能写入孤儿 novel / 不存在或跨作品的 variant。
 * 合法路径（novel 存在；variant 为空或属于同 novel）不受影响；守卫在每次初始化时幂等安装。
 */
class VocabularyEntryOwnershipGuardTest {

    private fun handle(url: String = JdbcSqliteDriver.IN_MEMORY): QianyanDbHandle = QianyanDbFactory.open(url)

    private fun makeNovel(id: String) = Novel(
        novelId = NovelId(id),
        projectId = ProjectId("proj-$id"),
        title = "作品 $id",
        source = ProjectSource.ORIGINAL_NOVEL,
        scope = VariantScope.ORIGINAL,
        status = ProjectStatus.DRAFT,
        createdAt = Clock.System.now(),
        updatedAt = Clock.System.now(),
    )

    private fun makeVariant(variantId: String, novelId: NovelId) = NovelVariant(
        variantId = VariantId(variantId),
        novelId = novelId,
        baseNovelId = BaseNovelId(novelId.value),
        projectId = ProjectId("proj-${novelId.value}"),
        name = "变体 $variantId",
        status = VariantStatus.DRAFT,
        createdAt = Clock.System.now(),
        updatedAt = Clock.System.now(),
    )

    private fun vocabularyOf(h: QianyanDbHandle, vocabularyId: String, novelId: NovelId?): String {
        SqliteVocabularyRepository(h.db).saveVocabulary(
            Vocabulary(
                vocabularyId = VocabularyId(vocabularyId),
                novelId = novelId,
                scopeLevel = VocabularyScopeLevel.NOVEL,
                name = "词库",
            ),
        )
        return vocabularyId
    }

    private fun entry(
        entryId: String,
        vocabularyId: String,
        novelId: NovelId?,
        variantId: VariantId? = null,
        canonical: String = "灵石",
    ) = VocabularyEntry(
        entryId = VocabularyEntryId(entryId),
        vocabularyId = VocabularyId(vocabularyId),
        novelId = novelId,
        variantId = variantId,
        scopeLevel = if (variantId == null) VocabularyScopeLevel.NOVEL else VocabularyScopeLevel.VARIANT,
        canonical = canonical,
        type = VocabularyEntryType.WORLD_TERM,
        status = VocabularyEntryStatus.APPROVED,
    )

    private fun count(h: QianyanDbHandle): Long = h.driver.executeQuery(
        null,
        "SELECT COUNT(*) FROM VocabularyEntry",
        { cursor ->
            cursor.next()
            QueryResult.Value(cursor.getLong(0) ?: 0L)
        },
        0,
    ).value

    @Test
    fun `insert entry with unknown novel aborts`() {
        val h = handle()
        try {
            val vocabularyId = vocabularyOf(h, "vocab-ghost", null)
            val ghost = NovelId("ghost-novel")

            val ex = assertFailsWith<Exception> {
                SqliteVocabularyRepository(h.db).saveEntry(entry("e-ghost-novel", vocabularyId, ghost))
            }

            assertTrue(ex.message.orEmpty().contains("不存在"), "必须给出归属缺失原因：${ex.message}")
            assertEquals(0, count(h), "非法写入不得留下任何行")
        } finally {
            h.driver.close()
        }
    }

    @Test
    fun `insert entry with unknown variant aborts`() {
        val h = handle()
        try {
            val novel = makeNovel("novel-a")
            SqliteNovelRepository(h.db).createOriginal(novel)
            val vocabularyId = vocabularyOf(h, "vocab-a", novel.novelId)

            val ex = assertFailsWith<Exception> {
                SqliteVocabularyRepository(h.db).saveEntry(
                    entry("e-ghost-variant", vocabularyId, novel.novelId, VariantId("ghost-variant")),
                )
            }

            assertTrue(ex.message.orEmpty().contains("不存在或不属于该作品"), "Variant 归属必须显式：${ex.message}")
            assertEquals(0, count(h))
        } finally {
            h.driver.close()
        }
    }

    @Test
    fun `insert entry with variant of another novel aborts`() {
        val h = handle()
        try {
            val novels = SqliteNovelRepository(h.db)
            val novelA = makeNovel("novel-a")
            val novelB = makeNovel("novel-b")
            novels.createOriginal(novelA)
            novels.createOriginal(novelB)
            novels.createVariant(makeVariant("variant-b", novelB.novelId))
            val vocabularyId = vocabularyOf(h, "vocab-a", novelA.novelId)

            val ex = assertFailsWith<Exception> {
                SqliteVocabularyRepository(h.db).saveEntry(
                    entry("e-cross-novel", vocabularyId, novelA.novelId, VariantId("variant-b")),
                )
            }

            assertTrue(ex.message.orEmpty().contains("不属于该作品"), "跨作品 Variant 必须被拒：${ex.message}")
            assertEquals(0, count(h))
        } finally {
            h.driver.close()
        }
    }

    @Test
    fun `legal novel and variant entries succeed`() {
        val h = handle()
        try {
            val novels = SqliteNovelRepository(h.db)
            val novel = makeNovel("novel-a")
            novels.createOriginal(novel)
            novels.createVariant(makeVariant("variant-a", novel.novelId))
            val vocabularyId = vocabularyOf(h, "vocab-a", novel.novelId)
            val repo = SqliteVocabularyRepository(h.db)

            repo.saveEntry(entry("e-novel", vocabularyId, novel.novelId))
            repo.saveEntry(entry("e-variant", vocabularyId, novel.novelId, VariantId("variant-a")))

            assertEquals(2, count(h), "合法 NOVEL / VARIANT 行必须成功")
            assertEquals(1, repo.findEntriesByNovel(novel.novelId).size)
            assertEquals(1, repo.findEntriesByVariant(novel.novelId, VariantId("variant-a")).size)
        } finally {
            h.driver.close()
        }
    }

    @Test
    fun `update novel id to unknown aborts`() {
        val h = handle()
        try {
            val novel = makeNovel("novel-a")
            SqliteNovelRepository(h.db).createOriginal(novel)
            val vocabularyId = vocabularyOf(h, "vocab-a", novel.novelId)
            SqliteVocabularyRepository(h.db).saveEntry(entry("e-keep", vocabularyId, novel.novelId))

            val ex = assertFailsWith<Exception> {
                h.driver.execute(
                    null,
                    "UPDATE VocabularyEntry SET novel_id = 'ghost-novel' WHERE entry_id = 'e-keep'",
                    0,
                )
            }

            assertTrue(ex.message.orEmpty().contains("不存在"), "UPDATE 也必须被守卫：${ex.message}")
            assertEquals(1, count(h))
            val kept = SqliteVocabularyRepository(h.db).findEntriesByNovel(novel.novelId).single()
            assertEquals(novel.novelId, kept.novelId)
        } finally {
            h.driver.close()
        }
    }

    @Test
    fun `guard triggers are idempotent across reopen`() {
        val tmp = Files.createTempFile("qianyan-vocab-guard", ".db").toString()
        val url = "jdbc:sqlite:$tmp"
        try {
            var h = handle(url)
            val novel = makeNovel("novel-a")
            SqliteNovelRepository(h.db).createOriginal(novel)
            val vocabularyId = vocabularyOf(h, "vocab-a", novel.novelId)
            SqliteVocabularyRepository(h.db).saveEntry(entry("e-1", vocabularyId, novel.novelId))
            assertFailsWith<Exception> {
                SqliteVocabularyRepository(h.db).saveEntry(
                    entry("e-bad-1", vocabularyId, NovelId("ghost-novel")),
                )
            }
            h.driver.close()

            // 重开：初始化再跑一遍 GUARD_DDL（IF NOT EXISTS），守卫仍生效且不重复建
            h = handle(url)
            try {
                SqliteVocabularyRepository(h.db).saveEntry(entry("e-2", vocabularyId, novel.novelId))
                val ex = assertFailsWith<Exception> {
                    SqliteVocabularyRepository(h.db).saveEntry(
                        entry("e-bad-2", vocabularyId, NovelId("ghost-novel")),
                    )
                }
                assertTrue(ex.message.orEmpty().contains("不存在"), "重开后非法写入仍被拒：${ex.message}")
                assertEquals(2, count(h), "重开只保留两条合法行")
            } finally {
                h.driver.close()
            }
        } finally {
            Files.deleteIfExists(java.nio.file.Path.of(tmp))
        }
    }
}
