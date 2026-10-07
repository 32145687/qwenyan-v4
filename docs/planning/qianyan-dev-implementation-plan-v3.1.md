# Qianyan 开发实施计划 v3.1

> 状态：规划基线（实施规划层 · 已按架构总图重新对齐并在 v3 基础上收口）
> 性质：**基于 v2 修正**，不推倒重来；本文档**不含代码改动**
> 本计划基于 v2 修订，v2 保留作为历史演进记录，不再作为当前实施基线。
> 架构基线（唯一，不可修改）：`docs/planning/Qianyan_开发总规划_连接优先架构.md` @ `b738086`
> 前序版本：`docs/planning/qianyan-dev-implementation-plan-v2.md`（保留为演进记录）· `docs/planning/qianyan-dev-implementation-plan-v3.md`（保留不动）
> 代码基线：`feature/novel-ide` @ `b738086` + 工作树未提交工作（P1-01 / P0-3 / I14）
> 角色：实施规划工程师 —— 只负责把「目标架构」拆解为可执行 Phase

---

## 0. 本次对齐的裁决输入（v3 的修正依据）

本次对齐由 18 条要求驱动，落实点如下：

| # | 裁决 | 落实位置 |
|---|---|---|
| 1 | 架构总图**不可修改**（不重设计 / 不删层 / 不用旧 Pipeline 替代 / DSH 不入业务层 / UI 不成业务架构 / 不建第二套 Canonical·World Model·Context·Draft） | §2 架构基线 + §3 事实源唯一性 + §25 禁止项 |
| 2 | **P1 重新审查 `saveContent = 保存即批准`**：作者手改可直接保存；**AI 产出禁止经 saveContent 绕过 Human Gate** | §4 语义裁决 + §10（Phase 1） |
| 3 | **P5 删除「每个 Tool 必须有生产 caller」**；Phase 5 只负责把 Tool Layer 接入 Qianyan，不要求 Agent 自主 Tool Calling | §14（Phase 5） |
| 4 | **P7 必须存在真正的 Agent Decision Point**，不得是固定 Pipeline 换名 | §16（Phase 7） |
| 5 | **P6 核心写作 MVP Skill = 长篇续写 + 人物一致性**（不得用「小说分析」替代核心写作 Skill） | §15（Phase 6） |
| 6 | **P2 最小可生产 World Model**：冻结 7 类对象，但只实现 MVP 所需最小集合；**Memory 不是第二套 Canonical** | §3 + §11（Phase 2） |
| 7 | **P3 必须补充冲突生命周期**（Pending / Accepted / Rejected / Conflict），高风险冲突可入 Human Gate；**禁止静默覆盖** | §12（Phase 3） |
| 8 | **P4 Snapshot 先做不可变运行时事实**，仅在真实需要时持久化，不提前扩 Storage | §13（Phase 4） |
| 9 | **P8 才是完整闭环验收**；此前每个 Phase 必须有自己的最小 vertical slice | §21 + 各 Phase 第 11 项 |
| 10 | **Phase 0 瘦身为 Preflight / Baseline Cleanup**（6 项）；Backup 移出、TXT→Chapter 移出、UI→Application 移出 | §9（Phase 0）+ §27「移出项（不在 Phase 0）」；归属：TXT→Chapter → §10（Phase 1）、Backup / Restore → §19（Phase 10）、UI→Application 收口 → §19（Phase 10） |
| 11 | **测试验收改为 A/B/C/D 分类**，禁止以「exemption」长期保留红灯 | §24 测试验收规则 |
| 12 | **P9 DSH 最后**；DSH 不得反向定义 Novel Project / Canonical / World Model / Change Layer / Skill / Tool Contract | §18（Phase 9） |
| 13 | **P10 UI 是 Product Experience Layer**，只能调 Application / UseCase | §19（Phase 10） |
| 14 | 每 Phase 输出 13 项 | §9–§19 各 Phase 统一结构 |
| 15 | 必须给 **Architecture Alignment Matrix**，并检查「无 Phase 负责」/「两 Phase 重复负责」 | §22 |
| 16 | 输出 v3 计划 + 对齐矩阵 + v2→v3 修改清单 + Phase 依赖 + MVP Slice + 每 Phase Gate | 全文；修改清单见 §28 |

---

## 1. 输出物索引

| 输出物 | 位置 |
|---|---|
| 《Qianyan 开发实施计划 v3.1》 | 全文 |
| Architecture Alignment Matrix | §22 |
| v2 → v3 修改清单 | §28 |
| v3 → v3.1 修改清单 | §29 |
| Phase 依赖关系 | §20 |
| MVP Vertical Slice | §21（含每 Phase 最小 slice） |
| 每 Phase Completion Gate | §9–§19 各 Phase 第 12 项 |
| R1 升级评估条件（FD-10 重新评估） | §23A |
| R3 读策略 | §23B |
| 词库归属 | §11A |

---

## 2. 架构基线（唯一，不可修改）

本次对齐采用**唯一架构基线**（节点与流向以下图为准）：

```text
PROJECT
│
├── CANONICAL STORY
│     ↓
│   WORLD MODEL
│     ├── Entity
│     ├── Relationship
│     ├── State
│     ├── Event
│     ├── Timeline
│     ├── Knowledge Boundary
│     └── Foreshadowing
│
└── AUTHOR / AGENT MEMORY
        ↓
   CONTEXT ENGINE
        ↓
     RETRIEVAL
        ↓
    NOVEL AGENT
        ↓
   SKILLS / TOOLS
        ↓
      EXECUTION
        ↓
       DRAFT
        ↓
       DIFF
        ↓
      CHANGE
        ↓
   HUMAN GATE
        ↓
      COMMIT
        ↓
    CANONICAL STORY
        ↓
  WORLD MODEL UPDATE
        ↓
      CONTEXT
        ↓
     NOVEL AGENT
```

**核心闭环**：

```text
World Model → Context / Retrieval → Novel Agent → Skills / Tools
→ Draft → Change Layer → Human Gate → Commit → Canonical → World Model Update
```

**方向硬规则（全 Phase 通用，不得反转）**：

```text
World Model / Memory
    ↓
Context Engine
    ↓
Structured Retrieval
    ↓
Context Snapshot / Context
    ↓
Novel Agent
```

> Agent 的职责是：**消费 Context**，并基于当前 Task + Context 决定下一步。
> **Agent 不直接读取 World Model**，不把 World Model 当作自己的数据库读取入口。
> 禁止出现 `Novel Agent → World Model → Context` 或 `Agent → World Model → Retrieval` 的反向链路。

**Runtime 层（与小说业务层并列，不嵌入）**：

```text
Novel Agent → Qianyan Runtime Adapter → DSH
DSH = Agent Loop / Tool Calling / Session / Permission / Sandbox / Subagent / Trace / Resume / LLM Runtime
```

**本计划只做一件事**：把 v2 的 Phase 拆分与上图严格对齐。上图的概念、节点、层、流向**一律不改**。

> 说明（不构成修改）：总图 §4 的**画法**把 Novel Tool Layer 画在 Agent 与 World Model 之间，本文档 §2 采用对齐评审中给出的**节点顺序**（Context Engine → Retrieval → Novel Agent → Skills/Tools）。两者**在归属与边界上一致**（Tool 只暴露业务能力、Retrieval 属 Context 的底层能力、DSH 独立），差异仅在绘图顺序，因此**不产生架构变更**。

---

## 3. 事实源唯一性裁决（新增 · 关闭 v2 的 R2）

v2 把「Memory 与 World Model 谁 canonical」列为待裁决。**本次已裁决，规则如下：**

```text
唯一事实关系：
Canonical Story  →  World Model

Memory（AUTHOR / AGENT MEMORY）≠ 小说事实源
```

- **World Model 表示**：小说世界**当前真实状态 / 事实**（Entity / Relationship / State / Event / Timeline / Knowledge Boundary / Foreshadowing）。
- **Memory 表示**：**作者 / Agent 的长期工作记忆、偏好、经验** → 只喂 Context，**不作为小说事实的下游**。
- **禁止**出现互相竞争的多个「事实源」：`Canonical Story` / `World Model` / `Memory` / `StoryState` / `NarrativeState` **不得并列成为第二 Canonical**。
- 命名澄清（不改代码，仅口径）：现有代码里的 `StoryState`（六表）与 `NarrativeState` 属**World Model 的既有载体**，其定位由本次裁决统一为「World Model 的存储层」，**不再自称事实源**；`Memory`（`MemoryEntry` + `MemoryLayer`）定位为**工作记忆**。

**这条裁决的实践后果**：Phase 2 只允许把 World Model 建立在 `Canonical → World Model` 这一条链上；`Memory` 的写入继续走既有 KU 链，但**不得**被读成"小说事实"去和 World Model 争权威。

---

## 4. Change Layer 语义裁决（修正 v2 的 P1）

v2 引用了既有裁决「**保存即批准**（i-b）」并把 `saveContent` 纳入 Change Layer。**本次审查后收紧，按"谁在写"区分：**

### 4.1 作者手工编辑 → 允许直接保存

```text
Author Edit → Save → （直接落 Draft）
```

理由：作者本人即人类决策者，保存行为本身就是 HITL 的落地；不需要再弹一次 Gate。

### 4.2 AI 生成 / AI 修改 → 必须走 Change Layer

```text
AI → Working Draft → Diff → Change → Human Gate → Commit → Canonical
```

### 4.3 明确禁止（v3 新增硬约束）

```text
禁止：AI → saveContent → Approve → Canonical
禁止：任何"普通 Save"路径成为 AI 产出进入 Canonical 的通道
```

**实现含义（Phase 1 契约）**：`saveContent` 只能接收**作者手工编辑**；AI 产出的落点必须是 `WorkingDraft`，且**不存在**从 `WorkingDraft` 直通 `Canonical` 的路径。即"保存即批准"**仅适用于作者手改**，**不得**被扩展为 AI 的捷径。

> 与既有裁决的关系：本次不是推翻"保存即批准"，而是**限定其适用范围**（作者手改），并**新增**"AI 产出禁止经 Save 绕过 Gate"这条硬约束。

---

## 5. 当前真实代码状态

以「是否存在**生产调用者**」为准。

| 层 | 代码 | 生产可达 | 真实结论 |
|---|---|---|---|
| `core:model` | 全量 | 是 | 复用；19 种 `OverridableKind` 仅 9 种有表 |
| `core:engine` | TxtPipeline / ControlledMarkdown / AnalysisInputBuilder | 是 | 复用 |
| `provider` | 4 网关 | 是 | 真实可用；`stream=false`、`usage` 无消费者 |
| `storage` | 21 表族 + 迁移 1–21（+未提交 `24.sqm`） | 是 | 主体可复用；**17 字段持久化丢失**；迁移编号断裂 |
| `application` | 165 文件 / 17 子包 | 部分 | 大量 UseCase **无生产 caller** |
| Change Layer | 七段全实现 | **否** | 唯一驱动者 `NovelAgent` 零 UI 调用；**7 条直写 `DraftRepository`** |
| Canonical | 隐式 | 部分 | = `latestByChapter`；**无 Commit 记录** |
| World Model（六表） | 表 + 仓储 + mapper | **否** | 写侧零调用者；读侧被 prompt 边界丢弃 |
| Context | **四套并存** | 部分 | `ContextEngineUseCases` / `PlanningContextAssembly` / `ChapterContextCompileUseCases` / `WriterGateway.loadContext` |
| Retrieval | 内存 index | **否** | 不索引正文；`.sq` 中 `LIKE/GLOB/FTS` = 0 |
| Novel Tool | 7 只读 Tool + registry / executor | **否** | 唯一 invoke 点在不可达路径；`AgentRuntime:84` 有绕过 ActionPolicy 的旁路 |
| Novel Skill | 硬编码 5 个 | **否** | 契约字段无读取者 |
| Novel Agent | 七相位 + while-loop | **否** | 固定 pipeline；`AgentRuntime` 被空注册表废成单次调用 |
| Runtime / DSH | `:runtime` 空壳 | **否** | 适配器只在 `feature/runtime-dsh@9a6f7c0` |
| Android | Writer / Reader | 是 | Writer = 唯一完整受控闭环；Reader 读不到 TXT 导入章节 |
| Desktop | 部分（I14 未提交） | 部分 | 写作链可用；**P0-3 已修**（3 测试通过） |

---

## 6. 当前生产链路（唯一真正在跑的写作链）

```text
WritePage「继续写」→ WriterController.continueWriting() → WriterFacade
  → ChapterWorkflowFacade.advance → WorkflowOrchestrator.runForward:90（guard<16 固定流水线）
      ├─ executePlanning:151 → PlanningContextAssembly.assemble → PlannerAgent
      ├─ executeWriting:165  → WritingExecutionUseCase:52 → WriterAgent → LLM
      │                        → DraftParser → DraftRepository.save:71（status=WRITTEN）
      ├─ executeCritique:199 → CritiqueAgent（布尔 PASS/REVISION 驱动分支）
      ├─ createPendingGate:272 + WAITING_HUMAN:273（真实阻断）
      └─ 「通过确认」→ approveGate:294 → confirmFinalDraft → gate RESOLVED + Draft CONFIRMED ← P0-3 已接通
```

进 prompt：Novel 元信息 · 当前 Draft 正文 · 词库 · Author · Foundation · DecisionPolicy · Memory 字符串。
**不进 prompt**：前文 · 人物 · 世界 / 事件 / 时间线 / 伏笔。

唯一闭环的领域写入：KU → `MemoryEntry` → `orderedVisible` → prompt。

---

## 7. 当前与目标架构的差距

| # | 架构节点 | 目标 | 当前 | 差距性质 |
|---|---|---|---|---|
| D1 | Canonical | 只有 Commit 后成为 Canonical | = `latestByChapter`；7 条直写 | **事实源缺失** |
| D2 | Change / Diff / Commit | 生产链贯通 | 仅有 TEST-ONLY 路径 | 接线 |
| D3 | World Model | 当前世界状态 | 六表零写入者、17 字段丢失、读侧丢弃 | 连接 + migration |
| D4 | World Model Update | Canonical Change 驱动 | **完全不存在** | 新建（依赖 D1/D3） |
| D5 | Context Engine | 唯一入口 | **四套并存** | 收敛 |
| D6 | Retrieval | 结构化检索 | 内存 map、不索引正文 | 新建 |
| D7 | Novel Tool | 受控接口 | 7 Tool 无人调用 + 一条策略旁路 | 接线 + 收口 |
| D8 | Novel Skill | 统一契约 + 生产调用 | 契约字段无读取者、9 项能力多不存在 | 新建 |
| D9 | Novel Agent | 自然语言入口 + Decision Point | **无入口**、固定 pipeline | 新建 |
| D10 | Human Gate | PC / Android 统一 | 桌面刚接通、Android 已可用 | 收口 |
| D11 | Memory | 工作记忆（非事实源） | 唯一活着的领域写入，易被误当事实源 | **口径裁决**（§3） |
| D12 | Storage 基线 | migration / schema 自洽 | 编号断裂 + 12 版本期望红 + 8 DB lock | Preflight 处置 |
| D13 | 导入路径 | 导入 → Chapter → 可读 | 导入不建 Chapter | 连接缺失 |
| D14 | UI | 只经 Application | 桌面 8 处直连仓储 | 收口（Phase 10） |
| D15 | DSH | Runtime，不入业务层 | 适配器不在工作树 | 最后做 |

---

## 8. Phase 总览

| Phase | 名称 | 架构节点 | 依赖 | 最小 Vertical Slice |
|---|---|---|---|---|
| **0** | **Preflight / Baseline Cleanup** | —（基线） | — | 构建 + 测试基线可判定 |
| **1** | **Change Layer（建立 Canonical）** | Draft / Diff / Change / Human Gate / Commit / Canonical | P0 | AI 产出 → Gate → Commit → Canonical |
| **2** | **World Model（最小可生产）** | World Model | P1（建议） | 读 World Model 并验证事实 |
| **3** | **World Model Update** | World Model Update | P1 + P2 | Canonical Change → Candidate → Update |
| **4** | **Context + Retrieval** | Context Engine / Retrieval | P2 | Task → ContextRequest → Context → Agent |
| **5** | **Novel Tool** | Novel Tool Runtime | P4 | Caller → Tool → Result |
| **6** | **Novel Skill（核心写作优先）** | Skills | P5 | Task → Skill → Execution → Result |
| **7** | **Natural Language + Novel Agent** | Novel Agent | P6 | NL → Agent → Tool/Skill → Result → Agent |
| **8** | **Complete Writing Vertical Slice** | 全链验收 | P1–P7 | §21.1 完整 17 段闭环 |
| **9** | **DSH Runtime Adapter** | Runtime Adapter → DSH | P8 + 动机裁决 | 单 Tool 经 DSH 往返 |
| **10** | **Product UI** | UI / Client | P8 | UI 只经 Application 完成一条真实任务 |

**并行轨**：UI Prototype（自 Phase 0 起；只验证「客户端感觉」，不产出信息架构）。

---

## 9. Phase 0 — Preflight / Baseline Cleanup

**1. 目标**：让后续每个 Phase 的「真实生产链验证」可信。**本 Phase 不开始任何新的产品能力。**

**2. 在架构总图中的位置**：不属于任何架构节点，是**基线前置**。

**3. 当前代码真实状态**：
- 分支 `feature/novel-ide`，HEAD `b738086`；工作树 38 项未提交（19 M / 7 D / 12 ??）。
- `:storage` 曾因缺 import 编译失败（已修并提交 `d75bf34`）；`:application` 曾因 3 处 `WorkflowId?` 失败（工作树已收口为 `?: throw`）。
- 未提交的 `24.sqm` 使 `Schema.version` 由 22 变 25 ⇒ 12 处版本期望红（10 `:storage:test` + 2 `DesktopGraphSmokeTest`）。
- 8 个 `:storage:test` 因 Windows 临时 DB 句柄占用失败。
- P0-3（桌面「通过确认」）代码 + 测试已完成且通过，但**未提交**。

**4. Gap**：缺一个"可判定的基线"：能编译、能跑、失败项有归属。

**5. 本 Phase 要连接什么**：不连接业务能力；只把 **Git baseline ↔ 测试基线 ↔ 失败归属** 三者对齐。

**6. 不做什么**：
- 不实现 TXT→Chapter（Phase 0 不接管；归属 **Phase 1**，见 §10 与 §27「移出项」）
- 不做 Backup / Restore（Phase 0 不接管；归属 **Phase 10**，见 §19 与 §27「移出项」）
- 不做 UI→Application 收口（Phase 0 不接管；归属 **Phase 10**，见 §19 与 §27「移出项」）
- 不新增产品能力、不改架构、不提前做任何 Phase 1+

**7. 涉及模块 / 文件**：
- 只读：`git`（分支 / SHA / 远程一致性）、`storage/build/test-results/**`（失败清单）
- 待处置（仅编译 / schema 类）：`storage/src/main/sqldelight/com/qianyan/storage/db/*.sqm`、`storage/src/main/kotlin/com/qianyan/storage/db/DatabaseInitializer.kt`、`storage/src/test/java/com/qianyan/storage/*MigrationTest.kt`、`app/desktop/src/jvmTest/kotlin/com/qianyan/app/desktop/DesktopGraphSmokeTest.kt`

**8. API / Contract**：**不新增、不修改任何契约**。

**9. Storage / Migration**：只做**编号归位**（`24.sqm` 与 `Schema.version` 语义一致：实测版本 = 编号最大值 + 1），**不改 SQL 语义、不新增表**。

**10. 测试**：产出**基线清单**（按 §24 的 A/B/C/D 分类逐个归属）：哪些是本阶段必须修、哪些是历史遗留、哪些是环境问题、哪些是测试自身问题。

**11. 本 Phase 的最小 Vertical Slice**：

```text
Git baseline 确认 → 构建成功 → 测试基线可判定（每项失败有 A/B/C/D 归属）
```

**12. Completion Gate**：
- 六项完成：① baseline 确认 ② 未提交工作登记 ③ 编译 / schema 阻塞处理 ④ 测试基线明确 ⑤ 失败责任归属明确 ⑥ 未开始新产品能力；
- **A 类失败 = 0**（本阶段引入的失败必须修完）；
- B/C/D 类**有登记、有归属、有后续 Phase**，且**不以「exemption」长期保留**。

**13. 下一 Phase 的输入**：可信的构建 / 测试基线 + 未提交工作边界 → Phase 1 可以在干净基线上动 Change Layer。

---

## 10. Phase 1 — Change Layer（建立 Canonical）

**1. 目标**：建立**最小可运行的 Change Layer 闭环**，让 Canonical 从"隐式 latest Draft"变成"Commit 的产物"。

**2. 在架构总图中的位置**：`Draft → Diff → Change → Human Gate → Commit → Canonical` —— 图中唯一的"事实落地"段。

**3. 当前代码真实状态**：七段实现齐全但唯一驱动者 `NovelAgent` 无 UI caller；7 条直写 `DraftRepository`；`saveContent` 原地改 `content`（保留 `draftId`/status/lineage）⇒ 人类一次按键改写正典行；`getByResultingDraftId` 已可用（`d75bf34`）；桌面确认按钮已接通（P0-3）、Android 一直可用。

**4. Gap**：入口不可达；无 Commit 记录；AI 产出与作者手改共用同一条 Save 通道。

**5. 本 Phase 要连接什么**：

```text
现在：AI 产出 → DraftRepository.save → latestByChapter（隐式正典）
现在：Author Edit → saveContent → 原地改正典行
本 Phase：AI 产出 → WorkingDraft → Diff → Change → Human Gate → Commit → Canonical
本 Phase：Author Edit → Save →（直接落 Draft；作者即人类决策者）
本 Phase（导入语义）：作者 TXT → Import → Chapter → Canonical 初始化（**不走 AI 变更链**）
本 Phase（导入后 AI 修改）：AI → Working Draft → Diff → Change → Human Gate → Commit → Canonical
```

**6. 不做什么**：
- 不做 Change Layer 的"高级能力"（多提案并行、部分接受、跨章重构、Revert 全链）
- 不做 World Model Update（Phase 3）
- 不做 Context 收敛（Phase 4）
- 不引入第二套 Draft / 第二套 Gate
- **不把 AI 产出塞进 `saveContent`**

**7. 涉及模块 / 文件**：
- `application/.../usecase/draft/WorkingDraftUseCases.kt`、`change/ChangeUseCases.kt`、`commit/CommitUseCases.kt`、`action/ActionPolicyUseCases.kt`
- `application/.../usecase/writing/WriterUseCases.kt`（`saveContent` 适用范围收紧）、`writing/WritingExecutionUseCase.kt`、`writing/revision/RevisionExecutionUseCase.kt`、`writing/confirmation/ConfirmationExecutionUseCase.kt`、`chapter/ChapterWritingUseCases.kt`
- `application/.../usecase/workflow/{WorkflowOrchestrator,WorkflowService,ChapterWorkflowFacade}.kt`
- `storage/.../repository/{DraftRepository,CommitHistoryRepository}.kt`、`sqldelight/.../{Draft,CommitHistory}.sq`
- `app/desktop/src/jvmMain/.../ui/pages/WritePage.kt`、`ui/write/WriterController.kt`

**8. API / Contract**：
- `saveContent` 契约**收紧**：仅接受作者手工编辑（见 §4）；**签名不变**，语义边界明确化。
- AI 产出**禁止**经 `saveContent`；必须经 `WorkingDraftUseCases`。
- Canonical 判定统一用 `getByResultingDraftId`（**不新增 `isCommitted` 字段**）。
- Human Gate 保持既有 `ActionPolicyUseCases` / `WorkflowService.approveGate` 一套，**不新建第二套 Gate**。
- **本 Phase 建立的是"最小闭环"**：Draft → Diff → Change → Gate → Commit → Canonical，高级能力后置。
- **新增正式读取入口 `CanonicalRead`**：Reader / Writer / Application / UI 取正典一律经 `CanonicalRead`；`latestByChapter` **仅限** Draft / Working Draft 查询，**不得**再被当作 Canonical 正文来源（**具体场景读取策略见 §23B；R3 已 FINALIZED**）。
- **TXT 导入语义 = Canonical 初始化**：作者已有内容 `TXT → Import → Chapter → Canonical 初始化`，**不走** `AI Draft → Diff → Human Gate → Commit`；导入后 AI 的修改才走变更链。

**9. Storage / Migration**：**不需要新表**。复用 `CommitHistory.resulting_draft_id`（已有只读查询）。若需要"哪些 Draft 已 Commit"的判定，用现成查询即可。

**10. 测试**：
- A 类（本阶段必须修）：`AI 产出无法经 saveContent 进入 Canonical`（**负向断言**：构造 AI 产出 → 断言 Canonical 未变）。
- `AI 产出 → 人工确认 → CommitHistory 有记录 → Canonical == 该 Draft`
- `未 Commit 的 Draft 不被任何"正典读取者"读到`
- `重复 Commit 幂等`
- `作者手改 → Save → 直接生效`（正向断言，确保 §4.1 不被误收紧）

**11. 本 Phase 的最小 Vertical Slice**：

```text
AI 生成一段正文 → Working Draft → Diff 可见 → Change 生成
→ Human Gate 待确认 → 作者确认 → Commit → Canonical 更新（且旧正典可追溯）
```

**12. Completion Gate**：
- 直写 `DraftRepository` 的生产路径 = **0**（Commit 内部除外）；
- AI 产出**不存在**绕过 Human Gate 的路径（负向测试通过）；
- `CommitHistory` 有真实生产写入；Canonical ≠ latest Draft；
- **`CanonicalRead` 是正式正典读取入口**；`latestByChapter` 不再被 Reader / Writer / Application / UI 当作正典正文来源；
- **TXT 导入 = Canonical 初始化**（不经过 AI 变更链），且与 AI 修改路径语义分离；
- 作者手改路径仍可用（未被误伤）；
- 三条边界检查全绿。

**13. 下一 Phase 的输入**：**Commit 事件**（这是 Phase 3 的唯一触发源）+ 显式 Canonical 判定。

---

## 11. Phase 2 — World Model（最小可生产）

**1. 目标**：建立**最小可生产的 World Model Foundation**：定义最小模型 / Repository / 读取 / 字段完整性，能读、能验证、字段不丢；**本 Phase 不建立正式生产 World Model 写入链路（production write callers = 0）**；**不为"完整"提前造空表**。

**2. 在架构总图中的位置**：`Canonical Story → World Model`（唯一事实关系的落点）。

**3. 当前代码真实状态**：`StoryState.sq` 六表 + `StoryStateRepository` + `StoryWorldContextResolver`（合并算法正确、确定性）；但 `addXxx / overrideXxx` 零生产调用者；`PlanningContextAssembly:111 characters = emptyList()`；17 字段丢失；`KnowledgeEntry / StoryConflict / Location / Relationship` 有 ID 无表；19 种 kind 仅 9 种落表。

**4. Gap**：读侧不通 prompt；写侧无入口；字段丢失；悬空引用。

**5. 本 Phase 要连接什么**：

```text
现在：Resolver → worldContext →（丢弃）
本 Phase：Resolver → 读侧契约（Tool 层能力）→ 被 Context 消费
本 Phase：明确 World Model ↔ Memory 的事实源关系（§3）
本 Phase 不建立：Canonical → World Model → Update 的正式生产写入链（那是 Phase 3 的职责）
```

**6. 不做什么**：
- **不为 7 类对象各建一套空表**：先冻结概念，**只实现 MVP 所需最小集合**
- 不实现 World Model Update（Phase 3）
- 不新建第二套 World Model / 不新建第二套事实源
- 不让 UI 直连 World Model 存储（见 §25 明确禁止项）
- 不把 Memory 升格为小说事实源
- **不建立正式生产 World Model 写入链路**（production write callers = 0；正式写入由 P3 的 Update 管道与人工编辑承担）

**7. 涉及模块 / 文件**：`core/model/.../{character,world,timeline,story,knowledge}/*.kt`；`storage/.../StoryState.sq` + `StorageMappers.kt` + `StoryStateRepository.kt`；`application/.../usecase/story/StoryStateVariantUseCases.kt`、`override/OverrideUseCases.kt`、`writing/context/StoryWorldContextResolver.kt`、`writing/planning/PlanningContextAssembly.kt`。

**8. API / Contract**：
- **冻结 7 类对象**（Entity / Relationship / State / Event / Timeline / Knowledge Boundary / Foreshadowing）作为命名与归属口径；
- **只实现 MVP 最小集合**（建议：Character/State + Event/Timeline + Foreshadowing；Relationship / Knowledge Boundary 先保持"概念冻结、实现后置"）；
- 读侧通过 Tool 能力暴露（Phase 5 落地真正的 Tool Runtime），**本 Phase 只保证数据能正确读出**。
- **写侧不接线**：`StoryStateVariantUseCases` / `OverrideUseCases` 在本 Phase 仅随字段补全一并核对，**不接入任何生产调用者**（production write callers = 0）。

**9. Storage / Migration**：**需要 additive migration**（补 `Event` 7 字段、`CharacterState` 7 字段、`Character` 3 字段）—— 否则"能写"等于"写进去就丢"。**不新建表族**；悬空 ID（`KnowledgeEntry` / `StoryConflict` / `Location` / `Relationship`）按"是否进 MVP"决定**落表或明确标注为未实现**（禁止留着含糊）。

**10. 测试**：
- 六类（实现范围内）实体"存 → 读"往返无损（逐字段断言；**写入用 fixture / Canonical 数据验证，不用生产 caller**）；
- `resolve()` 的产物能被下游消费（此阶段的消费方是测试内的读取验证，不要求 prompt）；
- Original 不可改写（既有触发器）；
- Variant 合并四情形（INHERIT / OVERRIDE / REMOVE / ADD）保持确定。

**11. 本 Phase 的最小 Vertical Slice**：

```text
（fixture / Canonical 数据）写入一条 World Model 事实 → 读取 → 校验字段完整 → 校验 Variant 隔离
（production World Model write callers = 0；正式写入由 P3 建立）
```

**12. Completion Gate**：**P2 production World Model write callers = 0**；实现范围内的往返无损测试全绿；World Model **不被 UI 直连**；`Canonical → World Model` 事实关系成立（Memory 未被当作事实源）；不新增竞争性事实源。

**13. 下一 Phase 的输入**：可读、字段完整的 World Model 载体 → Phase 3 有写入目标。

---

## 11A. 词库归属（Vocabulary Anchoring）

**定位**：词库不是独立事实源或独立存储系统，而是**跨作用域的词条查询能力**。

**三层作用域**：

| 作用域 | 归属 | 范围 |
|---|---|---|
| AUTHOR | Memory | 作者句式偏好、拼写习惯、作者级禁用词 |
| SERIES | World Model · scope = SERIES | 系列共享的世界观专名、地名、组织、功法、历史事件 |
| BOOK | World Model · scope = BOOK | 本书人物、专有称谓、特殊物品、术语、敏感词 |

**查询顺序**：按作用域由具体到宽泛补充，越具体优先级越高。

```text
BOOK → 未命中 → SERIES → 未命中 → AUTHOR → 未命中 → empty
```

同名冲突时，以**最具体作用域的词条为有效结果**；宽作用域**仅作为补充**（不是删除或覆盖存储数据）。

**TEMPLATE**：可导入的预置资源（如「玄幻常用词模板」）。导入后**按条目语义归入已有的 BOOK / SERIES / AUTHOR 作用域**。TEMPLATE 本身不参与小说事实判断，不建立独立模板存储。

**P2 / P4 边界**：**P2** 只处理 BOOK 级词条；SERIES / AUTHOR 级的语义归属已确定，但当前**不启用查询**、**不增加生产写入链路**。**P4** 的查询按 **BOOK 单一作用域**执行；多作用域查询（BOOK → SERIES → AUTHOR）作为**未来扩展**，当前不实现。

**禁止**：不建立第二套事实源；不新增词库表 / `VocabularyUseCase` / `VocabularyContext`；不新增独立 Vocabulary 领域层。

---

## 12. Phase 3 — World Model Update

**1. 目标**：Commit 之后把"正文变化"沉淀为 World Model 增量，**且永不静默覆盖**。

**2. 在架构总图中的位置**：`Canonical Story → World Model Update → Context → Novel Agent`（闭环的回边）。

**3. 当前代码真实状态**：**完全不存在**。可复用范式：`KnowledgeUpdateExecutionUseCase`（Extract → Validate → Persist，含 `KnowledgeValidator`、单事务、"人工确认"）；`TextDiffer`；`ControlledMarkdown`；`AnalysisInputBuilder`。

**4. Gap**：Extract / Candidate / Validate / Update 四段全缺；缺"由 Commit 触发"的挂载点；**缺冲突处理**。

**5. 本 Phase 要连接什么**：

```text
Phase 1 产出：Commit 事件（resultingDraftId + previous/resulting content）
本 Phase：Commit → Extract（规则 + Index + AI）→ Candidate Facts
        → 【冲突生命周期】→ Validate → World Model Update（携带来源）
```

**6. 不做什么**：
- 不做语义检索 / 全文检索 / RAG / Vector / Embedding / Full Text Search —— **当前状态**：受 **§25 明确禁止项**约束，**FD-10 生效中**（排除边界）；**未来状态**：重新评估条件见 **§23A**（R1 已 FINALIZED，FD-10 未解封）；**实施规则**：本 Phase **不得自行解封或实现**
- 不在草稿阶段更新 World Model（边界二）
- 不做"批量重建整个世界模型"的一次性大动作（增量优先）

**7. 涉及模块 / 文件**：`application/.../usecase/writing/knowledgeupdate/*`（范式来源）、`commit/CommitUseCases.kt`（触发点）、`writing/context/StoryWorldContextResolver.kt`（读取面）、`story/*`（写入面）、`core/engine`（确定性抽取）、`storage/.../{StoryState.sq,CommitHistory.sq}`。

**8. API / Contract**（**新增冲突生命周期，本次重点补充**）：

```text
CandidateFact
├── Pending     待判定
├── Accepted    已接受（写入 World Model）
├── Rejected    已拒绝（记录理由，不写入）
└── Conflict    与现有 World Model 冲突（不得覆盖）
```

- **禁止静默覆盖**：`Candidate Fact` 与现有事实冲突时，**不得直接覆盖**；进入 `Conflict`。
- **高风险冲突 → Human Gate**（复用既有 Gate 机制，不新建）。
- 每次 Update 必须携带**来源引用**（哪个 Commit / 哪段正文），否则不可写入。
- 复用 `KnowledgeValidator` 的校验范式；不新建第二套校验体系。

**9. Storage / Migration**：需一张**候选事实**表（additive）承载 Pending/Accepted/Rejected/Conflict 状态与来源；`Event.participants / evidence / factLevel` 的落库依赖 Phase 2 补列。

**10. 测试**：
- `未 Commit 的 Draft 变化 → World Model 不变`（**边界二的可执行断言**）
- `Commit → World Model 增量 + 来源可追溯`
- `候选与现有事实冲突 → 状态为 Conflict，原事实未被覆盖`（**本次新增的核心断言**）
- `Validation 拒绝的错误事实 → 不污染 World Model`
- 幂等（同一 Commit 重放不重复写入）

**11. 本 Phase 的最小 Vertical Slice**：

```text
一次 Commit → 抽取候选 → 与现有事实比较 → 无冲突则 Accepted 并写入 / 有冲突则 Conflict 并留待人审
```

**12. Completion Gate**：`Commit → World Model Update` 回路闭合；**冲突零静默覆盖**（断言通过）；抽取结果 100% 可追溯到 Commit；草稿阶段 World Model 零变化。

**13. 下一 Phase 的输入**：可增量更新、可追溯、有冲突状态的世界模型 → Phase 4 有真实的"事实"可取。

---

## 13. Phase 4 — Context + Retrieval

**1. 目标**：让"按任务取正确的 Context"真正跑起来；把四套 Context 收敛为**唯一入口**。

**2. 在架构总图中的位置**：`World Model + Memory → Context Engine → Retrieval → Novel Agent`。

**3. 当前代码真实状态**：四套并存（`ContextEngineUseCases` 只服务 Inspector / `PlanningContextAssembly` 写作真链路 / `ChapterContextCompileUseCases` 仅 UI / `WriterGateway.loadContext`）；`packVersion`/`isStale` 只保护没人消费的产物；Snapshot 不持久化；Retrieval 不存在（不索引正文；`find()` 无 caller）。

**4. Gap**：入口不唯一；Snapshot 不可复现；无结构化检索；写作链读不到前文/人物/世界。

**5. 本 Phase 要连接什么**：

```text
现在：多个入口各自拼 prompt（四套）
本 Phase：Task → ContextRequest → ContextEngine → Retrieval → ContextSnapshot → Agent
现在：写作链前文/人物/世界不进 prompt
本 Phase：按任务需要取（当前章节 / 相关前文 / 人物状态 / World Model / Timeline / Foreshadowing / Knowledge Boundary）
```

**6. 不做什么**：
- **不为了"完整架构"提前扩 Storage**：Snapshot **先做不可变运行时事实**，仅当现有 Storage / 业务真正需要时才持久化
- 不做语义检索 / 全文检索 / RAG / Vector / Embedding / Semantic Search / Full Text Search —— **当前状态**：受 **§25 明确禁止项**约束，**FD-10 生效中**（排除边界）；**未来状态**：重新评估条件见 **§23A**（R1 已 FINALIZED，FD-10 未解封）；**实施规则**：本 Phase **不得自行解封或实现**（第一阶段只做 Structured Retrieval，见第 8 项）
- 不做"Context 页面"（UI 是 Phase 10；Context 不是页面）
- 不新增第三套 Context

**7. 涉及模块 / 文件**：`application/.../usecase/context/{ContextEngineUseCases,ContextSources}.kt`、`usecase/writing/planning/PlanningContextAssembly.kt`、`usecase/lcl/ChapterContextCompileUseCases.kt`、`usecase/index/ProjectIndexUseCases.kt`、`core/model/.../{context,lcl,projectindex}/*`、`usecase/workspace/WorkspaceUseCases.kt`。

**8. API / Contract**：
- 唯一契约：`Task → ContextRequest → ContextEngine → ContextSnapshot → Agent`；
- `PlanningContextAssembly` / `ChapterContextCompileUseCases` **降级为 ContextEngine 的 Source 或视图**，**不得**再各自拼 prompt；
- `Retrieval` 作为 **Context Engine 的底层能力**（**不是独立架构层**），**第一阶段只做 Structured Retrieval（结构化检索）**，索引对象限定为**当前实际存在的索引类型**：
  `NOVEL` / `CHAPTER` / `DRAFT` / `STORY_FOUNDATION` / `VOCABULARY`；
  `CharacterIndex` / `EventIndex` / `TimelineIndex` / `ForeshadowingIndex` **暂不作为本阶段要求**（无稳定 Canonical 来源时，建立索引即为虚构）——待 **P2 / P3** 提供相应 Canonical 来源并形成实际可用的数据来源后，在**后续 Phase 补齐**；
- **"Previous Chapters" 必须经 `ChapterId / Sequence / Relation` 获取**，**不得**偷偷实现成 RAG / Vector Search / Embedding / Semantic Search / Full Text Search（这些替代方案**当前受 §25 明确禁止，FD-10 生效中**；重新评估条件见 **§23A**；本 Phase **不得自行解封或实现**）；
- Snapshot **不可变**（运行时可复现）；持久化 = 可选，由真实需求触发。

**9. Storage / Migration**：**原则上不需要**（Snapshot 先运行时）；仅当出现"必须跨进程复现"的真实需求时才 additive 落库。

**10. 测试**：
- 同一输入 → 同一 Snapshot（确定性）；
- 写作链与 Inspector 得到**同一** Context（消除四套不一致）；
- "AI 这次看到了什么"可复现；
- 检索：按**当前实际存在的索引类型**（`NOVEL` / `CHAPTER` / `DRAFT` / `STORY_FOUNDATION` / `VOCABULARY`）结构化取回（`Character` / `Event` / `Timeline` / `Foreshadowing` 待 P2 / P3 提供 Canonical 来源后补齐，见第 8 项）；
- **按任务取用**（不是默认全量读取）。

**11. 本 Phase 的最小 Vertical Slice**：

```text
一句任务（如"继续写第 89 章"）→ ContextRequest → ContextEngine → 检索到当前章 + 前文 + 人物状态
→ ContextSnapshot → 交给 Agent（此时 Agent 可以是受控的固定调用）
```

**12. Completion Gate**（**正向 + 负向，二者同时成立**）：

- **正向 Gate**：Context 入口数 = **1**；Snapshot 可复现；**结构化 Retrieval 覆盖当前实际存在的索引类型**——`NOVEL` / `CHAPTER` / `DRAFT` / `STORY_FOUNDATION` / `VOCABULARY`；**`Character` / `Event` / `Timeline` / `Foreshadowing` 四类索引暂不作为本 Phase Completion Gate 的要求**（待 **P2 / P3** 提供相应 Canonical 来源并形成实际可用的数据来源后，在后续 Phase 补齐）；budget / 优先级（既有 `ContextPriorities`）仍生效；**未超范围扩 Storage**。
- **负向 Gate**：**未引入 §25 禁止的检索能力**，且**必须可机械验证**：
  1. **Storage 检查**：`storage/**/*.sq` 中**不得**出现 FTS5 虚拟表定义（检索用途的 `CREATE VIRTUAL TABLE ... USING fts5`）；
  2. **Embedding / Vector 检查**：代码与 Storage 中**不得**出现 embedding 表定义、vector 表定义、vector index 定义、embedding provider 调用、vector database 接入；
  3. **构造性测试**：若现有测试结构允许，注入 mock FTS / embedding / vector 依赖时 **P4 Completion Gate 必须失败**；若当前测试架构不适合，**不为该门重构测试系统**——至少必须保留上述可机械扫描的 Storage / Code 检查。
- **判定式**：`正向 Gate AND 负向 Gate`（即：当前实际存在的索引类型已覆盖 **且** §25 禁止的检索能力未被引入）。**不得**把"没有偷偷引入"写成不可验证的空话。

> 本 Gate 只是**可达性修正 + 防偷偷引入**，**不是 FD-10 解封**（重新评估条件见 §23A）。

**13. 下一 Phase 的输入**：稳定、可复现的 Context → Phase 5/6/7 有真实输入。

---

## 14. Phase 5 — Novel Tool

**1. 目标**：**把 Novel Tool Layer 接入 Qianyan**（Tool Runtime 可用），**不要求**每个 Tool 都有生产 caller，**不要求** Agent 自主 Tool Calling。

**2. 在架构总图中的位置**：`Novel Agent → Skills / Tools → Execution → Domain`。

**3. 当前代码真实状态**：`agent/tool` 已有契约 / Registry / Executor / 类型化错误（**通用运行时已存在，勿重建**）；7 个只读 Tool 在 `application/.../usecase/tool/ProductTools.kt`；唯一 invoke 点在不可达路径；`AgentRuntime.kt:84` 有一条**绕过 ActionPolicy 与 ToolCallLog** 的执行路径。

**4. Gap**：工具集合不全；**结果无回流**；**旁路未收口**；权限未覆盖。

**5. 本 Phase 要连接什么**：

```text
NovelTool Contract → Tool Registry → Tool Executor
→ Tool → Application / Domain（经 UseCase / Repository）
→ Tool Result（返回上层调用者）
→ Policy / Permission
```

**6. 不做什么**（**本阶段的关键边界**）：
- **不要求**"每个 Tool 必须有生产 caller"（那是 Phase 7 的事）
- **不要求** Agent 自主 Tool Calling（Phase 7）
- 不新建第二套 Tool Registry / Executor
- 不让 Tool 直接修改 Canonical、不绕过 Change Layer
- 不让 Tool 直连 SQLite / Repository 实现（必须经 Application / Domain）

**7. 涉及模块 / 文件**：`agent/tool/src/main/kotlin/com/qianyan/agent/tool/*`、`core/model/.../tool/ToolModels.kt`、`application/.../usecase/tool/{ProductTools,ProductToolService,ReadOnlyProductTool}.kt`、`agent/runtime/.../AgentRuntime.kt`、`application/.../usecase/action/ActionPolicyUseCases.kt`。

**8. API / Contract**：至少建立并可运行：

```text
NovelTool · ToolRegistry · ToolExecutor · ToolResult · ToolCallLog
```

保证：Tool 可真正执行 · 有明确输入输出 · 有权限边界 · 不直接改 Canonical · 不绕过 Change Layer · Result 可返回上层。

**9. Storage / Migration**：**不需要**（只读 Tool；`ToolCallLog` 表已存在）。

**10. 测试**：
- 每个**已实现** Tool：可执行 + 输入输出明确 + 权限边界生效（**不要求生产 caller**）；
- `ToolResult` 能被上层调用者取到（用测试内的显式调用者验证）；
- **旁路收口断言**：`ToolExecutor.execute` 只能经 `ProductToolService` 被调用（守卫测试）；
- `ToolCallLog` 在有调用时被写入（管道齐备性验证，不要求 UI 展示）。

**11. 本 Phase 的最小 Vertical Slice**：

```text
Caller（测试内显式调用者）→ Tool → Application / Domain → ToolResult 返回
```

**12. Completion Gate**：Tool Runtime 五件套齐备且可运行；权限边界生效；无旁路；**不要求 Agent 自主调用**；不越权改 Canonical。
> **P5 与 P7 的明确分界**：P5 完成 = **Tool Runtime ready**（调用者可以是 Unit / Integration / Application Test Caller）；P7 完成 = **Agent 能自主选择并调用 Tool、并消费 Result**。P5 **不得**把"Novel Agent → Tool → Result → Novel Agent"当成本阶段必须达成的生产链。

**13. 下一 Phase 的输入**：可用的 Tool Runtime → Phase 6 的 Skill 可以调用 Tool；Phase 7 的 Agent 可以做 Tool Calling。

---

## 15. Phase 6 — Novel Skill（核心写作优先）

**1. 目标**：建立**能进入生产调用链**的 Skill 层；**核心写作能力优先**。

**2. 在架构总图中的位置**：`Novel Agent → Skills → Context Engine / Novel Tools → Execution → Result / Draft / Proposal`。

**3. 当前代码真实状态**：`CoreSkills` 硬编码 5 个；`SkillRegistry.selectSkill` 用静态偏好表；`Skill.requiredContext / expectedActions / bindings / capabilities` 无执行读取者；9 项能力中只有"文风保持"真连通（靠 prompt 注入），"长篇续写 / 改写 / 剧情规划 / 小说分析"部分存在，"人物一致性 / 伏笔管理 / 场景设计 / 节奏控制"不存在。

**4. Gap**：契约未被消费；能力与 Skill 未对齐；核心写作 Skill 未生产化。

**5. 本 Phase 要连接什么**：

```text
Task → Skill → Context Engine → Novel Tools → Execution → Result / Draft / Proposal
```

**6. 不做什么**：
- **不做 Skill 页面**（Skill 不是 UI）
- **不用"小说分析"替代核心写作 Skill**（易实现 ≠ 核心）
- 不把 9 个 Skill 一次做完（禁止能力堆叠）
- Skill 不直接操作 Storage

**7. 涉及模块 / 文件**：`core/model/.../skill/SkillModels.kt`、`agent/agents/.../{CoreSkills,SkillRegistry}.kt`、`application/.../usecase/agent/NovelAgent.kt`（`selectSkill` / `executeSkill`）、`usecase/writing/{WriterAgent,PlannerAgent}.kt`、`usecase/writing/{critique,revision,knowledgeupdate}/*`。

**8. API / Contract**：**固化 Skill 契约**（`purposes / allowedTools / requiredContext / expectedActions / bindings` 必须**被读取**，否则字段无意义）；声明与执行绑定必须一致（消除 `SKILL_NOT_EXECUTABLE` 陷阱）。

**9. Storage / Migration**：**不需要**（Skill 是编排层，不是数据）。

**10. 测试**：
- **核心写作 MVP Skill 的两个必须端到端**（非 mock）：
  1. **长篇续写（LongContinuation）**
  2. **人物一致性（CharacterConsistency）**
- 其余 7 个：**只在契约层声明**，不实现、不假装完成；
- 契约字段"被读取"的守卫测试。

**11. 本 Phase 的最小 Vertical Slice**：

```text
Task → Skill（长篇续写）→ Context → Tool → Execution → Result
```

**12. Completion Gate**：**长篇续写 + 人物一致性**两个 Skill 进入真实生产调用链（有 caller、有 Context、有 Tool、有结果）；Skill 契约字段全部有读取者；**没有"只有 interface + registry + mock test"的假完成**。

**13. 下一 Phase 的输入**：可被 Agent 选择与执行的 Skill → Phase 7 的 Agent 有"能力"可调度。

---

## 16. Phase 7 — Natural Language + Novel Agent

**1. 目标**：建立**自然语言任务入口**与**真正的 Agent Decision Point**（不是固定 Pipeline 换名）。

**2. 在架构总图中的位置**：`Natural Language Task → Novel Agent → Context / Retrieval / Skill / Tool → Execution → Observation → 下一步决策`。

> **方向约束**（§2 硬规则在本 Phase 的落点）：Agent **消费 Context**；Context 由 `World Model / Memory → Context Engine → Structured Retrieval → Context Snapshot` 产出。
> **Agent 不直接读取 World Model**，不得出现 `Novel Agent → World Model → Context`。

**3. 当前代码真实状态**：**无自然语言入口**；意图硬编码 `IntentType.CONTINUE`；`NovelAgent.analyzeIntent` 是关键词规则且不可达；生产链是固定 pipeline（`guard<16`、相位硬编码、后继表硬编码）；`AgentRuntime` while-loop 被空注册表废成单次调用。

**4. Gap**：缺入口；缺 Intent；**缺 Decision Point**。

**5. 本 Phase 要连接什么**：

```text
Natural Language Task → Intent → Novel Agent
→ Agent 判断需要什么：
   ├── Context（由 Context Engine 提供；含 World Model / Memory 经 Structured Retrieval 后的 Context Snapshot）
   ├── Skill
   └── Tool
→ Execution → Observation / Result → Agent 下一步决策
```

**6. 不做什么**（**本阶段的关键判据**）：
- **不得**只有固定顺序的 `Intent → Research → Planning → Writing → Critique → Revision`
- 即使第一版能力有限，**也必须存在"Agent 根据任务决定下一步行动"**
- 不接入 DSH（Phase 9）
- 不做多 Agent 协作 / Subagent

**7. 涉及模块 / 文件**：`application/.../usecase/agent/NovelAgent.kt`、`usecase/workflow/WorkflowOrchestrator.kt`、`usecase/writing/planning/*`、`agent/{runtime,agents}`、UI 入口 `app/desktop/.../ui/pages/*`（属 Prototype 轨）。

**8. API / Contract**：新增「任务入口」契约 `NaturalLanguageTask → Intent → AgentRun`；`NovelAgent` **二选一**：转为生产可用，**或**明确废弃 —— **不得两条并行**；Agent 动作必须经 `ActionPolicyUseCases`（当前 `NeedsHuman` 分支从未被触发）；不确定时**回问作者**，不得静默猜。

**9. Storage / Migration**：复用既有 `Task` / `Checkpoint` / `AgentSession`；运行可审计记录复用 `Activity`。

**10. 测试**：
- 5 类真实语句（继续写 / 修改 / 查询 / 检查 / 规划）分别触发正确的 Skill/Tool 组合；
- **Decision Point 断言（本阶段的核心验收，必须是可测行为而不是 API 存在性）**：

```text
同一个 Task：
  前一步 Result = X  → Agent 下一步选择 A
  前一步 Result = Y  → Agent 下一步选择 B
```

  即：**Agent 的下一步行为必须受前一步 Tool / Skill / Context Result 影响**。
  测试写法要求：同一输入 Task、只替换第一步返回的 Result（X / Y 两份 fixture），断言第二次动作**不同**；
  仅证明「存在 Decision API / Branch API」**不构成**本项通过。
- 旧硬编码意图路径已消除（守卫测试）；
- `Activity` 有真实写入。

**11. 本 Phase 的最小 Vertical Slice**：

```text
Author → 自然语言任务 → Agent → （决定）读 Context + 调 Tool / Skill
→ Execution → 结果 → Agent 决定下一步（或给出结果）
```

**12. Completion Gate**：自然语言入口可用；**Agent Decision Point 存在且可测**——至少包含第 10 项的「同一 Task，Result X → A / Result Y → B」行为测试一条；≥2 类任务在生产链跑通；无硬编码意图；动作受 ActionPolicy 约束；每次运行可审计。**缺少该测试即视为 P7 未完成**（防止旧固定 Pipeline 换名）。

**13. 下一 Phase 的输入**：可自主决定下一步的 Agent → Phase 8 可跑完整闭环。

---

## 17. Phase 8 — Complete Writing Vertical Slice（完整闭环验收）

**1. 目标**：把全部节点串成**一条完整闭环**并验收。**本 Phase 不是"第一次运行整个系统"**（每个前序 Phase 已有各自最小 slice）。

**2. 在架构总图中的位置**：全部节点（§2 完整图）。

**3. 当前代码真实状态**：见 §5 / §6（此阶段预期：Phase 1–7 完成后各段可达）。

**4. Gap**：段与段之间的**接口缝隙**（各 Phase 自己的 slice 通了，但整链未必通）。

**5. 本 Phase 要连接什么**：

```text
Author → Natural Language Task → Intent → Novel Agent
→ Context（其上游为：World Model / Memory → Context Engine → Structured Retrieval → Context Snapshot）
→ Skill / Tool → Execution → Working Draft → Diff → Change
→ Human Gate → Commit → Canonical → World Model Update
→ Next Context → Novel Agent → Result / Proposal
```

> **不得写成** `Novel Agent → World Model → Context`，也不得写成 `Agent → World Model → Retrieval`。
> Agent 只消费 Context；World Model 是 Context Engine 的上游数据源，不是 Agent 的数据库。

**6. 不做什么**：不新开功能；不引入禁止项；不便通（be通）语义。

**7. 涉及模块 / 文件**：横跨 `app/desktop`、`application`、`agent/*`、`storage`、`core/*`（只做端到端验收与必要缝隙收口）。

**8. API / Contract**：全部既有契约；**本 Phase 不得新增顶层契约**（§2 原则的反向检验）。

**9. Storage / Migration**：不需要新迁移（验收）。

**10. 测试**：
- 1 个**完整闭环端到端测试**（LLM 用 Mock / Offline 网关保证确定性）；
- **每段"断点回归"断言**（防回退）：任一节点断开时测试必须失败；
- 三条边界测试全绿。

**11. 本 Phase 的最小 Vertical Slice**：**就是完整闭环本身**（§21）。

**12. Completion Gate**：§21.1 的 17 段全部为 ✓（无 △ / → / ✗）；三边界全绿；禁止项零引入；每个前序 Phase 的最小 slice 仍绿。

**13. 下一 Phase 的输入**：稳定的完整闭环 → Phase 9 才值得把 Runtime 换成 DSH。

---

## 18. Phase 9 — DSH Runtime Adapter

**1. 目标**：Agent 跑在外部 Runtime 上，**小说业务层零改动**。

**2. 在架构总图中的位置**：`Novel Agent → Qianyan Runtime Adapter → DSH`（**与业务层并列，不嵌入**）。

**3. 当前代码真实状态**：适配器（`runtime/api` 契约 + `runtime/dsh` ACP + `runtimeintegration` + `RuntimeSessionRef`）**只在 `feature/runtime-dsh@9a6f7c0`**；工作树 `:runtime` 是空壳；真实 DSH 曾 PASS（`DSH_POC_OK`）；**产品动机在正式文档中仍无锚点**。

**4. Gap**：契约未落地主线；能力协商缺失；`embeddedContext=false` 与结构化 Context 口径冲突；`shutdown` 无生产调用者（**接线即泄漏**）。

**5. 本 Phase 要连接什么**（总图 §14 十步，顺序不变）：

```text
① RuntimeAdapter ② Agent Session Contract ③ Tool Call Contract ④ Tool Result Contract
⑤ 单 Tool ⑥ Agent Loop ⑦ Session ⑧ Permission ⑨ Resume / Trace ⑩ 替换内部 Runtime
```

**6. 不做什么**（**本次重点重申**）：DSH **不得反向定义**：

```text
Novel Project · Canonical Story · World Model · Change Layer · Novel Skill · Novel Tool Contract
```

DSH 是 **Runtime**，不是 **Novel Domain**。

**7. 涉及模块 / 文件**：`runtime/api/.../contract/AgentRuntimeGateway.kt`、`runtime/dsh/.../*`、`application/.../usecase/runtimeintegration/*`、`storage/.../RuntimeSessionRef.sq`、`settings.gradle.kts`、`app/desktop/.../di/DesktopGraph.kt`（应依赖 `runtime:api`，不直接 `new` 具体适配器）。

**8. API / Contract**：契约先行、**vendor-neutral**（含错误文案）；Permission 语义 = **只在 Qianyan 层问人一次**；`resume` 语义需真实（当前无条件抛 `NotAvailable`）。

**9. Storage / Migration**：`RuntimeSessionRef`（分支已有）；如需能力协商则记录能力快照。

**10. 测试**：单 Tool 经 DSH 往返；Permission 映射；Resume；`shutdown` 可达且无子进程泄漏；**契约 vendor-neutral 断言**。

**11. 本 Phase 的最小 Vertical Slice**：

```text
Novel Agent → Runtime Adapter → DSH → 单 Tool 调用 → Tool Result 回到 Agent
```

**12. Completion Gate**：Runtime Adapter 落地主线；单 Tool + Agent Loop 经 DSH 跑通；边界三测试全绿；**产品动机裁决已给出**（否则本 Phase 不得启动）。

**13. 下一 Phase 的输入**：Runtime 可替换 → Phase 10 只做产品体验，不碰运行方式。

---

## 19. Phase 10 — Product UI

**1. 目标**：PC / Android 作为 **Product Experience Layer** 消费稳定的 Application API。

**2. 在架构总图中的位置**：`Author → Qianyan UI / Client → Application API → Novel Agent / UseCase → Context/Skill/Tool → Domain`。

**3. 当前代码真实状态**：Android Writer = 完整受控闭环；Desktop 写作链可用（I14 未提交）；桌面 **8 处直连仓储**；Android Reader 读不到 TXT 导入章节；Prototype 信息架构**尚未拍板**。

**4. Gap**：自然语言入口未接 UI；Diff / Proposal / 历史未展示；按需内容未成型；导入路径断裂；直连仓储未收口。

**5. 本 Phase 要连接什么**：

```text
UI → Application / UseCase → Agent / Domain
（两端共用一套业务逻辑）
```

**6. 不做什么**：
- UI **不得**直接操作：`Repository` / `Storage` / World Model DB / DSH / Tool Registry
- 不因 UI 设计反向修改核心领域架构
- 不做 Skill / Tool / Runtime / MCP / Dashboard 页面
- 不擅自拍板信息架构（保持"尚未确定"）

**7. 涉及模块 / 文件**：`app/desktop/src/jvmMain/**`（含 `ui/pages/{ListPages,StoryWorldPages,MaterialPages}.kt` 去直连）、`app/android/src/main/**`、`application/.../di/ApplicationContainer.kt`、`storage/.../repository/SqliteBackupStore.kt`（Backup 作为产品化项在此收口）。

**8. API / Contract**：UI 只能依赖 `ApplicationContainer` 暴露的 UseCase / Gateway；**禁止**新增 UI 专用业务规则；两端同一用例行为一致。

**9. Storage / Migration**：不需要（沿用）。

**10. 测试**：UI 直连仓储 = 0；两端同一用例一致性；Reader 可读 TXT 导入章节（若导入连接已在 Phase 1 落地）；**不要求**全量新 UI 测试。

**11. 本 Phase 的最小 Vertical Slice**：

```text
UI → Application → 一条真实任务完成（不经过任何 Repository 直连）
```

**12. Completion Gate**：两端只经 Application API；无第二套业务逻辑；直连仓储 = 0；信息架构仍由产品裁决（未擅自拍板）。

**13. 下一 Phase 的输入**：无（收口阶段）。

---

## 20. 架构依赖图（Phase 依赖关系）

```text
Preflight / Baseline（Phase 0）
   │
   ├──→ Phase 1 Change Layer（建立 Canonical + Commit 事件）
   │        │
   │        ├──→ Phase 2 World Model（最小可生产；可与 P1 部分并行）
   │        │        │
   │        │        └──→ Phase 3 World Model Update ←── 需要 P1 的 Commit 事件
   │        │
   │        └──→ Phase 4 Context + Retrieval ←── 需要 P2 的读侧
   │                 │
   │                 ├──→ Phase 5 Novel Tool
   │                 │        │
   │                 │        └──→ Phase 6 Novel Skill
   │                 │                 │
   │                 │                 └──→ Phase 7 NL + Novel Agent
   │                 │                          │
   └─────────────────┴──────────────────────────┴──→ Phase 8 Complete Vertical Slice（验收门）
                                                        │
                                            ┌───────────┴───────────┐
                                            ↓                       ↓
                                     Phase 9 DSH Runtime      Phase 10 Product UI
                                     （前置：动机裁决）        （前置：Prototype 结论）

并行轨：UI Prototype（Phase 0 起，仅验证"客户端感觉"）
```

**硬依赖**：
`P1 → P3`（Commit 事件）、`P2 → P3`（写入目标）、`P2 → P4`（读侧）、`P1 → P4`（Canonical 判定）、`P4 → P5`、`P5 → P6`、`P6 → P7`、`P7 → P8`、`P8 → P9`、`P8 → P10`。

**可并行**：P1 与 P2（不同文件面）；UI Prototype 与全部 Phase。

---

## 21. MVP Vertical Slice

### 21.1 完整闭环（Phase 8 验收对象）

**用户语句**：「继续写这一章 3000 字，保持人物状态和前文一致。」

**完整闭环固定为 17 段**（以下顺序即验收顺序，不得增删段、不得改段名）：

| 段 | 环节 | 现状 | 负责 Phase |
|---|---|---|---|
| 1 | Author | ✓ | — |
| 2 | Natural Language Task | ✗ | P7 |
| 3 | Intent | ✗ | P7 |
| 4 | Novel Agent（第一次：决定需要什么） | △（固定 Pipeline、无 Decision Point） | P7 |
| 5 | Context / Retrieval | △（Context 四套并存、Retrieval 内存占位） | P4（上游 World Model 读侧 P2） |
| 6 | Skill / Tool | ✗（Skill 契约无消费者；Tool Runtime 有、无人调用） | P6 / P5 |
| 7 | Execution | △ | P6 / P7 |
| 8 | Working Draft | ✓ | P1 纳入 |
| 9 | Diff | ✗（`TextDiffer` 孤立） | P1 |
| 10 | Change | ✗（仅测试级） | P1 |
| 11 | Human Gate | ✓（P0-3 后桌面可决议） | P1 统一 |
| 12 | Commit | ✗ | P1 |
| 13 | Canonical | △（隐式 = latest Draft） | P1（`CanonicalRead`） |
| 14 | World Model Update | ✗ | P3 |
| 15 | Next Context | ✗ | P4（第二轮 Context） |
| 16 | Novel Agent（第二次：基于新 Context 收敛） | △ | P7 |
| 17 | Result / Proposal | △（有正文产出，但无正式 Result 契约回流） | P6 / P7 |

**图例**：✓ 已有 · △ 部分 · → 需连接 · ✗ 尚不存在

**关于段 5 的方向说明**：World Model 不是 Agent 的直连数据源，而是段 5 的**上游**（`World Model / Memory → Context Engine → Structured Retrieval → Context Snapshot → Novel Agent`），因此不单列一段，其读侧状态计入段 5、由 P2 负责。

### 21.2 每 Phase 的最小 Vertical Slice（对齐输入 §十 要求）

| Phase | 最小 Vertical Slice |
|---|---|
| 0 | `Git baseline → 构建 → 测试基线可判定（失败有 A/B/C/D 归属）` |
| 1 | `AI 产出 → Working Draft → Diff → Change → Human Gate → Commit → Canonical` |
| 2 | `（fixture / Canonical 数据，write callers = 0）写一条 World Model 事实 → 读回 → 字段完整 → Variant 隔离成立` |
| 3 | `Commit → 抽取候选 → 比较 → Accepted 写入 / Conflict 待人审（不覆盖）` |
| 4 | `一句任务 → ContextRequest → ContextEngine → 检索 → ContextSnapshot` |
| 5 | `Caller → Tool → Application/Domain → ToolResult` |
| 6 | `Task → Skill（长篇续写）→ Context → Tool → Execution → Result` |
| 7 | `NL → Agent → 决定读 Context / 调 Tool → 结果 → Agent 决定下一步` |
| 8 | 完整闭环 17 段（§21.1） |
| 9 | `Novel Agent → Runtime Adapter → DSH → 单 Tool → Tool Result` |
| 10 | `UI → Application → 一条真实任务（零 Repository 直连）` |

---

## 22. Architecture Alignment Matrix

| 架构节点 | 对应 Phase | 是否已有生产链路 | 本计划负责什么 |
|---|---|---|---|
| Canonical | **P1** | 当前不完整（= latest Draft） | 建立正式 Canonical（Commit 产物） |
| World Model | **P2** | 部分（表在、写侧空、读侧丢弃） | 建立最小生产模型 + 补字段 + 明确事实源关系 |
| World Model Update | **P3** | 缺失 | 建立更新闭环 + 冲突生命周期 |
| Context Engine | **P4** | 重复实现（四套） | 收敛为唯一入口 |
| Retrieval | **P4** | 占位（内存 map） | 接入真实结构化检索（**当前实际存在的索引类型**：`NOVEL` / `CHAPTER` / `DRAFT` / `STORY_FOUNDATION` / `VOCABULARY`；`Character` / `Event` / `Timeline` / `Foreshadowing` 待 P2 / P3 Canonical 来源后补齐） |
| Novel Tool | **P5** | 占位（Runtime 有、无人调用、有旁路） | 建立 Tool Runtime 并收口旁路 |
| Novel Skill | **P6** | 占位（契约无消费者） | 建立生产 Skill（核心写作两个优先） |
| Novel Agent | **P7** | 固定 Pipeline（无 Decision Point） | 建立真正 Agent Decision |
| Draft | **P1**（纳入）/ **P8**（验收） | 已存在 | 纳入正式 Change Loop |
| Diff | **P1** | 孤立（`TextDiffer` 不被调用） | 接入 Change |
| Change | **P1** | 测试级 | 接入生产链路 |
| Human Gate | **P1** | 部分（Android 可用、桌面刚接通） | 正式接入并统一 |
| Commit | **P1** | 孤立 | 建立 Canonical Commit |
| DSH | **P9** | Runtime 部分存在（在侧分支） | Adapter 接入主线 |
| UI | **P10** | Desktop 部分存在 | 最终 Product Experience Layer |
| **Memory**（AUTHOR / AGENT MEMORY） | **P2 口径裁决 + P3 复用** | 已存在（唯一活着的领域写入） | 明确"工作记忆，非事实源"；不新建第二 Canonical |
| **Runtime Adapter**（Qianyan 侧契约） | **P9** | 缺失（仅在侧分支） | 契约先行、vendor-neutral |
| **Skills / Tools → Execution** 之间的编排 | **P6 → P7** | 缺失 | Skill 可执行 → Agent 可调度 |

### 22.1 无 Phase 负责 / 重复负责检查（对齐输入 §十七 要求）

| 检查 | 结论 |
|---|---|
| 是否有架构节点**没有 Phase 负责**？ | **无**。15 个节点（+ Memory / Runtime Adapter / 执行编排）全部有归属。 |
| 是否有节点**被两个 Phase 重复负责**？ | **无重复"负责"**，但有两组**显式的跨 Phase 交接**，已写明：<br>· `Draft`：**P1 纳入**（进入 Change Loop）+ **P8 验收**（闭环跑通）—— 职责不同，非重复实现。<br>· `Human Gate`：**P1 统一接入**（PC/Android 同一套）+ **P7 触发**（Agent 动作经 ActionPolicy）—— 一个是接入、一个是调用方。 |
| 是否存在**两个 Phase 各建一套**同类东西？ | **无**。已明令禁止第二套 Canonical / World Model / Context / Draft / Gate / Tool Registry / Orchestrator（§25）。 |

---

## 23. 风险矩阵

| ID | 风险 | 概率 | 影响 | 缓解 | 状态 |
|---|---|---|---|---|---|
| **R1** | 总图 §8 第二阶段（Text / Semantic Search）与 FD-10 排除边界（全文搜索 / RAG / Vector / **Embedding**）的关系 | 高 | 高 | FD-10 当前作为**阶段性 Scope Boundary**（**生效中**）；**升级评估条件见 §23A** | **FINALIZED**（方案 A：保持 FD-10 语义 + 增加 Evidence-Based Reassessment Criteria；**FD-10 未解封**） |
| **R2** | Memory 与 World Model 谁 canonical | — | — | 已由本次审查裁决：**`Canonical → World Model` 唯一事实关系；Memory = 工作记忆，非事实源** | **已关闭**（§3） |
| **R3** | Phase 1 收口后，既有 `latestByChapter` 消费者（Reader / ListPages / Android Reading）会读到非正典内容 | 高 | 高 | P1 已定义 **`CanonicalRead` = 正式 Canonical 读取入口**；「哪些场景读 Canonical / 哪些场景读 Draft」**见 §23B** | **FINALIZED（决策已落成，见 §23B）** |
| **R4** | 未提交工作（P1-01 / P0-3 / I14）与 Phase 0 / 1 强耦合 | 高 | 中 | Preflight 登记边界；**不得覆盖** | 由 P0 处置 |
| **R5** | `24.sqm` 编号归位方式影响未提交的 P1-01 | 中 | 中 | 只改编号与期望，不改 SQL 语义 | 由 P0 处置 |
| **R6** | 8 个 Windows DB lock 失败（环境问题） | 中 | 中 | 按 §24 归入 **C 类（环境）**，单独记录 | 已分类 |
| **R7** | DSH 产品动机仍无锚点 | 高 | 中 | Phase 9 前置门 = 动机裁决；动机未落地前 P9 不得开工 | **UNRESOLVED（需用户裁决）** |
| **R8** | Prototype 被用来倒推 UI 信息架构 | 中 | 中 | 严格遵守"IA 尚未拍板" | 约束 |
| **R9** | Agent 引入后绕过 ActionPolicy / 绕过 Human Gate | 中 | 高 | P1 负向断言 + P5 旁路守卫 + P7 权限测试 | 约束 |
| **R10** | 收敛 Context 时引入第三 / 第四套 | 中 | 中 | P4 出口门"入口数 = 1" | 约束 |
| **R11** | DSH 只收纯文本（`embeddedContext=false`）与结构化 Context 口径冲突 | 中 | 中 | P9 前定"序列化契约" | 约束 |
| **R12** | 为"完整"提前创建大量空表 / 提前扩 Storage | 中 | 中 | P2 只做 MVP 最小集合；P4 Snapshot 先运行时 | 约束 |

**未决事项处置规则**：**R7** 属用户裁决项（**R3 已 FINALIZED**，见 §23B），本计划**不替用户拍板**，在 v3.1 中保持 **UNRESOLVED**。
（**R1 已 FINALIZED**：采用方案 A——保持 FD-10 语义 + 增加 Evidence-Based Reassessment Criteria，见 **§23A**；FD-10 仍**生效中**，**未解封**。）
实施过程中若出现「已经按某一方做下去了」的代码或文档，视为**越权解决**，必须回退为待裁决状态并重问用户。

---

## 23A. R1 — FD-10 升级评估条件

> **R1 状态：FINALIZED**（采用**方案 A**：**保持 FD-10 当前语义 + 增加 Evidence-Based Reassessment Criteria**）。
> **R1 FINALIZED ≠ FD-10 解封。** FD-10 仍为**生效中**的阶段性 Scope Boundary。

### 23A.1 FD-10 的语义（唯一口径）

```text
FD-10 = 当前阶段的 Scope Boundary / Exclusion Boundary
```

- **是**：定义**本阶段暂不实施**的高级检索能力（全文搜索 / RAG / Vector / Embedding）。
- **不是**：永久锁 / Master Switch / 多级开关系统 / 自动解锁机制。
- **当前仍不解封**：Full-text Search · Semantic Retrieval · RAG · Vector · Embedding —— **全部保持不实施**。
- **原始来源**：`docs/architecture/p20-architecture-freeze.md`（FD-10 · Scope 限制，状态「**生效中**」）。该文件另有明文出口：「**这些能力只能在后续阶段按各自范围单独决策与交付**」。
- **措辞纪律**：**不得**再用「封存 / 已封存 / 尚未解封 / LOCKED / 永久禁止」描述 FD-10 的当前状态；统一使用 **「FD-10 生效中」** 或 **「FD-10 排除边界」**。

### 23A.2 重新评估的总原则

> 不是「感觉应该上 RAG 了」，而是**已有生产证据表明当前 Retrieval 能力出现结构性瓶颈**。

评估必须沿同一结构（不可省略任何一步）：

```text
事件（可观察）
   ↓ 指标（可测量）
P4 出口建立 baseline
   ↓ threshold（在 baseline 之后确定）
是否达到 FD-10 重新评估条件
```

**数字纪律**：本文档**不得**冻结任何 `N` / `X` / `Y` / `p95` / coverage 数值；所有阈值一律标注「**P4 出口建立 baseline 后确定**」。

### 23A.3 A 类 — 任务失败（**必须区分 A1 / A2**）

| 子类 | 定义 | 是否触发 FD-10 重新评估 | 归属 |
|---|---|---|---|
| **A1 · 真正的 FD-10 能力边界** | 任务失败的原因是**任务需要 FD-10 当前排除的检索能力**：需要全文搜索定位正文中的某段内容；需要语义检索寻找**相关但没有结构化索引**的段落；需要依赖 Vector / Embedding / RAG 才能完成的检索任务 | **是**（升级评估候选） | FD-10 重新评估 |
| **A2 · 已有能力未接入生产 Context** | 数据**已经存在**但未进入 Context：人物状态已在 World Model 却未进 Context；事件已在 Story State 却未进 Prompt / ContextPack；Timeline 已存在但 `ContextEngine` 未读取；Foreshadowing 已存在但未进当前 Context | **否** | **P4 Context / Retrieval 实现缺口** |

```text
A1 → FD-10 升级评估候选

A2 → P4 实现缺口
        ↓
     修 Context / Retrieval
        ↓
     不解封 FD-10
```

**判定要求**：必须能回答「这条任务是否**只有** FD-10 排除的能力才能完成」。若用**现有结构化检索 + 已接入的 Context** 即可完成 ⇒ 归 **A2**。

### 23A.4 B 类 — Agent 事实错误

**必证三件套**：

```text
错误样本  +  ContextPack 快照  +  Canonical 对照
```

- **可复现性**：在**相同 ContextPack / 相同 `packVersion`** 条件下问题可以复现；
- **排除模型随机性**：必要时通过**更换模型**确认问题来自 **Context 缺失 / Retrieval 错误提供**，而不是单纯模型随机错误；
- **归因条件**（三条同时成立才计入 B）：`Canonical 中存在该事实` **且** `Context 未提供 / Retrieval 错误提供` **且** `Agent 因此产生错误`。

**边界（必须写明）**：

- **普通 LLM 幻觉不自动归入 B 类**；
- **B 类不是 FD-10 的「豁免」**：B 类证据本身**不会自动绕过 FD-10**；只有满足既定条件后，才**进入 FD-10 重新评估**；
- 若实际原因是「World Model 已有事实，但尚未接入 Context」⇒ 按 **A2 / P4 实现缺口**处理，**不触发** FD-10 重新评估。

### 23A.5 C 类 — Context / Retrieval Coverage

```text
coverage = |ContextPack.items ∩ 任务必需对象| / |任务必需对象|
```

- **当前不得凭空冻结 X / N**；流程固定为：**P4 出口 → 建立 Coverage Baseline → 确定 Threshold → 后续按 Threshold 判断**；
- **Miss 定义**：**Canonical 中存在、但 ContextPack 无该对象**；
- 现有可用观测通道：`WorkspaceUseCases` 的 `notices`（显式记录"没有稳定来源的关联"，例如"某人物出现的章节"）。

### 23A.6 D 类 — Performance / Context Budget

- 至少覆盖：**Retrieval / Context Build 延迟**（可用 **p95 latency** 等指标）、**高优先级 Context 因 Budget 被截断**；
- **具体阈值（如 Y）必须在 P4 出口建立 baseline 后确定**；
- **当前没有 baseline ⇒ 只定义「如何测量」与「何时建立 baseline」，不定义伪造阈值**。

### 23A.7 E 类 — 真实用户任务需求

- 必须是**可重复的真实用户任务需求**：同一类需求在**多个独立真实会话**中持续出现；
- 可用形式：`同类需求 ≥ N 次独立会话`；
- **N 当前不冻结**，应在真实产品验证 / P4 baseline 阶段确定。

### 23A.8 F 类 — 工程复杂度（**仅辅助信号**）

> **F 类（工程复杂度）仅作为辅助信号，不得单独触发升级评估，必须与 A–E 中至少一类同时成立。**

可包括：结构化字段越来越多；Index 数量增加；Migration / Backfill 复杂度增加；Context 构建维护成本增加；当前 Structured Retrieval 工程维护成本持续上升。

```text
F  ≠  独立升级条件

F  +  A / B / C / D / E 至少一类
      ↓
才可进入 FD-10 重新评估
```

### 23A.9 措辞纪律（可测量性）

**不得**把「稳定出现 / 持续失败 / 明显不足 / 很慢 / 用户觉得不好用」用作**最终规则**——它们只能作为描述。最终规则必须落到：

```text
事件 → 指标 → P4 baseline → threshold → 是否达到 FD-10 重新评估条件
```

### 23A.10 与其它条款的关系

| 关联 | 关系 |
|---|---|
| §25 明确禁止项 | FD-10 的排除边界在此被引用为**当前禁止**；**未引入**该能力由 §13（Phase 4）的**负向 Gate** 保证 |
| §13 Phase 4 | **正向 Gate**（覆盖当前实际存在的索引类型）+ **负向 Gate**（未引入 §25 禁止的检索能力，可机械验证） |
| §23 风险矩阵 | R1 行只保留简短指向（升级评估条件在本节） |
| R3 / R7 | **R3 已 FINALIZED**（读策略见 §23B）；**R7 仍为 UNRESOLVED** —— 二者均与本节的重新评估条件无关 |

---

## 23B. R3 Canonical / Draft Read Policy

> **R3 状态：FINALIZED** —— 「Canonical / Draft 到底分别被哪些场景读取」的决策**已正式落成并写入本文档**。
> **R3 FINALIZED ≠ 读取行为已经实现**：`CanonicalRead` 落地、Reader / Chapter List / Android Reading 的读取切换属**后续 Phase 的代码工作**（见 §23B.6）。

### 23B.1 场景决策表（谁读什么）

| 场景 | 读取对象 | 原则 |
|---|---|---|
| Reader | **Canonical** | 正式阅读看到的是**已提交**的小说事实 |
| Chapter List（桌面 ListPages） | **Canonical** | 章节列表面向**正式小说状态** |
| Android Reading | **Canonical** | 正式阅读**不读取未提交 Draft** |
| 作者编辑（Editor） | **当前 Draft** | 作者正在修改的内容 |
| AI Writing / Revision | **Working Draft / 指定 Draft** | AI 的工作产物属于 Draft |
| Diff / Change Review | **关联 Draft / baseline** | 审查围绕**变更对象**与其基线，不改变 Canonical |
| History | **Canonical** | 按 `CommitHistory`（`resultingDraftId`）追溯**已提交**变化 |
| Agent Context / Retrieval | **Canonical 前文 + 当前章 Draft** | 历史事实与当前编辑内容并行（见 §23B.3） |
| TXT Import initialization | **初始 Canonical** | TXT 导入是 **Canonical 初始化**，不是 AI 变更链的一次 Draft |

### 23B.2 判定原则（**优先于场景表**）

1. 面向「**读者 / 事实**」→ **Canonical**；
2. 面向「**作者 / 编辑中的内容**」→ **Draft**；
3. 面向「**Agent 决策**」→ **经 Context Engine 获取上下文**：小说事实基线来自 **Canonical**，当前正在编辑的内容来自**指定 Draft**；**不得**理解为 Agent 绕过 Context Engine 直读底层存储；
4. 面向「**历史 / 备份**」→ **Canonical / 已提交内容**。

> **上述判定原则优先于具体场景表。** 场景表是原则在当前已知场景上的落地，不是穷举。
> 未来出现新场景时：**先按判定原则判断，再决定具体读取入口**。

### 23B.3 Agent Context / Retrieval 规则（R3 关键）

```text
前文（历史章节）      →  Canonical
当前章（正在编辑）    →  当前 Draft
                        ↓
最终 Context  =  Canonical 前文  +  当前章 Draft
```

1. **前文历史章节 → Canonical**；
2. **当前正在编辑章节 → 当前 Draft**；
3. **Agent 最终 Context = Canonical 前文 + 当前章 Draft**；
4. **未提交 Draft 不得被视为已经发生的小说事实**；
5. 「前文一致性」判断**必须以 Canonical 为事实基线**；
6. 指定 Draft 可以被**分析、比较、修改**，但**不能因此变成 Canonical**；
7. 仍然使用**现有 Context Engine**：**不得**新建第二套 Context / 第二套 Retrieval；「前文 + 当前章」是 Context Engine **内部按任务拆分的读取结果**，**不是新的架构层**。

> **特别禁止**：因为 Draft 是当前最新内容，就把 Draft 当成小说**已经发生的事实**。

### 23B.4 五条核心规则

1. 正式 Reader 读取 **Canonical**，**不读取 `latestByChapter` 作为正式事实来源**；
2. **未提交 AI Draft 永远不能直接成为 Canonical**；
3. 作者编辑中的 Draft 与正式阅读看到的 Canonical **可以暂时不同**；
4. Agent Context 采用 **`Canonical 前文 + 当前章 Draft`**；
5. **所有依赖小说事实的判断以 Canonical 为事实基线**；指定 Draft 可作为分析对象，但仍属**未提交内容**。

### 23B.5 补充定位（与 §10 P1 一致）

- **`latestByChapter`**：**只用于 Draft / Working Draft 查询**（作者编辑、AI 写作、Diff 基线、索引条目等）；**不得**再被 Reader / Writer / Application / UI 当作正式 Canonical 正文来源。
- **TXT Import**：**TXT 导入 = Canonical 初始化**（`作者 TXT → Import → Chapter → Canonical 初始化`，**不经** `AI Draft → Diff → Human Gate → Commit`）；导入后 AI 对其内容的修改才走变更链。

### 23B.6 R3 FINALIZED 的含义

```text
R3 FINALIZED  =  读取决策已落成  +  文档基线完成
R3 FINALIZED  ≠  读取行为已实现
```

仍属**后续 Phase 代码工作**：`CanonicalRead` 的落地实现、Reader 读取切换、Chapter List / ListPages、Android Reading、Canonical / Draft 实际读取行为。**R3 阶段不实现这些功能。**

---

## 24. 测试验收规则（A / B / C / D）

**废弃**："测试通过，除了允许的失败" 与"exemption"式长期红灯。

每个 Phase 必须对失败项**逐条分类**：

| 类 | 定义 | 处理要求 |
|---|---|---|
| **A** | **本阶段引入的失败** | **必须修复**，不得豁免 |
| **B** | **历史遗留失败** | 必须记录：来源 / 影响 / 归属 Phase / 临时状态 |
| **C** | **环境问题**（如 Windows 文件锁） | 单独记录，不与产品缺陷混为一谈 |
| **D** | **测试自身的问题**（断言错误 / 期望过期） | **必须修复测试**（不是修产品去迎合错误测试） |

**规则**：
- 每个 Phase 的 Completion Gate 中，**A 类必须为 0**；
- B / C 类必须**在 Phase 0 的基线清单里有编号**，并在其归属 Phase 里关闭；
- **禁止**用"允许失败清单"长期承载红灯；
- 跨阶段回归：任一前序 Phase 的最小 slice 变红，视为**当前阶段引入的 A 类**。

---

## 25. 明确禁止项（全阶段生效）

来自架构总图 + 本次对齐评审：

- 重新设计架构 / 删除架构层 / 用旧 Pipeline 替代总图结构
- 把 DSH 放进小说业务层；DSH 反向定义 Novel Project / Canonical / World Model / Change Layer / Skill / Tool Contract
- 让 UI 成为业务架构层；UI 直连 `Repository` / `Storage` / World Model DB / DSH / Tool Registry
- 建立第二套 Canonical / World Model / Context / Draft / Human Gate / Tool Registry / Orchestrator
- **让 AI 绕过 Human Gate**（`AI → saveContent → Approve → Canonical` 一律禁止）
- **让 Memory 成为第二事实源**（`Canonical → World Model` 是唯一事实关系）
- 在 AI 草稿阶段更新 World Model
- 静默覆盖 World Model（必须走 Conflict 生命周期）
- 提前做 DSH 业务化 / MCP 产品化 / 云服务 / 多设备同步
- **RAG / Vector / Embedding / 全文与语义检索**：**当前状态**——受本节（**§25 明确禁止项**）禁止，**FD-10 生效中**（排除边界）；**未来状态**——重新评估条件见 **§23A**；**实施规则**——任何 Phase 在实施中**不得自行解封或实现**
- AI Dashboard / Skill 页面 / Tool 页面 / Runtime 页面 / Sandbox 页面 / MCP 页面
- 重做 Storage / 重做 ApplicationContainer / 重做 Provider
- 为"完整"提前建空表 / 提前扩 Storage（Snapshot 先运行时）
- Tool / Skill 在没有生产调用链的情况下宣布完成
- 用"小说分析"替代核心写作 Skill
- 引入第二套 Writer Document Model

---

## 26. 最终实施顺序

```text
Phase 0   Preflight / Baseline Cleanup      ← 只清理基线，不开始产品能力
Phase 1   Change Layer（建立 Canonical）     ← 唯一地基（造出 Commit 事件）
Phase 2   World Model（最小可生产）           ← 可与 P1 部分并行
Phase 3   World Model Update                 ← 依赖 P1 + P2；含冲突生命周期
Phase 4   Context + Retrieval                ← 收敛四套 + 结构化检索 + Snapshot
Phase 5   Novel Tool                         ← Tool Runtime 接入（不要求生产 caller）
Phase 6   Novel Skill                        ← 核心写作两个优先
Phase 7   Natural Language + Novel Agent     ← 必须存在 Agent Decision Point
Phase 8   Complete Writing Vertical Slice    ← 完整闭环验收
Phase 9   DSH Runtime Adapter                ← 前置：动机裁决
Phase 10  Product UI                         ← 前置：Prototype 结论

并行轨：UI Prototype（Phase 0 起，只验证"客户端感觉"）
```

**检索边界（全 Phase 通用）**：第一阶段只做**结构化检索**；语义检索 / 全文检索 / RAG / Vector / Embedding **当前受 §25 明确禁止，FD-10 生效中**（排除边界）；重新评估条件见 **§23A**；**任何 Phase 不得自行解封或实现**。

**与 v2 的差异**：Phase 0 瘦身 + 移出 Backup / TXT→Chapter / UI 收口；Phase 1 语义收紧（§4）；Phase 5/6/7 判据修正；Phase 2/3/4 补充约束；新增 §22 对齐矩阵、§24 测试分类、§21.2 每 Phase 最小 slice。

---

## 27. 第一阶段实施入口（Preflight / Baseline Cleanup）

| 单元 | 内容 | 完成判据 |
|---|---|---|
| **0-1** | **确认 Git baseline** | 分支 / HEAD / 远程一致；工作树清单落表 |
| **0-2** | **记录已有未提交工作** | P1-01 / P0-3 / I14 逐项登记（边界与归属），**不覆盖** |
| **0-3** | **处理阻塞后续 Phase 的基础编译 / schema 问题** | `:storage` / `:application` 可编译；`24.sqm` 编号与 `Schema.version` 语义一致；12 处版本期望同步（**A 类必须为 0**） |
| **0-4** | **明确测试基线** | 全量测试的绿 / 红清单落表；豁免概念**不使用** |
| **0-5** | **明确已知失败的责任归属** | 每个失败归入 A / B / C / D，B/C 有归属 Phase |
| **0-6** | **不开始任何新的产品能力** | 本 Phase 结束时无新产品功能 |

**移出项（不在 Phase 0）**：
- **Backup / Restore** → **Phase 10**（产品化项）
- **TXT → Chapter** → **Phase 1**（Canonical 的第二个入口：导入必须与 AI 路径共用同一 Canonical 规则）
- **UI → Application 收口（8 处直连仓储）** → **Phase 10**

**Preflight 出口门**：六项完成；A 类失败 = 0；B/C/D 有登记与归属 ⇒ 才进入 Phase 1。

**Phase 1 的第一个可执行单元**（预告，不启动）：把 `WritingExecutionUseCase` 的产出从"直写 `DraftRepository`"改为"入 `WorkingDraftUseCases`"，并**加上"AI 产出无法经 `saveContent` 进入 Canonical"的负向断言**。

---

## 28. v2 → v3 修改清单

| # | v2 内容 | v3 修正 | 依据 |
|---|---|---|---|
| 1 | Phase 0「连接基础与基线恢复」含 TXT→Chapter / Backup / UI 直连收口（5 单元） | 瘦身为 **Preflight / Baseline Cleanup（6 项，不做产品能力）**；Backup → P10；TXT→Chapter → P1；UI 收口 → P10 | 对齐输入 §十一 |
| 2 | Phase 1 =「Canonical + Change Layer」，契约引用"保存即批准（i-b）" | Phase 1 = **Change Layer（建立 Canonical）**；**新增 §4 语义裁决**：作者手改可 Save；**AI 产出禁止经 saveContent 绕过 Gate** | 对齐输入 §三 P1 |
| 3 | Phase 5 要求"每个 Tool 必须有生产 caller" | **删除该要求**；Phase 5 = Tool Runtime 接入（Contract / Registry / Executor / Result / ToolCallLog + 权限边界 + 不绕 Canonical + Result 可返回）；**不要求** Agent 自主 Tool Calling | 对齐输入 §四 |
| 4 | Phase 6 首落 Skill = `LongContinuation` + `NovelAnalysis` | **首落改为 `长篇续写` + `人物一致性`**；明确"不得用小说分析替代核心写作 Skill" | 对齐输入 §六 |
| 5 | Phase 7 未要求 Decision Point | **必须存在 Agent Decision Point**（可测：第二步由第一步结果决定）；明确禁止固定 Pipeline 换名 | 对齐输入 §五 |
| 6 | Phase 2 "7 类对象"表述偏"全建" | 明确 **7 类冻结 + 只实现 MVP 最小集合**；新增 **§3 事实源唯一性裁决**（Canonical → World Model；Memory 非事实源，关闭 R2） | 对齐输入（§七 + §三） |
| 7 | Phase 3 只有 Extract / Validate / Update | **新增冲突生命周期** `Pending / Accepted / Rejected / Conflict`；**禁止静默覆盖**；高风险冲突 → Human Gate | 对齐输入 §八 |
| 8 | Phase 4 列出 Snapshot 持久化（需 additive Storage） | **Snapshot 先做不可变运行时事实**；持久化仅在真实需要时；**不提前扩 Storage** | 对齐输入 §九 |
| 9 | Phase 8 表述近似"第一次串起来" | **明确 Phase 8 = 完整闭环验收**；**新增 §21.2 每 Phase 最小 Vertical Slice** | 对齐输入 §十 |
| 10 | 出口门用"除豁免项外全绿" | **改 A/B/C/D 分类**；A 类必须为 0；**禁止 exemption 长期红灯**；新增 §24 | 对齐输入 §十二 |
| 11 | Phase 9 未强调 DSH 不得反向定义 | **新增禁止清单**：DSH 不得定义 Novel Project / Canonical / World Model / Change Layer / Skill / Tool Contract | 对齐输入 §十三 |
| 12 | Phase 10 未明确 UI 层定位 | **明确 UI = Product Experience Layer**，只调 Application / UseCase；列明禁止直连对象 | 对齐输入 §十四 |
| 13 | 每 Phase 11 项 | **扩为 13 项**（新增"不做什么""Vertical Slice""下一 Phase 的输入"） | 对齐输入 §十六 |
| 14 | 无对齐矩阵 | **新增 §22 Architecture Alignment Matrix** + §22.1 无归属 / 重复归属检查 | 对齐输入 §十七 |
| 15 | 风险 R2（Memory vs World Model）为未决 | **R2 关闭**；新增 R12（提前建空表 / 扩 Storage） | 对齐输入（§七 / §九） |
| 16 | 无"未分配项"处理 | 明确三处移出项的归属（Backup→P10、TXT→P1、UI 收口→P10） | 对齐输入 §十一 / §27「移出项（不在 Phase 0）」（汇总），另见 §0 第 10 行 · §9 第 6 项 |

**未改动**：架构总图的概念 / 节点 / 层 / 流向；Phase 1–10 的总体顺序（仅 Phase 0 改名瘦身）；v2 的代码审计结论（§5 / §6 沿用）。

---

## 29. v3 → v3.1 修改清单

> 本轮为**文档收口**：只修下列 8 项 + §2 一条方向硬规则，不重设计架构、不增删 Phase、不改 Phase 顺序。

| # | 修正项 | v3.1 的落实 | 位置 |
|---|---|---|---|
| 1 | **P2 不得建立第二条 World Model 生产写入路径** | P2 只做 Foundation（模型 / Repository / 读取 / 字段完整性，用 fixture 与 Canonical 数据验证）；明确「**P2 production World Model write callers = 0**」；写侧用例（`StoryStateVariantUseCases` / `OverrideUseCases`）**不接任何生产调用者**；正式写入链（Canonical Change → Extract → Candidate → Validate → Conflict → Update）**唯一归属 P3** | §11 第 1 / 5 / 6 / 8 / 10 / 11 / 12 项 · §21.2 |
| 2 | **完整闭环统一为 17 段** | 「15 段」全部改为 **17 段**（三处）；§21.1 表按用户给定的 17 段（Author … Result / Proposal）重写，段名与顺序固定为验收口径 | §8 表 · §17 第 12 项 · §21.1 · §21.2 |
| 3 | **P8 的 Agent / World Model / Context 顺序修正** | 删除 `Novel Agent → World Model → Context / Retrieval` 写法；改为 `World Model / Memory → Context Engine → Structured Retrieval → Context Snapshot → Novel Agent`；§2 新增「方向硬规则」并在 §16 / §17 标注 **Agent 消费 Context，不直接读 World Model** | §2 · §16 第 2 / 5 项 · §17 第 5 项 |
| 4 | **P4 第一阶段只做 Structured Retrieval** | **当前实际存在的索引类型**：`NOVEL` / `CHAPTER` / `DRAFT` / `STORY_FOUNDATION` / `VOCABULARY`（`Character` / `Event` / `Timeline` / `Foreshadowing` 待 P2 / P3 Canonical 来源后补齐）；Previous Chapters 必须经 ChapterId / Sequence / Relation；**不做** RAG / Vector / Embedding / Semantic / Full Text Search。**当前状态**：受 **§25** 明确禁止，**FD-10 生效中**；**未来状态**：重新评估条件见 **§23A**（R1 已 FINALIZED，**FD-10 未解封**）；**实施规则**：P3 / P4 及任何 Phase **不得自行解封或实现**；P4 Completion Gate = **正向 + 负向**（未引入 §25 禁止的检索能力，可机械验证） | §12 第 6 项 · §13 第 6 / 8 / 12 项 · §23A · §25 |
| 5 | **P1 增加 `CanonicalRead` 正式读取入口** | `CanonicalRead` = **唯一**正式 Canonical 正文读取入口；`latestByChapter` 只用于 Draft / Working Draft 查询；禁止 Reader / Writer / Application / UI 继续把它当 Canonical；**「哪些场景读 Canonical / Draft」见 §23B（R3 已 FINALIZED）** | §10 第 8 / 12 项 · §23B |
| 6 | **TXT Import 语义 = Canonical 初始化** | 两条路径语义分离：`作者 TXT → Import → Chapter → Canonical 初始化`（不经 Gate）；`AI → Working Draft → Diff → Change → Human Gate → Commit → Canonical`；AI 对导入内容的后续修改才走 Change Layer | §10 第 5 / 8 / 12 项 · §4 |
| 7 | **P5 Tool Runtime ≠ P7 Agent Tool Calling** | P5 完成条件 = Tool Runtime ready（调用者可为 Unit / Integration / Application Test Caller）；**不要求** P5 已存在 `Agent → Tool → Result → Agent` 生产链；自主选取 / 调用 / 消费 Result 归 P7 | §14 第 6 / 12 项 |
| 8 | **P7 Agent Decision Point 必须可测** | 第 10 项加入行为测试口径：**同一个 Task，前一步 Result = X → 下一步 A；Result = Y → 下一步 B**（只换 Result fixture，断言第二次动作不同）；第 12 项 Gate 明确「缺该测试即 P7 未完成」；仅存在 Decision / Branch API **不算通过** | §16 第 10 / 12 项 |

**未改动**：架构总图的概念 / 节点 / 层 / 流向；Phase 0–10 的数量与顺序；各 Phase 的职责划分；§22 对齐矩阵；§24 A/B/C/D 规则；§25 禁止项；§28 v2→v3 清单；v2 文档本体。

**R1 = FINALIZED**（方案 A：保持 FD-10 语义 + 增加 Evidence-Based Reassessment Criteria，见 **§23A**；**FD-10 仍未解封**）。
**保持 UNRESOLVED**：R7（DSH 产品动机锚点）。**R3 = FINALIZED**（Canonical / Draft 读策略见 §23B）。不替用户拍板。

---

## 附：文档纪律

- 本计划**不修改架构总图**（唯一基线见 §2）。
- 所有 `module / package / 文件` 路径均来自**已实际验证存在**的路径或目录；未编造文件名。
- 承接 v2 的全部代码审计结论；本次只做**对齐修正 + 约束补充**。
- 未决项（**R7**）**不替产品决定**，保持显式标记；**R1 已 FINALIZED**（升级评估条件见 §23A，**FD-10 未解封**）；**R3 已 FINALIZED**（读策略见 §23B）。
- 任何新功能若无法说明连接路径（总图 §20 七问），不进入正式开发（总图 §23）。
