# 文档目录导航（docs/）

> 本目录是千言的唯一文档根。按"性质"分文件夹，避免同类文档散落在根目录。
> 新增文档前先看本页，选对文件夹；根目录只保留本 README。

## 目录结构

| 文件夹 | 放什么 | 典型文件 |
|---|---|---|
| `architecture/` | 架构与方向：产品方向、架构总览、冻结契约、审计前置 | `qianyan-novel-ide-direction.md`、`qianyan-novel-ide-architecture.md`、`p20-architecture-freeze.md`、`pre-dsh-audit.md` |
| `planning/` | 计划与裁决：总规划、阶段计划、契约冻结、决策记录、验收标准草案 | `qianyan-master-plan.md`、`qianyan-phase0-baseline-register.md`（含多 TXT 规则 R1–R5）、`qianyan-vocabulary-ownership-decision.md` |
| `project-control/` | 项目控制：**唯一任务状态源**、阶段收口规则、工作区约定、版本台账 | `qianyan-task-tree.md`（唯一状态源）、`stage-closeout-rules.md`、`development-workspaces.md`、`v3.1-roadmap.md`、`v3.1-feature-status.md`、`p0-3b-closeout.md` |
| `status/` | 当前状态快照（面向读者/评审的现状说明） | `qianyan-project-status.md` |
| `audit/` | 审计快照，**按原样归档、不改写**（历史基线只加指针） | `2026-10-18-be1b755/`（审计证据 + 特性台账 csv/md/json）、`latest/LATEST.md` |
| `reports/` | 阶段完成报告与前置审计（按阶段编号命名） | `P8.1`、`P8.3`、`P9`、`P10`、`P11`、`P11.1`–`P11.3`、`P11-preflight-audit` |

## 读文档的推荐顺序

1. 想知道"**现在做到哪、还差什么**" → `project-control/qianyan-task-tree.md`（§0 当前焦点 + §3 未完成）
2. 想知道"**为什么这样设计**" → `architecture/qianyan-novel-ide-direction.md` → `architecture/qianyan-novel-ide-architecture.md`
3. 想知道"**某个裁决的依据**" → `planning/`（D-xx 裁决、契约冻结、R1–R5 等规则）
4. 想追溯"**某阶段的交付**" → `reports/` 对应编号
5. 想核对"**某次审计的原始证据**" → `audit/<日期-commit>/`

## 维护规则（摘要）

- **状态只写一处**：任务状态只改 `project-control/qianyan-task-tree.md`，其它文档不得另立状态表；状态变更须在 §5 更新日志追加一行。
- **快照不改写**：`audit/` 下的历史快照按原样保留；新结论写在新文档或用指针引用。
- **命名约定**：`qianyan-<主题>.md`；阶段报告 `P<阶段>-<类型>.md`；日期目录 `YYYY-MM-DD-<commit>`。
- **一次一个主题**：一份文档只回答一类问题，避免"大而全"的混合文档。
