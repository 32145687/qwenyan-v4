# Qianyan V4.2 Architecture Review

> **版本**: v0.1-review
> **日期**: 2026-08-16
> **性质**: 架构审查（只读，不修改任何代码/Gradle/数据库/模块）
> **图例**: [DECIDED] 已冻结的 V4.1 决策 | [RECOMMENDED] 本次建议（待你确认）| [TBD] 待定 | [FUTURE] 未来 | [CONFLICT] 冲突
> **结论**: **KEEP 主体 + 针对 2 类新增需求做少量 MODIFY（加法式）** → 最终推荐 **B：先少量架构修正，再进入 P1**。

---

## 1. Executive Summary

**结论：[KEEP]（主体）+ [MODIFY]（两个新增需求的加法式扩展），不建议 REWORK。**

V4.1 架构 + P0 落地是目前可作为继续开发**坚实基础**的：
- 分层正确（Agent 推理 / Engine 执行 / Tool 封装 / Task 生命周期 / Provider 隔离）；
- 确定性兜底完整（Validation / Knowledge Lifecycle / Conflict Detection / Checkpoint / Backup-Migration）；
- P0 的 12 模块与架构一一对应，依赖方向基本正确，构建/测试/APK 全绿。

两个新需求（**Novel Variant** 与 **Vocabulary System**）本质上是**对既有分层做加法**：
- 都落在 `core:model`（新增领域模型）+ `core:engine`（新增引擎子包）+ `agent:tool`（新增工具）+ `storage`（新增分区），**无需新增模块、无需新增 Agent**。
- 但现有 `KnowledgeEntry` 以 `novelId` 为唯一作用域、`CreativeProject` 仅支持单层派生，**无法直接表达"多 Variant + Variant 级知识 + Variant 级故事结构"**——这是 P1 前必须补齐的一处小改。
- Vocabulary 与 Novel Knowledge **职责正交**，当前 ContextType / Validator / NovelEngine 能承载，但需新增一个 ContextType 与一个确定性校验步骤。

因此推荐 **B**：先做少量、定向、加法式的架构修正（NovelVariant + Vocabulary 领域模型、ISSUE-1 状态机裁决、TBD-4 存储选型），再进入 P1。不需要推倒重构。

---

## 2. P0 Implementation vs V4.1 Architecture

### 2.1 设计中的结构 vs 实际代码结构

| V4.1 架构设计（§1.5 core/ 子包） | P0 实际模块（实现计划 §2.2） | 一致？ |
|----------------------------------|------------------------------|:---:|
| `core/model/*` | `:core:model` | ✅ |
| `core/engine/*`（validation/knowledge/context/txt/analysis/novel/task）| `:core:engine`（聚合模块，子包） | ✅ 按计划聚合 |
| `core/agent/orchestration` | `:agent:orchestration` | ✅ |
| `core/agent/agents` | `:agent:agents` | ✅ |
| `core/agent/tool` | `:agent:tool` | ✅ |
| `core/provider/` + `core/provider/gateway` | `:provider` | ✅ |
| `core/storage/` + `core/migration/` | `:storage`（migration 并入） | ✅（计划明确合并）|
| `core/runtime/` | `:runtime` | ✅ |
| `core/android/` / `core/desktop/` | `:app:android` / `:app:desktop` | ✅ |
| —— | `:agent:runtime`（新，Agent Runtime 框架载体）| ✅ 计划新增，承载 §19-20 契约 |
| —— | `:test:e2e` | ✅ 计划新增 |

### 2.2 一致的部分
- 模块职责与架构分层一一对应；聚合 `:core:engine` 避免过度拆分，符合"不追求模块数"。
- 依赖方向覆盖面正确：核心纯领域无外部依赖；`core:engine→core:model`；`provider` 只依赖领域；`storage` 只依赖领域；`app` 尚未接入 core（P0 预期）。

### 2.3 不一致 / 潜在问题
1. **`:agent:runtime` 直接依赖 `:provider`（实现模块）**：`provider` 同时装 LLM 接口 + DeepSeek/MiMo 实现。Agent Runtime 拿到的是**实现模块**，而非纯接口。虽"不绑定具体 Provider 调用"在源代码上暂未暴露，但耦合到实现模块的编译面。
   - 建议：`provider` 拆出 `:provider:api`（接口）与 `:provider:impl`（适配器）逻辑边界，Agent 只依赖 api；或至少在 `provider` 内用 `internal` 约束实现类。→ [RECOMMENDED]
2. **`:agent:orchestration` 依赖 `:storage`**：Orchestrator 负责 Task/Checkpoint 持久化，方向可接受；但需警惕 Orchestrator 不应直接从 SQL 读写业务数据（须经 Engine/Repository）。
   - 建议：明确 Orchestrator 只经 `Storage` 的 TaskStore/Checkpoint，业务数据一律走 Tool→Engine。→ [RECOMMENDED]
3. `:app:android` / `:app:desktop` 目前只依赖少量模块，尚未接入 engine/storage/provider——P0 预期行为，非问题。
4. 架构 §26.1 `CreativeProject` 的 `originalNovelId` 是**单项派生**，无法表达多 Variant（见 §10）。→ [CONFLICT，见下]

---

## 3. New Requirements Impact

### A. TXT Rewrite / Reconstruction（剧情重构/再创作）
- **现状**：架构支持 TXT 导入 → Analysis Pipeline 建 Original Novel Knowledge；`CreativeProject` 支持从已导入原文派生（DERIVED），**从想法续写流程完整**（§25 Writing Workflow）。
- **缺口**：只有一个 Creative Project 派生层，且 `NovelId` 是唯一知识作用域。需求要求"保留人物和世界观、重写部分剧情"生成 **多个 Variant（A/B/C）**，且不破坏原文、每个 Variant 有自己的故事结构（重规划 Arc→Act→Chapter→Scene）与自己的覆写知识。
- **结论**：需要引入 **NovelVariant** 抽象（见 §10）。流程"Original → 提取可保留 → 建立新 Blueprint → 重规划 → 写作 → Critic → Revision → New Variant"可通过**复用现有 Writing Workflow 的 Planning→Writing→Critique→Revision** 实现，无需新 Agent，只需在请求入口增加"重构范围（保留/重写哪些实体）"。→ 影响域模型，需 P1 前补。

### B. Vocabulary System（词库系统）
- **职责正交**：Knowledge 回答"小说发生了什么"，Vocabulary 回答"该用什么词/称谓/术语/表达"。两者并存，可互相引用（术语词条可关联到 Knowledge 实体），**不是 Knowledge 的子集**。
- **承载可行性**（逐模块）：
  - `:core:model`：新增 `Vocabulary / VocabularyEntry / VocabularyRule / VocabularyCandidate`（纯领域）✅
  - `:core:engine`：新增 `VocabularyEngine`（确定性：层级解析、候选审核、替换规则、Validator）✅（core:engine 本为聚合子包，不加模块）
  - `:agent:tool`：新增 VocabularyTools（pre-inject / validate / auto-fix），经 Tool 给 Planner/Writer/Critic ✅
  - `:agent:orchestration`：在 WRITING/CRITIQUE 阶段接入"词库校验"为一个校验步骤，不是新 Workflow 状态 ✅
  - `:storage`：新增 Vocabulary 分区；`VocabularyCandidate` 走待确认队列 ✅
  - `:provider`：不受影响（替换规则确定性执行，不调 LLM）✅
  - `context`：新增 `ContextType.VOCABULARY`（分 Global/Novel/Variant/Task 作用域注入）✅
- **不需要新 Agent**：候选审核走 User Confirmation + 确定性规则；创作前注入走 Context；创作后校验走 Validator + Critic；AI 辅助生成 Vocabulary 可由现有 KnowledgeAgent 或一个普通 Engine 操作承载，不由独立 Agent 承担。
- **结论**：Vocabulary 完全可被现有分层承载，属加法式扩展。→ [RECOMMENDED]

### C. 词库与重构模式结合
- 流程：Original → Original Knowledge → New Variant（Variant 级 Vocabulary）→ New Blueprint → Planning → Writing → Critic → Vocabulary Validation → New Novel。
- **当前架构是否自然**：**基本自然**，只要满足两个前提：
  1. Vocabulary 有 **Variant 级作用域**（§9 层级）→ 则"重构 + 换词库"自然成立；
  2. Validation 提供**确定性词库检查/自动替换**阶段（创作后）。
- 若这两点补齐，该复合流程只是"Variant 派生 + 标准写作工作流 + 词库校验"的组合，无需特殊状态机。→ [RECOMMENDED]

---

## 4. Domain Model Review

分类口径：Domain Entity / Value Object / Embedded / Projection / Persistence，判断归属（详见 §11 数据关系）。

### KEEP（现有，保持不变）
- `KnowledgeEntry / Evidence / KnowledgeSource / Conflict / Confirmation`（§2-4）——设计良好。
- `Memory`（四层，§5）。
- `Character / CharacterState / CharacterArc / CharacterArcProgress`（§6）——注意"CharacterArc 是轨迹、CharacterState 是瞬时快照"的区分是好的，保持。
- `Event / Timeline`（§7-8）。
- `StoryArc / Act / ChapterPlan / ScenePlan / Beat`（§9）——Story Hierarchy 正确。
- `StoryConflict / Stakes / Foreshadowing / Payoff / InformationState / Pacing / EmotionalArc`（§10）。
- `CreativeProject / ProjectManifest / SchemaVersion / BackupPackage`（§26-28）。
- `Task / Checkpoint`（§18）。
- `AgentContract / AgentState / ToolSpec / AgentPermission`（§20,22）。
- 强类型 ID 方向（P1 计划落实）。

### MODIFY（小幅改动）
1. **`KnowledgeEntry` 作用域**：目前以 `novelId` 为唯一作用域。为支撑 Variant，需增加可选 `variantId?`（null = 项目共享/Original；非 null = Variant 专属），或引入一个轻量 `scope` 字段（Project/Original/Variant）。→ [RECOMMENDED，P1 前定]
   - 倾向：**给 Knowledge 增加 `scope`（projectScope：ORIGINAL | SHARED | VARIANT）+ `variantId?`**，避免破坏 Original Knowledge 共享读取；Original 本身只读不变。
2. **`CreativeProject`**：由"单项目 + 单 originalNovelId"扩展为"可挂接 **多个 Variant**"，或单独用 `NovelVariant` 承载（见 §10）。倾向后者（不重定义已有 Project，只加新实体）。→ [RECOMMENDED]

### ADD（新增，P1 前设计）
- `NovelVariant`（引用 + 覆写，见 §10）。
- `VariantKnowledge`（或复用 KnowledgeEntry + scope，见上）。
- `Vocabulary / VocabularyEntry / VocabularyRule / VocabularyCandidate`（见 §9）。
- （可选）`VocabularyScopeOverride / VocabularyReplacementRule`（并入 VocabularyRule）。

### REMOVE / 不做
- 不新增"好词好句库"顶层大系统；词藻类仅作为 VocabularyRule 的固定表达/禁区词的输入，不构建独立巨型词库。→ 遵从"本地优先、不过度设计"。
- 不为 Vocabulary / Variant 新增 Agent。
- 不新增模块。

---

## 5. Module Boundary Review（12 模块逐个）

| 模块 | 职责 | 依赖 | 问题 | 建议 |
|------|------|------|------|------|
| `:core:model` | 纯领域 | 无 | 无 | ✅ 保持；P1 加入 Variant/Vocabulary 模型 |
| `:core:engine` | 确定性引擎聚合 | core:model | 担责偏广（validation/knowledge/context/txt/analysis/novel/task 全在此） | ✅ 架构既定聚合，内部按子包隔离；Vocabulary 也放这里，不拆新模块 |
| `:agent:tool` | Tool System | core:model, core:engine | 无 | ✅；加 VocabularyTools |
| `:agent:runtime` | Agent Runtime | core:model, provider, agent:tool, core:engine | **依赖 provider 实现模块** | 拆 provider api/impl 边界，只依赖 api（或 internal 约束）[RECOMMENDED] |
| `:agent:agents` | 6 Agent | agent:runtime, agent:tool | 无 | ✅ 不再加 Agent |
| `:agent:orchestration` | Orchestrator/Workflow/状态机/HITL | agent:runtime, core:model, storage | 依赖 storage；防止从 SQL 直接读业务数据 | 只经 TaskStore/Repository；Variant 重构只是 workflow 的一次参数化运行 [RECOMMENDED] |
| `:provider` | LLM 接口+适配器 | core:model | 接口与实现同模块 | 内部拆 `api`/`impl` 包，api 暴露给 agent:runtime [RECOMMENDED] |
| `:storage` | SQLite/Repository/Migration/Backup | core:model | 无 | ✅ 加 Vocabulary 分区；选型见 §8 |
| `:runtime` | 平台抽象 | core:model | 无 | ✅ 保持不变 |
| `:app:android` | Android 客户端 | core:model（暂少） | 尚未接 core rest | P11 再接入，非 P0 问题 |
| `:app:desktop` | PC 客户端 | core:model, runtime | 同上 | P16 再接入 |
| `:test:e2e` | E2E | orchestration, agents, provider | 无 | ✅ |

**边界违规排查（§9 检查）**：当前仅占位代码，无实际违规；唯一编译面疑点是 `agent:runtime→provider`（见上）。其余（Domain→SQLite、UI→DB、Provider→Agent、Storage→UI）在现有依赖中**均不存在**。

---

## 6. Agent Architecture Review

**6 Agent 是否足够：足够。** 不需要为 Vocabulary / Novel Variant 增加 Agent。

| 新能力 | 是否需新 Agent | 由谁承载 |
|--------|--------------|---------|
| Vocabulary 候选生成/辅助审核 | 否 | 确定性 VocabularyEngine + 用户审核；AI 辅助生成走现有 KnowledgeAgent 的一次操作 |
| Vocabulary 创作前注入 | 否 | Context Engine + ContextType.VOCABULARY（Research/Planner/Writer 读取）|
| Vocabulary 创作后校验/自动替换 | 否 | 确定性 Validator + Tool，经 Critic 上报 SwiftIssue-缺陷 |
| Novel Variant 派生 | 否 | Novel Engine 的 derive 操作 + Writing Workflow 参数化；StoryPlanner 已负责重规划 |
| 剧情重构 | 否 | 复用 Planning→Writing→Critique→Revision；仅请求入口多一个"重构范围"参数 |

结论：**Agent 数量保持 6，不增加**。[DECIDED]

---

## 7. Workflow Review（解决 ISSUE-1）

### 7.1 冲突位置与两版本

**冲突位置**：V4.1 架构 §23.2 `WorkflowState` 枚举 与 开发目标（实现计划 ISSUE-1 记载的用户清单）**不一致**。

- **版本 A（用户产品清单）**：
  `RECEIVED → INTENT_ANALYSIS → CONTEXT_RESEARCH → PLANNING → WRITING → CRITIQUE → REVISION → FINAL_REVIEW → KNOWLEDGE_UPDATE → COMPLETED`
- **版本 B（冻结架构 §23.2）**：
  `INTENT_PARSING, RESEARCH, PLANNING, PLAN_REVIEW, WRITING, CRITIQUE, REVISION, KNOWLEDGE_UPDATE, CONFLICT_HITL, COMPLETED, FAILED, CANCELLED, PAUSED`

### 7.2 为何冲突
两者是**不同抽象层次**的视图：
- A 是"产品旅程 / 理想顺流"，包含生命周期边界（RECEIVED、FINAL_REVIEW）；
- B 是"实现状态机"，包含 HITL 分支（PLAN_REVIEW、CONFLICT_HITL）与终止态（FAILED/CANCELLED/PAUSED）及命名（RESEARCH vs CONTEXT_RESEARCH、INTENT_PARSING vs INTENT_ANALYSIS）。
把两个视图揉进**一个枚举**必然矛盾：A 的顺流没有分支与终止态，B 没有 FIRST/LAST 边界。

### 7.3 判断
**都不能直接作为"唯一状态机"**：
- 用 A：缺少 HITL/Revision-超限/暂停恢复的分支，无法表达受控 Agentic 所需的失败与裁决。
- 用 B：缺少对外表达"请求已接收"与"终审"的边界的语义，但**这正是产品可另用 Task State 表达**的。

### 7.4 第三种方案（推荐）：按层次拆开，B 为唯一 WorkflowState —— [DECIDED] 已确认

> 更新：ISSUE-1 已由用户拍板以架构 §23.2 为唯一定义（见《ISSUE-1 Resolution Report》与架构 §23.3）。下文为裁决内容记录。
| 状态族 | 取值 | 职责 | 持久化 |
|--------|------|------|:---:|
| **Task State** | `PENDING, RUNNING, PAUSED, CANCELLED, COMPLETED, FAILED`（架构 §18.2） | 异步任务生命周期 | 是 |
| **Workflow State** | = 冻结架构 §23.2 枚举（**用 B**）| 处于写作流水哪一阶段 | 是（Checkpoint）|
| **Agent State** | `READY/RUNNING/WAITING_HUMAN/FAILED/...`（架构 §20.2）| 单个 Agent 执行状态 | 是 |
| **Draft State** | `DRAFT / UNDER_CRITIQUE / PASSING / REJECTED(→REVISION) / FINAL` | 一份 Draft 的审查与修订（含 revisionCount ≤3）| 是 |
| **HITL State** | `NONE / PLAN_REVIEW / CONFLICT / REVISION_OVERFLOW` | 当前是否存在待人工裁决点 | 否（由 Workflow+Agent 推导）|

**映射**：`RECEIVED` = Task.status `PENDING`（不新增 Workflow State）；`FINAL_REVIEW` = 最后一次 CRITIQUE 复用 `CRITIQUE` 状态（通过即 `COMPLETED`）；`INTENT_ANALYSIS`=`INTENT_PARSING`；`CONTEXT_RESEARCH`=`RESEARCH`。→ 这样既不丢弃 A 的语义，也不摧毁 B 的实现。
将此作为 **ISSUE-1 的最终裁决建议**，[DECIDED]（待你确认后正式落定）。

### 7.5 新流程是否需要新状态
- **剧情重构/再创作**：**不需要独立状态机**。它是"对一个 Variant 目标运行既有 Writing Workflow"，仅在请求/规划层增加"重构范围（保留实体 / 需重写弧）"的字段，在 INTENT/PLANNING 消费。→ [RECOMMENDED]
- **词库预注入**：不属于状态，是 Context 阶段的数据装配（在 RESEARCH/PLANNING/WRITING 读取 Vocabulary）。
- **词库校验**：是 `CRITIQUE` 内新增一个确定性检查（VocabularyRule + 自动替换），**不是新 Workflow State**。

**结论**：无需为两个新需求新增 Workflow State。[RECOMMENDED]

---

## 8. Storage Decision（解决 TBD-4）

### 8.1 背景约束
Android + PC 共用 Domain Model；共用大量 Core；同一 SQLite；需要 Migration / Backup（确定性引擎）；Repository 隔离；JVM 单测友好；未来可能有其它 Runtime（TBD-5 iOS 决定是否 KMP）。

### 8.2 两方案对比

| 维度 | SQLDelight（单 SQL）| Room(Android)+sqlite-jdbc(PC)（双实现）|
|------|-------------------|-----------------------------------|
| 同一 Domain Model | ✅ | ✅ |
| 单一 SQL/schema 真源 | ✅ 一份 | ❌ 两套 SQL（Room 注解 + jdbc DDL）|
| 双端实现成本 | 一套 Repository | 两套 Adapter，SQL 双写 |
| Migratio | SQLDelight schema/delta + 上层 MigrationEngine | Room 自带迁移（仅 Android）+ sqlite-jdbc 另写 → 迁移双份 |
| Backup | 保持一致（BackupPackage 全量 JSON + schemaVersion）| 同上（差异化较小）|
| FTS5 | 原生 SQL 支持 | Room FTS 仅 Android，PC jdbc 需原生 SQL → 差异|
| JVM 单测（无模拟器）| ✅ SQLDelight 有 JVM driver，可在命令行跑 | Room 单测需 Robolectric；PC 用 jdbc 可跑但与 Room 分离 |
| 未来 iOS/KMP（TBD-5）| ✅ SQLDelight 原生支持 KMP | ❌ Room 仅 Android，几乎需重写 |

### 8.3 推荐结论
**推荐 SQLDelight 单 SQL 方案**。理由：
1. 唯一 schema/migration 真源 → 大幅减少"同一 Domain Model 双实现"的维护与迁移风险；
2. JVM driver → 容器/CI 可跑持久化测试，符合"每 Phase 必须有测试"；
3. 未来如上 iOS（KMP），SQLDelight 迁移成本远低于 Room；
4. 符合"不为了技术先进选复杂方案"——单 SQL 反而更简单，**避免双实现**。

**缺点**：SQLDelight 是 codegen/DSL，DAO 不如 Room 声明式；需生成代码进 CI；对 FTS5 属原生 SQL 门槛略高。缓解：Repository 隔离（:storage 内的 Adapter 对上层透明），SQLDelight 细节不外泄。

**未来迁移成本**：若先选 Room，未来迁 SQLDelight 或 KMP 成本高（重写 schema/DAO）；先选 SQLDelight，未来保持 SQL 真源、增端（iOS）成本低。→ [RECOMMENDED，需你拍板]

---

## 9. Vocabulary Architecture

### 9.1 层级（作用域收敛，窄覆盖宽）
```
Global Vocabulary      ← 全局通用（用户个人通用词库）
   ↓
Novel Vocabulary       ← 整部小说共享（世界观/人名/术语基线）
   ↓
Variant Vocabulary     ← 单个 Variant 专属（重构/换风格时覆盖）
   ↓
Task Vocabulary        ← 单次写作任务临时注入（一次性，不入全局）
```
规则：查询时从窄到宽匹配，**窄作用域优先于宽作用域**（Variant 可覆盖 Novel；Task 再覆盖 Variant）。

### 9.2 词条类型（VocabularyEntry）
- 人物称谓 / 专有名词 / 世界观术语 / 地名 / 势力 / 境界 / 物品 / 技能
- 固定表达 / 禁用词（forbidden）/ 替换规则（replacement）/ 文风表达（style-snippet）

### 9.3 生命周期
```
Candidate(INFERRED/auto/AI 提取)  →  User Review  →  Approved(USER_CONFIRMED)
   ├── 用户拒绝 → Rejected
   └── 用户编辑 → Approved(USER_EDITED)
Approved → 使用中
Approved → (被新词覆盖) → Deprecated / Superseded
```
- **AI 提取的词汇不能直接成为最高可信度词库** → 一律先为 Candidate，进待确认队列（复用 §14 User Confirmation）。[DECIDED 原则]
- **替换规则为确定性生成/执行，不调 LLM**：Draft 命中 forbidden/需替换时，按 App卖润规则自动替换；无替换规则或规则冲突时，路由到 Critic/Revision。→ [RECOMMENDED]

### 9.4 双时机
- 创作前：`ContextType.VOCABULARY` 按当前作用域注入 → Planner/Writer。
- 创作后：`VocabularyValidator`（确定性）校验 Draft → 命中规则自动替换 / 标记 issue → Critic/Revision。

---

## 10. Novel Variant Architecture

### 10.1 是否复制全文？
**不复制。Variant 应"引用 Original + 覆写 Delta"，不做全量拷贝。** 理由：原文可能很大（长篇小说），复制产生一致性与存储成本；且违反"不污染原文"精神——Variant 应是独立覆写视图而非新副本。

### 10.2 关系模型
```
Original Novel（只读）  ──┬── derived_from ──►  Variant A
                         ├── derived_from ──►  Variant B
                         └── derived_from ──►  Variant C
```
```kotlin
NovelVariant(
  variantId, projectId,
  baseNovelId: NovelId,        // 引用原文（只读，永不被修改）
  blueprint: VariantBlueprint, // 重构的故事结构：Arcs/Acts/Chapters/Scenes/Beats（Variant 专属）
  scopeSpec: VariantScopeSpec, // 保留哪些实体 / 重写哪些（用户要求的"重构范围"）
  overwrittenKnowledge: Set<KnowledgeId>,  // 覆写的知识条目引用（新内容在 VariantKnowledge）
  status...
)
```
- **继承（inherit）**：读穿透到 Original → 得到原有人物/世界观/知识。
- **覆写（override）**：Variant 专属知识/词汇/结构命中，优先于 Original。
- **新增（new）**：只在 Variant 中存在。
- **如何避免改原文**：原文只读；Variant 的所有写入落在 `VariantKnowledge` / Variant 蓝图，永不回写 Original。原始 Chapter 只读，重写后的正文作为 Variant 的章节（不覆盖 Original 章节）。→ [DECIDED 原则]

### 10.3 作用域落到存储
为 `KnowledgeEntry` 增加 `scope`（ORIGINAL/SHARED/VARIANT）+ `variantId?`（见 §4-MODIFY-1）。读取时按作用域合并（Original 基座 + Variant 覆写）。这样多 Variant 互不干扰，原文不被破坏。

---

## 11. Context / Knowledge / Vocabulary / Memory / Story Structure 关系

```
                         ┌────────────────────────────────────────────┐
                         │            Context Engine                  │
                         │   (ContextType 枚举 + Ranking + Token预算)   │
                         └───────────────┬────────────────────────────┘
                                         │ 注入给 Planner/Writer/Critic
       ┌───────────────┬─────────────────┼─────────────────────┬───────────────┐
       ▼               ▼                 ▼                     ▼               ▼
+--------------+  +----------+  +-----------------+  +------------------+  +---------------+
| Novel        |  | Knowledge|  | Vocabulary       |  | Memory           |  | Story         |
| Knowledge    |  | Lifecycle|  | (术语/称谓/替换) |  | (四层: 短/长/    |  | Structure     |
| "发生了什么" |  | +Conflict|  | "用什么词"      |  |  语义/工作)      |  | (Arc/Act/Plan/ |
|              |  +----------+  | 作用域: G/N/V/T |  +------------------+  |  Scene/Beat)   |
+--------------+               +-----------------+                       +---------------+
        │               建/查│               │ 候选→审核                    │
        ▼                       ▼               ▼                             ▼
+----------------------------------------------------+   +----------------------------+
|           Storage（Repository 隔离）                 |   |  Validator / VocabularyValidator |
| 分区: Original/Knowledge/Story/State/Vocab/Task/     |   |  (JSON schema + 词库确定性校验) |
|       Writing/Project                                 |   +----------------------------+
+----------------------------------------------------+
```
要点：
- **Knowledge（事实）** 与 **Vocabulary（用词）** 是两条并行通道，都经 Context 注入，但数据源不同（知识来自分析/生命周期，词汇来自词库层级的确定性数据）。
- **Memory** 提供"人物/世界当前状态"的即时上下文；**Story Structure（规划）** 提供"接下来推进什么"；二者在 Context Ranking 按 score 分配 Token。
- **Validator** 同时作用于 AI 结构化输出（§12）与 Draft 词法（VocabularyValidator），保证确定性兜底。

---

## 12. Recommended V4.2 Architecture（完整图）

```
:core:model
  ├─ Knowledge / Evidence / Conflict / Confirmation        [保持]
  ├─ Memory / Character(State/Arc) / Event / Timeline      [保持]
  ├─ Story: Arc/Act/ChapterPlan/ScenePlan/Beat/Conflict/Stakes/Foreshadow/Payoff/InfoState [保持]
  ├─ CreativeProject / Task / Checkpoint                    [保持]
  ├─ + NovelVariant / VariantBlueprint / VariantScope       [ADD]
  ├─ + Vocabulary / VocabularyEntry / VocabularyRule / VocabularyCandidate [ADD]
  └─ + Knowledge.scope(ORIGINAL|SHARED|VARIANT)+variantId?  [MODIFY]

:core:engine            （聚合；子包新增，不拆模块）
  ├─ ... 既有 engine 子包（validation/knowledge/context/txt/analysis/novel/task）[保持]
  └─ + vocabulary     VocabularyEngine（层级解析/候选审核/替换/校验）[ADD]

:agent:tool             + VocabularyTools（inject/validate/autofix）[ADD]
:agent:runtime          (→ provider api 边界) [MODIFY]
:agent:agents           6 个保持 [DECIDED]
:agent:orchestration    状态机=§23.2 唯一定义；重构=参数化运行 [DECIDED 建议]
:provider               (拆 api/impl 包边界) [RECOMMENDED]
:storage                + Vocabulary 分区；选型=SQLDelight [RECOMMENDED]
:runtime                [保持]
:app:android / :app:desktop / :test:e2e  [保持]
```

**核心改动性质**：全部为**加法 / 小改**，不新增模块、不新增 Agent、不推倒分层。

---

## 13. Required Changes Before P1（未决项分级）

### [MUST DECIDE]（进入 P1 前必须定，且影响 P1 建模）

1. ~~**ISSUE-1 状态机裁决**~~：**已解决 [DECIDED]**——确认"Task/Workflow/Agent/Draft/HITL 五族分离 + 以架构 §23.2 为唯一 WorkflowState"，接受 `RECEIVED→Task.PENDING` 与 `FINAL_REVIEW→复用 CRITIQUE` 的映射。详见《ISSUE-1 Resolution Report》与架构 §23.3。
2. **Knowledge 作用域**：确认给 `KnowledgeEntry` 增加 `scope + variantId?`，用以支撑 Variant 级知识（否则 P1 的领域模型无法表达多 Variant）。

### [RECOMMENDED]（建议采纳，进 P1 前确认）
3. **TBD-4 存储选型**：确认 **SQLDelight 单 SQL**（影响 P2 与 Repository 写法，但不阻塞 P1 领域建模）。
4. **NovelVariant 领域模型**：在 P1 直接加入（纯领域，越早定越省返工）。
5. **Vocabulary 领域模型**：在 P1 直接加入（同上）。
6. **Provider api/impl 边界**：P3 前把 `agent:runtime` 与 provider 实现解耦。

### [TBD]（用户提供信息 / 后续决定）
7. **MiMo** endpoint/model/鉴权/是否 OpenAI 兼容（P3）。
8. **API Key 存储**（建议 Keystore / 系统凭据）。
9. **PC UI 框架**（Compose Desktop 或 JavaFX，倾向 Compose Desktop）。
10. **是否未来需要 iOS**（决定 ISSUE-2/KMP）。

### [FUTURE]
11. 词库自动风格学习、跨 Variant 词库复用、Variant 差异对比视图、自动合并相似 Variant 等。

---

## 14. Things We Should NOT Change

- **6 Agent 边界**（不新增）。
- **Agent ≠ Engine ≠ Storage ≠ Provider** 的角色分离。
- **Original Novel 只读、不污染原文** 原则。
- **Agent 不直接写 DB**：写必须经 Validator + Lifecycle；Agent 只经 Tool。
- **Provider 与 Agent 的解耦**（只经 LLM Gateway）。
- **AI 不直接写数据库**、所有输出过 Validator + Retry(≤3) → UNCERTAIN 兜底。
- **Knowledge Lifecycle + Conflict Detection + User Confirmation（待确认队列）**。
- **Revision 限 3 次 + Checkpoint 可暂停恢复 + 防无限循环**。
- **Context Ranking + Token Budget**（6000 可配）。
- **Backup 在迁移前、写提交后自动触发的确定性方案**。
- **TXT Engine 纯本地零 AI；Analysis Pipeline 九阶段**。
- **`:core:engine` 聚合、不拆大量 Gradle 模块**。
- **JSON Schema（字符串编码枚举）为唯一真相、跨 Provider 一致**（ISSUE-3 原则）。

---

## 15. Final Recommendation

**选 B：先做少量、定向、加法式的架构修正，再进入 P1。**

理由：
- V4.1 主体健康，不需要 REWORK（C 排除）；
- 但两个新需求要求先确定两类领域元模型（**NovelVariant**、**Vocabulary**）与两项待决（**ISSUE-1 状态机**、**TBD-4 存储**），否则 P1 的领域模型会建错、P2/P11 返工；
- 修正范围小且纯加法（模型 + 引擎子包 + 工具 + 存储分区），不触碰已验证的 Agent/Engine/Provider/Storage 分层原则。

**进入 P1 前的必需清单**：确认 §13 中 [MUST DECIDE] 1、2 与 [RECOMMENDED] 3、4、5。

---

*本报告为只读审查。未修改任何代码 / Gradle / 数据库 / 模块；未开始 P1；未实施任何建议。所有 [RECOMMENDED] 待你确认后才会作为设计落定。*