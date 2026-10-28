package com.qianyan.application.usecase.writing.consistency

import com.qianyan.agent.runtime.AgentRuntime
import com.qianyan.agent.tool.ToolExecutor
import com.qianyan.agent.tool.ToolRegistry
import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.application.error.ErrorMapper
import com.qianyan.model.AgentId
import com.qianyan.model.agent.AgentContract
import com.qianyan.model.character.Character
import com.qianyan.model.character.CharacterState
import com.qianyan.model.context.StoryWorldContext
import com.qianyan.model.memory.MemoryLayer
import com.qianyan.model.story.ChapterPlan
import com.qianyan.model.story.Foreshadow
import com.qianyan.model.timeline.Event
import com.qianyan.model.timeline.TimelineEntry
import com.qianyan.model.world.WorldRule
import com.qianyan.provider.LLMGateway
import com.qianyan.provider.ModelProfile

/**
 * CharacterConsistency 只读 Agent（T-2B-2）。
 *
 * 单次 LLM 运行：渲染当前 Draft / 前章 Canonical / StoryWorldContext 证据源 → LLM 原始输出 →
 * [CharacterConsistencyParser] 严格解析为 Raw DTO。**不在此处做证据/状态校验**，
 * 校验由 [CharacterConsistencyValidator] 在 UseCase 中完成。
 *
 * 边界：不读持久化仓储 / 不写任何数据 / 不调用 Tool；只依赖 LLMGateway 抽象。
 */
internal class CharacterConsistencyAgent(
    private val gateway: LLMGateway,
    private val errorMapper: ErrorMapper,
    private val model: ModelProfile = ModelProfile.MOCK,
) {

    /** 实际执行 Agent 身份（T-2B-3 Skill.bindings 使用）。 */
    val agentId: AgentId get() = AGENT.agentId

    fun check(
        input: CharacterConsistencyInput,
        worldContext: StoryWorldContext,
    ): CharacterConsistencyRawResult = try {
        val result = runtime.run(AGENT, renderInput(input, worldContext))
        val raw = result.answer
            ?: throw CharacterConsistencyException.InvalidOutput(
                CharacterConsistencyErrorCodes.INVALID_OUTPUT,
                "character consistency agent returned no answer",
            )
        CharacterConsistencyParser.parse(raw)
    } catch (e: ApplicationException) {
        throw e
    } catch (e: CharacterConsistencyException) {
        throw errorMapper.map(e)
    } catch (t: Throwable) {
        // 不新增 ApplicationError 子类型：Agent/Provider 运行失败统一归到 Output 失败边界。
        throw ApplicationException(
            ApplicationError.InvalidCharacterConsistencyOutput(
                "character consistency agent failed: ${t.message ?: t::class.simpleName}",
            ),
        )
    }

    private val runtime: AgentRuntime = AgentRuntime(
        gateway = gateway,
        toolExecutor = ToolExecutor(ToolRegistry()),
        model = model,
    )

    /** 只给模型可引用的真实来源与 ID/field；不复制第二套事实。 */
    private fun renderInput(input: CharacterConsistencyInput, worldContext: StoryWorldContext): String = buildString {
        appendLine("【任务】只读检查当前章节的人物一致性；只输出 JSON，不要 Markdown/code fence。")
        appendLine("【作用域】novelId=${input.novelId.value}, variantId=${input.variantId?.value ?: "ORIGINAL"}, " +
            "chapterId=${input.chapterId.value}, scope=${input.scope.name}, contextPackId=${input.contextPackId ?: "无"}")
        appendLine()
        appendLine("【当前章 Draft】")
        val draft = input.currentDraft
        if (draft == null) {
            appendLine("（ContextPack 无 DRAFT item；currentDraft 缺失）")
        } else {
            appendLine("draftId: ${draft.draftId.value}")
            appendLine("content:")
            appendLine(draft.content)
        }
        appendLine()
        appendLine("【前章 Canonical（只可引用 PRIOR_CANONICAL）】")
        if (input.priorCanonical.isEmpty()) {
            appendLine("（无）")
        } else {
            input.priorCanonical.forEach { chapter ->
                appendLine("chapterId: ${chapter.chapterId.value}")
                appendLine("chapterOrder: ${chapter.chapterOrder}")
                appendLine("draftId: ${chapter.draftId.value}")
                appendLine("variantId: ${chapter.variantId?.value ?: "ORIGINAL"}")
                appendLine("content:")
                appendLine(chapter.content)
                appendLine()
            }
        }
        appendLine("【本章规划（可选；只可引用 PLAN）】")
        input.plan?.let { appendPlan(it) } ?: appendLine("（未提供）")
        appendLine()
        appendLine("【世界模型故事摘要】${worldContext.worldSummary.ifBlank { "（无）" }}")
        appendLine()
        appendLine("【Characters（只可引用 CHARACTER_PROFILE）】")
        if (worldContext.characters.isEmpty()) appendLine("（无）") else worldContext.characters.forEach { appendCharacter(it) }
        appendLine()
        appendLine("【CharacterStates（只可引用 CHARACTER_STATE）】")
        if (worldContext.characterStates.isEmpty()) appendLine("（无）") else worldContext.characterStates.forEach { appendState(it) }
        appendLine()
        appendLine("【WorldRules（只可引用 WORLD_RULE）】")
        if (worldContext.worldRules.isEmpty()) appendLine("（无）") else worldContext.worldRules.forEach { appendRule(it) }
        appendLine()
        appendLine("【Events（只可引用 EVENT）】")
        if (worldContext.events.isEmpty()) appendLine("（无）") else worldContext.events.forEach { appendEvent(it) }
        appendLine()
        appendLine("【Timeline（只可引用 TIMELINE）】")
        if (worldContext.timelineEntries.isEmpty()) appendLine("（无）") else worldContext.timelineEntries.forEach { appendTimeline(it) }
        appendLine()
        appendLine("【Foreshadows（只可引用 FORESHADOW）】")
        if (worldContext.foreshadows.isEmpty()) appendLine("（无）") else worldContext.foreshadows.forEach { appendForeshadow(it) }
        appendLine()
        appendLine("【Memory（只可引用 MEMORY；sourceId 必须为 null，用 layer + sourceIndex 定位）】")
        appendMemories(worldContext.canon, MemoryLayer.ORIGINAL)
        appendMemories(worldContext.worldState, MemoryLayer.CURRENT_STATE)
        appendMemories(worldContext.facts, MemoryLayer.LONG_TERM)
        appendMemories(worldContext.memories, MemoryLayer.WRITING)
        appendLine()
        appendLine("【输入覆盖】")
        appendLine("currentDraftFromContextPack=${input.coverage.currentDraftFromContextPack}")
        appendLine("planProvided=${input.coverage.planProvided}")
        appendLine("priorCanonicalCount=${input.coverage.priorCanonicalCount}")
        appendLine("priorCanonicalGapChapterIds=${input.coverage.priorCanonicalGapChapterIds.joinToString(",") { it.value }}")
        appendLine("missingInputs=${input.missingInputs.joinToString(",") { it.name }}")
        appendLine()
        appendLine("【证据与输出规则】")
        appendLine("- 只能引用上面实际出现的来源；quote 必须是来源 field 文本的精确子串。")
        appendLine("- sourceKind 必须是：CURRENT_DRAFT / PRIOR_CANONICAL / CHARACTER_PROFILE / CHARACTER_STATE / WORLD_RULE / EVENT / TIMELINE / FORESHADOW / PLAN / MEMORY。")
        appendLine("- CURRENT_DRAFT 与 PRIOR_CANONICAL 的 field 固定为 content，sourceId 为对应 draftId。")
        appendLine("- CHARACTER_PROFILE 字段：name / description / personality[i] / goals[i] / fears[i]。")
        appendLine("- CHARACTER_STATE 字段：currentGoal / currentMotivation / location / snapshotType / source / physicalState.label / physicalState.details / emotionalState.label / emotionalState.intensity / relationships[i].otherCharacterId / relationships[i].relation / knownFacts[i] / unknownFacts[i] / abilities[i].name / abilities[i].description。")
        appendLine("- WORLD_RULE 字段：content / category；EVENT 字段：name / description / what / type / status / who[i] / where[i]；TIMELINE 字段：description / timeType；FORESHADOW 字段：content / state。")
        appendLine("- PLAN 字段：chapterGoal / emotionalDirection / endingHook / mainConflict / expectedEvents[i] / constraints[i] / forbiddenEvents[i] / characterGoals[characterId]。")
        appendLine("- MEMORY 的 sourceId 为 null，必须给 memoryLayer（ORIGINAL/CURRENT_STATE/LONG_TERM/WRITING）和 sourceIndex（0 起）。")
        appendLine("- startOffset/endOffset 是 code point 偏移；若 quote 可能多处出现，请给 offsets。不得伪造 offset。")
        appendLine("- 缺失输入只能声明实际不存在的来源；PASS 不得有 findings；ISSUES 必须有 findings；INSUFFICIENT_EVIDENCE 必须有 missingInputs。")
        appendLine()
        appendLine("【输出 JSON（字段必须完整，禁止额外字段）】")
        appendLine(
            """{"status":"PASS|ISSUES|INSUFFICIENT_EVIDENCE","scope":"CURRENT_CHAPTER|CONTINUITY","findings":[{"type":"TRAIT|GOAL|FEAR|ABILITY|RELATIONSHIP|KNOWLEDGE|TEMPORAL_STATE|UNRESOLVED_CHARACTER|OTHER","severity":"ERROR|WARNING|INFO","characterId":"c1"或null,"characterName":"名字","message":"...","evidence":[{"quote":"...","sourceKind":"CURRENT_DRAFT|PRIOR_CANONICAL|CHARACTER_PROFILE|CHARACTER_STATE|WORLD_RULE|EVENT|TIMELINE|FORESHADOW|PLAN|MEMORY","sourceId":"..."或null,"field":"...","memoryLayer":"ORIGINAL|CURRENT_STATE|LONG_TERM|WRITING"或null,"sourceIndex":0或null,"startOffset":0或null,"endOffset":4或null}],"confidence":"EXPLICIT|INFERRED|UNKNOWN","suggestion":"..."或null}],"checkedCharacters":[{"characterId":"c1","name":"名字"}],"missingInputs":["CURRENT_DRAFT|CHARACTER_PROFILE|CHARACTER_STATE|PRIOR_CANONICAL|WORLD_RULE|EVENT|TIMELINE|FORESHADOW|MEMORY|PLAN"],"summary":"..."}""",
        )
    }.trimEnd()

    private fun StringBuilder.appendPlan(plan: ChapterPlan) {
        appendLine("chapterPlanId: ${plan.chapterPlanId.value}")
        appendLine("chapterGoal: ${plan.chapterGoal}")
        appendLine("emotionalDirection: ${plan.emotionalDirection}")
        appendLine("endingHook: ${plan.endingHook}")
        if (plan.mainConflict != null) appendLine("mainConflict: ${plan.mainConflict!!.value}")
        if (plan.expectedEvents.isNotEmpty()) appendLine("expectedEvents: ${plan.expectedEvents}")
        if (plan.constraints.isNotEmpty()) appendLine("constraints: ${plan.constraints}")
        if (plan.forbiddenEvents.isNotEmpty()) appendLine("forbiddenEvents: ${plan.forbiddenEvents}")
        if (plan.characterGoals.isNotEmpty()) {
            appendLine("characterGoals: ${plan.characterGoals.entries.joinToString("; ") { "${it.key.value}=${it.value}" }}")
        }
    }

    private fun StringBuilder.appendCharacter(character: Character) {
        appendLine("- characterId=${character.characterId.value} name=${character.name}")
        appendLine("  personality=${character.personality}")
        appendLine("  goals=${character.goals}")
        appendLine("  fears=${character.fears}")
        appendLine("  description=${character.description}")
    }

    private fun StringBuilder.appendState(state: CharacterState) {
        appendLine("- stateId=${state.id.value} characterId=${state.characterId.value} snapshotType=${state.snapshotType.name} source=${state.source.name}")
        appendLine("  currentGoal=${state.currentGoal ?: "null"}")
        appendLine("  currentMotivation=${state.currentMotivation ?: "null"}")
        appendLine("  location=${state.location?.value ?: "null"}")
        appendLine("  physicalState=${state.physicalState.label}/${state.physicalState.details}")
        appendLine("  emotionalState=${state.emotionalState.label}/${state.emotionalState.intensity}")
        appendLine("  relationships=${state.relationships.joinToString("; ") { "${it.otherCharacterId.value}:${it.relation}" }}")
        appendLine("  knownFacts=${state.knownFacts.joinToString(",") { it.value }}")
        appendLine("  unknownFacts=${state.unknownFacts.joinToString(",") { it.value }}")
        appendLine("  abilities=${state.abilities.joinToString("; ") { "${it.name}:${it.description}" }}")
    }

    private fun StringBuilder.appendRule(rule: WorldRule) {
        appendLine("- ruleId=${rule.ruleId.value} category=${rule.category} content=${rule.content}")
    }

    private fun StringBuilder.appendEvent(event: Event) {
        appendLine("- eventId=${event.id.value} name=${event.name} type=${event.type.name} status=${event.status.name}")
        appendLine("  description=${event.description}")
        appendLine("  what=${event.what}")
        appendLine("  who=${event.who.joinToString(",") { it.value }} where=${event.where.joinToString(",") { it.value }}")
    }

    private fun StringBuilder.appendTimeline(entry: TimelineEntry) {
        appendLine("- timelineEntryId=${entry.id.value} timeType=${entry.timeType.name} description=${entry.description}")
    }

    private fun StringBuilder.appendForeshadow(foreshadow: Foreshadow) {
        appendLine("- foreshadowId=${foreshadow.foreshadowId.value} state=${foreshadow.state.name} content=${foreshadow.content}")
    }

    private fun StringBuilder.appendMemories(items: List<String>, layer: MemoryLayer) {
        items.forEachIndexed { index, content ->
            appendLine("- layer=${layer.name} sourceIndex=$index content=$content")
        }
    }

    private companion object {
        val AGENT: AgentContract = AgentContract(
            agentId = AgentId("story-character-consistency"),
            name = "StoryCharacterConsistencyAgent",
            capabilities = emptyList(),
            allowedTools = emptyList(),
        )
    }
}
