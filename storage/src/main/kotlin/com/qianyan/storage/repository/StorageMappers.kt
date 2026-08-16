package com.qianyan.storage.repository

import com.qianyan.model.BaseNovelId
import com.qianyan.model.MemoryEntryId
import com.qianyan.model.NovelId
import com.qianyan.model.OverrideId
import com.qianyan.model.ProjectId
import com.qianyan.model.ProjectSource
import com.qianyan.model.ProjectStatus
import com.qianyan.model.TextBlockId
import com.qianyan.model.TxtChapterId
import com.qianyan.model.TxtDocumentId
import com.qianyan.model.UserId
import com.qianyan.model.VariantId
import com.qianyan.model.VariantScope
import com.qianyan.model.VocabularyCandidateId
import com.qianyan.model.VocabularyEntryId
import com.qianyan.model.VocabularyId
import com.qianyan.model.VocabularyRuleId
import com.qianyan.model.core.EntityOverride
import com.qianyan.model.core.Novel as DomainNovel
import com.qianyan.model.core.NovelVariant as DomainNovelVariant
import com.qianyan.model.core.OverrideOperation
import com.qianyan.model.core.OverridableKind
import com.qianyan.model.core.VariantBlueprint
import com.qianyan.model.core.VariantScopeSpec
import com.qianyan.model.core.VariantStatus
import com.qianyan.model.memory.MemoryEntry as DomainMemoryEntry
import com.qianyan.model.memory.MemoryLayer
import com.qianyan.model.txt.SourceLocation
import com.qianyan.model.txt.TextBlock as DomainTextBlock
import com.qianyan.model.txt.TxtChapter as DomainTxtChapter
import com.qianyan.model.txt.TxtDocument as DomainTxtDocument
import com.qianyan.model.txt.TxtEncoding
import com.qianyan.model.txt.TxtParseStatus
import com.qianyan.model.vocabulary.Vocabulary as DomainVocabulary
import com.qianyan.model.vocabulary.VocabularyCandidate as DomainVocabularyCandidate
import com.qianyan.model.vocabulary.VocabularyCandidateSource
import com.qianyan.model.vocabulary.VocabularyCandidateStatus
import com.qianyan.model.vocabulary.VocabularyEntry as DomainVocabularyEntry
import com.qianyan.model.vocabulary.VocabularyEntryStatus
import com.qianyan.model.vocabulary.VocabularyEntryType
import com.qianyan.model.vocabulary.VocabularyRule as DomainVocabularyRule
import com.qianyan.model.vocabulary.VocabularyScopeLevel
import com.qianyan.storage.db.EntityOverride as DbEntityOverride
import com.qianyan.storage.db.MemoryEntry as DbMemoryEntry
import com.qianyan.storage.db.Novel as DbNovel
import com.qianyan.storage.db.NovelVariant as DbNovelVariant
import com.qianyan.storage.db.TextBlock as DbTextBlock
import com.qianyan.storage.db.TxtChapter as DbTxtChapter
import com.qianyan.storage.db.TxtDocument as DbTxtDocument
import com.qianyan.storage.db.Vocabulary as DbVocabulary
import com.qianyan.storage.db.VocabularyCandidate as DbVocabularyCandidate
import com.qianyan.storage.db.VocabularyEntry as DbVocabularyEntry
import com.qianyan.storage.db.VocabularyRule as DbVocabularyRule
import com.qianyan.storage.QianyanJson
import kotlinx.datetime.Instant
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement

/**
 * 领域模型 <-> SQLDelight 生成 DTO 的双向映射（Repository 边界）。
 * - ID：DB 存 TEXT（全局唯一 UUID 字符串），边界恢复强类型 ID（P2.3）。
 * - 时间：DB 存 epoch 毫秒（INTEGER），边界恢复 kotlinx.datetime.Instant。
 * - 枚举：DB 存枚举名（TEXT）。
 * - 结构化字段：一律走 kotlinx.serialization JSON，禁用 Map<String, Any>（P2.5）。
 */
internal object StorageMappers {

    private val json get() = QianyanJson.json

    /* ---- 时间转换（不使用可能不可见的扩展函数，改用属性运算） ---- */
    private fun Instant.toEpochMillis(): Long = epochSeconds * 1000 + nanosecondsOfSecond / 1_000_000

    private fun epochMillisToInstant(value: Long): Instant =
        Instant.fromEpochSeconds(value / 1000, ((value % 1000) * 1_000_000).toInt())

    /* ---------------- Novel ---------------- */

    fun domainNovel(novel: DomainNovel): DbNovel = DbNovel(
        novel_id = novel.novelId.value,
        project_id = novel.projectId.value,
        title = novel.title,
        source = novel.source.name,
        genre = json.encodeToString(ListSerializer(String.serializer()), novel.genre),
        synopsis = novel.synopsis,
        scope = novel.scope.name,
        status = novel.status.name,
        created_at = novel.createdAt.toEpochMillis(),
        updated_at = novel.updatedAt.toEpochMillis(),
    )

    fun dbNovel(row: DbNovel): DomainNovel = DomainNovel(
        novelId = NovelId(row.novel_id),
        projectId = ProjectId(row.project_id),
        title = row.title,
        source = ProjectSource.valueOf(row.source),
        genre = json.decodeFromString(ListSerializer(String.serializer()), row.genre),
        synopsis = row.synopsis,
        scope = VariantScope.valueOf(row.scope),
        status = ProjectStatus.valueOf(row.status),
        createdAt = epochMillisToInstant(row.created_at),
        updatedAt = epochMillisToInstant(row.updated_at),
    )

    /* ---------------- NovelVariant ---------------- */

    fun domainVariant(v: DomainNovelVariant): DbNovelVariant = DbNovelVariant(
        variant_id = v.variantId.value,
        novel_id = v.novelId.value,
        base_novel_id = v.baseNovelId.value,
        project_id = v.projectId.value,
        name = v.name,
        status = v.status.name,
        blueprint = v.blueprint?.let { json.encodeToString(VariantBlueprint.serializer(), it) },
        scope_spec = v.scopeSpec?.let { json.encodeToString(VariantScopeSpec.serializer(), it) },
        created_at = v.createdAt.toEpochMillis(),
        updated_at = v.updatedAt.toEpochMillis(),
    )

    fun dbVariant(row: DbNovelVariant): DomainNovelVariant = DomainNovelVariant(
        variantId = VariantId(row.variant_id),
        novelId = NovelId(row.novel_id),
        baseNovelId = BaseNovelId(row.base_novel_id),
        projectId = ProjectId(row.project_id),
        name = row.name,
        status = VariantStatus.valueOf(row.status),
        blueprint = row.blueprint?.let { json.decodeFromString(VariantBlueprint.serializer(), it) },
        scopeSpec = row.scope_spec?.let { json.decodeFromString(VariantScopeSpec.serializer(), it) },
        createdAt = epochMillisToInstant(row.created_at),
        updatedAt = epochMillisToInstant(row.updated_at),
    )

    /* ---------------- EntityOverride ---------------- */

    fun domainOverride(o: EntityOverride): DbEntityOverride = DbEntityOverride(
        override_id = o.overrideId.value,
        variant_id = o.variantId.value,
        target_kind = o.targetKind.name,
        target_id = o.targetId,
        operation = o.operation.name,
        replaced_value = o.replacedValue?.let { json.encodeToString(JsonElement.serializer(), it) },
        note = o.note,
    )

    fun dbOverride(row: DbEntityOverride): EntityOverride = EntityOverride(
        overrideId = OverrideId(row.override_id),
        variantId = VariantId(row.variant_id),
        targetKind = OverridableKind.valueOf(row.target_kind),
        targetId = row.target_id,
        operation = OverrideOperation.valueOf(row.operation),
        replacedValue = row.replaced_value?.let { json.decodeFromString(JsonElement.serializer(), it) },
        note = row.note,
    )

    /* ---------------- Memory ---------------- */

    fun domainMemory(m: DomainMemoryEntry): DbMemoryEntry = DbMemoryEntry(
        memory_id = m.id.value,
        novel_id = m.novelId.value,
        variant_id = m.variantId?.value,
        scope = m.scope.name,
        layer = m.layer.name,
        content = m.content,
        source = m.source,
        created_by = m.createdBy?.value,
        created_at = m.createdAt.toEpochMillis(),
        updated_at = m.updatedAt.toEpochMillis(),
    )

    fun dbMemory(row: DbMemoryEntry): DomainMemoryEntry = DomainMemoryEntry(
        id = MemoryEntryId(row.memory_id),
        novelId = NovelId(row.novel_id),
        variantId = row.variant_id?.let { VariantId(it) },
        scope = VariantScope.valueOf(row.scope),
        layer = MemoryLayer.valueOf(row.layer),
        content = row.content,
        source = row.source,
        createdBy = row.created_by?.let { UserId(it) },
        createdAt = epochMillisToInstant(row.created_at),
        updatedAt = epochMillisToInstant(row.updated_at),
    )

    /* ---------------- Vocabulary ---------------- */

    fun domainVocabulary(v: DomainVocabulary): DbVocabulary = DbVocabulary(
        vocabulary_id = v.vocabularyId.value,
        novel_id = v.novelId?.value,
        variant_id = v.variantId?.value,
        scope_level = v.scopeLevel.name,
        name = v.name,
    )

    fun dbVocabulary(row: DbVocabulary): DomainVocabulary = DomainVocabulary(
        vocabularyId = VocabularyId(row.vocabulary_id),
        novelId = row.novel_id?.let { NovelId(it) },
        variantId = row.variant_id?.let { VariantId(it) },
        scopeLevel = VocabularyScopeLevel.valueOf(row.scope_level),
        name = row.name,
    )

    fun domainVocabularyEntry(e: DomainVocabularyEntry): DbVocabularyEntry = DbVocabularyEntry(
        entry_id = e.entryId.value,
        vocabulary_id = e.vocabularyId.value,
        novel_id = e.novelId?.value,
        variant_id = e.variantId?.value,
        scope_level = e.scopeLevel.name,
        canonical = e.canonical,
        aliases = json.encodeToString(ListSerializer(String.serializer()), e.aliases),
        type = e.type.name,
        replacement = e.replacement,
        status = e.status.name,
    )

    fun dbVocabularyEntry(row: DbVocabularyEntry): DomainVocabularyEntry = DomainVocabularyEntry(
        entryId = VocabularyEntryId(row.entry_id),
        vocabularyId = VocabularyId(row.vocabulary_id),
        novelId = row.novel_id?.let { NovelId(it) },
        variantId = row.variant_id?.let { VariantId(it) },
        scopeLevel = VocabularyScopeLevel.valueOf(row.scope_level),
        canonical = row.canonical,
        aliases = json.decodeFromString(ListSerializer(String.serializer()), row.aliases),
        type = VocabularyEntryType.valueOf(row.type),
        replacement = row.replacement,
        status = VocabularyEntryStatus.valueOf(row.status),
    )

    fun domainVocabularyRule(r: DomainVocabularyRule): DbVocabularyRule = DbVocabularyRule(
        rule_id = r.ruleId.value,
        vocabulary_id = r.vocabularyId.value,
        novel_id = r.novelId?.value,
        variant_id = r.variantId?.value,
        scope_level = r.scopeLevel.name,
        vocab_from = r.from,
        vocab_to = r.to,
        enabled = r.enabled,
        deterministic_only = r.deterministicOnly,
    )

    fun dbVocabularyRule(row: DbVocabularyRule): DomainVocabularyRule = DomainVocabularyRule(
        ruleId = VocabularyRuleId(row.rule_id),
        vocabularyId = VocabularyId(row.vocabulary_id),
        novelId = row.novel_id?.let { NovelId(it) },
        variantId = row.variant_id?.let { VariantId(it) },
        scopeLevel = VocabularyScopeLevel.valueOf(row.scope_level),
        from = row.vocab_from,
        to = row.vocab_to,
        enabled = row.enabled,
        deterministicOnly = row.deterministic_only,
    )

    fun domainVocabularyCandidate(c: DomainVocabularyCandidate): DbVocabularyCandidate = DbVocabularyCandidate(
        candidate_id = c.candidateId.value,
        vocabulary_id = c.vocabularyId.value,
        novel_id = c.novelId?.value,
        variant_id = c.variantId?.value,
        scope_level = c.scopeLevel.name,
        suggested = json.encodeToString(DomainVocabularyEntry.serializer(), c.suggested),
        source = c.source.name,
        status = c.status.name,
        created_at = c.createdAt.toEpochMillis(),
    )

    fun dbVocabularyCandidate(row: DbVocabularyCandidate): DomainVocabularyCandidate = DomainVocabularyCandidate(
        candidateId = VocabularyCandidateId(row.candidate_id),
        vocabularyId = VocabularyId(row.vocabulary_id),
        novelId = row.novel_id?.let { NovelId(it) },
        variantId = row.variant_id?.let { VariantId(it) },
        scopeLevel = VocabularyScopeLevel.valueOf(row.scope_level),
        suggested = json.decodeFromString(DomainVocabularyEntry.serializer(), row.suggested),
        source = VocabularyCandidateSource.valueOf(row.source),
        status = VocabularyCandidateStatus.valueOf(row.status),
        createdAt = epochMillisToInstant(row.created_at),
    )

    /* ---------------- TXT Pipeline ---------------- */

    fun domainTxtDocument(d: DomainTxtDocument): DbTxtDocument = DbTxtDocument(
        document_id = d.documentId.value,
        novel_id = d.novelId?.value,
        source_name = d.sourceName,
        title = d.title,
        encoding = d.encoding.name,
        had_bom = d.hadBom,
        byte_count = d.byteCount,
        char_count = d.charCount.toLong(),
        original_text = d.originalText,
        normalized_text = d.normalizedText,
        content_hash = d.contentHash,
        rule_version = d.ruleVersion,
        status = d.status.name,
        created_at = d.createdAt.toEpochMillis(),
    )

    fun dbTxtDocument(row: DbTxtDocument): DomainTxtDocument = DomainTxtDocument(
        documentId = TxtDocumentId(row.document_id),
        novelId = row.novel_id?.let { NovelId(it) },
        sourceName = row.source_name,
        title = row.title,
        encoding = TxtEncoding.valueOf(row.encoding),
        hadBom = row.had_bom,
        byteCount = row.byte_count,
        charCount = row.char_count.toInt(),
        originalText = row.original_text,
        normalizedText = row.normalized_text,
        contentHash = row.content_hash,
        ruleVersion = row.rule_version,
        status = TxtParseStatus.valueOf(row.status),
        createdAt = epochMillisToInstant(row.created_at),
    )

    fun domainTxtChapter(c: DomainTxtChapter): DbTxtChapter = DbTxtChapter(
        chapter_id = c.chapterId.value,
        document_id = c.documentId.value,
        novel_id = c.novelId?.value,
        ordinal = c.ordinal.toLong(),
        title = c.title,
        source_start = c.sourceLocation.startOffset.toLong(),
        source_end = c.sourceLocation.endOffset.toLong(),
        first_block_ordinal = c.firstBlockOrdinal.toLong(),
        block_count = c.blockCount.toLong(),
    )

    fun dbTxtChapter(row: DbTxtChapter): DomainTxtChapter = DomainTxtChapter(
        chapterId = TxtChapterId(row.chapter_id),
        documentId = TxtDocumentId(row.document_id),
        novelId = row.novel_id?.let { NovelId(it) },
        ordinal = row.ordinal.toInt(),
        title = row.title,
        sourceLocation = SourceLocation(row.source_start.toInt(), row.source_end.toInt()),
        firstBlockOrdinal = row.first_block_ordinal.toInt(),
        blockCount = row.block_count.toInt(),
    )

    fun domainTextBlock(b: DomainTextBlock): DbTextBlock = DbTextBlock(
        block_id = b.blockId.value,
        chapter_id = b.chapterId.value,
        document_id = b.documentId.value,
        novel_id = b.novelId?.value,
        ordinal = b.ordinal.toLong(),
        text = b.text,
        source_start = b.sourceLocation.startOffset.toLong(),
        source_end = b.sourceLocation.endOffset.toLong(),
    )

    fun dbTextBlock(row: DbTextBlock): DomainTextBlock = DomainTextBlock(
        blockId = TextBlockId(row.block_id),
        chapterId = TxtChapterId(row.chapter_id),
        documentId = TxtDocumentId(row.document_id),
        novelId = row.novel_id?.let { NovelId(it) },
        ordinal = row.ordinal.toInt(),
        text = row.text,
        sourceLocation = SourceLocation(row.source_start.toInt(), row.source_end.toInt()),
    )
}