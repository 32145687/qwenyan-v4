# Qianyan Project Worktree

- generatedAt: 2026-10-17T17:31:30.242Z
- HEAD: be1b75582322a43a114fdc174597e29cfe2de87c
- branch: feature/novel-ide
- workingTree: modified (12 M / 8 ??)
- schema: qianyan-worktree-schema-1
- rules: qianyan-audit-rules-2026-10-18

## Status summary

| Domain | Nodes | ACCEPTED | IMPLEMENTED_PENDING_ACCEPTANCE | PARTIAL | IN_PROGRESS | DECISION_REQUIRED | NOT_STARTED | UNKNOWN |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| product | 7 | 1 | 2 | 3 | 0 | 1 | 0 | 0 |
| architecture | 18 | 10 | 1 | 6 | 0 | 0 | 0 | 1 |
| feature | 32 | 13 | 0 | 18 | 0 | 0 | 1 | 0 |
| phase | 18 | 6 | 1 | 5 | 2 | 2 | 1 | 1 |
| **total** | **75** | 30 | 4 | 32 | 2 | 3 | 2 | 2 |

## Confidence

| Domain | strong | medium | weak | speculative | none |
|---|---:|---:|---:|---:|---:|
| product | 1 | 6 | 0 | 0 | 0 |
| architecture | 9 | 8 | 1 | 0 | 0 |
| feature | 14 | 18 | 0 | 0 | 0 |
| phase | 8 | 9 | 1 | 0 | 0 |

## Tree

- **[PARTIAL] 产品与 UI** `product` (medium)
  - gap: 产品方向与 UI 落地分层；Desktop/Android 仍有未接线写路径
  - **[PARTIAL] Android 伴生应用** `product.android-app` (medium)
    - gap: 产品策略 PAUSED；部分旧闭环不再扩展
  - **[IMPLEMENTED_PENDING_ACCEPTANCE] Desktop 三栏工作区 Shell** `product.desktop-shell` (medium)
    - gap: 信息架构未最终裁决；部分写操作未接入
  - **[DECISION_REQUIRED] 信息架构 / 三栏形态** `product.information-architecture` (strong)
    - gap: 文档称信息架构未定，但 Desktop 已有三栏 shell
    - conflict: doc_says_undetermined_code_has_shell — README.md:29-34 / direction §1.5
  - **[PARTIAL] Inspector 只读检查器** `product.inspector` (medium)
    - gap: 任务上下文仍是独立 rebuild，不是 Agent 实际 Pack；AC-08/AC-35 未闭合
  - **[IMPLEMENTED_PENDING_ACCEPTANCE] 自然语言 / Agent 统一任务入口** `product.natural-language-entry` (medium)
    - gap: Agent Task 的 Context 快照未回传 Inspector
  - **[ACCEPTED] Agent 优先的 AI 小说创作客户端定位** `product.positioning` (medium)
    - gap: 决策级接受，不是功能实现

- **[PARTIAL] 核心架构** `architecture` (medium)
  - gap: 多数核心层稳定；Context 收敛与部分 Tool/Inspector 链路未闭合
  - **[ACCEPTED] Agent Runtime / Tool** `architecture.agent-runtime` (strong)
    - gap: Agent 自主 Tool Calling 受编排限制
  - **[ACCEPTED] ApplicationContainer / DI** `architecture.application-container` (strong)
    - gap: 部分服务仅暴露未接线
  - **[IMPLEMENTED_PENDING_ACCEPTANCE] Author / Decision Model** `architecture.author-intelligence` (medium)
    - gap: README P16 标注 PLANNED；Desktop 写路径多为接入点
    - conflict: doc_says_planned_code_implemented — README.md:141-142
  - **[ACCEPTED] Change Layer / Canonical Commit** `architecture.change-layer` (strong)
    - gap: 旧 saveContent 旁路需持续保护
  - **[ACCEPTED] CommitHistory / 格式契约** `architecture.commit-history` (strong)
    - gap: legacy 行为需按契约保护
  - **[PARTIAL] Context Engine / ContextPack** `architecture.context-engine` (medium)
    - gap: AC-06/08 未闭合；AC-30A/30B 证据不足；AC-08 任务快照未实现
  - **[ACCEPTED] 领域模型与强类型 ID** `architecture.domain-model` (strong)
    - gap: 部分 P14+ 模型有代码但 README 标注未实现
  - **[UNKNOWN] DSH Runtime** `architecture.dsh-runtime` (weak)
    - gap: runtime/api、runtime/dsh 不在 settings；无当前生产调用链；产品动机不明
  - **[ACCEPTED] Long-form Continuity Layer** `architecture.lcl` (medium)
    - gap: 部分写路径仅展示接入点
  - **[ACCEPTED] Gradle 多模块骨架** `architecture.modules` (strong)
    - gap: runtime/api、runtime/dsh 非 Gradle subproject
  - **[PARTIAL] NovelAgent 七相位编排** `architecture.novel-agent` (medium)
    - gap: Context Pack 真实快照未暴露；部分 Skill 未绑定
  - **[PARTIAL] PlanningContextAssembly / payload 投影** `architecture.planning-assembly` (medium)
    - gap: legacy 无 Pack 路径仍存在；Inspector 任务快照不经过该 Pack
  - **[PARTIAL] Project Index** `architecture.project-index` (medium)
    - gap: AC-03：find/findByType 检索结果缺生产消费方
  - **[ACCEPTED] LLM Provider / Gateway** `architecture.provider-runtime` (strong)
    - gap: 真实 Provider 依赖外部配置
  - **[ACCEPTED] SQLDelight Schema / Migration v28** `architecture.storage-schema` (strong)
    - gap: 未来表变更仍需逐版验证
  - **[PARTIAL] Durable Workflow / Human Gate** `architecture.workflow-hitl` (medium)
    - gap: Desktop 批准动作待接线（P0-3）
  - **[ACCEPTED] World Model / Story State** `architecture.world-model` (strong)
    - gap: 失败诊断载体、Human Gate、UPDATE/REMOVE、候选消费方仍挂起

- **[PARTIAL] 用户功能** `feature` (medium)
  - gap: 后端能力多于 Desktop 已接线写操作
  - **[PARTIAL] 自然语言 Agent 任务** `feature.agent-nl-task` (medium)
    - gap: Context Pack 任务快照未回传 Inspector
  - **[PARTIAL] Android 功能闭环** `feature.android` (medium)
    - gap: 产品状态 PAUSED；部分旧闭环不再扩展
  - **[PARTIAL] 作者智能 / Author Core** `feature.author-intelligence` (medium)
    - gap: README P16 标注 PLANNED；Desktop 写路径多为接入点
    - conflict: doc_says_planned_code_implemented — README.md:142
  - **[PARTIAL] 人物一致性 / Story State** `feature.characters` (medium)
    - gap: Variant 写入路径在 Desktop 未接线；读取为主
  - **[PARTIAL] ContextPack 统一入口 Phase 4B.1** `feature.context-pack` (medium)
    - gap: AC-06/08 未闭合；AC-30A/30B 证据不足；A 待决策
    - conflict: report_says_complete_audit_says_partial — Phase 4B.1 Completion Report
  - **[ACCEPTED] 长篇续写 / ContinuationReference** `feature.continuation` (strong)
  - **[PARTIAL] Desktop 写作界面** `feature.desktop-writer` (medium)
    - gap: 定稿确认/知识更新菜单未接入
  - **[PARTIAL] 伏笔生命周期 / Reveal** `feature.foreshadow` (medium)
    - gap: ForeshadowLifecycle 无 UI/生产调用者；Reveal 写入未接线
    - conflict: doc_says_sealed_no_production_caller — README.md:147 / status doc
  - **[ACCEPTED] 历史 / Revert** `feature.history-revert` (strong)
  - **[PARTIAL] Inspector 上下文展示** `feature.inspector-context` (medium)
    - gap: 当前独立 rebuild；AC-08/AC-35 未实现
    - conflict: doc_requires_task_snapshot_code_rebuilds — addendum §J-6 / AC-35
  - **[PARTIAL] Inspector 任务上下文四状态** `feature.inspector-task-context` (medium)
    - gap: 四状态文案/判定未实现；无真实 Pack 来源；AC-35 待决策
    - conflict: doc_says_required_code_not_implemented — addendum §J-6.2 / AC-35
  - **[ACCEPTED] 新建/打开小说** `feature.novel-create` (strong)
  - **[PARTIAL] 阅读** `feature.reading` (medium)
    - gap: 阅读位置写入在 Desktop 未接线
  - **[ACCEPTED] 改写 / 审阅 / Diff / Change** `feature.rewrite-review` (strong)
  - **[PARTIAL] Story Foundation / Idea** `feature.story-foundation` (medium)
    - gap: Desktop 确认/修改写路径未接线；README P14 标注未实现但代码存在
    - conflict: doc_says_planned_code_implemented — README.md:140
  - **[PARTIAL] TXT 导入与新小说创建** `feature.txt-import` (medium)
    - gap: 多 TXT 合并未实现
    - **[ACCEPTED] Canonical 初始化** `feature.txt-import.canonical-init` (strong)
    - **[NOT_STARTED] 多 TXT 全书章节顺序** `feature.txt-import.multi-document-order` (strong)
      - gap: 规则 R1-R5 已裁决，多 TXT 合并功能未实现
    - **[ACCEPTED] TXT 解析与章节识别** `feature.txt-import.parse` (strong)
  - **[ACCEPTED] TXT 词库候选分析** `feature.vocabulary.analysis` (strong)
  - **[PARTIAL] Model Input Vocabulary Subset** `feature.vocabulary.budget-subset` (medium)
    - gap: AC-30B 缺 prompt 级证据；omitted 互补未显式断言
  - **[PARTIAL] 词库候选确认/拒绝/编辑** `feature.vocabulary.confirmation` (medium)
    - gap: Desktop 创建/部分写路径未接线
  - **[PARTIAL] Vocabulary Source Dataset** `feature.vocabulary.context-source` (medium)
    - gap: AC-30A 缺旧/新 A/B；非 APPROVED Entry 行未覆盖
  - **[ACCEPTED] World Model Update** `feature.world-model-update` (strong)
    - gap: 四项后续工作挂起
  - **[PARTIAL] 定稿确认 Gate** `feature.writing.confirmation-gate` (medium)
    - gap: Desktop “定稿确认·未接入”
  - **[ACCEPTED] 批评与修订** `feature.writing.critique-revision` (strong)
  - **[ACCEPTED] 正文生成/Draft 持久化** `feature.writing.draft` (strong)
  - **[PARTIAL] 知识更新写入** `feature.writing.knowledge-update` (medium)
    - gap: Desktop 菜单标注“知识更新·未接入”
  - **[ACCEPTED] 章节规划** `feature.writing.planning` (strong)
  - **[ACCEPTED] 故事世界上下文** `feature.writing.story-world-context` (strong)
  - **[ACCEPTED] WorkingDraft→Change/Review** `feature.writing.working-draft-change` (strong)

- **[IN_PROGRESS] 开发阶段与质量** `phase` (medium)
  - gap: v3.1 Phase 4B.1 与 README 旧 P13 口径冲突
  - **[DECISION_REQUIRED] 当前阶段口径（冲突登记）** `phase.current` (strong)
    - gap: README 仍写 Current Phase=P13；实际工作树在 v3.1 Phase 4B.1/4C
    - conflict: doc_says_current_phase_p13_code_worktree_phase4b1 — README.md:147
  - **[IMPLEMENTED_PENDING_ACCEPTANCE] Legacy P14-P20 — Idea/Author/Productization** `phase.legacy.p14-p20` (medium)
    - gap: README 标注 PLANNED，代码已存在；UI 写路径未完整
    - conflict: doc_says_planned_code_implemented — README.md:140-143
  - **[NOT_STARTED] Legacy P21 — Long-form Auto Creation** `phase.legacy.p21` (strong)
    - gap: 规划存在，无实现证据
  - **[ACCEPTED] Legacy P4-P13 — 单章闭环/LCL** `phase.legacy.p4-p13` (strong)
    - gap: 部分 UI 写路径未接线；Document/roadmap 口径滞后
  - **[ACCEPTED] v3.1 Phase 0 — Preflight/Baseline** `phase.v31.0` (medium)
    - gap: Phase0 事后追认有条件
  - **[ACCEPTED] v3.1 Phase 1 — Change Layer** `phase.v31.1` (strong)
  - **[PARTIAL] v3.1 Phase 10 — Product UI** `phase.v31.10` (medium)
    - gap: 信息架构未裁决；部分写路径未接线
  - **[ACCEPTED] v3.1 Phase 2 — World Model** `phase.v31.2` (strong)
  - **[ACCEPTED] v3.1 Phase 3 — World Model Update** `phase.v31.3` (strong)
    - gap: 四项后续工作挂起
  - **[ACCEPTED] v3.1 Phase 4A — Context Contract Freeze** `phase.v31.4a` (medium)
    - gap: 只冻结契约；AC-08/AC-30A/B 待实现验证
  - **[IN_PROGRESS] v3.1 Phase 4B.1 — ContextPack 统一入口** `phase.v31.4b1` (strong)
    - gap: A 真实 Pack 未解决；AC-30A/30B 证据不足；未提交
    - conflict: worktree_uncommitted_acceptance_pending — Phase 4B.1 report
  - **[DECISION_REQUIRED] v3.1 Phase 4C — Acceptance Criteria** `phase.v31.4c` (strong)
    - gap: A 待决策；AC-30A/30B 未验收；4B.1 未通过
  - **[PARTIAL] v3.1 Phase 5 — Novel Tool** `phase.v31.5` (medium)
    - gap: 自主 Tool 调用不是本阶段目标
  - **[PARTIAL] v3.1 Phase 6 — Novel Skill** `phase.v31.6` (medium)
    - gap: 核心 Skill 执行绑定不完整
  - **[PARTIAL] v3.1 Phase 7 — NL + Novel Agent** `phase.v31.7` (medium)
    - gap: 自主决策点/Context 快照缺口
  - **[PARTIAL] v3.1 Phase 8 — Complete Vertical Slice** `phase.v31.8` (medium)
    - gap: E2E 证据少；Completion Gate 未记录
  - **[UNKNOWN] v3.1 Phase 9 — DSH Runtime Adapter** `phase.v31.9` (weak)
    - gap: DSH 不在 settings；陈旧 XML；无产品动机

