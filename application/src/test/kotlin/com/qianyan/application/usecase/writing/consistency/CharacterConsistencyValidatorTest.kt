package com.qianyan.application.usecase.writing.consistency

import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ErrorMapper
import com.qianyan.model.ArcId
import com.qianyan.model.ActId
import com.qianyan.model.ChapterId
import com.qianyan.model.CharacterId
import com.qianyan.model.DraftId
import com.qianyan.model.EventId
import com.qianyan.model.ForeshadowingId
import com.qianyan.model.KnowledgeId
import com.qianyan.model.LocationId
import com.qianyan.model.NovelId
import com.qianyan.model.StateId
import com.qianyan.model.TimelineEntryId
import com.qianyan.model.VariantId
import com.qianyan.model.VariantScope
import com.qianyan.model.WorldId
import com.qianyan.model.WorldRuleId
import com.qianyan.model.character.AbilitySnapshot
import com.qianyan.model.character.Character
import com.qianyan.model.character.CharacterState
import com.qianyan.model.character.EmotionalState
import com.qianyan.model.character.PhysicalState
import com.qianyan.model.character.RelationshipSnapshot
import com.qianyan.model.character.SnapshotType
import com.qianyan.model.characterconsistency.CharacterConsistencyConfidence
import com.qianyan.model.characterconsistency.CharacterConsistencyEvidenceSource
import com.qianyan.model.characterconsistency.CharacterConsistencyFindingType
import com.qianyan.model.characterconsistency.CharacterConsistencyMissingInput
import com.qianyan.model.characterconsistency.CharacterConsistencyScope
import com.qianyan.model.characterconsistency.CharacterConsistencyStatus
import com.qianyan.model.context.StoryWorldContext
import com.qianyan.model.memory.MemoryLayer
import com.qianyan.model.spec.IssueSeverity
import com.qianyan.model.story.ChapterPlan
import com.qianyan.model.story.Foreshadow
import com.qianyan.model.timeline.Event
import com.qianyan.model.timeline.TimelineEntry
import com.qianyan.model.timeline.TimelinePosition
import com.qianyan.model.world.WorldRule
import java.io.File
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Phase 6 · CharacterConsistency Validator（T3–T10）。 */
class CharacterConsistencyValidatorTest {

    private val now = Instant.fromEpochMilliseconds(1_700_000_000_000)
    private val novelId = NovelId("n1")
    private val chapterId = ChapterId("ch1")
    private val currentDraftId = DraftId("d1")
    private val priorDraftId = DraftId("d0")
    private val characterId = CharacterId("c1")
    private val character2Id = CharacterId("c2")
    private val stateId = StateId("s1")
    private val ruleId = WorldRuleId("r1")
    private val eventId = EventId("e1")
    private val timelineId = TimelineEntryId("t1")
    private val foreshadowId = ForeshadowingId("f1")
    private val planId = com.qianyan.model.ChapterPlanId("p1")
    private val currentContent = "她很胆小。林岚害怕黑暗。神秘人出现。"
    private val priorContent = "林岚说她不怕黑。"
    private val validator = CharacterConsistencyValidator()

    /* ---------------- fixtures ---------------- */

    private fun character(id: CharacterId = characterId, name: String = "林岚"): Character = Character(
        characterId = id,
        novelId = novelId,
        name = name,
        personality = listOf("胆小"),
        goals = listOf("活下去"),
        fears = listOf("黑暗"),
        description = "青云宗弟子",
        createdAt = now,
        updatedAt = now,
    )

    private fun character2(): Character = character(character2Id, "顾寒")

    private fun state(): CharacterState = CharacterState(
        id = stateId,
        characterId = characterId,
        novelId = novelId,
        snapshotType = SnapshotType.CHAPTER_END,
        location = LocationId("loc1"),
        physicalState = PhysicalState("受伤", "左臂"),
        emotionalState = EmotionalState("恐惧", 0.8f),
        currentGoal = "找到真相",
        currentMotivation = "救妹妹",
        relationships = listOf(RelationshipSnapshot(character2Id, "师徒")),
        knownFacts = listOf(KnowledgeId("k1")),
        unknownFacts = listOf(KnowledgeId("k2")),
        abilities = listOf(AbilitySnapshot("剑术", "御剑")),
        createdAt = now,
    )

    private fun rule(): WorldRule = WorldRule(
        ruleId = ruleId,
        worldId = WorldId("w1"),
        novelId = novelId,
        content = "修士不能飞行",
        category = "物理",
    )

    private fun event(): Event = Event(
        id = eventId,
        novelId = novelId,
        name = "青云试炼",
        description = "林岚参加试炼",
        what = "试炼开始",
        who = listOf(characterId),
        createdAt = now,
    )

    private fun timeline(): TimelineEntry = TimelineEntry(
        id = timelineId,
        novelId = novelId,
        position = TimelinePosition(),
        description = "试炼之后",
    )

    private fun foreshadow(): Foreshadow = Foreshadow(
        foreshadowId = foreshadowId,
        novelId = novelId,
        content = "黑暗中的眼睛",
        createdAt = now,
    )

    private fun plan(): ChapterPlan = ChapterPlan(
        chapterPlanId = planId,
        chapterId = chapterId,
        arcId = ArcId("a1"),
        actId = ActId("act1"),
        novelId = novelId,
        chapterGoal = "揭开秘密",
        characterGoals = mapOf(characterId to "保护妹妹"),
        expectedEvents = listOf("遇险"),
        emotionalDirection = "紧张",
        endingHook = "黑影出现",
        constraints = listOf("不能超自然"),
        forbiddenEvents = listOf("主角死亡"),
    )

    private fun world(
        novelId: NovelId = this.novelId,
        variantId: VariantId? = null,
        characters: List<Character> = listOf(character(), character2()),
        states: List<CharacterState> = listOf(state()),
        rules: List<WorldRule> = listOf(rule()),
        events: List<Event> = listOf(event()),
        timeline: List<TimelineEntry> = listOf(timeline()),
        foreshadows: List<Foreshadow> = listOf(foreshadow()),
        canon: List<String> = listOf("林岚是剑修"),
        worldState: List<String> = listOf("林岚在青云山"),
        facts: List<String> = listOf("林岚惧怕黑暗"),
        memories: List<String> = listOf("神秘人出现"),
    ): StoryWorldContext = StoryWorldContext(
        novelId = novelId,
        variantId = variantId,
        scope = if (variantId == null) VariantScope.ORIGINAL else VariantScope.VARIANT,
        worldSummary = "测试世界",
        canon = canon,
        worldState = worldState,
        facts = facts,
        memories = memories,
        characters = characters,
        characterStates = states,
        worldRules = rules,
        events = events,
        timelineEntries = timeline,
        foreshadows = foreshadows,
    )

    private fun priorChapter(
        id: ChapterId = ChapterId("ch0"),
        order: Int = 0,
        draftId: DraftId = priorDraftId,
        content: String = priorContent,
        variantId: VariantId? = null,
    ): CharacterConsistencyCanonicalChapter = CharacterConsistencyCanonicalChapter(
        chapterId = id,
        chapterOrder = order,
        draftId = draftId,
        content = content,
        variantId = variantId,
    )

    private fun coverageFor(
        currentDraft: CharacterConsistencyDraft?,
        plan: ChapterPlan?,
        prior: List<CharacterConsistencyCanonicalChapter>,
    ): CharacterConsistencyInputCoverage = CharacterConsistencyInputCoverage(
        currentDraftFromContextPack = currentDraft != null,
        planProvided = plan != null,
        priorCanonicalCount = prior.size,
    )

    private fun input(
        currentDraft: CharacterConsistencyDraft? = CharacterConsistencyDraft(currentDraftId, currentContent),
        plan: ChapterPlan? = plan(),
        priorCanonical: List<CharacterConsistencyCanonicalChapter> = listOf(priorChapter()),
        scope: CharacterConsistencyScope = CharacterConsistencyScope.CURRENT_CHAPTER,
        variantId: VariantId? = null,
        missingInputs: List<CharacterConsistencyMissingInput> = emptyList(),
        coverage: CharacterConsistencyInputCoverage = coverageFor(currentDraft, plan, priorCanonical),
    ): CharacterConsistencyInput = CharacterConsistencyInput(
        novelId = novelId,
        variantId = variantId,
        chapterId = chapterId,
        scope = scope,
        contextPackId = "pack-1",
        currentDraft = currentDraft,
        plan = plan,
        priorCanonical = priorCanonical,
        coverage = coverage,
        missingInputs = missingInputs,
    )

    private fun rawChecked(id: CharacterId = characterId, name: String = "林岚"): CharacterConsistencyRawCheckedCharacter =
        CharacterConsistencyRawCheckedCharacter(characterId = id.value, name = name)

    private fun evidence(
        quote: String = "她很胆小",
        sourceKind: CharacterConsistencyEvidenceSource = CharacterConsistencyEvidenceSource.CURRENT_DRAFT,
        sourceId: String? = currentDraftId.value,
        field: String? = "content",
        memoryLayer: MemoryLayer? = null,
        sourceIndex: Int? = null,
        startOffset: Int? = null,
        endOffset: Int? = null,
    ): CharacterConsistencyRawEvidence = CharacterConsistencyRawEvidence(
        quote = quote,
        sourceKind = sourceKind,
        sourceId = sourceId,
        field = field,
        memoryLayer = memoryLayer,
        sourceIndex = sourceIndex,
        startOffset = startOffset,
        endOffset = endOffset,
    )

    private fun finding(
        type: CharacterConsistencyFindingType = CharacterConsistencyFindingType.TRAIT,
        severity: IssueSeverity = IssueSeverity.WARNING,
        characterId: String? = this.characterId.value,
        characterName: String = "林岚",
        message: String = "性格描述冲突",
        evidence: List<CharacterConsistencyRawEvidence> = listOf(evidence()),
        confidence: CharacterConsistencyConfidence = CharacterConsistencyConfidence.EXPLICIT,
        suggestion: String? = null,
    ): CharacterConsistencyRawFinding = CharacterConsistencyRawFinding(
        type = type,
        severity = severity,
        characterId = characterId,
        characterName = characterName,
        message = message,
        evidence = evidence,
        confidence = confidence,
        suggestion = suggestion,
    )

    private fun raw(
        status: CharacterConsistencyStatus,
        scope: CharacterConsistencyScope = CharacterConsistencyScope.CURRENT_CHAPTER,
        findings: List<CharacterConsistencyRawFinding> = emptyList(),
        checkedCharacters: List<CharacterConsistencyRawCheckedCharacter> =
            if (status == CharacterConsistencyStatus.PASS || status == CharacterConsistencyStatus.ISSUES) {
                listOf(rawChecked())
            } else {
                emptyList()
            },
        missingInputs: List<CharacterConsistencyMissingInput> = emptyList(),
        summary: String = "summary",
    ): CharacterConsistencyRawResult = CharacterConsistencyRawResult(
        status = status,
        scope = scope,
        findings = findings,
        checkedCharacters = checkedCharacters,
        missingInputs = missingInputs,
        summary = summary,
    )

    private fun validate(
        input: CharacterConsistencyInput = input(),
        world: StoryWorldContext = world(),
        raw: CharacterConsistencyRawResult,
    ) = validator.validate(input, world, raw)

    private fun assertInvalid(code: String, block: () -> Unit) {
        val ex = assertFailsWith<CharacterConsistencyException.InvalidResult> { block() }
        assertEquals(code, ex.code)
    }

    /* ---------------- T3 / T4 status and PASS premises ---------------- */

    @Test
    fun `pass with complete inputs`() {
        val result = validate(raw = raw(CharacterConsistencyStatus.PASS))
        assertEquals(CharacterConsistencyStatus.PASS, result.status)
        assertTrue(result.findings.isEmpty())
        assertEquals(1, result.checkedCharacters.size)
        assertTrue(result.missingInputs.isEmpty())
        assertEquals(chapterId, result.basis.chapterId)
        assertEquals(currentDraftId, result.basis.currentDraftId)
        assertEquals(planId, result.basis.planId)
        assertEquals(listOf(priorDraftId), result.basis.priorCanonicalDraftIds)
        assertEquals(2, result.basis.availableCharacterCount)
        assertEquals(1, result.basis.availableStateCount)
        assertEquals(1, result.basis.worldRuleCount)
        assertEquals(1, result.basis.eventCount)
        assertEquals(1, result.basis.timelineCount)
        assertEquals(1, result.basis.foreshadowCount)
        assertEquals(4, result.basis.memoryLayerCounts.size)
        assertTrue(result.basis.worldContextUsed)
        assertTrue(!result.truncated)
    }

    @Test
    fun `pass without optional plan and prior canonical`() {
        val draft = CharacterConsistencyDraft(currentDraftId, currentContent)
        val result = validate(
            input = input(
                currentDraft = draft,
                plan = null,
                priorCanonical = emptyList(),
                coverage = coverageFor(draft, null, emptyList()),
            ),
            raw = raw(CharacterConsistencyStatus.PASS),
        )
        assertEquals(CharacterConsistencyStatus.PASS, result.status)
        assertEquals(null, result.basis.planId)
        assertTrue(result.basis.priorCanonicalDraftIds.isEmpty())
    }

    @Test
    fun `continuity without prior canonical is not pass`() {
        val draft = CharacterConsistencyDraft(currentDraftId, currentContent)
        assertInvalid(CharacterConsistencyErrorCodes.PASS_WITH_MISSING_INPUT) {
            validate(
                input = input(
                    currentDraft = draft,
                    priorCanonical = emptyList(),
                    scope = CharacterConsistencyScope.CONTINUITY,
                    coverage = coverageFor(draft, plan(), emptyList()),
                ),
                raw = raw(CharacterConsistencyStatus.PASS, scope = CharacterConsistencyScope.CONTINUITY),
            )
        }
    }

    @Test
    fun `issues may carry missing inputs`() {
        val result = validate(
            world = world(states = emptyList()),
            raw = raw(
                CharacterConsistencyStatus.ISSUES,
                findings = listOf(finding()),
                missingInputs = listOf(CharacterConsistencyMissingInput.CHARACTER_STATE),
            ),
        )
        assertEquals(CharacterConsistencyStatus.ISSUES, result.status)
        assertEquals(listOf(CharacterConsistencyMissingInput.CHARACTER_STATE), result.missingInputs)
    }

    @Test
    fun `pass without current draft is rejected`() {
        val noDraft = input(
            currentDraft = null,
            coverage = coverageFor(null, plan(), listOf(priorChapter())),
        )
        assertInvalid(CharacterConsistencyErrorCodes.PASS_WITH_MISSING_INPUT) {
            validate(input = noDraft, raw = raw(CharacterConsistencyStatus.PASS))
        }
    }

    @Test
    fun `pass without character profile is rejected`() {
        assertInvalid(CharacterConsistencyErrorCodes.PASS_WITH_MISSING_INPUT) {
            validate(
                world = world(characters = emptyList()),
                raw = raw(CharacterConsistencyStatus.PASS, checkedCharacters = emptyList()),
            )
        }
    }

    @Test
    fun `pass with empty world context is rejected`() {
        assertInvalid(CharacterConsistencyErrorCodes.PASS_WITH_MISSING_INPUT) {
            validate(
                world = StoryWorldContext(novelId = novelId),
                raw = raw(CharacterConsistencyStatus.PASS, checkedCharacters = emptyList()),
            )
        }
    }

    @Test
    fun `pass without checked character is rejected`() {
        assertInvalid(CharacterConsistencyErrorCodes.CHECKED_CHARACTER_INVALID) {
            validate(raw = raw(CharacterConsistencyStatus.PASS, checkedCharacters = emptyList()))
        }
    }

    @Test
    fun `pass with findings is rejected`() {
        assertInvalid(CharacterConsistencyErrorCodes.INVALID_STATUS) {
            validate(raw = raw(CharacterConsistencyStatus.PASS, findings = listOf(finding())))
        }
    }

    @Test
    fun `issues requires findings`() {
        assertInvalid(CharacterConsistencyErrorCodes.INVALID_STATUS) {
            validate(raw = raw(CharacterConsistencyStatus.ISSUES, findings = emptyList()))
        }
    }

    @Test
    fun `issues with valid finding returns issues`() {
        val result = validate(raw = raw(CharacterConsistencyStatus.ISSUES, findings = listOf(finding())))
        assertEquals(CharacterConsistencyStatus.ISSUES, result.status)
        assertEquals(1, result.findings.size)
        assertEquals(0, result.findings[0].evidence[0].startOffset)
        assertEquals(4, result.findings[0].evidence[0].endOffset)
    }

    @Test
    fun `insufficient requires missing inputs`() {
        assertInvalid(CharacterConsistencyErrorCodes.INSUFFICIENT_WITHOUT_MISSING) {
            validate(raw = raw(CharacterConsistencyStatus.INSUFFICIENT_EVIDENCE))
        }
    }

    @Test
    fun `insufficient with claimed missing input returns insufficient`() {
        val result = validate(
            world = world(states = emptyList()),
            raw = raw(
                CharacterConsistencyStatus.INSUFFICIENT_EVIDENCE,
                checkedCharacters = emptyList(),
                missingInputs = listOf(CharacterConsistencyMissingInput.CHARACTER_STATE),
            ),
        )
        assertEquals(CharacterConsistencyStatus.INSUFFICIENT_EVIDENCE, result.status)
        assertTrue(result.findings.isEmpty())
        assertEquals(listOf(CharacterConsistencyMissingInput.CHARACTER_STATE), result.missingInputs)
    }

    @Test
    fun `insufficient with findings is rejected`() {
        assertInvalid(CharacterConsistencyErrorCodes.INVALID_STATUS) {
            validate(
                raw = raw(
                    CharacterConsistencyStatus.INSUFFICIENT_EVIDENCE,
                    findings = listOf(finding()),
                    missingInputs = listOf(CharacterConsistencyMissingInput.CHARACTER_STATE),
                ),
            )
        }
    }

    @Test
    fun `scope mismatch is rejected`() {
        assertInvalid(CharacterConsistencyErrorCodes.INVALID_SCOPE) {
            validate(raw = raw(CharacterConsistencyStatus.PASS, scope = CharacterConsistencyScope.CONTINUITY))
        }
    }

    /* ---------------- T6 characters ---------------- */

    @Test
    fun `unknown finding character is rejected`() {
        assertInvalid(CharacterConsistencyErrorCodes.CHARACTER_NOT_IN_INPUT) {
            validate(
                world = world(characters = listOf(character2())),
                raw = raw(
                    CharacterConsistencyStatus.ISSUES,
                    findings = listOf(finding()),
                    checkedCharacters = emptyList(),
                ),
            )
        }
    }

    @Test
    fun `unresolved character with id is rejected`() {
        assertInvalid(CharacterConsistencyErrorCodes.UNRESOLVED_CHARACTER_INVALID) {
            validate(
                raw = raw(
                    CharacterConsistencyStatus.ISSUES,
                    findings = listOf(
                        finding(
                            type = CharacterConsistencyFindingType.UNRESOLVED_CHARACTER,
                            characterId = characterId.value,
                            characterName = "神秘人",
                        ),
                    ),
                ),
            )
        }
    }

    @Test
    fun `unresolved character without name is rejected`() {
        assertInvalid(CharacterConsistencyErrorCodes.UNRESOLVED_CHARACTER_INVALID) {
            validate(
                raw = raw(
                    CharacterConsistencyStatus.ISSUES,
                    findings = listOf(
                        finding(
                            type = CharacterConsistencyFindingType.UNRESOLVED_CHARACTER,
                            characterId = null,
                            characterName = " ",
                        ),
                    ),
                ),
            )
        }
    }

    @Test
    fun `duplicate checked characters are rejected`() {
        assertInvalid(CharacterConsistencyErrorCodes.CHECKED_CHARACTER_INVALID) {
            validate(
                raw = raw(
                    CharacterConsistencyStatus.PASS,
                    checkedCharacters = listOf(rawChecked(), rawChecked()),
                ),
            )
        }
    }

    @Test
    fun `unknown checked character is rejected`() {
        assertInvalid(CharacterConsistencyErrorCodes.CHECKED_CHARACTER_INVALID) {
            validate(
                raw = raw(
                    CharacterConsistencyStatus.PASS,
                    checkedCharacters = listOf(rawChecked(CharacterId("cx"), "未知")),
                ),
            )
        }
    }

    @Test
    fun `finding character must be checked`() {
        assertInvalid(CharacterConsistencyErrorCodes.CHECKED_CHARACTER_INVALID) {
            validate(
                raw = raw(
                    CharacterConsistencyStatus.ISSUES,
                    findings = listOf(finding()),
                    checkedCharacters = listOf(rawChecked(character2Id, "顾寒")),
                ),
            )
        }
    }

    /* ---------------- T7 severity / confidence ---------------- */

    @Test
    fun `error and warning require explicit or inferred confidence`() {
        listOf(IssueSeverity.ERROR, IssueSeverity.WARNING).forEach { severity ->
            assertInvalid(CharacterConsistencyErrorCodes.SEVERITY_CONFIDENCE_INVALID) {
                validate(
                    raw = raw(
                        CharacterConsistencyStatus.ISSUES,
                        findings = listOf(
                            finding(
                                severity = severity,
                                confidence = CharacterConsistencyConfidence.UNKNOWN,
                            ),
                        ),
                    ),
                )
            }
        }
    }

    @Test
    fun `info may be unknown confidence`() {
        val result = validate(
            raw = raw(
                CharacterConsistencyStatus.ISSUES,
                findings = listOf(
                    finding(
                        severity = IssueSeverity.INFO,
                        confidence = CharacterConsistencyConfidence.UNKNOWN,
                    ),
                ),
            ),
        )
        assertEquals(CharacterConsistencyConfidence.UNKNOWN, result.findings[0].confidence)
    }

    /* ---------------- T5 evidence ---------------- */

    @Test
    fun `missing evidence is rejected`() {
        assertInvalid(CharacterConsistencyErrorCodes.EVIDENCE_MISSING) {
            validate(
                raw = raw(
                    CharacterConsistencyStatus.ISSUES,
                    findings = listOf(finding(evidence = emptyList())),
                ),
            )
        }
    }

    @Test
    fun `unknown evidence source is rejected`() {
        assertInvalid(CharacterConsistencyErrorCodes.EVIDENCE_SOURCE_UNKNOWN) {
            validate(
                raw = raw(
                    CharacterConsistencyStatus.ISSUES,
                    findings = listOf(finding(evidence = listOf(evidence(sourceId = "missing")))),
                ),
            )
        }
    }

    @Test
    fun `unknown evidence field is rejected`() {
        assertInvalid(CharacterConsistencyErrorCodes.EVIDENCE_FIELD_UNKNOWN) {
            validate(
                raw = raw(
                    CharacterConsistencyStatus.ISSUES,
                    findings = listOf(finding(evidence = listOf(evidence(field = "badField")))),
                ),
            )
        }
    }

    @Test
    fun `quote not found is rejected`() {
        assertInvalid(CharacterConsistencyErrorCodes.EVIDENCE_QUOTE_NOT_FOUND) {
            validate(
                raw = raw(
                    CharacterConsistencyStatus.ISSUES,
                    findings = listOf(finding(evidence = listOf(evidence(quote = "不存在的句子")))),
                ),
            )
        }
    }

    @Test
    fun `ambiguous quote without offsets is rejected`() {
        val draft = CharacterConsistencyDraft(currentDraftId, "重复。重复。")
        assertInvalid(CharacterConsistencyErrorCodes.EVIDENCE_QUOTE_AMBIGUOUS) {
            validate(
                input = input(
                    currentDraft = draft,
                    coverage = coverageFor(draft, plan(), listOf(priorChapter())),
                ),
                raw = raw(
                    CharacterConsistencyStatus.ISSUES,
                    findings = listOf(finding(evidence = listOf(evidence(quote = "重复")))),
                ),
            )
        }
    }

    @Test
    fun `invalid offsets are rejected`() {
        listOf(
            Triple(0, null, "她很胆小"),
            Triple(0, 99, "她很胆小"),
            Triple(0, 2, "她很胆小"),
        ).forEach { (start, end, quote) ->
            assertInvalid(CharacterConsistencyErrorCodes.EVIDENCE_OFFSET_INVALID) {
                validate(
                    raw = raw(
                        CharacterConsistencyStatus.ISSUES,
                        findings = listOf(
                            finding(
                                evidence = listOf(
                                    evidence(quote = quote, startOffset = start, endOffset = end),
                                ),
                            ),
                        ),
                    ),
                )
            }
        }
    }

    @Test
    fun `valid offsets are preserved`() {
        val result = validate(
            raw = raw(
                CharacterConsistencyStatus.ISSUES,
                findings = listOf(
                    finding(
                        evidence = listOf(
                            evidence(quote = "她很胆小", startOffset = 0, endOffset = 4),
                        ),
                    ),
                ),
            ),
        )
        assertEquals(0, result.findings[0].evidence[0].startOffset)
        assertEquals(4, result.findings[0].evidence[0].endOffset)
    }

    @Test
    fun `offsets are unicode code point based`() {
        val emoji = "\uD83D\uDE00"
        val draft = CharacterConsistencyDraft(currentDraftId, "a${emoji}b")
        val input = input(
            currentDraft = draft,
            coverage = coverageFor(draft, plan(), listOf(priorChapter())),
        )
        val result = validate(
            input = input,
            raw = raw(
                CharacterConsistencyStatus.ISSUES,
                findings = listOf(
                    finding(
                        evidence = listOf(
                            evidence(quote = emoji, startOffset = 1, endOffset = 2),
                        ),
                    ),
                ),
            ),
        )
        assertEquals(1, result.findings[0].evidence[0].startOffset)
        assertEquals(2, result.findings[0].evidence[0].endOffset)

        assertInvalid(CharacterConsistencyErrorCodes.EVIDENCE_OFFSET_INVALID) {
            validate(
                input = input,
                raw = raw(
                    CharacterConsistencyStatus.ISSUES,
                    findings = listOf(
                        finding(
                            evidence = listOf(
                                evidence(quote = emoji, startOffset = 1, endOffset = 3),
                            ),
                        ),
                    ),
                ),
            )
        }
    }

    @Test
    fun `quotes must start on a code point boundary`() {
        val emoji = "\uD83D\uDE00"
        val draft = CharacterConsistencyDraft(currentDraftId, "a${emoji}b")
        val input = input(
            currentDraft = draft,
            coverage = coverageFor(draft, plan(), listOf(priorChapter())),
        )
        assertInvalid(CharacterConsistencyErrorCodes.EVIDENCE_QUOTE_NOT_FOUND) {
            validate(
                input = input,
                raw = raw(
                    CharacterConsistencyStatus.ISSUES,
                    findings = listOf(
                        finding(evidence = listOf(evidence(quote = "\uDE00"))),
                    ),
                ),
            )
        }
    }

    @Test
    fun `all structured evidence sources resolve their concrete fields`() {
        val cases = listOf(
            evidence(quote = "她很胆小", sourceKind = CharacterConsistencyEvidenceSource.CURRENT_DRAFT),
            evidence(
                quote = "林岚说她不怕黑",
                sourceKind = CharacterConsistencyEvidenceSource.PRIOR_CANONICAL,
                sourceId = priorDraftId.value,
                field = "content",
            ),
            evidence(
                quote = "胆小",
                sourceKind = CharacterConsistencyEvidenceSource.CHARACTER_PROFILE,
                sourceId = characterId.value,
                field = "personality[0]",
            ),
            evidence(
                quote = "找到真相",
                sourceKind = CharacterConsistencyEvidenceSource.CHARACTER_STATE,
                sourceId = stateId.value,
                field = "currentGoal",
            ),
            evidence(
                quote = "修士不能飞行",
                sourceKind = CharacterConsistencyEvidenceSource.WORLD_RULE,
                sourceId = ruleId.value,
                field = "content",
            ),
            evidence(
                quote = "青云试炼",
                sourceKind = CharacterConsistencyEvidenceSource.EVENT,
                sourceId = eventId.value,
                field = "name",
            ),
            evidence(
                quote = "试炼之后",
                sourceKind = CharacterConsistencyEvidenceSource.TIMELINE,
                sourceId = timelineId.value,
                field = "description",
            ),
            evidence(
                quote = "黑暗中的眼睛",
                sourceKind = CharacterConsistencyEvidenceSource.FORESHADOW,
                sourceId = foreshadowId.value,
                field = "content",
            ),
            evidence(
                quote = "揭开秘密",
                sourceKind = CharacterConsistencyEvidenceSource.PLAN,
                sourceId = planId.value,
                field = "chapterGoal",
            ),
            evidence(
                quote = "神秘人出现",
                sourceKind = CharacterConsistencyEvidenceSource.MEMORY,
                sourceId = null,
                field = "content",
                memoryLayer = MemoryLayer.WRITING,
                sourceIndex = 0,
            ),
        )
        cases.forEach { ev ->
            val result = validate(
                raw = raw(
                    CharacterConsistencyStatus.ISSUES,
                    findings = listOf(finding(evidence = listOf(ev))),
                ),
            )
            assertEquals(1, result.findings.size, "source=${ev.sourceKind}")
        }
    }

    @Test
    fun `each source rejects unknown field and source id`() {
        val cases = listOf(
            CharacterConsistencyEvidenceSource.CURRENT_DRAFT to currentDraftId.value,
            CharacterConsistencyEvidenceSource.PRIOR_CANONICAL to priorDraftId.value,
            CharacterConsistencyEvidenceSource.CHARACTER_PROFILE to characterId.value,
            CharacterConsistencyEvidenceSource.CHARACTER_STATE to stateId.value,
            CharacterConsistencyEvidenceSource.WORLD_RULE to ruleId.value,
            CharacterConsistencyEvidenceSource.EVENT to eventId.value,
            CharacterConsistencyEvidenceSource.TIMELINE to timelineId.value,
            CharacterConsistencyEvidenceSource.FORESHADOW to foreshadowId.value,
            CharacterConsistencyEvidenceSource.PLAN to planId.value,
        )
        cases.forEach { (kind, sourceId) ->
            assertInvalid(CharacterConsistencyErrorCodes.EVIDENCE_FIELD_UNKNOWN) {
                validate(
                    raw = raw(
                        CharacterConsistencyStatus.ISSUES,
                        findings = listOf(
                            finding(
                                evidence = listOf(
                                    evidence(
                                        quote = "她很胆小",
                                        sourceKind = kind,
                                        sourceId = sourceId,
                                        field = "badField",
                                    ),
                                ),
                            ),
                        ),
                    ),
                )
            }
            assertInvalid(CharacterConsistencyErrorCodes.EVIDENCE_SOURCE_UNKNOWN) {
                validate(
                    raw = raw(
                        CharacterConsistencyStatus.ISSUES,
                        findings = listOf(
                            finding(
                                evidence = listOf(
                                    evidence(
                                        quote = "她很胆小",
                                        sourceKind = kind,
                                        sourceId = "missing",
                                        field = "content",
                                    ),
                                ),
                            ),
                        ),
                    ),
                )
            }
        }
    }

    @Test
    fun `memory evidence requires layer and index`() {
        assertInvalid(CharacterConsistencyErrorCodes.EVIDENCE_MEMORY_INVALID) {
            validate(
                raw = raw(
                    CharacterConsistencyStatus.ISSUES,
                    findings = listOf(
                        finding(
                            evidence = listOf(
                                evidence(
                                    quote = "神秘人出现",
                                    sourceKind = CharacterConsistencyEvidenceSource.MEMORY,
                                    sourceId = null,
                                    field = "content",
                                    memoryLayer = null,
                                    sourceIndex = 0,
                                ),
                            ),
                        ),
                    ),
                ),
            )
        }
        assertInvalid(CharacterConsistencyErrorCodes.EVIDENCE_MEMORY_INVALID) {
            validate(
                raw = raw(
                    CharacterConsistencyStatus.ISSUES,
                    findings = listOf(
                        finding(
                            evidence = listOf(
                                evidence(
                                    quote = "神秘人出现",
                                    sourceKind = CharacterConsistencyEvidenceSource.MEMORY,
                                    sourceId = null,
                                    field = "content",
                                    memoryLayer = MemoryLayer.WRITING,
                                    sourceIndex = 9,
                                ),
                            ),
                        ),
                    ),
                ),
            )
        }
        assertInvalid(CharacterConsistencyErrorCodes.EVIDENCE_MEMORY_INVALID) {
            validate(
                raw = raw(
                    CharacterConsistencyStatus.ISSUES,
                    findings = listOf(
                        finding(
                            evidence = listOf(
                                evidence(
                                    quote = "神秘人出现",
                                    sourceKind = CharacterConsistencyEvidenceSource.MEMORY,
                                    sourceId = "not-null",
                                    field = "content",
                                    memoryLayer = MemoryLayer.WRITING,
                                    sourceIndex = 0,
                                ),
                            ),
                        ),
                    ),
                ),
            )
        }
    }

    @Test
    fun `field map covers concrete model fields`() {
        val cases = listOf(
            evidence(quote = "林岚", sourceKind = CharacterConsistencyEvidenceSource.CHARACTER_PROFILE, sourceId = characterId.value, field = "name"),
            evidence(quote = "青云宗弟子", sourceKind = CharacterConsistencyEvidenceSource.CHARACTER_PROFILE, sourceId = characterId.value, field = "description"),
            evidence(quote = "活下去", sourceKind = CharacterConsistencyEvidenceSource.CHARACTER_PROFILE, sourceId = characterId.value, field = "goals[0]"),
            evidence(quote = "黑暗", sourceKind = CharacterConsistencyEvidenceSource.CHARACTER_PROFILE, sourceId = characterId.value, field = "fears[0]"),
            evidence(quote = "找到真相", sourceKind = CharacterConsistencyEvidenceSource.CHARACTER_STATE, sourceId = stateId.value, field = "currentGoal"),
            evidence(quote = "救妹妹", sourceKind = CharacterConsistencyEvidenceSource.CHARACTER_STATE, sourceId = stateId.value, field = "currentMotivation"),
            evidence(quote = "CHAPTER_END", sourceKind = CharacterConsistencyEvidenceSource.CHARACTER_STATE, sourceId = stateId.value, field = "snapshotType"),
            evidence(quote = "INFERRED", sourceKind = CharacterConsistencyEvidenceSource.CHARACTER_STATE, sourceId = stateId.value, field = "source"),
            evidence(quote = "loc1", sourceKind = CharacterConsistencyEvidenceSource.CHARACTER_STATE, sourceId = stateId.value, field = "location"),
            evidence(quote = "受伤", sourceKind = CharacterConsistencyEvidenceSource.CHARACTER_STATE, sourceId = stateId.value, field = "physicalState.label"),
            evidence(quote = "左臂", sourceKind = CharacterConsistencyEvidenceSource.CHARACTER_STATE, sourceId = stateId.value, field = "physicalState.details"),
            evidence(quote = "恐惧", sourceKind = CharacterConsistencyEvidenceSource.CHARACTER_STATE, sourceId = stateId.value, field = "emotionalState.label"),
            evidence(quote = "0.8", sourceKind = CharacterConsistencyEvidenceSource.CHARACTER_STATE, sourceId = stateId.value, field = "emotionalState.intensity"),
            evidence(quote = "c2", sourceKind = CharacterConsistencyEvidenceSource.CHARACTER_STATE, sourceId = stateId.value, field = "relationships[0].otherCharacterId"),
            evidence(quote = "师徒", sourceKind = CharacterConsistencyEvidenceSource.CHARACTER_STATE, sourceId = stateId.value, field = "relationships[0].relation"),
            evidence(quote = "k1", sourceKind = CharacterConsistencyEvidenceSource.CHARACTER_STATE, sourceId = stateId.value, field = "knownFacts[0]"),
            evidence(quote = "k2", sourceKind = CharacterConsistencyEvidenceSource.CHARACTER_STATE, sourceId = stateId.value, field = "unknownFacts[0]"),
            evidence(quote = "剑术", sourceKind = CharacterConsistencyEvidenceSource.CHARACTER_STATE, sourceId = stateId.value, field = "abilities[0].name"),
            evidence(quote = "御剑", sourceKind = CharacterConsistencyEvidenceSource.CHARACTER_STATE, sourceId = stateId.value, field = "abilities[0].description"),
            evidence(quote = "物理", sourceKind = CharacterConsistencyEvidenceSource.WORLD_RULE, sourceId = ruleId.value, field = "category"),
            evidence(quote = "林岚参加试炼", sourceKind = CharacterConsistencyEvidenceSource.EVENT, sourceId = eventId.value, field = "description"),
            evidence(quote = "试炼开始", sourceKind = CharacterConsistencyEvidenceSource.EVENT, sourceId = eventId.value, field = "what"),
            evidence(quote = "MAIN_PLOT", sourceKind = CharacterConsistencyEvidenceSource.EVENT, sourceId = eventId.value, field = "type"),
            evidence(quote = "CONFIRMED", sourceKind = CharacterConsistencyEvidenceSource.EVENT, sourceId = eventId.value, field = "status"),
            evidence(quote = "c1", sourceKind = CharacterConsistencyEvidenceSource.EVENT, sourceId = eventId.value, field = "who[0]"),
            evidence(quote = "UNKNOWN", sourceKind = CharacterConsistencyEvidenceSource.TIMELINE, sourceId = timelineId.value, field = "timeType"),
            evidence(quote = "PLANTED", sourceKind = CharacterConsistencyEvidenceSource.FORESHADOW, sourceId = foreshadowId.value, field = "state"),
            evidence(quote = "遇险", sourceKind = CharacterConsistencyEvidenceSource.PLAN, sourceId = planId.value, field = "expectedEvents[0]"),
            evidence(quote = "不能超自然", sourceKind = CharacterConsistencyEvidenceSource.PLAN, sourceId = planId.value, field = "constraints[0]"),
            evidence(quote = "主角死亡", sourceKind = CharacterConsistencyEvidenceSource.PLAN, sourceId = planId.value, field = "forbiddenEvents[0]"),
            evidence(quote = "紧张", sourceKind = CharacterConsistencyEvidenceSource.PLAN, sourceId = planId.value, field = "emotionalDirection"),
            evidence(quote = "黑影出现", sourceKind = CharacterConsistencyEvidenceSource.PLAN, sourceId = planId.value, field = "endingHook"),
            evidence(quote = "保护妹妹", sourceKind = CharacterConsistencyEvidenceSource.PLAN, sourceId = planId.value, field = "characterGoals[c1]"),
        )
        cases.forEach { ev ->
            val result = validate(
                raw = raw(
                    CharacterConsistencyStatus.ISSUES,
                    findings = listOf(finding(evidence = listOf(ev))),
                ),
            )
            assertEquals(1, result.findings.size, "field=${ev.field}")
        }
    }

    @Test
    fun `memory field must be content`() {
        assertInvalid(CharacterConsistencyErrorCodes.EVIDENCE_FIELD_UNKNOWN) {
            validate(
                raw = raw(
                    CharacterConsistencyStatus.ISSUES,
                    findings = listOf(
                        finding(
                            evidence = listOf(
                                evidence(
                                    quote = "神秘人出现",
                                    sourceKind = CharacterConsistencyEvidenceSource.MEMORY,
                                    sourceId = null,
                                    field = "bad",
                                    memoryLayer = MemoryLayer.WRITING,
                                    sourceIndex = 0,
                                ),
                            ),
                        ),
                    ),
                ),
            )
        }
    }

    @Test
    fun `each source rejects unknown quote and invalid offset`() {
        val cases = listOf(
            Triple(CharacterConsistencyEvidenceSource.CURRENT_DRAFT, currentDraftId.value, "她很胆小"),
            Triple(CharacterConsistencyEvidenceSource.PRIOR_CANONICAL, priorDraftId.value, "林岚说她不怕黑"),
            Triple(CharacterConsistencyEvidenceSource.CHARACTER_PROFILE, characterId.value, "胆小"),
            Triple(CharacterConsistencyEvidenceSource.CHARACTER_STATE, stateId.value, "找到真相"),
            Triple(CharacterConsistencyEvidenceSource.WORLD_RULE, ruleId.value, "修士不能飞行"),
            Triple(CharacterConsistencyEvidenceSource.EVENT, eventId.value, "青云试炼"),
            Triple(CharacterConsistencyEvidenceSource.TIMELINE, timelineId.value, "试炼之后"),
            Triple(CharacterConsistencyEvidenceSource.FORESHADOW, foreshadowId.value, "黑暗中的眼睛"),
            Triple(CharacterConsistencyEvidenceSource.PLAN, planId.value, "揭开秘密"),
        )
        cases.forEach { (kind, sourceId, validQuote) ->
            val field = when (kind) {
                CharacterConsistencyEvidenceSource.CURRENT_DRAFT,
                CharacterConsistencyEvidenceSource.PRIOR_CANONICAL,
                CharacterConsistencyEvidenceSource.WORLD_RULE,
                CharacterConsistencyEvidenceSource.FORESHADOW,
                -> "content"
                CharacterConsistencyEvidenceSource.CHARACTER_PROFILE -> "personality[0]"
                CharacterConsistencyEvidenceSource.CHARACTER_STATE -> "currentGoal"
                CharacterConsistencyEvidenceSource.EVENT -> "name"
                CharacterConsistencyEvidenceSource.TIMELINE -> "description"
                CharacterConsistencyEvidenceSource.PLAN -> "chapterGoal"
                CharacterConsistencyEvidenceSource.MEMORY -> "content"
            }
            assertInvalid(CharacterConsistencyErrorCodes.EVIDENCE_QUOTE_NOT_FOUND) {
                validate(
                    raw = raw(
                        CharacterConsistencyStatus.ISSUES,
                        findings = listOf(
                            finding(
                                evidence = listOf(
                                    evidence(
                                        quote = "不存在的句子",
                                        sourceKind = kind,
                                        sourceId = sourceId,
                                        field = field,
                                    ),
                                ),
                            ),
                        ),
                    ),
                )
            }
            assertInvalid(CharacterConsistencyErrorCodes.EVIDENCE_OFFSET_INVALID) {
                validate(
                    raw = raw(
                        CharacterConsistencyStatus.ISSUES,
                        findings = listOf(
                            finding(
                                evidence = listOf(
                                    evidence(
                                        quote = validQuote,
                                        sourceKind = kind,
                                        sourceId = sourceId,
                                        field = field,
                                        startOffset = 0,
                                        endOffset = 999,
                                    ),
                                ),
                            ),
                        ),
                    ),
                )
            }
        }

        assertInvalid(CharacterConsistencyErrorCodes.EVIDENCE_QUOTE_NOT_FOUND) {
            validate(
                raw = raw(
                    CharacterConsistencyStatus.ISSUES,
                    findings = listOf(
                        finding(
                            evidence = listOf(
                                evidence(
                                    quote = "不存在的句子",
                                    sourceKind = CharacterConsistencyEvidenceSource.MEMORY,
                                    sourceId = null,
                                    field = "content",
                                    memoryLayer = MemoryLayer.WRITING,
                                    sourceIndex = 0,
                                ),
                            ),
                        ),
                    ),
                ),
            )
        }
        assertInvalid(CharacterConsistencyErrorCodes.EVIDENCE_OFFSET_INVALID) {
            validate(
                raw = raw(
                    CharacterConsistencyStatus.ISSUES,
                    findings = listOf(
                        finding(
                            evidence = listOf(
                                evidence(
                                    quote = "神秘人出现",
                                    sourceKind = CharacterConsistencyEvidenceSource.MEMORY,
                                    sourceId = null,
                                    field = "content",
                                    memoryLayer = MemoryLayer.WRITING,
                                    sourceIndex = 0,
                                    startOffset = 0,
                                    endOffset = 999,
                                ),
                            ),
                        ),
                    ),
                ),
            )
        }
    }

    @Test
    fun `validator only uses the provided world snapshot`() {
        val ev = evidence(
            quote = "神秘人出现",
            sourceKind = CharacterConsistencyEvidenceSource.MEMORY,
            sourceId = null,
            field = "content",
            memoryLayer = MemoryLayer.WRITING,
            sourceIndex = 0,
        )
        val raw = raw(
            CharacterConsistencyStatus.ISSUES,
            findings = listOf(finding(evidence = listOf(ev))),
        )
        validate(world = world(memories = listOf("神秘人出现")), raw = raw)
        assertInvalid(CharacterConsistencyErrorCodes.EVIDENCE_QUOTE_NOT_FOUND) {
            validate(world = world(memories = listOf("另一个人出现")), raw = raw)
        }
    }

    /* ---------------- T8 sort / dedupe / truncate ---------------- */

    @Test
    fun `findings are sorted by severity type character and message`() {
        val result = validate(
            raw = raw(
                CharacterConsistencyStatus.ISSUES,
                findings = listOf(
                    finding(type = CharacterConsistencyFindingType.GOAL, severity = IssueSeverity.INFO, characterId = character2Id.value, characterName = "顾寒", message = "b"),
                    finding(type = CharacterConsistencyFindingType.TRAIT, severity = IssueSeverity.ERROR, characterId = characterId.value, characterName = "林岚", message = "a"),
                    finding(type = CharacterConsistencyFindingType.GOAL, severity = IssueSeverity.WARNING, characterId = characterId.value, characterName = "林岚", message = "c"),
                ),
                checkedCharacters = listOf(rawChecked(), rawChecked(character2Id, "顾寒")),
            ),
        )
        assertEquals(
            listOf(IssueSeverity.ERROR, IssueSeverity.WARNING, IssueSeverity.INFO),
            result.findings.map { it.severity },
        )
        assertEquals("a", result.findings[0].message)
        assertEquals("c", result.findings[1].message)
        assertEquals("b", result.findings[2].message)
    }

    @Test
    fun `duplicate findings keep one`() {
        val result = validate(
            raw = raw(
                CharacterConsistencyStatus.ISSUES,
                findings = listOf(finding(), finding()),
            ),
        )
        assertEquals(1, result.findings.size)
        assertTrue(!result.truncated)
    }

    @Test
    fun `more than fifty findings are truncated`() {
        val findings = (0 until 51).map { finding(message = "m$it") }
        val result = validate(
            raw = raw(CharacterConsistencyStatus.ISSUES, findings = findings),
        )
        assertEquals(50, result.findings.size)
        assertTrue(result.truncated)
    }

    @Test
    fun `missing inputs are deduplicated and sorted`() {
        val result = validate(
            world = world(states = emptyList(), rules = emptyList()),
            input = input(missingInputs = listOf(CharacterConsistencyMissingInput.WORLD_RULE)),
            raw = raw(
                CharacterConsistencyStatus.INSUFFICIENT_EVIDENCE,
                checkedCharacters = emptyList(),
                missingInputs = listOf(
                    CharacterConsistencyMissingInput.CHARACTER_STATE,
                    CharacterConsistencyMissingInput.WORLD_RULE,
                    CharacterConsistencyMissingInput.CHARACTER_STATE,
                ),
            ),
        )
        assertEquals(
            listOf(
                CharacterConsistencyMissingInput.CHARACTER_STATE,
                CharacterConsistencyMissingInput.WORLD_RULE,
            ),
            result.missingInputs,
        )
    }

    /* ---------------- T10 input/snapshot consistency ---------------- */

    @Test
    fun `claimed missing input must actually be missing`() {
        assertInvalid(CharacterConsistencyErrorCodes.BASIS_INVALID) {
            validate(
                input = input(missingInputs = listOf(CharacterConsistencyMissingInput.CHARACTER_PROFILE)),
                raw = raw(
                    CharacterConsistencyStatus.INSUFFICIENT_EVIDENCE,
                    checkedCharacters = emptyList(),
                    missingInputs = listOf(CharacterConsistencyMissingInput.CHARACTER_PROFILE),
                ),
            )
        }
    }

    @Test
    fun `coverage must match input`() {
        val draft = CharacterConsistencyDraft(currentDraftId, currentContent)
        assertInvalid(CharacterConsistencyErrorCodes.BASIS_INVALID) {
            validate(
                input = input(
                    currentDraft = draft,
                    coverage = CharacterConsistencyInputCoverage(
                        currentDraftFromContextPack = false,
                        planProvided = true,
                        priorCanonicalCount = 1,
                    ),
                ),
                raw = raw(CharacterConsistencyStatus.PASS),
            )
        }
        assertInvalid(CharacterConsistencyErrorCodes.BASIS_INVALID) {
            validate(
                input = input(
                    coverage = CharacterConsistencyInputCoverage(
                        currentDraftFromContextPack = true,
                        planProvided = true,
                        priorCanonicalCount = 2,
                    ),
                ),
                raw = raw(CharacterConsistencyStatus.PASS),
            )
        }
    }

    @Test
    fun `world snapshot must match input scope`() {
        assertInvalid(CharacterConsistencyErrorCodes.BASIS_INVALID) {
            validate(
                world = world(novelId = NovelId("n2")),
                raw = raw(CharacterConsistencyStatus.PASS),
            )
        }
        assertInvalid(CharacterConsistencyErrorCodes.BASIS_INVALID) {
            validate(
                world = world(variantId = VariantId("v1")),
                raw = raw(CharacterConsistencyStatus.PASS),
            )
        }
    }

    @Test
    fun `prior canonical order and identity are validated`() {
        val outOfOrder = listOf(
            priorChapter(id = ChapterId("ch2"), order = 2, draftId = DraftId("d2")),
            priorChapter(id = ChapterId("ch1"), order = 1, draftId = DraftId("d1x")),
        )
        assertInvalid(CharacterConsistencyErrorCodes.BASIS_INVALID) {
            validate(
                input = input(
                    priorCanonical = outOfOrder,
                    coverage = coverageFor(CharacterConsistencyDraft(currentDraftId, currentContent), plan(), outOfOrder),
                ),
                raw = raw(CharacterConsistencyStatus.PASS),
            )
        }
        val duplicate = listOf(
            priorChapter(id = ChapterId("ch1"), order = 1, draftId = priorDraftId),
            priorChapter(id = ChapterId("ch2"), order = 2, draftId = priorDraftId),
        )
        assertInvalid(CharacterConsistencyErrorCodes.BASIS_INVALID) {
            validate(
                input = input(
                    priorCanonical = duplicate,
                    coverage = coverageFor(CharacterConsistencyDraft(currentDraftId, currentContent), plan(), duplicate),
                ),
                raw = raw(CharacterConsistencyStatus.PASS),
            )
        }
    }

    @Test
    fun `more than three prior canonical chapters are rejected`() {
        val prior = (0..3).map { index ->
            priorChapter(
                id = ChapterId("ch$index"),
                order = index,
                draftId = DraftId("d$index"),
            )
        }
        val draft = CharacterConsistencyDraft(currentDraftId, currentContent)
        assertInvalid(CharacterConsistencyErrorCodes.BASIS_INVALID) {
            validate(
                input = input(
                    currentDraft = draft,
                    priorCanonical = prior,
                    coverage = coverageFor(draft, plan(), prior),
                ),
                raw = raw(CharacterConsistencyStatus.PASS),
            )
        }
    }

    @Test
    fun `validator errors map to typed application errors`() {
        val ex = assertFailsWith<CharacterConsistencyException.InvalidResult> {
            validate(raw = raw(CharacterConsistencyStatus.PASS, checkedCharacters = emptyList()))
        }
        val mapped = ErrorMapper.map(ex)
        assertIs<ApplicationError.InvalidCharacterConsistencyResult>(mapped.error)
        assertTrue(mapped.error.detail.contains(ex.code))
    }

    @Test
    fun `validator does not read repositories or tools`() {
        val source = File(
            "src/main/kotlin/com/qianyan/application/usecase/writing/consistency/CharacterConsistencyValidator.kt",
        ).readText()
        listOf(
            "Repository",
            "ProductToolService",
            "LLMGateway",
            "SqlDriver",
            "DraftRepository",
            "WriterUseCases",
            "WorldModelReadUseCases",
        ).forEach { token ->
            assertTrue(token !in source, "Validator 不得依赖 $token（只使用传入快照）")
        }
    }
}
