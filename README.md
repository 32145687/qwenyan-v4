# Qianyan（千言）

**本地优先的长篇创作辅助工具** —— 受控的 Agentic Writing 系统。

面向长篇网络小说 / 连载作者，把「大纲、世界观、人物、时间线、伏笔、词汇风格」等创作要素结构化沉淀，并借助 AI Agent 完成剧情重构、续写、风格统一等写作任务，同时通过 Human-in-the-loop（HITL）保证作者对创作过程的完全控制。

## 要解决的问题

1. **长文记忆与一致性**：几十万字连载中的人物状态、时间线、伏笔、设定不冲突。
2. **受控 AI 写作**：AI 不直接写数据库、不越权修改原文；通过 Override 增量表达差异，**Original 始终只读**（V4.2 Hybrid 决策）。
3. **多版本重构**：同一本 Original 可派生多个 Variant（改线 / 续写 / 重写），差异以 EntityOverride 表达，不复制全文。

## 核心设计原则

- **Local-first**：本地优先，数据存于本地 SQLite。
- **Deterministic Engine**：确定性引擎（如 TXT 解析）不调用 AI、不引入随机性。
- **Repository 隔离**：上层只面向仓储接口，不直接操作 Storage。
- **Agent 受控**：Agent 通过 Tool → Engine → Repository 消费能力，不反向耦合、不越权访问存储。

## 当前进度（P0–P10）

| 阶段 | 内容 | 状态 |
|---|---|---|
| P0 | 13 模块工程骨架 + Gradle/CI 全绿 | ✅ |
| P1 | `core:model` 全量领域模型 + 强类型 ID + 测试 | ✅ |
| P2 | Storage：SQLDelight 单 SQL 真源 + 5 仓储 + 写保护触发器 + Backup | ✅ |
| P3 | Application：Use Case 层（DI / 错误边界 / 集成测试） | ✅ |
| P4 | TXT Pipeline：确定性 导入 → 规范化 → 章节识别 → 结构化 → 持久化 | ✅ |
| P5 | TXT Pipeline 接入 Application：TXT → 去重(contentHash) → Original Novel → novelId 绑定 → 原子持久化 → 结构化结果 + VariantContext(ORIGINAL) | ✅ |
| P6 | AI Analysis Pipeline：TXT → AnalysisInput → Provider(API/Impl) → AnalysisResult → Validation → VocabularyCandidate(PENDING) | ✅ |
| P7 | Android 功能闭环：Database 初始化 → Application API → Compose → DI → Novel List → TXT 导入 → Analysis → Vocabulary Candidate → 验收 | ✅ |
| P8.0 | Task System / Task Manager foundation：Task / Checkpoint persistence architecture | ✅ |
| P8.1 | Task / Checkpoint persistence：Schema v2 + migration + repository + transaction + tests | ✅ |
| P8.2 | Task Manager / Task State Machine：TaskManagerUseCases + 状态机 + Checkpoint revision 控制 + 类型化错误 | ✅ |
| P8.3 | Task Execution：Application 层受管 Task 执行驱动（TaskRunner）+ IMPORT 纵向切片 + 类型化拒绝 + 测试 | ✅ |
| P9 | 真实 LLM Provider 接入：DeepSeek-V4-Flash + MiMo-V2.5（JDK HttpClient transport + ProviderException 映射 + API Key 注入 + fake transport 测试） | ✅ |
| P10 | Agent Runtime + Tool System：最小同步 Agent Runtime + Tool 契约/注册表/执行器 + LLM/Tool 循环 + maxSteps + 类型化错误 + 测试 | ✅ |

**当前阶段**：P7 = DONE（Android 功能闭环已完成并验收）；P8.0 = DONE；P8.1 = DONE；P8.2 = DONE；P8.3 = DONE；P9 = DONE；**P10 = DONE**。
**P8 说明**：P8（Task System / Task Manager）已完成 P8.0 / P8.1（Task / Checkpoint 持久化基础设施）、P8.2（TaskManager 状态机 / Checkpoint 管理）与 P8.3（TaskRunner 受管执行 IMPORT 纵向切片）；**Task 生命周期 / 状态管理 / IMPORT 受管执行 = DONE，Agent / Tool / Workflow 编排 = NOT STARTED（后续阶段）**。
**P9 说明**：真实 **DeepSeek-V4-Flash Provider = DONE**、**MiMo-V2.5 Provider = DONE**、**真实 LLM 接入 = DONE**（JDK HttpClient transport + ProviderException 结构化映射 + API Key 注入 + fake transport 测试）；**Agent / Tool / Workflow / 完整小说创作 Pipeline = NOT STARTED（后续阶段）**。
**P10 说明**：最小 **Agent Runtime = DONE**、**Tool System = DONE**（`core:model` Tool 领域模型 + `:agent:tool` Tool 契约/Registry/Executor + `:agent:runtime` 同步执行循环），Agent 只依赖 `:provider:api` 的 `LLMGateway` 契约，可完成 **LLM → Tool call → ToolResult → LLM → Final** 的真实执行链，带 `maxSteps` 防护与类型化错误（ToolNotFound / InvalidToolRequest / ToolExecutionFailed / MaxStepsExceeded）；**Writing/Planning/Critique/Revision Agent、Novel Workflow、HITL、KnowledgeUpdate、完整小说创作 Pipeline = NOT STARTED（DEFER 到 P11+）**。
**P6/P7 说明**：AI Analysis 默认仍走 **Mock Provider（MockLLMGateway）**，仅用于验证完整应用调用链；P9 起装配方可注入 `DeepSeekLLMGateway` / `MiMoLLMGateway` 并选择 `ModelProfile.DEEPSEEK_V4_FLASH` / `MIMO_V2_5`；正式 **Knowledge / Character / Event / Timeline / World 持久化 DEFER**；**Variant Analysis DEFER**；`AnalysisResult` 为 transient（不建表）；AI 提取仅进入 PENDING `VocabularyCandidate`，不直接写正式 `VocabularyEntry`；**Candidate 确认 / 转正式词条流程 DEFER**。
**尚未实现**：ANALYSIS / WRITING / PLANNING / KNOWLEDGE_UPDATE 等其余 TaskType 的真实执行（P8.3 仅 IMPORT）、写作工作流（Workflow） / Writing/Planning/Critique/Revision Agent / HITL / 自动 retry、完整小说创作 Pipeline、Knowledge / Character / Event / Timeline / World 正式持久化、Candidate 确认流程、Android Task UI、Desktop UI、PC / Cloud 后端。

---

## Current Development Roadmap（现行路线，唯一阶段口径）

> 以**当前实际开发路线**为准，本表为仓库唯一现行阶段编号。
> 该编号与历史规划文档（见 [docs/planning/qianyan-implementation-plan.md](docs/planning/qianyan-implementation-plan.md) 的旧 P0–P18 编号）**不同**；历史文档的旧编号已被本表取代。

| 阶段 | 定义 | 状态 |
|---|---|---|
| P8.1 | Task Storage | ✅ DONE |
| P8.2 | Task Manager / State Machine | ✅ DONE |
| P8.3 | Task Execution / TaskRunner | ✅ DONE |
| P9 | Real LLM Provider（DeepSeek / MiMo / LLMGateway / HTTP Transport / Provider Error Handling） | ✅ DONE |
| P10 | Agent Runtime + Tool System（Agent Contract / Agent State / Execution Context / Runtime / Tool Contract / Tool Execution / Tool Registry / Tool Result / 基础 Agent 生命周期） | ✅ DONE |
| P11 | Writing Workflow / 完整小说创作 Pipeline | ⬜ NOT STARTED |
| P12+ | 后续高级能力（Critique→Revision 完整循环 / HITL 完整流程 / PC UI / Android UI / 自动后台任务 / 知识更新闭环等） | 🔮 FUTURE |

**Current Phase = P11**（下阶段任务：Writing Workflow / 完整小说创作 Pipeline）。

### 关于「什么时候才能真正开始小说创作」

重要区分状态（避免把"模型已接入"误认为"已能创作"）：

| 阶段完成 | 意味着 | 不意味着 |
|---|---|---|
| **P9 DONE** | LLM 可以被系统**正确调用**（DeepSeek / MiMo 已作为可靠 Provider 接入，经 `LLMGateway` 注入 API Key 与 fake transport 测试） | ❌ 已经可以完成小说创作 |
| **P10 = DONE** | Agent / Tool 执行基础已具备（Agent 可经 `LLMGateway` 调用 LLM、调用 Tool、读取 ToolResult、继续执行、受 `maxSteps` 保护正常结束） | ❌ 已经可以完成小说创作（Writing Agent / Workflow 属 P11） |
| **P11 = NOT STARTED** | （后完成）完整小说创作 Pipeline 基础闭环建立 | ❌ 当前未实现 |

**当前状态**：`P9 DONE` / `P10 DONE` / `P11 NOT STARTED` → **Agent Runtime + Tool System = DONE，完整小说创作 Pipeline = NOT STARTED**。

### MiMo 特殊写作处理（规划登记，不在 P9/P10 实现）

后续已登记需求：部分模型（尤其 MiMo）可能有**过度解释 / 额外说明 / 元话语 / 不符合小说正文风格**的输出。**不在 Provider 层处理**，未来在 **P11 的最终创作输出流程**中处理：

```text
Writing → Output Post-processing → MiMo-specific handling → Final Review → Final Novel Text
```

> **MiMo writing-specific post-processing = P11**（本次禁止实现，仅做规划登记）。

## P7 Android Functional Loop

P7 形成 Android 端第一条完整功能闭环：

```text
Database
    ↓
Application API
    ↓
Compose
    ↓
Android DI
    ↓
Novel List
    ↓
TXT Import
    ↓
Analysis
    ↓
Vocabulary Candidate
    ↓
Validation
```

### P7.0 — Storage 驱动无关数据库初始化

- `DatabaseInitializer.initializeDatabase(driver)`：建表 + 3 个守卫触发器（Original 只读写保护、禁止 Variant→Variant）+ 幂等初始化。
- Android 使用 `AndroidSqliteDriver → DatabaseInitializer`；JVM 使用 `QianyanDbFactory.open()`（JDBC 专用入口）。两者共用同一套驱动无关初始化。

### P7.1 — Application 查询 API

- `NovelUseCases.listOriginals()`（Android 首页小说列表入口）。
- `VocabularyUseCases.findCandidatesByNovel(novelId)`（候选词查询入口）。

### P7.2 — Android Compose 构建基础

- Kotlin Compose plugin + Compose Runtime / Foundation / Material3 / Activity Compose / Lifecycle Compose / ViewModel Compose。

### P7.3 — Android DI

```text
QianyanApplication
    ↓
AndroidSqliteDriver
    ↓
DatabaseInitializer
    ↓
MockLLMGateway
    ↓
ApplicationContainer
    ↓
Application Use Cases
```

### P7.4 — 第一版功能 UI

- Novel List：`NovelListScreen` / `NovelListViewModel` / `NovelListUiState`（Loading / Empty / Success / Error）+ 重试。
- Compose Theme / Color / Type（Material3）+ `MainActivity` 装配。

> **当前 UI 是功能验证版本，不是最终 UI 设计。** 采用 Apple-inspired 基础视觉方向（中性色 / 大标题 / 留白 / 圆角卡片，Material3 基础设施），最终 Apple-inspired UI/UX 将在后续 UI 重设计阶段完成。

### P7.5 — TXT 导入（Android SAF）

```text
SAF
 ↓
Uri
 ↓
ByteArray + displayName
 ↓
NovelListViewModel
 ↓
TxtUseCases.importTxtAsOriginal
 ↓
TXT Pipeline
 ↓
Novel + Document
```

- 使用 Android SAF（`OpenDocument`），**无需存储权限**。
- **Uri 只存在 Android 平台层**，不进入 Application / Domain。
- 支持重复导入检测（contentHash → isDuplicate）、UTF-8 / 空文档错误处理（中文提示）。

### P7.6 — Analysis（Mock）

```text
Novel
 ↓
TxtDocument
 ↓
Novel Vocabulary
 ↓
AnalysisUseCases
 ↓
MockLLMGateway
 ↓
AnalysisOutput
 ↓
VocabularyCandidate
```

- `AnalysisScreen` / `AnalysisViewModel` / `AnalysisUiState`（Idle / Loading / Success / SuccessWithWarnings / Error）已完成。
- `AnalysisViewModel` 通过 `TxtUseCases.findDocumentsByNovel` → `VocabularyUseCases.getOrCreateNovelVocabulary`（创建/复用 NOVEL 词库）→ `AnalysisUseCases.analyzeTxtOriginal` → `findCandidatesByNovel` 完成候选查询与展示。
- **当前仍然使用 `MockLLMGateway`**；**真实 DeepSeek / MiMo Provider 不属于 P7**。

### P7.7 — 最终验收

- 架构边界审计、错误处理审计、全量测试、Android Unit Test、assembleDebug、Git 审计。

### P7 边界（未在 P7 实现）

- ❌ 真实 DeepSeek / MiMo Provider　❌ Agent / Orchestration / Workflow　❌ Knowledge / Character / Event 正式系统　❌ Candidate 确认流程　❌ Desktop UI　❌ PC / Cloud Backend　❌ Hilt / Koin / Room / DataStore / Navigation Framework

## P8.1 Task / Checkpoint Persistence

P8.1 将领域层已存在的 `Task` / `Checkpoint` 模型落地为可持久化数据层（**不含 TaskManager 状态机**）：

- **core:model**：`Checkpoint` 补齐 `revision`（1..3）与 `createdAt`（持久化必需的最小调整）。
- **Schema v2**：新增 `Task` / `Checkpoint` 表（TEXT 强类型 ID、INTEGER epoch 毫秒、枚举名状态、JSON snapshot）。
- **v1 → v2 migration**（`1.sqm`）：仅新增两表，不删除/修改既有 P0–P7 表，旧数据兼容，可重复幂等初始化。
- **`TaskRepository` / `SqliteTaskRepository`**：`create / findById / update / delete / saveCheckpoint / findCheckpoints / findLatestCheckpoint`。
- **Task ↔ Checkpoint 映射**（`StorageMappers`）与 Task 存储异常（`TaskNotFoundException` / `RevisionLimitExceededException`）。
- **Checkpoint 持久化**：`saveCheckpoint` 同事务同步 Task `revision_count` / `updated_at`。
- **revision 1..3 约束**：领域 / 仓储校验 + DB `CHECK`（`revision BETWEEN 1 AND 3`、`revision_count BETWEEN 0 AND 3`）。
- **事务原子性**：create / update / delete / saveCheckpoint / migration 均为单事务，不留半成品数据。
- **测试**：`TaskRepositoryTest`（14 用例）+ `TaskMigrationTest`（1 用例）+ 全量回归通过。

> 明确：P8.1 **不实现** TaskManager / Task 状态机 / start-pause-resume-cancel-complete-fail 的 Application 管理（属 P8.2）。

## P8.2 Task Manager / Task State Machine

P8.2 在 P8.1 持久化之上为 `Task` / `Checkpoint` 提供严格生命周期的 Application 管理（`application/usecase/task/`）：

- **`TaskManagerUseCases`**：`create / findById / start / pause / resume / cancel / complete / fail / saveCheckpoint / restoreCheckpoint / findCheckpoints`。所有操作读取 Task → 状态机校验 → 变更字段 + 更新 `updatedAt` → 经 `TaskRepository` 单事务持久化（不触碰 SQLDelight / 不写 SQL）。
- **确定性 `TaskStateMachine`**（纯函数）：冻结转换表 `PENDING→RUNNING/CANCELLED`、`RUNNING→PAUSED/COMPLETED/FAILED/CANCELLED`、`PAUSED→RUNNING/COMPLETED/CANCELLED`；非法转换（如 PENDING→PAUSED、RUNNING→PENDING）一律拒绝；终态 `COMPLETED / CANCELLED / FAILED` 拒绝一切操作；**`FAILED→RUNNING` 自动 retry 不属于 P8.2（DEFER 至 Workflow 层）**。
- **Checkpoint revision 严格顺序**：`nextRevision = revisionCount + 1`（0→1→2→3，上限 3），调用方不可指定 revision；P8.1 的 DB `CHECK` / `UNIQUE(task_id, revision)` 作为最后防线。
- **Checkpoint**：`saveCheckpoint` 由 Manager 控制 revision；snapshot 沿用结构化 `JsonObject` 最小契约（`{type, input, output}`），不新增数据库列、不给 Task 增加 input/output 字段；`restoreCheckpoint` 只恢复最近 Checkpoint 上下文，**不重新执行、不调用 LLM/Agent/Tool**。
- **类型化错误**：新增 `TaskNotFound / InvalidTaskStateTransition / RevisionLimitExceeded / CheckpointNotFound / TaskAlreadyCompleted / TaskAlreadyCancelled / RestoreFailure`；`ErrorMapper` 在 `UnknownStorage` 之前映射 Task 存储异常。
- **ApplicationContainer 手动 DI**：注入 `TaskRepository`（P8.1），暴露 `tasks: TaskManagerUseCases`（`fromDriver` / `open` 均装配）。
- **Android**：仅两处 sealed `ApplicationError` exhaustive `when` 编译修复（`AnalysisViewModel` / `NovelListViewModel`），无 Task UI / Navigation 变化。
- **测试**：`TaskStateMachineTest`（4）+ `TaskManagerUseCaseTest`（26）+ `TaskManagerIntegrationTest`（6，含 SQLite close/reopen 状态保持）。

> 明确：P8.2 **完成 Task 生命周期 / 状态管理**；**真实任务执行引擎 / Agent / Tool / Workflow 编排仍属后续阶段（P8.3+）**。

## P8.3 Task Execution

P8.3 在 P8.2 状态机之上，为 `Task` 提供 Application 层**受管执行**的最小纵向切片（`application/usecase/task/`）：

- **`TaskRunner`（薄执行适配器）**：只复用 `TaskManagerUseCases` 的 `start / saveCheckpoint / complete / fail`，**不绕过状态机**（禁止 `task.copy(status=...)` 直写 Repository）。
- **IMPORT 纵向切片**：`PENDING → RUNNING → 真实调用 TxtUseCases.importTxtAsOriginal(source, title) → saveCheckpoint → COMPLETED`；失败 `RUNNING → fail → FAILED`（记录错误并继续抛出类型化错误）。
- **Checkpoint snapshot（结构化 JSON）**：`{type:"IMPORT", input:{title, source}, output:{documentId, novelId, isDuplicate, contentHash, encoding, charCount, chapterCount, blockCount}}`；输入只存元信息（不持久化 bytes），不新增 DB 列 / 不给 Task 加 input/output 字段。
- **类型化拒绝**：`WRITING / PLANNING / KNOWLEDGE_UPDATE`（及 ANALYSIS）→ `ApplicationError.UnsupportedTaskType`（P8.3 无执行能力），不经字符串判断错误。
- **错误复用**：`TxtImportFailed / UnsupportedEncoding / EmptyDocument / InvalidText / ParseFailed / TaskNotFound / InvalidTaskStateTransition / RevisionLimitExceeded / TaskAlreadyCompleted / TaskAlreadyCancelled` 等全部走类型化 `ApplicationError`。
- **恢复语义**：`restoreCheckpoint()` 只恢复最近 Checkpoint 上下文，**不重新执行任务**；不实现 retry / resume execution / 自动重试 / 超时 / 取消令牌 / 后台 worker（全部 DEFER）。
- **ApplicationContainer 手动 DI**：新增 `taskRunner: TaskRunner`（复用 `tasks` + `txts` + `errorMapper`）。
- **ANALYSIS**：SHOULD，**DEFER**（`analyzeTxtOriginal` 需要前置 IMPORT 产出的 `documentId` / `vocabularyId`，属跨任务依赖，P8.3 不做以保持最小范围）。
- **Android**：仅两处 sealed `ApplicationError` exhaustive `when` 编译修复（`AnalysisViewModel` / `NovelListViewModel`），无 Task UI / Navigation 变化。
- **测试**：`TaskRunnerTest`（8）+ `TaskExecutionTest`（3）+ `TaskExecutionIntegrationTest`（2，真实调用 `importTxtAsOriginal` + SQLite close/reopen 后 COMPLETED 与 checkpoint 均保留）。

> 明确：P8.3 **完成 IMPORT 受管执行**；**ANALYSIS 执行（DEFER）/ Agent / Tool / Workflow 编排 / 真实 Provider 执行仍属后续阶段**。

## P9 Real LLM Provider Integration

P9 把两个真实写作模型作为**可靠 Provider** 接入现有 Provider 架构（`Application → LLMGateway → DeepSeekLLMGateway / MiMoLLMGateway / MockLLMGateway`），**不实现任何"如何写小说"逻辑**：

- **真实 Provider**：`DeepSeekLLMGateway`（`deepseek-v4-flash`，`https://api.deepseek.com/chat/completions`，`Authorization: Bearer <key>`）与 `MiMoLLMGateway`（`mimo-v2.5-pro`，`https://api.xiaomimimo.com/v1/chat/completions`，`api-key: <key>`）；两者均为官方 OpenAI 兼容 API，复用共享 `OpenAiChatCompletion`（DTO/JSON 全部限定在 `provider:impl`）。
- **HTTP Transport**：零第三方依赖，JDK 17 `java.net.http.HttpClient`（`JdkLlmHttpClient`）；经 `LlmHttpClient` 接缝注入 fake 实现保证普通测试不依赖真实网络。
- **API Key 安全**：注入式构造参数，不进 Git / docs / README / 日志 / 异常 / 仓库 / UI；测试仅用 fake key（`test-key`）。
- **ProviderException 映射**：复用既有 `ProviderException` 子类（Timeout / RateLimit / ProviderUnavailable / InvalidResponse / MalformedOutput / TokenLimit），仅按结构化字段（HTTP 状态码 + error.code）分类，禁止 message 子串匹配；API Key 缺失 → `ProviderUnavailable`。
- **模型选择**：`ModelProfile` 新增 `DEEPSEEK_V4_FLASH` / `MIMO_V2_5`；`AnalysisUseCases` 通过现有 Provider seam 注入模型（默认 MOCK，行为不变）。
- **测试**：DeepSeek（11）+ MiMo（10）网关契约测试（成功 / Key 缺失 / 超时 / 429 / 4xx / 5xx / 非法 JSON / 缺字段 / Token 超限）+ `RealProviderApplicationIntegrationTest`（3，真实网关经 fake transport 走通 AnalysisUseCases 全链路）；`MockLLMGateway` 原有测试全部保持通过。

> 明确：P9 只负责把 **DeepSeek-V4-Flash / MiMo-V2.5** 可靠接入；**Agent / Tool / Workflow / Orchestrator / HITL / 完整小说创作 Pipeline = NOT STARTED（后续阶段）**。

## P10 Agent Runtime + Tool System

P10 在 P8 之上（不动 `TaskRunner` 职责）与 P9 之上（复用 `LLMGateway` 契约）落地**最小同步 Agent Runtime + Tool System**，**不做任何小说创作**：

- **Tool 领域模型**（`core:model/tool`）：`ToolName`（复用于 `:model:agent` 已有类型）/ `ToolParameterSpec` / `ToolDefinition` / `ToolRequest` / `ToolResult`，参数用结构化 `JsonObject`，不引入复杂 Schema Framework。
- **Tool System**（`:agent:tool`）：`Tool` 契约 + `ToolRegistry`（注册/查找/覆盖）+ `ToolExecutor`（请求校验：必填参数/未知参数 + 执行 + 未归一异常归一） + `ToolContext`（最小跨工具追踪）+ 类型化 `ToolError` / `ToolException`（`ToolNotFound` / `InvalidToolRequest` / `ToolExecutionFailed`）。不实现 Tool Discovery / 动态插件 / 权限系统。
- **Agent Runtime**（`:agent:runtime`）：`AgentExecutionContext` + `AgentStep`（Final / Tool）+ `AgentResponseParser` + `AgentResult` + 同步 `AgentRuntime` 执行循环（`IDLE → RUNNING ─Tool→ RUNNING → COMPLETED / FAILED`），支持 **LLM → Tool call → ToolResult → LLM → Final** 全链；`maxSteps` 防无限循环（超限抛 `AgentException.MaxStepsExceeded`，`AgentState.FAILED`）。
- **架构边界**：`:agent:runtime` 只依赖 `:provider:api` 的 `LLMGateway`（不接触 `provider:impl` / HTTP / API Key / SQLite / Android）；Tool 只经 `ToolExecutor` 调用。**未接 `TaskRunner`**（P10 测试独立运行 AgentRuntime，避免为"接起来"产生耦合）。
- **Persistence / Concurrency**：默认 **transient、无新表、无 migration**；**同步执行**，无 Coroutine / Flow / Worker。
- **测试**：`ToolRegistryTest`（5）/ `ToolExecutionTest`（8）/ `AgentRuntimeTest`（6）/ `AgentToolIntegrationTest`（2）/ `AgentProviderIntegrationTest`（2），全部用 `FakeProvider`（`LLMGateway` 脚本化假实现）+ `EchoToolForAgent`，**无真实 DeepSeek / MiMo 网络请求**。

> 明确：P10 只验证 **Agent 能调用 LLM / 调用 Tool / 读取 ToolResult / 继续执行 / 正常结束**；**Writing/Planning/Critique/Revision Agent、Novel Workflow、HITL、KnowledgeUpdate、完整小说创作 Pipeline = NOT STARTED（DEFER 到 P11+）**。

## 模块结构

```
core/model         领域模型（纯数据，零依赖；含 P10 Tool 模型）
core/engine        TXT 确定性引擎（纯 JVM，零 AI 依赖）
core/engine/analysis 确定性 TXT → AnalysisInput 构建（P6，零 AI/存储依赖）
agent/tool         Tool 系统（Tool 契约 / Registry / Executor / Context / Error，P10）
agent/runtime      Agent Runtime（同步执行循环 / maxSteps / Step 解析 / Result，P10）
agent/agents       六个 Agent 定义（占位）
agent/orchestration  Agent 编排（占位）
provider/api       AI Provider 抽象契约（LLM 契约 + 请求/响应 + 异常，P6）
provider/impl      AI Provider 实现（Mock / DeepSeek / MiMo，P6 / P9）
storage            SQLDelight + SQLite / Repository / Backup / Task·Checkpoint persistence（P8.1）
application        Use Case 层（DI 容器 + 错误边界；含 P6 AnalysisUseCases / P8.2 TaskManagerUseCases）
runtime            平台 Runtime 抽象（占位）
app/android        Android 客户端（P7 功能验证 UI：列表 / TXT 导入 / Analysis）
app/desktop        Desktop 客户端（占位）
test/e2e           E2E 测试（占位）
```

### 分层架构（冻结 V4.1）

```
UI → Application → Task Manager → Agent Orchestrator → 6 Agent
     → Tool Layer → Core Engines（确定性）→ Knowledge / Memory → Storage（SQLite）
```

核心为 **Kotlin/JVM 共享 Core**，Android 与 PC 通过不同 Runtime Adapter 复用同一套 Domain / Agent / Workflow / Tool / Provider 契约。

## 技术栈

- Kotlin / JVM（toolchain 17）
- Gradle（Version Catalog 统一依赖）
- SQLDelight + SQLite（JDBC driver，JVM 可跑测试；Android driver，P7.3）
- Android Compose（Material3 / Activity Compose / Lifecycle Compose / ViewModel Compose，P7）
- kotlinx.serialization / kotlinx.datetime
- JUnit 5 + kotlin.test

## 构建与测试

```bash
# 全量测试（P9/P10 验证通过：JVM test BUILD SUCCESSFUL，含 Agent/Tool 测试）
./gradlew test

# 关键模块测试（P4 TXT Pipeline / Storage / Application）
./gradlew :core:engine:test :storage:test :application:test

# Agent Runtime + Tool System 测试（P10 新增）
./gradlew :agent:tool:test :agent:runtime:test

# Android 单元测试（P9：20 tests）
./gradlew :app:android:testDebugUnitTest

# Android Debug APK
./gradlew :app:android:assembleDebug
```

> 注：P7.7 验收实测 `./gradlew test`、`:app:android:testDebugUnitTest`、`:app:android:assembleDebug` 均 BUILD SUCCESSFUL；APK 生成于 `app/android/build/outputs/apk/debug/android-debug.apk`。

## TXT Pipeline（P4）

```
TXT 文件 → TxtImporter（编码/BOM）→ TextNormalizer（确定性规范化）
        → ChapterDetector（章节识别）→ Structured Document（章节/段落块）
        → TxtRepository → SQLite（SQLDelight）
```

- 支持 UTF-8 / UTF-8 BOM；CRLF / CR / LF 归一；空行折叠；段落边界保留。
- 章节识别：`第X章` / `卷一 第一章` / `Chapter 1` / `序章` / `尾声` / `番外` 等常见格式。
- 原文永不修改（`originalText` 保留），`reconstruct()` 可从结构化结果恢复正文。
- 完全确定性：相同输入 + 相同规则版本 → 相同结果（`contentHash` + `ruleVersion` 校验）。

## 文档

- [实现计划](docs/planning/qianyan-implementation-plan.md)
- [总体设计](docs/planning/qianyan-master-plan.md)
- [架构评审](docs/planning/qianyan-v4.2-architecture-review.md)
- [项目状态](docs/status/qianyan-project-status.md)
