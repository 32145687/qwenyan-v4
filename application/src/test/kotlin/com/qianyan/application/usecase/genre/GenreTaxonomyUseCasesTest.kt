package com.qianyan.application.usecase.genre

import com.qianyan.application.di.ApplicationContainer
import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.model.GenreId
import com.qianyan.model.genre.Genre
import com.qianyan.model.genre.GenreTaxonomy
import com.qianyan.model.genre.GenreTaxonomyCatalog
import com.qianyan.model.genre.GenreTaxonomyRules
import com.qianyan.provider.impl.MockLLMGateway
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * P14-A · GenreTaxonomyUseCases 测试（Taxonomy 受控 seam）。
 *
 * Confirmed-Genre 写入（写 `NovelVariant.genre`）因 **`NovelVariant` 无 genre 字段** 属 BLOCKER，
 * 本 P14-A 阶段不实现写入，故不测写入；仅覆盖受控 Taxonomy 的确定性/校验/目录完整性。
 */
class GenreTaxonomyUseCasesTest {

    private val genres get() = ApplicationContainer.open(analysisGateway = MockLLMGateway()).genres

    // 1. availableGenres() deterministic
    @Test fun `availableGenres is deterministic and non empty`() {
        val a = genres.availableGenres()
        val b = genres.availableGenres()
        assertEquals(a, b, "deterministic")
        assertTrue(a.isNotEmpty())
        assertEquals(a.sortedBy { it.genreId.value }, a, "稳定序")
    }

    // 2. genreId 唯一
    @Test fun `catalog genre ids are unique`() {
        val ids = genres.availableGenres().map { it.genreId.value }
        assertEquals(ids.size, ids.toSet().size, "GenreId 不应重复")
    }

    // 3. isKnown 正确
    @Test fun `isKnown reflects catalog`() {
        assertTrue(genres.isKnown(GenreId("romance")))
        assertTrue(genres.isKnown(GenreId("fantasy_eastern")))
        assertFalse(genres.isKnown(GenreId("no_such_genre")))
    }

    // 4. unknown rejected
    @Test fun `validate rejects unknown genre`() {
        val ex = assertFailsWith<ApplicationException> { genres.validate(listOf(GenreId("ghost_genre"))) }
        assertIs<ApplicationError.InvalidOperation>(ex.error)
    }

    // 5. duplicate rejected
    @Test fun `validate rejects duplicate genre ids`() {
        val ex = assertFailsWith<ApplicationException> {
            genres.validate(listOf(GenreId("romance"), GenreId("romance")))
        }
        assertIs<ApplicationError.InvalidOperation>(ex.error)
    }

    // 6-8: rules 分支（用合成 Taxonomy 直接验证纯规则，不受默认目录限制）
    @Test fun `rules reject invalid parent and self parent and accept valid parent child`() {
        val bad = GenreTaxonomy(version = 1, genres = listOf(
            Genre(GenreId("a"), "A", parentId = GenreId("ghost")),
            Genre(GenreId("s"), "S", parentId = GenreId("s")), // self-parent
        ))
        val r = GenreTaxonomyRules.validate(bad, listOf(GenreId("a"), GenreId("s")))
        assertFalse(r.isValid)
        assertTrue(r.errors.any { it.contains("parent 不存在") })
        assertTrue(r.errors.any { it.contains("self-parent") })

        // valid: child parent exists，无环（单层）
        val ok = GenreTaxonomy(version = 1, genres = listOf(
            Genre(GenreId("top"), "Top"),
            Genre(GenreId("child"), "Child", parentId = GenreId("top")),
        ))
        val v = GenreTaxonomyRules.validate(ok, listOf(GenreId("top"), GenreId("child")))
        assertTrue(v.isValid)
        assertEquals(listOf(GenreId("top"), GenreId("child")), v.resolved)
    }

    // 9-13: Confirmed-Genre 写入 —— BLOCKER（无 NovelVariant.genre 槽），本阶段不实现、不测写入。
    @Test fun `confirmed genre write is blocked by missing variant genre field`() {
        // 如实记录阻塞：经典冻结方案期望写入 NovelVariant.genre，但该字段不存在。
        // 断言默认目录至少可被校验通过（写入主体仍待 P14-F / 授权扩展）。
        assertEquals(listOf(GenreId("romance")), genres.validate(listOf(GenreId("romance"))))
    }
}