# Qianyan Audit Evidence

## 1. 审计规则与范围

- 规则版本：`qianyan-audit-rules-2026-10-18`
- 工作树 Schema：`qianyan-worktree-schema-1`
- 审计方式：只读文件/代码/Git/已有测试 XML 检查；未运行 Gradle；未修改业务/测试/契约/Schema/migration。
- 状态判据：
  - `ACCEPTED`：生产调用者 + 可信测试证据 + 阶段验收记录（Gate/Decision Record）。
  - `IMPLEMENTED_PENDING_ACCEPTANCE`：生产调用 + 测试存在，但缺少阶段 Gate 或仍有未完成 Gate 项。
  - `PARTIAL`：实现存在但链路不完整，或存在明确测试/接线缺口。
  - `IN_PROGRESS`：工作树有对应未提交实现。
  - `DECISION_REQUIRED`：存在未裁决的架构/产品选择。
  - `NOT_STARTED`：有规划、无实现。
  - `UNKNOWN`：证据不足。
- 产品目标/架构决策节点：`ACCEPTED` 表示决策已记录，不等同于功能实现；这些节点的 `statusBasis` 已注明“决策级接受”。
- 证据优先级：代码实现 > 测试证据 > 文档描述；文档与代码冲突单独登记，不自动判定文档错。

## 2. 输入基线

| 项目 | 值 |
|---|---|
| HEAD | `be1b75582322a43a114fdc174597e29cfe2de87c` |
| branch | `feature/novel-ide` |
| staged | 空 |
| modified | 12 |
| untracked | 8 |
| ahead / behind | 2 / 0 |
| `git status --porcelain` SHA-256 | `2cc75ae3c12dba7ffb215ba0dbb159930bf94ff1b18baa942e6fa7cddcb15e8a` |
| Gradle 模块 | `core:model`、`core:engine`、`agent:tool`、`agent:runtime`、`agent:agents`、`agent:orchestration`、`provider:api`、`provider:impl`、`storage`、`application`、`runtime`、`app:android`、`app:desktop`、`test:e2e` |
| Schema | SQLDelight 迁移 1–27；`27.sqm` 增至 v28（`CandidateFact`） |

**纳入审计的文档**

- `README.md`
- `docs/architecture/qianyan-novel-ide-direction.md`
- `docs/architecture/p20-architecture-freeze.md`
- `docs/planning/qianyan-dev-implementation-plan-v3.1.md`
- `docs/planning/qianyan-phase0-baseline-register.md`
- `docs/planning/qianyan-phase3-closure-decision-record.md`
- `docs/planning/qianyan-phase4a-context-contract-freeze.md`
- `docs/planning/qianyan-phase4a-context-contract-freeze-addendum-payload.md`
- `docs/planning/qianyan-phase4a-architecture-audit.md`
- `docs/planning/qianyan-phase4c-acceptance-criteria-draft.md`
- `docs/status/qianyan-project-status.md`
- `docs/P8.1`、`P8.3`、`P9`、`P10`、`P11.1`–`P11.3` completion reports

**纳入审计的代码范围**

- `core/model`、`core/engine`
- `agent/tool`、`agent/runtime`、`agent/agents`、`agent/orchestration`
- `provider/api`、`provider/impl`
- `storage`（`.sq` / `.sqm` / Repository / DatabaseInitializer）
- `application`（UseCase / ApplicationContainer）
- `runtime`（含未纳入 settings 的 `runtime/api`、`runtime/dsh` 目录）
- `app/desktop`、`app/android`、`test/e2e`

## 3. 测试证据

### 3.1 已有 XML 统计

- fresh XML：**243** 个（mtime ≥ `2026-10-17T14:20:00Z`）。
- raw 执行数：**1444** tests / **0** failures / **0** errors / **0** skipped。
- 该 raw 数包含 `app:android` 的 debug + release 两次执行（同一批 57 个测试）；按不同测试方法去重后约为 **1387**。
- 根项目 raw（不含 Desktop）：**1417**；Desktop `jvmTest`：**27**（单独任务）。
- 5 个陈旧 XML 位于 `runtime/api`、`runtime/dsh`（2026-10-06）；两者不在 `settings.gradle.kts`，未计入统计，也不能作为当前代码证据。

| 模块 | tests | failures | errors | skipped | XML 时间 |
|---|---:|---:|---:|---:|---|
| application | 959 | 0 | 0 | 0 | 2026-10-17T14:21:05Z |
| core:model | 61 | 0 | 0 | 0 | 2026-10-17T14:43:34Z |
| storage | 166 | 0 | 0 | 0 | 2026-10-17T14:22:38Z |
| core:engine | 41 | 0 | 0 | 0 | 2026-10-17T14:20:19Z |
| agent:agents | 13 | 0 | 0 | 0 | 2026-10-17T14:20:19Z |
| agent:runtime | 12 | 0 | 0 | 0 | 2026-10-17T14:20:19Z |
| agent:tool | 13 | 0 | 0 | 0 | 2026-10-17T14:20:17Z |
| agent:orchestration | 1 | 0 | 0 | 0 | 2026-10-17T14:20:24Z |
| provider:api | 1 | 0 | 0 | 0 | 2026-10-17T14:20:16Z |
| provider:impl | 34 | 0 | 0 | 0 | 2026-10-17T14:20:19Z |
| runtime | 1 | 0 | 0 | 0 | 2026-10-17T14:20:14Z |
| test:e2e | 1 | 0 | 0 | 0 | 2026-10-17T14:20:24Z |
| app:android debug | 57 | 0 | 0 | 0 | 2026-10-17T14:20:43Z |
| app:android release | 57 | 0 | 0 | 0 | 2026-10-17T14:20:43Z |
| app:desktop jvmTest | 27 | 0 | 0 | 0 | 2026-10-17T14:23:08Z |

### 3.2 与当前源码的对应关系

| 模块 | 最新源码 mtime (UTC) | 最新 XML (UTC) | 判断 |
|---|---|---|---|
| application | 2026-10-17T14:11:13Z | 2026-10-17T14:21:05Z | XML 晚于源码 |
| core:model | 2026-10-17T14:42:47Z | 2026-10-17T14:43:34Z | XML 晚于源码 |
| core:engine | 2026-09-24T16:16:05Z | 2026-10-17T14:20:19Z | XML 晚于源码 |
| storage | 2026-10-16T05:36:08Z | 2026-10-17T14:22:38Z | XML 晚于源码 |
| agent | 2026-10-14T15:18:08Z | 2026-10-17T14:20:17–24Z | XML 晚于源码 |
| provider | 2026-09-04T17:25:05Z | 2026-10-17T14:20:16–19Z | XML 晚于源码 |
| app:desktop | 2026-10-16T05:35:17Z | 2026-10-17T14:23:08Z | XML 晚于源码 |
| app:android | 2026-10-13T12:58:39Z | 2026-10-17T14:20:43Z | XML 晚于源码 |
| runtime | 2026-10-06T07:22:53Z | 2026-10-17T14:20:14Z | XML 晚于源码 |
| test:e2e | 2026-08-30T14:07:26Z | 2026-10-17T14:20:24Z | XML 晚于源码 |

**限制**：时间戳只能证明 XML 晚于对应源码的 mtime，不能单独证明测试针对当前 HEAD 重新执行过；本轮未运行测试。对 `ACCEPTED` 节点，仍要求生产调用链 + 阶段验收记录同时存在。

### 3.3 已有测试覆盖缺口

- 无 Inspector 四状态 / no-build 测试。
- 无旧 `assembleLegacy` 与新 Source 的直接 Vocabulary A/B 测试。
- 无 `VocabularyEntry` 表中非 APPROVED 行的端到端排除测试。
- 无 Planner / Writer 实际请求中的 Vocabulary 子集断言（`AgentContextPackPipelineTest` 只验证 NOVEL marker，且是“任一请求包含”）。
- 无 ContextPack 与 Inspector 任务归属一致性测试。
- 无完整 E2E 覆盖；`test:e2e` 仅 1 个测试。

## 4. 死代码 / 生产调用链检查

检查方法：

1. 在 `application/src/main`、`app/desktop/src`、`app/android/src` 检索类/方法直接调用。
2. 检查 `ApplicationContainer` 暴露是否为唯一组装点。
3. 检查 UI 是否真的调用（排除仅注释/文档中的方法名）。
4. 检查 Gradle settings 是否包含对应模块。

结果：

- `architecture.dsh-runtime`：`runtime/api`、`runtime/dsh` 不在 settings；仅有 2026-10-06 XML；`deadCode = unknown`。未做反射/框架入口穷举，不能判定为 true。
- `feature.foreshadow`：`ForeshadowLifecycleUseCases` 只在 `ApplicationContainer` 暴露，未找到 UI/生产消费方；测试存在。`deadCode = unknown`（未找到生产调用者，但未穷举反射/框架入口）；`Reveal` 有只读 UI，具体写路径未接线。
- `architecture.project-index`：`rebuild` 有 Workspace 调用；`find/findByType` 未找到生产消费方；节点 `deadCode = false`，但检索消费链为缺口。
- 其余节点未发现“定义但完全无引用”的证据；`deadCode = false` 表示**未确认死代码**，不表示已做穷举证明。

## 5. 文档 / 代码冲突

| 节点 | 类型 | 文档 | 实际状态 |
|---|---|---|---|
| `phase.current` | README 仍写 Current Phase = P13，工作树在 v3.1 Phase 4B.1 | `README.md:147` | 工作树 Phase 4B.1 + Phase 4C 草案 |
| `product.information-architecture` | 文档称信息架构未确定，Desktop 已有三栏 shell | README:29-34 / direction §1.5 | `Main.kt:220-230` 三栏实现 |
| `architecture.author-intelligence` | README P16 标记 PLANNED，代码/测试已存在 | `README.md:141-142` | author/decision 模块与测试 |
| `feature.author-intelligence` | 同上 | `README.md:142` | 后端/测试存在；UI 写路径未接 |
| `feature.story-foundation` | README P14 标记 PLANNED，foundation 代码/测试存在 | `README.md:140` | foundation 后端/测试存在；UI 写路径未接 |
| `phase.legacy.p14-p20` | README 标记 P14-P16 PLANNED，后端代码已存在 | `README.md:140-143` | foundation/author 后端 |
| `feature.foreshadow` | docs/status 标记 P13 SEALED，没有找到生产调用者 | `README.md:147` / status doc | UseCase+测试；UI 写路径未确认 |
| `feature.inspector-context` | addendum/AC-35 要求任务实际 Pack，代码独立 rebuild | addendum §J-6 / AC-35 | `WorkspaceUseCases.kt:314-353` |
| `feature.inspector-task-context` | 文档定义四状态，代码无状态逻辑 | addendum §J-6.2 / AC-35 | `ContextInspector.kt:195-225` 只有通用空态 |
| `feature.context-pack` | 4B.1 报告称子项完成，审计仍发现 A/B 验收缺口 | Phase 4B.1 Completion Report | AC-30A/30B 未闭合 |
| `phase.v31.4b1` | 工作树有未提交实现，阶段未验收 | Phase 4B.1 report | 12 M + 8 ?? |

## 6. 无法判断 / 需授权事项

- DSH Runtime 是否仍是项目范围：无法从当前 settings/生产调用链确认。
- `ForeshadowLifecycleUseCases` 是否存在反射/框架入口：未穷举，`deadCode=true` 仅表示未找到生产调用者。
- 测试是否在当前 HEAD 上重新全绿：本轮禁止运行构建/测试；只能确认已有 XML 晚于源码。
- Inspector 真实 Pack 的实现方案：需要产品/架构裁决（占位 vs 进程内 Store vs 结果扩展 vs 持久化）。
- AC-30A 的 A/B 数据前提：需要用户裁决“全 APPROVED 夹具”还是“两边取 APPROVED 子集 + 非批准负向”。
- 当前阶段口径：README P13 与 v3.1 Phase 4B.1 需要统一。

## 7. 非确定性记录

- 本轮只执行一次审计生成；未重复运行同一提示词，因此**未实测**两次审计结果是否完全一致。
- 为避免非确定性，状态判据采用明确的文件/测试/Gate 证据，而不是“完成度百分比”或主观权重。
- 未发现同一节点存在两个互相矛盾的证据来源；文档/代码冲突已显式记录，而不是取平均或隐藏。
- `idempotencyCheck = passed` 仅表示本次输入基线与规则可复现；不代表已重复执行验证。

## 8. 工作树与功能规划表一致性

- `qianyan-project-worktree.json` 与 `qianyan-feature-roadmap.md/csv` 由同一节点数组生成。
- 规划表只取 `type = feature` 的节点；ID 与状态直接来自工作树，不手工维护第二套状态。
- 工作树统计：75 节点；ACCEPTED 30、IMPLEMENTED_PENDING_ACCEPTANCE 4、PARTIAL 32、IN_PROGRESS 2、DECISION_REQUIRED 3、NOT_STARTED 2、UNKNOWN 2。
- 置信度：strong 32、medium 41、weak 2、speculative 0、none 0。

## 9. 与上一轮审计的基线比较

- 仓库内此前没有 `docs/audit/` 目录，因此**没有可比较的上一轮工作树审计**。
- 本轮输入基线已记录 HEAD、branch、status hash、模块/文档范围与测试统计；下一轮应先比较这些基线，再比较同 ID 节点状态。
- 不把历史对话中的 Completion Report 当作独立审计基线；它们只作为节点证据之一。

## 10. 不覆盖维度

本审计不覆盖：运行时行为（除已有 XML）、性能、并发、安全/凭据、UX、代码质量、依赖健康/CVE。需要单独任务与提示词。
