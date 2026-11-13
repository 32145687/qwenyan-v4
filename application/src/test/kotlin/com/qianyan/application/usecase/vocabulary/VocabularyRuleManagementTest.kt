package com.qianyan.application.usecase.vocabulary

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.qianyan.application.di.ApplicationContainer
import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.model.vocabulary.VocabularyRuleSkipReason
import com.qianyan.model.vocabulary.VocabularyScopeLevel
import com.qianyan.provider.ChatMessage
import com.qianyan.provider.ChatRole
import com.qianyan.provider.FinishReason
import com.qianyan.provider.ProviderResponse
import com.qianyan.provider.Usage
import com.qianyan.provider.impl.MockLLMGateway
import com.qianyan.storage.db.QianyanDbFactory
import com.qianyan.storage.db.QianyanDbHandle
import java.nio.file.Files
import kotlin.io.path.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * T-O37-B · 词库规则管理（创建期校验 / 启停 / 持久 / 引擎生效）用例。
 *
 * 断言边界：
 *  - 创建期四条拒绝（空原词 / 同形 / 非幂等 / 重复）都是**类型化** [ApplicationError.InvalidOperation]，
 *    且重复不得静默覆盖既有规则；
 *  - `createRule` 固定 NOVEL 层、`deterministicOnly = true`、`enabled = true`；
 *  - 启停只改 `enabled`，经仓储 round-trip（关库重开）后仍保持；
 *  - 停用后规则**仍在** `activeFor` 候选集中（A/A2 契约：不静默过滤），由 [VocabularyRuleEngine]
 *    记为 [VocabularyRuleSkipReason.DISABLED] 而**不执行**；重新启用后恢复替换。
 */
class VocabularyRuleManagementTest {

    /* ---------- 创建期校验 ---------- */

    @Test
    fun `blank from is rejected`() {
        val app = container()
        val novelId = app.novels.createOriginal(title = "规则校验书")

        val ex = assertFailsWith<ApplicationException> { app.vocabularyRules.createRule(novelId, "   ", "老村长") }

        assertIs<ApplicationError.InvalidOperation>(ex.error)
        assertTrue(app.vocabularyRules.listRules(novelId).isEmpty(), "校验失败不得落库")
    }

    @Test
    fun `same from and to is rejected`() {
        val app = container()
        val novelId = app.novels.createOriginal(title = "规则校验书")

        val ex = assertFailsWith<ApplicationException> { app.vocabularyRules.createRule(novelId, "村长", "村长") }

        assertIs<ApplicationError.InvalidOperation>(ex.error)
        assertTrue(app.vocabularyRules.listRules(novelId).isEmpty())
    }

    @Test
    fun `to containing from is rejected`() {
        val app = container()
        val novelId = app.novels.createOriginal(title = "规则校验书")

        val ex = assertFailsWith<ApplicationException> { app.vocabularyRules.createRule(novelId, "村长", "老村长") }

        assertIs<ApplicationError.InvalidOperation>(ex.error)
        assertTrue(ex.message!!.contains("包含原词"), "错误需可读：${ex.message}")
        assertTrue(app.vocabularyRules.listRules(novelId).isEmpty())
    }

    @Test
    fun `duplicate from is rejected without overwriting`() {
        val app = container()
        val novelId = app.novels.createOriginal(title = "规则校验书")
        app.vocabularyRules.createRule(novelId, "村长", "里正")

        val ex = assertFailsWith<ApplicationException> { app.vocabularyRules.createRule(novelId, " 村长 ", "甲长") }

        assertIs<ApplicationError.InvalidOperation>(ex.error)
        val rules = app.vocabularyRules.listRules(novelId)
        assertEquals(1, rules.size, "重复原词不得新增第二条")
        assertEquals("里正", rules.single().to, "既有规则不得被覆盖")
    }

    /* ---------- 创建成功 / 启停 ---------- */

    @Test
    fun `create rule persists novel scoped deterministic rule`() {
        val app = container()
        val novelId = app.novels.createOriginal(title = "规则创建书")

        val created = app.vocabularyRules.createRule(novelId, "  村长  ", "里正")

        assertEquals("村长", created.from, "from 需 trim")
        assertEquals("里正", created.to)
        assertEquals(novelId, created.novelId)
        assertEquals(null, created.variantId)
        assertEquals(VocabularyScopeLevel.NOVEL, created.scopeLevel)
        assertTrue(created.enabled, "新建默认启用")
        assertTrue(created.deterministicOnly, "规则必须确定性")
        assertEquals("novel-vocab-${novelId.value}", created.vocabularyId.value, "复用既有 NOVEL 词库容器")
        assertEquals(listOf(created), app.vocabularyRules.listRules(novelId))
    }

    @Test
    fun `set rule enabled toggles persisted flag`() {
        val app = container()
        val novelId = app.novels.createOriginal(title = "规则启停书")
        val created = app.vocabularyRules.createRule(novelId, "村长", "里正")

        app.vocabularyRules.setRuleEnabled(created.ruleId, false)

        assertFalse(app.vocabularyRules.listRules(novelId).single().enabled)
        app.vocabularyRules.setRuleEnabled(created.ruleId, true)
        assertTrue(app.vocabularyRules.listRules(novelId).single().enabled)
    }

    /* ---------- 持久（仓储 round-trip：关库重开） ---------- */

    @Test
    fun `rule enabled state survives reopen`() {
        val tmp = Files.createTempFile("qianyan-rule", ".db").toString()
        val handles = mutableListOf<QianyanDbHandle>()
        try {
            var app = openFile("jdbc:sqlite:$tmp", handles)
            val novelId = app.novels.createOriginal(title = "规则持久书")
            val created = app.vocabularyRules.createRule(novelId, "村长", "里正")
            app.vocabularyRules.setRuleEnabled(created.ruleId, false)

            app = openFile("jdbc:sqlite:$tmp", handles)
            val reopened = app.vocabularyRules.listRules(novelId).single()
            assertEquals(created.ruleId, reopened.ruleId)
            assertEquals("村长", reopened.from)
            assertEquals("里正", reopened.to)
            assertFalse(reopened.enabled, "停用状态必须持久")
        } finally {
            handles.forEach { (it.driver as JdbcSqliteDriver?)?.getConnection()?.close() }
            Files.deleteIfExists(Path(tmp))
        }
    }

    /* ---------- 接线：停用不生效（引擎层判定，不静默过滤） ---------- */

    @Test
    fun `disabled rule stays visible but engine skips it`() {
        val app = container()
        val novelId = app.novels.createOriginal(title = "规则生效书")
        val created = app.vocabularyRules.createRule(novelId, "村长", "里正")

        val enabledResult = VocabularyRuleEngine.apply("村长来了", app.vocabularyRules.activeFor(novelId, null))
        assertEquals("里正来了", enabledResult.content)
        assertEquals(1, enabledResult.hits.single().count)

        app.vocabularyRules.setRuleEnabled(created.ruleId, false)

        val candidates = app.vocabularyRules.activeFor(novelId, null)
        assertEquals(listOf(created.ruleId), candidates.map { it.ruleId }, "停用规则仍在候选集（可见），由引擎记录跳过")
        val disabledResult = VocabularyRuleEngine.apply("村长来了", candidates)
        assertEquals("村长来了", disabledResult.content, "停用规则不得改写正文")
        assertTrue(disabledResult.hits.isEmpty())
        assertEquals(VocabularyRuleSkipReason.DISABLED, disabledResult.skipped.single().reason)
    }

    /* ---------- fixtures ---------- */

    private fun container(): ApplicationContainer =
        ApplicationContainer.open(analysisGateway = MockLLMGateway { _ ->
            ProviderResponse(
                message = ChatMessage(ChatRole.ASSISTANT, """{"answer":"{}"}"""),
                usage = Usage(1, 1, 2),
                finishReason = FinishReason.STOP,
            )
        })

    private fun openFile(url: String, handles: MutableList<QianyanDbHandle>): ApplicationContainer {
        val handle = QianyanDbFactory.open(url)
        handles += handle
        return ApplicationContainer.fromDriver(
            handle.driver,
            analysisGateway = MockLLMGateway { _ ->
                ProviderResponse(
                    message = ChatMessage(ChatRole.ASSISTANT, """{"answer":"{}"}"""),
                    usage = Usage(1, 1, 2),
                    finishReason = FinishReason.STOP,
                )
            },
        )
    }
}
