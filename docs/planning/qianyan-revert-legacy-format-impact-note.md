# Qianyan｜`revert` 对 legacy 正文的格式戳记 · 影响分析记录

> - 状态：**记录性文档**（2026-10-13）。裁决 = **暂不修改生产行为**；本轮**未修改任何业务代码**。
> - 关联：`qianyan-phase0-baseline-register.md`（A3 已知问题 ①）；v3.1 §10「Phase 1 — Change Layer」。

## 1. 已确认的代码行为（代码事实）

- `CommitUseCases.revert(...)` 在事务内构造恢复用的 Draft 时调用 `WriterUseCases.stampControlledMarkdown(...)`
  （`application/.../usecase/commit/CommitUseCases.kt` 的 revert 分支）。
- `stampControlledMarkdown` 在 `draft.format == null` 时**无条件**改写为 `DraftFormat.CONTROLLED_MARKDOWN` 并落库
  （`application/.../usecase/writing/WriterUseCases.kt:62-67`）。
- TXT 导入初始化的 Canonical 载体明确写入 `format = null`（legacy plain text；Phase 1 F4 约定，`TxtCanonicalInitializationTest` 锁定）。
- **因此**：对**由 TXT 导入初始化产生**的章节执行一次 revert，会使恢复出的 Draft 带 `CONTROLLED_MARKDOWN` 标记，
  而其实质内容为 legacy 纯文本（段落以空行分隔）。

## 2. 尚未验证（不得由推断下结论）

- **Reader 实际显示**：`ReadingUseCases` 按 P2 Controlled Markdown 解析渲染；纯文本落入 Degraded 兼容路径后的呈现**是否符合契约**——**未复验**。
- **是否发生正文损坏**：目前只能确认**格式标记**被改写；**没有任何证据表明正文内容被改写**，
  ⇒ **不得据此认定发生了正文损坏**。
- **影响面规模**：需要统计"存在可 revert 历史的 TXT 导入章"的实际数量（当前无生产数据）。

## 3. 结论与后续

- **结论**：已确认**格式语义被改写**；**正文内容与 Reader 影响均未验证**；本轮**不改变现有行为**。
- **后续验证要求**（形成后续阶段任务，不在本轮实施）：
  1. 测试 A：TXT 导入章 → 作者保存修改 → `revert` → 断言 **revert 前后正文内容相等**（只比较内容）；
  2. 测试 B：断言 `Draft.format` 在 revert 前后的实际取值（暴露标记改写的事实）；
  3. 测试 C：`ReadingUseCases` 对该 Draft 的渲染结果符合 P2 契约（含 Degraded 行为）；
  4. **只有 A/B/C 均有结论后**，才讨论是否收窄 `stampControlledMarkdown` 的适用范围。
- **禁止**：在验证完成前修改 `stampControlledMarkdown`、`Draft.format` 语义或 `markdown:controlled:v1`。
