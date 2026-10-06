# Runtime Adapter（I1）现状审计

> **审计性质**：只读。未修改任何代码、测试、schema、迁移或现有文档；本文件是唯一新增产物。
> **审计对象**：`Qianyan` 分支 `feature/novel-ide`，HEAD `4cefd0d`，工作树含未提交的 I1 与 I14 改动。
> **取证方式**：逐文件阅读 + `git ls-files` / `git ls-tree` / `grep -n`，所有结论附 `file:line`。
> **时间**：2026-10-06
>
> ⚠️ 前置事实：`runtime/api`、`runtime/dsh`、`application/.../runtimeintegration`、`core/model/.../runtime`、
> `storage/.../RuntimeSessionRef.*` **全部未被 git 跟踪**（`git ls-tree -r HEAD` 命中 0）。本审计描述的是**工作树状态**，
> 不是任何提交状态。

---

## 1. 模块清单

| 模块 | 路径 | Gradle 依赖（project） | 职责一句话 |
|---|---|---|---|
| `:runtime:api` | `runtime/api/` | **无任何 project 依赖**（`runtime/api/build.gradle.kts` 仅有 `testImplementation` 两项） | 外部 Agent Runtime 的 **vendor-neutral 契约**（接口 + 值对象 + 类型化失败），零厂商概念、零业务概念 |
| `:runtime:dsh` | `runtime/dsh/` | `api(project(":runtime:api"))`（`runtime/dsh/build.gradle.kts:16`）；`implementation(libs.kotlinx.serialization.json)`（`:19`） | 契约的 **DSH Adapter 实现**：ACP over JSON-RPC/stdio，含子进程管理、协议编解码、类型化映射、权限应答 |
| `:runtime:impl` | — | — | **不存在**（`test -d runtime/impl` → 不存在） |
| 旧 `:runtime` | `runtime/`（现为 `api/` `dsh/` 的父目录） | — | **已废弃但处于半删除状态**，见 §9 风险 b |

**消费 Runtime 契约的模块：**

| 模块 | 依赖声明 | 行号 | 说明 |
|---|---|---|---|
| `:application` | `api(project(":runtime:api"))` | `application/build.gradle.kts:50` | 用 `api` 而非 `implementation` ⇒ 契约类型对下游 `app:*` 传递可见 |
| `:app:desktop` | `implementation(project(":runtime:api"))` + `implementation(project(":runtime:dsh"))` | `app/desktop/build.gradle.kts:30,31` | 唯一同时依赖契约与实现的模块（组合根） |
| `:app:android` | 无 | — | **Android 未接入 Runtime**（无任何 runtime 依赖） |

---

## 2. 接口清单（`:runtime:api`）

全部声明集中在**单个文件** `runtime/api/src/main/kotlin/com/qianyan/runtime/contract/AgentRuntimeGateway.kt`（234 行）。

### 2.1 `interface AgentRuntimeGateway`（`:27`）

| 方法签名 | 行号 | 返回类型 | suspend? | 说明 |
|---|---|---|---|---|
| `fun start(): RuntimeHandle` | `:30` | `RuntimeHandle` | **否** | 启动 Runtime 载体（当前 = 外部子进程） |
| `fun createSession(request: RuntimeSessionRequest): RuntimeSession` | `:33` | `RuntimeSession` | 否 | 新建一个 Runtime 会话 |
| `fun prompt(session: RuntimeSession, prompt: String): RuntimeRunResult` | `:36` | `RuntimeRunResult` | 否 | 发一次 prompt，**消费完所有 update 后返回终态** |
| `fun cancel(session: RuntimeSession)` | `:42` | `Unit` | 否 | 取消进行中的工作，幂等，**不等待结算**（`:44-45` kdoc） |
| `fun resume(sessionId: String): RuntimeSession` | `:49` | `RuntimeSession` | 否 | 按 id 恢复会话；kdoc 明示「真实 resume 语义**仍未验证**」（`:47`） |
| `fun close(session: RuntimeSession)` | `:52` | `Unit` | 否 | 关闭会话，幂等 |
| `fun shutdown()` | `:55` | `Unit` | 否 | 关闭载体与子进程，幂等，不泄漏进程 |

> **关键判定（回答"是否 suspend"）**：**7 个方法全部是普通阻塞 `fun`，没有一个 `suspend`，也没有任何 `Flow`/回调。** 见 §9 风险 d。

### 2.2 其余公开类型

| 类型 | 行号 | 种类 | 成员 |
|---|---|---|---|
| `interface RuntimeHandle` | `:59` | interface | `runtimeName: String`（`:60`）、`alive: Boolean`（`:61`）、`diagnostics(): List<String>`（`:64`，明示"不混入 protocol 流"）、`shutdown()`（`:66`） |
| `data class RuntimeSessionRequest` | `:70` | data | `workingDirectory: Path?`（`:71`）、`model: String?`（`:72`） |
| `data class RuntimeSession` | `:76` | data | `sessionId: String`（`:77`）、`workingDirectory: Path?`（`:78`）。**Qianyan 侧仅持有 id 字符串** |
| `data class RuntimeRunResult` | `:82` | data | `sessionId`（`:83`）、`finalText`（`:84`）、`updates: List<RuntimeUpdate>`（`:85`）、`stopReason: RuntimeStopReason`（`:86`）、`rawStopReason: String?`（`:88`） |
| `enum class RuntimeUpdateKind` | `:96` | enum | `MESSAGE`/`THOUGHT`/`TOOL_CALL`/`PLAN`/`USAGE`/`OTHER`（`:99-113`）＋ `fromRaw(String?)`（`:118`，未知→`OTHER`，绝不抛错） |
| `enum class RuntimeStopReason` | `:130` | enum | `END_TURN`/`MAX_TOKENS`/`MAX_TURN_REQUESTS`/`REFUSAL`/`CANCELLED`/`UNKNOWN`（`:132-147`）＋ `fromRaw(String?)`（`:152`，未知→`UNKNOWN`） |
| `data class RuntimeUpdate` | `:168` | data | `kind: RuntimeUpdateKind`、`rawKind: String`（保留实现侧原值供排障）、`text: String?` |
| `data class RuntimePermissionRequest` | `:182` | data | `runtimeSessionId: String`、`title: String?`、`detail: String?`（**仅此三字段**） |
| `enum class RuntimePermissionOutcome` | `:189` | enum | **`ALLOW` / `REJECT` 二态**（无 `NEEDS_HUMAN`） |
| `fun interface RuntimePermissionResponder` | `:202` | fun interface | `decide(request: RuntimePermissionRequest): RuntimePermissionOutcome`（单向应答端口） |
| `sealed class RuntimeError` | `:213` | sealed（继承 `Exception`） | 6 个子类：`StartupFailed`（`:216`）、`ProtocolViolation`（`:219`）、`ServerError`（`:222`，带 `code`/`serverMessage`/`data`）、`TimedOut`（`:226`）、`StreamClosed`（`:230`）、`NotAvailable`（`:234`） |

**契约自我声明的三条硬边界**（`:40-45` 文件头）：零依赖（不依赖 `core:model`/`provider:api`/任何 Adapter）、零厂商概念（不出现 DSH/ACP/JSON-RPC/`dshSessionId`）、零业务概念（不含 `ProjectId`/`NovelId`/`TaskId`）。**审计确认三条均成立**（全文件 grep 无上述词汇）。

---

## 3. DSH 实现清单（`:runtime:dsh`）

主源共 **4 个文件 / 663 行**：

| 类名 | 行号 | 实现的接口 | 可见性 | 关键方法 | 依赖的 DSH 能力 |
|---|---|---|---|---|---|
| `DshRuntimeClient` | `DshRuntimeClient.kt:48` | `AgentRuntimeGateway` | **public（唯一 public 类）** | `start():72`、`initialize():93`（**契约外扩展**）、`createSession():110`、`prompt():125`、`cancel():152`、`resume():163`、`close():166`、`shutdown():173`；内部 `request():185`、`dispatch():209`、`handleServerRequest():229`、`permissionResult():246`、`pickOptionId():269`、`recordNotification():281`、`extractText():295`、`extractFinalText():307`、`failPending():315` | ACP：`initialize` / `session/new` / `session/prompt` / `session/cancel` / `session/close` / `session/update` / `session/request_permission`（常量集中于 `:333-341`） |
| `DshProcess` | `DshProcess.kt:20` | 无（内部载体类） | `internal` | `start():37`、`send():64`（`@Synchronized`，`:63`）、`diagnostics():75`、`destroy():78`（默认宽限 1500ms）、`alive:32`、`exitCode:34` | `ProcessBuilder` 启动子进程 + **两条 daemon 线程分别 pump stdout/stderr**（`:53-54`） |
| `DshJsonRpc` | `DshJsonRpc.kt:28` | 无 | `internal object` | `encodeRequest():47`、`encodeNotification():58`、`encodeResponse():68`、`encodeErrorResponse():78`、`parseLine():94`；`sealed interface Message`（`:38`）= `Response`（`:39`）/`Notification`（`:40`）/`Request`（`:41`）；`data class Error`（`:44`） | JSON-RPC 2.0 行协议（逐行分帧） |
| `DshRuntimeConfig` | `DshRuntimeConfig.kt:20` | 无 | `public data class` | `resolveDefault():36`、`isAvailable():51`、`resolveExecutable():54`、`requireExecutable():69` | 解析可执行文件；字段：`executable`、`arguments`（默认 `["--profile","acp"]`，`:22`）、`workingDirectory`、`environment`、`startupTimeoutMillis = 10_000`（`:25`）、`requestTimeoutMillis = 30_000`（`:26`） |

**构造签名**（`DshRuntimeClient.kt:48-54`）：

```kotlin
class DshRuntimeClient(
    private val config: DshRuntimeConfig = DshRuntimeConfig.resolveDefault(),
    private val permissionResponder: RuntimePermissionResponder? = null,
) : AgentRuntimeGateway
```

→ `permissionResponder` **默认 null**，注释明示 null 即 fail-closed（`:51-53`）。

**线程模型**（`:44-46` kdoc）：单 reader 线程逐行解析、响应按 `id` 派发到各自 `ArrayBlockingQueue(1)`、通知按 `sessionId` 归集、**对端请求在 reader 线程内同步应答**。

---

## 4. `AgentSession` ↔ `RuntimeSession` 关系

| 问题 | 回答 | 证据 |
|---|---|---|
| **基数** | **一对多（1:N）**。一个 `AgentSession` 可先后绑定多次 Runtime 会话 | `core/model/src/main/kotlin/com/qianyan/model/runtime/RuntimeSessionRefModels.kt:17-18`；`storage/src/main/sqldelight/com/qianyan/storage/db/RuntimeSessionRef.sq:11`；查询 `listRuntimeSessionRefsByAgentSession`（`:31`）返回 List |
| **谁创建谁** | **Qianyan 创建 Runtime**：`RuntimeIntegrationUseCases.createRuntimeSession()` 先校验 AgentSession 存在，再调 `gateway.createSession()`，最后写绑定 | `application/.../runtimeintegration/RuntimeIntegrationUseCases.kt:64-72`（`:65` 悬挂引用早失败、`:69` 建会话、`:70` 写绑定） |
| **谁持有谁** | Qianyan 持有 `RuntimeSession`（**只含 id 字符串**），通过旁路表持有；**`AgentSession` 不持有任何 Runtime 字段** | `RuntimeSessionRefModels.kt:21`「不改 AgentSession：绑定是**旁路引用**」；`RuntimeSessionBindingUseCases.kt:26`「不给 AgentSession 增加任何运行时字段」 |
| **生命周期** | **进程作用域**：一次 run 打开一个会话、run 结束即收敛。`NovelAgent` 在 `finally` 里关闭 | `NovelAgent.kt:321-325`（打开，`runtimeBacked=false` 时直接返回 null）、`NovelAgent.kt:328-331`（best-effort 关闭，不抛异常） |
| **落库位置** | 表 **`RuntimeSessionRef`**（schema **v23**）：`ref_id` PK、`agent_session_id`（FK）、`runtime_name`、`runtime_session_id`、`created_at` | `RuntimeSessionRef.sq:14-21`；FK → `AgentSession(session_id)`（`:20`）；索引 `idx_runtime_session_ref_agent_session`（`:24`，本轮 B2 补齐后 create/migrate 双路径一致） |
| **迁移** | v22 → v23，**additive**（仅新增表+索引，不改任何既有表/列/语义，无 backfill） | `storage/src/main/sqldelight/com/qianyan/storage/db/22.sqm:1-18`；`DatabaseInitializer.kt:93-94`（`V22 = 22L`）、`:272-276`（`!tableExists("RuntimeSessionRef")` 分支 → 幂等） |
| **外键是否生效** | 是。每次连接建立时显式 `PRAGMA foreign_keys = ON` | `DatabaseInitializer.kt:139` |

**跨进程重启的当前真实语义（重要）**：表中的行**可以**被读回，但读回的 `runtime_session_id` 指向**已随旧进程消亡**的对端会话；`resume` 未实现，因此该 id 不可复用。当前**无害**，因为唯一生产调用方 `NovelAgent` 每次新建会话、从不复用持久化绑定（`NovelAgent.kt:324`）。详见 §9 风险 c。

---

## 5. Desktop 接入

| 文件 | 行号 | 用途 | 是否主链路 |
|---|---|---|---|
| `app/desktop/src/jvmMain/kotlin/com/qianyan/app/desktop/di/DesktopGraph.kt` | `:10` | `import com.qianyan.runtime.dsh.DshRuntimeClient` | — |
| 同上 | `:69-74` | **全仓唯一的 Adapter 构造点**：`private val runtimeGateway = DshRuntimeClient()`；注释声明"组合根唯一构造点"、"UI 不得 new Adapter"（`:71`） | — |
| 同上 | `:103` | 注入 `ApplicationContainer.fromDriver(..., runtimeGateway = runtimeGateway)` | — |
| `application/.../di/ApplicationContainer.kt` | `:117`/`:205` | 只 `import` **契约**类型 `AgentRuntimeGateway`；字段声明为 `AgentRuntimeGateway? = null`（可空=未装配） | — |
| 同上 | `:378` | 构造 `RuntimeIntegrationUseCases(gateway = runtimeGateway, ...)` | — |
| 同上 | `:412` | 把该 UseCase 传给 `NovelAgent` | — |
| `application/.../usecase/agent/NovelAgent.kt` | `:132`/`:315`/`:321-331` | Runtime seam：仅当 `request.runtimeBacked` 且已装配时建立/收敛一次绑定；**失败不阻断七相位编排** | — |

**结论：Desktop 不是主链路，且整条 Runtime 通路当前完全不可达。** 三条独立证据：

1. `NovelAgentRequest.runtimeBacked` 默认 `false`（`core/model/.../novelagent/NovelAgentModels.kt:63`），**全仓没有任何生产或测试调用方将其置为 `true`**（`grep -rn runtimeBacked` 仅命中定义、注释与 `if (!request.runtimeBacked) return null`）。
2. `app/desktop` 与 `app/android` 的**全部主源码**中 `runtimeIntegration` / `AgentRuntimeGateway` 引用数为 **0**。
3. `permissionResponder` 未注入（`DesktopGraph.kt:74`）⇒ 真实运行时权限请求一律被 fail-closed 拒绝。

**缺失的关闭路径**：`DesktopGraph` 只在重建容器时关闭 DB driver（`:101`），窗口关闭走 `Main.kt:96 onCloseRequest = ::exitApplication`；`ApplicationContainer` 无任何调用 `runtimeGateway.shutdown()` 的路径。**全仓唯一调用 `shutdown()` 的地方是测试的 `finally`。** 见 §9 风险 i。

---

## 6. 当前能力边界

### 现在能通过 Adapter 做什么（已由真实 DSH 验证）

| 能力 | 状态 |
|---|---|
| 启动 DSH 子进程并维持 stdio | ✅ 真实验证 |
| ACP `initialize`（自动，契约不暴露握手：首次 `createSession` 内部补做，`DshRuntimeClient.kt:112`） | ✅ 真实返回 `protocolVersion=1`、`agentInfo.name=deepseek-harness-acp` |
| `session/new` 建会话（含绝对 `cwd`） | ✅ 真实返回 sessionId |
| `session/prompt` 取回**终态**结果 | ✅ 真实返回 `finalText=DSH_POC_OK`、`stopReason=END_TURN` |
| `session/update` → 类型化 `RuntimeUpdateKind` | ✅ 真实观测到 `THOUGHT / MESSAGE / USAGE` |
| typed `RuntimeStopReason` 映射 | ✅ raw `end_turn` → `END_TURN` |
| typed `RuntimeError`（6 类，文案厂商中立） | ✅ 真实 `-32603` 正确归类为 `ServerError`（未误判为超时/流断） |
| `session/close` / `shutdown` | ✅ 被调用；但 close 无对端确认断言（§9） |
| stdout(protocol) 与 stderr(diagnostics) 隔离 | ✅ `DshProcess.kt:41-43` 显式 `redirectErrorStream(false)` + 双线程 pump |
| 1:N 绑定持久化（vendor-neutral） | ✅ 表/索引/FK/幂等迁移齐备 |
| 投影到既有 `Activity`（不建第二套事件表） | ✅ `RuntimeIntegrationUseCases.kt:67,71,86,89` |

### 现在**不能**做什么

| 缺口 | 证据 |
|---|---|
| **流式 / 增量输出** —— 契约是终态返回，无 `Flow`、无回调、无 `suspend` | `AgentRuntimeGateway.kt:36`；`DshRuntimeClient.kt:136`（`updates` 在 prompt 返回后才读）；真实帧已在期间到达但被累积后一次性交付 |
| **会话恢复 resume** —— 直接抛错 | `DshRuntimeClient.kt:163-164` `throw RuntimeError.NotAvailable(...)` |
| **会话列表 session/list** —— 契约无此方法、实现无、全仓零命中 | `AgentRuntimeGateway.kt:27-56`；`DshRuntimeClient.kt:333-341` 常量表无该项 |
| **能力协商** —— 不解析 `initialize` 响应体（对端已声明 `sessionCapabilities{close,list,resume}`、`mcpCapabilities.http`） | `DshRuntimeClient.kt:93-108`（只置 `initialized=true`）；全仓 grep `sessionCapabilities` = 0 |
| **原生 function calling / 工具调用** —— 不映射 tool 帧到领域工具 | `DshRuntimeClient.kt:39-40` 明示排除；`RuntimeUpdateKind.TOOL_CALL` 无真实生产者 |
| **模型选择 / model routing** —— 只透传 `model`，未配置 | `DshRuntimeClient.kt:115`；`DesktopGraph.kt:74` 未传 `RuntimeSessionRequest.model` |
| **多模态内容** —— `extractText` 只取文本 | `DshRuntimeClient.kt:295-306` |
| **并发会话 / 多线程安全（部分）** —— `process`/`started`/`initialized` 为普通 `var`，无 `@Volatile`/同步 | `DshRuntimeClient.kt:60-62`；仅 `DshProcess.send` 加锁（`:63`） |
| **NEEDS_HUMAN 表达** —— 权限应答只有二态 | `AgentRuntimeGateway.kt:189-192` |

### 哪些还是 Mock / 未接通

| 项 | 真实情况 |
|---|---|
| `:runtime:dsh` **主源码内没有任何 Mock** —— 4 个文件全是真实 `ProcessBuilder` + stdio + JSON-RPC 实现 | `DshProcess.kt:39-49`（真实 spawn）、`DshJsonRpc.kt`（真实编解码） |
| Mock/替身**只存在于测试源码**：`runtime/dsh/src/test/.../FakeDshServer.kt`（122 行，手写 ACP 桩）、`application/src/test/.../runtimeintegration/`（`FakeRuntimeGateway`） | 路径即证据 |
| ⚠️ **重要提醒**：`ProviderType.MOCK`（`app/desktop/.../DesktopGraphSmokeTest.kt:50`）是 **LLM Provider 层的 Mock，与 Runtime Adapter 无关**，二者不可混淆 | `provider/impl` 与 `runtime/dsh` 是两条独立轨道 |
| **未接通**：整条 Runtime 链路（§5 三条证据）；`permissionResponder` 未注入；`runtimeBacked` 无 true 值 | 同上 |

---

## 7. 和 DSH 真实协议的关系

**分层（自下而上）：**

```
DshRuntimeConfig    启动配置层   —— 可执行文件/参数/cwd/env 解析（DshRuntimeConfig.kt:36,54,69）
DshProcess          传输与进程层 —— ProcessBuilder + stdout/stderr 双 pump + 同步写（DshProcess.kt:37,63,78）
DshJsonRpc          协议编解码层 —— JSON-RPC 2.0 行协议：encode*/parseLine（DshJsonRpc.kt:47-94）
DshRuntimeClient    ACP 语义层   —— 方法名映射 + id 派发 + typed 转换 + 权限应答（DshRuntimeClient.kt:110-180,229-292）
AgentRuntimeGateway 领域契约层   —— vendor-neutral，位于 :runtime:api，不知协议存在
```

**真实协议调用占比：**

- 生产代码 **100% 真实**：实现 ACP 的 7 个方法（5 个出站 + 2 个入站），无占位实现。
- 唯一"非真实"成分在测试层：`FakeDshServer.kt`。⚠️ 该桩是**照适配器自身预期手写**的，其帧形状并非取自 ACP 规范文本 —— 因此"28 个 dsh 单测全绿"证明的是适配器与**它自己的假设**一致，不等于与真实 DSH 一致。真实一致性由 B4 实测单独证明（见下）。

**已实现 / 未实现的 ACP 面：**

| 方向 | 方法 | 状态 |
|---|---|---|
| client→server | `initialize`、`session/new`、`session/prompt`、`session/cancel`、`session/close` | ✅ 已实现（`:333-341`） |
| server→client | `session/update` | ✅ 已实现（`recordNotification():281`） |
| server→client | `session/request_permission` | ✅ 已实现，fail-closed（`handleServerRequest():229` → `permissionResult():246`） |
| server→client | 其它未映射请求 | ✅ 显式回 `-32601`，注释说明"不回错误会让对端永久挂起"（`:227`） |
| — | `session/load`（list）、`session/resume`、`session/set_model`、协议能力协商 | ❌ 未实现 |

**真实 DSH 验证状态**（引 `docs/architecture/i1-runtime-integration-closure.md` §6 与本轮实测）：十步全链路 PASS，`protocolVersion=1`、`agentInfo.name=deepseek-harness-acp`、真实 sessionId、`DSH_POC_OK`、`raw=end_turn`、真实帧类型 `[THOUGHT, MESSAGE, USAGE]`、stderr 零输出、无残留进程。

---

## 8. 未完成项

### I1 明文排除（原文照录）

`DshRuntimeClient.kt:39-40`：

> 本阶段**不**做：MCP / Skill / Tool Runtime 产品化 / Subagent / Model routing / Context 注入 / Change Layer 接入（**全部记 NEXT PHASE**）

这是全仓唯一一处 `NEXT PHASE` —— **只列排除项，未命名也未定义后续阶段。**

### 已声明的 DEFERRED / NOT VERIFIED

| 项 | 声明位置 | 原话 |
|---|---|---|
| resume | `AgentRuntimeGateway.kt:47`；`DshRuntimeClient.kt:162` | 「resume = NOT YET VERIFIED」/「在 I1 未验证」 |
| Resume Engine | `RuntimeSessionBindingUseCases.kt:27` | 「不实现 Resume Engine（本类只恢复**身份**，不重放执行）」 |
| AgentRuntime 不改 | `RuntimeSessionBindingUseCases.kt:29`；`agent/runtime/.../AgentExecutionContext.kt:12` | 「不产生第二套会话状态机」/「默认保持 transient，不持久化」 |
| AgentSession 与 Runtime 接线 | `pre-dsh-audit.md` 附录 A#6 | 标注为 DEFERRED（且同一件事在仓库中被标为 **I3 / I4 / I14** 三个不同编号） |
| 第二套 History/transcript | `RuntimeSessionRef.sq:7`、`RuntimeSessionRefModels.kt:19-20` | 明示**不做**，属边界而非待办 |

### 无 TODO/FIXME 残留

`:runtime:api` 与 `:runtime:dsh` 主源码中 `TODO(` / `FIXME` 命中数为 **0** —— 未完成项全部以**契约注释**形式记录，而非代码标记。

---

## 9. 风险与疑问

按严重度排列，均为事实描述。

**a. 模块名语义冲突（文档侧，高）**
现行 `README.md:540` 仍写 `runtime  平台 Runtime 抽象（占位）`；而 P0 首份 README（提交 `ccde587`）同样定义 `runtime = 平台 Runtime 抽象`，且那句「Android 与 PC 通过不同 **Runtime Adapter** 复用同一套 Domain / Agent / Workflow / Tool / Provider 契约」中的 "Runtime Adapter" 指的是**平台适配**。今天 `:runtime:*` 已表示**外部 Agent Runtime**。**同一个词在同一仓库承载两种含义**，这是"DSH 为什么存在"在文档里查不到的直接原因之一。属 `qianyan-novel-ide-architecture.md §45 R11「文档与代码漂移」` 风险类别。

**b. 旧 `:runtime` 处于半删除状态（中）**
`runtime/build.gradle.kts` 磁盘已删但仍被 git 跟踪；`runtime/src/test/java/com/qianyan/runtime/RuntimeSmokeTest.kt` **被跟踪但磁盘上不存在**（`find runtime/src -type f` 返回空，`git ls-files runtime/src` 返回 1 项）。从 HEAD 恢复仓库会得到一个不属于任何编译源的孤儿测试。

**c. Domain 注释声明的用途与实现不符（中）**
`RuntimeSessionRefModels.kt:12` 写明绑定记录「用于**跨进程重启后**把 Qianyan 会话身份解析回 Runtime 会话（**resume** / 审计）」，但 `resume` 未实现（`DshRuntimeClient.kt:163` 抛错）。⇒ 该表当前唯一的实际用途是**审计记录**，声明的首要用途尚未兑现。缓解事实：唯一生产调用方不复用持久化绑定（`NovelAgent.kt:324`），因此不会读到失效 id 去调用。

**d. 契约不支持流式，与"外部 Runtime 的主要增量"相冲突（高，架构层）**
`AgentRuntimeGateway.kt:36` 的 `prompt` 是阻塞返回终态值对象，无 `suspend`/`Flow`/回调（§2.1）。真实 DSH 已在 `session/prompt` 期间推送 `THOUGHT`/`MESSAGE`/`USAGE` 增量帧（本轮实测），而适配器把它们**累积后一次性交付**（`DshRuntimeClient.kt:136`）⇒ 外部 Runtime 相对自研 loop 最具用户可感知价值的流式能力，**在当前抽象下被结构性丢弃**。

**e. 权限请求携带的信息不足以做业务判定（高）**
`RuntimePermissionRequest` 只有 `runtimeSessionId/title/detail`（`:182-186`）；`title` **从未被赋值**；`permissionResult` 仅取 `sessionId`、`options`、`toolCall.toolCallId`（`DshRuntimeClient.kt:248-250`），**丢弃 `toolCall` 的 `title`/`kind`/`locations`**。即便注入 responder，也只有一个 id 字符串，无法映射到 `ActionPolicy` 的风险分级。

**f. 契约缺 `NEEDS_HUMAN` 三态（中）**
`RuntimePermissionOutcome` 只有 `ALLOW/REJECT`（`:189-192`），而同一文件的 kdoc（`:196-201`）却描述了三态语义（「NeedsHuman → 先在 Qianyan 走 Human Gate，用户确认一次后再返回」）。**注释描述的语义在类型上不可表达。**

**g. responder 同步应答会阻塞唯一 reader 线程（高，若接人工门则为死锁）**
`DshRuntimeClient.kt:44-46` 明示「对端请求在 **reader 线程内同步应答**」（`:229-237`）。若将来把 `decide()` 接到需要等待人类的 Human Gate，将阻塞该线程 ⇒ 后续帧不再解析、超时与取消失效、协议停摆。这是**接人工门的硬前置**，不是可选项。

**h. 无能力协商（中）**
`initialize` 响应不被解析（`DshRuntimeClient.kt:93-108`），客户端只发送空的 `clientCapabilities`（`:98`）。真实 DSH 已声明 `sessionCapabilities{close,list,resume}`，适配器既不消费也不据此裁剪功能 ⇒ 对端能力与客户端实现脱钩。

**i. 没有应用级 shutdown 路径（中）**
`DesktopGraph` 只关 DB driver（`:101`），`Main.kt:96` 用 `exitApplication`，`ApplicationContainer` 无 `shutdown()` 调用点。⇒ 一旦 seam 被启用且子进程被启动，正常退出应用**不会**显式收敛 DSH 进程（依赖 OS 回收）。

**j. 超时值为固定常量（低）**
`requestTimeoutMillis = 30_000`（`DshRuntimeConfig.kt:26`）对长篇章节生成任务可能不足；且无法经契约调节（`prompt` 无超时参数）。

**k. `application` 用 `api(project(":runtime:api"))`（低，属取舍）**
`application/build.gradle.kts:50` 使契约类型对 `app:*` 传递可见（这正是 `DesktopGraph` 能直接引用的原因）。方向无害，但与同文件对 `provider:api` 使用 `implementation`（`:33`，其注释 `:31` 明确写「只依赖 LLM 契约，不依赖具体实现」）不一致，缺乏统一规则。

**l. Domain 新增"运行时身份"（需产品确认，非缺陷）**
`core/model/.../model/runtime/RuntimeSessionRefModels.kt` 与 `core/model/.../Ids.kt`（新增 `RuntimeSessionRefId`）把"外部运行时绑定"引入了领域层。虽 vendor-neutral（`:15-16` 硬边界），但**领域模型确实新增了运行时概念**。是否与架构基线冲突：**无法确定，原因**：唯一权威路线表 `§44`（`qianyan-novel-ide-architecture.md:1188-1210`）15 个阶段中**没有 Runtime Integration 阶段**，仓库内也没有任何正式文档描述该阶段的领域建模意图。

**m. 全部 I1 代码未提交（高）**
`git ls-tree -r HEAD` 对 `runtime/api`、`runtime/dsh`、`runtimeintegration`、`model/runtime`、`RuntimeSessionRef.sq` 命中数均为 **0**。`origin/feature/novel-ide` 与 HEAD 处于"完全没有 DSH 代码"的状态。

**n. 无法确定的项（如实登记）**

| 疑问 | 无法确定的原因 |
|---|---|
| `I0 Final Architecture Review` 的 §6/§7/§九 具体内容 | 文档不在版本库；被 4 处引用（`RuntimeIntegrationUseCases.kt:22`、`RuntimeSessionBindingUseCases.kt:18`、`DshRuntimeClient.kt:42,243`、`DshPermissionTest.kt:14`）；`git log --all -S` 零命中。§44 文档中同号章节指向无关主题（§3=能力模型、§6=Project Index、§7=World Model、§9=Context Engine），无法自洽 |
| `app:desktop` 直接依赖 `:runtime:dsh` 是否被有意豁免 | `settings.gradle.kts` 注释只约束 provider（"调用方只依赖 provider:api"），对 runtime 无对应豁免条款文字；`pre-dsh-audit.md` 附录 B 亦列为无法判定 |
| `session/request_permission` 真实帧形状与字段 | 本轮真实 prompt 未触发该请求；唯一证据来自自证式 `FakeDshServer` |
| `AgentRuntimeGateway` 中 `resume` 是否应保留在契约里 | 无规范文档界定 I1 契约的最小完备面；保留一个必抛错的成员，可能是有意占位，也可能是过度设计 |

---

## 附：一句话总结

`:runtime:api` 是一份**干净、零依赖、类型化完备**的外部 Runtime 契约；`:runtime:dsh` 是一份**全部真实、无 Mock、分层清晰**的 ACP 适配器，并已用真实 DSH 验证到 `initialize`/`session/new`/`session/prompt`/typed 映射全绿。但它**不在任何产品主链路上**（`runtimeBacked` 无 true 值、UI 零引用、权限 responder 未注入、无 shutdown 路径），且契约的同步终态形状**主动丢弃了 DSH 已经发来的流式帧**。因此当前形态应描述为：**一个已完成并验证、但尚未被任何产品能力使用的可选 Adapter。**
