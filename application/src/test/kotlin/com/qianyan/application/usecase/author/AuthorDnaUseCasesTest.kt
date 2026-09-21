package com.qianyan.application.usecase.author

import com.qianyan.application.di.ApplicationContainer
import com.qianyan.application.error.ApplicationException
import com.qianyan.engine.txt.TxtSource
import com.qianyan.model.AuthorProfileId
import com.qianyan.model.TxtDocumentId
import com.qianyan.model.author.AuthorDnaFeatureStatus
import com.qianyan.provider.ChatMessage
import com.qianyan.provider.ChatRole
import com.qianyan.provider.FinishReason
import com.qianyan.provider.ProviderResponse
import com.qianyan.provider.Usage
import com.qianyan.provider.impl.MockLLMGateway
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * P18-C · AuthorDnaUseCases（application）：TXT → Analysis → AuthorDNA。
 * 覆盖：taxonomy / confidence / source / source binding / version / confirm-reject / multi-source / AuthorContext。
 * 全程经 ApplicationContainer（authorDnaRepository=SqliteAuthorDnaRepository），不触碰 Sqlite 细节。
 */
class AuthorDnaUseCasesTest {

    private fun source(text: String): TxtSource =
        TxtSource(text.toByteArray(StandardCharsets.UTF_8), "probe.txt")

    private val validText = "第一章\n\n对话一。\n\n对话二。\n\n第二章\n\n描写一。"

    private fun response(json: String): ProviderResponse = ProviderResponse(
        message = ChatMessage(ChatRole.ASSISTANT, json),
        usage = Usage(promptTokens = 1, completionTokens = 1, totalTokens = 2),
        finishReason = FinishReason.STOP,
    )

    private val standardFeaturesJson =
        "{\"features\":[" +
            "{\"dimension\":\"DIALOGUE\",\"featureKey\":\"dialogue.density\",\"value\":\"high\",\"statement\":\"对话占比较高\",\"confidence\":0.8}," +
            "{\"dimension\":\"SENTENCE\",\"featureKey\":\"sentence.rhythm\",\"value\":\"short\",\"statement\":\"短句为主\",\"confidence\":0.5}" +
            "]}"

    private fun app(json: String = standardFeaturesJson): ApplicationContainer =
        ApplicationContainer.open(analysisGateway = MockLLMGateway { response(json) })

    private fun import(app: ApplicationContainer, text: String = validText): TxtDocumentId {
        val out = app.txts.importTxtAsOriginal(source(text), title = "t")
        return TxtDocumentId(out.documentId.value)
    }

    private fun author(app: ApplicationContainer): AuthorProfileId =
        app.authorIntelligenceGateway.getOrCreateAuthorProfile().profileId

    // ---------------- taxonomy ----------------

    @Test
    fun `valid dimensions accepted`() {
        val app = app()
        val a = author(app)
        val doc = import(app)
        val dna = app.authorDnaUseCases.analyze(a, listOf(doc))
        assertTrue(dna.features.isNotEmpty())
        assertEquals(listOf("DIALOGUE", "SENTENCE"), dna.features.map { it.dimension.name })
    }

    @Test
    fun `invalid dimension rejected`() {
        val app = app("{\"features\":[{\"dimension\":\"METAPHOR\",\"featureKey\":\"x\",\"value\":\"v\",\"statement\":\"s\"}]}")
        val a = author(app)
        val doc = import(app)
        val dna = app.authorDnaUseCases.analyze(a, listOf(doc))
        assertEquals(0, dna.features.size, "未受控 dimension 应被过滤")
    }

    @Test
    fun `empty featureKey and value and statement rejected`() {
        val app = app(
            "{\"features\":[" +
                "{\"dimension\":\"POV\",\"featureKey\":\"\",\"value\":\"v\",\"statement\":\"s\"}," +
                "{\"dimension\":\"POV\",\"featureKey\":\"pov.k\",\"value\":\"\",\"statement\":\"s\"}," +
                "{\"dimension\":\"POV\",\"featureKey\":\"pov.k2\",\"value\":\"v\",\"statement\":\"\"}" +
                "]}",
        )
        val a = author(app)
        val doc = import(app)
        val dna = app.authorDnaUseCases.analyze(a, listOf(doc))
        assertEquals(0, dna.features.size, "空 featureKey/value/statement 均应被拒绝")
    }

    // ---------------- confidence ----------------

    @Test
    fun `llm confidence accepted and clamped to 0_9`() {
        // confidence 0.8 保留；1.5 非法 → fallback 0.5；缺失 → 0.5
        val app = app(
            "{\"features\":[" +
                "{\"dimension\":\"SENTENCE\",\"featureKey\":\"a\",\"value\":\"v\",\"statement\":\"s\",\"confidence\":0.8}," +
                "{\"dimension\":\"ACTION\",\"featureKey\":\"b\",\"value\":\"v\",\"statement\":\"s\",\"confidence\":1.5}," +
                "{\"dimension\":\"EMOTION\",\"featureKey\":\"c\",\"value\":\"v\",\"statement\":\"s\"}" +
                "]}",
        )
        val a = author(app)
        val doc = import(app)
        val dna = app.authorDnaUseCases.analyze(a, listOf(doc))
        val byKey = dna.features.associateBy { it.featureKey }
        assertEquals(0.8, byKey.getValue("a").confidence.value)
        assertEquals(0.5, byKey.getValue("b").confidence.value, "非法 LLM confidence 回退 0.5")
        assertEquals(0.5, byKey.getValue("c").confidence.value, "缺失 LLM confidence 回退 0.5")
        assertTrue(dna.features.all { it.confidence.value <= 0.9 })
    }

    // ---------------- source & binding ----------------

    @Test
    fun `invalid source refused on register`() {
        val app = app()
        val a = author(app)
        // 空文本 → TxtParseStatus.SUCCESS 但正文空？导入会失败；改为：不存在的 document 应抛 EntityNotFound。
        assertFailsWith<ApplicationException> {
            app.authorDnaUseCases.registerSource(a, TxtDocumentId("missing"))
        }
    }

    @Test
    fun `source binding comes from program not llm`() {
        // LLM 试图伪造 location 字段，但 parser 只读 dimension/featureKey/value/statement/confidence → 忽略 location。
        val app = app(
            "{\"features\":[{\"dimension\":\"DIALOGUE\",\"featureKey\":\"d\",\"value\":\"v\",\"statement\":\"s\",\"location\":\"FAKE:999-1000\"}]}",
        )
        val a = author(app)
        val doc = import(app)
        val dna = app.authorDnaUseCases.analyze(a, listOf(doc))
        val f = dna.features.single()
        assertEquals(1, f.sourceRefs.size)
        val ref = f.sourceRefs.first()
        assertEquals(doc.value, ref.sourceId.value)
        assertEquals(0, ref.sourceStart)
        assertTrue(ref.sourceEnd > 0, "sourceEnd 由程序绑定（文档实数范围），非 LLM")
    }

    // ---------------- version ----------------

    @Test
    fun `rebuild creates new version and old version preserved`() {
        val app = app()
        val a = author(app)
        val doc = import(app)
        app.authorDnaUseCases.analyze(a, listOf(doc))
        val v1 = app.authorDnaUseCases.viewActive(a)!!
        // 再 register + rebuild → 新 version
        app.authorDnaUseCases.registerSource(a, doc)
        val v2 = app.authorDnaUseCases.rebuild(a)!!
        assertTrue(v2.version > v1.version, "rebuild 应产生更高 version")
        val versions = app.authorDnaUseCases.listVersions(a)
        assertEquals(2, versions.size)
        assertEquals(1, versions.count { it.status.name == "SUPERSEDED" }, "旧 ACTIVE → SUPERSEDED")
    }

    @Test
    fun `new version features default to candidate`() {
        val app = app()
        val a = author(app)
        val doc = import(app)
        val dna = app.authorDnaUseCases.analyze(a, listOf(doc))
        assertTrue(dna.features.all { it.status == AuthorDnaFeatureStatus.CANDIDATE })
    }

    // ---------------- confirm / reject ----------------

    @Test
    fun `confirm moves candidate to active without modifying value`() {
        val app = app()
        val a = author(app)
        val doc = import(app)
        val dna = app.authorDnaUseCases.analyze(a, listOf(doc))
        val fid = dna.features.first().featureId
        val before = dna.features.first()
        app.authorDnaGateway.confirmFeature(fid)
        val after = app.authorDnaUseCases.viewActive(a)!!.features.first { it.featureId == fid }
        assertEquals(AuthorDnaFeatureStatus.ACTIVE, after.status)
        assertEquals(before.value, after.value, "confirm 不修改 value")
        assertEquals(before.confidence.value, after.confidence.value, "confirm 不修改 confidence")
    }

    @Test
    fun `confirm and reject only from candidate`() {
        val app = app()
        val a = author(app)
        val doc = import(app)
        val dna = app.authorDnaUseCases.analyze(a, listOf(doc))
        val fid = dna.features.first().featureId
        app.authorDnaGateway.confirmFeature(fid)
        // 已 ACTIVE，不可再 confirm/reject
        assertFailsWith<ApplicationException> { app.authorDnaGateway.confirmFeature(fid) }
        assertFailsWith<ApplicationException> { app.authorDnaGateway.rejectFeature(fid) }
    }

    @Test
    fun `reject only affects current version and rebuild can recreate`() {
        val app = app()
        val a = author(app)
        val doc = import(app)
        val dna1 = app.authorDnaUseCases.analyze(a, listOf(doc))
        val rejectedFid = dna1.features.first().featureId
        app.authorDnaGateway.rejectFeature(rejectedFid)
        assertEquals(
            AuthorDnaFeatureStatus.REJECTED,
            app.authorDnaGateway.viewActive(a)!!.features.first { it.featureId == rejectedFid }.status,
        )
        // rebuild → 新 version 回到 CANDIDATE（非永久黑名单）
        val dna2 = app.authorDnaUseCases.rebuild(a)!!
        val newFid = dna2.features.first().featureId
        assertTrue(newFid != rejectedFid, "新 version 使用新 feature id")
        assertEquals(AuthorDnaFeatureStatus.CANDIDATE, dna2.features.first().status)
    }

    // ---------------- multi-source 等权 ----------------

    @Test
    fun `two valid sources contribute equally and deterministic order`() {
        var call = 0
        val gateway = MockLLMGateway { _ ->
            call++
            if (call <= 1) {
                response("{\"features\":[{\"dimension\":\"SENTENCE\",\"featureKey\":\"k\",\"value\":\"v\",\"statement\":\"s\",\"confidence\":0.6}]}")
            } else {
                response("{\"features\":[{\"dimension\":\"SENTENCE\",\"featureKey\":\"k\",\"value\":\"v\",\"statement\":\"s\",\"confidence\":0.8}]}")
            }
        }
        val app = ApplicationContainer.open(analysisGateway = gateway)
        val a = author(app)
        val doc1 = import(app, "第一章\n\n正文一。")
        val doc2 = import(app, "第一章\n\n正文二。")
        val dna = app.authorDnaUseCases.analyze(a, listOf(doc1, doc2))
        val f = dna.features.single()
        // 等权聚合取均值：(0.6 + 0.8) / 2 = 0.7
        assertEquals(0.7, f.confidence.value)
        assertEquals(2, f.sourceRefs.size, "两个 source 都参与、都绑定")
    }

    // ---------------- AuthorContext 只暴露 DnaLite ----------------

    @Test
    fun `author context exposes only confirmed dna lite`() {
        val app = app()
        val a = author(app)
        val doc = import(app)
        val dna = app.authorDnaUseCases.analyze(a, listOf(doc))
        // 未 confirm → DNA 不投影
        var ctx = app.authorContextProjection.project(null)
        assertEquals(0, ctx.dna.size, "CANDIDATE 不投影到 AuthorContext")

        val fid = dna.features.first().featureId
        app.authorDnaGateway.confirmFeature(fid)
        ctx = app.authorContextProjection.project(null)
        assertEquals(1, ctx.dna.size)
        val lite = ctx.dna.first()
        assertNotNull(lite.featureKey)
        assertNotNull(lite.statement)
        assertNotNull(lite.dimension)
        assertTrue(lite.sourceRef != null, "AuthorDnaLite 应携带最小 sourceRef")
    }

    @Test
    fun `authorId derives from profile not novel`() {
        val app = app()
        val a = author(app)
        val doc = import(app)
        val dna = app.authorDnaUseCases.analyze(a, listOf(doc))
        // dna 身份 = 作者 profile id；source 绑定的是 txt document（含其来源 novel 仅作 provenance）
        assertEquals(a, dna.authorId)
        assertEquals(1, dna.features.first().sourceRefs.size)
        assertEquals(doc.value, dna.features.first().sourceRefs.first().sourceId.value)
    }
}