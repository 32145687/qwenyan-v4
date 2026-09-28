# Qianyan Novel IDE — 总架构设计

> **状态**：`DESIGN COMPLETE / NEEDS REVIEW`（设计完成，待评审）
> **性质**：DESIGN ONLY —— 本文档**不含任何代码改动**；不修改 P19 / P20 / Storage / Provider / Workflow / Android / Desktop UI
> **审计基线**：`feature/p14-f` @ `dbc750f`（本轮的代码现状；下文所有"现状"结论均来自**实际读取代码**，不依据文档推断）
> **上游方向**：[qianyan-novel-ide-direction.md](qianyan-novel-ide-direction.md)（产品方向与 PC FIRST 战略）
> **仍然有效的冻结件**：[p20-architecture-freeze.md](p20-architecture-freeze.md)（FD-1…FD-10）、P19 Decision Model
>
> **状态图例（全文严格区分）**
> `IMPLEMENTED` 已在仓库实现并有测试 · `SEALED` 已封存且不得改动 · `PROTOTYPE` 可运行原型/迁移来源 · `DESIGNED` 本文档定稿、未实现 · `PLANNED` 已列入规划、未设计定稿 · `PAUSED` 暂停 · `SUPERSEDED` 已被取代 · `ABSENT` 仓库中不存在

***

## 0. 审计基线（真实代码清点）

```
feature/p14-f @ dbc750f · schema v17 · ./gradlew test 158 suites / 923 tests / 0 failures
```

| 模块                     | 文件    | 行数    | 实际内容（已读取）                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                               |
| ---------------------- | ----- | ----- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `:core:model`          | 47    | 4048  | 包：`core`(Novel/Variant) `story`(Chapter/StoryArc/Act/ChapterPlan/Scene/ScenePlan/Beat/Conflict/Stakes/Foreshadowing/Payoff/EmotionalArc/ContinuationReference/ForeshadowLifecycle/Reveal) `world` `timeline` `character` `knowledge`(Knowledge+KnowledgeUpdate) `memory` `vocabulary` `txt` `task` `workflow` `writing`(Draft/format/status) `reading` `lcl`(NarrativeState/ChapterContextPack/RollingHorizon) `decision`(DecisionPolicy/Type/Outcome) `foundation`(StoryIntent/StoryFoundation) `author`(×4) `genre` `context`(ContextModels/StoryWorldContext) `analysis` `agent`(AgentContract/AgentState) `tool`(ToolModels) `spec` `Ids` `Support` |
| `:core:engine`         | 15    | 1153  | TXT：`TxtPipeline`/`TxtImporter`/`ChapterDetector`/`ChapterRules`/`TextNormalizer`/`TxtSource`/`TxtException`；Markdown：`ControlledMarkdown`/`MarkdownModel`/`MarkdownRenderer`；`AnalysisInputBuilder`                                                                                                                                                                                                                                                                                                                                                                                                                                                    |
| `:agent:tool`          | 8     | 324   | `Tool`(definition/execute) `ToolContext` `ToolError` `ToolExecutor` `ToolRegistry` + 3 测试。**无任何产品级 Tool 实现（`ABSENT`）**                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                  |
| `:agent:runtime`       | 10    | 532   | `AgentRuntime`（同步 LLM↔Tool 循环 + `maxSteps`）、`AgentExecutionContext`、`AgentResult`、`AgentStep`、`AgentError`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                              |
| `:agent:agents`        | **1** | **9** | **仅** **`AgentsSmokeTest.kt`（空占位，无 Agent 实现）**                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                          |
| `:agent:orchestration` | **1** | **9** | **仅** **`OrchestrationSmokeTest.kt`（空占位）**                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                              |
| `:provider:api`        | 6     | 203   | `LLMGateway`/`ProviderRequest`/`ProviderResponse`/`ProviderConfiguration`/`ModelProfile`/`ProviderException`/`ProviderCredentialStore`/`ProviderAssembler`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                              |
| `:provider:impl`       | 12    | 874   | `DefaultProviderAssembler`、`DeepSeekLLMGateway`、`MiMoLLMGateway`、`MockLLMGateway`、HTTP transport、`InMemoryProviderCredentialStore`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                      |
| `:storage`             | 103   | 9788  | 19 个 `.sq`（`Schema` + Novel/NovelVariant/Chapter/Draft/EntityOverride/Memory/Txt/Vocabulary/Task/Workflow/StoryState/NarrativeState/StoryFoundation/ReadingProgress/Author×4）· 迁移 `1.sqm…16.sqm`（**v17**）· 20 个 Repository/Store 接口 + Sqlite 实现 · `DatabaseInitializer`（守卫触发器）                                                                                                                                                                                                                                                                                                                                                                          |
| `:application`         | 165   | 21820 | 17 个 usecase 子包 + `di/ApplicationContainer` + `error`。`writing` = 35 文件（5 Agent + Parser + Snapshot + Gate + Facade）；`workflow`(1029) `task`(352) `author`(1602) `foundation`(701) `lcl`(179) `story`(323) 等                                                                                                                                                                                                                                                                                                                                                                                                                                            |
| `:runtime`             | **1** | **9** | **仅** **`RuntimeSmokeTest.kt`（空占位）**                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                    |
| `:app:android`         | 42    | 4815  | Novel/Chapter/Writer/Reader/Provider Settings 等 Compose UI + ViewModel（**PAUSED**，保留）                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                   |
| `:app:desktop`         | 23    | 3198  | Compose Desktop：`DesktopGraph`、adapter（文件选择/离线网关/Provider 组装/文件凭证）、`ui/`（Home/Plan/Write/Provider Settings 真实 + 骨架页）                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                    |
| `:test:e2e`            | **1** | **9** | **仅 smoke（空占位）**                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                        |

### 0.1 关键审计发现（代码 ≠ 文档，记录不改）

| #  | 发现                                                                                                                                                                                                                                                                  | 影响                                                             |
| -- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------- |
| A1 | **"五个 Agent" 不在** **`:agent:agents`**，而在 `:application/usecase/writing/`（`PlannerAgent`/`WriterAgent`/`CritiqueAgent`/`RevisionAgent`/`KnowledgeUpdateAgent`）。`:agent:agents` 与 `:agent:orchestration` 只有 smoke 占位                                                  | 迁移矩阵必须按**真实位置**填写；`:agent:agents` 是空壳，不是"要重构的 Agent 模块"        |
| A2 | `:runtime`、`:test:e2e` 亦为空占位（仅 smoke）                                                                                                                                                                                                                               | 新架构可直接在其中落地新组件，无需"迁移旧代码"                                       |
| A3 | 产品级 Tool **一个都没有**（只有契约 + 测试 stub）                                                                                                                                                                                                                                  | "Tool Registry" 属 `DESIGNED`；§12 不得写成"已有 Tool"                 |
| A4 | `Project Index`、`Artifact`、`ActionPolicy`、`Undo/Revert`、`Agent Session` 在代码中 **`ABSENT`**（`Artifact`/`ActionPolicy`/`Undo`/`Revert` 命中 0；`Session` 仅 `ChapterWritingSession`）；"diff" 仅出现在 `StoryFoundationDecisionUseCases` 的 `changedFields` 字段级差异与测试名 "different" | 这些是**新设计**，不是现状                                                |
| A5 | `Index` 的 27 处命中几乎都是 **SQLite 索引**（迁移里的 scope 索引）                                                                                                                                                                                                                   | 与"Project Index"无关，勿混用                                         |
| A6 | Workflow 已含 `PAUSED/CANCELLED/FAILED/WAITING_HUMAN/COMPLETED`、`AttemptErrorCategory{RETRYABLE,NON_RETRYABLE,NEEDS_HUMAN,TERMINAL}`、`HumanDecision{PENDING,APPROVED,REJECTED,REQUEST_REVISION}`                                                                      | 生命周期/恢复/取消/人工门**已有真实承载**，新架构应复用而非重建                            |
| A7 | `TaskType` 只有 `ANALYSIS/WRITING/PLANNING/KNOWLEDGE_UPDATE/IMPORT`；`TaskStatus` 含 `PENDING/RUNNING/PAUSED/CANCELLED/COMPLETED/FAILED`                                                                                                                                | 后台任务/队列的**状态语义已有**，但"后台调度"无实现（`TaskRunner` 是同步受管执行）            |
| A8 | `EntityOverride`（INHERIT/OVERRIDE/REMOVE/ADD）+ `Draft.previousDraftId` + `StoryFoundation.revision`/`FoundationOverride` + `changedFields` 差异记录                                                                                                                     | **Change/版本语义已有真实原语**，是 Working Draft / Diff / Commit 设计的主要复用点 |
| A9 | `Recipe`/`Skill`/`Plugin`/`Marketplace` 概念：仓库中**不存在**                                                                                                                                                                                                               | Skill Registry 属 `DESIGNED`                                    |

***

## 1. 产品架构总览

Qianyan = 面向长篇小说的 **AI IDE**：让 AI 在一个**长期存在的 Project** 中持续理解、规划、创作、检查、修改和维护整部长篇。

```text
                              User
                                │
                                ▼
                       ┌──────────────────┐
                       │   Novel Agent    │  ← 理解任务、决定路径（不越权）
                       └────────┬─────────┘
                                │  Agent Plan / Session
                ┌───────────────┴────────────────┐
                ▼                                ▼
        ┌───────────────┐                ┌───────────────┐
        │ Skill Registry│                │ Tool Registry │
        │ （专业方法）  │                │ （实际操作）  │
        └───────┬───────┘                └───────┬───────┘
                └───────────────┬────────────────┘
                                ▼
                       ┌──────────────────┐
                       │  Project Index   │  ← 快速定位（派生数据，可重建）
                       └────────┬─────────┘
                                ▼
                       ┌──────────────────┐
                       │    Workspace     │  ← 长期工作空间（≠ Context Window）
                       └────────┬─────────┘
                ┌───────────────┴────────────────┐
                ▼                                ▼
       ┌──────────────────┐            ┌──────────────────┐
       │ Novel World Model│            │  Project State   │
       │ （小说世界事实） │            │ （IDE/Agent 运行）│
       └────────┬─────────┘            └────────┬─────────┘
                └───────────────┬────────────────┘
                                ▼
                       ┌──────────────────┐
                       │  Context Engine  │  ← 决定"给模型看什么"
                       └────────┬─────────┘
                                ▼
                    ┌───────────────────────┐
                    │  LLM（Provider 契约） │
                    └───────────┬───────────┘
                                ▼
                       ┌──────────────────┐
                       │  Working Draft   │  ← 临时成果（不污染正式状态）
                       └────────┬─────────┘
                                ▼
                       ┌──────────────────┐
                       │    Validation    │  ← 确定性优先 + 语义补充
                       └────────┬─────────┘
                                ▼
                    Diff / Proposal / Finding
                                ▼
                    ┌───────────────────────┐
                    │ Human Gate / Policy   │  ← HITL 与权限边界
                    └───────────┬───────────┘
                                ▼
                       ┌──────────────────┐
                       │      Commit      │
                       └────────┬─────────┘
                ┌───────────────┴────────────────┐
                ▼                                ▼
       ┌──────────────────┐            ┌──────────────────┐
       │  Canonical Story │            │ World Model 更新 │
       └──────────────────┘            └──────────────────┘
                                │
                                ▼
                    ┌───────────────────────────┐
                    │ Workflow（生命周期/安全） │  ← 不在主链路上，但包住整条链路
                    └───────────────────────────┘
```

**相对上游方向图的三处修正（依据真实代码）**：

1. **补上 Workflow**：真实系统已有 Durable Workflow（Step/Attempt/HumanGate/Continuation + PAUSED/CANCELLED）。它不是"被 Agent 取代"，而是**包住**整条链路提供生命周期、Checkpoint、恢复、权限与提交边界（§27）。
2. **明确 Project Index 是派生层**：索引不是事实来源，位于 Workspace 之上、可重建（§6）。
3. **Canonical Story 与 World Model 更新是同一次 Commit 的两个结果面**（正文 + 世界事实），不是两条独立链路（§20/§21）。

***

## 2. Codex 类 Agent IDE 能力模型（现状对照）

| #  | 能力                         | 现状                                  | 说明                                                                      |
| -- | -------------------------- | ----------------------------------- | ----------------------------------------------------------------------- |
| 1  | Agent Session              | `ABSENT`                            | 仅有 `ChapterWritingSession`（章节写作专用，非通用会话）→ `DESIGNED` §13                |
| 2  | Agent Activity / Tool Log  | `ABSENT`（`AgentStep` 是内存态循环步骤）      | `DESIGNED` §15；需持久化以支撑 Debug/Audit/Resume/Replay                        |
| 3  | Agent Plan                 | `PLANNED`                           | 现有 `Planning*` 是**章节规划**（ChapterPlan），不是 Agent 的任务计划 → 需区分命名（§14）       |
| 4  | Diff / Change Review       | `ABSENT`（有 `changedFields` 字段级差异原语） | `DESIGNED` §19                                                          |
| 5  | Undo / Revert              | `ABSENT`                            | `DESIGNED` §22；复用 lineage/override 原语，不引入 Git 作为数据层                     |
| 6  | Permission / Action Policy | `ABSENT`                            | `DESIGNED` §16；但 `RevisionGate`/`HumanGate` 提供同类"确定性闸门"范式可复用            |
| 7  | Background Task            | `DESIGNED`                          | 生死线已有：`TaskStatus.PAUSED/CANCELLED` + `TaskRunner`（同步受管执行）→ §23         |
| 8  | Task Queue                 | `DESIGNED`                          | 无队列实现；`TaskRepository` 可承载排队语义 → §24                                    |
| 9  | Context Inspector          | `ABSENT`                            | 但 `ChapterContextPack`（含 token budget + packVersion）是可复用的**数据基础** → §25 |
| 10 | Artifact System            | `ABSENT`                            | 生成物目前散落为 Draft/ChapterPlan/Checkpoint/Knowledge → §26                   |

**结论**：10 项中 0 项可直接使用，2 项（Agent Session / Diff）有部分原语，5 项有可复用生死线。这是本轮"真正需要新建设计"的主体。

***

## 3. Qianyan 小说能力模型

```text
小说能力（Novel Capabilities）
├── 理解类：故事意图理解 / 题材判定 / 人物与关系抽取 / 事件抽取 / 时间线归位
├── 规划类：卷-大纲-剧情-章节规划 / 场景-节拍规划 / 伏笔布局
├── 创作类：续写 / 改写 / 风格统一 / 对话 / 场景扩写
├── 评审类：逻辑评审 / 人物评审 / 世界规则评审 / 节奏评审 / 文风评审
├── 修订类：按评审修订 / 定向重写 / 局部润色
├── 维护类：知识更新 / 世界状态更新 / 伏笔状态推进 / 揭示记录
└── 检查类：一致性 / 时间线 / 人物状态 / 章节引用 / 世界规则（确定性优先）
```

| 层   | 现状                                                                                                                         |
| --- | -------------------------------------------------------------------------------------------------------------------------- |
| 理解类 | 部分 `IMPLEMENTED`（`IdeaUnderstandingAgent`、`StoryIntentUseCases`、`GenreTaxonomy`、`AnalysisUseCases` 词汇分析、Author DNA TXT 分析） |
| 规划类 | `IMPLEMENTED`（Planning 链 + `ChapterPlan` 领域模型；卷/大纲产品层 `DESIGNED`）                                                          |
| 创作类 | `IMPLEMENTED`（Writer + Revision；AI 内容取决于 Provider）                                                                         |
| 评审类 | `IMPLEMENTED`（Critique；`CritiqueGate` 语义在 `RevisionGate`/`HumanGate`）                                                      |
| 修订类 | `IMPLEMENTED`（Revision + `RevisionGate` ≤3 + lineage）                                                                      |
| 维护类 | `IMPLEMENTED`（Knowledge Update + Validator/Applicator + Confirmation Gate；Foreshadow Lifecycle；Reveal）                     |
| 检查类 | 部分 `IMPLEMENTED`（TXT 确定性、受控 Markdown 降级、伏笔状态机、修订门控、Knowledge 校验）；**跨要素一致性检查** **`DESIGNED`**                               |

***

## 4. Project

**定义**：一部小说的完整项目 = 长期事实的集合（不是 Novel 表的同义词）。

```text
Project
├── Identity / Meta            // 作品身份、scope（ORIGINAL/VARIANT）、版本
├── Canonical Story            // 已确认的正文与事实（§20）
├── Novel World Model          // 世界事实（§7）
├── Planning                   // 卷-大纲-剧情-章节 + 内部 Arc/Act/Scene/Beat（§21 of direction）
├── Knowledge / Memory         // 沉淀的事实与写作记忆
├── Author / Writing Profile   // 作者偏好与决策模型
├── Rules                      // 创作规则 / Writing Policy
├── Index                      // 派生索引（§6）
└── History                    // 变更与版本记录（§22）
```

| 项                                                     | 现状                                                               |
| ----------------------------------------------------- | ---------------------------------------------------------------- |
| 项目身份 / scope 隔离（ORIGINAL 只读 + 写保护触发器）                 | `IMPLEMENTED`（`Novel`/`NovelVariant` + 3 触发器 + `EntityOverride`） |
| Canonical 正文                                          | `IMPLEMENTED`（`ChapterDraft` + `DraftStatus`，`CONFIRMED` 为确认后状态） |
| Novel World Model                                     | `IMPLEMENTED`（六类 Story State + NarrativeState）                   |
| Planning                                              | `IMPLEMENTED`（domain + Workflow）                                 |
| Knowledge / Memory                                    | `IMPLEMENTED`（`MemoryEntry` + `KnowledgeModels`）                 |
| Project 作为**统一聚合根**（ProjectId / Project State / 生命周期） | `DESIGNED`（当前没有 Project 聚合，只有 Novel/Variant 与散落的仓储）              |

> **设计取舍**：`Project` 作为**产品层聚合**引入；**底层不新建第二套存储**，仍以既有 Novel/Variant 为身份锚，其余为投影（§31）。

***

## 5. Workspace

**定义**：长期工作空间（人机共同编辑一部小说的环境与运行态容器）。**不是数据真源**。

```text
Workspace
├── 打开的项目集合（当前 Project）
├── Working set（正在处理的分卷/章节/场景/人物）
├── Agent Sessions（进行中/历史）
├── Working Drafts（未提交成果）
├── Pending Proposals（待确认变更）
├── Validation Findings（检查结论）
├── Background Tasks（运行中/排队）
└── UI 视图状态（三栏：Project / Workspace / Agent）
```

不变式：

- Workspace **持有运行态，不持有事实**；事实在 Project / Canonical Story。

- 所有写回经既有 Application UseCase / Gateway（禁止 UI → Repository / SQLDelight）。

- Workspace 状态**可丢弃可重建**（重启后可由 Project 重建视图）。

**现状**：`ABSENT`（桌面端当前只有 `DesktopAppState` + 各页面的 `remember` 局部状态，属 UI 层）→ `DESIGNED`。

***

## 6. Project Index

**定位**：**派生数据层**，用于"按需定位"，使 Agent 不必把整本书塞进上下文。可随时从 Project 重建，**永不是事实来源**。

```text
Project Index
├── 文件/结构索引    章节、分卷、场景、节拍的层级与顺序
├── 实体索引         人物 / 地点 / 物品 / 组织（identity 归并）
├── 人物索引         出场、状态变化、关系
├── 章节索引         摘要、关键要素、引用关系、前驱后继
├── 事件索引         事件 → 章节 / 参与人物 / 时间点
├── 时间线索引       全局时序与相对顺序
├── 伏笔索引         埋设 / 推进 / 回收 / 废弃 + 揭示记录
├── 关系索引         人物关系与关系变化
├── 全文搜索         （预留）
└── 语义检索         （预留，DESIGNED only）
```

| 项                               | 现状                                                                                              |
| ------------------------------- | ----------------------------------------------------------------------------------------------- |
| 章节顺序 / scope 索引                 | `IMPLEMENTED`（SQLite 索引，非 Project Index）                                                        |
| 结构/实体/事件/伏笔的可检索事实               | `IMPLEMENTED` 为**数据**（Story State / NarrativeState / Foreshadow / Reveal），**索引服务** **`ABSENT`** |
| Index Builder / Query API（扩展接口） | `DESIGNED`                                                                                      |
| 全文搜索                            | `PLANNED`（FD-10 明确排除，不在本轮/近期实现）                                                                 |
| 语义检索 / Vector / Embedding       | `PLANNED` / **本轮明确不实现**                                                                         |

**扩展接口预留（DESIGNED，不实现）**：

```text
IndexProvider（spi）
├── rebuild(projectId)              // 从 Project 重建索引
├── query(IndexQuery): IndexResult  // 结构/实体/事件/时间线/伏笔/关系
├── invalidate(scope)               // 提交后失效策略
└── capabilities(): Set<IndexKind>  // 声明支持的索引种类（为未来语义检索留位）
```

> 约束继承：**不引入 RAG / Vector DB / Embedding / MCP / Cloud**（FD-10）。语义检索仅保留接口位。

***

## 7. Novel World Model

**定义**：小说世界**当前状态的事实**（"世界现在是什么样"）。

```text
Novel World Model
├── 人物          Character
├── 人物状态      CharacterState（位置 / 境界 / 状态 / 知识）
├── 人物知识      谁知道什么（InformationState 语义）
├── 人物关系      关系与关系变化
├── 事件          Event
├── 地点          地点与位置（部分在 WorldRule/Event 内表达）
├── 世界规则      WorldRule
├── 时间线        TimelineEntry
├── 剧情          章节与剧情事实（Chapter / ChapterPlan 已确认部分）
├── 因果          事件前因后果（部分 `DESIGNED`）
├── 伏笔          Foreshadow（生命周期）+ Reveal（揭示记录）
└── 叙事账本      NarrativeState（长期压缩状态）
```

| 项                          | 现状                                                                                |
| -------------------------- | --------------------------------------------------------------------------------- |
| 六类结构化状态                    | `IMPLEMENTED`（schema v5 起；scope isolation；canon-first）                            |
| 伏笔生命周期 + 揭示                | `IMPLEMENTED`（`ForeshadowLifecycleRules` + `Reveal`，Variant-only 写 / Original 只读） |
| 叙事账本                       | `IMPLEMENTED`（`NarrativeState`/`NarrativeDelta`，v7）                               |
| 人物关系图（显式关系模型）              | `DESIGNED`（当前关系未作为一等实体持久化）                                                        |
| 因果链                        | `DESIGNED`                                                                        |
| 世界状态**快照**（world revision） | `DESIGNED`（现在可从事件/时间线重建，但无显式快照）                                                   |

***

## 8. Project State

**定义**：Qianyan IDE / Agent 的**运行状态**（"系统现在在做什么"）。与 §7 严格分离。

```text
Project State
├── 当前 Project / 当前打开对象
├── 当前 Agent Session / 当前 Task
├── 当前 Working Draft / Pending Proposal
├── Task Queue（运行中 / 排队 / 暂停）
├── 当前任务进度 / Checkpoint 引用
├── 用户确认状态（Human Gate 待决项）
├── 最近活动（Activity 摘要）
└── UI 视图状态（Workspace 三栏）
```

### 8.1 边界（必须严格区分）

| 维度            | Novel World Model                                      | Project State                                  |
| ------------- | ------------------------------------------------------ | ---------------------------------------------- |
| 回答的问题         | 小说**现在是什么样**                                           | 系统**现在在做什么**                                   |
| 生命周期          | 长期（跨会话、跨设备）                                            | 短期（随会话/任务变化）                                   |
| 是否进 Canonical | 是（Commit 后成为事实）                                        | 否（永不进 Canonical）                               |
| 可重建性          | 不可随意重建（是事实）                                            | 可重建/可丢弃                                        |
| 典型载体          | Story State / Foreshadow / NarrativeState / Draft(已确认) | Task / Workflow / Checkpoint / Session / Queue |
| 示例            | 苏清 怀疑程度 45→55                                          | "第 89 章 Writing 步骤正在 RUNNING，第 3 次尝试"          |

| 项                                    | 现状                                                                      |
| ------------------------------------ | ----------------------------------------------------------------------- |
| Task / Workflow / Checkpoint（运行态持久化） | `IMPLEMENTED`（v2 / v6；`TaskStatus`、`WorkflowStatus` 含 PAUSED/CANCELLED） |
| 人工确认待决状态                             | `IMPLEMENTED`（`WorkflowHumanGate.PENDING` + `HumanDecision`）            |
| Agent Session / Queue / 活动摘要         | `ABSENT` → `DESIGNED`                                                   |

***

## 9. Context Engine

**定义**：根据当前任务**动态决定给模型提供什么**（选择 / 排序 / 压缩 / 预算 / 快照）。

```text
User Task
   ↓
Agent（判定任务类型与所需信息）
   ↓
Project Index（定位候选）
   ↓
相关资料（章节 / 人物 / 事件 / 伏笔 / 时间线 / 规则 …）
   ↓
Novel World Model + Project State（事实与运行态注入）
   ↓
Context Engine
   ├── Context Selection   选什么
   ├── Context Priority    谁优先
   ├── Context Budget      给多少 token
   ├── Context Compression 怎么压缩（摘要/投影）
   ├── Context Cache       可复用片段
   └── Context Snapshot    本次任务的 Context 冻结（可审计、可复现）
   ↓
Dynamic Context → LLM
```

### 9.1 关键区分

> **Context Window = 当前工作记忆（短、易失、按任务生成）**
> **Workspace = 长期项目空间（长、持久、承载状态）**

不得混用：Workspace 不是"更大的 Context"，Context 也不是"Workspace 的子集"。

| 环节                              | 现状                                                                       |
| ------------------------------- | ------------------------------------------------------------------------ |
| 章节级上下文组装（canon-first 分层）        | `IMPLEMENTED`（`StoryWorldContextResolver`）                               |
| 有界上下文包 + token 预算 + 确定性版本       | `IMPLEMENTED`（`ChapterContextPack` / `TokenBudgetGuard` / `packVersion`） |
| 任务级 Context Projection（按任务动态取舍） | `DESIGNED`（本方向核心新增）                                                      |
| Context Snapshot（任务级冻结与审计）      | `DESIGNED`                                                               |
| Context Cache                   | `PLANNED`                                                                |
| Context Inspector（可视化）          | `ABSENT` → `DESIGNED` §25                                                |

***

## 10. Novel Agent

**定义**：理解用户任务、制定计划、选择 Skill/Tool、驱动执行、触发校验、产出 Proposal 的**单一入口 Agent**。

职责边界：

```text
Novel Agent 可以做：  理解任务 / 制定计划 / 选择能力 / 读取 Project / 执行 / 自检 / 产出 Proposal
Novel Agent 不可以：  越权写 Canonical / 绕过 Human Gate / 无视 Action Policy / 静默改 Original
```

**现状**：

- 通用 Agent 执行内核（LLM↔Tool 循环）`IMPLEMENTED`（`:agent:runtime`，同步、`maxSteps`）。

- 现有五个 Agent 是**固定角色 Agent**（`IMPLEMENTED`，位于 `:application`）。

- "Novel Agent + 能力选择"的编排层 `DESIGNED`。

- Agent 契约 `IMPLEMENTED`（`core:model` `AgentContract`/`AgentState`）。

***

## 11. Skill Registry

**定义**：**专业创作方法**的注册与调用（"怎么做得专业"）。Skill ≠ Tool：Skill 是方法论/流程编排，Tool 是原子操作。

```text
Novel Agent
├── Planning Skill
├── Writing Skill
├── Critique Skill
├── Revision Skill
├── Knowledge Update Skill
├── Consistency Skill
└── Prose Quality Skill
```

| 项                            | 现状                                                                                                                      |
| ---------------------------- | ----------------------------------------------------------------------------------------------------------------------- |
| 上述七项能力的**算法/Agent 实现**       | `IMPLEMENTED`（前五项在 `:application/usecase/writing`；Consistency/Prose Quality 部分 `IMPLEMENTED`（伏笔状态机/修订门控），整体 `DESIGNED`） |
| Skill **注册表 / 契约 / 发现机制**    | `ABSENT` → `DESIGNED`                                                                                                   |
| Skill 与 Prompt/模型/Parser 的绑定 | `DESIGNED`（现有绑定方式是 Agent 内硬编码 prompt + 严格 Parser）                                                                       |

***

## 12. Tool Registry

**定义**：Agent **实际操作能力**的注册与执行（"能对 Project 做什么"）。

```text
契约层（已有）    Tool / ToolDefinition / ToolRequest / ToolResult / ToolContext / ToolExecutor / ToolRegistry
产品层（待建）    read_file · search_file · edit_file · create_file
                  search_character · search_chapter · search_timeline · search_foreshadowing
                  check_consistency · check_character · check_world_rule · check_plot
                  analyze_style · extract_events · update_story_state
```

| 项                                         | 现状                                                             |
| ----------------------------------------- | -------------------------------------------------------------- |
| Tool 契约 / Registry / Executor / 类型化错误     | `IMPLEMENTED`（`:agent:tool` 324 行 + `core:model` `ToolModels`） |
| 产品级 Tool 实现                               | **`ABSENT`（0 个）** → `DESIGNED`                                 |
| Tool 权限/可见性（只读 vs 可写）                     | `DESIGNED`（与 §16 Action Policy 联动）                             |
| Tool 与 Human Gate 的交互（写类 Tool 需 Proposal） | `DESIGNED`                                                     |

***

## 13. Agent Session

**定义**：一次完整 Agent 工作过程（可暂停、可恢复、可审计）。

```text
Session
├── User Request
├── Agent Plan
├── Context（Snapshot 引用）
├── Tool Calls / Skill Calls
├── Generated Artifacts
├── Findings（校验/检查结论）
├── Proposed Changes
├── Validation
├── User Confirmation（Human Gate）
├── Commit
└── Final Result
```

研究项与现状：

| 子项               | 现状 / 迁移观                                                                                  |
| ---------------- | ----------------------------------------------------------------------------------------- |
| Session 状态机      | `DESIGNED`（可参考 `WorkflowStatus` + `TaskStatus` 的既有状态词汇）                                   |
| Session 持久化      | `DESIGNED`（可复用 `Task`/`Checkpoint` 的持久化范式与仓储风格）                                           |
| Checkpoint       | `IMPLEMENTED`（`TaskRepository.saveCheckpoint`/`findCheckpoints`；幂等恢复 FD-3）                |
| Pause / Resume   | 部分 `IMPLEMENTED`（Workflow `PAUSED` + resultReference-first Recovery）；Session 层 `DESIGNED` |
| Retry            | `IMPLEMENTED`（`WorkflowStepAttempt` + `AttemptErrorCategory`；Retry ≠ Revision）            |
| Cancel           | `IMPLEMENTED`（`WorkflowStatus.CANCELLED`、`TaskStatus.CANCELLED`）                          |
| Failure Recovery | 部分 `IMPLEMENTED`（Recovery 路径 + Failure 分类）；Session 级 `DESIGNED`                           |

***

## 14. Agent Plan

**定义**：Agent 对一次任务的**执行计划**（≠ 章节规划 ChapterPlan，命名需严格区分）。

```text
用户任务 → Agent Plan → （可选）用户预览 → 执行
```

风险分级（DESIGNED）：

| 风险  | 判定                                       | 处理                              |
| --- | ---------------------------------------- | ------------------------------- |
| 低风险 | 只读 / 检索 / 检查 / 生成草稿（不落 Canonical）        | 自动执行                            |
| 中风险 | 写入 Working Draft / 记录 / 更新 Project State | Agent 执行 + **记录 Activity**      |
| 高风险 | 修改 Canonical Story / 世界事实 / 规划结构 / 删除    | **Proposal + 用户确认**（Human Gate） |

| 项                             | 现状                                                 |
| ----------------------------- | -------------------------------------------------- |
| 章节级规划（ChapterPlan）            | `IMPLEMENTED`（含严格 Parser + 快照 + DecisionPolicy 复用） |
| Agent 任务计划（Plan 结构 + 预览 + 分级） | `DESIGNED`                                         |
| 不强制所有任务先确认                    | `DESIGNED`（原则：低风险自动、高风险必确认）                        |

> **命名纪律**：文档与代码中，"Plan" 若指 Agent 计划必须写作 `AgentPlan`，章节规划仍为 `ChapterPlan`，避免歧义。

***

## 15. Activity / Tool Log

**定义**：记录 Agent **读过什么 / 搜过什么 / 调过什么 / 生成过什么 / 改过什么 / 查出什么问题**。

目标用途：`Debug` · `Audit` · `Resume` · `Replay` · `Diff` · `History`。

```text
ActivityRecord
├── stepIndex / 时间 / 参与者（Agent | User | System）
├── 动作类型：READ | SEARCH | TOOL_CALL | SKILL_CALL | GENERATE | VALIDATE | PROPOSE | COMMIT
├── 目标：Project 实体引用（章 / 人物 / 伏笔 / 规则 / 时间线）
├── 结果摘要（成功/失败/类型化错误）
└── 关联：SessionId / TaskId / WorkflowStepId / ArtifactId / ChangeId
```

| 项                                     | 现状                                                                    |
| ------------------------------------- | --------------------------------------------------------------------- |
| Agent **内存**循环步骤                      | `IMPLEMENTED`（`AgentStep`）                                            |
| 已执行 Tool 的历史（跨进程可查）                   | `ABSENT` → `DESIGNED`                                                 |
| Workflow 步骤/尝试/结果引用（可作为 Activity 的骨架） | `IMPLEMENTED`（`WorkflowStep`/`WorkflowStepAttempt`/`resultReference`） |
| Task Checkpoint（可作 Activity 的持久化节流点）  | `IMPLEMENTED`                                                         |

***

## 16. Permission / Action Policy

**定义**：Agent 行动权限与边界（"能做多大范围的事"）。

```text
动作权限：READ · SEARCH · CREATE · WRITE_DRAFT · EDIT_DRAFT
          UPDATE_WORLD · MODIFY_PLAN · MODIFY_CANONICAL · DELETE · COMMIT

风险：低 / 中 / 高
处理：Agent 自动 / Agent + 日志 / Proposal / Human Confirmation
```

> 核心原则：**Agent 可以自主决定行动路径，但不能突破系统权限与任务边界。**

| 项                           | 现状                                                                                                                    |
| --------------------------- | --------------------------------------------------------------------------------------------------------------------- |
| 确定性闸门范式（可复用）                | `IMPLEMENTED`（`RevisionGate` ≤3；`WorkflowHumanGate` + `HumanDecision`；Knowledge 前置 Confirmation Gate；Original 写保护触发器） |
| Action Policy（动作级权限模型 + 分级） | `ABSENT` → `DESIGNED`                                                                                                 |
| 任务边界（本次任务允许的动作集合）           | `DESIGNED`（应作为 Session/AgentPlan 的输入约束）                                                                               |

***

## 17. Working Draft

**定义**：Agent 的**临时成果区**——所有未经确认的产出都在这里，**不污染 Canonical**。

```text
Working Draft（可多个、可并行、可丢弃）
├── 正文草稿（ChapterDraft 未确认态）
├── 规划草稿（ChapterPlan / 卷-大纲提案）
├── 世界事实提案（人物状态/事件/伏笔变更提案）
├── 分析/检查报告
└── 衍生 Artifact（§26）
```

| 项                                 | 现状                                                                                   |
| --------------------------------- | ------------------------------------------------------------------------------------ |
| 正文草稿（含 format / lineage / status） | `IMPLEMENTED`（`ChapterDraft` + `DraftStatus` + `previousDraftId`；`CONFIRMED` 区分确认前后） |
| 差异表达（不复制全文）                       | `IMPLEMENTED`（`EntityOverride` INHERIT/OVERRIDE/REMOVE/ADD；Original 只读）              |
| 统一的"提案/工作区"抽象（跨正文/规划/世界）          | `DESIGNED`（当前各域各自表达，缺统一 Working Draft 语义）                                            |
| 草稿清理/过期策略                         | `PLANNED`                                                                            |

***

## 18. Validation

**定义**：变更是否**合法 / 一致 / 符合约束**。

```text
Validation
├── 确定性（Deterministic Engine 优先）
│   ├── schema / 引用完整性 / scope 与 isolation
│   ├── 章节顺序、时间顺序、章节引用关系
│   ├── 人物是否已死亡、人物当前位置与状态
│   ├── 世界规则约束
│   ├── 伏笔状态机（PLANTED→ACTIVE→RESOLVED/ABANDONED）
│   └── 修订次数 / 受控 Markdown 结构
└── 语义（LLM 补充）
    ├── 逻辑评审（Critique）
    ├── 节奏/风格评审
    └── 复杂语义冲突判断
```

| 项                                | 现状                                                                                                                                                |
| -------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------- |
| 确定性校验                            | `IMPLEMENTED`（迁移+触发器、`ForeshadowLifecycleRules`、`RevisionGate`、`KnowledgeValidator`、`ControlledMarkdown` 降级、`ChapterPlanParser`/DraftParser 严格解析） |
| 跨要素一致性检查（人物/时间/位置/引用/世界规则）       | `DESIGNED`（新的确定性引擎能力，见 §12 `check_*`）                                                                                                             |
| 语义评审                             | `IMPLEMENTED`（Critique Agent）                                                                                                                     |
| 统一 Validation 结果模型（供 Diff/UI 共用） | 部分 `IMPLEMENTED`（Critique `ValidationResult`）；跨域统一 `DESIGNED`                                                                                     |

***

## 19. Diff / Change Review

**定义**：小说领域的变更审阅（不只文本 Diff）。

```text
Diff 维度：
├── 文本 Diff        章节正文（受控 Markdown v1 结构化差异）
├── 人物状态 Diff    苏清 怀疑程度 45 → 55
├── 剧情 Diff        剧情走向 / 章节结构变化
├── 时间线 Diff      事件顺序 / 插入 / 调整
├── 伏笔状态 Diff    F023 ACTIVE → BUILDING
├── 世界规则 Diff    新增 / 修改 / 废弃规则
├── 人物关系 Diff    关系新增 / 变化
└── 规划 Diff        ChapterPlan 89 修改
```

| 项                                   | 现状                                                                                                            |
| ----------------------------------- | ------------------------------------------------------------------------------------------------------------- |
| 字段级变更记录原语                           | `IMPLEMENTED`（`StoryFoundationDecisionUseCases`：`sourceRevision` + `changedFields` 差异 + `FoundationOverride`） |
| 实体级差异原语                             | `IMPLEMENTED`（`EntityOverride` INHERIT/OVERRIDE/REMOVE/ADD）                                                   |
| 版本链原语                               | `IMPLEMENTED`（`Draft.previousDraftId`；`StoryFoundation.revision`）                                             |
| **跨域统一 Diff 模型 + Change Review UI** | `ABSENT` → `DESIGNED`                                                                                         |
| 结构化正文差异（受控 Markdown 块级 diff）        | `DESIGNED`（`MarkdownModel` 提供块结构基础，可复用）                                                                       |

***

## 20. Canonical Story

**定义**：**经确认后的小说出事实**（正文 + 世界事实 + 规划结构 + 规则）。

```text
Canonical Story
├── 已确认正文（DraftStatus = CONFIRMED / FINAL）
├── 已确认世界事实（Story State 的 Original/Canon 层）
├── 已确认规划（ChapterPlan 已提交部分）
├── 已确认规则（Writing Policy / Story Foundation）
└── 已确认知识（MemoryEntry 沉淀）
```

| 项               | 现状                                                                                                                             |
| --------------- | ------------------------------------------------------------------------------------------------------------------------------ |
| 正文确认语义          | `IMPLEMENTED`（`DraftStatus.PENDING_CONFIRMATION/CONFIRMED`；`ConfirmationExecutionUseCase`；KU 仅对 CONFIRMED 执行；Revision 不自动继承确认） |
| 世界事实确认语义        | 部分 `IMPLEMENTED`（Story State 写入 + Variant-only/Original 只读约束；"事实确认"的统一闸门 `DESIGNED`）                                           |
| Canonical 版本/快照 | `DESIGNED`（有 lineage 与 revision，但无统一 canonical revision）                                                                       |
| Original 只读保障   | `IMPLEMENTED / SEALED`（3 个写保护触发器，不可绕过）                                                                                         |

***

## 21. Commit

**定义**：把**经确认**的变更正式写入 Canonical Story（= 正文 + 世界事实 + 知识的原子化落地 + 版本记录）。

```text
Proposal → Validation → User Confirmation / Policy → Commit
   ↓                                                   ↓
   └──────────── 失败可回退（不产生半提交）◀───────────┘
```

| 项                                                  | 现状                                                               |
| -------------------------------------------------- | ---------------------------------------------------------------- |
| 已确认落地（正文确认 → KU → Memory）                          | `IMPLEMENTED`（`confirmation` + `knowledgeUpdate` 链，HITL 前置）      |
| 事务/原子性                                             | 部分 `IMPLEMENTED`（单仓储事务；**跨仓储 Unit-of-Work 仍是 P12.0.1 遗留 TODO**）  |
| 统一 commit 语义（Change → Commit → Canonical revision） | `DESIGNED`                                                       |
| Commit 后的 Index/Context 失效                         | `DESIGNED`（`ChapterContextPack` 已有 `invalidate/recompile` 范式可复用） |

***

## 22. History / Revert

```text
History
├── Session History    本次/历史会话（§13）
├── Change History     变更与 Proposal 记录（§19）
├── Artifact History   生成物版本（§26）
└── Canonical History  正式状态演进
        ↓
Revert / Rollback（把某个 Change/Artifact 的影响回退）
```

| 项                                | 现状                                                    |
| -------------------------------- | ----------------------------------------------------- |
| 版本链（草稿级）                         | `IMPLEMENTED`（`previousDraftId`；修订不破坏原稿）              |
| 变更记录（字段级）                        | `IMPLEMENTED`（`changedFields` + `FoundationOverride`） |
| **统一 History + Revert/Rollback** | `ABSENT` → `DESIGNED`                                 |
| Git 作为小说数据层                      | **明确不引入**（见 §43）。未来若需 Project 外部版本控制，单独设计             |

***

## 23. Background Task

```text
状态：Running · Queued · Paused · Completed · Failed · Cancelled
典型后台任务：
  后台一致性检查 / 后台时间线检查 / 后台人物检查 / 后台伏笔检查 / 后台全书分析
```

| 项                     | 现状                                                                              |
| --------------------- | ------------------------------------------------------------------------------- |
| 任务状态词汇                | `IMPLEMENTED`（`TaskStatus` = PENDING/RUNNING/PAUSED/CANCELLED/COMPLETED/FAILED） |
| 受管执行（同步）              | `IMPLEMENTED`（`TaskRunner`：Task 生命周期 + 类型化拒绝）                                   |
| 后台**调度器**（何时唤醒、并发、限流） | `ABSENT` → `DESIGNED`（FD-2：平台调度器**只能是 Scheduler**，业务事实仍在 Task/Checkpoint）       |
| 后台检查类任务               | `ABSENT` → `DESIGNED`（依赖 §18 跨要素一致性引擎）                                          |

***

## 24. Task Queue

```text
Queue
├── 入队（任务类型 + 目标 + 优先级 + 依赖）
├── 调度（串行/并行、限流、取消传播）
├── 状态订阅（UI 进度）
└── 结果回收（Artifact / Proposal / Finding）
```

| 项                     | 现状                                                            |
| --------------------- | ------------------------------------------------------------- |
| 任务持久化（可作为队列存储）        | `IMPLEMENTED`（`TaskRepository`，含状态字段）                         |
| 队列语义（入队/出队/优先级/依赖/并发） | `ABSENT` → `DESIGNED`                                         |
| 取消传播                  | 部分 `IMPLEMENTED`（Workflow/Task 有 `CANCELLED`）；传播语义 `DESIGNED` |

> 本轮**不实现**完整后台调度系统（明确边界）。

***

## 25. Context Inspector

**目标**：让用户看到 **AI 到底看到了什么**。

```text
当前任务 / 当前模型 / 当前 Context Token
已加载：章节 · 人物 · 伏笔 · 时间线 · 世界规则 · 规划
Context 来源（哪些来自 Index 检索、哪些来自 World Model、哪些来自 Rules）
Context 优先级与取舍原因（被裁剪了什么、为什么）
```

| 项                       | 现状                                                                                          |
| ----------------------- | ------------------------------------------------------------------------------------------- |
| 可展示的数据基础                | 部分 `IMPLEMENTED`（`ChapterContextPack` 含分组、token 预算、`packVersion`；`StoryWorldContext` 为分层结构） |
| Inspector 数据结构（选择/裁剪原因） | `DESIGNED`                                                                                  |
| UI                      | `ABSENT` → `DESIGNED`（本轮不改 UI）                                                              |

***

## 26. Artifact System

**定义**：Agent 输出不只聊天文本，而是可创建/预览/编辑/比较/接受/拒绝/提交的**成果物**。

```text
Artifact 类型：
  章节正文 · 章节规划 · 卷规划 · 人物分析 · 人物弧 · 时间线 · 伏笔表
  人物关系 · 世界观文档 · 一致性报告 · 修改方案

Artifact 操作：Create · Preview · Edit · Compare · Accept · Reject · Commit
```

| Artifact                                           | 现有可复用载体                                   | 状态                                            |
| -------------------------------------------------- | ----------------------------------------- | --------------------------------------------- |
| 章节正文                                               | `ChapterDraft`（format/lineage/status）     | `IMPLEMENTED`（作为 Artifact 的**统一包装** `ABSENT`） |
| 章节规划                                               | `ChapterPlan` + Task Checkpoint           | `IMPLEMENTED`                                 |
| 知识/事件抽取                                            | `KnowledgeUpdateModels` + `MemoryEntry`   | `IMPLEMENTED`                                 |
| 一致性/检查报告                                           | Critique `ValidationResult`（章节级）          | 部分 `IMPLEMENTED`；跨域报告 `DESIGNED`              |
| 时间线/伏笔表/人物关系/世界观文档                                 | 数据已有（Story State / Timeline / Foreshadow） | 作为**可导出 Artifact** `DESIGNED`                 |
| Artifact 生命周期（Create→Compare→Accept/Reject→Commit） | ——                                        | `DESIGNED`                                    |

***

## 27. Workflow 与 Agent 的新关系

**结论：Workflow 不被删除，而是改变职责边界。**

```text
Workflow = 系统级生命周期 / 安全边界
Agent    = 当前任务执行者（决定"怎么做"）
Skill    = 专业能力（"怎么做得专业"）
Tool     = 实际操作（"能做什么"）
```

| 职责                                               | 归属                       | 现状                                                |
| ------------------------------------------------ | ------------------------ | ------------------------------------------------- |
| 决定具体步骤顺序                                         | **Agent**（新）             | 现在由 Workflow 固定五段 → `SUPERSEDED`（**用法**取代，代码保留）   |
| 生命周期（CREATED/RUNNING/PAUSED/CANCELLED/COMPLETED） | Workflow                 | `IMPLEMENTED`                                     |
| Checkpoint / 恢复                                  | Workflow + Task          | `IMPLEMENTED`（resultReference-first Recovery）     |
| Retry（Attempt 级）                                 | Workflow                 | `IMPLEMENTED`（`AttemptErrorCategory`）             |
| Cancel                                           | Workflow                 | `IMPLEMENTED`                                     |
| Human Gate（人工门幂等审批）                              | Workflow                 | `IMPLEMENTED`（`HumanDecision`）                    |
| 安全/权限最终边界                                        | Workflow + Action Policy | Workflow 侧 `IMPLEMENTED`；Action Policy `DESIGNED` |
| **最终提交边界（Commit）**                               | Workflow                 | 部分 `IMPLEMENTED`（确认→KU 链）；统一 Commit `DESIGNED`    |

### 27.1 WorkflowOrchestrator 的迁移观

```text
WorkflowOrchestrator（现状：固定五段执行 + 人工门 + 恢复）
        ↓
Novel IDE（目标：Workflow 提供 step 容器与安全边界，Agent 在容器内决定 step 内容）
```

- **保留**：`WorkflowOrchestrator` 的生命周期/Attempt/Gate/Recovery 机制（`REUSE`）。

- **扩展**：新增"Agent 驱动的 step"（step 由 AgentPlan 决定，而非硬编码 phase 列表）（`EXTEND`）。

- **重构**：仅"step 内容由谁决定"这一层（`REFACTOR`，不重写机制）。

- **明确不做**：删除 `WorkflowStepPhase` 或 Workflow 表结构。

***

## 28. 五个旧 Agent 如何迁移

```text
现状（IMPLEMENTED，位于 :application/usecase/writing/）
  PlannerAgent · WriterAgent · CritiqueAgent · RevisionAgent · KnowledgeUpdateAgent
        ↓ 转型（REFACTOR，保留算法/严格 Parser/快照机制）
能力（Capability / Skill）
  Planning Skill · Writing Skill · Critique Skill · Revision Skill · Knowledge Update Skill
        + 新增：Consistency Skill · Prose Quality Skill（DESIGNED）
        ↓ 由 Novel Agent 按任务选择调用
```

| 迁移纪律        | 说明                                                                                                                                             |
| ----------- | ---------------------------------------------------------------------------------------------------------------------------------------------- |
| 不删除任何 Agent | 五个 Agent 的 prompt、严格 Parser、异常类型、快照（`*Snapshot`）全部保留                                                                                           |
| 不破坏已冻结契约    | **P19 DecisionPolicy**：Planning 仍"决定一次"并落 Checkpoint，Writing 复用同一份（FD-4），**不得因 Agent 自主决策而改**                                                  |
| 保留确定性闸门     | `RevisionGate`（≤3）、`HumanGate`、Knowledge 前置 Confirmation Gate 原样保留                                                                             |
| 编排层变化       | "固定五段流水线"作为**唯一路径**被取代（用法 `SUPERSEDED`）；能力集合成为 Agent 的可选工具                                                                                     |
| 资产复用        | `PlanningContextAssembly`、`ContinuationResolver`、`ChapterPlanParser`、`DraftParser`、`CritiqueParser`、`KnowledgeValidator/Applicator` 全部 `REUSE` |

***

## 29. P19 如何复用

| 项                                                                    | 处理                             | 原因                                                                   |
| -------------------------------------------------------------------- | ------------------------------ | -------------------------------------------------------------------- |
| `DecisionType` / `DecisionOutcome` / `DecisionPolicy`（stateless 三字段） | **`SEALED`** **+** **`REUSE`** | 已冻结的领域契约；不新增字段（不加 id/version/timestamp/reason/score）                 |
| `DecisionModelUseCases`（确定性转译：AuthorContext → DecisionPolicy）        | `REUSE`                        | 纯确定性、stateless，适合作为 Agent 的"作者模型投影"能力                                |
| `DecisionPolicySnapshot`（FD-4：随 Checkpoint 持久化，Resume 不 re-decide）   | `SEALED` + `REUSE`（关键）         | **Novel Agent 引入自主决策不得破坏此条**：政策仍由 Application orchestration 一次性决定并复用 |
| `decisionModelGateway`（极薄 seam）                                      | `REUSE`                        | 不扩展为"Agent 决策入口"，避免双决策源                                              |

> **硬约束**：P19 = `SEALED`。任何"Agent 自主决定创作政策"的诉求都必须在**不改 P19 契约**的前提下表达（例如由 Action Policy 决定"是否调用决策能力"，而不是改写政策语义）。

***

## 30. P20 如何复用

| FD / 交付物                                                  | 处理                                     | 原因                                                                       |
| --------------------------------------------------------- | -------------------------------------- | ------------------------------------------------------------------------ |
| FD-1 受控 Markdown v1（`format` / ControlledMarkdown / 渲染降级） | **`SEALED`** **+** **`REUSE`**         | 正文格式唯一真源；Diff/Artifact 渲染直接复用其块结构                                        |
| FD-2 Task/Checkpoint 为业务事实、平台调度器仅 Scheduler               | `SEALED` + `REUSE`（**约束后台任务/队列设计**）    | 后台调度不得承载业务状态                                                             |
| FD-3 Checkpoint Resume 幂等                                 | `SEALED` + `REUSE`（Session Resume 的基础） | 幂等是 Session 恢复前提                                                         |
| FD-4 DecisionPolicy 单次决定                                  | `SEALED` + `REUSE`                     | 见 §29                                                                    |
| FD-5 Compose Desktop                                      | `SEALED` + `REUSE`                     | PC FIRST 与之一致；Novel IDE UI 仍为 Compose Desktop                            |
| FD-6 Vocabulary Confirmation                              | `REUSE`                                | 词法确认流可作"低风险自动/中风险记录"的既有范例                                                |
| FD-7 Reader 复用既有正文事实                                      | `REUSE`                                | Reader/Artifact 预览共用同一正文来源                                               |
| FD-8 Error Recovery 分层                                    | `REUSE` + `EXTEND`                     | Session/Queue 的错误恢复必须遵守同分层，不引入全局重试总线                                     |
| FD-9 Storage additive only                                | `SEALED` + `REUSE`（**约束所有新表设计**）       | 新模型只能加法式新增迁移                                                             |
| FD-10 Scope 限制                                            | `SEALED` + `REUSE`                     | 排除富文本/全文搜索/后台调度器实现/凭证加密/RAG/Vector/MCP/Cloud/Multi-Agent                 |
| P20 P1/P2/P3/P4/P5-fix 交付物                                | `REUSE`                                | 词法确认 / 受控 Markdown / Android Writer / Android Reader / DecisionPolicy 集成 |

***

## 31. Storage 如何复用

```text
REUSE（不重写）
  19 个 .sq 表族 · 16 个迁移（v17）· 20 个 Repository/Store 接口 · DatabaseInitializer 守卫触发器
EXTEND（additive only，FD-9）
  新模型按域新增表 + 新迁移（例：Project 聚合投影 / Agent Session / Activity / Artifact / Change / Index 缓存）
  迁移只加表/列/索引，不改既有列语义，不删表
REFACTOR（谨慎）
  · 跨仓储 Unit-of-Work（P12.0.1 遗留 TODO）——Commit 原子性是 §21 的前置
RESTRICTION
  · scope 隔离（ORIGINAL/VARIANT）与三个写保护触发器不得改动
  · 不引入新数据库系统；不绕过 SQLDelight
```

| 结论   | 内容                                                            |
| ---- | ------------------------------------------------------------- |
| 直接复用 | 全部现有表、仓储、迁移、触发器、`QianyanDbFactory`/`DatabaseInitializer`      |
| 需要扩展 | Session / Activity / Artifact / Change / Index 的持久化（新表 + 新迁移） |
| 需要重构 | 跨仓储事务边界（Unit-of-Work）                                         |
| 需要重写 | **无**                                                         |
| 暂不处理 | Index 缓存与全文/语义检索的持久化形态                                        |

***

## 32. Provider 如何复用

| 项                                                                                | 处理                                             | 原因                                                                      |
| -------------------------------------------------------------------------------- | ---------------------------------------------- | ----------------------------------------------------------------------- |
| `LLMGateway` / `ProviderRequest/Response` / `ModelProfile` / `ProviderException` | **`REUSE`（契约稳定）**                              | Agent/Skill/Tool 全部只依赖该契约                                               |
| `DefaultProviderAssembler` / DeepSeek / MiMo / Mock                              | `REUSE`                                        | 真实与离线双路径已可用                                                             |
| `ProviderCredentialStore`（Android Keystore seam / Desktop 文件明文）                  | `REUSE`（凭证加密仍属 PC-8 / FD-10，不在本轮）              | 安全边界不得弱化：API Key 不进 SQLite/Domain/Task/Checkpoint/Memory/StoryState/Log |
| 多 Provider 并行 / 路由 / 成本控制                                                        | `PLANNED`（`DESIGNED` 位置：Provider 之上的 Policy 层） | 不得改 `LLMGateway` 契约                                                     |
| 流式输出                                                                             | `PLANNED`（当前为同步请求-响应）                          | 与 Session/Activity 的实时性相关，单独设计                                          |

***

## 33. AgentRuntime 如何复用

| 项                                                        | 处理                                        | 原因                                                   |
| -------------------------------------------------------- | ----------------------------------------- | ---------------------------------------------------- |
| `AgentRuntime`（同步 LLM↔Tool 循环 + `maxSteps` + 类型化错误）      | `REUSE`                                   | 已验证的最小执行内核，职责边界干净（只依赖 `LLMGateway` + `ToolExecutor`） |
| `AgentContract` / `AgentState` / `AgentExecutionContext` | `REUSE` + `EXTEND`（增加 Session/Plan/日志上下文） | 契约稳定，可加法扩展上下文结构                                      |
| `AgentStep`（内存步骤）                                        | `EXTEND` → 可持久化的 Activity（§15）            | 需跨进程可查                                               |
| 同步模型（无 Coroutine/Flow）                                   | `REFACTOR`（定向，仅执行内核）                      | Background Task/Queue 要求异步与取消；**保留契约与循环语义**          |
| 失败语义（Provider/Tool 错误透传不重包）                              | `SEALED`（作为约束保留）                          | FD-8 分层错误恢复的基础                                       |

***

## 34. Application 如何复用

```text
REUSE（作为唯一能力入口，不破坏）
  ApplicationContainer（唯一 DI 根）· 17 个 usecase 子包 · 既有 UseCase/Gateway 命名与语义
EXTEND（additive）
  新增 Agent Session / AgentPlan / Skill Registry / Tool 实现 / Change / Artifact / Index / Context Engine 的 UseCase
REFACTOR（局部）
  writing 包的"固定五段"编排用法（能力保留，编排改由 Agent 选择）
  跨 UseCase 事务边界（与 §31 同一件事）
RESTRICTION
  UI 不得直连 Repository / SQLDelight / WorkflowOrchestrator（既有硬约束继续有效）
```

| 子包                                                                          | 复用判断                                         |
| --------------------------------------------------------------------------- | -------------------------------------------- |
| `novel` `chapter` `txt` `vocabulary` `memory` `genre` `override` `analysis` | `REUSE`（原样）                                  |
| `workflow` `task`                                                           | `REUSE` + `EXTEND`（Session/Queue 接入；机制不改）    |
| `writing`（35 文件）                                                            | `REUSE`（算法/Parser/快照/Gate）+ `REFACTOR`（编排用法） |
| `lcl` `context`                                                             | `REUSE`（Context Engine 的既有数据基础）              |
| `story` `foundation` `author` `decision` `reading`                          | `REUSE`（P19 见 §29）                           |
| 新域（Project/Workspace/Index/Session/Artifact/Change）                         | `DESIGNED`（本轮不实现）                            |

***

## 35. Desktop 如何演进

```text
现状（IMPLEMENTED · PC-1/PC-2/PC-2.1）
  Compose Desktop（FD-5）· DesktopGraph → ApplicationContainer
  Home / Plan（真实）· Write（真实，WriterGateway + workflowFacade）
  Story / Manage / Book / Author（骨架）· Provider Settings（真实）

目标（DESIGNED）
  三栏 Novel IDE：
  ┌──────────┬──────────────────┬──────────────┐
  │ Project  │    Workspace     │   AI Agent   │
  │ 世界/人物 │      正文        │ 当前任务      │
  │ 剧情/伏笔 │    第 89 章      │ 正在读取…     │
  │ 时间线    │                  │ ✓ 人物 ✓ 主线 │
  │ 章节      │                  │ Context/Action│
  └──────────┴──────────────────┴──────────────┘
```

| 项                                           | 处理                                              |
| ------------------------------------------- | ----------------------------------------------- |
| Compose Desktop 技术形态（FD-5）                  | `SEALED` + `REUSE`                              |
| `DesktopGraph`（装配根）→ `ApplicationContainer` | `REUSE` + `EXTEND`（注入 Session/Queue/Index seam） |
| UI 约束（不直连 Repository；骨架页不伪造能力）              | `SEALED`（继续有效）                                  |
| 现有 8 个页面                                    | `EXTEND`（真实页保留能力；骨架页随新架构接入）                     |
| 三栏 IDE 布局 + Agent 面板 + Diff/Inspector       | `DESIGNED`（**本轮不改 UI**）                         |
| PC-3…PC-9 旧阶段路线                             | `SUPERSEDED`（在新架构下重新规划）                         |

***

## 36. Android 为什么继续 PAUSED

| 理由   | 说明                                                                                                             |
| ---- | -------------------------------------------------------------------------------------------------------------- |
| 产品策略 | **PC FIRST**：先用 PC 验证 Project + Workspace + Agent + Tool + Context 的新范式，再定义移动端形态                               |
| 架构原因 | Agent Session / 三栏 Workspace / Index / Diff Review 属**重交互、重上下文**场景，与 PC 匹配；移动端应做 Companion 精简投影（direction §12） |
| 成本原因 | Android 端若先行改造，将在范式未定时重复实现两次                                                                                   |
| 保留范围 | `:app:android` 全部代码与功能**保留不删**、继续编译、测试保持通过；已交付的 P7 / P12.1.6 / P12.1.7 / P12.5 / P20-P3 / P20-P4 能力继续有效        |
| 未来   | PC Novel IDE 完成后，Android 以 **Companion**（阅读 / 轻量编辑 / 状态查看 / 简单操作 + 精简 Context Projection）重新设计                  |

***

## 37. 现有模块逐项迁移表

| 现有模块                               | 新架构职责                                                        | 处理方式                             | 原因（基于真实代码）                                                                                                                                                          |
| ---------------------------------- | ------------------------------------------------------------ | -------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `:core:model`（47 文件）               | Domain Model（Project / World / Planning / Draft / Change 契约） | **EXTEND**                       | 已覆盖 Novel/Story/World/Timeline/Knowledge/Memory/Vocabulary/Task/Workflow/Writing/LCL/Decision/Author；缺 Project/Workspace/Session/Artifact/Change/Index 契约（`ABSENT`） |
| `:core:engine`（15 文件）              | Deterministic Engine（TXT / 受控 Markdown / 未来一致性检查）            | **REUSE + EXTEND**               | `TxtPipeline`/`ControlledMarkdown` 已确定性且被冻结引用；一致性检查引擎缺失                                                                                                             |
| `:storage`（103 文件，v17）             | Project / Workspace Persistence                              | **REUSE + EXTEND**               | 19 表族 / 16 迁移 / 20 仓储 / 触发器齐备；新模型按 FD-9 additive 新增；跨仓储事务待重构                                                                                                        |
| `:provider:api` + `:provider:impl` | LLM Layer                                                    | **REUSE**                        | 契约稳定、真实/离线双路径可用、凭证边界已封存                                                                                                                                             |
| `:agent:tool`（324 行）               | Tool Registry（契约层）                                           | **REUSE（契约）+ EXTEND（实现）**        | 契约/注册表/执行器/类型化错误已有；**产品级 Tool 为 0**                                                                                                                                 |
| `:agent:runtime`（532 行）            | Agent Session / Runtime 执行内核                                 | **REUSE + EXTEND +（定向）REFACTOR** | 循环/maxSteps/错误语义可复用；会话与日志需扩展；后台化需异步（保留契约）                                                                                                                           |
| `:agent:agents`（1 smoke 文件）        | Skills / Capabilities                                        | **占位 → 重新承载（非重写）**               | **实际为空壳**；五 Agent 在 `:application`（审计发现 A1）                                                                                                                         |
| `:agent:orchestration`（1 smoke 文件） | Agent Lifecycle / Workflow Boundary                          | **占位 → 暂不处理**                    | 空壳；生命周期已由 `:application/workflow` 承担                                                                                                                                |
| `:application`（165 文件）             | Use Cases / Application Services（唯一能力入口）                     | **REUSE + EXTEND +（局部）REFACTOR** | 17 子包覆盖全部既有能力；`writing` 编排用法需重构；UI 硬约束继续有效                                                                                                                          |
| `:app:desktop`（23 文件）              | Novel IDE UI                                                 | **EXTEND → REFACTOR（UI 结构）**     | PC-1/PC-2 已把真实能力接上 Compose Desktop；三栏 IDE 属 UI 演进                                                                                                                   |
| `:app:android`（42 文件）              | Companion / Mobile Client                                    | **PAUSED**                       | 当前阶段不做；代码保留                                                                                                                                                         |
| `:runtime`（1 smoke 文件）             | 运行时装配位（可承载 Session/Queue 运行时）                                | **占位 → 备用**                      | 空壳，可直接承载新组件                                                                                                                                                         |
| `:test:e2e`（1 smoke 文件）            | 端到端验收位                                                       | **占位 → 备用**                      | 空壳；未来承载 Novel IDE E2E                                                                                                                                               |

***

## 38. 直接复用 / 扩展 / 重构 / 重写 / 暂不处理

### 38.1 完全不用动（REUSE / SEALED）

```text
:core:engine 确定性能力（TxtPipeline / ControlledMarkdown / MarkdownModel / AnalysisInputBuilder）
:provider（api + impl，含凭证边界）
:storage 既有表族 / 迁移 / 仓储 / 写保护触发器 / DatabaseInitializer
:application 的 novel/chapter/txt/vocabulary/memory/genre/override/analysis/lcl/context/story/foundation/author 子包
:application 的 workflow/task 机制（状态机 / Attempt / Gate / Recovery 机制本身）
P19 DecisionPolicy 契约 + DecisionPolicySnapshot（FD-4）
P20 FD-1…FD-10 全部
:agent:tool 契约层 · :agent:runtime 循环契约
:app:desktop 的 Compose Desktop 形态 + DesktopGraph 装配 + UI 硬约束
:app:android 全部（PAUSED 但保留）
```

### 38.2 只需要扩展（EXTEND，加法式）

```text
:core:model      + Project / Workspace / Session / Activity / Artifact / Change / Index 契约（additive）
:storage         + 上述新模型的表与迁移（FD-9：只加表/列/索引）
:agent:runtime   + Session 上下文 / Activity 持久化钩子 / Skill 调用入口
:agent:tool      + 产品级 Tool 实现（read/search/edit/check/analyze 族）
:application     + Session / AgentPlan / SkillRegistry / Change / Artifact / Index / ContextEngine 的 UseCase
:core:engine     + Consistency Engine（跨要素确定性检查）
:workflow        + "Agent 驱动的 step"（step 内容由 AgentPlan 决定）
:app:desktop     + Session/Queue/Index/Inspector 的 seam 注入与页面演进
```

### 38.3 需要重构（REFACTOR，范围受控）

```text
:application/usecase/writing 的**编排用法**（固定五段作为唯一路径 → 能力集合 + Agent 选择）
                                 保留：Agent 实现 / Prompt / 严格 Parser / Snapshot / Gate
:application/workflow         "step 内容由谁决定"这一层（机制不改）
:agent:runtime                同步执行内核 → 支持异步/取消（**保留契约与错误语义**）
:storage                      跨仓储事务边界（Unit-of-Work，P12.0.1 遗留 TODO）——§21 Commit 原子性前置
:app:desktop                  UI 结构（页面 → 三栏 IDE；**不在本轮**）
```

### 38.4 未来可能重写（REWRITE，谨慎且定向）

```text
:agent:agents        目前是空壳（1 个 smoke 文件）→ 未来在此落地 Skill/Capability 注册（不是"重写旧代码"，而是"填充占位"）
:agent:orchestration 同上（空壳）→ 生命周期编排若最终迁出 :application/workflow，则在此重建薄编排层
:runtime             空壳 → 若引入 Background/Queue，在此落地运行时装配
```

> 说明：`REWRITE` 仅针对**空壳占位模块**与**执行内核的异步化**；**不存在**对 Storage / Provider / Core / Workflow 机制 / Application UseCase 的整体重写计划。

### 38.5 应该直接废弃（DEPRECATED）

```text
代码级废弃：无（本轮不删除任何代码、模块、表、文档）
用法级废弃（SUPERSEDED）：
  · "固定五段 Agent 流水线作为唯一编排路径"        → 由 Agent 选择能力取代
  · 旧 PC 路线（PC-3 Reader … PC-9 E2E）           → 由 Novel IDE 架构下的新阶段取代（历史保留）
  · 文档中"P14+ 未来阶段"旧口径                     → 由现行状态表取代（历史保留）
```

***

## 39. 数据流

```text
① 只读任务（检查/分析）
User → Agent → Plan → Index(检索) → Context Engine → LLM → Finding → Activity Log → 展示

② 创作任务（写第 89 章）
User → Agent → Plan → Index(第88章/人物/伏笔/规则) → Context Engine →
  ├─（可选）Planning Capability → ChapterPlan（Working Draft）
  └─ Writing Capability → 正文（Working Draft，受控 Markdown v1）
→ Validation（确定性 + 语义）→ Diff → Human Gate →
  Commit → Canonical Story（Draft CONFIRMED）+ World Model 更新（KU → Memory/Story State）

③ 维护任务（后台一致性）
Queue → Background Task → Consistency Engine（确定性优先）→ Finding/Artifact → Activity Log
   （不改 Canonical；如需修改 → 升级为 ② 的 Proposal 路径）
```

不变式：

- **任何** Canonical 写入都必须经过 `Validation → Human Gate/Policy → Commit`。

- Original 只读约束在**所有**路径成立（物理触发器兜底）。

- Context 只读 Project / Index，不反向写。

***

## 40. Agent 工作流

```text
1. 接收 User Request（自然语言 / 结构化指令）
2. 分类任务（只读 / 创作 / 维护 / 结构变更）→ 风险等级
3. 制定 Agent Plan（步骤 + 需要的 Skill/Tool + 预计影响范围）
4. （高风险）Plan 预览与用户确认；（低/中风险）直接进入执行
5. 逐步执行：
   a. 经 Index 定位 → Context Engine 组装（快照）
   b. 调用 Skill（专业能力）→ 内部经 Tool 读写
   c. 所有读写先进 Working Draft
   d. 每步写 Activity（含 Tool 调用与结果）
6. Validation（确定性校验 → 必要时语义评审）
7. 产出：Artifact + Diff + Proposal
8. Human Gate（高风险必过；低风险可配置）
9. Commit（原子化写 Canonical + 世界事实 + 版本记录 + Index 失效）
10. 归档 Session（可 Resume / Replay / Revert）
异常路径：Attempt 级 Retry（可恢复）→ 人工介入（NEEDS_HUMAN）→ 终止（TERMINAL）
```

***

## 41. Session 生命周期

```text
CREATED
  ↓（接受请求、生成 Plan）
PLANNING            ← 用户可否决计划（高风险）
  ↓
RUNNING  ⇄  PAUSED          （PAUSED 可 Resume；幂等恢复 FD-3）
  │  │
  │  └─→ WAITING_HUMAN      （Human Gate 待决；不自动通过）
  │            ↓
  │        （APPROVED）→ 继续 RUNNING
  │        （REJECTED / REQUEST_REVISION）→ 回到 RUNNING / 终止
  │
  ├─→ FAILED  ⇒ 可 RETRY（Attempt 级，分类化：RETRYABLE / NON_RETRYABLE / NEEDS_HUMAN / TERMINAL）
  └─→ CANCELLED（用户或策略取消；已产生的 Working Draft 保留但不提交）
  ↓
VALIDATING → PROPOSED →（Gate）→ COMMITTED
  ↓
ARCHIVED（可 Resume 查看 / Replay / Revert；Artifact 与 Activity 长期留存）
```

> 状态词汇**复用**既有 `WorkflowStatus` / `TaskStatus` 语义，不新造第二套并行状态机。

***

## 42. Change 生命周期

```text
Intention（Agent/User 的修改意图）
   ↓
Working Draft（临时成果，不动 Canonical）
   ↓
Validation（确定性 + 语义）
   ↓（未通过）
Rejected / NeedsRevision（带 Finding 回到 Working Draft）
   ↓（通过）
Diff / Proposal（结构化变更描述：文本 + 人物状态 + 伏笔 + 时间线 + 规划）
   ↓
Human Gate / Action Policy
   ├─ 低风险：自动通过（留 Activity）
   ├─ 中风险：通过 + 记录
   └─ 高风险：用户确认（APPROVED / REJECTED / REQUEST_REVISION）
   ↓（APPROVED）
Commit（原子化）
   ├─ Canonical Story 更新（正文/规划/事实）
   ├─ World Model 更新（Story State / Memory / Foreshadow / Timeline）
   ├─ Canonical / Change History 追加（含 changedFields 级差异）
   └─ Index / ContextPack 失效与重建
   ↓
Applied（可 Revert：以 Change 为单位的回退，见 §22）
```

***

## 43. 安全边界

| 边界                | 约束                                                                                                                    | 现状                                   |
| ----------------- | --------------------------------------------------------------------------------------------------------------------- | ------------------------------------ |
| Original 只读       | `novel_original_update_protect` / `novel_original_delete_protect` / `variant_base_must_be_original` 三个触发器（物理级）        | `IMPLEMENTED / SEALED`               |
| Scope 隔离          | Original / Variant 严格隔离；Variant 之间不可见                                                                                 | `IMPLEMENTED / SEALED`               |
| 无第二写路径            | 所有写入经 Application UseCase；UI 不得直连 Repository / SQLDelight / WorkflowOrchestrator                                      | `IMPLEMENTED`（PC-2 起有可执行架构守卫测试）      |
| HITL 不可绕过         | Human Gate 幂等审批；确认绑定到具体 Draft；重新修订需重新确认                                                                               | `IMPLEMENTED / SEALED`               |
| DecisionPolicy 单源 | Planning 决定一次，Writing 复用；Resume 不 re-decide                                                                           | `IMPLEMENTED / SEALED`（FD-4）         |
| 凭证边界              | API Key 不进 SQLite / Domain / Task / Checkpoint / Memory / Story State / Prompt / Log                                  | `IMPLEMENTED`（FD / P12.1.5）；加密待 PC-8 |
| Storage 演进        | 只允许 additive（FD-9）                                                                                                    | `IMPLEMENTED / SEALED`               |
| 范围边界              | FD-10 排除项：富文本 / 全文搜索 / 后台调度器实现 / Error Recovery UX / 凭证加密 / RAG / Vector / Embedding / MCP / Cloud / Multi-Agent / 微调 | `SEALED`（本轮不解除）                      |
| 新增（DESIGNED）      | Agent **不得**直接写 Canonical；写类动作必须产 Proposal 并过 Gate；Action Policy 为会话级上限                                               | `DESIGNED`                           |
| 新增（DESIGNED）      | Session 取消后**不得**继续写 Canonical；Working Draft 保留但不提交                                                                   | `DESIGNED`                           |

***

## 44. 下一阶段实现顺序

> 顺序原则：**先把"边界与状态"立起来，再把"能力"接进来**；每一步都要可独立验证、可回退。

| #   | 阶段                                     | 内容                                                             | 依赖          | 风险                       |
| --- | -------------------------------------- | -------------------------------------------------------------- | ----------- | ------------------------ |
| I0  | 设计评审                                   | 本文档评审通过（含 §37/§38 结论）                                          | —           | 低                        |
| I1  | **Project 聚合与 Project State 契约**       | Project 身份/聚合投影 + Project State 定义（新增 domain 契约 + additive 迁移） | I0          | 中（避免与 Novel/Variant 双身份） |
| I2  | **Action Policy + Human Gate 复用**      | 动作权限模型 + 风险分级 + 复用既有 Gate（不改 P19）                              | I1          | 中（不得绕过既有确认语义）            |
| I3  | **Agent Session（持久化 + 生命周期）**          | Session 状态机/持久化/Resume/Cancel，复用 Task/Checkpoint 范式            | I2          | 中（与 Workflow 状态词汇对齐）     |
| I4  | **Activity / Tool Log**                | 步骤与 Tool 调用持久化（含结果与错误分类）                                       | I3          | 低                        |
| I5  | **Tool 实现（只读族先行）**                     | `read_file`/`search_*`/`check_*` 的只读 Tool（无 Canonical 写入）      | I4          | 低（只读，风险最小）               |
| I6  | **Context Engine（任务级 Projection）**     | 在既有 Resolver/Pack 之上加任务级选择/预算/快照                               | I5          | 中（确定性要求）                 |
| I7  | **Skill Registry + 五能力接入**             | 把既有五 Agent 暴露为 Skill（保留算法/Parser/Gate）                         | I6          | 中高（编排重构）                 |
| I8  | **Working Draft + Validation 统一**      | 跨域工作区与统一校验结果模型                                                 | I7          | 中高                       |
| I9  | **Diff / Change Review + Artifact**    | 统一 Diff（文本+状态+规划）与 Artifact 生命周期                               | I8          | 中                        |
| I10 | **Commit（原子化）+ History/Revert**        | 跨仓储 Unit-of-Work + Change History + 按 Change 回退                | I9          | **高**（原子性与回退正确性）         |
| I11 | **Novel Agent 编排 + Agent Plan 分级**     | 单入口 Agent：分类→计划→选择能力→执行→校验→提案                                  | I7–I10      | **高**（自由度最大）             |
| I12 | **Project Index（结构/实体/事件/伏笔/关系）**      | 索引构建与查询；不含全文/语义                                                | I1, I6      | 中                        |
| I13 | **Background Task / Queue（受 FD-2 约束）** | 队列 + 调度器（仅 Scheduler）+ 只读检查类后台任务                               | I4, I12     | 中高                       |
| I14 | **Context Inspector + UI 演进（三栏 IDE）**  | 面板与 Inspector（UI 大改，单独阶段）                                      | I6, I9, I13 | 中高                       |
| I15 | **E2E 与新阶段收口**                         | 真实长篇场景 E2E + 文档/契约收口                                           | 全部          | 中                        |

> 与旧路线的关系：旧 **PC-3…PC-9** 全部 `SUPERSEDED`；其未交付项（Reader/Story/Plan/Manage/Author/Book/Export/Provider 产品化）在新架构下**按上表现有次序重新落位**，不机械续做。

***

## 45. 架构风险

| #   | 风险                    | 说明                                          | 缓解                                                                    |
| --- | --------------------- | ------------------------------------------- | --------------------------------------------------------------------- |
| R1  | **Project 双身份**       | 新增 Project 聚合可能与既有 Novel/Variant 形成两套身份     | Project 只做**聚合投影**，底层仍以 Novel/Variant 为身份锚（§4/§31）                    |
| R2  | **Agent 自由度 vs 安全边界** | Agent 自主决定路径可能越权写 Canonical                 | Action Policy + 一律 Proposal + Gate；Original 触发器兜底（§16/§43）            |
| R3  | **编排重构影响既有语义**        | 从固定五段改为能力选择，易破坏 FD-4/HITL/Revision 上限       | 先做 I2–I3（边界）再做 I7（能力）；保留全部 Gate 与 Snapshot；回归测试先扩后改                   |
| R4  | **Commit 原子性**        | 跨仓储 Unit-of-Work 仍是遗留 TODO；半提交会污染 Canonical | 把 Unit-of-Work 提到 I10 前置；在完成前**不允许** Agent 写 Canonical                |
| R5  | **Context 不确定性**      | 任务级 Context 若含随机/时间因素，破坏可复现性                | 沿用 `ChapterContextPack` 的确定性 + `packVersion` 范式；Snapshot 冻结本次 Context |
| R6  | **Index 与事实漂移**       | 派生索引可能过期导致 Agent 读到旧事实                      | Index 可重建 + Commit 后失效/重建；**事实永远从 Project 读**，索引只定位（§6）               |
| R7  | **状态机重复**             | 新造 Session/Queue 状态可能与 Workflow/Task 语义冲突   | 复用既有状态词汇，不新造并行状态机（§41）                                                |
| R8  | **Tool 爆炸与权限泄漏**      | 写类 Tool 若无约束会成为绕过 Gate 的后门                  | 写类 Tool **只写 Working Draft**；Canonical 只能经 Commit（§12/§17/§21）        |
| R9  | **后台任务与业务事实混淆**       | 调度器承载业务状态会违反 FD-2                           | 调度器仅 Scheduler；事实仍在 Task/Checkpoint（§23）                              |
| R10 | **范围蔓延**              | Novel IDE 极易引入 RAG/Vector/MCP/Cloud/多 Agent | FD-10 继续生效；每阶段验收显式检查排除项（§46）                                          |
| R11 | **文档与代码漂移**           | 本次审计已发现 A1（Agent 不在 `:agent:agents`）等不一致    | 每阶段收口更新本文档 §37/§0；把"文档-代码一致性"纳入阶段验收                                   |
| R12 | **Android 长期脱节**      | PAUSED 期间 Android 与新契约脱节                    | Android 保留可编译 + 回归通过；新契约保持 additive，Companion 阶段再对齐（§36）              |

***

## 46. 明确不做事项（本轮 + 近期）

**本轮（DESIGN ONLY）明确不做**

```text
❌ 修改任何业务代码（Kotlin / Java / SQL / Gradle）
❌ 修改 Android / Desktop UI
❌ 重写或删除 Agent / Workflow / 模块 / 表
❌ 修改 P19 DecisionPolicy；破坏 P20 Freeze
❌ 引入 RAG / Vector DB / Embedding / MCP / Cloud / Multi-Agent
❌ 实现 Project / Workspace / Index / Session / Tool / Agent
❌ 开始 PC UI 重做；开始 Background Agent；Plugin Marketplace
❌ 新建数据库 schema
```

**近期阶段仍不做（需单独决策）**

```text
· 富文本编辑器（FD-10）            · 全文搜索（FD-10）
· 凭证加密（PC-8 / FD-10）          · Error Recovery UX（FD-10）
· 平台后台调度器实现（FD-2 仅允许 Scheduler）· 跨设备同步 / 云
· 语义检索 / 向量检索                · Git 作为小说数据层
· Android 产品开发（PAUSED）
```

***

## 附录 A：架构审计发现（代码 vs 文档）

| #  | 发现                                                                                                                                            | 建议（本轮不改代码）                                                                  |
| -- | --------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------- |
| A1 | 五 Agent 实际在 `:application/usecase/writing`，与"`agent:agents`"的命名直觉不符；`:agent:agents`/`:agent:orchestration`/`:runtime`/`:test:e2e` 只有 smoke 占位 | 在新架构中明确"Skill/Capability 落地位置"，避免误把空壳当能力；文档署名时标注真实位置（本文件 §0/§37 已做）         |
| A2 | `Project Index` 与 SQLite `index` 同名易混淆                                                                                                        | 文档与代码统一使用 `ProjectIndex` / `IndexProvider` 命名                               |
| A3 | "Agent Plan" 与 "ChapterPlan" 命名冲突                                                                                                             | Agent 计划统一命名 `AgentPlan`（§14）                                               |
| A4 | 跨仓储 Unit-of-Work 仍是遗留 TODO（P12.0.1）                                                                                                           | 提升为 I10 前置风险项（§45 R4）                                                       |
| A5 | 旧 PC UI 契约文档中 PC-3 起阶段仍以"现行路线"措辞存在                                                                                                            | 已在 `qianyan-pc-ui-contract.md` 添加方向横幅（`dbc750f`，PC-3 起 `PAUSED/SUPERSEDED`） |
| A6 | `Draft.format` 的"新 Draft 打标"语义曾有边界缺陷（COMPLETED 后回溯迁移）                                                                                         | PC-2.1 已修复（`8db807e`）；本文档 §17/§20 的"Working Draft vs Canonical"设计继续以该语义为基线  |
| A7 | README 路线表存在与实现不一致的旧标注（如 P14/P16 标 PLANNED 而实际已有实现）                                                                                           | 已在 README 增加"现行状态（2026-09-28 核实）"表（`dbc750f`）                               |

## 附录 B：本文档的一致性检查清单

- [x] 所有结论标注状态（`IMPLEMENTED` / `SEALED` / `PROTOTYPE` / `DESIGNED` / `PLANNED` / `PAUSED` / `SUPERSEDED` / `ABSENT`）

- [x] 未把 `DESIGNED` 写成已实现；未把 `PLANNED` 写成现有能力

- [x] §37 迁移矩阵每行均基于**实际读取的代码**（§0 清点表）

- [x] P19 / P20 结论为 `SEALED` + `REUSE`，未提出任何契约修改

- [x] 未提出 RAG / Vector / Embedding / MCP / Cloud / 多 Agent 实现（仅保留接口位）

- [x] 明确"不删除任何代码/模块/文档"

- [x] 本轮代码改动 = 0
