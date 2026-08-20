# Qianyan V4.1 Implementation Plan

> ⚠️ **本文档为历史规划文档（V4.1，P0–P18 旧编号），已被现行路线取代。**
>
> 本文档使用的阶段编号与**现行唯一阶段口径不同**：
> - 本文档 `P8 = Tool System`、`P9 = Agent Runtime`、`P11 = Writing Workflow + 最小 Android UI`、`P12 = TXT Vertical Slice` 等，均为 **V4.1 历史细分计划的旧编号**。
> - **现行路线**以 [README.md](../../README.md#current-development-roadmap现行路线唯一阶段口径) 的「Current Development Roadmap」为准：`P8.1 = Task Storage`、`P8.2 = Task Manager / State Machine`、`P8.3 = Task Execution`、`P9 = Real LLM Provider（DONE）`、`P10 = Agent Runtime + Tool System`、`P11 = Writing Workflow / 完整小说创作 Pipeline`、`P12+ = 高级能力`。
> - 请勿把本文档的 P 编号当现行阶段；涉及阶段进度一律以 README / 当前状态文档 / completion report 为准。
>
> 本文档内容保留作为 **V4.1 历史规划档案**，不依据旧编号推导当前状态。

> **For agentic workers:** 本计划以 **Codex 为执行对象**。请严格按照本文档 Phase 顺序与各 Phase 内任务执行；每完成一个任务执行一遍：编译 → 测试 → 架构检查 → 更新文档 → Commit。禁止一次修改数百个文件。
>
> **架构约束（不可违反）**：以 [qianyan-master-plan.md](qianyan-master-plan.md)（V4.1，冻结）为唯一依据。**不修改架构、不新增 Agent、不重新设计系统**。若实现中发现架构层冲突，禁止自行改架构，须标记 `[IMPLEMENTATION ISSUE]` 并上报。

**Goal:** 分阶段交付一个 Android + PC 双端、本地优先的 AI 小说创作系统（Qianyan V4.1），并尽早通过两条 End-to-End Vertical Slice 验证 Agent Architecture + Workflow + AI Provider + Knowledge + UI 真实可用。

**Architecture:** 严格沿用冻结的 V4.1 分层架构：UI → Application → Task Manager → Agent Orchestrator → 6 Agent → Tool Layer → Core Engines（确定性）→ Novel Knowledge / Memory → Storage（SQLite）。核心为 Kotlin/JVM 共享 Core，Android 与 PC 通过不同 Runtime Adapter 复用同一 Domain / Agent / Workflow / Tool / Provider 契约。

**Tech Stack:** Kotlin + Gradle（多模块、version catalog）；SQLite（Android 内建 / PC sqlite-jdbc）+ FTS5；kotlinx.serialization + coroutines；Jetpack Compose（Android UI）；Compose Desktop 或 JavaFX（PC UI，见 TBD-3）；DeepSeek / MiMo 经 LLM Gateway；JUnit5 + kotlinx-coroutines-test + MockK。

---

# 目录

1. [Executive Summary](#1-executive-summary)
2. [Architecture Baseline](#2-architecture-baseline)
3. [Dependency Graph 与排序理由](#3-dependency-graph-与排序理由)
4. [Phase Plan（总表）](#4-phase-plan总表)
5. [Vertical Slice 计划](#5-vertical-slice-计划)
6. [Domain 实现计划（Domain First）](#6-domain-实现计划domain-first)
7. [Storage 计划](#7-storage-计划)
8. [TXT Pipeline 计划](#8-txt-pipeline-计划)
9. [AI Provider 计划](#9-ai-provider-计划)
10. [Tool System 计划](#10-tool-system-计划)
11. [Agent Runtime 计划](#11-agent-runtime-计划)
12. [Agent 实现计划](#12-agent-实现计划)
13. [Workflow 计划](#13-workflow-计划)
14. [Story Intelligence 计划](#14-story-intelligence-计划)
15. [Android 计划](#15-android-计划)
16. [PC 计划](#16-pc-计划)
17. [Runtime Switching 计划](#17-runtime-switching-计划)
18. [Testing 计划](#18-testing-计划)
19. [Git 策略](#19-git-策略)
20. [Definition of Done](#20-definition-of-done)
21. [Risks](#21-risks)
22. [TBD（待用户决定）](#22-tbd待用户决定)
23. [Future Work](#23-future-work)
- [Phase 0 → N 执行顺序（总览）](#phase-0--n-执行顺序总览)
- [下一步 Codex 应该实际做什么](#下一步-codex-应该实际做什么)

---

# 1. Executive Summary

- 本计划将 V4.1 架构分解为 **19 个 Phase（P0–P18）**，每个 Phase 有独立的 Goal / Modules / Dependencies / Tasks / Deliverables / Tests / Acceptance Criteria / Risks，并有明确的"Phase 完成后系统能运行什么"。
- **两条 Vertical Slice 被前置**：
  - **Slice A（写作链路）**：`写一个主角进入地下城的场景` → Intent → Research → Planner → Writer → Critic → Revision → Draft → Knowledge Update，在 **P11** 落地（含最小 Android UI）。
  - **Slice B（TXT 链路）**：上传 TXT → 切章 → Chunk → 基础分析 → 人物/事件/时间线 → Knowledge → Novel Project → `继续写下一章` 全链路，在 **P12** 落地。
- **关键排序调整**（相对建议顺序）：AI Provider（原 P8）提前到 **P3** 与 Storage 并行；Task System（原 P7）提前到 **P4**（Workflow 与长任务恢复依赖它）；Android 最小 UI 不再等 P19，提前到 **P11** 支撑 Vertical Slice；Story Intelligence 完整 AI 推理延后到 **P14**（领域模型在 P1 先建，不阻塞基本写作）。
- **发现 5 处实现层注意事项**，其中 1 处（Workflow 状态清单冲突）**需要用户决定**，详见第 2 节 `[IMPLEMENTATION ISSUE]`。
- 全程不写业务代码、不修改架构；Codex 按"Small Incremental Changes"执行。

---

# 2. Architecture Baseline

## 2.1 冻结架构要点（实现必须遵守）

| 层 | 内容 | 出处 |
|----|------|------|
| 分层 | UI → Application → Task Manager → Orchestrator → 6 Agent → Tool → Engines → Knowledge/Memory → SQLite | 1.3 |
| 角色分离 | Agent 推理/决策；Engine 确定性执行；Task 生命周期；Provider 调模型；Orchestrator 控制流程 | 1.4 |
| 核心原则 | AI 永不直接写数据库；写操作走 Proposal → Validator → Engine；结构化输出；Knowledge 分级；可追溯；防无限循环（Revision≤3）；不污染原文 | 1.4 |
| 六大 Agent | Intent / Research / Story Planner / Writing / Critic / Knowledge（**冻结**） | 19.2 |
| 引擎 | Novel / TXT / Analysis / Context / Validation / Knowledge / Task | 1.5 |
| Story Intelligence | Story Hierarchy（Arc/Act/ChapterPlan/ScenePlan/Beat）+ Conflict/Stakes/Foreshadowing/Payoff/InfoState/Pacing/EmotionalArc | 9–10 |
| 项目持久化 | Creative Project / Schema Version / Backup / Migration / Storage | 26–28 |
| Runtime | Android Runtime / PC Runtime；手动切换优先，自动发现后续 | 29 |

## 2.2 计划中的代码布局（从架构 1.5 推导，属实现组织，不改变架构）

```
settings.gradle.kts
gradle/libs.versions.toml
:core:model             → architecture core/model/（无依赖，纯领域）
:core:engine            → architecture core/engine/（validation/knowledge/context/txt/analysis/novel/task）
:agent:tool             → architecture agent/tool/
:agent:runtime          → Agent Runtime 框架（新增为 19–20 章契约的实现载体）
:agent:agents           → 6 个 Agent
:agent:orchestration    → Orchestrator / WritingWorkflow / 状态机 / HITL
:provider               → architecture provider/ + gateway/
:storage                → SQLite / FTS / Repository / Migration / Backup（MigrationEngine 归入此处实现）
:runtime                → 平台 Runtime 抽象（文件系统/网络/DB）
:app:android            → architecture android/
:app:desktop            → architecture desktop/
:test:e2e               → 端到端测试
```

> 说明：`:core:engine` 为多个 Engine 的聚合模块（内部按子包划分），避免过早拆出过多 Gradle 模块；若某 Engine 独立演进需求出现，再拆分。这不改变架构。

## 2.3 ⚠️ [IMPLEMENTATION ISSUE]（不修改架构，逐条上报）

### ISSUE-1：Workflow 状态清单冲突 —— [DECIDED] 已解决

- **问题**：用户在《开发目标（十二）》中给出的状态流 `RECEIVED → INTENT_ANALYSIS → CONTEXT_RESEARCH → PLANNING → WRITING → CRITIQUE → REVISION → FINAL_REVIEW → KNOWLEDGE_UPDATE → COMPLETED` 与冻结架构 §23.2 的 `WorkflowState` 枚举**不一致**：
  - 架构含 `PLAN_REVIEW`（HITL 规划确认）与 `CONFLICT_HITL`（冲突人工裁决），用户清单没有；
  - 用户清单含 `RECEIVED` 与 `FINAL_REVIEW`，架构枚举没有。
- **影响**：状态机实现、UI 进度展示、Checkpoint 恢复点的命名都依赖这一清单；不统一会导致实现漂移。
- **最终决策（[DECIDED]，V4.2）**：以架构 §23.2 `WorkflowState` 为**唯一** Workflow 状态机，**不再存在第二套并列的 Workflow 状态枚举**。与 §23.2 不一致的历史清单一律以 §23.2 为准。
- **状态映射**（用于表达产品语义，不新增 Workflow 状态；详见架构 §23.3）：
  - `RECEIVED` → 由 `TaskStatus=PENDING`（§18.2）承载，**不新增 Workflow 状态**；
  - `INTENT_ANALYSIS` → `WorkflowState.INTENT_PARSING`；
  - `CONTEXT_RESEARCH` → `WorkflowState.RESEARCH`；
  - `FINAL_REVIEW` → 复用 `WorkflowState.CRITIQUE`（最后一次复查），通过即 `COMPLETED`。
- **状态族分离**：WorkflowState / TaskState(= §18.2 TaskStatus) / AgentState(§20.2) / DraftState[TBD] / HITLState[TBD] 五个状态族互不替代，定义见架构 §23.3。

### ISSUE-2：共享 Core 的跨平台技术选型 —— 建议决策

- **问题**：架构要求"共享 Core，平台实现不同"，但未指定 Kotlin Multiplatform（KMP）还是纯 Kotlin/JVM。Android 与 PC（JVM Desktop）均可消费同一 JVM Core。
- **影响**：决定工程结构、平台适配层写法、未来是否可上 iOS。
- **建议**：**第一版采用纯 Kotlin/JVM 共享 Core**（Android 也运行在 JVM 生态，`:core:*`、`:agent:*`、`:provider`、`:storage`、`:runtime` 全部为 JVM 模块，Android 与 Desktop 应用依赖同一 Core）。仅当明确未来需要 iOS 时才迁移 KMP。
- **是否需要用户决定**：否（采用建议即可）；仅当有 iOS 目标时需用户决定。

### ISSUE-3：Structured Output 的跨 Provider 一致性

- **问题**：架构要求"所有 Agent 输出必须结构化且经 Validator 校验"（§12），但 DeepSeek（OpenAI 兼容）的 JSON mode 不保证严格 schema，MiMo 能力未知。
- **影响**：不能依赖 Provider 的"structured output"能力，否则 Agent 输出可能不可解析。
- **建议**：**SchemaValidator 为唯一真相**——任何 Provider 输出都先过 JSON Schema 校验 + 修复 + Retry（最多 3 次）→ 标记 UNCERTAIN。Provider 的 JSON mode 仅作为"优化提示"，不作为正确性保证。
- **是否需要用户决定**：否。

### ISSUE-4：MiMo Provider 契约未知 —— 需要用户提供

- **问题**：架构列出 Provider=DeepSeek / MiMo，但 MiMo 的 endpoint / model 名 / 鉴权方式 / 是否 OpenAI 兼容未知。
- **影响**：P3 只能交付 DeepSeek Adapter + MockProvider；MiMo Adapter 无法编写真实测试。
- **建议**：P3 先定义统一 `LLMProvider` 接口并实现 DeepSeek + Mock；MiMo 以接口适配器占位，待用户提供接入信息后补齐（见 TBD-1）。
- **是否需要用户决定**：是（需要用户提供 MiMo API 信息）。

### ISSUE-5：Task Checkpoint `snapshot: Map<String, Any>` 的序列化边界

- **问题**：架构 §18.2 中 `Checkpoint.snapshot` 为任意对象 Map，无法直接持久化。
- **影响**：Checkpoint 恢复依赖可序列化快照，否则暂停/恢复不可靠。
- **建议**：定义受控的 `CheckpointPayload` 密封类型集合（如 `PlanningSnapshot`、`DraftingSnapshot`、`CritiqueSnapshot`），用 kotlinx.serialization 序列化为 JSON 入库；禁止向 snapshot 写入任意对象。
- **是否需要用户决定**：否。

---

# 3. Dependency Graph 与排序理由

## 3.1 依赖图

```
P0  Foundation
 │
 ├───────────────► P1  Domain Model
 │                    │
 ├───────────────► P3  AI Provider (并行于 P1/P2，仅依赖 P0)
 │                    │
 │                    ├──────────► P2  Storage
 │                    │               │
 │                    │               ▼
 │                    │           P4  Task System
 │                    │               │
 │                    │               ▼
 │                    │           P5  Novel Project + TXT Import
 │                    │               │
 │                    ▼               ▼
 │              P6  Knowledge / Memory / Timeline / Analysis (需 P3+P5)
 │                    │
 │                    ▼
 │              P7  Context / Retrieval
 │                    │
 │                    ▼
 │              P8  Tool System
 │                    │
 │                    ▼
 │              P9  Agent Runtime (需 P3+P8+P4)
 │                    │
 │                    ▼
 │              P10  Minimal Agents (6) + Orchestrator
 │                    │
 │                    ▼
 │              P11  Writing Workflow + 最小 Android UI  ★ Slice A
 │                    │
 │                    ▼
 │              P12  TXT Vertical Slice  ★ Slice B
 │                    │
 │        ┌───────────┴───────────┐
 │        ▼                       ▼
 │   P13  Full Agents      P14  Story Intelligence 激活
 │        └───────────┬───────────┘
 │                    ▼
 │              P15  Android UI 完整
 │                    │
 │                    ▼
 │              P16  PC Runtime + UI
 │                    │
 │                    ▼
 │              P17  Runtime Switching
 │                    │
 │                    ▼
 │              P18  Integration / E2E / 加固
```

## 3.2 排序理由（相对用户建议顺序的调整）

| 建议顺序 | 调整 | 理由 |
|---------|------|------|
| P8 AI Provider | **提前到 P3** | 独立于领域与存储，且是 Vertical Slice 的硬依赖；尽早消除"能否调通 LLM/结构化输出"的最大技术风险 |
| P7 Task System | **提前到 P4** | Analysis Pipeline 与 Writing Workflow 都需要可暂停/恢复/取消的长任务；Task 先于长流程实现 |
| P4 TXT Import | 提前并入 **P5** | 与 Novel Project 强耦合（导入即建项目），合并减少中间态 |
| P19 Android UI | **最小版提前到 P11** | 用户要求"Vertical Slice 阶段就提供最小 UI"，用于真机验证 Agent+Workflow+UI 闭环 |
| P18 Story Intelligence | 领域模型在 **P1** 先建；**完整 AI 推理延后到 P14** | 不因 Story Intelligence 推迟基本写作（用户要求 §13） |
| P20 PC UI / P21 Runtime Switching | **延后到 P16/P17** | PC UI 第一阶段不要求完整；Runtime 手动切换依赖双端已可运行 |
| 各 Agent（建议 P11-16） | 先"最小六 Agent"（P10）跑通链路，再"完整 Agent"（P13） | 逐个做完整 Agent 在未跑通 Workflow 前无法验证，且互相有输入输出依赖，宜先建立可运行的骨架再深化 |

## 3.3 并行窗口

- **P1 ↔ P3**：领域模型与 AI Provider 完全独立，可并行开发。
- **P2 与 P3**：可并行。
- **P5 与 P3**：TXT Engine 不依赖 AI（纯本地），但 Analysis 依赖；P5 内先做 TXT Engine，Analysis 在 P6。
- **P15 与 P16**：同用 Core，可共享测试，但分属不同 UI 工程，可并行。

---

# 4. Phase Plan（总表）

| Phase | 名称 | 核心交付 | 完成后系统能运行什么 |
|-------|------|---------|---------------------|
| P0 | Project Foundation | 多模块 Kotlin 工程 + CI + 测试基建 + Git 策略落地 | `./gradlew build` 全绿，空测试运行 |
| P1 | Core Domain Model | 全部领域模型（含 Story Intelligence 模型，纯 Kotlin，无 DB） | 领域不变量可单测 |
| P2 | Storage | SQLite Adapter + Repository + 事务 + 迁移 + 备份/恢复 + 版本化 | 全部领域对象可 CRUD/事务/迁移/备份恢复 |
| P3 | AI Provider / LLM Gateway | LLM 接口 + DeepSeek + Mock + Gateway（流式/结构化/超时/重试/预算） | 统一接口可调 DeepSeek 或 Mock，返回结构化 JSON |
| P4 | Task System | Task 生命周期 + Checkpoint + 暂停/恢复/取消/重试 + 持久化 | 长任务可跨进程恢复 |
| P5 | Novel Project + TXT Import | CreativeProject + Novel Engine 基础 + TXT Engine（切章/Chunk） | 导入 TXT → 生成带章节与 Chunk 的项目（原文只读） |
| P6 | Knowledge/Memory/Timeline/Analysis | Knowledge Lifecycle + Evidence + 4 层 Memory + 人物/事件/时间线 + 9 阶段分析 | 分析 TXT → 人物/事件/时间线知识进入待确认队列 |
| P7 | Context / Retrieval | ContextEngine + 排序 + Token Budget + Story 上下文收集 | 按预算构建上下文 Prompt |
| P8 | Tool System | ToolSpec + Registry + 权限矩阵 + 各引擎工具 | Agent 只能调用被授权工具，写操作受控 |
| P9 | Agent Runtime | 统一 Agent Runtime（契约/状态/执行上下文/Tool/LLM/重试/取消/持久化） | 任意 Agent 经同一 Runtime 运行并可恢复 |
| P10 | Minimal Agents + Orchestrator | 6 个最小 Agent + 顺序编排 | 对固定请求跑通 Intent→…→Knowledge 链路（Mock Provider） |
| P11 | Workflow + Slice A + 最小 Android UI | Writing Workflow 状态机 + HITL 门 + 8 屏最小 UI | **Android 上"写一个地下城场景"→ 看到正文（Vertical Slice A）** |
| P12 | TXT Vertical Slice | 导入流程 + 确认队列 UI + 续写链路 | **导入 TXT→知识→"继续写下一章"→ 出正文（Vertical Slice B）** |
| P13 | Full Agents | 6 个 Agent 完整能力（全 schema/工具/prompt） | 各 Agent 输出可校验，Critic 能抓注入缺陷 |
| P14 | Story Intelligence 激活 | 弧/Act/场景/Beat/弧光/冲突/伏笔/信息/节奏/情绪在 Planner 与 Critic 生效 | Critic 拦截提前回收、场景无变化、POV 泄密 |
| P15 | Android UI 完整 | 8 屏完善 + 进度 + 确认队列 + 设置 | Android 全流程可用 |
| P16 | PC Runtime + UI | PC Runtime + 最小 PC UI | PC 可导入/写作/看进度（共享 Core） |
| P17 | Runtime Switching | 手动 Runtime 选择（自动发现后续） | 用户可在设置中切换 Runtime |
| P18 | Integration / E2E / 加固 | 全量 E2E + 故障注入 + 备份/迁移演练 | 两条 Slice 在双端全绿，异常可恢复 |

> 各 Phase 的完整八要素（Goal / Modules / Dependencies / Implementation Tasks / Deliverables / Tests / Acceptance Criteria / Risks）见下方 §4.1–§4.19。

---

## 4.1 Phase 0 — Project Foundation

- **Goal**: 建立一个可编译、可测试、带 CI 的多模块 Kotlin 工程骨架，并落地 Git 策略。
- **Modules**: `settings.gradle.kts`、`gradle/libs.versions.toml`、全部 12 个模块（先空壳）、`.github/workflows/ci.yml`、`.gitignore`、`.editorconfig`。
- **Dependencies**: 无。
- **Implementation Tasks**:
  - T0.1 初始化 Gradle wrapper 与根 `settings.gradle.kts`，注册 12 个模块。
  - T0.2 建立 version catalog（Kotlin / coroutines / serialization / Koin / JUnit5 / MockK / SQLite 驱动 / Compose 待 P11/P15 引入）。
  - T0.3 每个模块加一个冒烟测试（证明模块可编译、可跑测试）。
  - T0.4 配置 CI：push 触发 `./gradlew build`。
  - T0.5 提交首个空工程并合并 `phase-0-foundation` 到 main（按 §19）。
- **Deliverables**: 可 `./gradlew build` 全绿的工程骨架 + CI 绿 + Git 分支/提交规范落地。
- **Tests**: 每模块冒烟测试。
- **Acceptance Criteria**: `./gradlew build` 通过；`./gradlew test` 运行冒烟测试；CI 在 push 后绿。
- **Risks**: JDK/AGP 版本不匹配、依赖下载网络问题。
- **完成后系统能运行什么**: 空工程可构建，尚无业务功能。

---

## 4.2 Phase 1 — Core Domain Model（Domain First）

- **Goal**: 定义全部领域模型（纯 Kotlin，无 DB、无 LLM 依赖），并明确 Entity / VO / Embedded / Projection / Persistence 分类（§6）。
- **Modules**: `:core:model`（子包：knowledge / story / agent / project / task / workflow / flow）。
- **Dependencies**: P0。
- **Implementation Tasks**:
  - T1.1 实现 §6.2 分类表中的全部模型（KnowledgeEntry、Evidence、CharacterState/Arc/ArcProgress、Event、Timeline、StoryArc、Act、ChapterPlan、ScenePlan、Beat、StoryConflict、Stakes、Foreshadowing、Payoff、InformationState、PacingProfile、EmotionalArc、AgentContract、AgentState、ToolSpec、Task、Checkpoint、CreativeProject、BackupPackage、UserWritingRequest、PlanningResult、Draft、CritiqueIssue 等）。
  - T1.2 实现强类型 ID（`ChapterId`、`KnowledgeId`…）。
  - T1.3 实现 §6.3 关键不变量校验函数（ScenePlan entry≠exit、FactLevel 升级规则、Foreshadowing/Conflict 状态迁移、Checkpoint 可序列化、Payoff 引用完整性）。
  - T1.4 为所有模型加 kotlinx.serialization 标注（枚举用字符串编码）。
- **Deliverables**: 完整领域模型 + 不变量单测。
- **Tests**: 每个不变量一个 Unit Test；序列化 round-trip 测试。
- **Acceptance Criteria**: `:core:model` 无外部依赖编译通过；不变量测试全绿；模型可序列化/反序列化。
- **Risks**: 模型规模大导致一次性改动过多 → 按子包分 4–6 个提交分批完成。
- **完成后系统能运行什么**: 领域模型可创建/校验/序列化，尚无持久化与 UI。

---

## 4.3 Phase 2 — Storage

- **Goal**: SQLite 本地持久化：CRUD、事务、迁移、备份/恢复、版本化，双端可切换 Adapter。
- **Modules**: `:storage`（adapter / repository / migration / backup / fts）。
- **Dependencies**: P1。
- **Implementation Tasks**:
  - T2.1 定义 `StorageAdapter` 接口与 `Repository` 接口（每个 Store 一个，见 §7.2）。
  - T2.2 实现 SQLite 实现（选型见 TBD-4，建议 SQLDelight 单 SQL）。
  - T2.3 实现事务包装（单事务提交"一次工作流产物"，失败回滚）。
  - T2.4 实现 `MigrationEngine` + `MigrationRegistry`，schema v1 建表 + 一条 v1→v2 示例迁移。
  - T2.5 实现 `BackupService`（BackupPackage 导出/整包+选择性恢复/版本校验）。
  - T2.6 实现 FTS5 索引（Chunk / Draft / Knowledge.content）。
  - T2.7 实现 `AndroidStorageAdapter` 与 `DesktopStorageAdapter`（同一 Repository 接口，双实现或 SQLDelight 统一实现）。
- **Deliverables**: 全部 Store 可持久化 + 迁移/备份恢复可运行。
- **Tests**: 每 Store Persistence Test；事务回滚 Test；迁移 v1→v2 Test；备份→恢复 round-trip Test；FTS 检索 Test。
- **Acceptance Criteria**: CRUD 后重启数据仍在；事务失败回滚；迁移路径测试通过；备份恢复数据一致；Android/PC 两 Adapter 均通过同一 Repository 测试。
- **Risks**: 双端 SQL 方言差异（FTS5、类型）；迁移与备份相互触发顺序。
- **完成后系统能运行什么**: 数据可持久化/迁移/备份恢复，无 AI 与 UI。

---

## 4.4 Phase 3 — AI Provider / LLM Gateway（与 P1/P2 并行）

- **Goal**: 统一 LLM 接口 + Gateway + DeepSeek/Mock 适配器，支持流式/结构化/超时/重试/Token 预算/错误处理。
- **Modules**: `:provider`（api / deepseek / mimo / mock / gateway）。
- **Dependencies**: P0（独立于领域与存储）。
- **Implementation Tasks**:
  - T3.1 定义 `LLMProvider` / `ChatRequest` / `StructuredResult` / `ProviderError` 等接口（§9.1）。
  - T3.2 实现 `LLMGateway`（Provider 选择/回退、Retry 退避、超时、Token 预算、错误归一化）。
  - T3.3 实现 `DeepSeekAdapter`（OpenAI 兼容：流式 + JSON mode）。
  - T3.4 实现 `MiMoAdapter`（接口占位，TBD-1）。
  - T3.5 实现 `MockProvider`（确定性、可注入 schema 响应）。
- **Deliverables**: 统一 LLM 接口 + Gateway + 3 个 Adapter。
- **Tests**: Provider Contract Test（Mock 全绿；DeepSeek 用注入 client 模拟）；Retry/Timeout Test；结构化解析失败→Retry 路径 Test（对应 ISSUE-3）。
- **Acceptance Criteria**: 经 Gateway 可调 Mock 返回确定性结构化 JSON；超时/限流/鉴权错误被分类并可重试；Token 预算生效；Agent 代码不含 Provider 实现。
- **Risks**: DeepSeek 真实行为与文档差异（用契约测试隔离）；MiMo 缺失（占位）。
- **完成后系统能运行什么**: 有可用的 LLM 通道（Mock），可被任何上层调用。

---

## 4.5 Phase 4 — Task System

- **Goal**: Task 生命周期 + Checkpoint + 暂停/恢复/取消/重试 + 持久化 + 修订计数。
- **Modules**: `:core:engine:task`。
- **Dependencies**: P2（持久化）。
- **Implementation Tasks**:
  - T4.1 实现 `TaskManager`（状态机 PENDING/RUNNING/PAUSED/CANCELLED/COMPLETED/FAILED）。
  - T4.2 实现 `CheckpointStore`（受控 `CheckpointPayload` 序列化，ISSUE-5）。
  - T4.3 实现恢复逻辑（从 Checkpoint 阶段继续）与取消逻辑（协程 cancel）。
  - T4.4 实现 revisionCount 上限（3）。
- **Deliverables**: Task 全生命周期可运行。
- **Tests**: 生命周期转换 Test；Checkpoint 持久化+恢复 Test；取消 Test；revision 上限 Test。
- **Acceptance Criteria**: Task 跨进程重启可恢复；取消保留产物；revision>3 阻断。
- **Risks**: 快照序列化边界失控（用密封类型约束）。
- **完成后系统能运行什么**: 长任务可暂停/恢复/取消。

---

## 4.6 Phase 5 — Novel Project + TXT Import

- **Goal**: CreativeProject 生命周期 + Novel Engine 基础 + TXT Engine（解析/切章/Chunk）+ 原文只读约束。
- **Modules**: `:core:engine:novel`、`:core:engine:txt`。
- **Dependencies**: P1、P2。
- **Implementation Tasks**:
  - T5.1 实现 `NovelEngine`（项目创建/打开/关闭 + 基础 Story 实例管理，见架构 §15）。
  - T5.2 实现 `TXTEngine`（读取/切章规则/段落归一/Chunk 生成，§16、§8.2）。
  - T5.3 实现导入流程：TXT → 项目 + 章节 + Chunk 写入 OriginalMemory（只读）。
  - T5.4 Repository 层对 OriginalMemory 仅暴露读接口（测试强制）。
- **Deliverables**: 可从 TXT 建立带章节/Chunk 的只读项目。
- **Tests**: 切章规则 Unit Test（中/英/自定义正则）；Chunk 生成 Test；项目 round-trip；只读约束 Test（写 OriginalMemory 应被拒绝）。
- **Acceptance Criteria**: 导入 TXT → 项目生成且章节/Chunk 持久化；OriginalMemory 不可被写。
- **Risks**: 切章规则误切（提供用户修正 UI 兜底）。
- **完成后系统能运行什么**: 导入 TXT 建立项目（无分析、无 AI）。

---

## 4.7 Phase 6 — Knowledge / Memory / Timeline / Analysis

- **Goal**: Knowledge Lifecycle + Evidence + 4 层 Memory + 人物/事件/时间线 + 9 阶段分析 Pipeline（基础版）。
- **Modules**: `:core:engine:knowledge`、`:core:engine:analysis`。
- **Dependencies**: P3、P5。
- **Implementation Tasks**:
  - T6.1 实现 `KnowledgeLifecycle`（Validator → FactLevel → Evidence → 冲突 → 写 → 确认，§3）。
  - T6.2 实现 `MemoryService`（四层：Current / Writing / Long-term / Original，§5）。
  - T6.3 实现 `CharacterState` 快照写入与查询（§6.4 状态快照查询）。
  - T6.4 实现 `Event` / `Timeline` 构建与时间冲突检测（§7/§8）。
  - T6.5 实现 `AnalysisPipeline`（9 阶段，按章节粒度 Task，产物进待确认队列，§17）。
- **Deliverables**: 分析 TXT → 结构化知识（待确认队列）；Lifecycle 全流程。
- **Tests**: Lifecycle 状态转换 Test（含 §2.3 升级规则）；快照查询 Test；时间冲突 Test；Pipeline 用 MockProvider 的 Integration Test。
- **Acceptance Criteria**: 小 TXT 分析后产出人物/事件/时间线知识并进入确认队列；用户确认后升级 USER_CONFIRMED；推断不自动升 EXPLICIT。
- **Risks**: 分析输出不稳定（走 Validator+Retry）；阶段耗时（按章节 Task 可暂停）。
- **完成后系统能运行什么**: TXT → 可确认的结构化知识。

---

## 4.8 Phase 7 — Context / Retrieval

- **Goal**: ContextEngine + 排序 + Token Budget + Story Intelligence 上下文按需收集。
- **Modules**: `:core:engine:context`。
- **Dependencies**: P6、P2。
- **Implementation Tasks**:
  - T7.1 实现候选收集（当前章节前文/人物状态/时间线/世界规则/近期事件/历史/Style/Memory）。
  - T7.2 实现评分（score = relevance×authority×priority + recency_bonus，§11.1）。
  - T7.3 实现 Token Budget 分配（默认 ~6000，固定+动态分区，可配置）。
  - T7.4 实现 Story Intelligence 候选收集（当前 Arc/Act/ChapterPlan/冲突/弧光进度/开放伏笔/预期回收/信息状态/节奏/情绪，按相关性选择，不全部塞入）。
  - T7.5 实现 FTS 检索接入（Context.search）。
- **Deliverables**: 可构建受预算约束、相关性排序的上下文。
- **Tests**: 排序顺序 Test；预算截断 Test；Story 上下文选择 Test；检索 Test。
- **Acceptance Criteria**: 给定输入返回按 score 排序且不超预算的上下文；Story 上下文按需选择。
- **Risks**: 预算与真实 token 不一致（以估算为准，留余量）。
- **完成后系统能运行什么**: 可生成受控上下文供 Agent 使用。

---

## 4.9 Phase 8 — Tool System

- **Goal**: ToolSpec + Registry + 权限矩阵 + Context/Knowledge/Story/Writing/Validation 工具。
- **Modules**: `:agent:tool`。
- **Dependencies**: P6、P7、P2。
- **Implementation Tasks**:
  - T8.1 实现 `ToolSpec` / `ToolPermission`（§10.1、§22.1）。
  - T8.2 实现 `ToolRegistry`（按 (agentId, toolId) 鉴权，越权返回 TOOL_PERMISSION_DENIED）。
  - T8.3 实现 §10.2 全部工具（封装对应 Engine）。
  - T8.4 落实 §22.3 权限矩阵（写工具默认 requireConfirmation）。
- **Deliverables**: 完整工具层 + 权限强制。
- **Tests**: 每工具 Contract Test（input/output schema）；权限矩阵逐格 Test；越权拒绝 Test（Agent 不可直写存储）。
- **Acceptance Criteria**: Agent 只能调用被授权工具；写操作受 requireConfirmation 控制；无直连 Database 路径。
- **Risks**: 权限遗漏（以矩阵测试兜底）。
- **完成后系统能运行什么**: 工具可被任何 Agent 经鉴权调用。

---

## 4.10 Phase 9 — Agent Runtime

- **Goal**: 统一 Agent Runtime（契约/状态/执行上下文/Tool/LLM/结构化/重试/取消/持久化）。
- **Modules**: `:agent:runtime`。
- **Dependencies**: P3、P8、P4。
- **Implementation Tasks**:
  - T9.1 实现 `AgentContext`（ExecutionId/TaskId/TokenBudget/快照）与 `AgentRunResult`。
  - T9.2 实现执行循环（LLM → SchemaValidator → Tool 调用 → 状态更新，§11.1）。
  - T9.3 实现 AgentState 管理（§20.2 枚举）与持久化（接 P4 Checkpoint）。
  - T9.4 实现取消（协程 + Provider.cancel）与重试（≤3）。
- **Deliverables**: 任意 Agent 可经统一 Runtime 运行。
- **Tests**: Runtime 循环 Test（Mock LLM/Tool）；结构化失败→Retry→UNCERTAIN Test；取消 Test；状态持久化+恢复 Test。
- **Acceptance Criteria**: 同一 Runtime 跑通任意 Agent；进程重启恢复；取消生效。
- **Risks**: 状态与 Checkpoint 一致性（快照原子性）。
- **完成后系统能运行什么**: Agent 框架就绪，可承载具体 Agent。

---

## 4.11 Phase 10 — Minimal Agents (6) + Orchestrator

- **Goal**: 6 个最小 Agent（能跑通链路）+ 顺序编排（Mock Provider 下全链路）。
- **Modules**: `:agent:agents`、`:agent:orchestration`。
- **Dependencies**: P9。
- **Implementation Tasks**:
  - T10.1 实现最小 IntentAgent（解析 intentType + planningScope，§21.1）。
  - T10.2 实现最小 ResearchAgent（决定查什么，调 context 工具）。
  - T10.3 实现最小 StoryPlannerAgent（产出 ChapterPlan + 1 个 ScenePlan + Beats）。
  - T10.4 实现最小 WritingAgent（按 Beats 写 Draft）。
  - T10.5 实现最小 CriticAgent（结构：entry≠exit；一致性基础检查）。
  - T10.6 实现最小 KnowledgeAgent（propose/confirm 走 Lifecycle）。
  - T10.7 实现 `AgentOrchestrator` 顺序编排（含 HITL 钩子占位）。
- **Deliverables**: 6 Agent + Orchestrator 可运行。
- **Tests**: 每 Agent Contract Test（Mock LLM/Tool）；Orchestrator 顺序 Test。
- **Acceptance Criteria**: 对固定请求，Mock Provider 下跑通 Intent→…→Knowledge 全链。
- **Risks**: Agent 间 DTO 契约不一致（以 schema 测试锁定）。
- **完成后系统能运行什么**: 全链路骨架可跑（Mock），可接真实 Provider。

---

## 4.12 Phase 11 — Writing Workflow + 最小 Android UI ★ Vertical Slice A

- **Goal**: Writing Workflow 状态机（暂停/恢复/取消/重试/失败恢复）+ HITL 门 + 最小 Android 8 屏；Android 上"写一个地下城场景"出正文。
- **Modules**: `:agent:orchestration`（WritingWorkflow / WorkflowStateMachine / HumanInTheLoop）、`:app:android`（8 屏最小 UI）。
- **Dependencies**: P10、P3、P4。
- **Implementation Tasks**:
  - T11.1 实现 `WritingWorkflow` 状态机（以冻结 §23.2 为基准，ISSUE-1）。
  - T11.2 实现暂停/恢复/取消/失败恢复（接 Task+Checkpoint）。
  - T11.3 实现最小 HITL（冲突对比卡片 + 确认）。
  - T11.4 Android 最小 UI：Home / Create Novel / Import TXT / Novel / Chapter / Writing / AI Task Progress / Settings(API Key)（§15.2，先做除 Import 外的 7 屏，Import 在 P12）。
  - T11.5 接入 AndroidRuntime + 真 Provider（DeepSeek）+ Task 进度绑定。
- **Deliverables**: Slice A 在 Android 上端到端跑通。
- **Tests**: Workflow Test（状态转换/暂停/恢复/取消/失败恢复）；E2E 用 MockProvider 跑 Slice A；Android 冒烟（真机/模拟器）。
- **Acceptance Criteria**: Android 输入"写一个主角进入地下城的场景"→ 显示 Task 进度 → 最终 Chapter 视图展示正文；取消/恢复可用；API Key 可在 Settings 配置。
- **Risks**: 真 Provider 输出不稳定（Validator 兜底）；UI 与 Task 状态不同步。
- **完成后系统能运行什么**: **Slice A 全链路可用**——最基本的 AI 写作闭环。

---

## 4.13 Phase 12 — TXT Vertical Slice ★ Slice B

- **Goal**: TXT → 切章 → Chunk → 基础分析 → 人物/事件/时间线 → 知识 → 项目 → "继续写下一章" 出正文。
- **Modules**: 复用 P5/P6/P11；`:app:android` 增加 Import TXT 屏 + 待确认队列最小 UI。
- **Dependencies**: P11。
- **Implementation Tasks**:
  - T12.1 实现 Import TXT 屏（选文件 → 切章预览 → 启动分析 Task）。
  - T12.2 实现待确认队列最小 UI（批量确认 → USER_CONFIRMED）。
  - T12.3 实现"继续写下一章"入口（复用 WritingWorkflow，携带既有 Knowledge 上下文）。
- **Deliverables**: Slice B 端到端跑通。
- **Tests**: E2E（MockProvider）：TXT→知识→续写全链；确认后知识进入上下文（Integration Test）。
- **Acceptance Criteria**: 导入样例 TXT → 切章正确 → 分析产出知识 → 用户确认 → "继续写下一章"产出的正文与既有知识一致（Critic 无 KNOWLEDGE_CONSISTENCY 错误）。
- **Risks**: 分析质量影响续写一致性（以确认后的知识为准）。
- **完成后系统能运行什么**: **两条 Vertical Slice 均可运行**——核心价值已验证。

---

## 4.14 Phase 13 — Full Agents

- **Goal**: 6 个 Agent 完整能力（全 schema/工具/prompt），Critic 抓注入缺陷。
- **Modules**: `:agent:agents`。
- **Dependencies**: P12。
- **Implementation Tasks**: 按 §12 表格逐 Agent 补全（Research 完整检索决策、Planner 完整规划产物、Writer beatAlignment、Critic 完整分类、Knowledge 完整冲突升级）。
- **Deliverables**: 完整 Agent 集。
- **Tests**: 每 Agent 行为 Test（记录好的 fixture）；Critic 缺陷样本 Test（注入一致性/结构/伏笔缺陷）。
- **Acceptance Criteria**: 每 Agent 输出过 SchemaValidator；Critic 能抓出全部注入缺陷样本。
- **Risks**: Prompt 过拟合（用多样化 fixture）。
- **完成后系统能运行什么**: Agent 质量达到完整版要求。

---

## 4.15 Phase 14 — Story Intelligence 激活

- **Goal**: 弧/Act/场景/Beat/弧光/冲突/伏笔/信息/节奏/情绪在 Planner 与 Critic 生效（§14.1）。
- **Modules**: `:core:engine`（story 操作）、`:agent:agents`（Planner/Critic 集成）。
- **Dependencies**: P13。
- **Implementation Tasks**: 实现 Story 确定性操作（弧状态迁移/伏笔生命周期/信息状态变更）与 Critic 规则（Entry≠Exit、payoffWindow、POV 泄密、pacing/emotional 偏离、open conflict 触碰检查）。
- **Deliverables**: Story Intelligence 在写作闭环中生效。
- **Tests**: 每规则一个 Critic Test（提前回收被拦截、场景无变化被拦截、POV 泄密被拦截、长期未触碰冲突被提示）。
- **Acceptance Criteria**: 上述规则测试全绿；Planner 产物含 Story 字段且通过校验。
- **Risks**: 规则误报（阈值可配置、宽松起步）。
- **完成后系统能运行什么**: 基本写作功能不受 Story Intelligence 拖累，且已具备结构化检查能力。

---

## 4.16 Phase 15 — Android UI 完整

- **Goal**: 8 屏完善（确认队列批量、规划可视化、写作历史/Revision、设置补全）。
- **Modules**: `:app:android`。
- **Dependencies**: P14。
- **Tests**: UI/流程 Test（Compose UI Test + ViewModel Test）。
- **Acceptance Criteria**: Android 全流程（新建/导入/写作/确认/设置）可用；进度与错误提示清晰。
- **Risks**: UI 复杂度膨胀（控制在 8 屏内）。
- **完成后系统能运行什么**: Android 端完整产品体验。

---

## 4.17 Phase 16 — PC Runtime + UI

- **Goal**: PC Runtime（本地/文件访问/大文件/任务执行）+ 最小 PC UI，共享同一 Core 契约。
- **Modules**: `:app:desktop`、`:runtime`（DesktopRuntime）。
- **Dependencies**: P15 的 Core、P3。
- **Implementation Tasks**: DesktopRuntime（文件系统/SQLite/网络）、最小 PC UI（复用 8 屏最小实现）、导入大 TXT + 分析/写作 Task。
- **Tests**: 双端共享 Core 测试（同一 Repository/Workflow 测试在 Android 与 PC 环境各跑一遍）；PC 大文件导入 Test。
- **Acceptance Criteria**: 同一 Core 在 PC 可运行；PC 导入大 TXT、跑分析/写作 Task、看进度；文件访问正常。
- **Risks**: Compose Desktop/JavaFX 与 Android 差异（TBD-3）。
- **完成后系统能运行什么**: PC 端具备核心写作与任务能力。

---

## 4.18 Phase 17 — Runtime Switching

- **Goal**: 手动 Runtime 选择（自动发现延后）。
- **Modules**: `:runtime`（RuntimeRegistry / RuntimeSelection）、设置屏。
- **Dependencies**: P15、P16。
- **Implementation Tasks**: RuntimeRegistry、Settings 手动切换、切换驱动 Storage/文件/网络能力；BackupPackage 跨端导出导入。
- **Tests**: 切换 Test（切换后走对应 Adapter）；跨端导入导出 round-trip。
- **Acceptance Criteria**: 用户在设置中手动切换 Runtime 生效；跨端数据经备份包可迁移。
- **Risks**: 双端环境差异（用契约测试锁定）。
- **完成后系统能运行什么**: Android/PC 可手动切换并共享数据（备份包）。

---

## 4.19 Phase 18 — Integration / E2E / 加固

- **Goal**: 全量 E2E + 故障注入 + 备份/迁移演练 + 最终 DoD。
- **Modules**: `:test:e2e`、全模块加固。
- **Dependencies**: P17。
- **Implementation Tasks**: 两条 Slice 的自动化 E2E（MockProvider，CI 可跑）；故障注入（Provider 超时/限流、进程中断）；备份→迁移→恢复演练；性能冒烟。
- **Tests**: 全量 E2E；故障恢复 Test；备份/迁移 Test。
- **Acceptance Criteria**: 两条 Slice 双端全绿；中断后从 Checkpoint 恢复完成；备份→迁移→恢复数据一致；全部 DoD 条目满足（§20）。
- **Risks**: 回归（以 E2E 为防线）。
- **完成后系统能运行什么**: **稳定可交付的 MVP**——两条链路、双端、可恢复、可备份。

---

# 5. Vertical Slice 计划

## 5.1 Slice A：写作链路（P11 落地）

```
用户输入 "写一个主角进入地下城的场景"
  ↓
IntentAgent   → UserWritingRequest { intentType=CONTINUE, planningScope=SCENE, constraints=[], styleHints=[] }
  ↓
ResearchAgent → ResearchResult { requiredKnowledge, currentArc?, activeConflicts, openForeshadowing, relevantState, tokenBudget }
  ↓
StoryPlannerAgent → PlanningResult { chapterPlan, scenePlans(含 entry/exit), beats, pacing, emotionalArc }
  ↓
WritingAgent → Draft { content(按 Beat 顺序), beatAlignment, usedKnowledge }
  ↓
CriticAgent  → CritiqueIssue[]（最小集：结构/一致性/质量）
  ↓ 通过
KnowledgeAgent → KnowledgeUpdateResult（人物状态/事件/弧光进度沉淀）
  ↓
Final Draft 展示在 Android Chapter 视图
```

**验证点**：Agent 架构、Workflow、AI Provider、Knowledge、UI 五者真实联动。验收标准见 P11。

## 5.2 Slice B：TXT 链路（P12 落地）

```
上传 TXT
  ↓
TXT Engine → 章节切分 → TextChunk（写入 Original Memory，只读）
  ↓
Analysis Pipeline（9 阶段，按章节粒度 Task，可暂停恢复）
  ↓ 人物抽取 → 事件抽取 → 时间线构建 → 世界观抽取 → 知识整合
Character / Event / Timeline → 待确认队列
  ↓ 用户确认
Novel Project（Knowledge 就绪）
  ↓ 用户输入 "继续写下一章"
Intent → Research → Planner → Writer → Critic → Knowledge Update → 出正文
```

**验证点**：TXT 导入链路 + 续写链路衔接；确认后的知识真正进入后续写作上下文。

## 5.3 两条 Slice 的共同前置

| 能力 | 依赖 Phase |
|------|-----------|
| 领域模型 | P1 |
| 持久化 | P2 |
| LLM | P3 |
| 长任务 | P4 |
| 上下文 | P7 |
| 工具 | P8 |
| Agent Runtime | P9 |
| 最小六 Agent | P10 |
| 最小 Android UI | P11（Slice A）|

---

# 6. Domain 实现计划（Domain First）

## 6.1 分类原则（先分类，后建表）

| 类别 | 含义 | 处理方式 |
|------|------|---------|
| **Domain Entity** | 有独立标识、可被引用、有生命周期的对象 | 独立表 + Repository + 版本化 |
| **Value Object** | 无标识、由值相等判定的对象 | 不单独建表，内嵌或投影 |
| **Embedded Object** | 从属于父实体、随父存活的子对象 | 作为父表的一列/JSON，或子表级联 |
| **Projection** | 为查询/展示派生的只读形态 | 不建表，由查询组装 |
| **Persistence Model** | 表结构对应的存储形态 | 与 Domain 分离，由 Repository 映射 |

> **理由**：避免"按对象建表"导致的过度表设计；Domain 与 Persistence 分离使 SQLite 演进（迁移）不影响上层模型。

## 6.2 分类表（基于 V4.1 第 2–10 / 18 / 20 / 26–28 章）

| 模型 | 类别 | 说明 |
|------|------|------|
| Novel / CreativeProject / ProjectManifest | Entity | 项目标识与清单 |
| KnowledgeEntry | Entity | 唯一标识、版本化、状态机 |
| Evidence / KnowledgeSource / SourceReference | Value Object | 从属 KnowledgeEntry |
| PendingConfirmation | Entity | 待确认队列条目 |
| Character | Entity | 人物（含档案） |
| CharacterState | Entity | 快照，按章/事件打点 |
| CharacterArc / CharacterArcProgress | Entity | 弧光轨迹与每章进度 |
| Event / TimelineEntry / TimelinePosition | Entity / Value Object | 事件与时间线 |
| StoryArc / Act / ChapterPlan / ScenePlan / Beat | Entity | Story Hierarchy |
| StoryConflict / Stakes | Entity / Embedded | Stakes 作为 Embedded 挂在 Conflict 与 ScenePlan |
| Foreshadowing / Payoff | Entity | 伏笔生命周期与回收 |
| InformationState | Value Object | 挂接 Knowledge 的可见状态 |
| PacingProfile / EmotionalArc | Value Object / Embedded | 节奏与情绪标注 |
| AgentContract / AgentState / ToolPermission | Entity / Value Object | Agent 与工具契约 |
| Task / Checkpoint | Entity | 任务生命周期 |
| UserWritingRequest / PlanningResult / Draft / CritiqueIssue | Value Object（流程 DTO） | Agent 输入输出 |
| ValidationResult / KnowledgeUpdateResult | Value Object（流程 DTO） | 引擎返回 |
| BackupPackage / SchemaVersion | Entity / Value Object | 备份与版本 |

## 6.3 关键不变量（P1 单测覆盖）

- `ScenePlan.entryState != ScenePlan.desiredExitState`（无变化则 Critic 报错）。
- `FactLevel` 升级规则：INFERRED 不可自动升级为 EXPLICIT；只有用户确认升 USER_CONFIRMED（§2.3）。
- `Foreshadowing.status` 合法迁移：PLANNED → INTRODUCED → DEVELOPING → PAYOFF_READY → RESOLVED / ABANDONED。
- `StoryConflict.status` 合法迁移：OPEN → ESCALATING → RESOLVED / ABANDONED。
- `Checkpoint` 必须可序列化（见 ISSUE-5）。
- 引用完整性：`Payoff.foreshadowingId` 必填且存在。

## 6.4 序列化策略

- 全部 Domain 用 kotlinx.serialization 标注；ID 用 `@Serializable` 的强类型包装（如 `ChapterId`、`KnowledgeId`）。
- 枚举采用字符串编码（迁移友好），不使用 ordinal。

---

# 7. Storage 计划

## 7.1 存储适配器模式

```
StorageAdapter (interface)            ← 唯一入口
├── AndroidStorageAdapter   (Android 内建 SQLite / Room? 见 TBD-4)
└── DesktopStorageAdapter   (sqlite-jdbc)

Repository（Domain 面）  ──►  StorageAdapter（存储面）
```

- **同一 Domain Model，不同底层 Adapter**：Repository 接口定义在 `:core:engine`（或 `:storage` 接口层），Android/PC 各自提供实现，通过 Runtime 注入。
- 实现建议：第一版直接用 **SQLDelight**（同一套 SQL，Android + JVM 通用，FTS5 经原生 SQL）或 **Room（Android）+ sqlite-jdbc（PC）双实现**（见 TBD-4，推荐前者以减双实现成本）。

## 7.2 数据分区（对应架构 §27.2）

| Store | 内容 |
|-------|------|
| OriginalMemory | Original Chapter / TextChunk（只读） |
| KnowledgeStore | KnowledgeEntry / Evidence / Conflict / Confirmation |
| StoryStore | StoryArc / Act / ChapterPlan / ScenePlan / Beat / CharacterArc / CharacterArcProgress / StoryConflict / Foreshadowing / Payoff / InfoState |
| StateStore | CharacterState / TimelineEntry / Event |
| ProjectStore | ProjectManifest / SchemaVersion |
| TaskStore | Task / Checkpoint |
| WritingStore | Chapter / Draft / Revision |

## 7.3 必须支持的能力

- **CRUD**：每 Store 对应 Repository 接口 + 实现。
- **Transaction**：单事务提交"一次工作流产物"（Draft + Knowledge + 弧状态），失败回滚（§27.3）。
- **Migration**：`MigrationEngine`（确定性，不调 AI）：读 schemaVersion → 生成有序 `MigrationStep` 列表 → 迁移前自动 Backup → 逐 Step 执行 → 校验 → 更新版本（§28.2）。首版 schema 版本号定 v1。
- **Backup / Restore**：`BackupPackage`（含 schemaVersion + 全量 JSON）；整包/选择性恢复；恢复前版本校验（§28.1）。
- **Versioning**：`SchemaVersion` 记录于 ProjectStore；每 Schema 变更递增；旧版本项目打开自动迁移。

## 7.4 FTS

- FTS5 索引 Chunk / Draft / Knowledge.content，供 Context 检索（P7）使用。
- 迁移需包含 FTS 表的建/重建。

---

# 8. TXT Pipeline 计划

## 8.1 阶段（对应架构 §16 / §17）

```
读取(TXT Engine, 零 AI)
  → 章节切分（"第X章"/"Chapter X"/用户正则，可修正）
  → 段落归一（去空行/缩进/对话识别）
  → Chunk 生成（可检索 TextChunk，FTS 索引，Evidence 引用）
  → 写入 OriginalMemory（只读）
  → Analysis Pipeline（9 阶段，P6 实现，按章节粒度 Task）
```

## 8.2 切章规则（P5 实现要点）

- 预置规则集：中文 `第[一二三四五六七八九十百千0-9]+章`、英文 `Chapter\s+\d+`、用户自定义正则。
- 切分结果可被用户合并/拆分；用户修正记录为 USER_INPUT 来源，用于重切。

## 8.3 只读约束

- OriginalMemory 对 AI 与创作只读；任何创作产物写 CreativeProject（§26.2 不污染原文）。
- Repository 层对 OriginalMemory 暴露 `read*` 接口，无 `write*` 写路径（P5 用测试强制）。

## 8.4 Analysis Pipeline（9 阶段，P6 实现）

- Stage 1–2 预处理/切章校验（TXT Engine 输出）；Stage 3–7 抽取（人物/档案/事件/时间线/世界观）；Stage 8 知识整合（Lifecycle）；Stage 9 一致性检查（复用 §13 规则，全库批处理触发）。
- 每阶段输出走 OutputValidator + Lifecycle；推断标记 INFERRED/UNCERTAIN；产物进待确认队列。

---

# 9. AI Provider 计划

## 9.1 统一接口（架构 §1.2 Provider / §1.3 LLM Gateway）

```kotlin
interface LLMProvider {
    val id: ProviderId
    fun chat(request: ChatRequest): Flow<ChatChunk>          // 流式
    suspend fun structured(request: ChatRequest, schema: JsonSchema): StructuredResult
    fun cancel(callId: CallId)
    val capabilities: ProviderCapabilities  // 是否支持流式/JSON mode/并发
}

data class ChatRequest(
    val model: ModelId,
    val messages: List<Message>,
    val system: String?,
    val maxTokens: Int?,
    val temperature: Float?,
    val tokenBudget: TokenBudget?,   // 架构 §11.1
    val timeoutMillis: Long
)

data class StructuredResult(
    val json: JsonElement?,          // 可能为 null → 走 SchemaValidator+Retry（ISSUE-3）
    val usage: TokenUsage,
    val error: ProviderError?
)
```

## 9.2 Gateway

- `LLMGateway`：Agent 唯一入口，负责 Provider 选择/回退、Retry（退避 1s×n）、超时、Token 预算、错误归一化。
- 配置：API Key / Model / baseUrl 来自用户设置（Android Settings 屏）。

## 9.3 Provider Adapter

| Adapter | 说明 | P3 交付 |
|---------|------|---------|
| DeepSeekAdapter | OpenAI 兼容（chat/completions），流式 + JSON mode | ✅ 真实实现 |
| MiMoAdapter | 接口占位，待用户提供 endpoint/model（TBD-1） | ⚠️ 占位 |
| MockProvider | 确定性、可注入 schema 的假响应，用于测试 | ✅ 必须 |

## 9.4 必须支持（架构 §9 用户要求）

- API Key（本地存储，见 TBD-2）；Model 选择；Streaming；Structured Output（经 SchemaValidator 兜底）；Timeout；Retry；Token Budget（§11.1 的 6000 可配置）；错误处理（鉴权/限流/超时/网络错误分类）。

---

# 10. Tool System 计划

## 10.1 Tool Contract（架构 §22）

```kotlin
data class ToolSpec(
    val id: ToolId,                  // 如 knowledge:propose
    val description: String,
    val inputSchema: JsonSchema,
    val outputSchema: JsonSchema,
    val permission: ToolPermission,  // allowed / writeAccess / requireConfirmation
    val readOnly: Boolean,
    val allowedAgents: Set<AgentId>
)
```

## 10.2 工具分组（对应架构 §22.2）

| 组 | 工具 | 封装引擎 |
|----|------|---------|
| ContextTools | context:collect / context:build / context:search | ContextEngine |
| KnowledgeTools | knowledge:get / knowledge:search / knowledge:propose / knowledge:confirm / knowledge:detectConflict | NovelEngine + Lifecycle |
| StoryTools | story:plan / story:getArc / story:getChapterPlan / story:getScenePlan | NovelEngine |
| WritingTools | writing:write / writing:rewrite / writing:draft | NovelEngine |
| ValidationTools | validation:check / conflict:detect | ValidationEngine |

## 10.3 权限强制（架构 §22.3 最小集）

- `ToolRegistry` 以 `(agentId, toolId)` 查询 `ToolPermission`；**Agent 不能直接访问 Database**——所有数据访问必须经工具。
- 写工具（propose/write/plan/rewrite）默认 `requireConfirmation=true` 或由 AgentContract 显式放行。
- 运行时在执行工具前校验 permission；越权调用返回 `TOOL_PERMISSION_DENIED` 并记录（审计）。

## 10.4 测试重点

- Agent 无法绕过 Registry 直写存储（架构级约束）。
- 权限矩阵逐格测试（P8）。

---

# 11. Agent Runtime 计划

## 11.1 统一 Runtime（对应架构 §19/§20/§23）

单一 `AgentRuntime`，所有 Agent 共用，避免"每个 Agent 一套 Runtime"：

```
AgentRuntime.execute(agent, input): AgentRunResult
  1. 检查 AgentState（IDLE 才可启动）
  2. 构建 AgentContext（ExecutionId / TaskId / TokenBudget / 快照）
  3. 循环:
     a. 调 LLM（Gateway）→ 结构化输出
     b. SchemaValidator 校验 → 失败 Retry(≤3) → 仍失败标记 UNCERTAIN/FAILED
     c. Agent 决定 Tool 调用 → ToolRegistry 鉴权 → 执行 → 结果回填
     d. 更新 AgentState（RUNNING/WAITING_TOOL/WAITING_HUMAN/RETRYING/...）
  4. 结果/状态持久化到 Task Checkpoint（P4）
  5. 支持 Cancellation（协程 cancel + Provider.cancel）
```

## 11.2 能力清单（架构 §11 用户要求）

Agent Contract、Agent State（§20.2 枚举）、Execution Context、Tool Calling、LLM Calling、Structured Output、Retry（3 次）、Timeout、Cancellation、Persistence（Checkpoint）。

## 11.3 并发模型

- 协程驱动；单 Agent 串行，多 Task 并行；HITL 等待用 `WAITING_HUMAN` 挂起并持久化，进程重启后可恢复。

---

# 12. Agent 实现计划

> P10 先做"最小版"（能跑通 Slice），P13 再做"完整版"。以下为完整版契约（与架构 §21 一致）。

| Agent | 输入 | 输出（结构化） | 主要工具 | 实现要点 |
|-------|------|---------------|---------|---------|
| **IntentAgent** | 用户原始文本 + 风格 | UserWritingRequest（intentType / target / **planningScope** / constraints / styleHints） | context:getStyle, knowledge:search | 解析 Planning Scope（SCENE/CHAPTER/ARC/NOVEL，§21.1） |
| **ResearchAgent** | UserWritingRequest + Story 上下文 | ResearchResult（requiredKnowledge / currentArc / activeConflicts / openForeshadowing / relevantState / tokenBudget） | context:collect, knowledge:search | **决定查什么**，不实现检索 |
| **StoryPlannerAgent** | ResearchResult + Story 上下文 | PlanningResult（chapterPlan / scenePlans(entry/exit) / beats / arcUpdate / characterProgress / foreshadowingOps / payoffOps / infoStateChanges / pacing / emotionalArc） | knowledge:get, story:plan | 尊重 Open Conflict / payoffWindow / InfoState（§21.3） |
| **WritingAgent** | ChapterPlan + ScenePlan + Beats + 上下文 | Draft（content 按 Beat 顺序 / beatAlignment / usedKnowledge） | context:build, writing:write | 只按规划写，不重构剧情 |
| **CriticAgent** | Draft + ChapterPlan + Knowledge | CritiqueIssue[]（severity / category / location / blocking） | knowledge:verify, conflict:detect | 最小集（P10）：结构+一致性；完整集（P13）：弧光/伏笔/信息/节奏/情绪 |
| **KnowledgeAgent** | 新事实 Proposal / 规划产物 | KnowledgeUpdateResult（accepted / pendingConfirmation / conflicts / updatedStates） | knowledge:propose, knowledge:confirm | 走 Lifecycle；冲突升级（§13.3） |

- 每个 Agent = `AgentContract`（schema + allowedTools）+ 实现类 + prompt 模板 + 专属测试（Agent Contract Test）。
- **P13 验收**：每个 Agent 输出过 SchemaValidator；用记录好的 fixture 断言行为；Critic 能抓出注入的缺陷样本。

---

# 13. Workflow 计划

## 13.1 状态机（以冻结架构 §23.2 为唯一基准，见 ISSUE-1）

```
INTENT_PARSING → RESEARCH → PLANNING → PLAN_REVIEW(HITL,可选) → WRITING → CRITIQUE
  → 通过 → KNOWLEDGE_UPDATE → COMPLETED
  → 未通过 & revision<3 → REVISION → WRITING
  → 未通过 & revision≥3 → CONFLICT_HITL(HITL) → 裁决后回到 WRITING 或 CANCELLED
任意状态 → PAUSED / CANCELLED / FAILED（可恢复）
```

## 13.2 必须支持（架构 §12 用户要求）

- **Pause / Resume**：状态 + Checkpoint 持久化；恢复从 Checkpoint 阶段继续。
- **Cancel**：取消当前 Agent 调用（协程 cancel + Provider.cancel），保留已产生产物。
- **Retry**：失败重试 ≤3（§12.3 Retry 策略：注入错误 → 简化 prompt → 标记 UNCERTAIN）。
- **Failure Recovery**：Workflow 状态落库，进程重启恢复。

## 13.3 HITL（架构 §24）

- 触发：CRITICAL/MAJOR 冲突、修订超限、规划调整；AgentState=WAITING_HUMAN。
- 交互载体：冲突对比卡片（§4.3）、待确认队列（§14）、修订裁决。P11 先提供最小 HITL（冲突对比 + 确认按钮）。

---

# 14. Story Intelligence 计划

## 14.1 第一版必须实现（不阻塞基本写作）

| 项 | 实现范围 |
|----|---------|
| Story Arc / Act | 数据模型 + 状态机（PLANNED/ACTIVE/COMPLETED/ABANDONED）+ 弧级状态迁移（确定性） |
| ChapterPlan / ScenePlan / Beat | 数据结构 + Planner 输出 + Writer 按 Beat 写 + Critic 检查 Beat 完成度（**必须**） |
| Scene Entry/Exit State | Critic 强制"Entry≠Exit"，无变化报错（**必须**） |
| CharacterArc / CharacterArcProgress | 数据模型 + 每章记录 recentChange/reachedTurningPoint（数据层面**必须**，自动推导可后续） |
| Conflict / Stakes | StoryConflict 状态机 + Planner"每次规划至少触碰一个 Open Conflict"检查（**必须**） |
| Foreshadowing / Payoff | 生命周期状态机 + Critic 时机检查（payoffWindow 内才可 RECALL；用户约束禁止则拦截）（**必须**） |
| Information Control | InformationState 挂接 + POV 检查（POV 角色不应知道其 unknownFacts）（**必须**） |
| Pacing / EmotionalArc | 作为标注字段 + Critic 基础偏离检查（第一版宽松） |

## 14.2 先建 Domain Model，暂不做完整 AI 推理

- ARC/NOVEL 级自动整体规划（第一版可由用户选择范围，Planner 产出基础结构；不要求自动生成完整卷结构）。
- Stakes 全自动推导、复杂情绪弧自动建模、跨章伏笔窗口自动推荐（后续迭代，见 Future）。

## 14.3 引擎与 Agent 集成

- StoryStore 由 `:core:engine` 的 Story 操作承载（弧状态迁移、伏笔生命周期、信息状态变更），Planner/Critic 经 StoryTools 调用。
- P14 完成集成并补充针对 Critic 规则的测试（拦截提前回收、场景无变化、POV 泄密等）。

---

# 15. Android 计划

## 15.1 工程

- `:app:android`：Kotlin + Jetpack Compose + ViewModel + Coroutines + Koin（DI）+ Room/SQLDelight（见 TBD-4）。
- 依赖 `:core:*` / `:agent:*` / `:provider` / `:storage` / `:runtime`（注入 AndroidRuntime）。

## 15.2 最小 UI（P11，Vertical Slice 阶段就要）

1. **Home**（新建小说 / 导入 TXT / 最近项目）
2. **Create Novel**（Idea 入口：题材/POV/简介/风格）
3. **Import TXT**（选文件 → 切章预览）
4. **Novel**（项目详情 / 章节列表 / 续写入口）
5. **Chapter**（正文阅读 / 当前规划展示）
6. **Writing**（输入指令 / 展示生成过程）
7. **AI Task Progress**（Task 状态 / 进度 / 暂停 / 取消 / 冲突确认）
8. **Settings / API Key**（Provider 选择、Key、Model、Token 预算）

> 第一版就做这 8 屏，不做几十个页面。

## 15.3 P15 完整 UI

- 确认队列批量处理、规划可视化（弧/章节）、写作历史与 Revision 记录、设置补全。

---

# 16. PC 计划

## 16.1 工程与 Runtime

- `:app:desktop`：JVM Desktop（Compose Desktop 或 JavaFX，见 TBD-3）；`DesktopRuntime` 提供文件系统访问（大文件）、本地网络、SQLite。
- **与 Android 完全共享**：Domain Model、Agent Contract、Workflow、Tool Contract、Provider Interface、Storage Repository 接口（仅底层 Adapter 不同）。
- **PC 第一版重点**：Local Runtime、File Access、Larger Processing Capacity、AI Task Execution；UI 不要求完整（可复用 8 屏的最小实现）。

## 16.2 验收

- 同一份 Core 在 Android 与 PC 均能运行；PC 能导入大 TXT、跑分析/写作 Task、看进度。

---

# 17. Runtime Switching 计划

## 17.1 第一阶段：手动选择（P17）

- `RuntimeRegistry`：注册 AndroidRuntime / DesktopRuntime；`RuntimeSelection` 由用户在 Settings 手动切换；切换后驱动 Storage Adapter 与文件/网络能力。
- 数据跨端通过 BackupPackage 导出/导入（非实时同步）。

## 17.2 第二阶段：自动发现（Future）

- "PC Runtime Running → Android 检测 → 选择是否切换"；第一版不做复杂自动发现（架构 §29 / 用户 §16 确认）。

---

# 18. Testing 计划

## 18.1 每 Phase 至少覆盖（对应架构 §17 用户要求）

| 测试类型 | 覆盖 | 起始 Phase |
|---------|------|-----------|
| Unit Test | 领域不变量、引擎纯逻辑 | P1 |
| Persistence Test | CRUD/事务/迁移/备份恢复 | P2 |
| Provider Test | Mock + DeepSeek 契约、超时/重试 | P3 |
| Workflow Test | 状态机转换、暂停/恢复/取消/失败 | P11 |
| Agent Contract Test | 每个 Agent 输入/输出 schema | P10–P13 |
| Tool Permission Test | 权限矩阵、越权拒绝 | P8 |
| Integration Test | 引擎+存储+上下文联动 | P6–P8 |
| **End-to-End Test** | TXT→…→Knowledge→续写；写场景全链路（§5） | P12 / P18 |

## 18.2 E2E 基建

- `:test:e2e` 模块：用 MockProvider 跑两条 Slice 的自动化 E2E（CI 可跑）；真 Provider E2E 仅在本地手动（需 Key）。

---

# 19. Git 策略

> 面向 Git 新手，保持简单。

## 19.1 分支模型

```
main                          ← 唯一长期分支，始终可发布
  └── phase-N-<slug>          ← 每个 Phase 一个 feature branch
        └── task-<n>-<slug>   ← （可选）Phase 内大任务再开子分支
```

## 19.2 命名

- Branch：`phase-0-foundation`、`phase-1-domain-model`、`phase-11-slice-a` …
- Commit：`类型(范围): 描述`，如 `feat(domain): add KnowledgeEntry model`、`test(storage): add migration v1->v2 test`、`fix(provider): retry backoff`。
  - 类型：`feat` / `fix` / `test` / `docs` / `refactor` / `chore`。

## 19.3 合并方法（简单）

1. 每完成一个任务（编译+测试通过）就 commit 到当前 feature branch。
2. Phase 完成、验收通过后：`git checkout main && git merge --squash phase-N-<slug> && git commit`（**Squash**，保持 main 历史干净，一条 Phase 一个 commit）。
3. 合并前确认 `./gradlew build` 绿；绝不直接往 main 提交业务代码。

## 19.4 回滚（简单）

- 出错先 `git status` 看状态；未提交就改回：`git checkout -- <file>`。
- 已提交但未合并：`git reset --hard HEAD~1`（**仅在本机 feature branch，勿在 main 用**）。
- 已合并到 main 且推了远端：用 `git revert <commit>`（生成反向 commit，安全）。
- 推错远端：不要强推；告知用户并协助处理。

## 19.5 约定

- 每个 feature branch 用完即删（`git branch -d phase-N-<slug>`）。
- 大文件（模型、TXT 样例）不入库；`.gitignore` 排除 build/、local.properties、.env。

---

# 20. Definition of Done

## 20.1 全局 DoD（每个任务）

1. 编译通过（`./gradlew build`）。
2. 该任务相关测试通过（至少 Unit + 对应类型测试）。
3. 架构检查：未改动冻结架构、未新增 Agent、未绕过 Tool/Validator。
4. 文档同步更新（本计划对应 Phase 勾选、必要时补设计说明）。
5. Commit（见 §19 命名）。

## 20.2 Phase DoD（示例，各 Phase 详见其验收标准）

| 判断 | 具体标准 |
|------|---------|
| 数据可持久化 | CRUD 后进程重启数据仍在；事务回滚生效 |
| API 可调用 | 各模块对外接口有测试调用且通过 |
| Android 可运行 | 最小 UI 真机/模拟器可完成 Slice A |
| Workflow 可恢复 | 模拟进程中断后从 Checkpoint 恢复完成 |
| 错误可处理 | 超时/限流/鉴权错误被归一化并可展示/重试 |

> 禁止使用"代码基本完成"这类模糊表述作为完成依据。

---

# 21. Risks

| 风险 | 影响 | 缓解 |
|------|------|------|
| LLM 结构化输出不可靠 | Agent 输出解析失败 | SchemaValidator 为真相 + Retry + UNCERTAIN（ISSUE-3）；MockProvider 先行 |
| MiMo 接入信息缺失 | P3 MiMo 不可测 | 占位 + TBD-1 待用户提供 |
| 双端存储实现成本 | Repository 双实现复杂 | 优先 SQLDelight 单 SQL 方案（TBD-4） |
| Story Intelligence 复杂度过高 | 拖延基本写作 | 模型先行、AI 推理延后（P14）；P11 前不依赖 |
| 长任务/断点恢复不稳定 | 用户中断丢失进度 | P4 Task+Checkpoint 前置；E2E 覆盖恢复 |
| 用户确认积压 | 知识可信度停滞 | 待确认队列分组/批量（P15） |
| 项目规模大 | 一次改动过多 | Small Incremental + 每任务编译测试 + Git 分支隔离 |

---

# 22. TBD（待用户决定）

| # | 项 | 需要什么 |
|---|----|---------|
| TBD-1 | **MiMo** endpoint / model / 鉴权 / 是否 OpenAI 兼容 | 用户提供接入信息 |
| TBD-2 | API Key 存储方式 | 建议 Android Keystore / PC 系统凭据；确认无云端 |
| TBD-3 | PC UI 框架 | Compose Desktop 或 JavaFX（建议 Compose Desktop，与 Android 共享 Compose 技能） |
| TBD-4 | 双端存储实现 | 建议 SQLDelight 单 SQL；或 Room(Android)+sqlite-jdbc(PC) 双实现——需用户确认倾向 |
| TBD-5 | 是否未来需要 iOS（决定是否 KMP） | 决定 ISSUE-2 走向 |
| TBD-6 | 首版支持的语言/方言 | 中文为主？是否要英文界面 |

---

# 23. Future Work

（来自架构 §31，MVP 稳定后按需启动；不推翻既有决策）

- 更多 AI Provider 接入（经 LLM Gateway）。
- 自动剧情重构（基于 Arc/CharacterArc 结构化重构）。
- 写作风格学习（从用户修正学习 Style Profile）。
- 可视化大纲（Arc/Act/Foreshadowing 时间轴）。
- 跨项目世界设定复用。
- 协同创作（多作者/审稿人）。
- Runtime 自动发现（PC→Android 检测切换，§17.2）。
- Stakes 自动推导 / 情绪弧自动建模 / 跨章伏笔窗口推荐（§14.2）。

---

# Phase 0 → N 执行顺序（总览）

```
P0  Foundation
 → P1  Domain Model            （与 P3 并行）
 → P2  Storage
 → P3  AI Provider / Gateway   （与 P2 并行）
 → P4  Task System
 → P5  Novel Project + TXT Import
 → P6  Knowledge / Memory / Timeline / Analysis
 → P7  Context / Retrieval
 → P8  Tool System
 → P9  Agent Runtime
 → P10 Minimal Agents (6) + Orchestrator
 → P11 Writing Workflow + 最小 Android UI  ★ Slice A
 → P12 TXT Vertical Slice        ★ Slice B
 → P13 Full Agents
 → P14 Story Intelligence 激活
 → P15 Android UI 完整
 → P16 PC Runtime + UI
 → P17 Runtime Switching
 → P18 Integration / E2E / 加固
```

并行：P1∥P3；P2∥P3；P15∥P16。

---

# 下一步 Codex 应该实际做什么

**立即开始 Phase 0（Project Foundation）**，只做以下内容，不做业务逻辑：

1. 初始化 Gradle 多模块工程：`settings.gradle.kts` 注册 `:core:model`、`:core:engine`、`:agent:tool`、`:agent:runtime`、`:agent:agents`、`:agent:orchestration`、`:provider`、`:storage`、`:runtime`、`:app:android`、`:app:desktop`、`:test:e2e`（可先全部建空模块，随后逐 Phase 填充）。
2. 配置 `gradle/libs.versions.toml`（Kotlin、coroutines、serialization、Koin、JUnit5、MockK、sqlite 驱动）。
3. 建立 CI workflow（push 触发 `./gradlew build`），提交首个空工程。
4. 按 §19 建分支 `phase-0-foundation`，完成后的验收 = `./gradlew build` 绿 + 冒烟测试跑通 + 合并回 main。
5. 进入 P1 前，先就 **ISSUE-1（Workflow 状态清单）** 与 **TBD-4（存储实现选型）** 与用户确认，二者不阻塞 P0，但阻塞 P11/P2 的实现细节。

> 顺序上先做 P0，随后 P1 与 P3 并行开工；P2/P4/P5 依次跟进。每条 Vertical Slice 的验收与测试在 P11/P12 明确定义。
