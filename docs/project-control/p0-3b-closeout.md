# P0-3B 收尾记录

> 更新时间：2026-10-20
> 性质：阶段收尾状态记录。**P0-3B 尚未正式关闭**，因为 GUI 验收未执行。
> 冻结契约：`docs/planning/qianyan-p0-3b-human-approval-architecture.md`（v2），共 D1–D9、Desktop UI 契约、7 步实施顺序与验收标准。

## 1. 总体状态

| 项目 | 状态 |
|---|---|
| 自动化验收 | **DONE（历史验收结果）** |
| GUI 验收（G1–G8） | **PENDING_VERIFICATION** |
| P0-3B 整体 | **PENDING_VERIFICATION** |
| 业务代码 | 未在本轮修改 |
| Schema / Migration | 无新增 |
| commit / push | 未执行 |

**结论**：不得把 P0-3B 标记为“全部完成”。自动化通过不等于 GUI 全部通过。

## 2. 自动化验收证据（历史，本轮未重跑）

证据文件（当前工作区存在）：

- `application/build/test-results/test/TEST-*.xml`：148 个 suite，**987 tests**
- `app/desktop/build/test-results/jvmTest/TEST-*.xml`：8 个 suite，**33 tests**
- `app/android/build/test-results/testDebugUnitTest/TEST-*.xml`：8 个 suite，**59 tests**

根 JVM 模块合计（application + core:model + core:engine + agent:* + provider:* + storage + e2e）：

- **1330 个唯一测试方法**
- 加 Android 59 + Desktop 33 = **1422 个唯一测试方法**
- 历史报告记录：**0 failures / 0 errors / 0 skipped**

历史报告中的 `git diff --check`：exit 0。

**本轮只核对上述 XML 存在与计数，未重新运行测试**；不得把历史结果表述为本轮实测。

## 3. GUI 验收

| 场景 | 状态 | 说明 |
|---|---|---|
| G1 未选择章节 | **PENDING_VERIFICATION** | 未执行真实输入/提交 |
| G2 正常 Agent 候选 | **PENDING_VERIFICATION** | 未执行真实任务 |
| G3 已桥接候选 | **PENDING_VERIFICATION** | 未执行 UI 检查 |
| G4 未桥接候选 | **PENDING_VERIFICATION** | 未制造/观察 |
| G5 桥接重试 | **PENDING_VERIFICATION** | 未执行 |
| G6 候选失效 | **PENDING_VERIFICATION** | 未执行 |
| G7 正文页正式审批 | **PENDING_VERIFICATION** | 未执行批准/拒绝 |
| G8 重复操作与边界 | **PENDING_VERIFICATION** | 未执行 |

已知事实：

- Desktop 曾真实启动并显示窗口 `千言 Qianyan · Novel IDE`，初始 AgentPage 可观察。
- 后续 GUI 自动化访问/点击被环境安全策略阻断；未绕过。
- 因此 G1–G8 **全部保持 PENDING_VERIFICATION / NOT RUN**，没有 PASS。

## 4. D1–D9 状态

| 决策 | 状态 | 说明 |
|---|---|---|
| D1 正式审批唯一归属 | **DONE** | ChapterWorkflow / Human Gate 唯一正式链路；NovelAgent 不另建审批 |
| D2 方案 A 桥接 | **DONE** | `startChapter` + `proposeRewrite`；Artifact 身份复用 |
| D3 候选身份绑定 | **DONE** | WorkingDraft.sessionId、缺失/错误 session、跨 Project 均有自动化测试 |
| D4 重复桥接幂等 | **DONE** | 同候选定位既有 Gate；冲突候选显式 UNBRIDGED |
| D5 桥接失败处理 | **DONE** | `CANDIDATE_UNBRIDGED` + 原因；候选保留；不创建第二 Workflow |
| D6 拒绝语义统一 | **DONE** | 正式 reject 先行；Session/候选由协调入口收敛 |
| D7 跨重启失效 | **DONE（按“不恢复”裁决）** | 重启后显式 `ARTIFACT_NOT_FOUND`；无恢复能力 |
| D8 无 stepId Gate | **IN_PROGRESS** | 默认拒绝/复用检查/源码守卫；`allowUnboundGate` 测试兼容开关仍存在 |
| D9 批准后收敛 | **IN_PROGRESS** | Commit 层清理失败已注入；Agent 层清理故障未独立注入 |

## 5. 已知限制

1. **D8 未结构性绝对禁止**：
   - `allowUnboundGate` 默认 `false`；
   - 无 stepId Gate 默认 `GATE_TARGET_MISMATCH`；
   - Gate 复用检查先于兼容分支；
   - 生产源码守卫覆盖命名/位置参数；
   - 但该字段仍可在运行时被显式置 true。彻底移除需要迁移 57 个 `approvedGate` + 4 个 `approvedCommitGate` 调用点，属独立测试重构。
2. **D9 Agent 层故障注入缺口**：Commit 层 WorkingDraft 清理失败、World Model 更新失败已验证；Agent 层“提交后自动补收尾”未独立注入。
3. **跨重启恢复未实现**：P0-3B 明确不扩展；候选失效后需重新生成。
4. **GUI 未验收**：Desktop 真实窗口启动过，但真实点击/输入未执行。
5. **无 Schema / Migration**：P0-3B 未新增表、迁移或恢复系统。
6. **工作区文档重复**：MAIN 中存在 A/D 文档的集成副本；权威归属见 `development-workspaces.md`。

## 6. 证据与来源

- 架构/契约：`docs/planning/qianyan-p0-3b-human-approval-architecture.md`（v2）
- 4B.1 决策：`docs/planning/qianyan-phase4b1-decision-record.md`
- 历史功能台账：`docs/audit/2026-10-18-be1b755/qianyan-feature-roadmap.md`
- 历史阶段快照：`docs/audit/2026-10-18-be1b755/qianyan-current-phase.md`
- 自动化测试 XML：上述 build/test-results 路径
- 代码实现位置（仅索引，不在本文件复述实现）：
  - `application/.../usecase/agent/NovelAgent.kt`
  - `application/.../usecase/workflow/ChapterWorkflowFacade.kt`
  - `application/.../usecase/workflow/WorkflowOrchestrator.kt`
  - `application/.../usecase/commit/CommitUseCases.kt`
  - `application/.../usecase/change/ChangeUseCases.kt`
  - `app/desktop/.../ui/Common.kt`
  - `app/desktop/.../ui/pages/AgentPage.kt`

## 7. 关闭条件

P0-3B 正式关闭前必须至少满足：

1. G1–G8 在真实 Desktop 窗口完成并记录 PASS/FAIL；
2. 若发现缺陷，明确是否属于 P0-3B 契约范围并修复/登记；
3. 关闭报告区分自动化验收与 GUI 验收；
4. 不修改 D1–D9 冻结决策；
5. 未经授权不 commit/push。

在条件满足前，P0-3B 保持 **PENDING_VERIFICATION**。
