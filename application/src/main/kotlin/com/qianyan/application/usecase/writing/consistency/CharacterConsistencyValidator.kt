package com.qianyan.application.usecase.writing.consistency

import com.qianyan.model.CharacterId
import com.qianyan.model.characterconsistency.CharacterConsistencyBasis
import com.qianyan.model.characterconsistency.CharacterConsistencyConfidence
import com.qianyan.model.characterconsistency.CharacterConsistencyEvidence
import com.qianyan.model.characterconsistency.CharacterConsistencyEvidenceSource
import com.qianyan.model.characterconsistency.CharacterConsistencyFinding
import com.qianyan.model.characterconsistency.CharacterConsistencyFindingType
import com.qianyan.model.characterconsistency.CharacterConsistencyMissingInput
import com.qianyan.model.characterconsistency.CharacterConsistencyResult
import com.qianyan.model.characterconsistency.CharacterConsistencyScope
import com.qianyan.model.characterconsistency.CharacterConsistencyStatus
import com.qianyan.model.characterconsistency.CheckedCharacter
import com.qianyan.model.characterconsistency.MemoryLayerCount
import com.qianyan.model.character.Character
import com.qianyan.model.character.CharacterState
import com.qianyan.model.context.StoryWorldContext
import com.qianyan.model.memory.MemoryLayer
import com.qianyan.model.spec.IssueSeverity
import com.qianyan.model.story.ChapterPlan
import com.qianyan.model.story.Foreshadow
import com.qianyan.model.timeline.Event
import com.qianyan.model.timeline.TimelineEntry
import com.qianyan.model.world.WorldRule

/**
 * CharacterConsistency 运行时 Validator（纯逻辑）。
 *
 * 只使用调用方传入的 [StoryWorldContext] 快照与 [CharacterConsistencyInput]；
 * 不读持久化仓储 / Tool / LLM，不写任何数据。
 */
internal class CharacterConsistencyValidator {

    fun validate(
        input: CharacterConsistencyInput,
        worldContext: StoryWorldContext,
        raw: CharacterConsistencyRawResult,
    ): CharacterConsistencyResult {
        validateSnapshotScope(input, worldContext)
        validateCoverage(input)
        validatePriorCanonical(input)
        if (raw.scope != input.scope) {
            fail(CharacterConsistencyErrorCodes.INVALID_SCOPE, "result.scope=${raw.scope} != input.scope=${input.scope}")
        }
        validateStatusShape(raw)
        if (raw.summary.isBlank()) {
            fail(CharacterConsistencyErrorCodes.INVALID_OUTPUT, "summary is blank")
        }

        val missing = resolveMissingInputs(input, worldContext, raw.missingInputs)
        validateStatusAgainstMissing(raw.status, missing)
        val checked = validateCheckedCharacters(raw.checkedCharacters, worldContext, raw.status)
        if (raw.status == CharacterConsistencyStatus.PASS && checked.isEmpty()) {
            fail(CharacterConsistencyErrorCodes.CHECKED_CHARACTER_INVALID, "PASS requires at least one checked character")
        }

        val findings = validateFindings(raw.findings, input, worldContext, checked)
        val sorted = sortAndDedupe(findings)
        val limited = sorted.take(MAX_FINDINGS)
        val truncated = sorted.size > limited.size
        val basis = buildBasis(input, worldContext, checked)

        return CharacterConsistencyResult(
            status = raw.status,
            scope = raw.scope,
            findings = limited,
            checkedCharacters = checked,
            missingInputs = missing,
            basis = basis,
            summary = raw.summary,
            truncated = truncated,
        )
    }

    /* ---------------- input / snapshot ---------------- */

    private fun validateSnapshotScope(input: CharacterConsistencyInput, worldContext: StoryWorldContext) {
        if (worldContext.novelId != input.novelId) {
            fail(
                CharacterConsistencyErrorCodes.BASIS_INVALID,
                "worldContext.novelId=${worldContext.novelId.value} != input.novelId=${input.novelId.value}",
            )
        }
        if (worldContext.variantId != input.variantId) {
            fail(
                CharacterConsistencyErrorCodes.BASIS_INVALID,
                "worldContext.variantId=${worldContext.variantId?.value ?: "null"} != input.variantId=${input.variantId?.value ?: "null"}",
            )
        }
    }

    private fun validateCoverage(input: CharacterConsistencyInput) {
        val coverage = input.coverage
        if (coverage.currentDraftFromContextPack != (input.currentDraft != null)) {
            fail(CharacterConsistencyErrorCodes.BASIS_INVALID, "coverage.currentDraftFromContextPack 与 currentDraft 不一致")
        }
        if (coverage.planProvided != (input.plan != null)) {
            fail(CharacterConsistencyErrorCodes.BASIS_INVALID, "coverage.planProvided 与 plan 不一致")
        }
        if (coverage.priorCanonicalCount != input.priorCanonical.size) {
            fail(
                CharacterConsistencyErrorCodes.BASIS_INVALID,
                "coverage.priorCanonicalCount=${coverage.priorCanonicalCount} != priorCanonical.size=${input.priorCanonical.size}",
            )
        }
        if (coverage.priorCanonicalGapChapterIds.size != coverage.priorCanonicalGapChapterIds.distinct().size) {
            fail(CharacterConsistencyErrorCodes.BASIS_INVALID, "priorCanonicalGapChapterIds 含重复")
        }
        val present = input.priorCanonical.map { it.chapterId }.toSet()
        if (coverage.priorCanonicalGapChapterIds.any { it in present }) {
            fail(CharacterConsistencyErrorCodes.BASIS_INVALID, "priorCanonicalGapChapterIds 与 priorCanonical 重叠")
        }
    }

    private fun validatePriorCanonical(input: CharacterConsistencyInput) {
        if (input.priorCanonical.size > MAX_PRIOR_CANONICAL) {
            fail(
                CharacterConsistencyErrorCodes.BASIS_INVALID,
                "priorCanonical 最多 $MAX_PRIOR_CANONICAL 章，实际 ${input.priorCanonical.size}",
            )
        }
        var lastOrder = Int.MIN_VALUE
        val chapterIds = mutableSetOf<String>()
        val draftIds = mutableSetOf<String>()
        input.priorCanonical.forEach { chapter ->
            if (chapter.chapterOrder <= lastOrder) {
                fail(CharacterConsistencyErrorCodes.BASIS_INVALID, "priorCanonical chapterOrder 必须严格升序")
            }
            lastOrder = chapter.chapterOrder
            if (!chapterIds.add(chapter.chapterId.value)) {
                fail(CharacterConsistencyErrorCodes.BASIS_INVALID, "priorCanonical chapterId 重复: ${chapter.chapterId.value}")
            }
            if (!draftIds.add(chapter.draftId.value)) {
                fail(CharacterConsistencyErrorCodes.BASIS_INVALID, "priorCanonical draftId 重复: ${chapter.draftId.value}")
            }
        }
    }

    /* ---------------- status / missing ---------------- */

    private fun validateStatusShape(raw: CharacterConsistencyRawResult) {
        when (raw.status) {
            CharacterConsistencyStatus.PASS -> if (raw.findings.isNotEmpty()) {
                fail(CharacterConsistencyErrorCodes.INVALID_STATUS, "PASS 不得包含 findings")
            }
            CharacterConsistencyStatus.ISSUES -> if (raw.findings.isEmpty()) {
                fail(CharacterConsistencyErrorCodes.INVALID_STATUS, "ISSUES 必须包含 findings")
            }
            CharacterConsistencyStatus.INSUFFICIENT_EVIDENCE -> if (raw.findings.isNotEmpty()) {
                fail(CharacterConsistencyErrorCodes.INVALID_STATUS, "INSUFFICIENT_EVIDENCE 不得包含 findings")
            }
        }
    }

    private fun validateStatusAgainstMissing(
        status: CharacterConsistencyStatus,
        missing: List<CharacterConsistencyMissingInput>,
    ) {
        if (status == CharacterConsistencyStatus.PASS && missing.isNotEmpty()) {
            fail(
                CharacterConsistencyErrorCodes.PASS_WITH_MISSING_INPUT,
                "PASS 但存在缺失输入: ${missing.joinToString(",") { it.name }}",
            )
        }
        if (status == CharacterConsistencyStatus.INSUFFICIENT_EVIDENCE && missing.isEmpty()) {
            fail(
                CharacterConsistencyErrorCodes.INSUFFICIENT_WITHOUT_MISSING,
                "INSUFFICIENT_EVIDENCE 必须声明至少一个缺失输入",
            )
        }
    }

    private fun resolveMissingInputs(
        input: CharacterConsistencyInput,
        worldContext: StoryWorldContext,
        rawMissing: List<CharacterConsistencyMissingInput>,
    ): List<CharacterConsistencyMissingInput> {
        val requested = (input.missingInputs + rawMissing).toSet()
        requested.forEach { missing ->
            val available = when (missing) {
                CharacterConsistencyMissingInput.CURRENT_DRAFT ->
                    input.currentDraft != null && input.currentDraft.content.isNotBlank()
                CharacterConsistencyMissingInput.CHARACTER_PROFILE -> worldContext.characters.isNotEmpty()
                CharacterConsistencyMissingInput.CHARACTER_STATE -> worldContext.characterStates.isNotEmpty()
                CharacterConsistencyMissingInput.PRIOR_CANONICAL -> input.priorCanonical.isNotEmpty()
                CharacterConsistencyMissingInput.WORLD_RULE -> worldContext.worldRules.isNotEmpty()
                CharacterConsistencyMissingInput.EVENT -> worldContext.events.isNotEmpty()
                CharacterConsistencyMissingInput.TIMELINE -> worldContext.timelineEntries.isNotEmpty()
                CharacterConsistencyMissingInput.FORESHADOW -> worldContext.foreshadows.isNotEmpty()
                CharacterConsistencyMissingInput.MEMORY -> hasMemories(worldContext)
                CharacterConsistencyMissingInput.PLAN -> input.plan != null
            }
            if (available) {
                fail(
                    CharacterConsistencyErrorCodes.BASIS_INVALID,
                    "声明缺失但实际存在: ${missing.name}",
                )
            }
        }

        val auto = buildSet {
            if (input.currentDraft == null || input.currentDraft.content.isBlank()) {
                add(CharacterConsistencyMissingInput.CURRENT_DRAFT)
            }
            if (worldContext.characters.isEmpty()) {
                add(CharacterConsistencyMissingInput.CHARACTER_PROFILE)
            }
            if (input.scope == CharacterConsistencyScope.CONTINUITY && input.priorCanonical.isEmpty()) {
                add(CharacterConsistencyMissingInput.PRIOR_CANONICAL)
            }
        }
        return (requested + auto).sortedBy { it.ordinal }
    }

    private fun hasMemories(worldContext: StoryWorldContext): Boolean =
        worldContext.canon.isNotEmpty() ||
            worldContext.worldState.isNotEmpty() ||
            worldContext.facts.isNotEmpty() ||
            worldContext.memories.isNotEmpty()

    /* ---------------- checked characters ---------------- */

    private fun validateCheckedCharacters(
        rawChecked: List<CharacterConsistencyRawCheckedCharacter>,
        worldContext: StoryWorldContext,
        status: CharacterConsistencyStatus,
    ): List<CheckedCharacter> {
        if (rawChecked.isEmpty()) {
            if (status == CharacterConsistencyStatus.PASS) {
                fail(CharacterConsistencyErrorCodes.CHECKED_CHARACTER_INVALID, "PASS requires checkedCharacters")
            }
            return emptyList()
        }
        val seen = mutableSetOf<String>()
        return rawChecked.map { raw ->
            if (raw.characterId.isBlank() || raw.name.isBlank()) {
                fail(CharacterConsistencyErrorCodes.CHECKED_CHARACTER_INVALID, "checked character id/name 为空")
            }
            if (!seen.add(raw.characterId)) {
                fail(CharacterConsistencyErrorCodes.CHECKED_CHARACTER_INVALID, "checked character 重复: ${raw.characterId}")
            }
            val profile = worldContext.characters.firstOrNull { it.characterId.value == raw.characterId }
                ?: fail(
                    CharacterConsistencyErrorCodes.CHECKED_CHARACTER_INVALID,
                    "checked character 不在输入档案: ${raw.characterId}",
                )
            if (profile.name != raw.name) {
                fail(
                    CharacterConsistencyErrorCodes.CHECKED_CHARACTER_INVALID,
                    "checked character name 不一致: ${raw.characterId}",
                )
            }
            CheckedCharacter(profile.characterId, profile.name)
        }.sortedBy { it.characterId.value }
    }

    /* ---------------- findings ---------------- */

    private fun validateFindings(
        rawFindings: List<CharacterConsistencyRawFinding>,
        input: CharacterConsistencyInput,
        worldContext: StoryWorldContext,
        checked: List<CheckedCharacter>,
    ): List<CharacterConsistencyFinding> = rawFindings.map { raw ->
        if (raw.message.isBlank()) {
            fail(CharacterConsistencyErrorCodes.INVALID_OUTPUT, "finding message is blank")
        }
        val domainCharacterId = when (raw.type) {
            CharacterConsistencyFindingType.UNRESOLVED_CHARACTER -> {
                if (!raw.characterId.isNullOrBlank()) {
                    fail(
                        CharacterConsistencyErrorCodes.UNRESOLVED_CHARACTER_INVALID,
                        "UNRESOLVED_CHARACTER 不得携带 characterId",
                    )
                }
                if (raw.characterName.isBlank()) {
                    fail(
                        CharacterConsistencyErrorCodes.UNRESOLVED_CHARACTER_INVALID,
                        "UNRESOLVED_CHARACTER 必须提供 characterName",
                    )
                }
                null
            }
            else -> {
                val id = raw.characterId?.takeIf { it.isNotBlank() }
                    ?: fail(
                        CharacterConsistencyErrorCodes.CHARACTER_NOT_IN_INPUT,
                        "finding 缺少 characterId（仅 UNRESOLVED_CHARACTER 可为空）",
                    )
                val profile = worldContext.characters.firstOrNull { it.characterId.value == id }
                    ?: fail(
                        CharacterConsistencyErrorCodes.CHARACTER_NOT_IN_INPUT,
                        "finding character 不在输入档案: $id",
                    )
                if (profile.name != raw.characterName) {
                    fail(
                        CharacterConsistencyErrorCodes.CHARACTER_NOT_IN_INPUT,
                        "finding characterName 与档案不一致: $id",
                    )
                }
                if (checked.none { it.characterId.value == id }) {
                    fail(
                        CharacterConsistencyErrorCodes.CHECKED_CHARACTER_INVALID,
                        "finding character 未在 checkedCharacters: $id",
                    )
                }
                profile.characterId
            }
        }

        if (raw.confidence == CharacterConsistencyConfidence.UNKNOWN && raw.severity != IssueSeverity.INFO) {
            fail(
                CharacterConsistencyErrorCodes.SEVERITY_CONFIDENCE_INVALID,
                "ERROR/WARNING 不允许 UNKNOWN confidence",
            )
        }
        if (raw.evidence.isEmpty()) {
            fail(CharacterConsistencyErrorCodes.EVIDENCE_MISSING, "finding 至少需要一条 evidence")
        }

        CharacterConsistencyFinding(
            type = raw.type,
            severity = raw.severity,
            characterId = domainCharacterId,
            characterName = raw.characterName,
            message = raw.message,
            evidence = raw.evidence.map { validateEvidence(it, input, worldContext) },
            confidence = raw.confidence,
            suggestion = raw.suggestion,
        )
    }

    /* ---------------- evidence ---------------- */

    private fun validateEvidence(
        raw: CharacterConsistencyRawEvidence,
        input: CharacterConsistencyInput,
        worldContext: StoryWorldContext,
    ): CharacterConsistencyEvidence = when (raw.sourceKind) {
        CharacterConsistencyEvidenceSource.MEMORY -> validateMemoryEvidence(raw, worldContext)
        else -> validateStructuredEvidence(raw, input, worldContext)
    }

    private fun validateStructuredEvidence(
        raw: CharacterConsistencyRawEvidence,
        input: CharacterConsistencyInput,
        worldContext: StoryWorldContext,
    ): CharacterConsistencyEvidence {
        if (raw.memoryLayer != null || raw.sourceIndex != null) {
            fail(CharacterConsistencyErrorCodes.EVIDENCE_MEMORY_INVALID, "非 MEMORY 证据不得携带 memoryLayer/sourceIndex")
        }
        val sourceId = raw.sourceId?.takeIf { it.isNotBlank() }
            ?: fail(CharacterConsistencyErrorCodes.EVIDENCE_SOURCE_UNKNOWN, "evidence 缺少 sourceId")
        if (!sourceExists(raw.sourceKind, sourceId, input, worldContext)) {
            fail(
                CharacterConsistencyErrorCodes.EVIDENCE_SOURCE_UNKNOWN,
                "evidence source 不存在: ${raw.sourceKind.name}/$sourceId",
            )
        }
        val field = raw.field?.takeIf { it.isNotBlank() }
            ?: fail(CharacterConsistencyErrorCodes.EVIDENCE_FIELD_UNKNOWN, "evidence 缺少 field")
        val text = resolveFieldText(raw.sourceKind, sourceId, field, input, worldContext)
            ?: fail(
                CharacterConsistencyErrorCodes.EVIDENCE_FIELD_UNKNOWN,
                "evidence field 不存在: ${raw.sourceKind.name}/$sourceId/$field",
            )
        val offsets = resolveOffsets(text, raw.quote, raw.startOffset, raw.endOffset)
        return CharacterConsistencyEvidence(
            quote = raw.quote,
            sourceKind = raw.sourceKind,
            sourceId = sourceId,
            field = field,
            startOffset = offsets.first,
            endOffset = offsets.second,
        )
    }

    private fun validateMemoryEvidence(
        raw: CharacterConsistencyRawEvidence,
        worldContext: StoryWorldContext,
    ): CharacterConsistencyEvidence {
        if (raw.sourceId != null) {
            fail(CharacterConsistencyErrorCodes.EVIDENCE_MEMORY_INVALID, "MEMORY 证据不得携带 sourceId")
        }
        val layer = raw.memoryLayer
            ?: fail(CharacterConsistencyErrorCodes.EVIDENCE_MEMORY_INVALID, "MEMORY 证据缺少 memoryLayer")
        val index = raw.sourceIndex
            ?: fail(CharacterConsistencyErrorCodes.EVIDENCE_MEMORY_INVALID, "MEMORY 证据缺少 sourceIndex")
        val field = raw.field?.takeIf { it.isNotBlank() }
            ?: fail(CharacterConsistencyErrorCodes.EVIDENCE_FIELD_UNKNOWN, "MEMORY 证据缺少 field")
        if (field != "content") {
            fail(CharacterConsistencyErrorCodes.EVIDENCE_FIELD_UNKNOWN, "MEMORY field 只能是 content")
        }
        val entries = memoryEntries(worldContext, layer)
        if (index !in entries.indices) {
            fail(CharacterConsistencyErrorCodes.EVIDENCE_MEMORY_INVALID, "MEMORY sourceIndex 越界: $index")
        }
        val offsets = resolveOffsets(entries[index], raw.quote, raw.startOffset, raw.endOffset)
        return CharacterConsistencyEvidence(
            quote = raw.quote,
            sourceKind = CharacterConsistencyEvidenceSource.MEMORY,
            sourceId = null,
            field = field,
            memoryLayer = layer,
            sourceIndex = index,
            startOffset = offsets.first,
            endOffset = offsets.second,
        )
    }

    private fun sourceExists(
        kind: CharacterConsistencyEvidenceSource,
        sourceId: String,
        input: CharacterConsistencyInput,
        worldContext: StoryWorldContext,
    ): Boolean = when (kind) {
        CharacterConsistencyEvidenceSource.CURRENT_DRAFT -> input.currentDraft?.draftId?.value == sourceId
        CharacterConsistencyEvidenceSource.PRIOR_CANONICAL ->
            input.priorCanonical.any { it.draftId.value == sourceId }
        CharacterConsistencyEvidenceSource.CHARACTER_PROFILE ->
            worldContext.characters.any { it.characterId.value == sourceId }
        CharacterConsistencyEvidenceSource.CHARACTER_STATE ->
            worldContext.characterStates.any { it.id.value == sourceId }
        CharacterConsistencyEvidenceSource.WORLD_RULE ->
            worldContext.worldRules.any { it.ruleId.value == sourceId }
        CharacterConsistencyEvidenceSource.EVENT ->
            worldContext.events.any { it.id.value == sourceId }
        CharacterConsistencyEvidenceSource.TIMELINE ->
            worldContext.timelineEntries.any { it.id.value == sourceId }
        CharacterConsistencyEvidenceSource.FORESHADOW ->
            worldContext.foreshadows.any { it.foreshadowId.value == sourceId }
        CharacterConsistencyEvidenceSource.PLAN ->
            input.plan?.chapterPlanId?.value == sourceId
        CharacterConsistencyEvidenceSource.MEMORY -> false
    }

    private fun resolveFieldText(
        kind: CharacterConsistencyEvidenceSource,
        sourceId: String,
        field: String,
        input: CharacterConsistencyInput,
        worldContext: StoryWorldContext,
    ): String? = when (kind) {
        CharacterConsistencyEvidenceSource.CURRENT_DRAFT ->
            if (field == "content") input.currentDraft?.content else null
        CharacterConsistencyEvidenceSource.PRIOR_CANONICAL ->
            if (field == "content") {
                input.priorCanonical.firstOrNull { it.draftId.value == sourceId }?.content
            } else {
                null
            }
        CharacterConsistencyEvidenceSource.CHARACTER_PROFILE ->
            worldContext.characters.firstOrNull { it.characterId.value == sourceId }
                ?.let { characterField(it, field) }
        CharacterConsistencyEvidenceSource.CHARACTER_STATE ->
            worldContext.characterStates.firstOrNull { it.id.value == sourceId }
                ?.let { stateField(it, field) }
        CharacterConsistencyEvidenceSource.WORLD_RULE ->
            worldContext.worldRules.firstOrNull { it.ruleId.value == sourceId }
                ?.let { ruleField(it, field) }
        CharacterConsistencyEvidenceSource.EVENT ->
            worldContext.events.firstOrNull { it.id.value == sourceId }
                ?.let { eventField(it, field) }
        CharacterConsistencyEvidenceSource.TIMELINE ->
            worldContext.timelineEntries.firstOrNull { it.id.value == sourceId }
                ?.let { timelineField(it, field) }
        CharacterConsistencyEvidenceSource.FORESHADOW ->
            worldContext.foreshadows.firstOrNull { it.foreshadowId.value == sourceId }
                ?.let { foreshadowField(it, field) }
        CharacterConsistencyEvidenceSource.PLAN ->
            input.plan?.takeIf { it.chapterPlanId.value == sourceId }?.let { planField(it, field) }
        CharacterConsistencyEvidenceSource.MEMORY -> null
    }

    private fun characterField(character: Character, field: String): String? = when (field) {
        "name" -> character.name
        "description" -> character.description
        else -> indexedValue(field, "personality", character.personality)
            ?: indexedValue(field, "goals", character.goals)
            ?: indexedValue(field, "fears", character.fears)
    }

    private fun stateField(state: CharacterState, field: String): String? {
        when (field) {
            "currentGoal" -> return state.currentGoal
            "currentMotivation" -> return state.currentMotivation
            "location" -> return state.location?.value
            "snapshotType" -> return state.snapshotType.name
            "source" -> return state.source.name
            "physicalState.label" -> return state.physicalState.label
            "physicalState.details" -> return state.physicalState.details
            "emotionalState.label" -> return state.emotionalState.label
            "emotionalState.intensity" -> return state.emotionalState.intensity.toString()
        }
        Regex("^relationships\\[(\\d+)]\\.(otherCharacterId|relation)$").matchEntire(field)?.let { match ->
            val relationship = state.relationships.getOrNull(match.groupValues[1].toIntOrNull() ?: -1)
            return when (match.groupValues[2]) {
                "otherCharacterId" -> relationship?.otherCharacterId?.value
                "relation" -> relationship?.relation
                else -> null
            }
        }
        Regex("^knownFacts\\[(\\d+)]$").matchEntire(field)?.let { match ->
            return state.knownFacts.getOrNull(match.groupValues[1].toIntOrNull() ?: -1)?.value
        }
        Regex("^unknownFacts\\[(\\d+)]$").matchEntire(field)?.let { match ->
            return state.unknownFacts.getOrNull(match.groupValues[1].toIntOrNull() ?: -1)?.value
        }
        Regex("^abilities\\[(\\d+)]\\.(name|description)$").matchEntire(field)?.let { match ->
            val ability = state.abilities.getOrNull(match.groupValues[1].toIntOrNull() ?: -1)
            return when (match.groupValues[2]) {
                "name" -> ability?.name
                "description" -> ability?.description
                else -> null
            }
        }
        return null
    }

    private fun ruleField(rule: WorldRule, field: String): String? = when (field) {
        "content" -> rule.content
        "category" -> rule.category
        else -> null
    }

    private fun eventField(event: Event, field: String): String? = when (field) {
        "name" -> event.name
        "description" -> event.description
        "what" -> event.what
        "type" -> event.type.name
        "status" -> event.status.name
        else -> indexedValue(field, "who", event.who.map { it.value })
            ?: indexedValue(field, "where", event.where.map { it.value })
    }

    private fun timelineField(entry: TimelineEntry, field: String): String? = when (field) {
        "description" -> entry.description
        "timeType" -> entry.timeType.name
        else -> null
    }

    private fun foreshadowField(foreshadow: Foreshadow, field: String): String? = when (field) {
        "content" -> foreshadow.content
        "state" -> foreshadow.state.name
        else -> null
    }

    private fun planField(plan: ChapterPlan, field: String): String? = when (field) {
        "chapterGoal" -> plan.chapterGoal
        "emotionalDirection" -> plan.emotionalDirection
        "endingHook" -> plan.endingHook
        "mainConflict" -> plan.mainConflict?.value
        else -> indexedValue(field, "expectedEvents", plan.expectedEvents)
            ?: indexedValue(field, "constraints", plan.constraints)
            ?: indexedValue(field, "forbiddenEvents", plan.forbiddenEvents)
            ?: Regex("^characterGoals\\[(.+)]$").matchEntire(field)?.let { match ->
                plan.characterGoals[CharacterId(match.groupValues[1])]
            }
    }

    private fun indexedValue(field: String, prefix: String, values: List<String>): String? {
        val match = Regex("^${Regex.escape(prefix)}\\[(\\d+)]$").matchEntire(field) ?: return null
        val index = match.groupValues[1].toIntOrNull() ?: return null
        return values.getOrNull(index)
    }

    private fun resolveOffsets(
        text: String,
        quote: String,
        startOffset: Int?,
        endOffset: Int?,
    ): Pair<Int, Int> {
        if (quote.isBlank()) {
            fail(CharacterConsistencyErrorCodes.EVIDENCE_QUOTE_NOT_FOUND, "evidence quote is blank")
        }
        if ((startOffset == null) != (endOffset == null)) {
            fail(CharacterConsistencyErrorCodes.EVIDENCE_OFFSET_INVALID, "startOffset/endOffset 必须同时提供")
        }
        if (startOffset == null) {
            val occurrences = occurrences(text, quote)
            if (occurrences.isEmpty()) {
                fail(CharacterConsistencyErrorCodes.EVIDENCE_QUOTE_NOT_FOUND, "quote 不在 field 文本中: $quote")
            }
            if (occurrences.size > 1) {
                fail(
                    CharacterConsistencyErrorCodes.EVIDENCE_QUOTE_AMBIGUOUS,
                    "quote 在 field 文本中出现多次，必须提供 offsets: $quote",
                )
            }
            val start = occurrences.single()
            val end = start + quote.codePointCount(0, quote.length)
            val startIndex = text.offsetByCodePoints(0, start)
            val endIndex = text.offsetByCodePoints(0, end)
            if (text.substring(startIndex, endIndex) != quote) {
                fail(
                    CharacterConsistencyErrorCodes.EVIDENCE_QUOTE_NOT_FOUND,
                    "quote 不落在完整 code point 边界: $quote",
                )
            }
            return start to end
        }
        val start = startOffset
        val end = endOffset!!
        val total = text.codePointCount(0, text.length)
        if (start < 0 || end <= start || end > total) {
            fail(
                CharacterConsistencyErrorCodes.EVIDENCE_OFFSET_INVALID,
                "offset 越界或顺序错误: start=$start end=$end total=$total",
            )
        }
        val startIndex = text.offsetByCodePoints(0, start)
        val endIndex = text.offsetByCodePoints(0, end)
        if (text.substring(startIndex, endIndex) != quote) {
            fail(
                CharacterConsistencyErrorCodes.EVIDENCE_OFFSET_INVALID,
                "offset 指向文本与 quote 不一致: start=$start end=$end",
            )
        }
        return start to end
    }

    private fun occurrences(text: String, quote: String): List<Int> {
        val result = mutableListOf<Int>()
        var index = text.indexOf(quote)
        while (index >= 0) {
            result += text.codePointCount(0, index)
            index = text.indexOf(quote, index + 1)
        }
        return result
    }

    private fun memoryEntries(worldContext: StoryWorldContext, layer: MemoryLayer): List<String> = when (layer) {
        MemoryLayer.ORIGINAL -> worldContext.canon
        MemoryLayer.CURRENT_STATE -> worldContext.worldState
        MemoryLayer.LONG_TERM -> worldContext.facts
        MemoryLayer.WRITING -> worldContext.memories
    }

    /* ---------------- result shaping ---------------- */

    private fun buildBasis(
        input: CharacterConsistencyInput,
        worldContext: StoryWorldContext,
        checked: List<CheckedCharacter>,
    ): CharacterConsistencyBasis = CharacterConsistencyBasis(
        chapterId = input.chapterId,
        currentDraftId = input.currentDraft?.draftId,
        planId = input.plan?.chapterPlanId,
        priorCanonicalDraftIds = input.priorCanonical.map { it.draftId },
        priorCanonicalGapChapterIds = input.coverage.priorCanonicalGapChapterIds,
        checkedCharacterIds = checked.map { it.characterId },
        availableCharacterCount = worldContext.characters.size,
        availableStateCount = worldContext.characterStates.size,
        worldRuleCount = worldContext.worldRules.size,
        eventCount = worldContext.events.size,
        timelineCount = worldContext.timelineEntries.size,
        foreshadowCount = worldContext.foreshadows.size,
        memoryLayerCounts = listOf(
            MemoryLayerCount(MemoryLayer.ORIGINAL, worldContext.canon.size),
            MemoryLayerCount(MemoryLayer.CURRENT_STATE, worldContext.worldState.size),
            MemoryLayerCount(MemoryLayer.LONG_TERM, worldContext.facts.size),
            MemoryLayerCount(MemoryLayer.WRITING, worldContext.memories.size),
        ),
        worldContextUsed = true,
    )

    private fun sortAndDedupe(findings: List<CharacterConsistencyFinding>): List<CharacterConsistencyFinding> =
        findings.sortedWith(FINDING_COMPARATOR).distinctBy { findingKey(it) }

    private fun findingKey(finding: CharacterConsistencyFinding): String = buildString {
        append(finding.type.name).append('|')
        append(finding.characterId?.value.orEmpty()).append('|')
        append(normalizeMessage(finding.message)).append('|')
        append(evidenceKey(finding.evidence))
    }

    private fun fail(code: String, detail: String): Nothing =
        throw CharacterConsistencyException.InvalidResult(code, detail)

    private companion object {
        const val MAX_FINDINGS = 50
        const val MAX_PRIOR_CANONICAL = 3

        val FINDING_COMPARATOR: Comparator<CharacterConsistencyFinding> = Comparator { a, b ->
            val severity = severityRank(a.severity).compareTo(severityRank(b.severity))
            if (severity != 0) return@Comparator severity
            val type = a.type.ordinal.compareTo(b.type.ordinal)
            if (type != 0) return@Comparator type
            val character = compareNullableLast(a.characterId?.value, b.characterId?.value)
            if (character != 0) return@Comparator character
            val message = normalizeMessage(a.message).compareTo(normalizeMessage(b.message))
            if (message != 0) return@Comparator message
            evidenceKey(a.evidence).compareTo(evidenceKey(b.evidence))
        }

        fun evidenceKey(evidence: List<CharacterConsistencyEvidence>): String =
            evidence.sortedWith(
                compareBy(
                    { it.sourceKind.name },
                    { it.sourceId.orEmpty() },
                    { it.field.orEmpty() },
                    { it.memoryLayer?.name.orEmpty() },
                    { it.sourceIndex ?: -1 },
                    { it.quote },
                ),
            ).joinToString(";") {
                "${it.sourceKind.name}|${it.sourceId.orEmpty()}|${it.field.orEmpty()}|${it.memoryLayer?.name.orEmpty()}|${it.sourceIndex ?: -1}|${it.quote}"
            }

        fun normalizeMessage(message: String): String = message.trim().replace(Regex("\\s+"), " ")

        fun severityRank(severity: IssueSeverity): Int = when (severity) {
            IssueSeverity.ERROR -> 0
            IssueSeverity.WARNING -> 1
            IssueSeverity.INFO -> 2
        }

        fun compareNullableLast(a: String?, b: String?): Int = when {
            a == null && b == null -> 0
            a == null -> 1
            b == null -> -1
            else -> a.compareTo(b)
        }
    }
}
