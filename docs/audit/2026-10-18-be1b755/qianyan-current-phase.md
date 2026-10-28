# Qianyan Current Phase

工作树使用契约

1. 本文件是当前阶段状态的快照，生成时间与对应 HEAD 见文件头。

2. 每轮新任务开始前，QCode / Trae 必须先读取本文件，确认：
   - 当前 Phase
   - 当前阻塞项
   - 待决策事项
   - 禁止进入的下一阶段

3. 不允许跳过本文件直接开始新任务。

4. 每轮任务完成后，更新本文件的状态；涉及阶段变化时，重跑完整审计。

5. 本文件不是计划的替代品——详细契约见 v3.1 和各 Phase Decision Record。

6. 待用户裁决事项在本文件中单独列出；未清空之前，不进入下一任务。

---

## 快照

- generatedAt: 2026-10-17T17:31:30Z（本地 2026-10-18）
- HEAD: `be1b75582322a43a114fdc174597e29cfe2de87c`
- branch: `feature/novel-ide`
- working tree: modified（12 modified + 8 untracked；staged 为空；ahead 2 / behind 0）
- audit snapshot: `docs/audit/2026-10-18-be1b755/`
- 工作树节点：75（ACCEPTED 30 / IMPLEMENTED_PENDING_ACCEPTANCE 4 / PARTIAL 32 / IN_PROGRESS 2 / DECISION_REQUIRED 3 / NOT_STARTED 2 / UNKNOWN 2）

## 当前 Phase

**v3.1 Phase 4B.1 — ContextPack 统一入口 / Context + Retrieval 实现。**

- Phase 4A 契约冻结已提交（`be1b755`）。
- Phase 4B.1 的代码、测试与文档修订在**工作树未提交**（12 M + 8 ??）。
- Phase 4C 验收草案已存在，明确仍有多项不可验收。
- **口径冲突**：README 仍写 `Current Phase = P13 Long-form Continuity Layer`（`README.md:147`），与实际工作树的 v3.1 Phase 4B.1 不一致。按代码与工作树判断，当前阶段是 Phase 4B.1；README 需要用户裁决后统一。

## 阻塞项

1. **A：Inspector 真实任务 ContextPack 未实现。**
   - 当前 Inspector 每次 load 独立调用 `ContextEngineUseCases.build`，生成相似但非任务实际的 Pack。
   - `NovelAgentResult` 只返回 Pack id/version，不返回 Pack；UI 调用方不保留 Pack。
   - AC-08「Inspector 与写作链同一 Context」、AC-35「任务上下文四状态」未实现。
   - 方案比较与授权级别见 `qianyan-project-worktree.md` / 审计报告；当前未选定真实 Pack 方案。

2. **B：Vocabulary 分层契约仍需验收证据。**
   - J-1.1 / J-2.1 已写入文档（拟议修订），但 AC-30A 缺旧/新 A/B 测试；AC-30B 缺 Planner/Writer prompt 级证据。
   - 现有 `VocabularyDatasetEquivalenceTest` 只比较新 Source 与 APPROVED 查询结果，不能代替旧/新整体 A/B。
   - 预算不足时 Vocabulary 子集可能被省略；省略规则有 Selector 测试，但没有 Vocabulary 专项 prompt 影响测试。

3. **Phase 4B.1 未验收。**
   - 代码与测试在工作树，测试 XML 显示 0 failures/errors（2026-10-17 14:20–14:43Z，243 个 fresh XML），但本审计未重跑测试。
   - A/B 未闭合，不能以“测试通过”替代阶段验收。

4. **阶段口径冲突。**
   - README/status doc 的旧路线（P13）与 v3.1 Phase 4B.1/4C 并存；跨轮审计与交接前必须统一。

5. **DSH Runtime 状态未知。**
   - `runtime/api`、`runtime/dsh` 不在 `settings.gradle.kts`；其 XML 为 2026-10-06 的陈旧结果，且无当前生产调用链。

6. **测试与源码对应关系未由本轮重跑证明。**
   - 现有 XML 均晚于对应源码最后修改时间；但本轮按约束未运行任何构建/测试。

## 待用户裁决

> **2026-10-18 更新**：D-1～D-9 已在 `docs/planning/qianyan-phase4b1-decision-record.md` 固化；下表保留为审计快照，不再作为当前待办。下一步唯一任务为 4B.1a（需用户单独下达提示词）。

| 编号 | 问题 | 选项 / 影响 | 推荐 |
|---|---|---|---|
| D-1 | Phase 4B.1 的 A 是否接受 Inspector 占位？ | A1 接受占位，4B.1 以占位通过，真实 Pack 延后；A2 要求真实 Pack，4B.1 不能通过并拆子阶段 | A1 作为最小路径，A2 作为后续 |
| D-2 | 若实施真实 Pack，采用哪种方案？ | B 进程内 Store（级别 3）；C1 扩展 Result（级别 2）；D 持久化（级别 3） | B（sessionId 键，无 Migration）；C1 仅作最近结果备选 |
| D-3 | Inspector 任务定位与入口 | sessionId + 最近 AgentSession；或要求 TaskId；或新增 Workspace 会话选择 | sessionId；TaskId 当前不是每次 run 都有 |
| D-4 | 当前事实是否继续展示？ | 单独标注的只读当前事实；或 4B.1 先隐藏 | 单独标注，禁止调用 ContextEngine.build |
| D-5 | J-1.1/J-2.1 分层是否生效？ | 生效并定为数据源层/模型输入层；或退回原 J-1/J-2 文字 | 生效；同时明确 AC-30A 的 A/B 数据前提 |
| D-6 | AC-30A 的 A/B 前提 | 两边都取 APPROVED 子集并另测未批准排除；或声明生产 Entry 只允许 APPROVED | 前者更可验证 |
| D-7 | 阶段口径 | 更新 README 为 v3.1 Phase 4B.1；或恢复旧 P13 口径 | 更新 README；旧路线保留历史 |
| D-8 | DSH Runtime 是否仍在范围内 | 保留待后续；或明确废弃/休眠 | 明确休眠并登记，不在 4B 处理 |
| D-9 | 是否授权运行完整回归 | 需要刷新测试 XML；会改变 build 目录 | 在实现/验收轮次授权运行 |

## 已裁决（历史，供参考）

- Phase 3：D-1…D-4（Commit 后旁路失败、Human Gate、UPDATE/REMOVE、规则解释）已封存；Completion Gate 满足，四项后续工作仍存在。
- Phase 4A：D1–D4（Context 契约、唯一载体、Memory 边界、packVersion）已冻结。
- Phase 4B：J-1=A（Vocabulary 数据源）、J-2=A（预算）、J-3=A（Variant fail-fast）、J-4=C（legacy 受控豁免）。
- Phase 4B.1：canonical-v1、两阶段选择、payload 投影、J-3/J-4 已实现；A/B 验收未闭合。

## 下一步验收任务

> **2026-10-18 更新**：D-1～D-9 已固化；以下旧步骤仅保留为审计历史。下一步唯一允许任务是 **4B.1a 实施**，且必须等待用户单独下达 4B.1a 提示词。

1. 用户裁决 D-1…D-7；未清空前不进入 4B.2。
2. 若选真实 Pack：按级别 2/3 另开子阶段，不在 4B.1 内顺手实现。
3. 若选占位：实现 Inspector 四状态占位与 no-build 边界，并更新 `WorkspaceBoundaryTest`。
4. B：补 AC-30A 旧/新 A/B、非 APPROVED 边界、AC-30B prompt 捕获测试（单独测试/实施任务）。
5. 在授权后重跑完整回归，刷新测试 XML 并更新本文件。
6. 统一 README / status doc / v3.1 的阶段口径。

## 禁止

- 未清空待裁决前，不得进入 Phase 4B.2 / 4B.3 或后续阶段。
- 不得用独立 rebuild 的 Pack 冒充 Agent 实际任务快照。
- 不得把 AC-30A 的预算后结果当作来源数据集等价证明。
- 不得在未授权时引入 Pack Store、持久化、Session Map 或第二套 Context 模型。

## 2026-10-18 决策固化（追加）

- **决策记录**：`docs/planning/qianyan-phase4b1-decision-record.md`。
- **D-1～D-9：已决定**。上文的「待用户裁决」表保留为审计快照；D-1～D-9 的内容、授权边界与验收以决策记录为准。
- **当前唯一允许的下一步**：4B.1a 实施（Inspector 四状态占位 + no-build 边界 + 必要测试）。**未经用户下达 4B.1a 提示词，不得开始实施。**
- **4B.1a 禁止**：A2、真实 ContextPack Store、扩展 Agent 结果、持久化、第二套 Context 模型、AC-30A/30B 测试、README/status doc 修改。
- **A2**：未实施、未验收；不得宣称 Inspector 已展示真实任务快照。
- **回归**：4B.1a 完成后授权一次完整回归；A2 完成后另一次；两次不合并。当前不授权运行完整回归。
