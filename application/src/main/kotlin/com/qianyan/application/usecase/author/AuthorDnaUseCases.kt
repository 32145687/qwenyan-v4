package com.qianyan.application.usecase.author

import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.application.error.ErrorMapper
import com.qianyan.application.usecase.UseCase
import com.qianyan.engine.analysis.AnalysisInputBuilder
import com.qianyan.model.AuthorDnaFeatureId
import com.qianyan.model.AuthorDnaSourceId
import com.qianyan.model.AuthorDnaVersionId
import com.qianyan.model.AuthorProfileId
import com.qianyan.model.TxtDocumentId
import com.qianyan.model.author.AuthorDna
import com.qianyan.model.author.AuthorDnaFeature
import com.qianyan.model.author.AuthorDnaFeatureStatus
import com.qianyan.model.author.AuthorDnaSource
import com.qianyan.model.author.AuthorDnaSourceRef
import com.qianyan.model.author.AuthorDnaVersion
import com.qianyan.model.author.AuthorDnaVersionStatus
import com.qianyan.model.author.Confidence
import com.qianyan.model.author.DnaFeatureCandidate
import com.qianyan.storage.repository.AuthorDnaRepository
import com.qianyan.storage.repository.TxtRepository
import com.qianyan.provider.ChatMessage
import com.qianyan.provider.ChatRole
import com.qianyan.provider.LLMGateway
import com.qianyan.provider.ModelProfile
import com.qianyan.provider.ProviderRequest
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/**
 * Author DNA Use Cases（P18-C · DEC-P18C-001~015）。
 *
 * 职责：TXT → Analysis → Author DNA。Full Rebuild 每次产生新 version；LLM 只提出 transient 候选，
 * 程序负责 dimension/结构校验（[AuthorDnaParser]）、confidence 校正、source binding、authorId binding、版本生成与落库。
 *
 * 边界：
 *  - **DNA ≠ Evidence ≠ Core**：本 Use Case 绝不写 AuthorCore / AuthorObservation / Story 表（DEC-P18C-008）。
 *  - **scope = GLOBAL，identity = authorId（== author profile id）**：不引入 NovelId→authorId 混用（DEC-P18C-015）。
 *  - 多 TXT **等权**聚合（DEC-P18C-012）：每有效 source 贡献一份候选；同 dimension+featureKey 合并。
 *    失败 source（非 SUCCESS / 无正文）**排除但不删除**。
 *  - Confirm/Reject 仅作用于当前 version 的 Feature；Rebuild 产生新 version，默认全部 CANDIDATE（DEC-P18C-013）。
 */
class AuthorDnaUseCases(
    private val dnaRepository: AuthorDnaRepository,
    private val txtRepository: TxtRepository,
    private val inputBuilder: AnalysisInputBuilder = AnalysisInputBuilder,
    private val gateway: LLMGateway,
    private val model: ModelProfile = ModelProfile.MOCK,
    errorMapper: ErrorMapper,
) : UseCase(errorMapper) {

    private val confidenceCeiling: Double = AuthorCoreAggregator.CONFIDENCE_CEILING // 0.9（DEC-P18C-011）

    // ================= 来源管理 =================

    /** 把 TXT 注册为某 author 的 DNA 来源（幂等：同 documentId 覆盖自身）。 */
    fun registerSource(authorId: AuthorProfileId, documentId: TxtDocumentId): AuthorDnaSource {
        val doc = requireDocument(documentId)
        requireValidSource(authorId, documentId, doc.status, doc.normalizedText)
        val now = Clock.System.now()
        val existing = guard { dnaRepository.listAuthorDnaSources(authorId) }
            .firstOrNull { it.txtDocumentId == documentId }
        val source = existing?.copy(
            sourceNovelId = doc.novelId,
            contentHash = doc.contentHash,
            updatedAt = now,
        ) ?: AuthorDnaSource(
            sourceId = AuthorDnaSourceId(nextId()),
            authorId = authorId,
            txtDocumentId = documentId,
            sourceNovelId = doc.novelId,
            contentHash = doc.contentHash,
            createdAt = now,
            updatedAt = now,
        )
        guard { dnaRepository.upsertAuthorDnaSource(source) }
        return source
    }

    /** 列出某 author 的 DNA 来源。 */
    fun listSources(authorId: AuthorProfileId): List<AuthorDnaSource> =
        guard { dnaRepository.listAuthorDnaSources(authorId) }

    /** 删除一个 DNA 来源（只从 DNA 关联移除，不物理改 TXT）。 */
    fun deregisterSource(authorId: AuthorProfileId, sourceId: AuthorDnaSourceId) {
        guard { dnaRepository.deleteAuthorDnaSource(sourceId) }
    }

    // ================= Full Rebuild =================

    /**
     * Full Rebuild：重新分析该 author 全部**有效**来源 → 生成新 DNA version（旧 version → SUPERSEDED）。
     *
     * 流程：sources → 每份有效来源构造确定性 [AnalysisInput] → LLM → [AuthorDnaParser] 候选 →
     * 程序绑定 authorId/sourceRef（source 文档实数 [0,charCount)，非 LLM）→ 等权聚合 → confidence 校正 →
     * 新建 version + 落库 Feature（默认 CANDIDATE）。
     */
    fun rebuild(authorId: AuthorProfileId): AuthorDna {
        val sources = guard { dnaRepository.listAuthorDnaSources(authorId) }
        val now = Clock.System.now()

        // 1) 收集每份有效来源的分析候选（失败/无效来源排除但不删除）
        val aggregator = DnaAggregator()
        for (source in sources) {
            val doc = guard { txtRepository.getDocument(source.txtDocumentId) } ?: continue
            if (!isValidSource(doc.status, doc.normalizedText)) continue
            val analysis = analyzeDocument(source, doc)
            aggregator.add(analysis, source, doc)
        }

        // 2) 无有效来源 → EMPTY version
        if (aggregator.isEmpty) {
            return createVersion(authorId, status = AuthorDnaVersionStatus.EMPTY, emptyList(), now)
        }

        // 3) 等权聚合候选 → 校正 confidence → 版本化落库
        val features = aggregator.features().map { c ->
            AuthorDnaFeature(
                featureId = AuthorDnaFeatureId(nextId()),
                versionId = AuthorDnaVersionId(""), // 待版本创建后回填
                authorId = authorId,
                dimension = c.dimension,
                featureKey = c.featureKey,
                value = c.value,
                statement = c.statement,
                confidence = Confidence(c.confidence.coerceIn(0.0, confidenceCeiling)),
                status = AuthorDnaFeatureStatus.CANDIDATE,
                sourceRefs = c.sourceRefs,
                createdAt = now,
                updatedAt = now,
            )
        }
        return createVersion(authorId, status = AuthorDnaVersionStatus.ACTIVE, features, now)
    }

    /** 注册来源并立即 Full Rebuild。@return 新 ACTIVE version。 */
    fun analyze(authorId: AuthorProfileId, documentIds: List<TxtDocumentId>): AuthorDna {
        documentIds.forEach { registerSource(authorId, it) }
        return rebuild(authorId)
    }

    // ================= 视图 =================

    /** 当前 ACTIVE DNA（无 active → null）。 */
    fun viewActive(authorId: AuthorProfileId): AuthorDna? {
        val version = guard { dnaRepository.listAuthorDnaVersionsByStatus(authorId, AuthorDnaVersionStatus.ACTIVE) }
            .firstOrNull() ?: return null
        val features = guard { dnaRepository.listAuthorDnaFeatures(version.versionId) }
        return AuthorDna(
            versionId = version.versionId,
            authorId = version.authorId,
            version = version.version,
            status = version.status,
            ruleVersion = version.ruleVersion,
            features = features,
            createdAt = version.createdAt,
        )
    }

    /** 某 author 全部 DNA version（最新优先）。 */
    fun listVersions(authorId: AuthorProfileId): List<AuthorDnaVersion> =
        guard { dnaRepository.listAuthorDnaVersions(authorId) }

    // ================= Confirm / Reject（DEC-P18C-013） =================

    /** Confirm：CANDIDATE → ACTIVE。只认可现有分析结果，不改 value/statement/confidence/dimension/featureKey。 */
    fun confirmFeature(featureId: AuthorDnaFeatureId) {
        val f = requireFeature(featureId)
        if (f.status != AuthorDnaFeatureStatus.CANDIDATE) {
            throw ApplicationException(ApplicationError.InvalidOperation("只有 CANDIDATE Feature 可确认（当前 ${f.status}）"))
        }
        guard { dnaRepository.setAuthorDnaFeatureStatus(featureId, AuthorDnaFeatureStatus.ACTIVE) }
    }

    /** Reject：CANDIDATE → REJECTED。仅作用于当前 version；下次 Rebuild 重进 CANDIDATE（非永久黑名单）。 */
    fun rejectFeature(featureId: AuthorDnaFeatureId) {
        val f = requireFeature(featureId)
        if (f.status != AuthorDnaFeatureStatus.CANDIDATE) {
            throw ApplicationException(ApplicationError.InvalidOperation("只有 CANDIDATE Feature 可拒绝（当前 ${f.status}）"))
        }
        guard { dnaRepository.setAuthorDnaFeatureStatus(featureId, AuthorDnaFeatureStatus.REJECTED) }
    }

    // ================= AuthorDnaLite（DEC-P18C-014） =================
    // 注：最小只读投影由 AuthorContextProjection.buildDnaLite() 唯一实现（读 Repository 直投），
    //     UseCase 不重复实现，避免双投影源（Review Finding 1 清理）。

    // ================= 内部 =================

    private fun analyzeDocument(source: AuthorDnaSource, doc: com.qianyan.model.txt.TxtDocument): List<DnaFeatureCandidate> {
        val chapters = guard { txtRepository.getChapters(doc.documentId) }
        val blocks = guard { txtRepository.getBlocks(doc.documentId) }
        val input = inputBuilder.build(doc, chapters, blocks)
        val request = ProviderRequest(
            model = model,
            messages = listOf(
                ChatMessage(ChatRole.SYSTEM, SYSTEM_PROMPT),
                ChatMessage(ChatRole.USER, render(input)),
            ),
            temperature = 0.0,
        )
        val response = try {
            gateway.chat(request)
        } catch (e: Exception) {
            throw errorMapper.map(e)
        }
        return AuthorDnaParser.parse(response.content)
    }

    private fun createVersion(
        authorId: AuthorProfileId,
        status: AuthorDnaVersionStatus,
        features: List<AuthorDnaFeature>,
        now: Instant,
    ): AuthorDna {
        // 旧 ACTIVE → SUPERSEDED
        guard { dnaRepository.listAuthorDnaVersionsByStatus(authorId, AuthorDnaVersionStatus.ACTIVE) }
            .forEach { guard { upsertVersion(asStatus(it, AuthorDnaVersionStatus.SUPERSEDED, now)) } }

        val nextVersion = (guard { dnaRepository.listAuthorDnaVersions(authorId) }.maxOfOrNull { it.version } ?: 0L) + 1
        val version = AuthorDnaVersion(
            versionId = AuthorDnaVersionId(nextId()),
            authorId = authorId,
            version = nextVersion,
            status = status,
            ruleVersion = RULE_VERSION,
            createdAt = now,
            updatedAt = now,
        )
        guard { dnaRepository.upsertAuthorDnaVersion(version) }
        val populated = features.map { it.copy(versionId = version.versionId, authorId = authorId) }
        populated.forEach { guard { dnaRepository.upsertAuthorDnaFeature(it) } }
        return AuthorDna(
            versionId = version.versionId,
            authorId = authorId,
            version = version.version,
            status = version.status,
            ruleVersion = version.ruleVersion,
            features = populated,
            createdAt = now,
        )
    }

    private fun upsertVersion(v: AuthorDnaVersion) = dnaRepository.upsertAuthorDnaVersion(v)

    private fun asStatus(v: AuthorDnaVersion, status: AuthorDnaVersionStatus, now: Instant): AuthorDnaVersion =
        v.copy(status = status, updatedAt = now)

    private fun requireDocument(id: TxtDocumentId): com.qianyan.model.txt.TxtDocument =
        guard { txtRepository.getDocument(id) }
            ?: throw ApplicationException(ApplicationError.EntityNotFound("TXT 文档不存在: ${id.value}"))

    private fun requireFeature(id: AuthorDnaFeatureId): AuthorDnaFeature =
        guard { dnaRepository.getAuthorDnaFeature(id) }
            ?: throw ApplicationException(ApplicationError.EntityNotFound("DNA Feature 不存在: ${id.value}"))

    private fun isValidSource(status: com.qianyan.model.txt.TxtParseStatus, normalizedText: String): Boolean =
        status == com.qianyan.model.txt.TxtParseStatus.SUCCESS && normalizedText.isNotBlank()

    private fun requireValidSource(authorId: AuthorProfileId, documentId: TxtDocumentId, status: com.qianyan.model.txt.TxtParseStatus, normalizedText: String) {
        if (!isValidSource(status, normalizedText)) {
            throw ApplicationException(
                ApplicationError.InvalidOperation("TXT 非有效来源（解析未成功或正文为空），无法注册为 DNA 来源: ${documentId.value}"),
            )
        }
    }

    private fun render(input: com.qianyan.model.analysis.AnalysisInput): String = buildString {
        appendLine("【文档】${input.title}")
        for (ch in input.chapters) {
            appendLine("【第${ch.ordinal + 1}章】${ch.title}")
            for (b in ch.blocks) {
                appendLine(b.text)
            }
        }
    }

    private companion object {
        const val RULE_VERSION: String = "p18c:author-dna:v1"

        const val SYSTEM_PROMPT: String =
            "你是资深文学编辑。分析下面小说文本，提取作者跨作品稳定的写作风格特征。只输出 JSON，不要其它文字或代码块围栏。" +
                "JSON 结构：{\"features\":[{\"dimension\":\"SENTENCE\",\"featureKey\":\"sentence.rhythm\",\"value\":\"short_sentence_dominant\",\"statement\":\"短句为主，节奏明快\",\"confidence\":0.8,\"evidence\":\"大量两句以内的短句\"}]}。" +
                "dimension 只能从 [SENTENCE, PARAGRAPH, DIALOGUE, NARRATIVE, POV, DESCRIPTION, EMOTION, ACTION] 中选。featureKey 用 snake_case 语义键。confidence 0..1。"
    }
}

/** P18-C 等权聚合器：每 source 一份候选，按 (dimension, featureKey) 合并；confidence 取均值夹取到 [0,0.9]。 */
internal class DnaAggregator {
    private data class Key(val dimension: com.qianyan.model.author.AuthorDnaDimension, val featureKey: String)

    private data class Acc(
        val key: Key,
        val value: String,
        val statement: String,
        val confidences: MutableList<Double> = mutableListOf(),
        val sourceRefs: MutableList<AuthorDnaSourceRef> = mutableListOf(),
    )

    private val byKey = LinkedHashMap<Key, Acc>()
    val isEmpty: Boolean get() = byKey.isEmpty()

    fun add(candidates: List<DnaFeatureCandidate>, source: AuthorDnaSource, doc: com.qianyan.model.txt.TxtDocument) {
        for (c in candidates) {
            val key = Key(c.dimension, c.featureKey)
            val acc = byKey.getOrPut(key) {
                Acc(key, value = c.value, statement = c.statement)
            }
            acc.confidences.add(c.confidence ?: 0.5)
            acc.sourceRefs.add(
                // 程序绑定 source 文档实数范围 [0, charCount)，绝不使用 LLM 提供的 location（DEC-P18C-010/005）。
                AuthorDnaSourceRef(
                    sourceId = source.txtDocumentId,
                    sourceNovelId = source.sourceNovelId ?: doc.novelId,
                    sourceStart = 0,
                    sourceEnd = doc.charCount,
                ),
            )
        }
    }

    data class Merged(
        val dimension: com.qianyan.model.author.AuthorDnaDimension,
        val featureKey: String,
        val value: String,
        val statement: String,
        val confidence: Double,
        val sourceRefs: List<AuthorDnaSourceRef>,
    )

    fun features(): List<Merged> =
        byKey.values.map { acc ->
            Merged(
                dimension = acc.key.dimension,
                featureKey = acc.key.featureKey,
                value = acc.value,
                statement = acc.statement,
                confidence = (acc.confidences.sum() / acc.confidences.size).coerceIn(0.0, 0.9),
                sourceRefs = acc.sourceRefs.distinctBy { it.sourceId.value },
            )
        }
}