# Qianyan 接入 DSH 前的现状审计（pre-dsh-audit）

> **审计对象**：`feature/novel-ide` 分支，HEAD `4cefd0d`，工作树含未提交改动（见文末附录）
> **审计方式**：只读代码审计（grep + 逐文件阅读），**未修改任何代码**
> **审计目的**：回答 Q1（我们已自研了哪些 Agent Runtime 能力）、Q2（哪些可能被 DSH 替代）、Q3（哪些是领域核心必须保留）
> **约束声明**：本报告**不假设 DSH 具备任何具体能力**。第 5 部分 B 的判据是"该能力是否与小说领域耦合"，而非"DSH 是否支持"。

---

## 审计覆盖范围

| 模块 | main 源码 | 测试 | 说明 |
|---|---|---|---|
| `core/model` | 54 | 8 | 领域模型（全部领域契约） |
| `core/engine` | 11 | 4 | 确定性引擎（Markdown / 文本处理） |
| `agent/tool` | 5 | 3 | 工具契约与调度 |
| `agent/runtime` | 5 | 5 | Agent 循环 |
| `agent/agents` | 3 | 2 | Skill 声明 |
| `agent/orchestration` | **0** | 1 | **空壳模块（见 §1.1）** |
| `provider/api` | 5 | 1 | LLM 网关契约 |
| `provider/impl` | 7 | 5 | DeepSeek / MiMo / Mock 实现 |
| `storage` | 51 | 38 | SQLDelight + Repository（25 `.sq` + 21 `.sqm`） |
| `application` | 106 | 128 | UseCase 层（30 个子包） |
| `runtime` | **0** | 1 | **空壳模块** |
| `app/android` | 34 | 8 | Android UI（PAUSED） |
| `app/desktop` | 25 | 5 | Compose Desktop UI |
| `test/e2e` | 0 | 1 | 端到端测试 |
| **合计** | **281 生产文件** | **211 测试文件** | 全仓 `.kt` 共 516 |

**第三方 Agent/LLM 框架依赖：零。** `gradle/libs.versions.toml` 中 grep `langchain|koog|spring-ai|openai-java|anthropic|okhttp|ktor|langgraph|semantic-kernel|dspy|litellm` **无命中**。HTTP 用 JDK `java.net.http`，JSON 用 `kotlinx.serialization`，持久化用 SQLDelight，DI 为手写 `ApplicationContainer`（`koin` 在版本目录中声明但**任何模块都未使用**）。

---

# 第一部分：Agent Runtime 能力盘点

> 成熟度取值：`完整实现（可用于生产）` / `部分实现（能用但不完整）` / `接口定义（只有接口，无实现）` / `原型（能跑，不稳定）` / `不存在`

| # | 能力 | 是否存在 | 代码位置 | 成熟度 | 是否自研 |
|---|---|---|---|---|---|
| 1 | Agent Loop | 是 | `agent/runtime/.../AgentRuntime.kt:59-97` | 完整实现 | 是 |
| 2 | Session 管理 | 是 | `application/.../session/AgentSessionUseCases.kt:41` | 完整实现 | 是 |
| 3 | Session 持久化 | 是 | `storage/.../AgentSession.sq:12`、`SqliteAgentSessionRepository.kt:9` | 完整实现 | 部分（封装 SQLDelight） |
| 4 | Tool Registry | 是 | `agent/tool/.../ToolRegistry.kt:11-34` | 完整实现 | 是 |
| 5 | Tool Schema 定义 | 是（最小） | `core/model/.../tool/ToolModels.kt:12-29` | 部分实现 | 是 |
| 6 | Tool 执行调度 | 是 | `agent/tool/.../ToolExecutor.kt:28-78`、`application/.../tool/ProductToolService.kt:68-88` | 完整实现 | 是 |
| 7 | Tool 权限（allow/deny） | 是 | `AgentRuntime.kt:81-85`、`application/.../action/ActionPolicyUseCases.kt:59-94` | 完整实现 | 是 |
| 8 | Prompt 组装 | 是（手写字符串） | `AgentRuntime.kt:129-144`、`PlannerAgent.kt:76-153`、`PlanningContextAssembly.kt:48-122` | 部分实现 | 是 |
| 9 | Model 调用 | 是 | `provider/impl/.../openai/OpenAiChatCompletion.kt:45-82`、`transport/JdkLlmHttpClient.kt:24` | 完整实现 | 是（JDK HttpClient） |
| 10 | Provider 抽象 | 是 | `provider/api/.../LLMGateway.kt:14-18`、`ProviderModels.kt`、`ProviderException.kt` | 完整实现 | 是 |
| 11 | Provider 切换 | 是 | `app/android/.../QianyanApplication.kt:82-105`、`DefaultProviderAssembler.kt:30-52`、`app/desktop/.../di/DesktopGraph.kt:138` | 完整实现 | 是 |
| 12 | Retry / Fallback | 部分 | Retry：`WorkflowOrchestrator.kt:530-540`、`TaskQueueUseCases.kt:197-217`；Fallback **不存在** | 部分实现 | 是 |
| 13 | Streaming 支持 | **否** | 反向证据：`OpenAiChatCompletion.kt:66` — `put("stream", false)` | 不存在 | — |
| 14 | Sandbox | **否** | 全仓 `sandbox` 零命中 | 不存在 | — |
| 15 | Subagent | **否** | 全仓 `subagent|childAgent|spawnAgent` 零命中 | 不存在 | — |
| 16 | Background Jobs | 部分 | `application/.../taskqueue/TaskWorker.kt:31-90`、`TaskQueueUseCases.kt`、`task/TaskRunner.kt:46` | 部分实现 | 是 |
| 17 | Plugin 系统 | **否** | 无运行时插件系统（仅 Gradle plugins） | 不存在 | — |
| 18 | MCP 支持 | **否** | 唯一命中为留白注释 `AuthorPreferenceUseCases.kt:34` | 不存在 | — |
| 19 | Web / Browser | **否** | Agent/Tool 层零命中 | 不存在 | — |
| 20 | Filesystem 工具 | **否** | 仅 UI 侧 TXT 导入（`app/android/.../MainActivity.kt:100-112`、`app/desktop/.../adapter/DesktopFilePicker.kt`），未注册为 Tool | 不存在 | — |
| 21 | Shell 工具 | **否** | `ProcessBuilder|Runtime.getRuntime|\.exec\(` 零命中 | 不存在 | — |
| 22 | Plan / Goal / Todo | 部分 | 领域规划：`PlannerAgent.kt:31`、`PlanningExecutionUseCase.kt`、`WorkflowOrchestrator.kt:151-163`；Agent 自主 Todo/Goal **不存在** | 部分实现 | 是 |
| 23 | Approval 机制 | 是 | `WorkflowOrchestrator.kt:294-304,617-629`、`ActionPolicyUseCases.kt:105-155`、`ConfirmationExecutionUseCase` | 完整实现 | 是 |

## 1.1 关键发现：两个空壳模块

- **`agent/orchestration`**：`settings.gradle.kts` 中 `include(":agent:orchestration")`，但 `src/main` **不存在**；唯一文件是 `agent/orchestration/src/test/java/.../OrchestrationSmokeTest.kt`，内容仅 `assertEquals(4, 2 + 2)`。其 `build.gradle.kts` 声明了 `api(project(":agent:runtime"))`、`implementation(project(":core:model"))`、`implementation(project(":storage"))`，**源码无一使用**（`:storage` 为冗余依赖）。
  → **它不是 orchestrator。真正的编排在 `application/`**：`WorkflowOrchestrator.kt`（669 行）与 `NovelAgent.kt`（856 行）。
- **`runtime`**：同样只有 `src/test/.../RuntimeSmokeTest.kt`，无 `src/main`。

## 1.2 各能力的实现细节与缺口

### 1 · Agent Loop — 完整实现
- `AgentRuntime.run(agent, input): AgentResult`（`AgentRuntime.kt:59`）用 `while (ctx.steps < maxSteps)`（默认 10）循环：`gateway.chat(ProviderRequest(...))` → `AgentResponseParser.parse`（`AgentStep.kt:33-62`）解析为 `AgentStep.Tool`（`{"tool":..,"arguments":..}`）或 `AgentStep.Final`（`{"answer":..}` 或纯文本兜底）→ 工具结果经 `renderToolObservation` 以 `USER` 消息回填 → 下一轮；超步抛 `MaxStepsExceeded`（`:96`）。
- 被 6 个生产 Agent 复用：`PlannerAgent.kt:155`、`WriterAgent.kt:156`、`CritiqueAgent`、`RevisionAgent`、`KnowledgeUpdateAgent`、`IdeaUnderstandingAgent.kt:69`。4 个测试文件覆盖。
- **缺口**：① 工具调用是**自造 JSON 约定**，非原生 function-calling（未用 `tools`/`tool_calls` 字段）；② 每轮只执行 **1 个**工具，无并行 tool calls；③ 同步阻塞，无 streaming；④ 循环内无 checkpoint / 中断恢复；⑤ 循环内不做 provider 重试。

### 2-3 · Session 管理 / 持久化 — 完整实现
- `AgentSessionUseCases`（`AgentSessionUseCases.kt:41`）：`startSession(novelId, workflowId?, taskId?)` + `activate/pause/complete/fail/cancel/touch/attachWorkflow/attachTask`，转换经 `AgentSessionLifecycleRules.canTransition`（确定性），非法转换抛 `InvalidOperation`。
- 落库：`AgentSession.sq:12`（session_id / project_id / novel_id / workflow_id / task_id / status / 三个时间戳），含 `findResumableSessionByProject`（status IN CREATED/ACTIVE/PAUSED）。
- **缺口**：只持久化**会话身份**，不持久化执行中间态（steps / messages / toolCalls）。`AgentExecutionContext`（`AgentExecutionContext.kt:14`）明写「默认保持 transient，不持久化」，与 `AgentSession` **是两套互不相通的东西**；文件注释明示「不改 AgentRuntime（I3 Runtime Integration = DEFERRED）」——**会话与 AgentRuntime.run() 之间无接线**。`findResumableSessionByProject` 注释明确「不实现 Resume Engine」，真正续跑靠 `WorkflowOrchestrator` 的 durable Workflow/Checkpoint。

### 4-5 · Tool Registry / Schema — 完整 / 部分
- `ToolRegistry`（`ToolRegistry.kt:11`）：`LinkedHashMap` + `@Synchronized`，`register/find/all/contains/size`。类注释自述「P10 不做：Tool Discovery、动态插件、跨进程注册、权限系统」。
- `ToolDefinition(toolName, description, parameters)` + `ToolParameterSpec(name, description, required)`（`ToolModels.kt:12-29`）——**无类型系统**，参数只有名字。`ToolExecutor.validate`（`ToolExecutor.kt:53-78`）按它做必填校验 + 拒绝未声明参数。
- 给模型的声明是 **systemPrompt 里的纯文本行**（`AgentRuntime.kt:136-142`：`"  - name*: desc（参数: a*,b）"`），不是结构化 `tools` 参数。
- `AgentContract.inputSchema/outputSchema`（`AgentModels.kt:26-27`）是 `JsonObject` 但**从未被填充或消费**（死字段）。

### 6-7 · Tool 执行调度 / 权限 — 完整实现，但**存在两条路径**
- **路径 A（合规）**：`ProductToolService.invoke(activityId, request)`（`:68-88`）—— Activity 锚 → 查表 → `ActionPolicy.evaluate` → 放行才执行 → 成功/失败/被拦截**都写 `ToolCallLog`**。注册 7 个只读工具（`ProductTools.kt`：`get_project` / `get_project_state` / `get_novel` / `list_chapters` / `get_chapter` / `get_latest_draft` / `search_vocabulary`）。调用点：`ApplicationContainer.kt:248`、`NovelAgent.kt:651`。
- **路径 B（绕过）**：`AgentRuntime.kt:84` 直接 `toolExecutor.execute(lastTool, ToolContext())`，权限**只**由 `:81` 的 `allowedTools` 白名单判定，**不经过 `ProductToolService` / `ActionPolicy` / `ToolCallLog`**。且 6 个 Agent 实例化时传入的是**空注册表**：`IdeaUnderstandingAgent.kt:71`、`WriterAgent.kt:158`、`CritiqueAgent.kt:85`、`KnowledgeUpdateAgent.kt:85`、`PlannerAgent.kt:157`、`RevisionAgent.kt:118`（均 `ToolExecutor(ToolRegistry())`）。
- `ActionPolicy`（`ActionPolicyUseCases.kt:59-94`）是纯确定性规则表：`READ/SEARCH/ANALYZE/VALIDATE`、`CREATE/EDIT_WORKING_DRAFT/PROPOSE_CHANGE` → Allowed；`MODIFY_PLAN/UPDATE_WORLD_MODEL/MODIFY_CANONICAL/COMMIT_CANONICAL` → NeedsHuman；`DELETE` → Denied。有测试。
- **缺口**：两套策略不联动（`AgentRuntime` 不感知 `ActionPolicy`）；7 个工具**全部只读**，无可写工具。

### 8 · Prompt 组装 — 部分实现
- `PlanningContextAssembly`（`:48`）从 Novel/Variant/Vocabulary/Memory/StoryFoundation/AuthorContext 确定性组装 `PlanningContext`；各 Agent 用 `buildString` 手写分段 prompt（`【创作请求】`/`【小说背景】`/`【已确认的故事基础 Story Foundation】`/`【作者偏好】`/`【相关人物】`/`【记忆】`/`【词库】`/`【续篇来源】`/`【创作决策 Decision Policy】`）。
- **缺口**：**无模板引擎 / 无 prompt 文件 / 无版本化**；每个 Agent 各写一份 renderer，无共享抽象；工具协议说明硬编码在 `AgentRuntime.systemPrompt`。

### 9-11 · Model 调用 / Provider 抽象 / 切换 — 完整实现
- DeepSeek（`https://api.deepseek.com/chat/completions`，`Authorization: Bearer`）与 MiMo（`https://api.xiaomimimo.com/v1`，`api-key` header）**共用** `OpenAiChatCompletion`；`kotlinx.serialization` 手搓请求/解析；HTTP 用 JDK 17 `java.net.http.HttpClient`（注释明确「零第三方 HTTP 依赖」）。错误按状态码结构化映射为 7 个 `ProviderException` 子类（429→RateLimit、5xx→ProviderUnavailable、`context_length_exceeded`→TokenLimit、超时→Timeout）。
- `LLMGateway` 契约极薄：`interface LLMGateway { fun chat(request: ProviderRequest): ProviderResponse }`（`LLMGateway.kt:14`）；`ProviderCredentialStore` 抽象把 API Key 挡在 Domain 之外（`ProviderConfiguration` 无 apiKey 字段）。**契约层无 tool-calling、无 streaming、无 suspend/Flow**。
- 切换：设置页选 Provider → 校验真实 Key（缺 Key 拒绝切换）→ 落 `ProviderSelectionStore` → `rebuild()` **重建整个 ApplicationContainer**。Android 侧有 `CoroutineLlmGateway`（`:18`）把阻塞网关包成 `suspend`。

### 12 · Retry / Fallback — 部分
- **生效的重试只在上层编排**：`WorkflowOrchestrator.shouldRetryStep`（`:530-540`，`maxStepAttempts=5`）按错误分类重试（`ProviderUnavailable`→RETRYABLE；`ProviderCredentialMissing`→NEEDS_HUMAN）；`TaskQueueUseCases`（`:197-217`）有 `maxAttempts` + `retryBackoffMillis=1000` 重排。
- **死代码**：`ProviderConfig.RetryConfig`（`ProviderConfig.kt:10-13`，`maxRetries=2`）与 `AgentContract.maxRetries`（`AgentModels.kt:33`）全仓除定义外**零引用**；`LLMGateway` 的 kdoc 声称「实现负责超时/重试语义」，但 `OpenAiChatCompletion.chat` 与 `JdkLlmHttpClient` **完全没有重试逻辑**。
- **Fallback 完全不存在**：无 provider 降级链、无 model 降级。

### 13-21 · 不存在的能力
Streaming（`put("stream", false)` 硬编码）、Sandbox、Subagent、Plugin、MCP、Web/Browser、Filesystem 工具、Shell 工具 —— 全部**不存在**。其中：
- **Subagent 的替代物是"固定相位调度"**：`NovelAgent`（`NovelAgent.kt:108`）按固定七相位 `INTENT→SKILL→CONTEXT→EXECUTE→REVIEW→GATE→COMMIT` 顺序调用既有 5 个 Agent（注释明言「有限执行计划：固定七相位、无 `while(true)`、无『LLM 决定下一步』」）。
- **Sandbox 的替代物是"分层纪律 + 结构守卫测试"**（见第三部分），不是隔离执行。
- **唯一"检索"是字符串包含匹配**（`ProjectIndexUseCases.find:114`、`SearchVocabularyTool.call:247`）。

### 22 · Plan / Goal / Todo — 部分
- "Plan" 以**领域产物**形态存在且成熟：`PlannerAgent` 生成结构化 `ChapterPlan`；`PlanningSnapshot` 编解码 + durable `PLANJSON2:` seam 落库可恢复；`WorkflowOrchestrator` 按相位推进。`NovelAgent` 有固定相位执行计划并逐步落 `Activity`（`:174-273`）。
- **缺口**：**无 Agent 自主的 Todo/Goal 管理工具**（无 `todo_write`/`task_create`，无待办清单模型，无跨轮目标跟踪/重规划）；`OrchestrationPhase` 是硬编码 enum 而非 LLM 可改写的计划。

### 23 · Approval 机制 — 完整实现
- 流程：`ActionPolicy.evaluate` 判定 HIGH → `NeedsHuman` → 建 `WorkflowHumanGate(PENDING)` + `Workflow.status=WAITING_HUMAN` → 用户批准 → `approveGate`（幂等，DB 事务）→ Draft 确认 + 回 `RUNNING`。
- 幂等键 `gateKey = workflowId:stepKey:draftId`；有落库（`Workflow.sq`）、有回放测试；`CommitUseCases.requireApproval:447` 校验 `CommitApproval` 凭据链。
- **非绕过设计**：`NovelAgent` 需要人工时返回 `WAITING_HUMAN` + `artifactId`，由调用方批准后 `resume`。
- **缺口**：无超时/自动升级；批准粒度是 **Draft/Gate 级**，无「按工具/按命令」细粒度审批（因为也没有 shell/写工具）。

---

# 第二部分：Qianyan 领域能力盘点

| # | 能力 | 是否存在 | 代码位置 | 成熟度 |
|---|---|---|---|---|
| 1 | Project 数据模型 | 存在 | `core/model/.../project/ProjectModels.kt:36`（Project）、`:51`（ProjectState） | 完整实现 |
| 2 | Novel World Model | 存在 | `core/model/.../context/StoryWorldContext.kt:32`；`application/.../writing/context/StoryWorldContextResolver.kt` | 部分实现 |
| 3 | Chapter 模型 | 存在 | `core/model/.../story/StoryModels.kt:44`、`:57` | 完整实现 |
| 4 | Character 模型 | 存在 | `core/model/.../character/CharacterModels.kt:27,42,84,116` | 部分实现 |
| 5 | World Rule 模型 | 存在 | `core/model/.../world/WorldModels.kt:12,24` | 部分实现 |
| 6 | Timeline 模型 | 存在 | `core/model/.../timeline/TimelineModels.kt:21,29,39,53,95` | 部分实现 |
| 7 | Foreshadowing 模型 | 存在 | `core/model/.../story/Foreshadow.kt:26`；状态机 `story/ForeshadowLifecycle.kt:17,31,34,39` | 部分实现 |
| 8 | Story Foundation | 存在 | `core/model/.../foundation/StoryFoundation.kt:50,68`；`application/.../foundation/StoryFoundationDecisionUseCases.kt:107` | 完整实现 |
| 9 | Context Engine | 存在 | `core/model/.../context/ContextPack.kt:210`（Selector）、`:154`（Request）、`:122`（Budget）、`:189`（Pack） | 完整实现 |
| 10 | Context Builder | 存在 | `application/.../context/ContextEngineUseCases.kt:51`；`core/model/.../lcl/ChapterContextPack.kt:88,104` | 完整实现 |
| 11 | Context Snapshot | 部分 | 冻结语义 `ContextPack.kt:189` + 指纹 `:256`；**无任何持久化/表** | 部分实现 |
| 12 | Retrieval Index | **不存在** | 显式排除：`ProjectIndexModels.kt:32`、`ProjectIndexUseCases.kt:50`、`GenreTaxonomy.kt:15` | 不存在 |
| 13 | Project Index | 存在 | `core/model/.../projectindex/ProjectIndexModels.kt:105`；`application/.../index/ProjectIndexUseCases.kt:81`（rebuild） | 完整实现 |
| 14 | Novel Agent | 存在 | `core/model/.../novelagent/NovelAgentModels.kt:52,114`；`application/.../agent/NovelAgent.kt:108,163,304,365` | 完整实现 |
| 15 | Novel Skills | 存在 | `core/model/.../skill/SkillModels.kt:61`；`agent/agents/.../CoreSkills.kt:63,88,115,139,170` | 部分实现 |
| 16 | Qianyan Tools | 存在 | `core/model/.../tool/ToolModels.kt:23,31,38`；`application/.../tool/ProductTools.kt`（7 个只读） | 部分实现 |
| 17 | Draft | 存在 | `core/model/.../writing/WritingModels.kt:33,57,62`；`application/.../writing/WriterUseCases.kt:29,38,50,62` | 完整实现 |
| 18 | Validation | 存在 | `spec/SpecModels.kt:27,33`；`draft/WorkingDraftValidator.kt:36,46`；`knowledgeupdate/KnowledgeValidator.kt:24,29`；`revision/RevisionGate.kt:22,35` | 部分实现 |
| 19 | Diff | 存在 | **自研**：`core/model/.../change/TextDiffer.kt:19,38`；`change/ChangeModels.kt:75,88` | 完整实现 |
| 20 | Change | 存在 | `change/ChangeModels.kt:106`；`application/.../change/ChangeUseCases.kt:47,64` | 部分实现 |
| 21 | Change Artifact | 存在 | `change/ChangeModels.kt:135,144,165,181,196`；`ChangeUseCases.kt:64,80,85` | 部分实现 |
| 22 | Commit | 存在 | `core/model/.../commit/CommitModels.kt:43,75,114,130,145,160`；`application/.../commit/CommitUseCases.kt:73,141,265` | 完整实现 |
| 23 | History | 存在 | `commit/CommitModels.kt:56,75`；`CommitUseCases.kt:209,217`；`storage/.../CommitHistoryRepository.kt` | 完整实现 |
| 24 | Canonical Story | 部分 | 载体 `WritingModels.kt:33` + `story/StoryModels.kt:44` + `foundation/StoryFoundation.kt:50`；唯一提交入口 `CommitUseCases.kt:141`；**无统一 CanonicalStory 实体，且存在旁路** | 部分实现 |
| 25 | SQLite Storage | 存在 | `storage/build.gradle.kts`；25 个 `.sq`；`database/DatabaseInitializer.kt:133`；`QianyanDbFactory.kt` | 完整实现 |
| 26 | Migration | 存在 | 21 个 `.sqm`（v1→v22）；`DatabaseInitializer.kt:28,133-270` | 完整实现 |
| 27 | Repository | 存在 | `storage/.../repository/`（48 文件：接口 + `Sqlite*` 实现） | 完整实现 |

## 2.1 关键能力的深度说明

### Context Engine（#9-10）
- **纯确定性选择算法，不调用任何模型。** 域层文件头明示「选择全部确定性（无 LLM / 无随机 / 不用当前时间决定顺序与取舍）」「不负责 LLM Request / Model Selection / Provider / Gateway / Prompt 渲染」。
- 入口：`ContextEngineUseCases.build(request: ContextRequest): ContextPack`（`:51`）；`isStale(pack)`（`:107`）；`requireValid(request)`（`:109`，校验 `maxTokens>0`、`candidateHorizon>0`）。构造注入 `ProjectUseCases / ChapterUseCases / AgentSessionUseCases / List<ContextSource> / Clock / ErrorMapper` —— **不含任何 LLM/Provider 依赖**。
- 算法：`ContextSelector.select(candidates, budget, estimator)`（`ContextPack.kt:212`）按 `ContextPriority.rank` 稳定排序 + 逐条累加尺寸，**单条超预算只淘汰该条**；淘汰集记入 `ContextBudgetGuard.omittedItemIds`。版本指纹 `ContextPackVersion.of`（`:256`）覆盖请求范围 + 入选条目 + 淘汰集。
- 7 个来源（`ContextSources.kt`）+ 工厂 `defaultContextSources:284`。
- **注意**：`ContextRequest` / `ContextBudget` / `ContextPack` **全部定义在 `core:model`（域层）**，不在应用层。

### Project Index（#13）
- **派生 + 可重建 + 可丢弃**：`rebuild(projectId, variantId?): ProjectIndex`（`:81`，`@Synchronized`）、`discard`（`:89`）、`isStale`（`:129`）。模型文件明示「索引永远从 Canonical 数据重建；索引不是事实来源，也不允许 Index → Canonical 的恢复路径」。
- 派生来源（`buildEntries:161`）：NOVEL←`novels.getNovel`；CHAPTER←`chapters.listByNovel`；DRAFT←`writer.latestDraft`（只存元数据含字数，**不存正文**）；STORY_FOUNDATION←`foundations.getStoryFoundation`；VOCABULARY←`vocabularies.findCandidatesByNovel`。
- **存储为进程内 `MutableMap`，不落库**（有意）。后台重建注册于 `ApplicationContainer.kt:421`（`TaskKind.PROJECT_INDEX_REBUILD`）。

### Validation（#18）
| 类别 | 实现 | 入口 |
|---|---|---|
| 结构/边界 | `WorkingDraftValidator` | `WorkingDraftValidator.kt:46` `validate(draft): ValidationResult`（终态/Project 一致/Chapter 作用域/session 归属/基线/内容长度/受控 Markdown 降级 WARNING） |
| 领域 | `KnowledgeValidator`、`RevisionGate`、`GenreTaxonomyRules` | `KnowledgeValidator.kt:29`（immutable canon：Original 禁 UPDATE/REMOVE）；`RevisionGate.kt:35`（`revisionCount < 3`） |
| 预算 | `ContextEngineUseCases.requireValid` + `ContextSelector` + `TokenBudgetGuard` | `ContextEngineUseCases.kt:109`；`ContextPack.kt:212` |

- 统一结果模型：`ValidationResult{passed, issues, ...}` + `ValidationIssue` + `IssueSeverity{ERROR,WARNING,INFO}`（`SpecModels.kt:23,27,33`）。
- **缺口**：**无单一 Validation 入口/编排**（无 `ValidationUseCases` 聚合器）；无「正文 vs 世界模型一致性」的确定性校验（`KnowledgeValidator` 注释自述「目标存在性 / timeline 冲突：当前 Domain 无对应持久化索引，不在此做」）；语义评审依赖 LLM（`CritiqueAgent`）。

### Diff（#19）
- **自研，无第三方库**（`TextDiffer.kt:4` 文件头明示「无第三方库」「项目内不存在 diff 依赖」）。
- 算法：**行级公共前缀/后缀裁剪，O(n)**，不做 LCS/Myers（自述「偏粗但完全确定」）；换行归一化 `normalizeNewlines:22`。
- 入口：`TextDiffer.diff(baseText, workingText): TextDiff{op, hunks, unchanged/added/removedLines}`（`:38`）。唯一业务调用点：`ChangeUseCases.kt:115`、`CommitUseCases.kt:158,397`。
- **无 patch 应用器**（`ChangeModels.kt:24`：Artifact 只是提案载体，不是执行命令）。

### Commit / History（#22-23）与 Canonical（#24）
- **Commit 是真正的原子事务**：`CommitUseCases.commit:141` 在 `workflows.inTransaction{}`（`:151`）内做两次事务内重检（重复提交 `:153`、Canonical 基线变化 `:158`），写新 Draft + `CommitHistoryEntry` 一起成败；七项前置校验 `validateForCommit:338`（含 STALE_ARTIFACT 内容级判定 `:397`）。
- Revert 产生**新** Commit 不删历史（`:265`）。
- **Canonical 无统一实体/投影**：无 `CanonicalStory` 类型或表；"正典"由约定 + `CommitUseCases` 承担（自称唯一提交入口），正典正文载体是 `ChapterDraft` 版本链。**该"唯一入口"声明与代码不符**（见第三部分第 8 项）。
- 世界模型事实（Character/Event/WorldRule）**不在 Commit 管控内**，改写走 `EntityOverride`，与 Draft 正典路径互不相通。

---

# 第三部分：跨层与重复检查

| # | 检查项 | 结论 |
|---|---|---|
| 1 | 两套 Agent Session 模型 | **存在（3 个 Session 概念，但分属三层、职责不同）** |
| 2 | 两套 Tool 定义 | **存在（契约单一，但分发有两条路径）** |
| 3 | 两套 Provider 抽象 | **未发现** |
| 4 | 两套 Context 机制 | **存在（且都在生产路径被调用；另有第三套组装；另有一个死代码）** |
| 5 | 两套 Change 机制 | **未发现（change/ 与 commit/ 是分工），但存在第二条变更旁路** |
| 6 | 两套 Storage 访问方式 | **未发现** |
| 7 | 绕过 Application 直接访问 Storage | **存在（仅限组合根，可接受）；`agent/orchestration` 有冗余依赖** |
| 8 | 绕过 Change Layer 直接修改 Canonical | **存在（13 处，多数绕过 Gate/Commit）** |
| 9 | Provider 依赖 Change Layer | **未发现** |
| 10 | UI 直接访问 Storage | **未发现（唯一命中为组合根，判定允许）** |

## 详细证据

### 1 · 两套 Agent Session 模型 — 存在（3 个）
- 领域会话（落库）：`core/model/.../session/AgentSessionModels.kt:60`（`AgentSession`）、`:37`（`AgentSessionStatus`）、`:87`（`AgentSessionLifecycleRules`）；UseCase `application/.../session/AgentSessionUseCases.kt:41`；表 `storage/.../AgentSession.sq:12`。
- 运行时执行上下文（transient）：`agent/runtime/.../AgentExecutionContext.kt:14`（文件头 `:12`「默认保持 transient，不持久化」），**唯一构造点** `AgentRuntime.kt:51`。
- 第三个：`application/.../chapter/ChapterWritingUseCases.kt:103`（`class ChapterWritingSession internal constructor`）。
- **判定**：三者层级与职责不同（可持久化身份 / 单次循环瞬时态 / 写作链进程内幂等会话），**不算重复实现**；但若以"两个 session 概念"为判据则成立。**真正的问题不是重复，而是 `AgentSession` 与 `AgentRuntime.run()` 之间没有接线**（见第一部分 2-3 的缺口）。

### 2 · 两套 Tool 定义 — 存在（分发两条路径）
- 定义/契约层**只有一套**：`core/model/.../tool/ToolModels.kt` + `agent/tool/.../Tool.kt` + `ToolRegistry.kt:11` + `ToolExecutor.kt:20`；应用层 `ProductTools.kt`（7 个 `class *Tool`）+ `ReadOnlyProductTool.kt:38`。
- **分发 A**：`ProductToolService.kt:37`（内部自建 `ToolRegistry:48` / `ToolExecutor:51`，串 ActionPolicy + ToolCallLog）；调用点 `ApplicationContainer.kt:248`、`NovelAgent.kt:651`。
- **分发 B（绕过 A）**：`AgentRuntime.kt:84` 直接 `toolExecutor.execute(...)`，实例化点**全部传空注册表**：`IdeaUnderstandingAgent.kt:71`、`WriterAgent.kt:158`、`CritiqueAgent.kt:85`、`KnowledgeUpdateAgent.kt:85`、`PlannerAgent.kt:157`、`RevisionAgent.kt:118`（6 处 `ToolExecutor(ToolRegistry())`）。
- **判定**：结构上是一套契约，但**存在两条可用性语义**（一条过权限与审计，一条不过）。判为存在。

### 3 · 两套 Provider 抽象 — 未发现
- 唯一 LLM 网关接口：`provider/api/.../LLMGateway.kt:14`。其余 `provider/api/` 文件是配置/异常/模型/运行时类型，非网关。
- `CoroutineLlmGateway.kt:18`（Android）与 `DesktopOfflineLlmGateway.kt:33`（Desktop）是**同接口的适配器/实现**。
- 传输层另有 seam `provider/impl/.../transport/LlmHttpClient.kt:10`（JVM / Android 两实现）——是 HTTP 抽象，非 LLM 网关。
- 附带（不构成重复）：application 中有 8 个命名为 `*Gateway` 的**应用层门面**接口（`AuthorIntelligenceGateway`、`ChapterWorkflowGateway`、`WriterGateway`、`DecisionModelGateway`、`FoundationDecisionGateway`、`IdeaFirstGateway`、`AuthorCoreGateway`、`AuthorDnaGateway`）——命名重合，语义是应用门面。

### 4 · 两套 Context 机制 — 存在（两套并存且都在生产路径）
- **I6**：`core/model/.../context/ContextPack.kt`（Request/Pack/Selector/BudgetGuard）。生产调用：`ContextEngineUseCases.kt:51`（build）+ `ContextSources.kt`；消费 `NovelAgent.kt:477`、`WorkspaceUseCases.kt:353`、`WorkspaceProjections.kt:39`。
- **LCL**：`core/model/.../lcl/ChapterContextPack.kt`（TokenBudgetGuard:34 / ChapterContextPack:57 / ChapterContextProjector:88）。生产调用：`ChapterContextCompileUseCases.kt:64,77`；暴露 `ApplicationContainer.kt:550`；消费 `RollingHorizonUseCases.kt:35`、`app/desktop/.../StoryExtraTabs.kt:243,348`。
- **第三套**：`application/.../writing/planning/PlanningContextAssembly.kt:31,41,48` → `PlanningContext`；调用者 `NovelAgent.kt:124`、`WritingExecutionUseCase.kt:39`、`PlanningExecutionUseCase.kt:40,106`。
- **死代码**：`core/model/.../context/ContextModels.kt:16` 的 `ContextCandidate`（浮点评分体系）已被 `ContextPriority` 枚举取代，全仓仅 3 处引用（定义 + 1 条注释），**无生产/测试使用**。（同文件 `UserWritingRequest` 仍在广泛使用。）

### 5 · 两套 Change 机制 — 未发现（分工），但有旁路
- `change/`（I9，审查层，**不落库**）：`ChangeUseCases.kt:47`，进程内 `store`（`:56`，注释「内存 Artifact Store，不落库、不加表、不加迁移」）；调用点 `NovelAgent.kt:607`、`CommitUseCases.kt:116,146`、`WorkspaceUseCases.kt:202,388,391,393`。
- `commit/`（I10，唯一 Canonical 提交）：`CommitUseCases.kt:73,146,229`；调用点 `NovelAgent.kt:269,331`。
- 二者是**分工**（前者只读生成 Diff/Artifact，后者才写入），不构成两套 Change。
- **但存在第二条变更旁路**：`override/OverrideUseCases.kt:31,39`、`story/StoryStateVariantUseCases.kt:195`（`replaceOverride`）、`writing/WriterUseCases.kt:50`（`saveContent`）—— 均不经 Change/Commit。

### 6 · 两套 Storage 访问方式 — 未发现
- 唯一使用 SQLDelight 生成 Queries 的位置是 `storage/.../repository/Sqlite*Repository.kt`（20 个文件）。对 `Queries(` / `QianyanDb` / `SqlDriver` / `.driver` 在 `agent/ app/ application/ core/ provider/ runtime/` 的**非测试** grep 返回 **0 行**。
- `ApplicationContainer` 只注入 `Sqlite*Repository` 接口。**单一访问方式。**

### 7 · 绕过 Application 直接访问 Storage — 存在（仅组合根）
- `app/android/.../QianyanApplication.kt:13,14`（`DatabaseInitializer`、`QianyanDb`）—— 组合根，`:44` 注释「只通过 ApplicationContainer 访问能力」。
- `app/desktop/.../di/DesktopGraph.kt:10,11`（`QianyanDbFactory`、`QianyanDbHandle`；`:91` open、`:94` `ApplicationContainer.fromDriver`）—— 组合根。
- `agent/`、`runtime/`、`core/`：**0 行**。
- `application/` 内的 storage import 属 Application 层自身的 Repository 契约依赖，允许。
- **冗余依赖**：`agent/orchestration/build.gradle.kts` 声明 `implementation(project(":storage"))`，但该模块无 `src/main`、唯一测试无 storage 引用 → **未使用依赖**。

### 8 · 绕过 Change Layer 直接修改 Canonical — 存在（13 处非测试写入）

| path:line | 写入对象 | 是否过 Gate/Commit |
|---|---|---|
| `application/.../writing/WriterUseCases.kt:54`（`saveContent`） | `draftRepository.save` | **否**（UI 直达正文编辑） |
| `application/.../writing/WriterUseCases.kt:65`（`stampControlledMarkdown`） | `draftRepository.save` | 原语本身**无守卫**；被 Commit 路径使用（`CommitUseCases.kt:163,283`），**也被** `WriterFacade.kt:82,95` 直接调用（无 Commit） |
| `application/.../chapter/ChapterWritingUseCases.kt:188`（`finalize`） | `draftRepository.save` | **否** |
| `application/.../workflow/WorkflowOrchestrator.kt:263`（`executeFinalize`） | `draftRepository.save` | **否** |
| `application/.../writing/WritingExecutionUseCase.kt:71` | `draftRepository.save` | **否** |
| `application/.../writing/confirmation/ConfirmationExecutionUseCase.kt:80` | `draftRepository.save`（置 CONFIRMED） | **否** |
| `application/.../writing/revision/RevisionExecutionUseCase.kt:61` | `draftRepository.save` | 仅 `RevisionGate`（次数上限），**无 Commit** |
| `application/.../chapter/ChapterUseCases.kt:77` | `chapterRepository.createNextChapter` | **否** |
| `application/.../workflow/WorkflowOrchestrator.kt:374` | `chapterRepository.createNextChapter` | **否** |
| `application/.../writing/planning/PlanningExecutionUseCase.kt:139` | `chapterRepository.createNextChapter` | **否** |
| `application/.../foundation/StoryFoundationDecisionUseCases.kt:178` | `storyFoundationRepository.upsertStoryFoundation` | 过 Human Gate（`:163`），但**不经 Change/Commit** |
| `application/.../story/StoryStateVariantUseCases.kt:195` | `novelRepository.replaceOverride` | **否** |
| `application/.../override/OverrideUseCases.kt:39` | `novelRepository.saveOverride` | **否** |

- `CommitUseCases` 自称"唯一 Canonical 提交入口"（`CommitUseCases.kt:46`），实际**仅覆盖 Draft 正文提交一条路径**。
- 唯一调用 `commits.commit(...)` 的业务路径是 `NovelAgent.kt:268`。即"合规路径"只有 Novel Agent 的 WRITING/REWRITE 分支。
- `CONFIRMED` 是 Knowledge Update 的唯一放行前提（`ConfirmationExecutionUseCase.kt:25`），却完全在 Change/Commit 之外达成。
- 更严重的后果：**用户手工保存正文（`saveContent`）路径上无 Diff、无 Artifact、无 ActionPolicy、无 Gate、无 Commit、不写 CommitHistory**，但改变了 `latestByChapter` 即正典正文。

### 9 · Provider 依赖 Change Layer — 未发现
- `grep -rn "import com\.qianyan\.\(model\.change\|model\.commit\|application\)" provider/` → **0 行**。
- 附注（相邻问题）：`app/android/build.gradle.kts` 与 `app/desktop/build.gradle.kts` 同时依赖 `:provider:api` **与** `:provider:impl`，与 `settings.gradle.kts` 中「调用方只依赖 provider:api，禁止依赖具体实现」的注释相冲突 —— 但 app 是组合根，属另一类问题。

### 10 · UI 直接访问 Storage — 未发现
- `app/*/ui/**`、`*ViewModel.kt`、`*Controller.kt`：**0 处** import `com.qianyan.storage`。
- 唯一命中是组合根（`QianyanApplication.kt:13-14`、`DesktopGraph.kt:10-11`），**判定允许**：`DesktopGraph.kt:28` 自述"PC 桌面装配根"；架构文档明确其为装配根（`qianyan-novel-ide-architecture.md:963,945,1016`、`p20-architecture-freeze.md:53`、`README.md:326`）。
- Android 侧刻意不用 JDBC 专用的 `QianyanDbFactory`，改用 `AndroidSqliteDriver`（`QianyanApplication.kt:71`）。

## 3.1 边界守卫测试（结构纪律的实际执行者）

仓库有 **16 个**"读源码 + 断言禁用 token"的结构守卫测试（`readText()` + `walkTopDown()`，剔除注释行）：

| 测试文件 | 守什么 |
|---|---|
| `application/.../context/ContextBoundaryTest.kt:84` | 扫 `usecase/context`；禁 SQLite/Repository/`LLMGateway`/`AgentRuntime`/写路径 |
| `application/.../index/ProjectIndexBoundaryTest.kt:21,47,68,82` | 扫 `usecase/index` + `core/model/projectindex`；**禁搜索中间件（FTS/Embedding/Vector/RAG）**；白名单 storage import 仅 `StoryFoundationRepository` |
| `application/.../action/ActionPolicyUseCasesTest.kt:260` | 扫 `usecase/action`；禁 `DecisionPolicy/AuthorContext/LLMGateway`（与 P19 隔离，判定须确定性） |
| `application/.../change/ChangeBoundaryTest.kt:101` | 扫 `usecase/change`；禁 storage/Repository/LLM/`Commit/Revert/History` |
| `application/.../commit/CommitBoundaryTest.kt:97,121` | 扫 `usecase/commit` + `core/model/commit`；禁 SQLite/LLM/`CommitPolicy/RevertPolicy/Branch/Merge/Rebase/CRDT/EventSourcing`；**要求复用** `ActionPolicyUseCases/ChangeUseCases/inTransaction` |
| `application/.../draft/WorkingDraftIsolationTest.kt:92` | 扫 `usecase/draft`；禁 storage/Repository/`Artifact/Diff/Commit` |
| `application/.../log/AgentLogUseCasesTest.kt:258` | 扫 `usecase/log` + `core/model/log`；禁 `WorldModel/.../LLMGateway` |
| `application/.../tool/ReadOnlyProductToolsTest.kt:304` | 扫 `usecase/tool`；禁 Repository/写路径/`LLMGateway` |
| `application/.../taskqueue/TaskQueueBoundaryTest.kt:23,51,90,109,127` | 扫 `usecase/taskqueue` + 模型；校验 `.sq` DDL 外键；**要求既有文件不得被删/改** |
| `application/.../workspace/WorkspaceBoundaryTest.kt:20,49,68,98` | 扫 `usecase/workspace` + 模型；白名单 storage import 仅 `StoryStateRepository` |
| `application/.../agent/NovelAgentBoundaryTest.kt:22,52,64` | 扫 `usecase/agent` + 模型；禁 storage/`AgentRuntime`/`ToolExecutor`/`while (`；**断言 5 个既有 Agent 契约与 agentId 未被改写** |
| `application/.../di/SkillRegistryBindingTest.kt:63,92` | 断言每个 Skill 的 `allowedTools` 必须是真实已注册的 ProductTool |
| `agent/agents/.../SkillRegistryTest.kt:196` | 扫 Skill 源码；禁 storage/LLM/`AgentRuntime`（只读声明） |
| `app/desktop/.../DesktopWorkspaceTest.kt:25,42,63` | 扫 desktop `ui/workspace`；禁 19 个 token；要求经 `workspace.*` Facade |
| `app/desktop/.../DesktopWriterTest.kt:344` | 扫 desktop UI 源码；禁 `container.*Repository`/`workflowOrchestrator`；要求经 `writerGateway` + `workflowFacade` |
| `storage/.../ForeignKeyHardeningTest.kt` | 校验 DB 触发器/外键（非源码扫描） |

> **这是本项目的实际"边界执行机制"**：没有 Sandbox，但有 16 组源码级断言。

---

# 第四部分：依赖关系（按真实 build.gradle.kts）

```text
                          ┌──────────────┐
                          │  core:model  │  ← 无内部依赖（只有 kotlinx.serialization / datetime）
                          └──────┬───────┘
        ┌───────────────┬────────┼────────────┬─────────────────┐
        │ api           │ api    │ api        │ api             │ api
   ┌────▼─────┐   ┌─────▼────┐  │     ┌──────▼──────┐   ┌──────▼──────┐
   │core:engine│  │agent:tool│  │     │ provider:api│   │   storage   │
   └──────────┘   └─────┬────┘  │     └──────┬──────┘   └──────┬──────┘
                        │ impl  │            │ api             │ api
                  ┌─────▼───────▼──┐         │                 │
                  │ agent:runtime  │─────────┘                 │
                  └───────┬────────┘   (impl provider:api)     │
                          │ api                                │
                  ┌───────▼────────┐                           │
                  │  agent:agents  │                           │
                  └────────────────┘                           │
                                                               │
   ┌───────────────────────────────────────────────────────────▼──────────┐
   │                            application                               │
   │  api: core:model · storage · core:engine                             │
   │  impl: provider:api · agent:runtime · agent:tool · agent:agents      │
   │  testImpl: provider:impl                                             │
   └───────────────┬──────────────────────────────┬───────────────────────┘
                   │ impl                          │ impl
        ┌──────────▼─────────┐          ┌─────────▼──────────┐
        │    app:android     │          │    app:desktop     │
        │  + storage         │          │  + core:engine     │
        │  + provider:api    │          │  + provider:api    │
        │  + provider:impl   │          │  + provider:impl   │
        │  + sqldelight.aar  │          │  + sqldelight.runtime / sqlite.jdbc
        └────────────────────┘          └────────────────────┘

   空壳模块（无 src/main）：
     agent:orchestration ── api agent:runtime, impl core:model + storage(未使用)
     runtime            ── api core:model
     test:e2e           ── agent:orchestration, agent:agents, provider:impl
```

**关键事实**：
- `application` 用 `api(project(":storage"))` —— 因此 app 模块**传递可见** storage（这就是 `DesktopGraph` 能直接 `QianyanDbFactory.open()` 的原因）。
- `application` 对 `provider` 只有 `implementation(project(":provider:api"))`；**`provider:impl` 只出现在 `testImplementation`**，生产运行时的 impl 由 app 模块注入。
- `app:*` 同时依赖 `:provider:api` 与 `:provider:impl`，与 `settings.gradle.kts` 注释「调用方只依赖 provider:api，禁止依赖具体实现」存在张力（组合根例外，架构文档承认）。
- **`agent/*` 不依赖 `storage`（除空壳 orchestration 的冗余声明），不依赖 `application`** —— 依赖方向是单向下行的。

---

# 第五部分：三个结论

## 结论 A：我们当前有什么

**当前 Agent Runtime 是 100% 自研的，且完整度出乎意料地高——但它是"确定性工作流引擎 + 薄 Agent 循环"的组合，不是通用 Agent 运行时。**

具体说：底层是一个**真实可用的多步 Agent 循环**（`AgentRuntime`，10 步上限，自造 JSON 工具协议，6 个生产 Agent 复用）；中层是一个**成熟的确定性工作流编排**（`WorkflowOrchestrator` + `NovelAgent` 固定七相位 + Durable Workflow + Checkpoint + 24 个稳定错误码 + 幂等人工门）；外圈是一整套**自研的 Provider 抽象**（零第三方 Agent/LLM 框架，HTTP 用 JDK，JSON 用 kotlinx，持久化用 SQLDelight）。

同时，**它缺少通用 Agent 运行时的全部"重型"能力**：Streaming、Sandbox、Subagent、Plugin、MCP、Web、文件/Shell 工具**全部不存在**；工具只能只读（7 个）；工具调用不是原生 function-calling；Prompt 无模板化；Session 身份虽落库但**与运行时执行态完全不相通**。

**一句话**：**自研（100%），并且已经形成"领域工作流"这一差异化资产；但通用运行时能力（流式/工具生态/沙箱/插件/子代理）几乎为零。**

## 结论 B：哪些未来最可能被同类 Runtime 替代

> **判据**：该能力是否**与小说领域解耦**（越通用越可替代）。**不代表 DSH 已有此能力。**

| 能力 | 通用性 | 替代难度 | 影响模块 | 现有调用点 |
|---|---|---|---|---|
| **Agent Loop**（循环 + 步数上限 + 工具结果回填） | 极高 | **低** | `agent/runtime` | 6 个 Agent：`PlannerAgent.kt:155`、`WriterAgent.kt:156`、`CritiqueAgent`、`RevisionAgent`、`KnowledgeUpdateAgent`、`IdeaUnderstandingAgent.kt:69` |
| **Provider 抽象与切换** | 极高 | **低** | `provider/api`、`provider/impl`、两个 app 的 DI | `DefaultProviderAssembler`、`DesktopGraph.saveProviderConfig`、`QianyanApplication.setProvider` |
| **Model 调用 / HTTP 传输** | 极高 | **低** | `provider/impl` | `OpenAiChatCompletion` → `DeepSeek/MiMo` 网关 |
| **Streaming**（当前不存在） | 极高 | **低**（新增而非替换） | `LLMGateway` 契约 + 实现 + UI 消费 | 无（需新增） |
| **Retry / Fallback**（当前半成品 + 死代码） | 高 | **低** | `provider/api`（`RetryConfig` 死代码）、编排层重试 | `WorkflowOrchestrator.kt:530`、`TaskQueueUseCases.kt:197` |
| **Prompt 组装** | 中高（有领域模板但机制通用） | **中** | 6 个 Agent 各自的 `renderInput` + `PlanningContextAssembly` | `PlannerAgent.kt:76-153`、`PlanningContextAssembly.kt:48` |
| **Tool Registry / Schema / 调度** | 高 | **中**（因为要保住 ActionPolicy + ToolCallLog 审计） | `agent/tool` + `application/tool` | `ProductToolService.kt:68`、`AgentRuntime.kt:84` |
| **Session 管理与持久化** | 高（身份部分） | **中** | `application/session` + storage 表 | `NovelAgent.resolveSession`、`AgentSessionUseCases` |
| **Background Jobs**（当前是同步 Worker） | 高 | **中** | `taskqueue` 全部 | `TaskWorker.drain`、`ApplicationContainer.kt:410` |
| **Plan / Goal / Todo**（Agent 自主部分不存在） | 中高 | **中**（领域 Plan 必须自留） | `agent/*` + 新增 | 无（需新增） |
| **Tool 权限（allow/deny）** | 中（规则表通用，但语义是领域的） | **中高** | `ActionPolicyUseCases` | `ProductToolService.kt:82`、`CommitUseCases.evaluateCommit:89` |
| **MCP / Plugin / Subagent / Web / Shell / FS**（均不存在） | 极高 | **低**（纯新增） | 无存量 | 无 |
| **Agent Loop 内的工具分发（路径 B）** | 高 | **低**（本应删除/统一） | `AgentRuntime.kt:74-91` | 6 处空注册表实例化 |

**替代难度说明**：
- **低** = 当前是"薄封装/无领域语义"，替换只影响 1-2 个模块，且调用点集中。
- **中** = 有跨层契约（如 `LLMGateway` 被 app DI 注入、或 Tool 路径与 ActionPolicy/ToolCallLog 审计绑定）。
- **中高** = 规则表本身通用，但它承载的是**领域安全语义**（哪些动作需要人工批准），替换会动摇产品底线。

## 结论 C：哪些必须自己保留

| 能力 | 为什么不能被替代 | 现在的位置 | 未来强化方向 |
|---|---|---|---|
| **Context Engine**（选择/排序/预算/取舍理由） | 这是"给 AI 看什么"的领域算法：`ContextPriority` 排序 + 单条超预算淘汰 + `omittedItemIds` 可解释 + 版本指纹。它决定了长篇一致性的上限，且**已被 Inspector 暴露给作者**（"AI 的视野"）。通用 Runtime 只会"塞上下文"，不会解释取舍 | `core/model/.../context/ContextPack.kt`（纯函数）+ `application/.../context/ContextEngineUseCases.kt`（7 个 Source） | 领域级 Source 扩充（人物状态/伏笔压力/风格样本）；把"为什么裁掉"升级为面向作者的解释层 |
| **Story State / 世界模型**（Character / CharacterState / WorldRule / Event / Timeline / Foreshadow + EntityOverride） | 这是小说的**长期记忆与正典**，是 89 章不写崩的唯一依据。通用 Runtime 没有"人物在甲章的状态"这种概念 | `core/model/{character,world,timeline,story}` + `storage/.../StoryStateRepository.kt` + `StoryWorldContextResolver` | 补 Original 写入路径（当前仅 Variant 可写）；补"正文 vs 世界模型一致性"的确定性校验 |
| **Foreshadow 生命周期状态机** | 4 态确定性迁移 + 条件 UPDATE + 并发检测（`affected != 1` 抛错）。伏笔是长篇的核心债务，回收曲线是产品的差异化 | `core/model/.../story/ForeshadowLifecycle.kt:34` + `ForeshadowLifecycleUseCases.kt:39` + 条件 SQL | 状态集扩展（文件头已列 PAYOFF_READY/ESCALATING 等未加入）；打通规划层 5 态与落地层 4 态 |
| **Change → Diff → Commit（正典变更链）** | 这是"AI 改了小说"的**可审计 + 可回退**保证：ChangeArtifact（提案）→ Human Gate → `commit` 原子事务（两次事务内重检）→ CommitHistory（可 revert）。通用 Runtime 的"编辑文件"没有这层语义 | `change/`（I9，内存）+ `commit/`（I10，事务）+ `TextDiffer`（自研 O(n)） | **堵住 13 处旁路**（见第三部分第 8 项），让"唯一提交入口"名副其实 |
| **Human Gate（人工批准门）** | 这是产品底线：AI 只能**提议**，人必须点接受。幂等 `gateKey`、落库、凭据链、拒绝/要求修订三条路径俱全 | `WorkflowOrchestrator.kt:294-304,617-629` + `ActionPolicyUseCases.kt:105-155` | 从 Draft/Gate 级细化到"按动作"；补 diff 预览式审批与批准理由 |
| **Durable Workflow + Checkpoint** | 长篇创作是**跨天跨周的长任务**，必须可中断可恢复。写入 `Checkpoint`（`saveCheckpoint`/`restoreCheckpoint`），且 `DecisionPolicySnapshot` 随 Checkpoint 持久化（FD-4：Resume 只解码不重算） | `WorkflowOrchestrator`（669 行）+ `Workflow.sq` + `TaskModels.Checkpoint` | 把 `AgentRuntime` 的中途状态也纳入 checkpoint（当前循环内不可恢复） |
| **Author Intelligence / Author DNA / Decision Policy**（P16–P19） | 从作者自己的文字里学出"短句主导、动作代替心理"，并让**每一次生成都遵循同一份政策**。这是"写出作者味道"的唯一来源 | `application/.../author/*`、`decision/*` + `core/model/author/*` | 让 `DecisionPolicy` 真正进入每一轮 prompt（当前只在 Planning 注入） |
| **Validation（三类确定性校验）** | 结构/领域/预算三类校验是"确定性质量闸"，与 LLM 评审互补（LLM 评审不确定，不能当闸门） | `WorkingDraftValidator` / `KnowledgeValidator` / `RevisionGate` / `ContextEngineUseCases.requireValid` | 补统一 Validation 入口；补"正文 vs 世界模型"一致性校验 |
| **Project Index** | 派生层设计（可重建、不落库、拒绝 FTS/Vector/RAG）是刻意的成本与确定性取舍，且被 I13 队列接入 | `application/.../index/ProjectIndexUseCases.kt:81` | 保持"派生"定位；按需扩展条目类型 |
| **SQLite Storage + Migration + 48 个 Repository + 16 组边界守卫测试** | schema v22 / 21 个 `.sqm` / 三次原生触发器守卫（Original 不可 UPDATE/DELETE）。**这是数据资产与合规底线**，不能交给外部运行时 | `storage/` | 保持；新增能力一律 `additive` 迁移 |
| **NovelAgent 的七相位编排 + 24 个稳定错误码** | 这是"小说创作流程"的领域形状（INTENT→SKILL→CONTEXT→EXECUTE→REVIEW→GATE→COMMIT），通用 Runtime 的 agent loop 没有这个形状 | `application/.../agent/NovelAgent.kt:108,806` | 保持确定性；避免退化成"LLM 决定下一步" |

---

# 附录 A：本次审计中最值得注意的发现

1. **两个声明在 settings 里但从未实现的模块**：`agent/orchestration`（无 `src/main`，仅 `assertEquals(4, 2+2)` 的 smoke test，却声明了未使用的 `:storage` 依赖）与 `runtime`（同样只有 smoke test）。**"编排"实际在 `application/`。**
2. **`CommitUseCases` 的"唯一 Canonical 写入入口"声明与代码不符**：至少 13 处非测试写入，多数绕过 ActionPolicy / Human Gate / Commit / CommitHistory（含用户手工保存正文、AI 写作/改写落库、finalize、confirm、createNextChapter、override）。
3. **AgentRuntime 内部存在绕过应用安全层的工具执行路径**（`AgentRuntime.kt:84`），且 6 个 Agent 用空注册表实例化 ⇒ LLM 循环内的工具执行不过 ActionPolicy、不写 ToolCallLog。
4. **Retry 配置是死代码**：`ProviderConfig.RetryConfig` 与 `AgentContract.maxRetries` 全仓零引用；`LLMGateway` kdoc 声称的实现级重试在 HTTP 层完全不存在。**Fallback 完全不存在。**
5. **两套 Context 机制并存且都在生产路径**（I6 任务级 + LCL 章节级），另有第三套 `PlanningContextAssembly`；`ContextCandidate` 是死代码。
6. **Session 与 Runtime 脱节**：`AgentSession` 落库、`AgentExecutionContext` transient，注释明确「不改 AgentRuntime（I14/I3 Integration = DEFERRED）」，无 Resume Engine。
7. **文档与代码不符**：`docs/status/qianyan-project-status.md:139` 称 "Migration: Not Started" / "Schema Version 1 / 11 张表"，实际是 schema v22 + 21 个 `.sqm` + 25 个 `.sq`。
8. **16 组"读源码断言禁用 token"的守卫测试**是本项目事实上的架构执行机制——这是比 Sandbox 更贴合当前阶段的治理手段。
9. **零第三方 Agent/LLM 框架**（无 LangChain4j/Koog/Spring AI/OkHttp/Ktor），全部自研；`koin` 声明在版本目录但未使用。

# 附录 B：无法确定的项

| 问题 | 原因 |
|---|---|
| `agent/orchestration` / `runtime` 两个空壳模块的**原定用途** | 无 `src/main`、无注释、无文档说明；只能确认"当前无实现" |
| `ContextCandidate`（`context/ContextModels.kt:16`）**是否计划复活** | 无引用、无 TODO；只能确认"当前是死代码" |
| `AgentContract.inputSchema/outputSchema` **是否计划填充** | 声明为 `JsonObject` 但从未赋值；无注释说明 |
| `app:*` 依赖 `provider:impl` 是否**被有意豁免** | `settings.gradle.kts` 注释说"禁止依赖具体实现"，但两个 app 都依赖；架构文档承认 app 是组合根，但未见明文豁免条款 |
| 13 处 Canonical 旁路**是否为有意的分阶段设计** | 代码无相关注释；`CommitUseCases.kt:46` 的措辞（"唯一"）与现状矛盾，更像是设计意图未完全落地 |

# 附录 C：工作树状态（审计未改动任何文件）

本次审计**只读**，未修改任何代码。审计期间工作树状态（相对 HEAD `4cefd0d`）为：

```text
Modified:  Main.kt · ui/Common.kt · ui/theme/QianyanTheme.kt · ui/settings/ProviderSettings.kt · ui/write/RewriteDialog.kt
           application/.../di/ApplicationContainer.kt（+27：workspace facade，审计前既有）
           app/desktop/src/jvmTest/.../DesktopWriterTest.kt（审计前既有）
Untracked: app/desktop/.../ui/pages/ · ui/workspace/ · ui/settings/SettingsWindow.kt · ui/theme/QianyanTokens.kt
           app/desktop/src/jvmMain/resources/ · app/desktop/src/jvmTest/.../DesktopWorkspaceTest.kt
           application/.../usecase/workspace/ · core/model/.../workspace/ · application/src/test/.../workspace/
Deleted:   7 个旧骨架页（AuthorScreen / BookScreen / HomeScreen / ManageScreen / PlanScreen / StoryScreen / WriteScreen）
CRLF 噪声: agent/runtime/.../AgentError.kt · agent/tool/.../ToolExecutor.kt · application/.../AnalysisUseCases.kt
           application/.../TxtUseCases.kt · core/model/.../AgentModels.kt · core/model/.../ToolModels.kt
           （git diff --ignore-cr-at-eol 零行差异）
```

本报告本身是本次唯一新增的文件：`docs/architecture/pre-dsh-audit.md`。
