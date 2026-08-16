# Qianyan（千言）— Architecture & Development Plan v4.1

> **版本**: v0.4.1-draft
> **日期**: 2026-08-16
> **状态**: 规划阶段 — 受控的 Agentic Writing System
>
> **图例**: ✅ 已确定 | 💡 建议 | ⚠️ TBD | 🔮 Future

> **文档说明**: 本文档为 V3 基础架构、V3.1 Agent Architecture、V4.1 Writing Intelligence 三部分的**融合版**。
> - 统一为一份连贯的 V4.1 架构文档，按逻辑分层组织，而非三段式补充。
> - 三版中重复的内容（写作示例、冲突处理、MVP/Future、一致性检查、决策状态）已合并去重。
> - 不推翻任何既有决策；V3.1 Agent Architecture 视为冻结，V4.1 只增加写作智能层。

---

# Part A 总览与架构原则

## 1. 项目定位与架构原则

### 1.1 项目定位

Qianyan（千言）是一个**本地优先**的长篇小说创作助手，目标不是把小说变成一堆标签，而是建立**可追溯、可验证、可演进的结构化知识体系**，并在此基础上提供**受控的 Agentic Writing**。

- Android 与 PC 双端，本地执行、本地数据、用户自带 API Key。
- 支持从 TXT 导入分析，也支持从想法开始创作。
- 第一版是 **Controlled Agent Workflow**：Agent 可以进行推理、决策与工具调用，但整个执行由 Agent Orchestrator / Workflow 控制，不允许完全自主、无限协作。

### 1.2 核心概念定义

| 概念 | 定义 |
|------|------|
| **Agent** | 负责理解、推理、规划、决策。调用 LLM + Tools。 |
| **Engine** | 负责确定性的业务能力和数据操作。不和 LLM 直接交互。 |
| **Tool** | Agent 可调用的标准化接口，封装 Engine 能力。 |
| **Task** | 负责任务生命周期、状态、进度、取消、恢复、重试。 |
| **Provider** | 负责调用具体 AI 模型（DeepSeek / MiMo）。 |
| **Storage** | 负责持久化。 |
| **Orchestrator** | 负责控制 Agent 的执行顺序、状态转换、工具权限与失败处理。 |

**Agent 必须明确：Agent ≠ Engine，Agent ≠ Database，Agent ≠ AI Provider，Agent ≠ Task Manager。**

### 1.3 分层架构总览

```
User
  ↓
UI（Android / Desktop）
  ↓
Application Layer（UserWritingRequest → AgentOrchestrator）
  ↓
Task Manager（Task 生命周期 / Checkpoint / Resume）
  ↓
Agent Orchestrator（工作流编排 / 状态转换 / 失败处理 / Human-in-the-Loop）
  ↓
Agents（Intent / Research / Story Planner / Writing / Critic / Knowledge）
  ↓
Tool Layer（Context / Knowledge / Writing / Prompt Service）
  ↓
Core Engines（Context / Novel / Validation / Knowledge / TXT / Task）
  ↓
Novel Knowledge / Memory / Retrieval（分层知识体系）
  ↓
Novel Engine
  ↓
Storage（SQLite）

同时接入:
  AI Provider: DeepSeek / MiMo（经 LLM Gateway，Agent 不直接绑定）
  Runtime: Android Runtime / PC Runtime（共享 Core，平台实现不同）
```

### 1.4 设计原则

```
1. AI 永不直接写数据库。
   所有 AI 输出必须经过 Output Validator → 才能进入 Knowledge Lifecycle。
2. Agent 不直接修改核心数据。
   写操作必须经过 Proposal → Validator → Engine。
3. 角色分离。
   Agent 推理/决策；Engine 执行/验证；Task 管理生命周期；Provider 调模型；Orchestrator 控制流程。
4. 受控 Agentic。
   第一版是受控 Workflow，非自主多 Agent 系统。
5. 结构化输出。
   所有 Agent 输出必须结构化，且经过 Validator 校验。
6. Knowledge 分级。
   AI 推断不能自动成为最高可信度事实；冲突必须进入 Conflict Detection。
7. 可追溯。
   每条 Knowledge 必须能追溯 Evidence；每条 AI 产出必须可定位来源。
8. 防无限循环。
   Revision 限 3 次；Workflow/Agent 状态可持久化、可暂停恢复。
9. 不污染原文。
   Original Novel 只读；Creative Project 派生独立，修改不反向污染。
10. 避免过度设计。
    第一版不做自动剧情重构、不做多 Agent 自由协商。
```

### 1.5 模块结构总览

```
core/
├── model/                               # 数据模型（无依赖）
│   ├── knowledge/                       # KnowledgeEntry / Evidence / Conflict / Confirmation
│   ├── story/                           # 🆕 Story Arc / Act / ChapterPlan / ScenePlan / Beat
│   │                                    #   CharacterArc / Conflict / Foreshadowing / Payoff / InfoState
│   ├── agent/                           # 🆕 AgentContract / AgentState / AgentPermission
│   ├── project/                         # ProjectManifest / SchemaVersion / BackupPackage
│   └── task/                            # Task / Checkpoint
│
├── engine/                              # 确定性引擎（不直接调 LLM）
│   ├── validation/                      # OutputValidator / SchemaValidator / ConflictDetector / Normalizer
│   ├── knowledge/                       # KnowledgeLifecycle / EvidenceTracker / ConfirmationManager
│   ├── context/                         # ContextEngine / ContextBuilder / ContextRetriever / ContextRanking
│   ├── txt/                             # TXTEngine（纯本地，零 AI 依赖）
│   ├── analysis/                        # AnalysisPipeline（9 Stage）
│   ├── novel/                           # NovelEngine
│   └── task/                            # TaskManager
│
├── agent/                               # 🆕 Agent Layer
│   ├── orchestration/                   # AgentOrchestrator / WritingWorkflow / WorkflowStateMachine / HumanInTheLoop
│   ├── agents/                          # IntentAgent / ResearchAgent / StoryPlannerAgent /
│   │                                    #   WritingAgent / CriticAgent / KnowledgeAgent
│   └── tool/                            # ToolRegistry / ToolPermission / ContextTools / KnowledgeTools / WritingTools
│
├── provider/                            # AI Provider 抽象接口 + DeepSeek / MiMo 实现
│   └── gateway/                         # 🆕 LLM Gateway
│
├── storage/                             # SQLite / FTS / Repository
│
├── migration/                           # MigrationEngine / MigrationStep / MigrationRegistry
│
├── android/                             # Android 客户端
├── desktop/                             # PC 客户端
└── runtime/                             # 平台 Runtime 抽象（文件系统 / 网络 / DB 实现）
```

### 1.6 版本演变（V3 → V3.1 → V4.1）

| 版本 | 核心内容 | 关系 |
|------|---------|------|
| V3 | Novel Knowledge / Memory / Evidence / Validation / Conflict / Creative Project / Backup / Migration | 基础数据与引擎层 |
| V3.1 | Agent Architecture（6 Agent + Orchestrator + Tool System + 状态机 + HITL + 权限矩阵） | 在 V3 之上新增 Agent Layer，冻结 |
| V4.1 | Writing Intelligence（Story Hierarchy / Arc / Act / Beat / Character Arc / Conflict / Foreshadowing / Payoff / Information Control / Pacing / Emotional Arc / Planning Scope） | 在 V3.1 之上新增写作智能层，不新增 Agent |

---

# Part B 数据层：Novel Knowledge 与 Story Intelligence Model

## 2. Novel Knowledge Model

### 2.1 Knowledge 条目模型

```kotlin
data class KnowledgeEntry(
    val id: KnowledgeId,
    val novelId: NovelId,
    val type: KnowledgeType,        // 知识类型
    val category: KnowledgeCategory, // 分类
    val content: String,             // 知识内容
    val factLevel: FactLevel,        // 事实等级
    val confidence: Float,           // 置信度 0.0-1.0
    val source: KnowledgeSource,     // 来源追溯
    val evidence: List<Evidence>,    // 证据列表
    val status: KnowledgeStatus,     // 状态
    val createdBy: KnowledgeCreator, // 创建者
    val confirmedBy: UserId?,        // 确认者
    val confirmedAt: Instant?,
    val version: Int,                // 版本号
    val previousVersionId: KnowledgeId?, // 上一版本
    val createdAt: Instant,
    val updatedAt: Instant
)

enum class FactLevel {
    EXPLICIT,           // 原文明确表达的事实
    INFERRED,           // AI 推断的知识
    UNCERTAIN,          // AI 无法确定的信息
    USER_CONFIRMED,     // 用户明确确认
    USER_CREATED,       // 用户直接创建
    GENERATED           // 创作过程中产生的新信息
}

enum class KnowledgeCategory {
    // 人物
    CHARACTER_IDENTITY, CHARACTER_PERSONALITY, CHARACTER_GOAL,
    CHARACTER_MOTIVATION, CHARACTER_ABILITY, CHARACTER_KNOWLEDGE,
    CHARACTER_SECRET, CHARACTER_RELATIONSHIP, CHARACTER_STATE,
    // 世界
    WORLD_RULE, WORLD_HISTORY, LOCATION_INFO, FACTION_INFO, ITEM_INFO,
    // 剧情
    EVENT, TIMELINE, PLOT_POINT,
    // 风格
    STYLE_FEATURE,
}

enum class KnowledgeStatus {
    DRAFT, ACTIVE, SUPERSEDED, REJECTED, DEPRECATED
}
```

### 2.2 Knowledge Source（来源追溯）

```kotlin
data class KnowledgeSource(
    val type: SourceType,
    val references: List<SourceReference>
)

enum class SourceType {
    ORIGINAL_TEXT, AI_ANALYSIS, AI_INFERENCE, USER_INPUT,
    USER_CONFIRMATION, AI_GENERATION, DERIVED
}

data class SourceReference(
    val refType: ReferenceType,
    val refId: String,          // 引用的实体 ID
    val description: String     // 引用说明
)

enum class ReferenceType {
    ORIGINAL_CHAPTER, TEXT_CHUNK, ANALYSIS_TASK, USER_INPUT_ID, KNOWLEDGE_ENTRY
}
```

### 2.3 事实等级升级规则

```
EXPLICIT ──────────────────────────────────────────────► USER_CONFIRMED
   (原文明确)                                               (用户确认)

INFERRED ──────► 用户确认 ──► USER_CONFIRMED
   (AI推断)                       │
                                  ├──► 用户拒绝 ──► REJECTED
                                  └──► 用户编辑 ──► USER_CREATED

UNCERTAIN ─────► 用户确认 ──► USER_CONFIRMED
   (不确定)         │
                   └──► 用户拒绝 ──► REJECTED

GENERATED ─────► 用户确认 ──► USER_CONFIRMED
   (创作产生)
```

**关键规则**：
- INFERRED 不能自动升级为 EXPLICIT。
- 只有用户确认才能提升可信等级。
- 用户确认后，FactLevel 变为 USER_CONFIRMED，confidence 变为 1.0。

---

## 3. Knowledge Lifecycle

### 3.1 完整生命周期

```
┌──────────────────────────────────────────────────────────────┐
│                    Knowledge Lifecycle                         │
│                                                               │
│  1. AI 产生原始输出                                            │
│     ▼                                                         │
│  2. Output Validator 验证                                      │
│     ├── 通过 → 进入步骤 3                                     │
│     └── 失败 → Retry（最多 3 次）→ 仍失败 → 标记为 UNCERTAIN   │
│     ▼                                                         │
│  3. 确定 FactLevel                                            │
│     ├── 来自原文 → EXPLICIT                                    │
│     ├── AI 推断 → INFERRED                                    │
│     ├── 无法确定 → UNCERTAIN                                  │
│     └── 创作产生 → GENERATED                                  │
│     ▼                                                         │
│  4. 关联 Evidence                                             │
│     ├── 追溯原文引用 / 记录 AI 分析任务 / 关联已有 Knowledge   │
│     ▼                                                         │
│  5. 冲突检测                                                   │
│     ├── 无冲突 → 进入步骤 6                                   │
│     └── 有冲突 → 标记冲突 → 降低 confidence → 进入步骤 6      │
│     ▼                                                         │
│  6. 写入决策                                                   │
│     ├── EXPLICIT (confidence ≥ 0.9) → 自动写入                │
│     ├── INFERRED / UNCERTAIN → 写入但标记为待确认             │
│     └── GENERATED → 写入但标记为创作产生                       │
│     ▼                                                         │
│  7. 用户确认（可选）                                           │
│     ├── 确认 → FactLevel → USER_CONFIRMED, confidence → 1.0   │
│     ├── 拒绝 → Status → REJECTED                              │
│     └── 编辑 → 创建新版本，Status → SUPERSEDED               │
│     ▼                                                         │
│  8. 持久化                                                     │
│     └── 写入 Novel Knowledge 存储                              │
└──────────────────────────────────────────────────────────────┘
```

### 3.2 不同 FactLevel 的写入策略

| FactLevel | 自动写入 | 需要确认 | 可被 AI 修改 | 可被用户修改 |
|-----------|:---:|:---:|:---:|:---:|
| EXPLICIT | ✅ | 否 | 重新分析时可覆盖 | 是 |
| INFERRED | ✅ | 建议 | 重新分析时可覆盖 | 是 |
| UNCERTAIN | ✅ | 建议 | 重新分析时可覆盖 | 是 |
| USER_CONFIRMED | N/A | 已确认 | ❌ | 是 |
| USER_CREATED | N/A | 已确认 | ❌ | 是 |
| GENERATED | ✅ | 建议 | 是 | 是 |

---

## 4. Evidence Model

### 4.1 Evidence 设计

```kotlin
data class Evidence(
    val id: EvidenceId,
    val knowledgeId: KnowledgeId,
    val sourceReference: SourceReference,
    val excerpt: String?,          // 原文摘录
    val confidence: Float?,
    val inferenceChain: List<InferenceStep>?,  // AI 推断链
    val createdAt: Instant
)
```

- 每条 Knowledge 可关联多条 Evidence。
- Evidence 支撑"Knowledge 可追溯"：任意事实都能定位到原文/任务/用户输入。
- AI 推断的 Knowledge 应记录 inferenceChain，便于用户理解为什么 AI 这样推断。

### 4.2 Evidence 追溯示例

```
Knowledge: "林默是青云门弟子"
  Evidence 1: { source: ORIGINAL_CHAPTER, ref: Chapter 18, chunk 18-4,
                excerpt: "林默深吸一口气，站在青云门山门前。" }
  Evidence 2: { source: ORIGINAL_CHAPTER, ref: Chapter 22, chunk 22-2,
                excerpt: "师兄弟们唤他一声'林师弟'。" }
```

### 4.3 冲突追溯

```
Conflict 检测到冲突时，会引用:
  existingKnowledge + existingEvidence  ← 已有知识的完整证据链
  newClaim + newClaimSource            ← 新声明的来源
这样用户可以判断: 哪个更可信，依据是什么。
```

---

## 5. Memory Model

### 5.1 四层 Memory 架构

```
┌──────────────────────────────────────────────────────────────┐
│                     Memory（四层）                              │
│                                                               │
│  ┌────────────────────────────────────────────────────────┐  │
│  │ Layer 1: Current State Memory（当前状态记忆）            │  │
│  │   - 人物/世界在当前剧情时间点的状态                       │  │
│  │   - CharacterState: { location, emotionalState, ... }    │  │
│  │   - 短时、高频更新                                       │  │
│  └────────────────────────────────────────────────────────┘  │
│  ┌────────────────────────────────────────────────────────┐  │
│  │ Layer 2: Writing Memory（创作记忆）                      │  │
│  │   - 创作过程中的决策、草稿、修订、用户偏好                │  │
│  │   - 例如: "用户希望减少环境描写" / "上一版在开头加了钩子" │  │
│  └────────────────────────────────────────────────────────┘  │
│  ┌────────────────────────────────────────────────────────┐  │
│  │ Layer 3: Long-term Memory（长期记忆）                    │  │
│  │   - 稳定事实：人物背景、世界观、已确认知识               │  │
│  │   - 低频率更新                                         │  │
│  └────────────────────────────────────────────────────────┘  │
│  ┌────────────────────────────────────────────────────────┐  │
│  │ Layer 4: Original Memory（原文记忆）                     │  │
│  │   - 来自 Original Novel 的原始文本内容与索引             │  │
│  │   - 只读                                               │  │
│  └────────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────┘
```

### 5.2 Memory 修改权限矩阵

| 层 | 来源 | AI 可写 | 用户可写 |
|----|------|:---:|:---:|
| Current State Memory | AI 创作/分析 | ✅（经 Validator） | ✅ |
| Writing Memory | AI 创作决策 | ✅（经 Validator） | ✅ |
| Long-term Memory | 已确认知识 | ❌（只能经确认） | ✅ |
| Original Memory | 原文 | ❌（只读） | ❌ |

---

## 6. Character 模型

### 6.1 Character State（瞬时快照）— 回答"现在在哪、什么状态"

```kotlin
data class CharacterState(
    val id: StateId,
    val characterId: CharacterId,
    val novelId: NovelId,
    val chapterId: ChapterId?,       // 关联章节
    val timelinePosition: TimelinePosition?, // 时间线位置
    val snapshotType: SnapshotType,
    // 人物当前状态
    val location: LocationId?,
    val physicalState: PhysicalState,
    val emotionalState: EmotionalState,
    val currentGoal: String?,
    val currentMotivation: String?,
    val relationships: List<RelationshipSnapshot>,
    // 人物所知信息
    val knownFacts: List<KnowledgeId>,
    val unknownFacts: List<KnowledgeId>,
    // 人物能力
    val abilities: List<AbilitySnapshot>,
    // 元数据
    val source: StateSource,
    val createdAt: Instant
)

enum class SnapshotType {
    CHAPTER_START, CHAPTER_END, KEY_EVENT, USER_DEFINED
}
```

### 6.2 Character Arc（轨迹）— 回答"人物在整个故事中如何变化"

将 Character 的 `arc` 文本字段扩展为结构化弧光。**Character State ≠ Character Arc**：
- State 是 Arc 上某一点的快照（瞬时）。
- Arc 是"如何走到这里、走向哪里"的轨迹（长期）。

```kotlin
data class CharacterArc(
    val characterArcId: ID,
    val characterId: CharacterId,
    val novelId: NovelId,
    val startingState: String,      // 弧光起点
    val coreDesire: String,         // 核心欲望
    val coreFear: String,           // 核心恐惧
    val falseBelief: String,        // 错误信念（需被打破）
    val internalConflict: String,   // 内在冲突
    val externalGoal: String,       // 外在目标
    val obstacles: List<String>,    // 阻碍
    val turningPoints: List<TurningPoint>, // 弧光转折点
    val growth: List<String>,       // 成长轨迹
    val regression: List<String>,   // 退步轨迹
    val finalState: String,         // 弧光终点
    val status: ArcStatus           // PLANNED / ACTIVE / COMPLETED / ABANDONED
)
```

### 6.3 Character Arc Progress（每章进度）

为了让"人物成长"不依赖 AI 自由发挥，Planner 必须能知道当前章节对弧光的贡献。

```kotlin
data class CharacterArcProgress(
    val progressId: ID,
    val characterArcId: ID,
    val chapterId: ChapterId?,
    val currentPhase: String,          // 当前阶段
    val currentGoal: String,           // 当前目标
    val currentMentalState: String,    // 当前心理状态
    val growthDirection: String,       // 当前成长方向（如"从自我怀疑→信任"）
    val recentChange: String,          // 最近变化（如 "+1 Trust"）
    val triggerEvent: EventId?,        // 触发事件
    val reachedTurningPoint: Boolean   // 是否到达转折点
)
```

示例：

```
Chapter 10:  Character Arc Progress: { recentChange: "+1 Trust" }
Chapter 20:  Character Arc Progress: { recentChange: "-1 Trust" }
Chapter 30:  Character Arc Progress: { reachedTurningPoint: true }
```

### 6.4 状态快照查询

```
查询 "林默在 Chapter 30 时的状态"：
1. 查找 chapterId ≤ 30 的最新 CharacterState 快照
2. 如果没有精确匹配 → 使用最近的前一个快照
3. 结合 Original Memory 中的事实

示例返回：
  location: "青云山"
  physicalState: { health: "良好", injuries: [] }
  emotionalState: { primary: "困惑", description: "刚得知师父的秘密" }
  relationships: [ { target: "师父", quality: "动摇" }, { target: "苏晴", quality: "信任" } ]
  knownFacts: ["青云山的秘密入口", "师父的真实身份"]
  unknownFacts: ["敌人的真正目的", "古剑的下落"]
```

---

## 7. Event Model

```kotlin
data class Event(
    val id: EventId,
    val novelId: NovelId,
    val name: String,
    val description: String,
    val type: EventType,
    val importance: Int,           // 1-10
    // 六要素
    val what: String,              // 事件内容
    val when: TimelinePosition?,   // 时间
    val where: List<LocationId>,   // 地点
    val who: List<CharacterId>,    // 参与者
    val cause: List<EventId>,      // 前因
    val consequence: List<EventId>, // 后果
    // 详细信息
    val participants: List<EventParticipant>,
    val evidence: List<Evidence>,
    val status: EventStatus,
    // 元数据
    val chapterId: ChapterId?,
    val factLevel: FactLevel,
    val createdAt: Instant
)

data class EventParticipant(
    val characterId: CharacterId,
    val role: ParticipantRole,     // ACTOR, OBSERVER, VICTIM, BENEFICIARY
    val actions: List<String>,
    val stateBefore: StateId?,     // 事件前状态
    val stateAfter: StateId?       // 事件后状态
)

enum class EventType {
    MAIN_PLOT, SUB_PLOT, CHARACTER_ARC, WORLD_EVENT, REVELATION, TRANSITION
}

enum class EventStatus {
    CONFIRMED, PLANNED, IN_PROGRESS, COMPLETED, CANCELLED
}
```

---

## 8. Timeline Model

```kotlin
data class TimelinePosition(
    val storyTime: StoryTime?,      // 故事内时间
    val chapterReference: ChapterReference?, // 章节参考
    val relativeTime: RelativeTime?, // 相对时间
    val confidence: Float           // 时间位置的置信度
)

data class StoryTime(
    val year: Int?, val month: Int?, val day: Int?, val hour: Int?,
    val era: String?,               // 时代/纪元
    val description: String         // "核战后第50年春天"
)

data class ChapterReference(
    val chapterId: ChapterId,
    val sceneIndex: Int?,
    val position: String?           // "Chapter 18 开头" / "Chapter 18 中段"
)

data class RelativeTime(
    val baseEventId: EventId,       // 参考事件
    val offset: String,             // "三天后" / "两周前"
    val relation: TimeRelation      // BEFORE, AFTER, DURING
)

enum class TimeRelation { BEFORE, AFTER, DURING, UNKNOWN }

data class TimelineEntry(
    val id: TimelineEntryId,
    val novelId: NovelId,
    val position: TimelinePosition,
    val eventId: EventId?,
    val description: String,
    val timeType: TimeType,         // ABSOLUTE / RELATIVE / CHAPTER_BASED / UNKNOWN
    val chapterId: ChapterId?
)
```

---

## 9. Story Hierarchy

### 9.1 层级总览

```
Novel
  └── Story Arc         剧情发展单位（≠ 文本单位）
        └── Act         结构容器（通用，不强制三幕）
              └── Chapter Plan    本章计划（正文生成前的输入）
                    └── Scene Plan 场景单位（场景必须产生变化）
                          └── Beat 场景内最小叙事推进单位
                                └── Draft  实际正文
```

### 9.2 每一层职责

| 层 | 类型 | 职责 | 关键点 |
|----|------|------|--------|
| **Novel** | 作品单位 | 整部作品的元信息：题材、POV、世界、主线 | 不承载具体剧情推进 |
| **Story Arc** | 剧情发展单位 | 一个完整起承转合的剧情段：从某状态到另一状态的迁移 | ≠ Chapter。Chapter 是文本组织单位，Arc 是剧情推进单位 |
| **Act** | 结构容器 | Arc 内部的阶段划分（如 展开→上升→高潮→收束） | 通用容器，不强制三幕结构 |
| **Chapter Plan** | 规划单位 | 一章要完成什么：目标、冲突、事件、伏笔、节奏、钩子 | 在正文生成之前存在；是 Writer 的输入，不是正文 |
| **Scene Plan** | 场景单位 | 一个时间/地点/人物集合内发生的戏剧单元 | 必须有 Entry State → Desired Exit State |
| **Beat** | 叙事推进单位 | Scene 内部最小的叙事推进步骤 | 序列推进场景 |
| **Draft** | 文本单位 | 最终正文 | 由 Writer 按 Beat 顺序生成 |

### 9.3 重要区分

- **Story Arc ≠ Chapter**：Chapter 决定"文本如何分章"，Arc 决定"剧情如何推进"。一个 Chapter 可跨越两个 Arc 的衔接，一个 Arc 可跨越多个 Chapter。
- **Scene ≠ Chapter**：Scene 是戏剧单位（时间/地点/人物集合），Chapter 是文本单位。一个 Chapter 可含多个 Scene。
- **Beat 不进入 Chapter 命名**：Beat 只存在于 Scene 内部，是 Writer 的分段依据与 Critic 的完成度检查依据。

### 9.4 Story Arc

Story Arc 是长篇小说的剧情发展单位，标记"故事从一个状态迁移到另一个状态"的完整过程。Planner 在规划任何章节前，必须知道当前处于哪个 Arc。

```kotlin
data class StoryArc(
    val arcId: ID,
    val novelId: NovelId,
    val name: String,              // 弧名，如"地下城之卷"
    val description: String,
    val purpose: String,           // 该弧在整体故事中的作用
    val startState: String,        // 弧开始时的故事状态
    val targetState: String,       // 弧结束时目标状态
    val mainConflict: ConflictId,  // 该弧主冲突
    val stakes: Stakes,            // 该弧核心利害
    val keyCharacters: List<CharacterId>,
    val keyEvents: List<EventId>,
    val turningPoints: List<TurningPoint>, // 转折点（位置 + 内容 + 影响）
    val climax: Climax,            // 高潮（位置 + 内容）
    val resolution: String,        // 收束方式
    val relatedForeshadowing: List<ForeshadowingId>,
    val expectedPayoffs: List<PayoffId>,
    val status: ArcStatus          // PLANNED / ACTIVE / COMPLETED / ABANDONED
)
```

**Planner 使用方式**："继续写这一章"不再是简单"当前章节 → 续写"，而是：

```
当前章节
+ 当前 Scene
+ 当前 Story Arc          ← 我们在推进哪条弧
+ 当前 Character Arc      ← 人物弧光在哪个阶段
+ 当前 unresolved conflicts ← 哪些冲突还开着
+ 当前 foreshadowing      ← 哪些伏笔在窗口期/待回收
→ 下一步剧情
```

### 9.5 Act

Act 是 Story Arc 内部的结构容器，划分弧的阶段性。**不强制任何结构**（三幕/四幕/自由均由用户与 Planner 决定）。

```
Arc
├── Act 1  展开
├── Act 2  上升
├── Act 3  高潮
└── Act 4  收束
```

```kotlin
data class Act(
    val actId: ID,
    val arcId: ArcId,
    val order: Int,
    val name: String,
    val goal: String,              // 本幕要达成的目标
    val conflict: String,          // 本幕主要冲突
    val turningPoint: String,      // 本幕转折点
    val majorEvents: List<EventId>,
    val characterChanges: List<CharacterArcProgressId>, // 本幕人物弧光变化
    val emotionalDirection: String, // 本幕情绪走向
    val endingCondition: String    // 本幕结束条件（满足则进入下一幕）
)
```

### 9.6 Chapter Plan

ChapterPlan 在 Chapter 正文生成之前存在。Chapter 保存实际正文，ChapterPlan 保存"本章要完成什么"。Writer 只能依据 ChapterPlan 写作。

```kotlin
data class ChapterPlan(
    val chapterPlanId: ID,
    val chapterId: ChapterId?,     // 关联章节（正文生成前可为空）
    val arcId: ArcId,
    val actId: ActId,
    val chapterGoal: String,       // 本章目标
    val mainConflict: ConflictId?,
    val characterGoals: Map<CharacterId, String>, // 各人物的本章目标
    val expectedEvents: List<String>,
    val requiredInformation: List<KnowledgeId>, // 本章需要的既有信息
    val foreshadowing: List<ForeshadowingId>,   // 本章要引入/推进的伏笔
    val payoffs: List<PayoffId>,                // 本章要回收的伏笔
    val emotionalDirection: String, // 本章情绪走向
    val pacing: PacingProfile?,     // 本章节奏（覆盖弧级默认）
    val endingHook: String,         // 章末钩子
    val constraints: List<String>,
    val forbiddenEvents: List<String>
)
```

> ChapterPlan 不是正文，是 Writer Agent 的输入。

### 9.7 Scene Plan

继续使用既有 ScenePlan，完善字段。核心增强：**Entry State / Desired Exit State**，强制"场景必须产生变化"，避免"人物聊了一大段但故事毫无推进"。

| 字段 | 类型 | 说明 | 状态 |
|------|------|------|:---:|
| sceneGoal | String | 场景目标 | 已有 |
| characters | List<CharacterRole> | 出场人物与角色 | 已有 |
| location | LocationRef | 地点 | 已有 |
| time | TimelinePosition | 时间 | 已有 |
| **entryState** | SceneState | 场景开始时的人物/局势状态 | 🆕 |
| **desiredExitState** | SceneState | 场景结束时目标状态 | 🆕 |
| conflict | String | 场景冲突 | 已有 |
| **stakes** | Stakes? | 本场景利害 | 🆕 |
| motivation | String | 动机 | 已有 |
| **emotionalState** | String | 场景情绪基调 | 🆕 |
| importantEvents | List<String> | 重要事件 | 已有 |
| discoveries | List<String> | 发现 | 已有 |
| **informationRevealed** | List<KnowledgeId> | 本场景揭露的信息 | 🆕 |
| **informationHidden** | List<KnowledgeId> | 本场景刻意隐藏的信息 | 🆕 |
| foreshadowing | List<ForeshadowingId> | 本场景伏笔 | 🆕 |
| **payoff** | List<PayoffId> | 本场景回收 | 🆕 |
| **characterChanges** | List<CharacterArcProgressId> | 本场景人物弧光变化 | 🆕 |
| events | List<String> | 事件 | 已有 |
| **pacing** | PacingProfile? | 本场景节奏 | 🆕 |
| **pov** | CharacterId? | 本场景 POV 视角 | 🆕 |
| **dialoguePurpose** | String | 对话目的 | 🆕 |
| revealLevel | RevealLevel | 揭露等级 | 已有 |
| constraints | List<String> | 约束 | 已有 |
| forbiddenEvents | List<String> | 禁止事件 | 已有 |
| expectedOutcome | String | 预期结果 | 已有 |
| **beats** | List<Beat> | 本场景的 Beat 序列 | 🆕 |

> **Entry State ≠ Desired Exit State**：若两者无实质差异，Critic 必须报错"场景无变化"。

### 9.8 Beat

Beat 是 Scene 内部最小的叙事推进单位，按顺序串起整个场景。

```
示例（主角进入地下城）:
Beat 1  进入
Beat 2  发现异常
Beat 3  遭遇阻碍
Beat 4  发现父亲留下的符号
Beat 5  情绪变化
Beat 6  决定继续深入
```

```kotlin
data class Beat(
    val beatId: ID,
    val sceneId: SceneId,
    val order: Int,                 // 场景内顺序
    val purpose: String,            // 该 Beat 的叙事功能
    val action: String,             // 发生什么
    val conflict: String?,          // 该 Beat 的冲突
    val characterReaction: Map<CharacterId, String>, // 人物反应
    val information: List<KnowledgeId>,              // 该 Beat 涉及的信息
    val emotionalChange: String,    // 情绪变化
    val result: String              // 该 Beat 的产出/结果
)
```

- **Writing Agent**：按 Beat 顺序生成正文段落。
- **Critic Agent**：逐个检查 Beat 是否完成自己的功能（purpose 是否达成、result 是否存在）。

---

## 10. Story Intelligence 支撑模型

### 10.1 Conflict System

**冲突类型**：

| 类型 | 说明 |
|------|------|
| External | 外部对抗（敌人/势力） |
| Internal | 内心挣扎 |
| Interpersonal | 人物之间 |
| Environmental | 环境/自然/空间 |
| Social | 社会/制度/群体 |
| Mystery / Information | 信息不对称/谜团 |

```kotlin
data class StoryConflict(
    val conflictId: ID,
    val type: ConflictType,
    val participants: List<CharacterId>,
    val goalA: String,             // 一方目标
    val goalB: String,             // 另一方目标
    val stakes: Stakes,            // 利害
    val status: ConflictStatus,    // OPEN / ESCALATING / RESOLVED / ABANDONED
    val escalation: String,        // 升级过程
    val resolutionCondition: String, // 解决条件
    val relatedArc: ArcId?,
    val relatedEvents: List<EventId>
)
```

**Planner 义务**：规划时必须加载当前所有 OPEN / ESCALATING 冲突，并确保：
- 每次规划至少推进或触碰一个 Open Conflict；
- 不引入与已解决冲突矛盾的剧情；
- 长期未触碰的冲突（如超过 N 章未推进）由 Critic 提示。

### 10.2 Stakes

为每个主要 Conflict 记录利害，回答"这个冲突为什么值得发生"。

```kotlin
data class Stakes(
    val personal: String?,      // 个人利害
    val relationship: String?,  // 关系利害
    val material: String?,      // 物质利害
    val social: String?,        // 社会利害
    val world: String?          // 世界利害
)
```

> 不要求每个场景/冲突都填满全部五类；按剧情需要取舍。Stakes 作为 embedded object 挂在 Conflict 与 ScenePlan 上，不单独建表。

### 10.3 Foreshadowing

结构化伏笔生命周期，支持长线铺垫与回收。Critic 必须防止"提前揭露"。

```kotlin
data class Foreshadowing(
    val foreshadowingId: ID,
    val description: String,
    val introducedAt: ChapterId / SceneId,   // 引入位置
    val relatedCharacters: List<CharacterId>,
    val relatedEvents: List<EventId>,
    val importance: Int,           // 1-10
    val expectedPayoff: String,    // 预期回收内容
    val payoffWindow: ChapterRange?, // 回收窗口（最早/最晚章节）
    val status: ForeshadowStatus   // PLANNED / INTRODUCED / DEVELOPING / PAYOFF_READY / RESOLVED / ABANDONED
)
```

示例：

```
Chapter 10: 黑色戒指出现（INTRODUCED）
  Foreshadowing: "黑色戒指与父亲有关"（DEVELOPING）
Chapter 50: 真相揭示（PAYOFF_READY → RESOLVED）
  Payoff
```

### 10.4 Payoff

伏笔必须能回收。Foreshadowing → Payoff 是显式关系，Critic 检查揭露时机是否符合约束。

```kotlin
data class Payoff(
    val payoffId: ID,
    val foreshadowingId: ForeshadowingId, // 对应伏笔（必填）
    val chapterId: ChapterId,
    val sceneId: SceneId?,
    val eventId: EventId?,
    val revealLevel: RevealLevel,
    val resolution: String          // 回收内容/结局
)
```

**Critic 检查规则**：
- 若用户约束"不要现在揭露父亲秘密"：
  - Foreshadowing 允许出现（引入/推进）。
  - Payoff 不允许出现（禁止进入 REVEALED）。
- 若某 Foreshadowing 处于 PAYOFF_READY 但长期未回收 → Critic 提示。

### 10.5 Information Control

支持悬疑与伏笔的关键："读者不知道" ≠ "主角不知道"。Planner 必须能区分"谁知道什么"。

```kotlin
enum class InformationState {
    KNOWN_TO_USER,          // 读者已知
    KNOWN_TO_CHARACTER,     // 角色已知（可细分到具体角色）
    HIDDEN,                 // 隐藏
    PARTIALLY_REVEALED,     // 部分揭露
    MISLEADING,             // 误导
    REVEALED                // 已完全揭露
}
```

- InformationState 挂接在 Knowledge 条目上（记录该信息的全局可见状态）。
- 配合 CharacterState 的 knownFacts / unknownFacts（逐角色视角）使用，两者互补不重复：前者是"信息本身的可见状态"，后者是"某个角色在某时刻知道的快照"。
- Planner 在规划揭露/隐藏时显式声明 InformationState 变化。
- Critic 检查 POV Knowledge：场景若使用 POV，该 POV 角色不应知道其"未知"信息。

### 10.6 Pacing

节奏控制。存在于 Story Arc / Chapter Plan / Scene Plan 三个层级，低层可覆盖高层。第一版不强制数值化。

```json
{
  "speed": "FAST",
  "tension": "HIGH",
  "dialogueRatio": 0.35,
  "descriptionDensity": "MEDIUM"
}
```

| 类型 | 说明 |
|------|------|
| Fast | 快节奏 |
| Normal | 常规 |
| Slow | 慢节奏 |
| TensionBuild | 张力累积 |
| Action | 动作 |
| Reflection | 反思/内心 |
| Recovery | 恢复/缓冲 |

- Writer 遵循 PacingProfile 控制段落密度、对话占比、描写密度。
- Critic 检查实际文本节奏是否偏离计划（如计划 TensionBuild 却写成大片日常）。

### 10.7 Emotional Arc

情绪弧线，用于 Arc / Chapter / Scene 三个层级。Scene 应产生情绪变化，但不要求每个 Scene 都有巨变。

```kotlin
data class EmotionalArc(
    val startEmotion: String,
    val risingEmotion: List<String>,
    val peakEmotion: String,
    val endEmotion: String,
    val emotionalChange: String
)
```

示例：

```
平静 → 疑惑 → 恐惧 → 紧张 → 决心
```

- Critic 检查：Scene 的 Entry/Exit 情绪是否与 EmotionalArc 一致；若计划有变化而文本无变化则报错。

---

## 11. Context & Retrieval

### 11.1 Context Ranking Model

```kotlin
data class ContextCandidate(
    val id: String,
    val type: ContextType,
    val content: String,
    val relevance: Float,       // 相关性 0.0-1.0
    val authority: Float,       // 权威性 0.0-1.0
    val priority: Float,        // 优先级 0.0-1.0
    val recency: Float,         // 时效性 0.0-1.0
    val tokenCost: Int,         // token 消耗
    val score: Float            // 综合评分
)

enum class ContextType {
    CURRENT_CHAPTER_TEXT, CURRENT_SCENE, CHARACTER_STATE, CURRENT_EVENT,
    DIRECT_CHARACTERS, RELATED_CHARACTERS, TIMELINE_POSITION, WORLD_RULES,
    RECENT_EVENTS, RELEVANT_HISTORY, RELEVANT_MEMORY, STYLE_PROFILE, WRITING_MEMORY,
    // 🆕 Story Intelligence 上下文
    CURRENT_STORY_ARC, CURRENT_ACT, CHAPTER_PLAN, CURRENT_CONFLICTS,
    CHARACTER_ARC_PROGRESS, OPEN_FORESHADOWING, EXPECTED_PAYOFFS,
    INFORMATION_STATE, PACING, EMOTIONAL_ARC
}
```

**排序策略**：

```
1. 收集候选 Context（当前章节前文 / 场景人物状态 / 时间线位置 / 世界规则 /
   近期事件 / 直接相关人物 / 相关历史 / Style Profile / Writing Memory /
   Story Intelligence 上下文按需收集）
2. 计算评分: score = relevance × authority × priority + recency_bonus
   relevance: 当前章节文本 1.0 / 场景人物状态 0.95 / 当前事件 0.9 /
              直接相关人物 0.85 / 世界规则 0.7 / 历史章节 0.3-0.6 / Style 0.5
   authority: USER_CONFIRMED 1.0 / EXPLICIT 0.9 / USER_CREATED 0.85 /
              GENERATED 0.6 / INFERRED 0.5 / UNCERTAIN 0.3
   priority:  Current State 1.0 / Current Event 0.9 / Character Info 0.8 /
              World Rules 0.7 / History 0.5 / Style 0.4
   recency_bonus: 最近章节 +0.1
3. Token Budget 分配（~6000 tokens 可配置）
   ├── 固定: System Prompt 500 / Style Profile 摘要 300 / Task Prompt 200
   └── 动态: 当前章节前文 2000 + 按 score 排序的候选 3000
4. 选择: 按 score 降序，逐个添加直到 budget 用尽，超出则截断
```

**Story Intelligence 上下文关键约束**：不全部塞进 Prompt。Context Engine 依据 Context Ranking + Token Budget 按相关性选择，仅把与当前规划相关的部分加入上下文；Research Agent 仍只负责"决定查什么"，实际检索仍走 Context Engine 的 Tool。

---

## 12. AI Output Validation

### 12.1 验证流程

```
AI Raw Output
  │
  ▼
Step 1: JSON Schema Validation
  ├── 检查输出是否符合预期 JSON Schema
  ├── 失败 → 尝试修复（常见格式错误）
  └── 仍失败 → Retry（最多 3 次）
Step 2: Type Checking（字段类型）
Step 3: Required Field Check（缺失 → 尝试推断 / 标记 UNCERTAIN）
Step 4: Enum Value Check（越界 → 最近匹配 / 默认值 + 标记）
Step 5: Reference Integrity Check（悬空引用标记）
Step 6: Data Completeness Check（不完整 → PARTIAL）
Step 7: Conflict Detection（有冲突 → 标记 + 降低 confidence）
Step 8: Normalization（名称/日期统一、去重、合并）
Step 9: Output → ValidationResult { passed, issues, normalizedData, conflicts }
```

### 12.2 验证结果模型

```kotlin
data class ValidationResult(
    val passed: Boolean,
    val issues: List<ValidationIssue>,
    val normalizedData: Map<String, Any>,
    val conflicts: List<KnowledgeConflict>,
    val partialSuccess: Boolean,  // 部分字段通过
    val failedFields: List<String>,
    val retryCount: Int
)

data class ValidationIssue(
    val field: String,
    val severity: IssueSeverity,  // ERROR / WARNING / INFO
    val message: String,
    val originalValue: Any?,
    val resolvedValue: Any?
)
```

### 12.3 Retry 策略

```
1. 第 1 次失败: 将错误信息注入 prompt，重新请求
2. 第 2 次失败: 简化 prompt，降低复杂度要求
3. 第 3 次失败: 标记为 UNCERTAIN，confidence 降低
每次 Retry 之间等待 1 秒 × retryCount
```

---

## 13. Conflict Detection

### 13.1 冲突检测模型

```kotlin
data class KnowledgeConflict(
    val id: ConflictId,
    val novelId: NovelId,
    val type: ConflictType,
    val severity: ConflictSeverity,
    // 已有知识
    val existingKnowledge: KnowledgeEntry,
    val existingEvidence: List<Evidence>,
    // 新声明
    val newClaim: String,
    val newClaimSource: ClaimSource,
    // 解决
    val resolution: ConflictResolution?,
    val resolvedBy: UserId?,
    val resolvedAt: Instant?,
    val createdAt: Instant
)

enum class ConflictType {
    CONTRADICTION, INCONSISTENCY, TIMELINE_CONFLICT,
    CHARACTER_CONFLICT, WORLD_RULE_VIOLATION
}

enum class ConflictSeverity {
    CRITICAL, MAJOR, MINOR, COSMETIC
}

enum class ClaimSource {
    AI_GENERATED, AI_INFERRED, USER_INPUT, TXT_ANALYSIS
}

enum class ResolutionDecision {
    KEEP_EXISTING, ACCEPT_NEW, MERGE, DEFER
}
```

### 13.2 触发时机与检测范围

```
触发时机:
1. AI 写作完成后，Consistency Check 阶段
2. AI 分析 Pipeline 的 Knowledge Integration 阶段
3. 用户手动修改 Knowledge 时
4. 导入新 TXT 时

检测范围:
- 人物状态冲突（同一时间点在两个不同地点）
- 事实冲突（同一人物有两个不同的背景故事）
- 时间线冲突（事件 A 在 B 之后，但 A 时间早于 B）
- 世界规则冲突（违反已建立的魔法体系规则）
```

### 13.3 Agent 视角的冲突处理

```
1. CriticAgent 在 Critique 阶段检测一致性冲突
   → 发现 Draft 与已有 Knowledge 矛盾 → 输出 CritiqueIssue (KNOWLEDGE_CONSISTENCY) → 触发 Revision

2. KnowledgeAgent 在 Knowledge Update 阶段检测冲突
   → Proposal 中的新事实与已有 Knowledge 矛盾 → 调用 knowledge:detectConflict
   → 若发现冲突:
     a. 新事实来自 AI 创作 → GENERATED（confidence 较低）
     b. 已有 Knowledge 来自 EXPLICIT → 冲突标记为 MAJOR
     c. 进入 HumanInTheLoop

3. 冲突解决优先级:
   已有 USER_CONFIRMED > 已有 EXPLICIT > 已有 INFERRED > 新 GENERATED

4. 冲突升级规则:
   - 新事实与 USER_CONFIRMED 冲突 → CRITICAL → 必须用户确认
   - 新事实与 EXPLICIT 冲突 → MAJOR → 建议用户确认
   - 新事实与 INFERRED 冲突 → MINOR → 自动标记
```

---

## 14. User Confirmation

### 14.1 确认流程

```
AI Suggestion 产生
  ↓
Pending Confirmation Queue
  [Item 1] "林默与李教授可能是父子关系" (confidence: 0.6)
  [Item 2] "青云山存在隐藏的传送阵" (confidence: 0.4)
  ...
  ↓
用户看到待确认列表
  ├── Accept → FactLevel → USER_CONFIRMED, confidence → 1.0, 移除队列
  ├── Reject → Status → REJECTED, 移除队列, 可选记录原因
  ├── Edit   → 用户修改内容, FactLevel → USER_CREATED,
  │           原版本 Status → SUPERSEDED, 移除队列
  └── Defer  → 保留队列, 可选设置提醒
```

### 14.2 确认条目模型

```kotlin
data class PendingConfirmation(
    val id: ConfirmationId,
    val novelId: NovelId,
    val knowledgeId: KnowledgeId,
    val type: ConfirmationType,    // KNOWLEDGE_CONFIRM / CONFLICT_RESOLVE /
                                   // FACT_UPGRADE / STATE_UPDATE
    val suggestion: String,
    val aiRationale: String?,      // AI 的推理过程
    val confidence: Float,
    val evidence: List<Evidence>,
    val conflicts: List<KnowledgeConflict>,
    val status: ConfirmationStatus, // PENDING / ACCEPTED / REJECTED / EDITED / DEFERRED / EXPIRED
    val createdAt: Instant,
    val expiresAt: Instant?,       // 可选过期时间
    val resolvedAt: Instant?
)
```

---

# Part C 引擎层：确定性 Core Engines（V3 基础，不直接调 LLM）

> 引擎层承担所有**确定性**业务能力：数据操作、验证、导入、检索、任务生命周期。引擎不调用 LLM，Agent 通过 Tool 封装引擎能力。此层在 V3 中已冻结，V4.1 不改变引擎职责，只由 Agent 层按 Writing Intelligence 需要调用。

## 15. Novel Engine

### 15.1 职责

Novel Engine 是创意项目的核心操作门面，负责**创意项目（Creative Project）**的创建、读取、更新与派生。它封装了底层 Knowledge / Story / Event / Character / Timeline 等模型，对上层（Tool Layer / Agent）提供统一、确定性的业务接口。

```
Novel Engine 职责:
- 创建/打开/关闭 Creative Project
- 管理 Story Hierarchy 实例（Story Arc / Act / Chapter Plan / Scene Plan / Beat）
- 管理 Character / Character Arc / Character State / Character Arc Progress
- 管理 Event / Timeline / Foreshadowing / Payoff / StoryConflict / Stakes
- 管理 Information State（Knowledge 挂接的可见状态）
- 管理 Chapter 与 Draft（正文读写）
- 提供查询：状态快照查询、弧光进度查询、伏笔/冲突开列查询
```

### 15.2 设计原则

1. **不污染原文**：Original Novel 只读；Creative Project 派生独立，修改不反向污染原文。
2. **AI 永不直接写数据库**：所有写入必须经 Output Validator（第 12 章）与 Knowledge Lifecycle（第 3 章）。
3. **确定性操作**：引擎不调用 LLM；推理/决策在 Agent 层完成，引擎只执行。
4. **可追溯**：所有写操作记录来源（SourceType / SourceReference）。

### 15.3 与 Data Layer 的关系

Novel Engine 不重新定义数据结构，它**编排**第 2~10 章定义的数据模型。例如：

| 操作 | 使用模型 |
|------|---------|
| 规划新章节 | StoryArc / Act / ChapterPlan（第 9 章） |
| 记录场景 | ScenePlan / Beat / SceneState（第 9 章） |
| 推进人物 | CharacterState / CharacterArcProgress（第 6 章） |
| 维护冲突 | StoryConflict / Stakes（第 10.1 / 10.2 节） |
| 维护伏笔 | Foreshadowing / Payoff（第 10.3 / 10.4 节） |
| 信息控制 | InformationState + knownFacts/unknownFacts（第 10.5 节） |

---

## 16. TXT Engine

### 16.1 定位

TXT Engine 是**纯本地、零 AI 依赖**的导入与解析引擎，用于把用户提供的 TXT 长篇小说转换为可分析的章节结构。

### 16.2 能力

```
TXT Engine 能力:
1. 文件读取: 本地文件 / 用户粘贴文本
2. 章节切分: 依据章节标题规则（"第X章" / "Chapter X" / 自定义正则）切分章节
3. 段落归一: 去空行、统一缩进、识别对话段落
4. 生成 Chunk: 章节 → 可检索的 TextChunk（用于 Evidence 引用与 FTS 检索）
5. 输出: Chapter + TextChunk 写入 Original Memory（只读）
```

### 16.3 约束

- TXT Engine 不做任何语义理解，不调用 AI。
- 切分结果可被用户修正（合并/拆分章节），修正作为用户输入记录。
- 切分出的章节进入 Analysis Pipeline（第 17 章）做后续 AI 分析。

---

## 17. Analysis Pipeline

### 17.1 定位

Analysis Pipeline 用于把**导入的小说**（或用户粘贴的创作草稿）转化为结构化知识。由 AI 分析 + 确定性校验组成；AI 输出全部经过 Output Validator（第 12 章）与 Knowledge Lifecycle（第 3 章）。

### 17.2 九阶段流程

```
Stage 1  文本预处理     TXT Engine 输出 Chunk；清洗、分词、去噪
Stage 2  章节切分校验    校验切分结果；用户可修正
Stage 3  人物抽取       识别出现的人物、别名、指代
Stage 4  人物档案构建    背景、性格、能力、关系（INFERRED，待确认）
Stage 5  事件抽取       按六要素（what/when/where/who/cause/consequence）抽取事件
Stage 6  时间线构建      事件 → TimelineEntry；解决时间冲突
Stage 7  世界观抽取      世界规则、地点、势力、物品
Stage 8  知识整合       Knowledge Lifecycle：FactLevel 分级、关联 Evidence、冲突检测
Stage 9  一致性检查      全库一致性校验（时间线/人物状态/世界规则）
```

> **一致性检查**与第 13 章 Conflict Detection 共用一套规则：Stage 9 为全库批处理触发，第 13 章为实时单条触发。两者是同一冲突检测机制的两种触发方式，不重复实现。

### 17.3 分析范围控制

- 分析按章节粒度推进，可暂停/恢复（Task Checkpoint）。
- 每阶段产物均写入待确认队列（第 14 章），用户确认后才进入 Long-term Memory。
- 分析不改变原文；所有推断标记 INFERRED / UNCERTAIN。

---

## 18. Task Manager 与 Checkpoint

### 18.1 职责

Task Manager 负责任务的**生命周期、状态、进度、取消、恢复、重试**，是 Agent 工作流可被中断/恢复的保障（对应设计原则 8：防无限循环、可暂停恢复）。

### 18.2 模型

```kotlin
data class Task(
    val taskId: TaskId,
    val type: TaskType,            // ANALYSIS / WRITING / PLANNING / KNOWLEDGE_UPDATE / IMPORT
    val status: TaskStatus,        // PENDING / RUNNING / PAUSED / CANCELLED / COMPLETED / FAILED
    val progress: Float,           // 0.0-1.0
    val checkpoint: Checkpoint?,   // 最近检查点
    val revisionCount: Int,        // 修订次数（上限 3）
    val error: String?,
    val createdAt: Instant,
    val updatedAt: Instant
)

data class Checkpoint(
    val checkpointId: CheckpointId,
    val taskId: TaskId,
    val stage: String,             // 恢复点：阶段标识
    val snapshot: Map<String, Any> // 恢复所需的上下文快照
)
```

### 18.3 规则

- 每个 Task 持有一个 Checkpoint，恢复时从 Checkpoint 阶段继续。
- 写作任务的 Revision 限 3 次（见第 24.3 节），超限进入 HITL。
- 取消任务保留 Checkpoint 与已产生产物，不丢弃。

---

# Part D Agent 层：受控 Agentic Writing（V3.1 冻结）

> 本节为 V3.1 Agent Architecture，**视为冻结**：其决策不改动。V4.1 只在既有 Agent 的输入/输出与校验规则中接入 Writing Intelligence（第 9 / 10 章），**不新增 Agent、不改变角色分离**。

## 19. Agent 层总览

### 19.1 设计原则

```
1. Agent 负责: 理解、推理、规划、决策（调用 LLM + Tools）。
2. Engine 负责: 确定性业务能力与数据操作（不调 LLM）。
3. Tool 是 Agent 访问引擎能力的标准化接口。
4. Agent ≠ Engine / Database / AI Provider / Task Manager。
5. 第一版为受控 Workflow：执行顺序、状态转换、工具权限与失败处理由 Orchestrator 控制。
6. 所有 Agent 输出必须结构化（第 12 章 Validation）。
7. 写操作必须经过 Proposal → Validator → Engine（第 3 章 Lifecycle）。
```

### 19.2 六大 Agent 与分工

| Agent | 职责 | 关键输入（含 V4.1） | 主要 Tools |
|-------|------|---------------------|-----------|
| **IntentAgent** | 理解用户意图，转译为结构化写作请求 | 用户原始输入 | context:getStyle, knowledge:search |
| **ResearchAgent** | 决定"查什么"，返回检索到的既有知识 | 写作请求、Story Intelligence 上下文 | context:collect, knowledge:search |
| **StoryPlannerAgent** | 生成 Story Arc / Act / Chapter Plan / Scene Plan / Beat 规划 | Arc / Act / Conflict / Foreshadowing / Payoff / Pacing / EmotionalArc | knowledge:get, story:plan |
| **WritingAgent** | 按 Chapter Plan 与 Beat 顺序生成正文 Draft | ChapterPlan、ScenePlan、Beats、上下文 | context:build, writing:write |
| **CriticAgent** | 对 Draft 做一致性、结构、质量审查 | Draft、ChapterPlan、Knowledge | knowledge:verify, conflict:detect |
| **KnowledgeAgent** | 维护 Knowledge / Story Intelligence 数据的生命周期 | 新事实/新规划 | knowledge:propose, knowledge:confirm |

---

## 20. Agent 契约与状态

### 20.1 Agent Contract

```kotlin
data class AgentContract(
    val agentId: AgentId,
    val name: String,
    val capabilities: List<Capability>,     // 该 Agent 能做什么
    val allowedTools: List<ToolName>,       // 允许调用的工具
    val inputSchema: JsonSchema,            // 输入约束
    val outputSchema: JsonSchema,           // 输出约束（结构化）
    val maxRetries: Int = 3
)
```

### 20.2 Agent State

```kotlin
enum class AgentState {
    IDLE,             // 空闲
    RUNNING,          // 执行中
    WAITING_TOOL,     // 等待工具结果
    WAITING_HUMAN,    // 等待用户确认（HITL）
    RETRYING,         // 重试中（最多 3 次）
    FAILED,           // 失败
    COMPLETED         // 完成
}
```

- Agent 状态可持久化（写入 Task Checkpoint），支持暂停/恢复。
- Orchestrator 依据 AgentState 决定下一步动作（第 23 章）。

---

## 21. 六大 Agent 详解（含 V4.1 集成）

### 21.1 IntentAgent

职责：理解用户自然语言意图，转译为**结构化写作请求**（UserWritingRequest），并判断本次请求的 **Planning Scope**。

```kotlin
data class UserWritingRequest(
    val requestId: ID,
    val intentType: IntentType,      // CONTINUE / PLAN / REWRITE / EXPAND / ANALYZE / CUSTOM
    val target: TargetRef,           // 目标：章节/场景/弧/全文
    val planningScope: PlanningScope,// 🆕 V4.1：本次规划的跨度
    val constraints: List<String>,   // 用户约束（如"不要现在揭露父亲秘密"）
    val styleHints: List<String>,    // 风格提示
    val rawText: String              // 原始输入
)
```

**Planning Scope（V4.1 新增，融入 Intent 解析）**：

| 范围 | 说明 | 规划产出 |
|------|------|---------|
| SCENE | 只规划一个场景 | Scene Plan + Beats |
| CHAPTER | 规划一个章节 | Chapter Plan + Scene Plans |
| ARC | 规划一条弧 | Story Arc + Acts + 章节序列 |
| NOVEL | 整卷/整部规划 | 弧序列 + 主线 + 副线 |

> 决策不推翻：写作仍以"当前章节 → 续写"为主，但新增弧级/卷级规划能力，由用户选择范围。

### 21.2 ResearchAgent

职责：**决定查什么**，返回检索到的既有知识与上下文。实际检索由 Context Engine 的 Tool 完成（第 11 章），Research Agent 不做检索实现。

```
输入: UserWritingRequest + 已解析的 Story Intelligence 上下文（第 11 章按需选择）
输出: ResearchResult {
  requiredKnowledge: List<KnowledgeId>,   // 需要的既有信息
  currentArc: ArcId?,                     // 当前弧
  activeConflicts: List<ConflictId>,      // 开着的冲突
  openForeshadowing: List<ForeshadowingId>, // 待回收伏笔
  relevantState: List<CharacterStateId>,  // 相关人物状态
  tokenBudget: Int                        // 分配到的预算
}
```

### 21.3 StoryPlannerAgent

职责：生成**规划而非正文**。V4.1 中，Planner 依据第 9 / 10 章模型输出结构化规划。

```
输入: ResearchResult + Story Intelligence 上下文
输出: PlanningResult {
  chapterPlan: ChapterPlan,          // 9.6 节
  scenePlans: List<ScenePlan>,       // 9.7 节（含 Entry/Exit State）
  beats: List<Beat>,                 // 9.8 节
  arcUpdate: ArcUpdate?,             // 弧状态更新（若触及弧边界）
  characterProgress: List<CharacterArcProgress>, // 6.3 节
  foreshadowingOps: List<ForeshadowingOp>,       // 引入/推进/标记
  payoffOps: List<PayoffOp>,                       // 回收/禁止
  infoStateChanges: List<InfoStateChange>,        // 10.5 节
  pacing: PacingProfile,             // 10.6 节
  emotionalArc: EmotionalArc         // 10.7 节
}
```

Planner 义务（来自第 10 章）：
- 每次规划至少推进或触碰一个 Open Conflict；
- 不引入与已解决冲突矛盾的剧情；
- 显式声明 InformationState 变化；
- 尊重 payoffWindow（不在窗口前回收、不在窗口后长期悬置）。

### 21.4 WritingAgent

职责：**只按规划写作**，不自行重构剧情。

```
输入: ChapterPlan + ScenePlan + Beats + 上下文（Context Engine 构建）
输出: Draft {
  chapterId: ChapterId,
  content: String,                    // 按 Beat 顺序生成的正文
  beatAlignment: Map<BeatId, TextSpan>, // 各 Beat 对应的文本区间（供 Critic 检查）
  usedKnowledge: List<KnowledgeId>
}
```

约束：
- 不改变 ChapterPlan 的目标与事件；
- 遵循 PacingProfile（段落密度、对话占比）；
- 遵循 POV 信息约束（POV 角色不应知道其 unknownFacts）。

### 21.5 CriticAgent

职责：对 Draft 做**一致性 + 结构 + 质量**审查，输出结构化 CritiqueIssue 列表。

```kotlin
data class CritiqueIssue(
    val id: IssueId,
    val severity: IssueSeverity,       // ERROR / WARNING / INFO
    val category: IssueCategory,       // KNOWLEDGE_CONSISTENCY / PLOT_STRUCTURE /
                                       // CHARACTER_ARC / FORESHADOWING / PAYOFF /
                                       // INFORMATION / PACING / EMOTIONAL / QUALITY
    val location: TextSpan?,           // 问题位置
    val message: String,
    val suggestion: String?,
    val blocking: Boolean              // 是否阻止进入下一阶段
)
```

Critic 检查项（含 V4.1）：
- **一致性**：Draft 与既有 Knowledge 矛盾（第 13.3 节流程）；
- **结构**：场景 Entry/Exit State 是否变化、Beat 是否完成功能（9.7 / 9.8）；
- **弧光**：人物变化是否符合 CharacterArcProgress（6.3）；
- **伏笔/回收**：揭露时机是否符合约束与 payoffWindow（10.3 / 10.4）；
- **信息**：POV 角色是否知道其"未知"信息（10.5）；
- **节奏/情绪**：实际文本是否偏离 PacingProfile 与 EmotionalArc（10.6 / 10.7）。

### 21.6 KnowledgeAgent

职责：维护 Knowledge 与 Story Intelligence 数据的生命周期（第 3 章）。

```
输入: 新事实 Proposal / 新规划产物（来自 Planner / Writer 的产出）
输出: KnowledgeUpdateResult {
  accepted: List<KnowledgeId>,      // 进入 Lifecycle 的条目
  pendingConfirmation: List<ConfirmationId>, // 进入待确认队列
  conflicts: List<KnowledgeConflict>,        // 检测到的冲突（第 13 章）
  updatedStates: List<CharacterStateId>
}
```

- 新事实必须经过 Proposal → Validator → Engine 才写入。
- 冲突处理遵循第 13.3 节升级规则（CRITICAL → HITL）。

---

## 22. Tool System 与权限矩阵

### 22.1 模型

```kotlin
data class ToolPermission(
    val toolName: ToolName,
    val agentId: AgentId,
    val allowed: Boolean,             // 是否允许
    val writeAccess: Boolean,         // 是否允许写
    val requireConfirmation: Boolean  // 写操作是否需用户确认
)

data class ToolCall(
    val toolName: ToolName,
    val args: Json,
    val caller: AgentId,
    val result: ToolResult,
    val startedAt: Instant,
    val finishedAt: Instant
)
```

### 22.2 工具分组

| 组 | 工具 | 封装引擎 |
|----|------|---------|
| **ContextTools** | context:collect / context:build / context:search | Context Engine |
| **KnowledgeTools** | knowledge:get / knowledge:search / knowledge:propose / knowledge:confirm / knowledge:detectConflict | Novel Engine + Knowledge Lifecycle |
| **StoryTools** | story:plan / story:getArc / story:getChapterPlan / story:getScenePlan | Novel Engine（V4.1 Story 模型） |
| **WritingTools** | writing:write / writing:rewrite / writing:draft | Novel Engine |
| **ValidationTools** | validation:check / conflict:detect | Validation Engine |

### 22.3 权限矩阵（示例，写操作均需确认）

| 工具 | Intent | Research | Planner | Writer | Critic | Knowledge |
|------|:---:|:---:|:---:|:---:|:---:|:---:|
| context:collect | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| knowledge:search | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| knowledge:get | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| knowledge:propose | ❌ | ❌ | ✅ | ✅ | ❌ | ✅ |
| knowledge:confirm | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ |
| story:plan | ❌ | ❌ | ✅ | ❌ | ❌ | ❌ |
| writing:write | ❌ | ❌ | ❌ | ✅ | ❌ | ❌ |
| writing:rewrite | ❌ | ❌ | ❌ | ✅ | ❌ | ❌ |
| validation:check | ❌ | ❌ | ❌ | ❌ | ✅ | ✅ |
| conflict:detect | ❌ | ❌ | ❌ | ❌ | ✅ | ✅ |

> 权限矩阵为最小集，具体扩展以 AgentContract.allowedTools 为准；所有写操作经 ToolPermission.requireConfirmation 控制。

---

## 23. Agent Orchestrator 与状态机

### 23.1 Orchestrator 职责

Agent Orchestrator 控制 Agent 的**执行顺序、状态转换、工具权限与失败处理**，并托管 Human-in-the-Loop。

```
核心循环:
UserWritingRequest
  → IntentAgent（解析意图 + Planning Scope）
  → ResearchAgent（决定查什么）
  → StoryPlannerAgent（产出规划）
  → [用户确认规划]（HITL，可选）
  → WritingAgent（产出 Draft）
  → CriticAgent（审查）
  → 通过 → 进入 KnowledgeAgent（更新知识 + 冲突处理）
  → 未通过 → Revision（限 3 次）→ 重写 → 再审查
  → 超限 → HITL 人工裁决
```

### 23.2 Workflow State Machine

> **[DECIDED]（V4.2）** `WorkflowState` 是**唯一**的工作流（Workflow）状态机。
> 系统中不得再存在第二套与之并列的 Workflow 状态枚举。任何"整个写作工作流现在运行到哪里"的表达，
> 只能使用本节枚举。历史产品流程清单与本节不一致处，一律以本节为准。

```kotlin
enum class WorkflowState {
    INTENT_PARSING,
    RESEARCH,
    PLANNING,
    PLAN_REVIEW,        // HITL：规划确认
    WRITING,
    CRITIQUE,
    REVISION,           // 计数 ≤ 3
    KNOWLEDGE_UPDATE,
    CONFLICT_HITL,      // HITL：冲突裁决
    COMPLETED,
    FAILED,
    CANCELLED,
    PAUSED
}
```

```
状态转换（示例）:
  WRITING → CRITIQUE
  CRITIQUE → (通过) → KNOWLEDGE_UPDATE
  CRITIQUE → (未通过 & revisionCount < 3) → REVISION → WRITING
  CRITIQUE → (未通过 & revisionCount ≥ 3) → CONFLICT_HITL
  PLANNING → (用户拒绝) → PAUSED / CANCELLED
```

- 任意状态可持久化到 Task Checkpoint，支持暂停/恢复。
- 失败处理：可重试（最多 3 次）→ 仍失败 → 标记 FAILED → 通知用户。

### 23.3 Workflow State Architecture（V4.2，状态族分离）

**[DECIDED]** 系统中不是只有 `WorkflowState` 一种状态。以下五个概念是**不同的状态族**，
职责不同、可同时存在、**互不替代、互不混用**：

```
Workflow
 ├── WorkflowState   整个 Workflow 运行到哪一阶段（§23.2，唯一来源）
 ├── TaskState       一个用户任务的启停生命周期（= §18.2 TaskStatus）
 ├── AgentState      单个 Agent 的执行状态（§20.2）
 ├── DraftState      一份 Draft 的审查/修订状态（[TBD]）
 └── HITLState       是否需要人工介入及等待用户做什么（[TBD]）
```

| 状态族 | 负责什么 | 定义来源 | 状态 |
|--------|---------|---------|:---:|
| **WorkflowState** | 整个创作 Workflow 运行到哪一阶段 | §23.2（唯一） | 正式定义 |
| **TaskState** | 一个用户任务的生命周期（等待/执行/暂停/取消/完成/失败） | = §18.2 `TaskStatus` | 正式定义 |
| **AgentState** | 单个 Agent 的执行状态 | §20.2 | 正式定义 |
| **DraftState** | 一份 Draft 的起草/待审/需修订/通过 | 未定义独立枚举 | **[TBD]** |
| **HITLState** | 是否需要人工介入、等待用户做什么 | 未定义独立枚举（由 §20.2 `WAITING_HUMAN` 与 §23.2 `PLAN_REVIEW`/`CONFLICT_HITL` 承载语义） | **[TBD]** |

**它们可以同时存在，且这不是冲突**（示例）：

```
WorkflowState = CRITIQUE        // 整个工作流处于审查阶段
TaskState     = RUNNING         // 该用户任务正在执行
AgentState    = RUNNING         // CriticAgent 正在执行
DraftState    = [TBD]           // 属 Draft 生命周期，本次不定
HITLState     = NOT_REQUIRED    // 当前无需人工介入
```

**命名与实现对应**：
- **`TaskState` 族对应架构的 `TaskStatus` 枚举（§18.2）**（`PENDING / RUNNING / PAUSED / CANCELLED / COMPLETED / FAILED`），文档以"TaskState 族"表达语义，实现层类型名用 `TaskStatus`。
- **`AgentState`（§20.2）** 已正式定义（`IDLE / RUNNING / WAITING_TOOL / WAITING_HUMAN / RETRYING / FAILED / COMPLETED`）。
- **`DraftState`、`HITLState`** 当前文档未给出正式独立枚举，标记 **[TBD]**，**本次修正不私自设计**，待后续需要时再正式定义。

**唯一性约束**：任何表示"整个写作工作流处于哪一阶段"的枚举值，只能来自 §23.2 `WorkflowState`；
`TaskState / AgentState / DraftState / HITLState` 均不得承担 Workflow 的阶段语义。

---

## 24. Human-in-the-Loop

### 24.1 触发时机

| 场景 | 触发 |
|------|------|
| 新事实与 USER_CONFIRMED 冲突 | CRITICAL → 必须用户确认 |
| 新事实与 EXPLICIT 冲突 | MAJOR → 建议用户确认 |
| 修订超限（3 次仍未通过 Critic） | 人工裁决 |
| 规划方案被用户要求调整 | 人工确认后重规划 |
| 用户主动发起的任何编辑 | 直接写（经 Lifecycle） |

### 24.2 HITL 交互方式

```
HITL 界面元素:
1. 冲突对比卡片: existingKnowledge + evidence ↔ newClaim + source（第 4.3 节）
2. 确认操作: 接受新事实 / 保留原事实 / 合并 / 延后（ResolutionDecision，第 13.1 节）
3. 修订裁决: 接受当前 Draft / 重写 / 调整规划
4. 待确认队列: 批量处理（第 14 章）
```

### 24.3 防无限循环

- Revision 限 3 次；超限进入 HITL。
- Workflow / Agent 状态可持久化、可暂停恢复。
- 所有 HITL 等待都有 AgentState = WAITING_HUMAN，不会无限重试。

---

## 25. Writing Workflow（受控 Agentic 写作）

### 25.1 端到端流程

```
1. 用户发起写作请求（UI → UserWritingRequest）
2. IntentAgent 解析意图 + Planning Scope
3. ResearchAgent 决定查什么 → Context Engine 收集上下文
4. StoryPlannerAgent 产出 ChapterPlan / ScenePlans / Beats
5. （可选）用户确认规划
6. WritingAgent 按 Beat 顺序生成 Draft
7. CriticAgent 审查（一致性/结构/伏笔/信息/节奏/情绪）
8. 通过 → KnowledgeAgent 更新知识 → 完成
9. 未通过 → Revision（≤3）→ 重写 → 再审查
10. 超限 / 冲突 → HITL
```

### 25.2 与 V4.1 的集成关系

- **规划范围**由 IntentAgent 在请求入口确定（第 21.1 节），贯穿整个工作流。
- **规划输入**来自 Story Intelligence 上下文（第 11 章按相关性选择，不全部塞入 Prompt）。
- **Writer 只按规划写**，**Critic 按规划检**（Beat 完成度、Entry/Exit 变化、伏笔时机、信息控制、节奏、情绪）。
- **KnowledgeAgent** 把规划与写作的产物经 Lifecycle 沉淀（弧状态、弧光进度、伏笔状态、Payoff、冲突状态），形成闭环。

---

# Part E 项目与持久化

## 26. Creative Project 与 Schema Version

### 26.1 Creative Project

```kotlin
data class CreativeProject(
    val projectId: ProjectId,
    val manifest: ProjectManifest,
    val schemaVersion: SchemaVersion,   // 数据模型版本
    val source: ProjectSource,          // ORIGINAL_NOVEL / FROM_IDEA / DERIVED
    val originalNovelId: NovelId?,      // 派生来源（只读）
    val createdAt: Instant,
    val updatedAt: Instant
)

data class ProjectManifest(
    val title: String,
    val genre: List<String>,
    val pov: String?,                   // 视角
    val synopsis: String,
    val status: ProjectStatus           // DRAFT / ACTIVE / COMPLETED
)
```

### 26.2 项目类型

| 类型 | 说明 | 原文 |
|------|------|------|
| Original Novel | 导入的原文，**只读**，不可被创作修改 | ✅ |
| Creative Project | 从原文派生的创作项目，独立演进 | 只读引用 |
| From Idea | 从想法直接开始创作 | 无原文 |

> **不污染原文**：Creative Project 的修改不反向写入 Original Novel；原创项目不依赖原文。

### 26.3 Schema Version

- 每个项目携带 schemaVersion；数据模型变更通过 Migration 升级（第 28 章）。
- 旧版本项目打开时自动迁移，迁移前自动备份。

---

## 27. Storage

### 27.1 存储选型

```
- 主存储: SQLite（本地优先，双端一致）
- 全文检索: SQLite FTS5（Chunk / Draft / Knowledge 检索）
- Repository 层: 统一数据访问接口，隔离存储实现
```

### 27.2 数据分区

```
- Original Memory（只读原文）: Original Chapters / TextChunks
- Knowledge Store: KnowledgeEntry / Evidence / Conflict / Confirmation
- Story Store（V4.1）: StoryArc / Act / ChapterPlan / ScenePlan / Beat /
                       CharacterArc / CharacterArcProgress / StoryConflict /
                       Foreshadowing / Payoff / InfoState
- State Store: CharacterState / TimelineEntry / Event
- Project Store: ProjectManifest / SchemaVersion
- Task Store: Task / Checkpoint
- Writing Store: Chapter / Draft / Revision
```

### 27.3 事务与一致性

- 一次写作工作流的产物（Draft + Knowledge 更新 + 弧状态更新）在单事务中提交。
- Knowledge 与 Story 数据的更新遵循第 3 章 Lifecycle 顺序（Validator → 冲突 → 写入）。

---

## 28. Backup 与 Migration

### 28.1 Backup

```kotlin
data class BackupPackage(
    val backupId: BackupId,
    val projectId: ProjectId,
    val schemaVersion: SchemaVersion,
    val storageVersion: String,
    val data: Json,                    // 全量数据快照
    val manifest: BackupManifest,
    val createdAt: Instant
)
```

- 备份在**迁移前**、**用户手动**、**每 N 次写提交后**自动触发（可配置）。
- 恢复：整包恢复（覆盖）或选择性恢复（单条 Knowledge / 单章）。
- 备份包含 schemaVersion，恢复时校验版本一致性。

### 28.2 Migration

```
MigrationEngine 流程:
1. 打开项目时读取 schemaVersion
2. 与当前版本比对 → 生成 MigrationPlan（按顺序的 MigrationStep 列表）
3. 迁移前自动 Backup
4. 逐 Step 执行迁移（SQL + 数据转换）
5. 校验迁移结果 → 更新 schemaVersion
```

- 迁移为确定性引擎操作，不调用 AI。
- 迁移失败自动回滚到备份点，项目保持可打开。

---

## 29. 平台运行时

| Runtime | 职责 | 共享 |
|---------|------|------|
| Android Runtime | 文件系统（导入 TXT）、网络（API 调用）、本地 SQLite | Core（模型/引擎/Agent/存储） |
| PC Runtime | 文件系统、网络、SQLite；支持大文件与批量操作 | 同上 |

- 平台差异收敛到 Runtime 抽象；Core 与平台无关。
- 双端数据可导出/导入（BackupPackage），便于迁移。

---

# Part F 范围与演进

## 30. MVP 范围

### 30.1 已确定（✅，纳入第一版）

```
✅ 核心能力:
  - TXT 导入 + 章节切分（TXT Engine）
  - 九阶段 Analysis Pipeline（人物/事件/时间线/世界观抽取）
  - Novel Knowledge + Evidence + FactLevel + 待确认队列
  - 四层 Memory（Current / Writing / Long-term / Original）
  - Character State + Character Arc + Character Arc Progress
  - Event / Timeline / Story Hierarchy（Arc / Act / ChapterPlan / ScenePlan / Beat）
  - Story Intelligence 支撑模型（Conflict / Stakes / Foreshadowing / Payoff /
    Information Control / Pacing / Emotional Arc）
  - Context Ranking + Token Budget（含 Story Intelligence 上下文）
  - Output Validation + Conflict Detection（CRITICAL/MAJOR/MINOR 升级 + HITL）
  - 受控 Agentic 写作（六大 Agent + Orchestrator + 状态机 + HITL）
  - Writing Workflow（规划 → 写作 → 审查 → 修订 ≤3 次 → 知识沉淀）
  - Creative Project / Schema Version / Backup / Migration / Storage
```

### 30.2 建议（💡）

```
💡 建议纳入:
  - 弧级/卷级整体规划（Planning Scope = ARC / NOVEL）优先出 MVP 中的"规划可视化"
  - 用户批量确认队列的筛选/分组
  - 双端数据同步（经 BackupPackage 导入导出，非实时同步）
```

### 30.3 第一版明确不做（⚠️ 防过度设计）

```
⚠️ 第一版不做:
  - 自动剧情重构
  - 多 Agent 自由协商 / 完全自主 Agent
  - 实时多人协作
  - 非本地云端数据存储
```

---

## 31. Future 演进

| 方向 | 说明 | 状态 |
|------|------|:---:|
| 更多 AI Provider | MiMo 之外的模型接入（经 LLM Gateway） | 🔮 |
| 自动剧情重构 | 基于 Arc/Character Arc 的结构化重构 | 🔮 |
| 写作风格学习 | 从用户修正中学习 Style Profile | 🔮 |
| 可视化大纲 | Story Arc / Act / Foreshadowing 时间轴可视化 | 🔮 |
| 跨项目复用 | 世界设定库在不同项目间复用 | 🔮 |
| 协同创作 | 多作者/审稿人协作 | 🔮 |

> Future 项均不推翻既有决策；在 MVP 稳定后按需启动。

---

# 附录

## A. 决策记录摘要（融合三版，不推翻任何既有决策）

| 决策 | 出处 | 状态 |
|------|------|:---:|
| AI 永不直接写数据库；输出必须经 Output Validator | V3 | ✅ 冻结 |
| Agent ≠ Engine / Database / Provider / Task Manager | V3.1 | ✅ 冻结 |
| 第一版为受控 Workflow，非自主多 Agent | V3.1 | ✅ 冻结 |
| 六大 Agent + Orchestrator + Tool System + HITL | V3.1 | ✅ 冻结 |
| Revision 限 3 次；可暂停/恢复 | V3.1 | ✅ 冻结 |
| Story Hierarchy：Arc ≠ Chapter、Scene ≠ Chapter | V4.1 | ✅ 新增 |
| Scene 必须有 Entry State → Desired Exit State | V4.1 | ✅ 新增 |
| Character State ≠ Character Arc | V4.1 | ✅ 新增 |
| 冲突 CRITICAL/MAJOR/MINOR + 升级 HITL | V3 / V3.1 | ✅ 合并去重 |
| Foreshadowing → Payoff 显式生命周期 | V4.1 | ✅ 新增 |
| Information Control（读者已知 ≠ 角色已知） | V4.1 | ✅ 新增 |
| 一致性检查 = 全库批处理 + 单条实时，共用一套规则 | V3 | ✅ 合并去重 |
| MVP / Future 范围 | V3 / V4.1 | ✅ 合并去重 |

## B. 术语表

| 术语 | 含义 |
|------|------|
| Agent | 推理/决策单元（调 LLM + Tools） |
| Engine | 确定性业务能力（不调 LLM） |
| Tool | Agent 访问引擎能力的接口 |
| Orchestrator | 控制 Agent 顺序/状态/权限/失败处理 |
| Task | 任务生命周期与 Checkpoint |
| Story Arc | 剧情发展单位（状态迁移） |
| Act | 弧内结构容器 |
| Chapter Plan | 章节规划（Writer 的输入，非正文） |
| Scene Plan | 场景单位（Entry/Exit State） |
| Beat | 场景内最小叙事推进单位 |
| Character Arc | 人物弧光轨迹 |
| Foreshadowing | 伏笔（生命周期管理） |
| Payoff | 伏笔回收 |
| Information State | 信息可见状态（读者/角色） |
| Pacing / Emotional Arc | 节奏与情绪控制 |

---

*本文档由 V3 基础架构、V3.1 Agent Architecture、V4.1 Writing Intelligence 融合而成；V3.1 Agent Architecture 冻结，V4.1 仅新增写作智能层，未推翻任何既有决策。*
