package com.qianyan.agent.agents

import com.qianyan.model.AgentId
import com.qianyan.model.IntentType
import com.qianyan.model.action.AgentActionKind
import com.qianyan.model.agent.Capability
import com.qianyan.model.agent.ToolName
import com.qianyan.model.context.ContextSourceKind
import com.qianyan.model.skill.Skill
import com.qianyan.model.skill.SkillBinding
import com.qianyan.model.skill.SkillId

/**
 * I7 · 五项核心 Skill（§6）。
 *
 * 全部是**声明**：能力类别 + 适用目的 + 需要哪些 Context 类型 + 预期使用的既有 Product Tool +
 * 预期动作类别 + 实现该能力的**既有** Agent 绑定。本对象不含任何执行逻辑。
 *
 * 映射关系（architecture §11 的七项能力 → I7 五类）：
 * ```
 * STORY_PLANNING          ← Planning Skill           → 既有 PlannerAgent
 * WRITING                 ← Writing Skill            → 既有 WriterAgent
 * REWRITE_REVISION        ← Revision Skill           → 既有 RevisionAgent
 * ANALYSIS_CRITIQUE       ← Critique Skill           → 既有 CritiqueAgent
 *                            （Consistency / Prose Quality 属未来能力，I7 不新建）
 * KNOWLEDGE_WORLD_MODEL   ← Knowledge Update Skill   → 既有 KnowledgeUpdateAgent
 * ```
 *
 * 绑定来源说明：`agentId` / `ToolName` 采用**字面稳定 ID**，由 `:application` 侧测试
 * 与真实 `AgentContract.agentId`、真实已注册 Product Tool 名逐一比对（避免跨模块反向依赖）。
 */
object CoreSkills {

    // ---- 能力类别名（复用既有 Capability 模型；供匹配/审阅识别） ----

    const val CAPABILITY_STORY_PLANNING = "story-planning"
    const val CAPABILITY_WRITING = "writing"
    const val CAPABILITY_REWRITE_REVISION = "rewrite-revision"
    const val CAPABILITY_ANALYSIS_CRITIQUE = "analysis-critique"
    const val CAPABILITY_KNOWLEDGE_WORLD_MODEL = "knowledge-world-model"

    // ---- 既有 Agent 的稳定 ID（= 既有 AgentContract.agentId；不持有实现） ----

    private const val AGENT_STORY_PLANNER = "story-planner"
    private const val AGENT_STORY_WRITER = "story-writer"
    private const val AGENT_STORY_REVISION = "story-writer-revision"
    private const val AGENT_STORY_CRITIC = "story-critic"
    private const val AGENT_KNOWLEDGE_UPDATE = "knowledge-update"

    // ---- 既有只读 Product Tool 注册名（I5；只声明，不执行） ----

    private const val TOOL_GET_PROJECT = "get_project"
    private const val TOOL_GET_PROJECT_STATE = "get_project_state"
    private const val TOOL_GET_NOVEL = "get_novel"
    private const val TOOL_LIST_CHAPTERS = "list_chapters"
    private const val TOOL_GET_CHAPTER = "get_chapter"
    private const val TOOL_GET_LATEST_DRAFT = "get_latest_draft"
    private const val TOOL_SEARCH_VOCABULARY = "search_vocabulary"

    private fun tool(name: String): ToolName = ToolName(name)

    /** 1 · Story Planning：从创作目标 / 用户意图形成结构化故事计划（复用既有 Planning 能力）。 */
    val STORY_PLANNING: Skill = Skill(
        skillId = SkillId("skill.story-planning"),
        name = "Story Planning",
        description = "从创作目标 / 用户意图形成结构化故事计划（分卷 / 大纲 / 章节计划），复用既有 Planning 与 StoryFoundation 能力",
        capabilities = listOf(Capability(CAPABILITY_STORY_PLANNING, "结构化故事规划方法论")),
        purposes = listOf(IntentType.PLAN, IntentType.CUSTOM),
        requiredContext = listOf(
            ContextSourceKind.PROJECT,
            ContextSourceKind.PROJECT_STATE,
            ContextSourceKind.NOVEL,
            ContextSourceKind.CHAPTER,
            ContextSourceKind.STORY_FOUNDATION,
        ),
        allowedTools = listOf(
            tool(TOOL_GET_PROJECT),
            tool(TOOL_GET_PROJECT_STATE),
            tool(TOOL_GET_NOVEL),
            tool(TOOL_LIST_CHAPTERS),
            tool(TOOL_GET_CHAPTER),
        ),
        expectedActions = listOf(AgentActionKind.READ, AgentActionKind.ANALYZE),
        bindings = listOf(SkillBinding(AgentId(AGENT_STORY_PLANNER), "既有 PlannerAgent（固定五段之一的规划阶段）")),
    )

    /** 2 · Writing：依据当前 Context / 故事计划 / 运行态执行正文创作（复用既有 Writing 能力）。 */
    val WRITING: Skill = Skill(
        skillId = SkillId("skill.writing"),
        name = "Writing",
        description = "依据当前 Context / 故事计划 / 运行态执行正文创作（新写 / 继续写 / 局部写作），复用既有 Writer 能力",
        capabilities = listOf(Capability(CAPABILITY_WRITING, "正文创作方法论")),
        purposes = listOf(IntentType.CONTINUE, IntentType.EXPAND, IntentType.CUSTOM),
        requiredContext = listOf(
            ContextSourceKind.PROJECT_STATE,
            ContextSourceKind.CHAPTER,
            ContextSourceKind.DRAFT,
            ContextSourceKind.STORY_FOUNDATION,
            ContextSourceKind.VOCABULARY,
        ),
        allowedTools = listOf(
            tool(TOOL_GET_PROJECT_STATE),
            tool(TOOL_GET_NOVEL),
            tool(TOOL_LIST_CHAPTERS),
            tool(TOOL_GET_CHAPTER),
            tool(TOOL_GET_LATEST_DRAFT),
            tool(TOOL_SEARCH_VOCABULARY),
        ),
        // 声明"未来 Working Draft 入口"的预期动作；I7 **不实现** Working Draft 执行。
        expectedActions = listOf(AgentActionKind.READ, AgentActionKind.CREATE_WORKING_DRAFT),
        bindings = listOf(SkillBinding(AgentId(AGENT_STORY_WRITER), "既有 WriterAgent（正文创作阶段）")),
    )

    /** 3 · Rewrite / Revision：按用户要求修改已有文本或创作方案（复用既有 Revision 能力）。 */
    val REWRITE_REVISION: Skill = Skill(
        skillId = SkillId("skill.rewrite-revision"),
        name = "Rewrite / Revision",
        description = "按用户要求修改已有文本或创作方案（改写 / 润色 / 扩写 / 压缩 / 风格调整 / 局部重写），复用既有 Revision 能力",
        capabilities = listOf(Capability(CAPABILITY_REWRITE_REVISION, "改写与修订方法论")),
        purposes = listOf(IntentType.REWRITE, IntentType.EXPAND, IntentType.CUSTOM),
        requiredContext = listOf(
            ContextSourceKind.PROJECT_STATE,
            ContextSourceKind.CHAPTER,
            ContextSourceKind.DRAFT,
            ContextSourceKind.VOCABULARY,
        ),
        allowedTools = listOf(
            tool(TOOL_LIST_CHAPTERS),
            tool(TOOL_GET_CHAPTER),
            tool(TOOL_GET_LATEST_DRAFT),
            tool(TOOL_SEARCH_VOCABULARY),
        ),
        // 声明"改写落在 Working Draft"的预期动作；I7 **不实现** Canonical 修改 / Commit / Diff / Artifact。
        expectedActions = listOf(AgentActionKind.READ, AgentActionKind.EDIT_WORKING_DRAFT),
        bindings = listOf(SkillBinding(AgentId(AGENT_STORY_REVISION), "既有 RevisionAgent（修订阶段，计数 ≤ 3 门控保留）")),
    )

    /** 4 · Analysis / Critique：分析内容 / 结构 / 人物 / 世界观 / 连贯性并给出意见（复用既有 Critique 能力）。 */
    val ANALYSIS_CRITIQUE: Skill = Skill(
        skillId = SkillId("skill.analysis-critique"),
        name = "Analysis / Critique",
        description = "分析文本 / 结构 / 人物 / 世界观 / 连贯性并给出意见，复用既有 Critique 能力（Finding 体系属后续阶段）",
        capabilities = listOf(Capability(CAPABILITY_ANALYSIS_CRITIQUE, "分析与评审方法论")),
        purposes = listOf(IntentType.ANALYZE, IntentType.CUSTOM),
        requiredContext = listOf(
            ContextSourceKind.PROJECT,
            ContextSourceKind.NOVEL,
            ContextSourceKind.CHAPTER,
            ContextSourceKind.DRAFT,
            ContextSourceKind.STORY_FOUNDATION,
            ContextSourceKind.VOCABULARY,
        ),
        allowedTools = listOf(
            tool(TOOL_GET_NOVEL),
            tool(TOOL_LIST_CHAPTERS),
            tool(TOOL_GET_CHAPTER),
            tool(TOOL_GET_LATEST_DRAFT),
            tool(TOOL_SEARCH_VOCABULARY),
        ),
        expectedActions = listOf(
            AgentActionKind.READ,
            AgentActionKind.SEARCH,
            AgentActionKind.ANALYZE,
            AgentActionKind.VALIDATE,
        ),
        bindings = listOf(SkillBinding(AgentId(AGENT_STORY_CRITIC), "既有 CritiqueAgent（评审阶段）")),
    )

    /** 5 · Knowledge / World Model：读取 / 分析 / 维护小说世界状态相关能力（复用既有 Knowledge Update 能力）。 */
    val KNOWLEDGE_WORLD_MODEL: Skill = Skill(
        skillId = SkillId("skill.knowledge-world-model"),
        name = "Knowledge / World Model",
        description = "从项目内容中读取 / 分析 / 维护人物、世界规则、时间线、关系、伏笔与剧情状态，复用既有 Knowledge Update 与 Story State 能力",
        capabilities = listOf(Capability(CAPABILITY_KNOWLEDGE_WORLD_MODEL, "知识与世界状态维护方法论")),
        purposes = listOf(IntentType.CONTINUE, IntentType.ANALYZE, IntentType.CUSTOM),
        requiredContext = listOf(
            ContextSourceKind.NOVEL,
            ContextSourceKind.CHAPTER,
            ContextSourceKind.DRAFT,
            ContextSourceKind.VOCABULARY,
        ),
        allowedTools = listOf(
            tool(TOOL_GET_NOVEL),
            tool(TOOL_GET_CHAPTER),
            tool(TOOL_GET_LATEST_DRAFT),
            tool(TOOL_SEARCH_VOCABULARY),
        ),
        // 声明"更新世界模型需人工确认"的预期动作；权限仍由 I2 ActionPolicy 判定。
        expectedActions = listOf(AgentActionKind.READ, AgentActionKind.UPDATE_WORLD_MODEL),
        bindings = listOf(SkillBinding(AgentId(AGENT_KNOWLEDGE_UPDATE), "既有 KnowledgeUpdateAgent（知识沉淀阶段）")),
    )

    /** 五项核心 Skill（声明顺序固定；Registry 查询结果与顺序无关）。 */
    fun all(): List<Skill> = listOf(
        STORY_PLANNING,
        WRITING,
        REWRITE_REVISION,
        ANALYSIS_CRITIQUE,
        KNOWLEDGE_WORLD_MODEL,
    )
}