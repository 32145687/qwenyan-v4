# Qianyan V4 · P11 Preflight Audit Report

> **性质**: Preflight / 只读审计（P10 Push 后、P11 实现前）
> **日期**: 2026-08-21
> **版本**: v0.1-audit
> **结论**: **P11 PREFLIGHT = READY**（可进入 P11.1，但 P11 code = **NOT STARTED**，本审计不产生任何代码/测试/Schema/Migration/UI）

本审计基于当前仓库实际代码（非设计假设）。所有"是否已实现"的判断以 `git/grep/read` 为准。

---

## 1. Repository Baseline

| 项 | 值 |
|----|----|
| 分支 | `main` |
| HEAD | `01ef64c` |
| origin/main | `01ef64c`（HEAD == origin/main ✅） |
| working tree | CLEAN（`nothing to commit`） |
| git diff --check | PASS（无空白错误） |
| P10 提交 | `5225da4` feat(agent): P10 Agent Runtime + Tool System；`01ef64c` docs(readme): sync P10 agent runtime status |
| Push | 已推 `origin/main`（无 force push） |

**阶段状态**（README 现行路线图一致）：P8.1 / P8.2 / P8.3 / P9 / P10 = DONE；**P11 = NOT STARTED**。

---

## 2. P8 / P9 / P10 Baseline

### P8 Task System（DONE）
- 领域：[TaskModels.kt](file:///workspace/core/model/src/main/kotlin/com/qianyan/model/task/TaskModels.kt) —— `TaskType`（含 `WRITING`、`PLANNING`、`KNOWLEDGE_UPDATE`）、`Task`、`Checkpoint(revision∈1..3, snapshot:JsonObject)`。
- 持久化：[Task.sq](file:///workspace/storage/src/main/sqldelight/com/qianyan/storage/db/Task.sq) —— `Task`（`revision_count BETWEEN 0 AND 3`）+ `Checkpoint`（`UNIQUE(task_id,revision)`）。
- 行为：[TaskManagerUseCases.kt](file:///workspace/application/src/main/kotlin/com/qianyan/application/usecase/task/TaskManagerUseCases.kt) —— 严格状态机 + 顺序 revision + Checkpoint 恢复（只恢复上下文，不重执行）。
- 执行入口：[TaskRunner.kt](file:///workspace/application/src/main/kotlin/com/qianyan/application/usecase/task/TaskRunner.kt) —— **仅支持 `IMPORT`**；`WRITING/PLANNING/KNOWLEDGE_UPDATE` 抛 `ApplicationError.UnsupportedTaskType`。
- **P11 用法**：复用 `TaskManagerUseCases` 管理 WRITING Task 生命周期与 Checkpoint（revision 上限恰为写作"限额 3 次修订"提供既有约束）；**P11 必须放开 `TaskRunner.execute` 对 WRITING/PLANNING/KNOWLEDGE_UPDATE 的"不支持"分支**，但不破坏 IMPORT 路径。

### P9 Provider（DONE）
- 契约：[LLMGateway.kt](file:///workspace/provider/api/src/main/kotlin/com/qianyan/provider/LLMGateway.kt) —— 仅 `chat(request): ProviderResponse`，不解析领域、不写库。
- 模型：[ProviderModels.kt](file:///workspace/provider/api/src/main/kotlin/com/qianyan/provider/ProviderModels.kt) —— `ModelProfile`（`MOCK`/`DEEPSEEK_V4_FLASH`=`deepseek-v4-flash`/`MIMO_V2_5`=`mimo-v2.5-pro`）、`ProviderRequest(temperature,maxTokens)`、`ProviderResponse`、`Usage`、`FinishReason`。
- 实现：`:provider:impl` 含 [DeepSeekLLMGateway](file:///workspace/provider/impl/src/main/kotlin/com/qianyan/provider/impl/DeepSeekLLMGateway.kt) / [MiMoLLMGateway](file:///workspace/provider/impl/src/main/kotlin/com/qianyan/provider/impl/MiMoLLMGateway.kt) / Mock。
- **关键事实**：`MiMoLLMGateway` 源码中**没有任何 over-explain / writing 后处理**（仅实现 `chat`）。
- **P11 约束**：Writing Workflow 只依赖 `LLMGateway`（`:provider:api` seam），**禁止**直接引用 `DeepSeekLLMGateway` / `MiMoLLMGateway` / HTTP / API Key。真实模型由装配方经 `ModelProfile` 注入（同 ApplicationContainer 现有 `analysisModel` 模式）。

### P10 Agent Runtime（DONE）
- [AgentRuntime.kt](file:///workspace/agent/runtime/src/main/kotlin/com/qianyan/agent/runtime/AgentRuntime.kt) —— 同步循环：`LLM → AgentStep.Final|Tool → ToolExecutor → ToolResult 观察 → 下一轮 LLM`，`maxSteps=10` 防循环。
- [AgentStep.kt](file:///workspace/agent/runtime/src/main/kotlin/com/qianyan/agent/runtime/AgentStep.kt) —— `AgentResponseParser` 确定性解析 `{"tool":…}/{"answer":…}`。
- [ToolExecutor.kt](file:///workspace/agent/tool/src/main/kotlin/com/qianyan/agent/tool/ToolExecutor.kt) + [ToolRegistry.kt](file:///workspace/agent/tool/src/main/kotlin/com/qianyan/agent/tool/ToolRegistry.kt) + [Tool.kt](file:///workspace/agent/tool/src/main/kotlin/com/qianyan/agent/tool/Tool.kt) + [ToolContext.kt](file:///workspace/agent/tool/src/main/kotlin/com/qianyan/agent/tool/ToolContext.kt) —— 注册/校验/执行/错误归一；`ToolContext` 已预留 `tag`（后续承载 `VariantContext`）。
- **P11 用法**：直接调用 `AgentRuntime.run(AgentContract, input)`；具体 Agent 只以 `AgentContract` + Prompt 表达，Tool 只经 `ToolExecutor` 调用。**不要重写 Agent Runtime**。
- **缺口**：`:agent:agents`、`:agent:orchestration` 两块**无 main 代码**（仅 SmokeTest）。P11 的核心工作都在这里。

---

## 3. Existing Writing Capability（全仓实际）

**结论：P11 之前，仓库不存在任何可运行的写作能力。**

搜索 `writing / novel / chapter / generation / revision / critique / planning / plot / outline / context / prompt` 后确认：

| 能力 | 是否存在代码行为 | 说明 |
|------|:---:|------|
| `WritingUseCase` | ❌ | Application 无该 UseCase。已有 `NovelUseCases`（仅 Novel CRUD 数据，非"创作"）、`AnalysisUseCases`（读类分析，非写作）。 |
| `PlanningUseCase` | ❌ | 无。 |
| `CritiqueUseCase` | ❌ | 无。 |
| `RevisionUseCase` | ❌ | 无（仅 `Task.revisionCount` 这类**计数**机制，无"修订"行为）。 |
| `ChapterUseCase` | ❌ | 无（`Chapter` 仅是领域模型，无任何行为）。 |
| Agent：Intent/Research/StoryPlanner/Writing/Critic/Knowledge | ❌ | `:agent:agents` 为空。 |
| Orchestrator `WritingWorkflow` | ❌ | `:agent:orchestration` 为空。 |
| `WorkflowState` 状态机行为 | ❌ | 枚举存在（`AgentModels.kt`），**未接入任何执行流程**。 |
| 正文/草稿存储（Draft） | ❌ | **连 `Draft` 领域模型都不存在**；`Chapter` 有 `ChapterStatus`（PLANNED→…→FINAL）但与正文无关。 |

**一次模型（有形态、无行为）**：`UserWritingRequest` / `TargetRef` / `ContextCandidate` / `ContextType`（含 `WRITING_MEMORY`）/ `ValidationResult` / `KnowledgeConflict` / `PendingConfirmation` / `MemoryEntry`（含 `WRITING` 层）——全部已建模但无驱动它们的代码。

---

## 4. Existing Domain Capability（Story Domain 盘点）

| 模型 | 存在？ | 有行为？ | 已持久化？ |
|------|:---:|:---:|:---:|
| `Novel`（Original，Immutable） | ✅ | ✅ CRUD/Override | ✅ | 
| `NovelVariant` + `VariantContext` + `EntityOverride` | ✅ | ✅ CRUD/Override | ✅ |
| `Chapter` / `ChapterStatus` | ✅ 模型 | ❌ | ❌（无表） |
| `ChapterPlan` / `ScenePlan` / `Scene` / `Beat` / `StoryArc` / `Act` | ✅ 模型 | ❌ | ❌ |
| `Character` / `CharacterState` / `CharacterArc` / `Relationship` | ✅ 模型 | ❌ | ❌ |
| `KnowledgeEntry`（FACT/RULE/…，scope+variantId） | ✅ 模型 | ❌ | ❌ |
| `MemoryEntry`（`MemoryLayer.WRITING` 等 4 层） | ✅ 模型 | ✅ CRUD | ✅ |
| `ContextType` / `ContextCandidate` / `UserWritingRequest`/{`TargetRef`} | ✅ 模型 | ❌ | ❌ |
| `Timeline` / `World` | ✅ 模型（存在文件） | ❌ | ❌ |
| `Vocabulary`（GLOBAL/NOVEL/VARIANT scope，replacement） | ✅ | ✅ CRUD | ✅ |
| `ValidationResult` / `KnowledgeConflict` / `ConflictResolution` / `PendingConfirmation` | ✅ 模型 | ❌ | ❌ |
| **`Draft`（正文/草稿）** | ❌ | ❌ | ❌ |

结论：**域模型覆盖面很广（尊重了原设计 Canon/Knowledge/Vocabulary/Timeline/Memory/Context/Validation 的声明），但 9 成是"声明的数据载体"，无任何执行者。P11 不是加模型，而是第一次给这些模型接上"行为 + 编排 + 持久化"。**

---

## 5. Adding: Provider / Agent / Tool / Engine 能力摘要

- **Provider**：`LLMGateway.chat`；无结构化领域解析、无写作 Prompt、无后处理。写入与解析责任在调用方。
- **Agent**：`AgentContract`（`allowedTools` 天然支持按 Agent 授权工具）+ `AgentState`；`AgentRuntime.run` 已是通用 loop。缺：具体 Agent 契约、系统 Prompt、结构化写作产物协议。
- **Tool**：`Tool` 接口 + `ToolRegistry`/`ToolExecutor` 已就绪；Tool 只许访问 Application/Engine/Repository Contract。**缺：没有任何业务 Tool 被注册**（当前 AgentRuntime 测试用的是 Fake/Echo）。
- **Engine**：`:core:engine` 仅 `txt` + `analysis`。**缺：写作侧确定性校验（Canon/Knowledge Boundary/Revision Gate/Output Validation）引擎**——这些必须放在 Engine（确定性，不调 LLM）。

---

## 6. Writing Pipeline Gap（P11 需要补的洞）

| # | 缺口 | 归因 | 归属层 |
|---|------|------|--------|
| G1 | 具体写作 Agent（Intent/Planner/Writer/Critic/Knowledge）与写作 Prompt/产物协议 | `:agent:agents` 空 | agent:agents |
| G2 | 写作 Orchestrator（WorkflowState + Task/Checkpoint 驱动 + Revision 循环） | `:agent:orchestration` 空 | agent:orchestration |
| G3 | Agent→业务 Tool 的桥接与注册（context:build / writing:write / knowledge:verify…） | 无业务 Tool 被注册 | agent:tool + application |
| G4 | WritingUseCase / PlanningUseCase / Critique / Revision 编排层 | Application 缺这些 UseCase | application:usecase:writing |
| G5 | Draft 正文的**领域模型 + 持久化** | 无 Draft 模型、无 Chapter 表 | core:model + storage |
| G6 | 确定性写作校验引擎（Canon 校验、Knowledge Boundary、输出结构、Revision Gate） | core:engine 只到 analysis | core:engine |
| G7 | Writing Context 组装（ContextCandidate 按 VariantContext 作用域收集） | 只有模型声名 | application / agent:agents |
| G8 | `TaskRunner` 放开 WRITING/PLANNING/KNOWLEDGE_UPDATE | P8.3 显式不支持 | application:usecase:task |
| G9 | MiMo 写作后处理 seam（见 §9） | 无任何后处理 | application (writing) |

---

## 7. P11 Scope（最小可运行闭环）

P11 MVP = **单章节（chapter）受控写作片段（slice）**，满足：

```
UserWritingRequest（创作目标）
  → Agent Runtime（LLM via LLMGateway）
  → Tool（经 ToolExecutor，调用 Application/Engine/Repository）
  → 产出 Draft
  → 确定性校验（Canon / Knowledge Boundary / 输出结构）
  → Final 输出
```

**明确 P11 不做**（确保最小）：多章节/整书、复杂 `ContextCandidate` 重排分、HITL UI、Conflict 自动裁决、完整四层 Memory、Android/Desktop UI、真实多 Provider 网关（沿用现有装配模式）。

**P11 必须新增的领域模型**：最小 `Draft`（承载正文字符串 + 关联 novel/variant/chapter/plan/time/修订来源）。`Draft` 是唯一被论证过的新模型；其余复用已有模型。

---

## 8. P11.x Phase Breakdown（基于真实代码）

> 候选（写入此处）与"P11.1~8 直接照搬"不同，按 §6 的实际缺口收敛为 5 个子阶段。每子阶段独立可测、可提交。

- **P11.1 — Writing Scaffold**：补齐 G1+G8。
  - 定义 4~5 个写作 `AgentContract`（Intent/Planner/Writer/Critic/Knowledge）+ 写作系统 Prompt 构建器 + 结构化产物协议（Draft/Review JSON）。
  - 放开 `TaskRunner` 对 `WRITING/PLANNING/KNOWLEDGE_UPDATE` 的执行（`when` 分发到新 UseCase 入口），**不破坏 IMPORT**。
  - 用 Mock gateway 打通一次 AgentRuntime 冒烟（证明 Task→Agent→LLM→Tool 链路通）。
- **P11.2 — Writing Context + Planning**：补 G7 + 规划落盘。
  - 组装最小 `ContextCandidate`（按 `VariantContext` 作用域过滤可见知识），`StoryPlannerAgent` 产出 `ChapterPlan`。
  - 规划经 `TaskManager.saveCheckpoint` 落 Checkpoint（复用，**不加 Schema**）。
- **P11.3 — Writing + Draft 持久化**：补 G3+G5+G6(部分)。
  - `WritingAgent` 按 `ChapterPlan` 产出 Draft；新增 `chapter_draft` 最小表（唯一论证过的 migration，见 §11）或先经 `MemoryEntry WRITING` 落盘（二选一，见 §12 决策点）。
  - 注册 `writing:write` / `canon:validate` Tool；确定性 Canon/Knowledge Boundary 校验放 `core:engine`（不调 LLM）。
- **P11.4 — Critique + Revision（≤3）**：补 G2+G6(Revision Gate)。
  - `CriticAgent` 对照 Plan/Knowledge 审查；确定性 `RevisionGate` 用 `Task.revisionCount < 3` 驱动 `WRITING↔CRITIQUE`。
  - **在此登记 MiMo 写作后处理位置（设计/seam），不实现**（见 §9）。
- **P11.5 — Knowledge Update + Final Review + E2E Writing Slice**：补 G4+G9 落地。
  - `KnowledgeAgent` 把写作产物经 Lifecycle 沉淀 `MemoryEntry WRITING`（Variant 作用域保护）；Final Review。
  - **End-to-End Writing Slice 测试**：输入创作目标 → LLM → Agent → Tool → 内容 → 校验 → 最终输出。
  - 若 §11 决策选加表，则本阶段完成 migration + Repository/Mapper 更新 + P11 completion report。

**P11 完成标准**：G1~G9 全部闭环；`test`+`build` 通过；架构依赖边界审计（§13）通过；README/status 更新。

---

## 9. MiMo Special Writing Post-processing（设计登记，不实现）

- **定位**：MiMo-v2.5 是写作模型，且存在 over-explain 倾向。P11 不把它当普通"分析"模型用。
- **位置（P11.4 登记 seam，P11.5 可选落地）**：Writing Pipeline 产出 Draft 之后、入库/最终输出之前，处于 **Application writing UseCase**（编排层，而不是 Provider 实现里）：
  ```
  WritingAgent Final answer
    → 判断 profile.id
    → != mimo-v2.5-pro → 直通
    → == mimo-v2.5-pro → MiMo 写作后处理（压缩解释性冗余 / 收束）→ 规范化 Draft
  ```
- **边界**：`ModelProfile.id` 由装配方传入（复用 `analysisModel` 的 seam 模式，扩展为 `writingModel`）；后处理属于 Application 编排逻辑，**不**改写 Provider/AgentRuntime/Engine。
- **确认**：**MiMo writing post-processing = P11**（本 Preflight 只确认位置与边界，不实现）。

---

## 10. 真实模型定位

- DeepSeek-V4-Flash（`deepseek-v4-flash`）与 MiMo-V2.5（`mimo-v2.5-pro`）都是**写作模型**，P11 用于生成正文/规划/评审，不是纯分析。
- P11 需要（但不提前臆造 Provider API）：
  - `ModelProfile` 选模型（writingModel seam）；
  - Writing Prompt / System Prompt（在 `:agent:agents`/编排层构建，Provider 不碰）；
  - Generation 参数：`ProviderRequest.temperature` / `maxTokens` 已被契约支持（首次写作可用温度>0，评审/修订可走确定性低温）。
  - Output Parsing / Post Processing（结构化产物协议在 agents 层；MiMo 后处理在 application 编排层）。

---

## 11. 持久化审计

**必须持久化**：

| 数据 | 是否需新表/migration | 方案 |
|------|:---:|------|
| Novel / Variant / Override / Vocabulary / MemoryEntry | 复用现有表 | 不变 |
| Task / Checkpoint（含每次写作 checkpoint） | 复用现有 | 不变（snapshot 容量够放 plan） |
| **Draft 正文** | **是（唯一新增）** | `chapter_draft` 最小表（novel_id/variant_id/scope/chapter_plan/chapter body/time/source/provenance/created_at）。 |
| Chapter 结构 / Character / Knowledge 等 | 否（P11 不做） | 阶段外；沿用领域模型 + Checkpoint 快照承载 slice 内数据。 |

**原则**：不为 P11 一开始大规模改 Schema。**唯一**可能的新表 = `Draft`（写作必须落正文，且现在确实无处可放；`Checkpoint.snapshot` 有 revision≤3 与 task 作用域限制，不应用作正文长期载体）。已持久化表一条不改。

> 决策点（P11.1 前锁定）：Draft 落库用「新增 `chapter_draft` 表」还是「先 `MemoryEntry`（layer=WRITING）+ `Checkpoint.snapshot`」过渡。推荐前者（语义正、正文不应混入 Memory）。若选过渡方案，`chapter_draft` migration 顺延到 P11.5。

---

## 12. Android / Desktop 边界

- 目标：Android App + PC 端能力共用。
- **P11 保持核心创作引擎为 JVM / `Application`（+ `:agent:*` + `:provider:*`）**，Android 与 Desktop（`:app:android` / `:app:desktop`）暂只依赖契约层，**不新建 UI、不在 P11 连引擎到 App**（与 P0 的分层意图一致）。
- P11 产出物（AgentRuntime 驱动的写作 UseCase）两个端将来直接调用，本阶段**不实现 UI 也不做端侧适配**。

---

## 13. 架构边界（必须保持）

```
Application ← WritingUseCases ← :agent:orchestration(WritingWorkflow)
   :agent:orchestration → :agent:runtime(AgentRuntime)
   :agent:runtime → :provider:api(LLMGateway)  ──装配──▶ :provider:impl(DeepSeek/MiMo/Mock)
   :agent:runtime → :agent:tool(ToolExecutor)
   :agent:tool(Tool) → Application / core:engine / Repository Contract
```

**禁止**：
- Writing Workflow → `DeepSeek/MiMoLLMGateway` 具体实现、HTTP、API Key、SQLDelight；
- Agent → Storage / Android；
- Engine 调 LLM / 网络 / 依赖 Application / Storage；
- 绕过 `LLMGateway` / 重写 AgentRuntime / 让 Tool 直连 Repository 实现。

**错误分层**：Engine 异常 → Application Error（复用 `ApplicationError` 代数）→ 编排/UI 表示；不得在编排层 `catch(Exception)` 后静默吞。

**确定性**：Canon 校验、Revision Gate、Knowledge Boundary 必须放 `core:engine`（不调 LLM），相同输入同结果。

---

## 14. Test Plan（P11 最小测试矩阵）

单元/行为（各子阶段）+ 集成：

| 关注点 | 覆盖 |
|--------|------|
| Agent（Intent/Planner/Writer/Critic/Knowledge）解析产物 | 合法 JSON / 残缺 / 纯文本兜底 / 未知 tool |
| Tool（writing:write、canon:validate 等） | 校验失败 / 未知参数 / 业务失败 success=false / Variant 作用域拒写 Original |
| Revision Gate | count<3 进入重写、=3 ACK街 `CONFLICT_HITL` / 超限错误 |
| Workflow / Task 状态转换 | WorkflowState 各态、非法转换、Checkpoint 恢复 |
| 确定性校验 | 相同输入→同结构/同顺序/同结果；Canon 冲突；超出 Knowledge Boundary |
| 持久化读回 | Draft 入库读出一致；Chatpoint 恢复上下文不重执行；旧 Schema 兼容 |
| **End-to-End Writing Slice** | `输入创作目标 → LLM(Mock) → Agent → Tool → 内容 → 校验 → 最终输出` |

MiMo 后处理测试（P11.5）：`profile=mimo` 走后处理分支，`profile=其他` 直通，且后处理不产生死循环。

---

## 15. P11 不提前实现

本 Preflight 未实现任何：代码 / 测试 / Schema / Migration / UI / 真实小说生成。仅只读审计 + 方案。

---

## 16. Risks

- **R1 · 范围膨胀**：Story 域模型极多（Arc/Act/Scene/Beat/Foreshadowing/Payoff/Pacing/…），若 P11 试图全部接行为会失控。**对策**：MVP 严格限定「单章节 slice」，只接 Planning/Writing/Critique/Knowledge 四条最小编排路径。
- **R2 · Schema 决策拖延**：Draft 持久化二选一未定会阻塞 P11.3。**对策**：P11.1 即锁定 §11 决策点。
- **R3 · MiMo 后处理误放 Provider**：若把 over-explain 处理放 Provider/Engine，会破坏确定性/边界。**对策**：§9 已锁定放 Application 编排层，P11.4 只登记位置。
- **R4 · Revision 语义漂移**：`Task.revisionCount` 是"checkpoint revision"计数，与"创作修订次数"语义不完全同源。**对策**：RevisionGate 以 `revisionCount<3` 为唯一闸门，显式文档化二者对齐，不为计数新增字段。
- **R5 · 旧设计冲突**：`docs/planning/qianyan-implementation-plan.md` 历史阶段编号与现 P8~P11 路线不同。**对策**：保持其历史文档身份，不恢复旧编号、不覆盖；现路线以 master-plan / v4.2-architecture-review / README 为准。

---

## 17. Acceptance Criteria（P11 DONE 判定）

1. 单章节 Writing slice 端到端跑通（Mock 网关）：目标→规划→写作→评审→修订(≤3)→知识沉淀→最终输出。
2. `TaskRunner` 可执行 `WRITING/PLANNING/KNOWLEDGE_UPDATE`，IMPORT 行为不回归。
3. Draft 有明确领域模型 + 持久化（按 §11 决策），读回一致、旧库兼容。
4. Canon 校验 & Knowledge Boundary & Revision Gate 在 `core:engine` 且完全确定性。
5. MiMo 写作后处理 seam 已登记其位置（P11.4），并（可选）在 P11.5 落地且不影响其它模型。
6. 架构边界审计通过（§13 无违规、无绕过 LLMGateway/AgentRuntime/Tool 契约）。
7. 测试矩阵（§14）全绿；`test`+`build` 通过；README/status 更新；P11 completion report 生成。
8. Android / Desktop 无 UI 改动、核心引擎保持 JVM/Application 共用。

---

## 18. Verdict

```
P11 PREFLIGHT = READY
P11 code      = NOT STARTED          （本审计未产生任何实现，未 commit / push）
```

**停止。** P11 的实现从 P11.1 起另行开始，届时先锁定 §12 的 Draft 持久化决策点，再进入实现。

**注意**：本报告为新增审计文档，按审计性质仅记录了方案与边界；未改动任何属于 P8/P9/P10 的代码。是否 commit 本审计文档由你决定（若需要，我可以单独提交 `docs(P11): add preflight audit report` 且不触碰任何实现代码）。