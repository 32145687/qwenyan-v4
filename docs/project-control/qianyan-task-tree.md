# Qianyan 任务树（Living Task Tree）

> 唯一任务状态源。最后更新：2026-10-27。
> 本文件由规划层维护；执行层只读，不得修改。
> 状态词：`DONE` / `PENDING_GUI` / `PARTIAL` / `BLOCKED` / `NOT_STARTED` / `DEFERRED` / `PAUSED`。
> `DONE` = 实现 + 自动化验收 + 适用时真实生产 caller；GUI / 端到端未验另标 `PENDING_GUI`。
> 证据必须可定位：文档、测试名、`路径:行号`、commit。
> 更新规则：执行层回报后，规划层先对账；只有审查通过才改状态，并在“更新日志”追加一行。执行层自报不算。

## 0. 当前焦点

- 当前单元：**G1 提交执行（T-G1-C）** — 4/6 已提交，批次 4/5 待续（`5466f98` gate 瑕疵已裁接受）
- 阻塞：无；批次 4/5 需严格 gate（`&&` / `|| exit 1`）后继续
- 已完成：2A（D-20）；2B（D-21/D-23/D-24）；2C（D-25/D-26/D-27）；Gate（D-29/D-30/D-31 + GATE_PASS_WITH_LIMITATIONS）；G1 拆分（T-G1）+ sessionId 修复（T-G1-S）+ 提交 0–3（D-32）
- 并行决策：V1–V3（词库）、W1（工作树）
- 仓库快照：HEAD `5466f98`，本地领先 `5b047b0` **6 commits**，未 push；工作区 **9 porcelain 条目 / 15 文件**（批次 4/5 未提交）

## 1. 阶段总览

| Phase | 状态 | 说明 |
|---|---|---|
| 0 | DONE（有条件追认） | 0-6 已失效，见 `qianyan-phase0-baseline-register.md` |
| 1 | DONE（READY_WITH_LIMITATIONS） | Change/Commit/Canonical 生产链；legacy 限制保留 |
| 2 | DONE | World Model 模型/仓储/读取 |
| 3 | DONE | Update 管道；U1/U2/U5 后续项保留 |
| 4 | DONE（Gate） | ContextPack；AC-15 条件性不适用；词库契约另裁 |
| 5 | DONE | Tool Runtime 五件套 |
| 6 | DONE（GATE_PASS_WITH_LIMITATIONS） | 第一单元 + 2A + 2B + 2C DONE；Gate 审计 → D-29/30/31 → 重 Gate 通过；限制：O-23/24/25/29(2)/30/31/32/33 |
| 7 | PARTIAL | NovelAgent 七相位；决策点行为测试缺 |
| 8 | NOT_STARTED | 17 段完整纵切 |
| 9 | DEFERRED | DSH；R7 未裁 |
| 10 | PARTIAL | Desktop 有实现；UI 暂停；GUI G1–G8 未验 |

## 2. 已完成（DONE / PENDING_GUI）

| ID | 任务 | 状态 | 证据 |
|---|---|---|---|
| D-01 | Change / WorkingDraft / Diff / Gate / Commit / Canonical 链路 | DONE | `project-p1-canonical-rulings`；`AuthorSaveCanonicalTest`、`CommitRevertTest`、`CommitHistory` |
| D-02 | World Model 最小可生产（模型 / 仓储 / 读取） | DONE | schema v27/v28；`StoryWorldContextResolver`；Phase 2 记录 |
| D-03 | World Model Update 管道 | DONE | Phase 3 收口记录；30 个新增测试；U1/U2/U5 保留 |
| D-04 | 单 TXT 导入 → 章节识别 → Canonical 初始化 | DONE | `TxtCanonicalInitializationTest`；F1–F4 |
| D-05 | 作者手改 / Revert / CommitHistory 格式契约 | DONE | `AuthorSaveCanonicalTest`；Format Contract；`RevertLegacyFormatTest` |
| D-06 | CanonicalRead（R3） | DONE | `CanonicalReadUseCases`；Reader/History 读 Canonical |
| D-07 | ContextPack / Engine / Sources / Selector / Budget | DONE（Gate） | Phase 4 Gate；AC-03/06/07/08/10/16/23/30/35；AC-15 条件性不适用 |
| D-08 | A2 ContextPackStore + Inspector 四状态 | DONE（自动化） | `ContextPackStoreTest`；`WorkspaceInspectorTaskContextA2Test`；GUI 未点 |
| D-09 | Tool Runtime 五件套 + 权限/审计 | DONE | `ToolExecutionTest`；`ProductToolServiceTest`；`ToolBypassGuardTest` |
| D-10 | Phase 6 第一单元：Skill 字段生产读取 | DONE | `SkillContractConsumptionTest`；`SkillRegistryTest`；`agent/SkillExecutionGuard.kt` |
| D-11 | Durable Workflow + ContinuationReference + 重启恢复 | DONE（结构性） | `WorkflowContinuationE2ETest` E1–E4；语义承接见 2A |
| D-12 | P0-2.1 受控改写 | DONE | rewrite→WorkingDraft+Gate；reject 生产入口；Commit Gate 绑定 |
| D-13 | P0-3B 审批统一 D1–D7 | DONE（自动化） | 正式 Gate；D8/D9 仍 IN_PROGRESS；GUI G1–G8 未验 |
| D-14 | Desktop Shell + 主工作台 | DONE（自动化）→ PENDING_GUI | 三栏、故事/资料/阅读/历史/写作/Agent 页面；核心按钮已接 |
| D-15 | NovelAgent 七相位 + 自然语言任务入口 + 审批桥接 | DONE（自动化）→ PENDING_GUI | `NovelAgent`；P0-3B 桥接；Phase 7 Gate 未过 |
| D-16 | 词库候选分析 + 确认/拒绝/编辑（后端） | DONE（后端） | `VocabularyUseCases`；`VocabularyConfirmationTest`；UI 创建/部分写路径未接线 |
| D-17 | 词库 Context Source + 预算子集 | DONE（AC-30A/B） | `VocabularyDatasetEquivalenceA2Test`；`VocabularyPromptIntegrationTest` |
| D-18 | CommitHistory 格式契约 | DONE | 已随 `5b047b0`；`project-commit-format-contract` |
| D-19 | World Model 读侧进入 prompt 的既有路径 | DONE | `PlannerAgent` characters；`get_world_context`；Phase 2 Unit 2 |
| D-20 | 2A LongContinuation Canonical 上下文桥接 | DONE（L2） | `WorkflowContinuationE2ETest` 7/7；`ContinuationReferenceTest` 13/13；`ContinuationExcerptTest` 4/4；`application` 1022/0；`desktop` 46/0；`./gradlew test` 1485/0（执行报告）；`WorkflowOrchestrator.kt` / `ContinuationResolver.kt` / `WritingExecutionUseCase.kt` / `PlannerAgent.kt` / `WriterAgent.kt` 已对账 |
| D-21 | 2B CharacterConsistency 契约冻结 | DONE（契约层） | 2B 契约报告 + 规划层对账；B1–B5、7 项细节、O-26 三条已裁；无代码改动 |
| D-22 | 2B-1 编译闭合路线 | DONE（裁决） | 采"补齐 Android exhaustive mapper"而非撤回新 `ApplicationError` 子类型；两文件各加 2 分支、**不加 `else`**；文案不带 `detail`；勘误 findings 上限仍为 50 |
| D-23 | 2B-3 gap 信号 + token 预算 | DONE（裁决） | D-23a：`Basis.priorCanonicalGapChapterIds` 落 Result，Validator 从 `input.coverage` 拷贝并校验不重叠/不重复；missingInputs 语义不变。D-23b：token 预算不进 T-2B-3（与 DraftSource"原文不截断"一致，延后） |
| D-24 | NovelAgent 构造改 `internal` | DONE（裁决） | 注入 2B-2 的 internal 类型（CharacterConsistencyUseCase/Agent）要求构造非 public；`class NovelAgent internal constructor`（类仍 public），桌面/Android 经 `ApplicationContainer.novelAgent` 用公开方法，零破坏。禁止为恢复 public 构造而把 2B-2 类型改 public |
| D-25 | `requiredContext` 实数据语义 | DONE（裁决） | **实数据缺失不是 guard 前置拒绝，而是 Result 可见**（missingInputs/coverage/status）。保留"类型必须被引擎配置"；不引入 mandatory/optional 元数据、不新增第三种 guard 语义。理由：新章无 Draft、无前章 Canonical 都是合法产品状态；2B 已用 INSUFFICIENT_EVIDENCE + missingInputs 冻结（D-21/D-23a），guard 拒绝会回退行为。T-2C-4 本单元只核验 + 登记（O-30） |
| D-26 | Planner 工具派生方式 | DONE（裁决） | Planner 运行时注册 + AgentContract.allowedTools **完全等于被选中 Skill 的 allowedTools**（不做 PLANNER_TOOLS 交集、不保留隐藏最小集）；AgentContract 每次运行动态构建；"无桥 / 无 Activity 锚 → 空注册 + 空 allowedTools"降级保留 |
| D-27 | observed 校验粒度 | DONE（裁决） | 动态校验落在**工具级**：本次实际调用工具集合 ⊆ Skill.allowedTools（拒绝时复用既有错误，不新增子类型）。action 级（observed ⊆ expectedActions）需 tool→AgentActionKind 映射，映射不存在则登记 O-31，不在本单元发明 |
| D-29 | CharacterConsistency 是无工具 Agent | DONE（裁决） | 修订 D-21 的"Tool 经 AgentToolBridge"：世界上下文经 `StoryWorldContextResolver` 快照注入 prompt（与 `get_world_context` 同源，O-26 已闭环），Agent 不调用任何工具；`CoreSkills.CHARACTER_CONSISTENCY.allowedTools` 改为空。补 UseCase 级"无当前稿"测试（F4） |
| D-30 | LongContinuation 是工作流能力，非 Skill | DONE（裁决） | 撤销 D-28 的"LongContinuation=WRITING Skill"映射：2A 链（`WorkflowOrchestrator.continueToNextWorkflow` + `ContinuationResolver` + canonicalRead）是独立工作流能力，不挂 CoreSkills、不新建 Skill。Gate 判据 = 2A 链自身 |
| D-31 | WRITING CONTINUE 的 R3 偏差属 Phase 7 | DONE（裁决） | `NovelAgent.latestCanonicalDraftId`（:1190）实调 `get_latest_draft`→`writer.latestDraft`，非 canonical——命名陷阱 + 既有 R3 偏差（早于 Phase 6）。登记 O-32，Phase 7（NovelAgent base-draft 对齐）处理，不算 Phase 6 Gate 阻断。Desktop 无 `continueToNextChapter` 接线登记 O-33 |
| D-32 | 提交 3 的 whitespace gate 瑕疵 | DONE（裁决） | **接受 `5466f98`**：唯一问题是历史审计快照 `qianyan-project-worktree.md` 末尾空行（EOF blank line），而快照应**按原样归档、不改写**（符合"历史基线只加指针不改写"）；因此不对快照类文件套用 whitespace gate。**不 reset、不 amend、不追加修复提交**。流程错误（`git diff --cached --check` 与 `commit` 用 `;` 串联，未把 exit 0 作为前置）单独记录；批次 4/5 必须用 `&&` 串联（或用 `set -e` / `|| exit 1`），gate 失败即停 |

## 3. 未完成 / 部分完成 / 阻塞

| ID | 任务 | 状态 | 缺口 / 阻塞 |
|---|---|---|---|
| O-02 | 2B CharacterConsistency 实现 | **DONE** | 契约冻结（D-21）+ 纯逻辑 + Agent/UseCase/DI + Skill 路由 + NovelAgent 接线 + E2E 全绿（T-2B-1/2/3 DONE(L2)，1568/0 + 桌面 46/0）；未用 `ANALYSIS_CRITIQUE` 冒充 |
| O-29 | gap 信号已落 / token 预算延后 | **PARTIAL** | (1) 已由 D-23a 解决：`Basis.priorCanonicalGapChapterIds` 落 Result（`Models.kt:127`、`Validator.kt:651` 拷贝 + :107-112 去重/重叠校验），E2E V-6 断言。**(2) 仍开放**：当前稿/前章正文全量渲染、无 token 预算（与 DraftSource"原文不截断"一致但长文有溢出风险）；延后，不阻塞 |
| O-30 | 其余 Skill 的"缺失输入"可见性 | **OPEN（3 项登记）** | D-25 已定语义为 Result 可见；CharacterConsistency 满足。**O-30-1** STORY_PLANNING：结果/Artifact 无 coverage，声明来源（含 STORY_FOUNDATION）缺失不可见。**O-30-2** WRITING：新章无 DRAFT 合法，但 foundation/vocab 缺失静默。**O-30-3** REWRITE_REVISION / ANALYSIS_CRITIQUE：非 Pack Skill，仅 DRAFT 缺失有 `BASE_DRAFT_NOT_FOUND` 兜底，其余声明来源不可见。均只登记，未改行为 |
| O-31 | tool→AgentActionKind 映射 | **OPEN** | D-27 的 action 级 observed ⊆ expectedActions 需工具→动作类别映射；2C 只做工具级，未发明 |
| O-32 | WRITING CONTINUE 用 latest draft 非 canonical | **OPEN（Phase 7）** | `NovelAgent.latestCanonicalDraftId`（:1190-1207）实调 `get_latest_draft`→`writer.latestDraft`（`ProductTools.kt:204-209`），违反 R3"Agent Context=Canonical 前文"；命名陷阱。早于 Phase 6，Phase 7 NovelAgent base-draft 对齐时修 |
| O-33 | Desktop 无 continueToNextChapter 接线 | **OPEN（UI 恢复时）** | 2A 续篇唯一生产 caller 是 Android `ChapterWritingViewModel.kt:87`（Android 暂停）；Desktop `WritePage.kt` 的"下一章"仅导航、`continueWriting` 是同章推进（非续篇）。UI 线恢复时补 |
| O-03 | 2C Skill 契约收口 | **DONE** | T-2C-1/2/3 实施 + T-2C-4 核验，全量 1575/0 + 桌面 46/0；缺口转 O-30/O-31 |
| O-04 | 词库别名收敛 / VocabularyRule / Critic 漂移 | **NOT_STARTED** | prompt 只渲染 canonical + replacement，aliases 被丢弃；Rule 无消费者；无 Critic 检查 |
| O-05 | 多 TXT 全书章节顺序 | **NOT_STARTED** | R1–R5 已裁，功能未实现 |
| O-06 | World Model 冲突 → Human Gate | **PARTIAL** | CONFLICT 只写候选队列；Gate 挂载点未接 |
| O-07 | 伏笔生命周期 / Reveal 的生产调用与 UI | **PARTIAL** | `ForeshadowLifecycle` 无 UI/生产 caller |
| O-08 | 知识更新 Desktop 入口 | **PARTIAL** | 后端 KU 链路存在；Desktop 入口按当前 UI 复核，历史台账 PARTIAL |
| O-09 | 阅读位置写入的 L3 验收 | **PARTIAL** | 按 UI 记录已接；功能台账仍 PARTIAL，未按 L3 验收 |
| O-10 | GUI G1–G8 | **PENDING_GUI** | 未执行真实点击/输入；不得标记 PASS |
| O-11 | Phase 7 决策点行为测试 | **PARTIAL** | 缺 `Result X → A / Result Y → B` 行为测试 |
| O-12 | Phase 8 17 段完整纵切 | **NOT_STARTED** | 无自然语言 → Canonical 的完整 E2E |
| O-13 | DSH Runtime Adapter | **DEFERRED** | R7 产品动机未裁 |
| O-14 | Android | **PAUSED** | 产品策略暂停 |
| O-15 | Story Foundation / Author Intelligence 写路径 | **PARTIAL** | 模型/部分写路径有；Desktop 多为接入点 |
| O-16 | `commitApprovedDraft` 遗留入口 | **OPEN** | 无调用方/无测试，缺 Gate 绑定；待裁决删除或补齐 |
| O-17 | 词库 V1–V3 契约裁决 | **OPEN** | 三套模型未统一；详见 `project-vocabulary-contract-drift` |
| O-18 | 工作树 W1 | **OPEN** | 四个工作树冻结在 2026-10-20；未归档、未清理 |
| O-19 | 提交冻结 G1 | **OPEN** | 130 项未提交；DONE 不在任何 commit 里 |
| O-20 | P0-3B D8 / D9 | **PARTIAL** | D8 `allowUnboundGate` 兼容开关仍在；D9 Agent 层清理故障注入缺 |
| O-21 | 词库候选 Desktop 写路径 | **PARTIAL** | 后端 confirm/reject/edit 有；Desktop 创建/部分写路径未接线 |
| O-22 | ContextPack 功能行收口 | **PARTIAL** | Phase 4 Gate DONE；`feature.context-pack` 台账仍 IN_PROGRESS（AC-03/AC-15） |
| O-23 | 非 CONFIRMED Canonical carrier 的续篇 | **DEFERRED** | 作者手改/revert 产生的 FINAL/WRITTEN carrier 会被 CONFIRMED 守卫拒绝；L0=A 已知限制，未放宽 |
| O-24 | 长文 prompt 级摘录 E2E | **DEFERRED** | T5 覆盖共享截断；T1 用短正文覆盖 prompt；未用 1200/4000 长文做 prompt 级 E2E |
| O-25 | `executeWriting` 续篇查询的失败路径 | **PARTIAL** | `getContinuationByTargetChapter` 在 try 外；DB 异常不会 markAttemptFailed。低风险，后续收口 |
| O-26 | 2B 契约补强 | **DONE** | 三条全闭环：(1) 同快照——`CharacterConsistencyUseCase` 与 `get_world_context`（`ProductTools.kt:303` → `WorldModelReadUseCases`）都经同一 `StoryWorldContextResolver`（`ApplicationContainer.kt:587-588`），UseCase 单次 resolve 同实例喂 Agent+Validator；(2) field map 已具体化（`Validator.kt:438-665`）；(3) priorCanonical 按 `listByNovel(novelId, variantId)` 过滤（`UseCase.kt:160`） |
| O-27 | Android exhaustive mapper 是编译期守卫 | **DONE（决策，非债务）** | `AnalysisViewModel.kt:142`、`NovelListViewModel.kt:115` 两处各 41 分支且无 `else`，规划层 `comm` 比对：缺失集合恰为 2 个新子类型。禁止为省事加 `else`（见 D-22）；其余 6 处 `when(error)` 均有 `else`，不属守卫 |
| O-28 | 测试证据时效 | **DONE** | T-2B-1c 已 fresh 复核（`--rerun-tasks`）：全量 `./gradlew test` 1552/0 + 桌面 46/0，per-module XML 时间戳均为 2026-10-27T10:34–10:36Z；旧 118/0 快照弃用 |

## 4. 未来任务（按执行顺序）

### 4.1 Phase 6 / 2A — LongContinuation（已完成，见 D-20）

- [x] `T-2A-0` L0 裁决：A（按执行结果，2026-10-27）
- [x] `T-2A-1` 创建侧：`continueToNextWorkflow` 改用 `canonicalRead.committedDraft`
- [x] `T-2A-2` 解析侧：`ContinuationResolver` canonical 校验（A 路线）
- [x] `T-2A-3` Writing 透传 continuation（Orchestrator → WritingExecutionUseCase → assemble）
- [x] `T-2A-4` Planner / Writer 渲染前章 Canonical 摘录（1200 / 4000 code point）
- [x] `T-2A-5` 测试：T1–T7；E1–E4 回归；夹具升级（A 路线）
- [x] `T-2A-6` 执行报告 → 规划层审查 → 更新本任务树（D-20）

### 4.2 Phase 6 / 2B — CharacterConsistency

- [x] `T-2B-0` 契约冻结：D-21（B1–B5 + 7 项细节 + O-26 三条，2026-10-27）
  已冻结：B1 新 Agent + 独立 Result；B2 应用层 CanonicalRead 注入；B3 keyword + preferredSkillId；B4 编译 DTO + 运行时 Validator；B5 现有 NovelAgent Agent 任务入口；Memory 证据 layer + sourceIndex；当前 Draft 只认 ContextPack DRAFT；**findings 上限 50，超出截断并置 `truncated=true`**（勘误：上一轮提示词中"不限制 findings 为 50 条"是笔误，实现按 50 上限正确）；结果 summary + `NovelAgentResult.characterConsistency`；Tool 经 AgentToolBridge；Plan 可选 null；O-26（Validator 同快照、evidence field map、priorCanonical 按 variant 过滤）。
- [x] `T-2B-1` 契约模型 + Parser + Validator（纯逻辑，无 Agent / caller）— **DONE(L2)**
  代码与测试审查 PASS（规划层实测：`CharacterConsistencyModels.kt` 147 行、`CharacterConsistencyInput.kt` 49 行、`CharacterConsistencyParser.kt` 144 行、`CharacterConsistencyValidator.kt` 725 行；Parser/Validator 零 Repository/UseCase/Container 依赖 ⇒ O-26"不得二次重读世界状态"满足；`MAX_FINDINGS=50` + `truncated` 由 Validator 计算；17 个稳定错误码；evidence 偏移按 code point）。测试 `Models 4 / Parser 11 / Validator 50`；`T-2B-1c` 后全量 `./gradlew test` 1552/0 + 桌面 46/0。
- [x] `T-2B-1c` Android exhaustive mapper 编译闭合（裁决 D-22）— **DONE(L2)**
  3 文件 +19/−0（`AnalysisViewModel.kt:184-185`、`NovelListViewModel.kt:158-159`、`NovelListViewModelTest.kt:331-344`）；无 `else`、`messageFor` 仍 private、文案与 N3 逐字一致；`NovelListViewModelTest` 13/13（+1 V-1）；全量 fresh 复核 BUILD SUCCESSFUL，staged=0、HEAD `be1b755` 未变。
- [x] `T-2B-2` 只读 Agent + UseCase + DI（无 Skill 路由 / 无 caller）— **DONE(L2)**
  新增 `CharacterConsistencyAgent.kt`（223 行，只依赖 LLMGateway，`agentId=story-character-consistency`，渲染当前稿/前章/plan/全部 world evidence，严格 Parser）、`CharacterConsistencyUseCase.kt`（215 行：ContextPack DRAFT only + CanonicalRead·listByNovel 前 1–3 章连续链 + 单次 `StoryWorldContextResolver.resolve` + missingInputs）；`ApplicationContainer.kt` +3 import +3 getter。规划层实测：2B-1 的 7 文件 mtime 停在 15:51–16:01（未动）；DI 删除行为基线既有；无新 `ApplicationError` 子类型。测试 V-1..V-7 + Agent 3/3，定向 71/0；全量 1562/0 + 桌面 46/0（时间戳 11:14–11:16Z，晚于源码 11:13）。
- [x] `T-2B-3` Skill 路由 + NovelAgent 接线 + 真实 caller E2E + D-23a — **DONE(L2)**
  `CoreSkills.CHARACTER_CONSISTENCY`（skillId=`skill.character-consistency`，purposes=[ANALYZE,CUSTOM]，requiredContext=[PROJECT,NOVEL,CHAPTER,DRAFT]，allowedTools=[get_world_context]，expectedActions=[READ]，bindings=[story-character-consistency]）；`NovelAgent` keyword 路由（:815-819）+ skillConsumesContext（:858）+ dispatch（:896-897）+ expectedExecutionAgent（:912）+ `checkCharacterConsistency`（:1002-1025）+ `NovelAgentResult.characterConsistency`（:1313）；`Basis.priorCanonicalGapChapterIds`（Models:127 / Validator:651）。测试：Routing 3 + E2E 3（V-1..V-7），全量 1568/0 + 桌面 46/0（13:34–13:36Z > 源码 13:32）。规划层实测 NovelAgent `internal constructor` 无跨模块破坏（桌面 `Common.kt:346/367/377/385` 经容器用公开方法，绿）。

### 4.3 Phase 6 / 2C — Skill 契约收口

- [x] `T-2C-0` 只读定位 + 冲突登记 — **DONE（2026-10-27）**
  实测：`PlannerAgent.kt:194/209-219` PLANNER_TOOLS 硬编码且 `allowedTools = PLANNER_TOOLS`；NovelAgent 两处 `planner.plan(context, emptyList(), activityId)`（第二参是 `List<DecisionPolicy>`，**不是**工具表）→ 无 Skill 透传；`SkillExecutionGuard.kt:28-52` 只校验四类声明（工具已注册 / requiredContext 类型已配置 / bindings / expectedActions 对 ActionPolicy），无 ContextPack 实数据、无 observed；`ToolCallLogUseCases` 已记录 toolName（Planner 调用也留痕），但 NovelAgent 从不消费；`selectSkill` 实为 `:802-828`（非 :786）且不调用 `expectedExecutionAgent`（`:907-914`，KNOWLEDGE_WORLD_MODEL → null），故无绑定 Skill 可被选中后在 dispatch 才失败；`ContextPack` 无 `selectedSourceKinds`，可由 `items.map{source}` 派生。
  **冲突（HARD STOP 依据）**：T-2C-4 按字面「guard 校验实数据」会 (a) 拒绝合法新章写作（`NovelAgentRoutingTest.kt:228` 第二章无 DRAFT item 仍应 WAITING_HUMAN）；(b) 与 2B 冻结语义冲突（`CharacterConsistencyNovelAgentE2ETest` V6 无前章 Canonical 期望 COMPLETED + INSUFFICIENT_EVIDENCE，而非执行前拒绝）。→ 由 D-25 重定义。
- [x] `T-2C-1` Planner 的 `allowedTools` 从 Skill 派生（D-26）— **DONE(L2)**
  `PlannerAgent.plan(context, policies, activityId, allowedTools = PLANNER_TOOLS)`；`toolRuntime` 与动态 `AgentContract` 均用该入参，静态 `PLANNER_AGENT_WITH_TOOLS` 已删；`NovelAgent` 写作/规划两处传 `skill.allowedTools`（无交集）；无桥/无 Activity 锚空降级保留。V-1 3/3。
- [x] `T-2C-2` 观测工具集 ⊆ Skill.allowedTools（D-27）— **DONE(L2)**
  `NovelAgent` 注入 `ToolCallLogUseCases`（:126）；EXECUTE 后按 activityId 读 observed `toolName`（:940），越权 → `TOOL_NOT_ALLOWED`（:944）；零调用通过；未发明 tool→action 映射（O-31）。V-2 3/3。
- [x] `T-2C-3` 不可执行 / 无有效绑定的 Skill 在选择阶段过滤 — **DONE(L2)**
  自动候选剔除 `expectedExecutionAgent == null`（:810）；全不可执行 / preferred 不可执行 → 解释性 `SKILL_NOT_EXECUTABLE`（:813-815、:825-828、`notExecutableReason` :849-853）；dispatch `else` 兜底保留。V-3 + `NovelAgentRoutingTest` 适配。
- [x] `T-2C-4` `requiredContext` 实数据语义 = Result 可见缺失（D-25）— **DONE（核验）**
  CharacterConsistency 已满足（missingInputs + coverage + basis.gap，E2E V6）；STORY_PLANNING / WRITING / REWRITE_REVISION / ANALYSIS_CRITIQUE 缺口登记 O-30-1..3；KNOWLEDGE_WORLD_MODEL 不可执行 N/A。零代码。
  测试：`SkillContractClosureTest` 7/7（261 行）；全量 1575/0（application 1106）+ 桌面 46/0，XML 14:55–14:56Z > 源码 14:55:01Z；porcelain 149 → 152，staged=0。

### 4.4 Phase 6 Gate

- [x] `T-6G-1` 两个 MVP 能力的证据包 — **DONE（审计 GATE_FAIL，见 D-29/D-30/D-31 修正）**
  执行层只读审计发现两条契约-实现偏差：(F1) 2A 续篇链 ≠ WRITING Skill 链，且 WRITING CONTINUE 用 `get_latest_draft` 非 canonical；(F2) CharacterConsistency 声明 `get_world_context` 但从未调用（Agent 空 ToolRegistry，UseCase 直读 `StoryWorldContextResolver`）；(F3) Desktop 无 continueToNextChapter；(F4) CharacterConsistency 无 UseCase 级"无当前稿"测试。已由 D-29/D-30/D-31 裁决。
- [x] `T-6G-2` Phase 6 Gate 逐项验收 — **DONE：GATE_PASS_WITH_LIMITATIONS**
  D-29 落地后重 Gate：CharacterConsistency 八维 = PASS×7 + H PARTIAL（O-29(2)/O-31）；LongContinuation（工作流能力，D-30）八维 = A/C/E/F/G PASS、B PARTIAL（`continueToNextChapter` 仅 Android caller → O-33）、D N/A（非 Skill 无工具契约）、H PARTIAL（O-23/24/25/29(2)/30-2/32/33）。D-29 证据：`CoreSkills.kt` CC `allowedTools = emptyList()`；F4 测试断言 `INSUFFICIENT_EVIDENCE + missingInputs=[CURRENT_DRAFT]`、prompt 不含 DB 当前稿（无回退）、四类表内容相等 + `total_changes()` 不变。全量 1576/0 + 桌面 46/0（XML 15:44–15:47Z > 源码 15:43:25Z）。

### 4.5 Phase 7 / 8

- [ ] `T-7-1` 决策点行为测试：Result X → A，Result Y → B
- [ ] `T-7-2` P0-3B GUI G1–G8 真实验收
- [ ] `T-8-1` 17 段完整纵切 E2E
- [ ] `T-8-2` Phase 8 验收

### 4.6 并行决策 / 治理

- [ ] `T-V1` 词库归属：承认现行域并修订 §11A / 迁移
- [ ] `T-V2` 作用域映射：NOVEL / VARIANT 与 BOOK / SERIES
- [ ] `T-V3` 词库 MVP 范围：先 prompt 别名收敛，还是连 Rule + Critic
- [ ] `T-W1` 四个工作树：冻结归档 / 清理（先出 manifest + patch）
- [x] `T-G1` 提交拆分清单（只读，不执行）— **DONE（2026-10-28）**
  盘点：152 porcelain → 展开 **172 文件**（106 tracked 变更 + 66 新文件）；无 .sq/.sqm、无密钥、无 build/IDE 产物、无 >1MiB 新文件。
  拆分：提交 0（纯视觉 4 文件，可选）→ **提交 1（141 文件，必须单批）** → 提交 2（Desktop U 5 文件，可选）→ 提交 3（`docs/audit` 7 文件，建议）→ 提交 4（docs 8 文件）→ 提交 5（`docs/project-control` 7 文件，HOLD 已解除）。
  必须单批的强耦合：`ApplicationError` ↔ Android 两处穷尽 mapper；`NovelAgentOutcome.CANDIDATE_UNBRIDGED` ↔ `Common.kt`；Gateway 签名 ↔ Android fakes；legacy 删除 ↔ TaskRunner/WriterUseCases/CommitUseCases + 5 测试；共享 hub 单文件混合多阶段。
- [x] `T-G1-S` 提交前修 Desktop 候选操作 sessionId 缺口 — **DONE（2026-10-28）**
  `Common.kt:347`：`lastAgentRequest = result.sessionId?.let { request.copy(sessionId = it) } ?: request`（null 不编造）；`DesktopAgentApprovalUiTest` 新增行为级测试（`DesktopOfflineLlmGateway` + 真实 NovelAgent + 临时 DB，逐一断言 bridgeCandidate/refreshCandidate/reject 不再 `SESSION_REQUIRED`）+ 源码行断言。桌面 47/0、root 1576/0，XML 00:24–00:26Z > 源码 00:24:41Z；porcelain 仍 152、staged=0。
  注：报告称"本轮未新增代码改动、仅复核"，与同报告的"修复前 2 failed → 修复后 5/5、旧赋值断言已同步"自相矛盾（表述问题，不影响结论）；另源码文本断言较脆，属辅助手段。
- [ ] `T-G1-C` 执行提交 — **PARTIAL：4/6 已提交，批次 4/5 待续**
  已提交：`c2c3681`（4 文件，提交 0）、`0530e2b`（141 文件，提交 1）、`77c5718`（5 文件，提交 2）、`5466f98`（7 文件，提交 3）。本地 `0 6`（origin…HEAD），未 push。
  **过程错误**：批次 3 用 `;` 串联 `git diff --cached --check` 与 `commit`，check 返回 exit 2（`qianyan-project-worktree.md:181 new blank line at EOF`）仍提交。已 HARD STOP，未 amend/reset。
  待续：批次 4（8 文件：README + 7 docs/planning）、批次 5（7 文件：docs/project-control）。剩余 9 porcelain 条目 = 15 文件。
- [ ] `T-LEGACY` `commitApprovedDraft`：删除或补齐 Gate 绑定

## 5. 更新日志

| 日期 | 变更 | 依据 |
|---|---|---|
| 2026-10-27 | 初始创建；按 v3.1、控制层、审计台账与 2A 审查登记；2A 标 BLOCKED(L0) | `v3.1-roadmap.md`、`v3.1-feature-status.md`、`qianyan-feature-roadmap.md`、本次 2A 审查 |
| 2026-10-27 | 复核任务树与仓库一致；补 O-22；审查结论推荐 L0=A（T-2A-0 仍待用户确认） | 本次复核；`v3.1-feature-status.md:41` |
| 2026-10-27 | 2A 审查通过：`T-2A-0..6` → DONE；O-01 → D-20；新增 O-23/O-24/O-25 已知限制/跟进 | `WorkflowContinuationE2ETest` 7/7；`ContinuationReferenceTest` 13/13；`ContinuationExcerptTest` 4/4；application 1022/0；desktop 46/0；full 1485/0（执行报告）；源码 hunk 已对账 |
| 2026-10-27 | 2B 契约草案对账通过；T-2B-0 仍 PARTIAL；新增 O-26（快照一致 + field map）；待 1–7 裁决 | 执行层只读报告；`Common.kt:320/346`、`NovelAgent.kt:786/832/1085`、`ContextSources.kt:245`、`StoryWorldContextResolver.kt:123-132` 已核 |
| 2026-10-27 | 2B 契约冻结：B1–B5 + 7 项细节 + O-26 三条；D-21；T-2B-0 → DONE；O-02 BLOCKED → PARTIAL | 2B 契约报告 + 规划层对账；`Common.kt` / `NovelAgent.kt` / `ContextSources.kt` / `StoryWorldContextResolver.kt` 已核 |
| 2026-10-27 | 2B 实施拆分为 2B-1 模型+Parser+Validator / 2B-2 Agent+UseCase+路由 / 2B-3 E2E+边界 | 规划层裁决；先锁纯逻辑再接线，避免一次改太多 |
| 2026-10-27 | 2B-1 审查 PASS（纯逻辑）但 T-2B-1 → PARTIAL/BLOCKED_ANDROID_COMPILE；新开 T-2B-1c；D-22 采"补 Android mapper、不加 `else`、文案不带 detail"；勘误 findings 上限仍 50；新增 O-27/O-28；工作区快照 135 → 141 entries | 规划层实测：7 个新文件存在（147/49/144/725/138/242/1385 行）；XML `Models 4 / Parser 11 / Validator 50`、`core/model 66`、`application 1083` 全 0 失败；Parser/Validator `grep Repository\|UseCase\|Container` = 空；`MAX_FINDINGS=50`（Validator:681）+ `truncated`（:60-72、Models:146）；17 错误码；`comm` 证明两 mapper 各缺 2 个新子类型、余 6 处 `when` 有 `else`；`schema/migration = none`、`staged_files=0`、无 commit |
| 2026-10-27 | T-2B-1c 审查通过：T-2B-1 → DONE(L2)、T-2B-1c → DONE(L2)；O-28 → DONE；工作区 141 → 144 entries | 规划层实测：3 文件 +19/−0（`AnalysisViewModel.kt:184-185`、`NovelListViewModel.kt:158-159` 各 +2，`NovelListViewModelTest.kt:331-344` +15）；两 mapper `grep else ->` = 0；`messageFor` 仍 private；`NovelListViewModelTest` XML 13/13；android debug+release 各 60、全量 1552/0、桌面 46/0（fresh 复核，时间戳 10:34–10:36Z）；staged=0、HEAD `be1b755` |
| 2026-10-27 | T-2B-2 审查通过：T-2B-2 → DONE(L2)；新增 O-29（部分 gap 语义 + token 预算，随 T-2B-3 收口）；工作区仍 144 entries（4 新文件在未跟踪目录） | 规划层实测：`Agent` 223 行（只依赖 LLMGateway，`agentId=story-character-consistency`，无写路径）；`UseCase` 215 行（:136 ContextPack DRAFT only、:160 `listByNovel(novelId,variantId)`、:171 `canonicalRead.committedDraft`、:69 单次 resolve、:170-186 连续链遇 gap 即停）；2B-1 的 7 文件 mtime 15:51–16:01 未动；DI 新增仅 3 import + 3 getter（删除为基线既有）；测试 V-1..V-7 + Agent 3；定向 71/0、application 1093、全量 1562/0、桌面 46/0（11:14–11:16Z > 源码 11:13） |
| 2026-10-27 | T-2B-3 审查通过：T-2B-3 → DONE(L2)、2B 整体收口（O-02 → DONE）；O-26 → DONE；O-29(1) 由 D-23a 解决、O-29(2) 仍开放；新增 D-23/D-24；工作区 144 → 149 entries | 规划层实测：`CoreSkills.CHARACTER_CONSISTENCY`（:205-226 + all() :234）；`NovelAgent` keyword :815-819 / skillConsumesContext :858 / dispatch :896-897 / expectedExecutionAgent :912 / checkCharacterConsistency :1002-1025 / result :1313 / RunState :1332；`NovelAgentResult.characterConsistency` :144；`Basis.priorCanonicalGapChapterIds` Models:127 + Validator:651（:107-112 去重/重叠校验）；`NovelAgent internal constructor` :119 无跨模块破坏（桌面 `Common.kt:346/367/377/385` 经容器调用，绿）；测试 Routing 3 + E2E 3；全量 1568/0 + 桌面 46/0（13:34–13:36Z > 源码 13:32）；`get_world_context`（ProductTools:303 → WorldModelReadUseCases）与 UseCase 同走 `StoryWorldContextResolver`（Container:587-588）⇒ O-26 同快照闭环 |
| 2026-10-27 | 2C 第 0 步审查通过（只读）：T-2C-0 → DONE；T-2C-4 语义冲突成立，裁 D-25 重定义；新增 D-26/D-27、O-30/O-31；T-2C-1..3 待实施；无代码改动，工作区仍 149 entries | 规划层复核执行层 6 项定位：`PlannerAgent.kt:194/209-219` 硬编码且 `plan(context, policies, activityId)` 第二参确为 `List<DecisionPolicy>`；NovelAgent 两处 `plan(context, emptyList(), activityId)`（约 :935/:973）；`selectSkill` 实为 :802-828、`expectedExecutionAgent` :907-914；`NovelAgentRoutingTest.kt:228` 新章无草稿用例存在；`CharacterConsistencyNovelAgentE2ETest` V6 无前章 Canonical 期望 COMPLETED + INSUFFICIENT_EVIDENCE；`CoreSkills.WRITING.requiredContext` 含 STORY_FOUNDATION/VOCABULARY ⇒ 全量实数据强制会误杀合法写作，冲突属实 |
| 2026-10-27 | 2C 实施审查通过：T-2C-1/2/3 → DONE(L2)、T-2C-4 → DONE（核验）、O-03 → DONE；O-30 落 3 项、O-31 保留；工作区 149 → 152 entries | 规划层实测：`PlannerAgent.plan(..., allowedTools = PLANNER_TOOLS)` + `toolRuntime`/动态 `contract` 双用该入参、静态 `PLANNER_AGENT_WITH_TOOLS` 已删；`NovelAgent` :126 注入 ToolCallLog、:810 过滤无绑定、:849-853 notExecutableReason、:940-944 observed ⊆ allowedTools、:952 expectedExecutionAgent；`SkillContractClosureTest` 261 行 7 测试；全量 1575/0（application 1106、android 60+60、core/model 66、storage 166）+ 桌面 46/0；XML 14:55–14:56Z > 源码 14:55:01Z；`AgentToolChainTest` 负向断言改"工具声明行"属修正假阳性（get_chapter 描述合法提及 get_latest_draft），非放宽；staged=0、HEAD `be1b755` |
| 2026-10-27 | Phase 6 Gate 审计（只读）审查通过：T-6G-1 → DONE（审计 GATE_FAIL）；裁 D-29（CC 无工具 Agent，修订 D-21）/ D-30（LongContinuation=工作流能力，撤销 D-28 映射）/ D-31（WRITING latest-draft 偏差属 Phase 7）；新增 O-32/O-33；T-6G-2 IN_PROGRESS | 规划层复核执行层四发现：(F1) 2A 链 ≠ WRITING Skill 链，`NovelAgent.latestCanonicalDraftId`(:1190)→`get_latest_draft`→`writer.latestDraft`(ProductTools:204) 非 canonical；(F2) CC 声明 `get_world_context` 但 Agent 空 ToolRegistry、UseCase 直读 `StoryWorldContextResolver`；(F3) `continueToNextChapter` 唯一生产 caller 是 Android `ChapterWritingViewModel:87`（暂停），Desktop `WritePage` 仅导航；(F4) CC 缺 UseCase 级无当前稿测试。均属实，GATE_FAIL 判定正确，不伪造通过 |
| 2026-10-27 | **Phase 6 收口**：T-6G-2 → DONE，Gate = GATE_PASS_WITH_LIMITATIONS；Phase 6 → DONE（PASS_WITH_LIMITATIONS）；下一单元建议 G1 | 规划层实测 D-29 落地：`CoreSkills.kt` CC `allowedTools = emptyList()`；F4 测试（UseCase 级、DB 仍有稿但 Pack 无 DRAFT）断言 `INSUFFICIENT_EVIDENCE + missingInputs=[CURRENT_DRAFT]`、prompt 不含 DB 当前稿（无回退）、drafts/history/working 内容全等、`total_changes()` 不变；`SkillRegistryBindingTest` 对 CC 断言空集、其余 5 个仍非空且工具必须已注册；全量 1576/0 + 桌面 46/0（XML 15:44–15:47Z > 源码 15:43:25Z）；porcelain 152、staged=0、HEAD `be1b755` |
| 2026-10-28 | G1 只读拆分审查通过：T-G1 → DONE（172 文件 / 6 批）；控制层三份文档由规划层对账（Phase 6 DONE、2C 限制刷新、提交 5 HOLD 解除）；新增 T-G1-S（sessionId 先修）与 T-G1-C（待授权执行） | 规划层复核：152 porcelain = 172 文件（106 tracked + 66 新）；`docs/project-control` 7 / `docs/audit` 7 计数一致；`Common.kt:337-341` 建请求确无 sessionId、`:347` 存原始 request，`bridgeCandidate`/`reject` 的 SESSION_REQUIRED 确认存在；控制层 README/roadmap/feature-status 原写 Phase 6 IN_PROGRESS / CharacterConsistency BLOCKED，已更新为 DONE（GATE_PASS_WITH_LIMITATIONS）并保留限制 |
| 2026-10-28 | T-G1-S 审查通过 → DONE：Desktop 候选操作 sessionId 缺口已修（`Common.kt:347`），桌面 47/0 + root 1576/0；T-G1-C 待用户逐批授权 | 规划层实测：`lastAgentRequest = result.sessionId?.let { request.copy(sessionId = it) } ?: request`；`DesktopAgentApprovalUiTest` 5/5（含真实 NovelAgent + 临时 DB 断言 bridge/refresh/reject 不再 SESSION_REQUIRED）；desktop XML 5/0、最新 2026-10-28 08:25(+0800)=00:25Z > 源码 00:24:41Z；porcelain 152、staged=0、HEAD `be1b755`。报告"未新增代码改动"与"修复前 2 failed"自相矛盾，已注明为表述问题 |
| 2026-10-28 | G1 提交 0–3 已完成并审查：`c2c3681`(4) / `0530e2b`(141) / `77c5718`(5) / `5466f98`(7)；批次 3 gate 瑕疵裁 D-32 接受（快照不改写），流程错误单列；批次 4/5 待续 | 规划层实测：`git log` 四个 SHA 与文件数一致（4/141/5/7）；`git show --stat` insertion/deletion 与报告一致；HEAD `5466f98`，`rev-list origin...HEAD = 0 6`；剩余 9 porcelain 条目（5 M + 4 ??）= 15 文件（批次 4 的 8 + 批次 5 的 7）；staged=0；未 push。执行层自报 `ls-remote` 因网络不可达未复核远端，但未执行 push，本地 tracking ref 仍 `5b047b0` |
