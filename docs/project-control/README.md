# Qianyan 项目管理控制层

> 目录：`docs/project-control/`
> 生成/更新：2026-10-20（Phase 4 审计同步轮）
> 性质：**项目管理与状态控制层**。不替代 v3.1 规划，不修改冻结架构契约，不包含新的功能设计。

## 1. 权威层级

1. **冻结架构契约**：`docs/architecture/p20-architecture-freeze.md`、`docs/planning/qianyan-p0-3b-human-approval-architecture.md` 及各阶段 Decision Record。
2. **v3.1 阶段规划**：`docs/planning/qianyan-dev-implementation-plan-v3.1.md`（本目录不改写其正文）。
3. **本控制层**：阶段状态、功能状态增量、开发工作区职责、收尾规则。
4. **历史审计/报告**：`docs/audit/`、各阶段 completion/report 文档；仅作历史证据，不自动代表当前状态。

## 2. 文件索引

| 文件 | 用途 |
|---|---|
| [v3.1-roadmap.md](v3.1-roadmap.md) | v3.1 Phase 总览、当前阶段状态、P0-3B 覆盖项、下一阶段建议 |
| [v3.1-feature-status.md](v3.1-feature-status.md) | 功能状态增量台账（以审计功能清单为历史基线） |
| [development-workspaces.md](development-workspaces.md) | MAIN/A/B/C/D 开发工作区职责、路径、分支、文件归属与冲突 |
| [stage-closeout-rules.md](stage-closeout-rules.md) | 今后每个阶段完成后的固定收尾规则 |
| [p0-3b-closeout.md](p0-3b-closeout.md) | P0-3B 自动化证据、GUI 待验证状态、D8/D9 与已知限制 |

## 3. 当前状态摘要

| 项目 | 状态 |
|---|---|
| v3.1 当前阶段 | **Phase 4 `DONE`；Phase 5 `DONE`；Phase 6 `DONE`（GATE_PASS_WITH_LIMITATIONS）** |
| Phase 5 | **DONE（v3.1 §14 Gate）**；Tool Runtime 五件套、权限、审计、无旁路守卫均有证据 |
| Phase 6 | **DONE（GATE_PASS_WITH_LIMITATIONS）**：第一单元 + 2A + 2B + 2C 全部完成；CharacterConsistency Skill 闭环（D-21/D-29）；LongContinuation = 2A 工作流能力（D-30）。限制见任务树 O-23/24/25/29(2)/30/31/32/33 |
| P0-3B 自动化验收 | **DONE（历史验收结果，本轮未重跑）** |
| P0-3B GUI 验收 | **PENDING_VERIFICATION**（G1–G8 均未执行；属 P0-3B 独立验收） |
| P0-3B 整体 | **PENDING_VERIFICATION**（不得标记为全部完成） |
| 下一阶段建议 | **Phase 7（Natural Language + Novel Agent）**：T-7-1 决策点行为测试 → T-7-2 P0-3B GUI；并行的 V1–V3 词库、W1 工作树、G1 提交冻结待裁 |
| 本轮改动 | Phase 4 剩余裁决测试 + Phase 5 无旁路守卫 + 阶段状态收口；未改 Schema/Migration/冻结契约；未提交 |

## 4. 术语

- **Development Workspace（开发工作区）**：本文档统一使用该术语指代 MAIN/A/B/C/D。
- 术语统一**不等于**物理目录重命名；未经路径/分支/未提交内容审计与用户授权，不得重命名、删除、重建或合并任何开发工作区。

## 5. 文档纪律

- 同一份文档在多个工作区出现时，先确认归属；不得同时修改多份副本。
- 不把 A/B/C/D 的文档随意复制进 MAIN；MAIN 中的重复副本仅作为集成副本引用。
- 不修改已冻结的架构契约来“补齐”状态文档。
- 发现规划与代码不一致时，记录差异和证据，不擅自改写规划。

## 6. 状态词

`DONE` / `IN_PROGRESS` / `NOT_STARTED` / `BLOCKED` / `PENDING_VERIFICATION` / `DEFERRED`

- `DONE`：实现与对应验收证据均已成立。
- `PENDING_VERIFICATION`：实现/自动化证据存在，但规定的验收（如 GUI）未执行。
- `DEFERRED`：明确不在当前阶段实施，且有决策依据。
- `BLOCKED_BY_PHASE_4`：阶段未启动/未通过，原因是对 Phase 4 的硬依赖尚未满足。
