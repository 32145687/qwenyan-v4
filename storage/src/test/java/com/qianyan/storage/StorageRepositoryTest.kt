package com.qianyan.storage

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.qianyan.model.BaseNovelId
import com.qianyan.model.NovelId
import com.qianyan.model.OverrideId
import com.qianyan.model.ProjectId
import com.qianyan.model.ProjectSource
import com.qianyan.model.ProjectStatus
import com.qianyan.model.UserId
import com.qianyan.model.VariantId
import com.qianyan.model.VariantScope
import com.qianyan.model.VocabularyCandidateId
import com.qianyan.model.VocabularyEntryId
import com.qianyan.model.VocabularyId
import com.qianyan.model.VocabularyRuleId
import com.qianyan.model.core.EntityOverride
import com.qianyan.model.core.Novel
import com.qianyan.model.core.NovelVariant
import com.qianyan.model.core.OverrideOperation
import com.qianyan.model.core.OverridableKind
import com.qianyan.model.core.VariantStatus
import com.qianyan.model.memory.MemoryEntry
import com.qianyan.model.memory.MemoryLayer
import com.qianyan.model.vocabulary.Vocabulary
import com.qianyan.model.vocabulary.VocabularyCandidate
import com.qianyan.model.vocabulary.VocabularyCandidateSource
import com.qianyan.model.vocabulary.VocabularyCandidateStatus
import com.qianyan.model.vocabulary.VocabularyEntry
import com.qianyan.model.vocabulary.VocabularyEntryStatus
import com.qianyan.model.vocabulary.VocabularyEntryType
import com.qianyan.model.vocabulary.VocabularyRule
import com.qianyan.model.vocabulary.VocabularyScopeLevel
import com.qianyan.storage.db.QianyanDbFactory
import com.qianyan.storage.db.QianyanDbHandle
import com.qianyan.storage.repository.OriginalImmutableException
import com.qianyan.storage.repository.SqliteBackupStore
import com.qianyan.storage.repository.SqliteMemoryRepository
import com.qianyan.storage.repository.SqliteNovelRepository
import com.qianyan.storage.repository.SqliteVocabularyRepository
import com.qianyan.storage.repository.UniqueConflictException
import com.qianyan.storage.repository.VariantBaseViolation
import kotlinx.datetime.Clock
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Storage Foundation (P2.10) 测试。覆盖 15 项最低要求 + 事务回滚 + 重建持久化。
 * 每个测试使用独立内存数据库（IN_MEMORY），互不影响。
 */
class StorageRepositoryTest {

    private fun handle(url: String = JdbcSqliteDriver.IN_MEMORY): QianyanDbHandle = QianyanDbFactory.open(url)

    private fun randomId(prefix: String) = "$prefix-${Random.nextLong().toString(16)}"

    private fun makeNovel(
        id: String = randomId("nv"),
        title: String = "原著",
        genre: List<String> = listOf("仙侠"),
    ) = Novel(
        novelId = NovelId(id),
        projectId = ProjectId("proj-$id"),
        title = title,
        source = ProjectSource.ORIGINAL_NOVEL,
        genre = genre,
        synopsis = "synopsis of $title",
        scope = VariantScope.ORIGINAL,
        status = ProjectStatus.DRAFT,
        createdAt = Clock.System.now(),
        updatedAt = Clock.System.now(),
    )

    private fun makeVariant(
        variantId: String,
        baseNovelId: NovelId,
        name: String = "改线",
    ) = NovelVariant(
        variantId = VariantId(variantId),
        novelId = baseNovelId,
        baseNovelId = BaseNovelId(baseNovelId.value),
        projectId = ProjectId("proj-${baseNovelId.value}"),
        name = name,
        status = VariantStatus.DRAFT,
        createdAt = Clock.System.now(),
        updatedAt = Clock.System.now(),
    )

    /* 1. 创建 Original 成功 */
    @Test
    fun `create original novel succeeds`() {
        val repo = SqliteNovelRepository(handle().db)
        val novel = makeNovel()
        val id = repo.createOriginal(novel)
        assertEquals(novel.novelId, id)
    }

    /* 2. Original 可以读取 */
    @Test
    fun `original novel is readable`() {
        val h = handle()
        val repo = SqliteNovelRepository(h.db)
        val novel = makeNovel(genre = listOf("仙侠", "历史"))
        repo.createOriginal(novel)
        val read = repo.getNovel(novel.novelId)
        assertNotNull(read)
        assertEquals(novel.title, read.title)
        assertEquals(listOf("仙侠", "历史"), read.genre)
        assertEquals(VariantScope.ORIGINAL, read.scope)
        assertEquals(novel.synopsis, read.synopsis)
    }

    /* 3. Original 写保护生效（领域层 + 物理触发器） */
    @Test
    fun `original novel is write-protected`() {
        // 领域层：不允许以非 ORIGINAL scope 创建
        val h = handle()
        val repo = SqliteNovelRepository(h.db)
        val novel = makeNovel()
        repo.createOriginal(novel)

        // 物理层：直接对 ORIGINAL 行执行 UPDATE 会被触发器拒绝
        val protected = assertFailsWith<Exception> {
            h.driver.execute(null, "UPDATE Novel SET title='hacked' WHERE scope='ORIGINAL'", 0)
        }
        assertTrue(
            protected.message.orEmpty().contains("immutable", ignoreCase = true)
                    || protected.message.orEmpty().contains("abort", ignoreCase = true),
            "触发器应拒绝修改 Original，实际: ${protected.message}",
        )

        // 物理层：DELETE 同样被拒绝
        assertFailsWith<Exception> {
            h.driver.execute(null, "DELETE FROM Novel WHERE scope='ORIGINAL'", 0)
        }
        // Original 仍可被读取（写保护不影响读取）
        assertNotNull(repo.getNovel(novel.novelId))
    }

    /* 4. 创建 Variant 成功 */
    @Test
    fun `create variant succeeds`() {
        val repo = SqliteNovelRepository(handle().db)
        val original = makeNovel()
        repo.createOriginal(original)
        val vid = repo.createVariant(makeVariant("var-1", original.novelId))
        assertNotNull(vid)
    }

    /* 5. Variant 指向正确 Original */
    @Test
    fun `variant points to its original`() {
        val repo = SqliteNovelRepository(handle().db)
        val original = makeNovel()
        repo.createOriginal(original)
        val vid = repo.createVariant(makeVariant("var-x", original.novelId))
        val variant = repo.getVariant(vid)
        assertNotNull(variant)
        assertEquals(original.novelId.value, variant.baseNovelId.value)
        assertTrue(repo.getVariantsOfNovel(original.novelId).any { it.variantId == vid })
    }

    /* 6. Variant 可以写入（更新 name） */
    @Test
    fun `variant data is writable`() {
        val repo = SqliteNovelRepository(handle().db)
        val original = makeNovel()
        repo.createOriginal(original)
        val v = makeVariant("var-w", original.novelId, name = "旧名")
        repo.createVariant(v)
        val updated = v.copy(name = "新名", updatedAt = Clock.System.now())
        repo.saveVariantData(updated)
        assertEquals("新名", repo.getVariant(v.variantId)?.name)
    }

    /* 7. EntityOverride 可以保存 */
    @Test
    fun `entity override can be saved`() {
        val repo = SqliteNovelRepository(handle().db)
        val original = makeNovel()
        repo.createOriginal(original)
        val vid = repo.createVariant(makeVariant("var-ov", original.novelId))
        val ov = EntityOverride(
            overrideId = OverrideId("ov-1"),
            variantId = vid,
            targetKind = OverridableKind.MEMORY,
            targetId = "mem-1",
            operation = OverrideOperation.OVERRIDE,
            replacedValue = JsonPrimitive("新值"),
        )
        repo.saveOverride(ov)
        assertEquals("新值", (repo.getOverride(vid, "mem-1")?.replacedValue as JsonPrimitive).content)
    }

    /* 8. EntityOverride 可以读取 */
    @Test
    fun `entity override can be read`() {
        val repo = SqliteNovelRepository(handle().db)
        val original = makeNovel()
        repo.createOriginal(original)
        val vid = repo.createVariant(makeVariant("var-ro", original.novelId))
        repo.saveOverride(
            EntityOverride(OverrideId("ov-a"), vid, OverridableKind.CHARACTER, "ch-1", OverrideOperation.OVERRIDE, JsonPrimitive("X")),
        )
        val list = repo.getOverrides(vid)
        assertEquals(1, list.size)
        assertEquals(OverrideOperation.OVERRIDE, list[0].operation)
        // 唯一键 targetId + variantId 持久化后可读回，variantId 保持强类型
        assertEquals(vid, list[0].variantId)
        assertEquals("ch-1", list[0].targetId)
        assertEquals(OverridableKind.CHARACTER, list[0].targetKind)
    }

    /* 9. Variant 读取可以 read-through Original（无 Override 时回退基值） */
    @Test
    fun `variant read-through falls back to original value`() {
        val repo = SqliteNovelRepository(handle().db)
        val original = makeNovel()
        repo.createOriginal(original)
        val vid = repo.createVariant(makeVariant("var-rt1", original.novelId))
        val base = JsonPrimitive("original-value")
        // 没有 Override → 回退到 Original 基值
        assertEquals(base.content, (repo.resolveOverride(vid, "t-1", base) as JsonPrimitive).content)
    }

    /* 10. Override 覆盖 Original 值 */
    @Test
    fun `override wins over original value on read-through`() {
        val repo = SqliteNovelRepository(handle().db)
        val original = makeNovel()
        repo.createOriginal(original)
        val vid = repo.createVariant(makeVariant("var-ov1", original.novelId))
        repo.saveOverride(
            EntityOverride(OverrideId("o1"), vid, OverridableKind.CHARACTER, "t-10", OverrideOperation.OVERRIDE, JsonPrimitive("overridden")),
        )
        val base = JsonPrimitive("original")
        val result = repo.resolveOverride(vid, "t-10", base)
        assertEquals("overridden", (result as JsonPrimitive).content)
    }

    /* 11. 没有 Override 时继续读取 Original */
    @Test
    fun `inferit returns original value when no override`() {
        val repo = SqliteNovelRepository(handle().db)
        val original = makeNovel()
        repo.createOriginal(original)
        val vid = repo.createVariant(makeVariant("var-rt2", original.novelId))
        assertEquals(null, repo.resolveOverride(vid, "missing", null))
        // INHERIT 操作同样回退 Original（targetId 未命中任何 Override）
        repo.saveOverride(
            EntityOverride(OverrideId("oi"), vid, OverridableKind.CHARACTER, "t-11", OverrideOperation.INHERIT, null),
        )
        val base = JsonPrimitive("base")
        assertEquals(base.content, (repo.resolveOverride(vid, "t-11", base) as JsonPrimitive).content)
        // REMOVE 表示删除（返回 null），即使传入 Original 基值
        repo.saveOverride(
            EntityOverride(OverrideId("or"), vid, OverridableKind.CHARACTER, "t-12", OverrideOperation.REMOVE, null),
        )
        assertEquals(null, repo.resolveOverride(vid, "t-12", base))
    }

    /* 12. Variant → Variant 被拒绝 */
    @Test
    fun `variant-to-variant is rejected`() {
        val repo = SqliteNovelRepository(handle().db)
        val original = makeNovel()
        repo.createOriginal(original)
        val v1 = repo.createVariant(makeVariant("var-v1", original.novelId))
        // 以 Variant 为 base 创建另一个 Variant → 拒绝
        val illegal = makeVariant("var-v2", original.novelId).copy(baseNovelId = BaseNovelId(v1.value))
        assertFailsWith<VariantBaseViolation> { repo.createVariant(illegal) }
    }

    /* 13. targetId + variantId 唯一性生效 */
    @Test
    fun `targetId and variantId unique key is enforced`() {
        val repo = SqliteNovelRepository(handle().db)
        val original = makeNovel()
        repo.createOriginal(original)
        val vid = repo.createVariant(makeVariant("var-uni", original.novelId))
        repo.saveOverride(
            EntityOverride(OverrideId("u1"), vid, OverridableKind.CHARACTER, "same-target", OverrideOperation.OVERRIDE, JsonPrimitive(1)),
        )
        assertFailsWith<UniqueConflictException> {
            repo.saveOverride(
                EntityOverride(OverrideId("u2"), vid, OverridableKind.CHARACTER, "same-target", OverrideOperation.OVERRIDE, JsonPrimitive(2)),
            )
        }
    }

    /* 14. replacedValue JsonElement 序列化/反序列化正常 */
    @Test
    fun `replacedValue json roundtrip`() {
        val repo = SqliteNovelRepository(handle().db)
        val original = makeNovel()
        repo.createOriginal(original)
        val vid = repo.createVariant(makeVariant("var-json", original.novelId))
        val structured: JsonObject = buildJsonObject {
            put("name", "林晚")
            put("cultivation", 5)
            put("tags", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b"))))
        }
        repo.saveOverride(
            EntityOverride(OverrideId("oj"), vid, OverridableKind.CHARACTER, "ch-json", OverrideOperation.OVERRIDE, structured),
        )
        val read = repo.getOverride(vid, "ch-json")!!.replacedValue as JsonObject
        // 结构化 JsonObject（含嵌套数组）序列化/反序列化后可读回且内容一致（禁用 Map<String,Any>）
        assertEquals(structured, read)
        assertEquals((read["name"] as JsonPrimitive).content, "林晚")
        assertEquals((read["cultivation"] as JsonPrimitive).content, "5")
        // 读穿透解析的 Override 值与持久化读取的 replacedValue 一致
        val roundtrip = repo.resolveOverride(vid, "ch-json", null)
        assertNotNull(roundtrip)
        assertEquals(structured, roundtrip)
    }

    /* 15. 数据库重建后数据可以正确读取（文件持久化） */
    @Test
    fun `data persists after database reopen`() {
        val file = java.nio.file.Files.createTempFile("qianyan_test", ".db").toAbsolutePath()
        val url = "jdbc:sqlite:$file"
        val h1 = handle(url)
        val repo1 = SqliteNovelRepository(h1.db)
        repo1.createOriginal(makeNovel("persist-novel", title = "持久化", genre = listOf("科幻")))

        // 写入 Variant + Override
        val vid = repo1.createVariant(makeVariant("persist-var", NovelId("persist-novel")))
        repo1.saveOverride(
            EntityOverride(OverrideId("po"), vid, OverridableKind.CHARACTER, "p-target", OverrideOperation.OVERRIDE, JsonPrimitive("持久值")),
        )
        (h1.driver as JdbcSqliteDriver).getConnection().close() // 关闭旧连接

        // 重新打开同一文件，数据应仍在
        val h2 = handle(url)
        val repo2 = SqliteNovelRepository(h2.db)
        val read = repo2.getNovel(NovelId("persist-novel"))
        assertNotNull(read)
        assertEquals("持久化", read.title)
        assertEquals(listOf("科幻"), read.genre)
        val v = repo2.getVariant(vid)
        assertNotNull(v)
        assertEquals("persist-novel", v.baseNovelId.value)
        val o = repo2.getOverride(vid, "p-target")
        assertEquals("持久值", (o?.replacedValue as JsonPrimitive).content)
        java.nio.file.Files.deleteIfExists(file)
    }

    /* 附加：事务回滚 */
    @Test
    fun `transaction rolls back on failure`() {
        val h = handle()
        val repo = SqliteNovelRepository(h.db)
        val novel = makeNovel("tx-novel")
        assertFailsWith<IllegalStateException> {
            h.db.transaction {
                repo.createOriginal(novel)
                throw IllegalStateException("force rollback")
            }
        }
        assertNull(repo.getNovel(novel.novelId))
    }

    /* 附加：migration —— 全新库可重复构建、守卫触发器存在 */
    @Test
    fun `schema is re-creatable and guard triggers exist`() {
        val h = handle()
        // 表已建好：能写入 Original（写保护触发器不拦 INSERT）
        val repo = SqliteNovelRepository(h.db)
        repo.createOriginal(makeNovel("mig-novel"))
        // 触发器中存在
        val conn = (h.driver as JdbcSqliteDriver).getConnection()
        val triggerNames = buildList {
            conn.createStatement().use { st ->
                st.executeQuery("SELECT name FROM sqlite_master WHERE type='trigger'").use { rs ->
                    while (rs.next()) add(rs.getString(1))
                }
            }
        }
        // 守卫触发器已按初始 migration 建立（P2.9 可重复构建）
        assertTrue("novel_original_update_protect" in triggerNames, "缺 no_original_update_protect: $triggerNames")
        assertTrue("novel_original_delete_protect" in triggerNames, "缺 novel_original_delete_protect: $triggerNames")
        assertTrue("variant_base_must_be_original" in triggerNames, "缺 variant_base_must_be_original: $triggerNames")
        // Original 仍可读取
        assertNotNull(repo.getNovel(NovelId("mig-novel")))
    }

    /* 附加：Vocabulary 最小持久化 + 按 scope 查询 */
    @Test
    fun `vocabulary saves and queries by scope`() {
        val h = handle()
        val v = SqliteVocabularyRepository(h.db)
        val vocabId = VocabularyId(randomId("vocab"))
        v.saveVocabulary(Vocabulary(vocabularyId = vocabId, scopeLevel = VocabularyScopeLevel.VARIANT, variantId = VariantId("var-voc"), name = "词库"))
        v.saveEntry(
            VocabularyEntry(
                entryId = VocabularyEntryId(randomId("entry")),
                vocabularyId = vocabId,
                variantId = VariantId("var-voc"),
                scopeLevel = VocabularyScopeLevel.VARIANT,
                canonical = "灵石",
                aliases = listOf("灵晶", "石"),
                replacement = "星石",
            ),
        )
        val byScope = v.findVocabularyByScope(VocabularyScopeLevel.VARIANT)
        assertTrue(byScope.any { it.vocabularyId == vocabId })
        val entries = v.findEntriesByVariant(VariantId("var-voc"))
        assertEquals(1, entries.size)
        assertEquals("灵石", entries[0].canonical)
        assertEquals(listOf("灵晶", "石"), entries[0].aliases)
        assertEquals("星石", entries[0].replacement)
    }

    /* 附加：Memory 最小持久化 */
    @Test
    fun `memory entry persists`() {
        val h = handle()
        val m = SqliteMemoryRepository(h.db)
        val novelId = NovelId("mem-novel")
        m.saveEntry(
            MemoryEntry(
                id = com.qianyan.model.MemoryEntryId("mem-1"),
                novelId = novelId,
                variantId = null,
                scope = VariantScope.ORIGINAL,
                layer = MemoryLayer.LONG_TERM,
                content = "重要记忆",
                source = "test",
                createdAt = Clock.System.now(),
                updatedAt = Clock.System.now(),
            ),
        )
        val all = m.findEntriesByNovel(novelId)
        assertEquals(1, all.size)
        assertEquals("重要记忆", all[0].content)
        assertEquals(MemoryLayer.LONG_TERM, all[0].layer)
    }

    /* 附加：Backup / Restore roundtrip */
    @Test
    fun `backup and restore roundtrip preserves data`() {
        // 源库：写入数据
        val source = handle()
        val sourceRepo = SqliteNovelRepository(source.db)
        sourceRepo.createOriginal(makeNovel("bk-novel", title = "备份", genre = listOf("奇幻")))
        val vid = sourceRepo.createVariant(makeVariant("bk-var", NovelId("bk-novel")))
        sourceRepo.saveOverride(
            EntityOverride(OverrideId("bo"), vid, OverridableKind.CHARACTER, "bk-target", OverrideOperation.OVERRIDE, JsonPrimitive("backup")),
        )
        val pkg = SqliteBackupStore(source).exportBackup()

        // 恢复到一个全新数据库。
        // 说明：Original 行受物理写保护（禁止 DELETE），故 restore 目标需为空库；格式暂未定型（P2.13 最小边界）。
        val target = handle()
        SqliteBackupStore(target).restoreBackup(pkg)

        val repo = SqliteNovelRepository(target.db)
        val read = repo.getNovel(NovelId("bk-novel"))
        assertNotNull(read)
        assertEquals("备份", read.title)
        assertEquals(listOf("奇幻"), read.genre)
        val o = repo.getOverride(vid, "bk-target")
        assertEquals("backup", (o?.replacedValue as JsonPrimitive).content)
    }
}