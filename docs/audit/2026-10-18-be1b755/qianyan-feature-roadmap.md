# Qianyan Feature Roadmap

- generatedAt: 2026-10-17T17:31:30.242Z
- HEAD: be1b75582322a43a114fdc174597e29cfe2de87c
- source: qianyan-project-worktree.json (same node IDs/status)

| ID | Domain | Feature | Phase | Impl | Acceptance | Gaps |
|---|---|---|---|---|---|---|
| `product.desktop-shell` | desktop-shell | Desktop 三栏工作区 Shell | 未标注 | IMPLEMENTED | PENDING_ACCEPTANCE | 信息架构未最终裁决；部分写操作未接入 |
| `product.inspector` | inspector | Inspector 只读检查器 | 未标注 | PARTIAL | PARTIAL | 任务上下文仍是独立 rebuild，不是 Agent 实际 Pack；AC-08/AC-35 未闭合 |
| `product.natural-language-entry` | natural-language-entry | 自然语言 / Agent 统一任务入口 | 未标注 | IMPLEMENTED | PENDING_ACCEPTANCE | Agent Task 的 Context 快照未回传 Inspector |
| `product.android-app` | android-app | Android 伴生应用 | 未标注 | PARTIAL | PARTIAL | 产品策略 PAUSED；部分旧闭环不再扩展 |
| `feature.txt-import` | txt-import | TXT 导入与新小说创建 | P4/P5 | PARTIAL | PARTIAL | 多 TXT 合并未实现 |
| `feature.txt-import.parse` | txt-import | TXT 解析与章节识别 | P4 | IMPLEMENTED | ACCEPTED |  |
| `feature.txt-import.canonical-init` | txt-import | Canonical 初始化 | P1-02/F1-F3 | IMPLEMENTED | ACCEPTED |  |
| `feature.txt-import.multi-document-order` | txt-import | 多 TXT 全书章节顺序 | Phase1/后续 | NOT_STARTED | NOT_STARTED | 规则 R1-R5 已裁决，多 TXT 合并功能未实现 |
| `feature.novel-create` | novel-create | 新建/打开小说 | P4/P5/P7 | IMPLEMENTED | ACCEPTED |  |
| `feature.writing.planning` | writing | 章节规划 | P11.2 | IMPLEMENTED | ACCEPTED |  |
| `feature.writing.draft` | writing | 正文生成/Draft 持久化 | P11.3 | IMPLEMENTED | ACCEPTED |  |
| `feature.writing.critique-revision` | writing | 批评与修订 | P11.4 | IMPLEMENTED | ACCEPTED |  |
| `feature.writing.knowledge-update` | writing | 知识更新写入 | P11.5 | PARTIAL | PARTIAL | Desktop 菜单标注“知识更新·未接入” |
| `feature.writing.story-world-context` | writing | 故事世界上下文 | P11.6/P12.1.2 | IMPLEMENTED | ACCEPTED |  |
| `feature.writing.working-draft-change` | writing | WorkingDraft→Change/Review | P1-02 | IMPLEMENTED | ACCEPTED |  |
| `feature.writing.confirmation-gate` | writing | 定稿确认 Gate | P12.1.4 | PARTIAL | PARTIAL | Desktop “定稿确认·未接入” |
| `feature.reading` | reading | 阅读 | P13 | PARTIAL | PARTIAL | 阅读位置写入在 Desktop 未接线 |
| `feature.vocabulary.analysis` | vocabulary | TXT 词库候选分析 | P6 | IMPLEMENTED | ACCEPTED |  |
| `feature.vocabulary.confirmation` | vocabulary | 词库候选确认/拒绝/编辑 | P20-P1 | PARTIAL | PARTIAL | Desktop 创建/部分写路径未接线 |
| `feature.vocabulary.context-source` | vocabulary | Vocabulary Source Dataset | Phase4B.1 | PARTIAL | PARTIAL | AC-30A 缺旧/新 A/B；非 APPROVED Entry 行未覆盖 |
| `feature.vocabulary.budget-subset` | vocabulary | Model Input Vocabulary Subset | Phase4B.1 | PARTIAL | PARTIAL | AC-30B 缺 prompt 级证据；omitted 互补未显式断言 |
| `feature.continuation` | continuation | 长篇续写 / ContinuationReference | P12.1.3 | IMPLEMENTED | ACCEPTED |  |
| `feature.characters` | characters | 人物一致性 / Story State | P12.1.1/P12.3 | PARTIAL | PARTIAL | Variant 写入路径在 Desktop 未接线；读取为主 |
| `feature.foreshadow` | foreshadow | 伏笔生命周期 / Reveal | P13 | PARTIAL | PARTIAL | ForeshadowLifecycle 无 UI/生产调用者；Reveal 写入未接线 |
| `feature.rewrite-review` | rewrite-review | 改写 / 审阅 / Diff / Change | P11.4/P1-02 | IMPLEMENTED | ACCEPTED |  |
| `feature.history-revert` | history-revert | 历史 / Revert | P1/P20 | IMPLEMENTED | ACCEPTED |  |
| `feature.agent-nl-task` | agent-nl-task | 自然语言 Agent 任务 | Phase4B/7 | PARTIAL | PARTIAL | Context Pack 任务快照未回传 Inspector |
| `feature.inspector-context` | inspector-context | Inspector 上下文展示 | Phase4B.1 | PARTIAL | PARTIAL | 当前独立 rebuild；AC-08/AC-35 未实现 |
| `feature.inspector-task-context` | inspector-task-context | Inspector 任务上下文四状态 | Phase4B.1 | PARTIAL | PARTIAL | 四状态文案/判定未实现；无真实 Pack 来源；AC-35 待决策 |
| `feature.android` | android | Android 功能闭环 | P7/P12.1.6-P12.1.7 | PARTIAL | PARTIAL | 产品状态 PAUSED；部分旧闭环不再扩展 |
| `feature.desktop-writer` | desktop-writer | Desktop 写作界面 | PC-2 | PARTIAL | PARTIAL | 定稿确认/知识更新菜单未接入 |
| `feature.story-foundation` | story-foundation | Story Foundation / Idea | P14/P15 | PARTIAL | PARTIAL | Desktop 确认/修改写路径未接线；README P14 标注未实现但代码存在 |
| `feature.author-intelligence` | author-intelligence | 作者智能 / Author Core | P16-P19 | PARTIAL | PARTIAL | README P16 标注 PLANNED；Desktop 写路径多为接入点 |
| `feature.world-model-update` | world-model-update | World Model Update | Phase3 | IMPLEMENTED | ACCEPTED | 四项后续工作挂起 |
| `feature.context-pack` | context-pack | ContextPack 统一入口 Phase 4B.1 | Phase4B.1 | PARTIAL | PARTIAL | AC-06/08 未闭合；AC-30A/30B 证据不足；A 待决策 |
