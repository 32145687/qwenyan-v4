# Development Workspaces

> 更新时间：2026-10-21
> 术语：统一称 **Development Workspace（开发工作区）**。本文档只统一术语与职责，**不等于立即重命名物理目录**。

## 1. 当前实际状态

| 工作区 | 实际路径 | 分支 | HEAD | staged | modified | untracked | 逻辑职责 |
|---|---|---|---|---|---|---|---|
| MAIN | `D:\qwenyan-v4\qwenyan-v4` | `feature/novel-ide` | `be1b755` | 0 | 73 | 36 | 主集成工作区、A2/Phase 5/6 实施面、P0-3B 文档、项目控制层 |
| A | `D:\qwenyan-v4\wt\p0-3b-a-context` | `p0-3b/a-context-pack` | `be1b755` | 0 | 12 | 8 | 上下文工作区（4A/4B.1 Context/Payload） |
| B | `D:\qwenyan-v4\wt\p0-3b-b-inspector` | `p0-3b/b-inspector-workspace` | `be1b755` | 0 | 9 | 0 | Inspector 工作区（4B.1a） |
| C | `D:\qwenyan-v4\wt\p0-3b-c-rewrite-gate` | `p0-3b/c-controlled-rewrite-gate` | `be1b755` | 0 | 20 | 2 | 改写与审批工作区（P0-2.1） |
| D | `D:\qwenyan-v4\wt\p0-3b-d-audit` | `p0-3b/d-audit-docs` | `be1b755` | 0 | 0 | 1 | 审计与文档工作区 |

说明：

- 统计为 **2026-10-21 Phase 6 第一单元轮**实际读取的 `git status --short` 条目数；目录按一条计（D 的 `docs/audit/` 在 porcelain 中显示为一条，实际含多个文件）。不是历史快照。
- MAIN 的 modified/untracked 中包含**并行 UI 工作**（`app/desktop/...` 多个文件与 `DesktopUiPreferences` 等）；该部分不是本轮 Phase 4/5 产物，本轮未修改、未恢复、未覆盖。
- 备份：`D:\qwenyan-v4\backup\p0-3b-isolation-20261020-002218`。
- C 的旧称 `p0-3b-c-controlled-rewrite-gate` 只存在于分支名；实际目录为 `p0-3b-c-rewrite-gate`。
- 未执行任何重命名、删除、重建、合并或强制切换。

## 2. 工作区职责

### MAIN（主集成工作区）

- 集成 A/B/C 的代码与 P0-3B 实现。
- 拥有：
  - `docs/planning/qianyan-p0-3b-human-approval-architecture.md`
  - `docs/project-control/`（本控制层）
- 含有 A/D 文档的集成副本（见 §3），但**不是这些文档的权威所有者**。

### A（上下文工作区）

- 负责 4A/4B.1 Context/Payload 的实现与文档。
- 文档：
  - `docs/planning/qianyan-phase4a-architecture-audit.md`（M）
  - `docs/planning/qianyan-phase4c-acceptance-criteria-draft.md`（M）
  - `docs/planning/qianyan-phase4a-context-contract-freeze-addendum-payload.md`（??）
  - `docs/planning/qianyan-phase4b1-decision-record.md`（??）

### B（Inspector 工作区）

- 负责 4B.1a Inspector/Workspace 代码与测试。
- 无独立阶段文档；`README.md`、`docs/planning/qianyan-phase4a-architecture-audit.md`、`qianyan-phase4c-acceptance-criteria-draft.md` 是基线副本，不是权威文档。
- B 的 Inspector 源码/测试与 MAIN 当前内容一致（除行尾差异外）。

### C（改写与审批工作区）

- 负责 P0-2.1 受控改写、Reject、Gate/Commit 绑定。
- 无独立阶段文档；`README.md` 与 Phase 4 文档是基线副本，不是权威文档。

### D（审计与文档工作区）

- 拥有 `docs/audit/`（porcelain 显示为 1 个未跟踪目录条目，实际含多个文件）。
- 审计台账是本控制层引用功能状态的历史来源，但不自动代表当前状态。
- `qianyan-current-phase.md`、`LATEST.md` 是 2026-10-18 历史快照/指针，不得当作当前阶段状态源。

## 3. 文档归属与重复副本

### 3.1 已知重复

| 文档 | MAIN | A | B/C/D | 权威归属 | 处理 |
|---|---|---|---|---|---|
| `docs/planning/qianyan-phase4a-architecture-audit.md` | M | M | 基线副本（B/C/D） | **A** | 不编辑 MAIN/B/C/D 副本；控制层只引用 |
| `docs/planning/qianyan-phase4c-acceptance-criteria-draft.md` | M | M | 基线副本（B/C/D） | **A** | 同上 |
| `docs/planning/qianyan-phase4a-context-contract-freeze-addendum-payload.md` | ?? | ?? | — | **A** | 同上 |
| `docs/planning/qianyan-phase4b1-decision-record.md` | ?? | ?? | — | **A** | 同上 |
| `docs/audit/2026-10-18-be1b755/*`、`docs/audit/latest/LATEST.md` | ?? | — | D 为权威；B/C 为基线 | **D** | 不编辑 MAIN 副本；控制层只引用 |

补充：

- MAIN 与 A 的 4A/4C/4B.1/addendum 文档内容一致（逐字节比较仅行尾 CRLF/LF 不同）。
- B/C/D 中的 `qianyan-phase4c-acceptance-criteria-draft.md` 与 `qianyan-phase4a-architecture-audit.md` 是较旧基线副本，不得作为权威依据，也不得用 A/MAIN 内容覆盖。
- A 的 Context/Payload 源码与 MAIN 当前内容一致（行尾差异除外）；B 的 Inspector 源码/测试与 MAIN 一致；MAIN 的 `NovelAgent.kt` 额外包含 P0-3B 审批桥接集成。

### 3.2 单一所有者

| 文档/目录 | 所有者 | 说明 |
|---|---|---|
| `docs/planning/qianyan-p0-3b-human-approval-architecture.md` | MAIN | P0-3B v2 冻结契约；不得改写 |
| `docs/project-control/` | MAIN | 项目管理控制层 |
| `docs/audit/` | D | 历史审计产物 |
| 4A/4B.1 文档 | A | 上下文线文档 |

## 4. 文档修改规则

1. 同一文档多副本时，只修改**权威所有者**工作区；其他副本不编辑。
2. 控制层可以**引用** A/D 文档，但不得复制其全文到 MAIN。
3. 不得把 D 审计文档当作生产代码或当前状态合并。
4. 不得为了统一文件名而覆盖、删除或重命名既有文档。
5. 文档改动完成后，在**实际修改的工作区**执行 `git diff --check`。

## 5. 2026-10-20 文档同步轮

本轮只修改 Markdown，不修改代码、测试、配置、Schema、Migration：

| 工作区 | 修改 |
|---|---|
| MAIN | `docs/project-control/README.md`、`v3.1-roadmap.md`、`v3.1-feature-status.md`、`development-workspaces.md`、`README.md`（仅加阶段指针） |
| A | `docs/planning/qianyan-phase4c-acceptance-criteria-draft.md`、`qianyan-phase4a-context-contract-freeze-addendum-payload.md`、`qianyan-phase4b1-decision-record.md`（均追加 2026-10-20 状态补充） |
| B | 未修改 |
| C | 未修改 |
| D | `docs/audit/2026-10-18-be1b755/qianyan-current-phase.md`、`docs/audit/latest/LATEST.md`（加历史快照提示） |

### 5.1 A2 的跨工作区阻断

A2 同时涉及 Context/Pack Store（A）、Inspector/Workspace 与 UI（B）、`NovelAgent`/DI（MAIN）。当前没有单一工作区覆盖全部，且本轮禁止合并/切换工作区。A2 实施前必须先决定工作区策略；建议由用户授权后在 MAIN 统一实施，A/B 保留历史工作区，不得直接合并或覆盖。

## 6. 2026-10-20 A2 实施轮（所有权裁决）

- **实施面：MAIN。** A2 同时涉及 Context Store（A）、Inspector/UI（B）与 `NovelAgent`/DI（MAIN）；审计确认 A 的 Context 源码与 MAIN 一致、B 的 Inspector 源码与 MAIN 一致，且只有 MAIN 含 P0-3B 集成，因此 MAIN 是唯一不会丢失现有工作的集成面。
- **A/B/C/D 保持不动。** 未合并、未切换、未复制覆盖；A/B 的阶段性成果已在 MAIN 中。
- A2 修改文件（MAIN）：
  - `application/.../usecase/context/ContextPackStore.kt`（新增）
  - `application/.../usecase/context/ContextEngineUseCases.kt`（内部 variant 构建入口）
  - `application/.../usecase/agent/NovelAgent.kt`（发布 Pack、仅消费型 Skill 构建）
  - `application/.../usecase/workspace/WorkspaceUseCases.kt`（Inspector 读取 Store）
  - `application/.../di/ApplicationContainer.kt`（Store 单例与注入）
  - `application/.../usecase/writing/planning/PlanningExecutionUseCase.kt`、`WritingExecutionUseCase.kt`（移除 legacy assembly 调用）
  - `core/model/.../context/ContextPack.kt`（Selector 去重）
  - `core/model/.../workspace/WorkspaceInspectorModels.kt`、`app/desktop/.../ContextInspector.kt`、`WorkspaceController.kt`（Inspector 展示）
  - 相关测试与边界守卫。
- 本轮仍不合并工作树；后续如需把 MAIN 的 A2 变更回写到 A/B 分支，必须另立授权并逐文件比较。

## 8. 2026-10-21 剩余验收裁决轮

- **实施面：MAIN。** 只新增裁决测试与最小入口命名收敛；A/B/C/D 未修改。
- 修改（MAIN）：
  - `application/.../usecase/context/ContextEngineUseCases.kt`：`buildWithVariant` 收敛为同一 Engine 的内部 `build(request, variantOverride)` 重载。
  - `PlanningExecutionUseCase.kt`、`WritingExecutionUseCase.kt`：调用点同步。
  - 新增测试：`AgentStructuredRetrievalTest`（AC-03）、`ContextEntryBoundaryTest`（AC-06）、`ContextStaleProductionBoundaryTest`（AC-15）、`MemoryContextBoundaryTest`（AC-11/19）。
  - 控制层文档更新 AC-03/06/11/15/35 与 Phase 4 Gate 状态。
- GUI：尝试使用 Desktop 自动化工具被环境策略阻断（“Desktop/GUI automation is restricted”）；未伪造 PASS。

## 9. 2026-10-21 Phase 5 Gate 收口轮

- 实施面：MAIN；A/B/C/D 未修改。
- 新增测试：`application/.../usecase/tool/ToolBypassGuardTest.kt`。
- 目的：证明生产非空 ToolRegistry 只由 `AgentToolBridge` / `ProductToolService` 构造；生产 ToolExecutor 只能是空 Registry 或桥接 Registry；`AgentRuntime` 不反向依赖 Application 调度层。
- 并行 UI 工作：MAIN 中 `app/desktop/...` 的额外修改来自并行工作，本轮未触碰。

## 10. 2026-10-21 Phase 6 第一单元轮

- 实施面：MAIN；A/B/C/D 未修改。
- 新增：`application/.../usecase/agent/SkillExecutionGuard.kt`、`SkillContractConsumptionTest.kt`。
- 修改：`NovelAgent`、`ContextEngineUseCases`、Planner/Writer/Critique/Revision Agent（暴露实际 agentId）。
- 内容：`allowedTools / requiredContext / expectedActions / bindings` 进入执行前生产校验；`purposes / capabilities` 由 Registry 读取。
- 未完成：LongContinuation / CharacterConsistency 的 MVP Gate 仍缺明确行为契约（见控制状态）。
- 并行 UI 工作仍保留未触碰。

## 11. 未执行事项

- 未重命名任何物理目录。
- 未合并、删除或重建任何工作区。
- 本文件初版轮未修改 A/B/C/D 的任何文件；2026-10-20 文档同步轮仅修改上表所列 A/D 的 Markdown，未修改 B/C。
- 未提交、未推送。
