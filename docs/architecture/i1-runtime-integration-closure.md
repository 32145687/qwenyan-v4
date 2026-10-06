# I1 Runtime Integration — Closure Record（可追溯性记录）

> **性质**：本文件只做**事实登记与编号消歧**，不引入任何新架构决策、不定义任何新阶段。
> **取证时间**：2026-10-06　**取证分支/提交**：`feature/novel-ide` @ `4cefd0d`（工作树含未提交 I1 与 I14 改动）
> **取证方式**：只读代码审计 + 真实 DSH 运行 + Gradle 测试执行
> **上位文档**：不改变 `p20-architecture-freeze.md`（FD-1…FD-10 继续有效）与
> `qianyan-novel-ide-architecture.md` §44 的任何结论。

---

## 1. I1 的规范依据文档目前不在仓库内（待补）

I1 代码在 4 处引用一份名为 **`I0 Final Architecture Review`** 的文档作为立论依据：

| 引用位置 | 引用的章节 |
|---|---|
| `application/.../usecase/runtimeintegration/RuntimeIntegrationUseCases.kt:22` | §7（I1 Scope）/ §6（Provider Ownership） |
| `application/.../usecase/runtimeintegration/RuntimeSessionBindingUseCases.kt:18` | §3（Session Mapping） |
| `runtime/dsh/.../DshRuntimeClient.kt:42`、`:243` | 「I1 §九」（方向约束） |
| `runtime/dsh/src/test/.../DshPermissionTest.kt:14` | 同上 |

**经全仓检索（含 `docs/` 18 个文件、`README.md`、`app/desktop/README.md`、仓库外层目录、`git log --all -S`）确认该文档不存在于版本库。**
其章节号在 `qianyan-novel-ide-architecture.md` 中指向完全不同的主题（§3=能力模型、§6=Project Index、§7=World Model、§9=Context Engine），无法自洽对应。

**结论**：该文档属外部/会话产物。**在它被正式入库之前，I1 的范围与验收无法被独立复核。**
本记录**不尝试重建**其内容（那会构成发明架构）。需要由决策方提供原文，或正式确认"以代码现状为准"。

在补齐之前，本文件第 2–5 节以**代码可观测事实**登记 I1 的实际范围。

---

## 2. I1 实际范围（从代码取证，非新增设计）

### 已实现

| 能力 | 证据 |
|---|---|
| 模块拆分 `:runtime:api` / `:runtime:dsh` | `settings.gradle.kts` include + 显式 `projectDir` |
| vendor-neutral 契约 7 方法 | `runtime/api/.../AgentRuntimeGateway.kt:27-56` |
| 类型化 `RuntimeUpdateKind` / `RuntimeStopReason` | 同文件 `:118-125`、`:152-159` |
| 类型化 `RuntimeError`（8 子类，文案厂商中立） | 同文件 `:205-240` |
| ACP over stdio 适配器 | `DshRuntimeClient.kt`、`DshProcess.kt`、`DshJsonRpc.kt` |
| `AgentSession : RuntimeSessionRef = 1 : N` | `RuntimeSessionRef.sq:11`（约束注释）、`:31-41`（list/latest 查询） |
| Schema v22 → v23 additive 迁移 | `22.sqm:1-18`、`DatabaseInitializer.kt`（`!tableExists("RuntimeSessionRef")` 分支） |
| Application 只依赖契约 | `application/build.gradle.kts:50` `api(project(":runtime:api"))` |
| Adapter 仅在组合根创建 | `app/desktop/.../di/DesktopGraph.kt:10,74,103`（全仓唯一构造点） |
| Permission 单向应答 + fail-closed | `DshRuntimeClient.kt:246-266`（无 responder → REJECT/cancelled） |
| 未映射 server→client 请求显式回 `-32601` | `DshRuntimeClient.kt:229-238`（kdoc：不回错误会让对端永久挂起） |
| `NovelAgent` opt-in seam（默认关闭、失败不阻断） | `NovelAgent.kt:321-331` |

### 明确未实现（I1 自述排除，非疏漏）

`DshRuntimeClient.kt:39-40` 原话：本阶段**不**做 MCP / Skill / Tool Runtime 产品化 / Subagent /
Model routing / Context 注入 / Change Layer 接入，「全部记 NEXT PHASE」。
全仓仅此一处 `NEXT PHASE`，**只列排除项，未命名也未定义下一阶段**。

---

## 3. `resume` 归属决定（Closure 期间确认）

**决定：`resume = 显式延后（explicitly deferred）`，不属 I1 范围。**

三处独立证据一致：

1. `RuntimeSessionBindingUseCases.kt:27` —「不实现 Resume Engine（本类只恢复**身份**，不重放执行）」
2. `DshRuntimeClient.kt:162-164` — `resume()` 直接抛 `RuntimeError.NotAvailable("... 在 I1 未验证")`
3. `AgentRuntimeGateway.kt:47` — 契约 kdoc 明示「真实 resume 语义**仍未验证**（resume = NOT YET VERIFIED）」

**最终归属**：`resume` 属 **Runtime Integration Layer**（契约 `:runtime:api` + Adapter `:runtime:dsh`），
**永不属 Domain**。Domain 只保存 vendor-neutral 的会话身份：

- `RuntimeSessionRefModels.kt:15,20` 明示不出现 DSH / ACP / JSON-RPC / `dshSessionId`；
- `AgentSession` 领域模型**未新增任何运行时字段**（`AgentSessionModels.kt:60-63`；`RuntimeSessionBindingUseCases.kt:26` 为硬约束）；
- DSH 原始 transcript 由对端自管，不进 Qianyan（`RuntimeSessionRef.sq:7`）。

**可达性说明（重要）**：跨进程重启后从 SQLite 读回的 `runtimeSessionId` 指向已消亡的对端会话。
但 I1 唯一的生产调用方 `NovelAgent.openRuntimeSessionIfRequested`（`:321-325`）**每次都新建会话**并在
`finally` 中收敛（`:328-331`），从不复用持久化 ref ⇒ **该失效路径在 I1 内不可达**，属未来 resume 实现时的约束，不是 I1 缺陷。

**命名冲突登记**：仓库中存在三个不同含义的 "resume"，后续文档与代码必须区分：

| 名称 | 含义 | 位置 |
|---|---|---|
| `AgentRuntimeGateway.resume` | 外部 Runtime 会话恢复（**deferred**） | `AgentRuntimeGateway.kt:49` |
| `NovelAgent.resume` | Qianyan 人工门批准后的 Commit 续跑（**已实现**） | `NovelAgent.kt:339` |
| `AgentSessionStatus.isResumable` | Qianyan 会话可重新打开（**已实现**） | `AgentSessionModels.kt:50` |

---

## 4. Canonical 边界：保持完好

DSH Runtime 输出**没有**任何写入 Canonical Story 的路径。边界链条未被改动：

```
DSH Agent Output ──✗ 无写入路径
Qianyan 侧唯一入口：Draft → Validation → Diff → Change → Artifact → Human Gate → Commit → Canonical
```

取证：

- `usecase/runtimeintegration/` 的全部 import 中**不含** Draft/Change/Commit/Repository 任何类型；
- 全仓 `commits.commit(` 仅两个调用点，均在 `NovelAgent.kt:282,366`，与 Runtime 无关；
- `RuntimeSessionRef.sq:7` 明示该表不承载正文 / World Model / Project State / Workflow 状态 / transcript。

⇒ **`Canonical isolation preserved.`**

**历史遗留（不在本阶段处理）**：`pre-dsh-audit.md:254-276` 登记的 **13 处 Canonical 旁路**（用户手工保存正文、
finalize、confirm、createNextChapter、override 等不经 Change/Commit）在 Closure 期间**未改动**，
记录为后续强化项（`pre-dsh-audit.md` 结论 C 第 4 条亦要求"让唯一提交入口名副其实"）。

---

## 5. I 编号消歧约定（新增约定，不改代码）

仓库当前存在**两套 `I<N>`**，且在同一文件内混用（`DatabaseInitializer.kt:81` 用 §44 义、`:93` 用 Runtime 义；
`DesktopGraphSmokeTest.kt:70` 同一行两种含义并存）。为避免误读，本记录确立书写约定：

| 体系 | 来源 | 书写形式 |
|---|---|---|
| Novel IDE 阶段 I0–I15 | `qianyan-novel-ide-architecture.md` §44（`:1188-1209`） | 写作 **`§44 I<n>`**，如 `§44 I1 = Project 聚合` |
| DSH Runtime Integration 阶段 | 代码注释（本文档第 2 节） | 写作 **`Runtime I<n>`**，如 `Runtime I1 = Runtime Integration` |

**不得互换使用。** 尤其：`§44 I2 = Action Policy + Human Gate 复用`（`:1196`）
**不是** DSH 路线的下一阶段，**不得改名**为 `DSH-I2`。

**本文件不定义 DSH 路线的任何后续阶段。** DSH 路线在 Runtime I1 之后没有任何正式定义（见第 1 节）。

---

## 6. 验证状态快照（Closure 期间）

| 项 | 状态 |
|---|---|
| `:runtime:api:test` | PASS |
| `:runtime:dsh:test` | PASS（`DshRealIntegrationTest` 在无 DSH 环境时 SKIP） |
| 真实 DSH：process startup / ACP initialize / session/new | 真实环境可达（见 Closure Report） |
| 真实 DSH：prompt 之后的链路 | **BLOCKED — 本机无 DeepSeek API Key**（非代码缺陷） |
| Canonical 隔离 | 保持完好 |
| Provider / Change Layer / Commit / P19 / P20 | Closure 期间**未改动** |
