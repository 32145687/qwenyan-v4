# Qianyan 任务树（Living Task Tree）

> 唯一任务状态源。最后更新：2026-10-31。
> 本文件由规划层维护；执行层只读，不得修改。
> 状态词：`DONE` / `PENDING_GUI` / `PARTIAL` / `BLOCKED` / `NOT_STARTED` / `DEFERRED` / `PAUSED`。
> `DONE` = 实现 + 自动化验收 + 适用时真实生产 caller；GUI / 端到端未验另标 `PENDING_GUI`。
> 证据必须可定位：文档、测试名、`路径:行号`、commit。
> 更新规则：执行层回报后，规划层先对账；只有审查通过才改状态，并在“更新日志”追加一行。执行层自报不算。

## 0. 当前焦点

- 当前单元：**T-G12 任务树文档批**；之后 T-O48 手工 G8
- 阻塞：T-O48 真实双击未验证（需桌面解锁）；O-37 需新契约（Rule 执行语义 + 创建/读取路径）；提交/推送需用户逐条授权
- 已完成：Phase 4/5/6/8 DONE（8=GATE_PASS）；T-O39b DONE（T-G11 `c80e2e8`）；T-O48 代码/测试（T-G9 `a56b04b`）；T-43（D-37）、T-O44、T-D8、T-O38、T-O45、T-O47、T-O39（读侧）、T-G6/T-G7/T-G8/T-G10/T-G11/T-G9、T-LEGACY、G5、T-PUSH-2/T-PUSH-3；词库线；T-7-1/T-7-3/T-42；T-8 全系；W1；G1–G5；**5 个原始提交已 push（7af66c5 = 远端）**
- 并行决策：T-O48（PARTIAL，待手动 G8）、T-O39b（DONE，Schema FK 残留）、T-7-2（PARTIAL）、O-37（需契约）、O-39b（Schema FK）
- 仓库快照：HEAD `a56b04b`，`origin/feature/novel-ide` = `7af66c5`，ahead **2**；工作区 **1 项**（本文件 M）；fallback `D:/qwenyan-v4/qianyan-v4-recovery-clone`（d9aa385 + 36 项）；恢复包 `C:/Users/ZhuanZ1/Documents/qianyan-original-20261030/`

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
| 7 | PARTIAL（仅剩 T-7-2 GUI） | NovelAgent 七相位 + **D-36 结果驱动决策已落地（O-11 DONE）**；决策点 X→A / Y→B 有行为测试；GUI G1–G8 未验 |
| 8 | **DONE（GATE_PASS，T-8-2R 2026-10-28）** | ①17 段全 ✓（段 4 由 D-36 的 X/Y + V3 有界 + V5 守卫补足）；②三边界绿（边界三为结构级，DSH 未接入）；③禁止项 18 条零引入；④P0–P6 slice 仍绿，P7 slice 转绿（O-11 DONE）。不证明：3000 字 / 真实 LLM / GUI / 重启持久化 / O-42 跨章 / 多 Variant 并发 / 边界三集成反测 |
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
| D-33 | T-7-1 决策点语义裁决（7 项） | DONE（裁决） | (a) 空 Pack 不新增 CONTEXT 拒绝（D-25）；Writing/Plan 现映射 `EXECUTION_FAILED` 属错误语义缺口 → O-34，不测为期望行为。(b) `Analysis.passed=false` 保持 COMPLETED（信息性结果），补"结果携带 passed/计数"断言。(c) CharacterConsistency 三态均 COMPLETED，补 PASS 结果级断言。(d) ActionPolicy `Denied/Allowed` 当前策略下不可达且 `ActionPolicyUseCases` 是 final class → **不测、不改结构**，登记 O-35。(e) `DecisionPolicySource` 只有 `RULE_MATRIX`/`DETERMINISTIC_DEFAULT`；`PREFERENCE` 是**规划层上一轮提示词的口径错误**，以代码为准，不新增枚举。(f) 防御/不可达分支（S7/C5/E6/R2）不纳入 T-7-1。(g) 已提交事实三入口：补 `bridgeCandidate` 幂等分支（G4），与 resume/refresh 对齐 |
| D-34 | 词库 V1–V3 归属裁决 | DONE（裁决，FINALIZED） | 见 `docs/planning/qianyan-vocabulary-ownership-decision.md`。**文档优先级**：p20 FD-6 / Phase 4A J-1 / Phase 4C AC-30 > v3.1 §11A 禁止条款（按文档性质与形成时间，不以"代码已存在"倒推动机）。**V1-A** 承认现行独立词库域（唯一表族，不得再新增第二套）；**V2-①** 作用域 = NOVEL + VARIANT（VARIANT 合并 NOVEL，同名 canonical 由 VARIANT 覆盖），`scope_level` 降级为标注，GLOBAL/TASK 标注未启用，不做 BOOK/SERIES/AUTHOR；**V3-A** MVP 只做别名收敛进 prompt + 修预算不一致，Rule/Critic/TEMPLATE/UI 不做（O-37/O-38）。§11A 已加指针，本文件仅追加未改写 |
| D-35 | 段 16「第二次 Agent」的产品语义 | DONE（用户裁决） | **显式"收敛评审轮"**：Commit 后由用户显式触发；第二轮**只读**——用新 Context（含刚写入的 WM Event/CharacterState）对已提交正文做一致性与偏离检查，输出 findings/suggestions；**不写正文、不自动进 Gate**；轮次上限 1。实现形态：一次新的 `NovelAgent.run`（新会话、`preferredSkillId`=只读 Skill），结果与 `sourceCommitId` 绑定。T-8-E 的 Proposal = 该结构化复审结果，不另造空泛类型 |
| D-36 | O-11 决策点实现形态 | DONE（用户裁决） | **真实结果驱动决策**：同一 Task 内，`analysis_critique` 的 Result 决定下一步——PASS → 结束（COMPLETED，无产物）；含 ERROR → Agent **自行决定**再执行一次 `rewrite-revision`（产出 WorkingDraft → 正式 Gate，WAITING_HUMAN）。**有界（最多 1 次续步）**；不新增 Skill、不改 ANALYSIS_CRITIQUE 的只读契约；决策点可写 X→A / Y→B 两条行为测试 |
| D-37 | analysis 续步的 Revision 复用初次 critique | DONE（裁决，2026-10-28） | 为满足"critique ≤ 2（初次 + 复评）"，analysis ERROR 续步不再重复 critique **同一基线**：初次 analysis 的 `ValidationResult` 直接作为 `RevisionAgent` 输入（`executeSkill(..., critiqueOverride)`），省掉一次对相同内容的冗余 LLM 调用；仅在 analysis 续步生效，其他 REWRITE/EXPAND 入口仍保持内部 critique。顺序与有界性不变；D-36 的"analysis Result 驱动下一步"因此更名副其实 |

## 3. 未完成 / 部分完成 / 阻塞

| ID | 任务 | 状态 | 缺口 / 阻塞 |
|---|---|---|---|
| O-02 | 2B CharacterConsistency 实现 | **DONE** | 契约冻结（D-21）+ 纯逻辑 + Agent/UseCase/DI + Skill 路由 + NovelAgent 接线 + E2E 全绿（T-2B-1/2/3 DONE(L2)，1568/0 + 桌面 46/0）；未用 `ANALYSIS_CRITIQUE` 冒充 |
| O-29 | gap 信号已落 / token 预算延后 | **PARTIAL** | (1) 已由 D-23a 解决：`Basis.priorCanonicalGapChapterIds` 落 Result（`Models.kt:127`、`Validator.kt:651` 拷贝 + :107-112 去重/重叠校验），E2E V-6 断言。**(2) 仍开放**：当前稿/前章正文全量渲染、无 token 预算（与 DraftSource"原文不截断"一致但长文有溢出风险）；延后，不阻塞 |
| O-30 | 其余 Skill 的"缺失输入"可见性 | **OPEN（3 项登记）** | D-25 已定语义为 Result 可见；CharacterConsistency 满足。**O-30-1** STORY_PLANNING：结果/Artifact 无 coverage，声明来源（含 STORY_FOUNDATION）缺失不可见。**O-30-2** WRITING：新章无 DRAFT 合法，但 foundation/vocab 缺失静默。**O-30-3** REWRITE_REVISION / ANALYSIS_CRITIQUE：非 Pack Skill，仅 DRAFT 缺失有 `BASE_DRAFT_NOT_FOUND` 兜底，其余声明来源不可见。均只登记，未改行为 |
| O-31 | tool→AgentActionKind 映射 | **OPEN** | D-27 的 action 级 observed ⊆ expectedActions 需工具→动作类别映射；2C 只做工具级，未发明 |
| O-32 | `latestCanonicalDraftId` 命名与实际语义 | **DONE（改判，2026-10-28）** | 复核 R3：**"Agent Context = Canonical 前文 + 当前章 Draft"** ⇒ 当前章生成基线取 latest draft **符合 R3**，不是违规。真实问题是**函数名**（`latestCanonicalDraftId` 实际返回 draft）与**前章正文**：`generateNewContent` 调 `planningContexts.assemble(writingRequest, contextPack)` 未传 `continuationReference`，故 NovelAgent 多章续写**不带前章 Canonical**。前者改名即可（非缺陷），后者登记 O-42（本 17 段场景是"同一章"，不受影响） |
| O-42 | NL 跨章续写缺前章 Canonical | **DONE（2026-10-28，T-42）** | `NovelAgent.resolveContinuation`（:1066-1112）：`listByNovel(novelId, chapter.variantId)` 取紧邻前章 → `canonicalRead.committedDraft` → 仅 CONFIRMED 才构造 reference → 复用 `ContinuationResolver` → `assemble(..., reference, resolved)`。**NL 侧降级不硬拒**：`NO_PREVIOUS_CHAPTER` / `NO_CANONICAL` / `CARRIER_NOT_CONFIRMED` / `CONTINUATION_UNRESOLVED` 写入新增三字段（`continuationIncluded` / `continuationSourceChapterId` / `continuationAbsentReason`），工作流链的硬拒契约未改（V-6 回归 7/7 + 13/13）。5 个新测试 V1–V5 |
| O-43 | 续步后无"对修订稿复评" | **DONE（2026-10-28，T-43）** | 复评已落地：续步产出 Draft 后 `critic.critiqueContent(修订稿)` 一次，结果写入 `postRevisionAnalysis`；复评失败写 `postRevisionCheckFailure`（INVALID_OUTPUT / CALL_FAILED）且任务继续。配合 D-37，raw critique 恒为 2（初次 + 复评） |
| O-44 | 复评未进 Activity | **DONE（2026-10-29，T-O44）** | `reviewRevisedContent` 已新增 `REVIEW` Activity（start/complete/fail），并追加到 `state.activityIds`；`NovelAgentResult.activityIds` 含复评记录。成功 summary 记 passed/ERROR/issues，失败 error 记 `INVALID_OUTPUT`/`CALL_FAILED`；Activity 写入 best-effort，不改变任务结局。V1/V2/V4 + `DecisionPointAnalysisFollowUpTest` 4/4，application 1156/0 |
| O-45 | 作品切换器行点击静默失败 | **DONE（2026-10-29，T-O45）** | 根因：`main()` 根作用域未 remember `DesktopGraph`/`DesktopAppState`；打开作品写 `state.novel` 触发根重组合，重建新 state（novel=null），业务成功但 UI 重置。修复：两处 `remember` + `openNovel` null 显式 toast。新增 `DesktopNovelSwitchTest` 3 条；desktop 52/0；手工双作品切换、新建/导入/主区新建均正常。 |
| O-46 | Toast 全局不渲染 | **DONE（2026-10-30，T-O46）** | 原症状无法复现（探针三路径均可见）；已加固 toastJob 取消旧计时器 + toast 层最终前景 zIndex。T-7-2-FALLBACK G1 实测：发送后 1s 内 toast 正常、输入保留、无任务卡；O-46 关闭。 |
| O-47 | 重启后失效候选仍显示可批准 | **DONE（2026-10-30，T-O47）** | `candidateLost=true` 时禁用批准、保留“拒绝失效提案”（reject 不依赖 artifact）；拒绝后 Gate=REJECTED、Canonical/commits 不变，普通编辑态恢复后才出现“去 Agent 重新发起”。`RestoreFailure` 显式文案。`DesktopWriterCandidateLostTest` 6/0、desktop **60/0**；手工 ch3 stale Gate 拒绝 + ch4 正常 Gate 批准均通过。 |
| O-48 | 双击“拒绝”穿透到“继续写” | **PARTIAL（2026-10-30，T-O48）** | 已加 500ms 决议防抖：approve/reject 成功后 `decisionLocked=true`，`generate`/`canGenerate`/`canRewrite` 窗口内拦截；按钮禁用。测试 63/0（+3）。**手工双击拒绝未验证**：桌面被 LogonUI/输入法 CoreWindow 占据，fallback 拒绝输入；G8 仍待真实窗口。 |
| O-33 | Desktop 无 continueToNextChapter 接线 | **OPEN（UI 恢复时）** | 2A 续篇唯一生产 caller 是 Android `ChapterWritingViewModel.kt:87`（Android 暂停）；Desktop `WritePage.kt` 的"下一章"仅导航、`continueWriting` 是同章推进（非续篇）。UI 线恢复时补 |
| O-34 | 空 Pack 的错误语义 | **OPEN** | D-33a：空 Pack 允许进入 EXECUTE（D-25），但 Writing/Plan 缺 payload 现映射为 `EXECUTION_FAILED`——把"缺输入"说成"执行失败"，语义不准。应改为可区分的类型化缺失输入错误；改与不改待裁，T-7-1 不测为期望行为 |
| O-35 | ActionPolicy Denied/Allowed 不可达 | **DEFERRED** | D-33d：`COMMIT_CANONICAL` 在 `ActionPolicyUseCases` 恒 `NeedsHuman`，NovelAgent GATE 的 `Denied→ACTION_DENIED` / `Allowed→COMMIT` 当前不可达；且该类是 final（`ActionPolicyUseCases.kt:44`），测试替换需改结构。不为不可达分支改结构，暂不测 |
| O-36 | R3/R4 失败路径缺可注入缝隙 | **OPEN** | `WorkingDraftUseCases` / `ChangeUseCases` 均为具体类（非接口/非 open），无法在测试中注入创建失败 / `prepare` 失败，故 R3（`WORKING_DRAFT_FAILED`）与 R4（`ARTIFACT_FAILED`）无行为测试。改结构只为测试不划算；将来若要覆盖，优先用 DB 层故障注入而非改类 |
| O-37 | `VocabularyRule` 执行语义 | **OPEN（V3-A 外）** | D-34/V3-A：Rule 有模型、无生产消费者，且"确定性替换"的执行位置（Writer 后处理？Revision？写回？）、`deterministicOnly` 语义均未定义。先有契约再实现，不用现状反推 |
| O-38 | Critic/Revision 别名漂移检查 | **DONE（2026-10-29，T-O38/D-38）** | 已裁 D-38：只取 APPROVED 词条；Critic 对正文别名做确定性扫描，命中记 WARNING（不升级 ERROR、不改 passed、不自动修正）；Revision prompt 要求优先 canonical、不机械替换；NovelAgent 分析/改写/复评三处接线；空词库行为不变。共享投影 `PlanningContextAssembly.approvedVocabulary` 复用 `mergedApprovedEntries`。`VocabularyAliasDriftTest` 7/0，application 1163/0，storage 166/0 |
| O-39 | `VocabularyEntry.variant_id` 无外键/归属校验 | **PARTIAL（2026-10-30；读侧 DONE，写侧应用层守卫 DONE，Schema FK 残留）** | 读侧：`findEntriesByVariant(novelId, variantId)` JOIN `NovelVariant`，跨作品 variant 不返回；存储 167/0、application 1163/0。写侧应用层守卫已由 T-O39b 补齐；仍无 Schema FK，底层 repo.saveEntry 可绕过，见 O-39b。 |
| O-39b | `VocabularyEntry` 写侧归属/FK | **PARTIAL（2026-10-30，T-O39b 应用层 DONE）** | 应用层：`saveEntry` 校验 novelId 必填 / variant 存在 / 归属一致，`confirmCandidate` 走同一入口；5 条测试，application 1168/0、storage 167/0。仍无 Schema FK，底层 `repo.saveEntry` 可绕过；Schema 级 FK 另立 O-39b-FK。 |
| O-40 | 第二次 NovelAgent 的产品语义 | **RESOLVED（D-35）** | 用户裁决：**显式"收敛评审轮"** —— Commit 后由用户显式触发，第二轮只读，用新 Context 对已提交正文做一致性与偏离检查，输出 findings/suggestions，**不写正文、不自动进 Gate**，轮次上限 1 |
| O-03 | 2C Skill 契约收口 | **DONE** | T-2C-1/2/3 实施 + T-2C-4 核验，全量 1575/0 + 桌面 46/0；缺口转 O-30/O-31 |
| O-04 | 词库别名收敛 / VocabularyRule / Critic 漂移 | **NOT_STARTED** | prompt 只渲染 canonical + replacement，aliases 被丢弃；Rule 无消费者；无 Critic 检查 |
| O-05 | 多 TXT 全书章节顺序 | **NOT_STARTED** | R1–R5 已裁，功能未实现 |
| O-06 | World Model 冲突 → Human Gate | **PARTIAL** | CONFLICT 只写候选队列；Gate 挂载点未接 |
| O-07 | 伏笔生命周期 / Reveal 的生产调用与 UI | **PARTIAL** | `ForeshadowLifecycle` 无 UI/生产 caller |
| O-08 | 知识更新 Desktop 入口 | **PARTIAL** | 后端 KU 链路存在；Desktop 入口按当前 UI 复核，历史台账 PARTIAL |
| O-09 | 阅读位置写入的 L3 验收 | **PARTIAL** | 按 UI 记录已接；功能台账仍 PARTIAL，未按 L3 验收 |
| O-10 | GUI G1–G8 | **PENDING_GUI** | 未执行真实点击/输入；不得标记 PASS |
| O-11 | Phase 7 决策点行为测试 | **DONE（2026-10-28，T-7-3）** | D-36 真实结果驱动已落地：同一 Task（同一 `userIntent="分析第一章"`）下，critic PASS → COMPLETED 无产物；critic ERROR → 同一 session 内自动续步一次 `rewrite-revision` → WorkingDraft + Artifact + pending Gate + WAITING_HUMAN。V1/V2 用**同一请求只换 critic Result** 对照，满足 §20-12 的 X→A / Y→B。附带记录：续步调用**不经过 `purposes` 选择**（由 Agent 决定触发，仅受 SkillExecutionGuard 约束），这是 D-36 的既定设计，不是漏洞 |
| O-12 | Phase 8 17 段完整纵切 | **NOT_STARTED** | 无自然语言 → Canonical 的完整 E2E |
| O-13 | DSH Runtime Adapter | **DEFERRED** | R7 产品动机未裁 |
| O-14 | Android | **PAUSED** | 产品策略暂停 |
| O-15 | Story Foundation / Author Intelligence 写路径 | **PARTIAL** | 模型/部分写路径有；Desktop 多为接入点 |
| O-16 | `commitApprovedDraft` 遗留入口 | **OPEN** | 无调用方/无测试，缺 Gate 绑定；待裁决删除或补齐 |
| O-17 | 词库 V1–V3 契约裁决 | **OPEN** | 三套模型未统一；详见 `project-vocabulary-contract-drift` |
| O-18 | 工作树 W1 | **OPEN** | 四个工作树冻结在 2026-10-20；未归档、未清理 |
| O-19 | 提交冻结 G1 | **OPEN** | 130 项未提交；DONE 不在任何 commit 里 |
| O-20 | P0-3B D8 / D9 | **D8 DONE（2026-10-29，T-D8）；D9 PARTIAL** | D8 已移除 `allowUnboundGate` 兼容面：`CommitApproval` 仅 `gateId`，无 stepId Gate 无条件 `GATE_TARGET_MISMATCH`；62 处测试经 bound 夹具迁移。D9 仍缺 Agent 层清理故障注入 |
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

- [x] `T-7-1-0` 决策点枚举与覆盖审计（只读）— **DONE（2026-10-28）**
  枚举 40 个分支对（S1–S7 / C1–C5 / E1–E7 / R1–R4 / G1–G8 / D1–D8）；已覆盖 19、缺一支 9、完全无 6、N/A 若干。规划层裁 D-33（7 项语义）；工作树 `M docs/project-control/qianyan-task-tree.md` 已确认是规划层更新，非异常。
- [x] `T-7-1-A` 决策点行为测试第一批 — **DONE（2026-10-28）**
  新增 10 个测试（DecisionModelTest +3：DNA-only / core-only / DNA-only LONG；`DecisionPointBehaviorTest` +6：A4 已提交幂等不重复 Commit、A5a/b/c refresh 三状态、A6 reject 已提交不新增 Commit/Cancel、A7 passed=false 仍 COMPLETED 且 summary 可见计数；E2E +1：A8 PASS 空 findings）。规划层复核断言均为行为级（含 CommitHistory 条数、Canonical 内容、session 状态、summary 文本）。application 1117/0（+10）、root 1586/0（含 Android 120）、desktop 47/0（未改动、沿用）。A9 未写（见 O-36）。
- [x] `T-7-1-B` 依赖裁决项 — **DONE（登记，无需测试）**：G1 Denied/Allowed → O-35（不可达/final class）；C4 空 Pack → O-34（错误语义缺口，不测为期望）；D8 `PREFERENCE` → 已勘误（代码无此枚举）。
- [x] `T-7-1-C` 防御/不可达分支 — **DONE（记录不测）**：S7/C5/E6/R2 为防御分支，当前不可达；不为不可达路径造 mock 内部状态的测试。
- [ ] `T-7-2` P0-3B GUI G1–G8 真实验收 — **PARTIAL（2026-10-30；G1–G7 PASS；G8 FAIL：双击拒绝穿透到继续写）**
  **2026-10-30 12:56 最终复跑**：MCP 启动 14/14×2；应用 PID 85148 从 e3b581f 重启；第一次“新建作品”无响应，第二次点击时 MCP ECONNRESET → 0/14。未创建作品（novels=9）、G1–G8 全 NOT RUN；DB 与启动基线一致。规划层同一时刻 MCP 14/14。
  证据：HEAD `b703ea0`；O-45 回归 PASS（1808/长夜行舟反复切换）；G2 任务卡/BRIDGE；G3 候选/Diff/审批按钮；G4 第二候选 UNBRIDGED、1 Gate；G5 retry→拒绝 A→B BRIDGED；G7 双击 1 Commit、拒绝另一候选 Canonical 不变、历史 1。
  G1 FAIL：拒绝行为正确但 toast 不渲染（O-46）。G6 PARTIAL：重启后候选内容消失仍显示可批准，点击通用“操作失败”，无失效文案（O-47）。G8 部分：双击批准/重复拒绝已过；“丢弃 UNBRIDGED/切页刷新”因 MCP 第二次消失未执行。
  环境事件：应用被外部关闭一次（exit 0）；MCP 消失 2 次（一次恢复、一次停止）；桌面有并行人工操作。未改代码/DB/任务树/记忆，未 commit/push。
  历史阻塞：Session 7 曾被 `LogonUI.exe` 锁定；18:08/18:?? 两次 computer-use 会话级失效。**2026-10-29 19:27 新会话 MCP 稳定（14/14）×2，但目标作品 `T-7-2 GUI 20261029-1808` 点击切换器行无任何效果；G1–G8 无法开始，转 O-45。**
  历史证据：Provider=MOCK；3 个重复测试作品 `T-7-2 GUI 20261029-0055`（重复创建待核实）；证据目录 `D:/qwenyan-v4/backup/t-7-2-20261029-0049/`。恢复后需先在最终 HEAD 重启应用，再重跑 G1–G8。
  **2026-10-29 18:08 重试**：桌面无 `LogonUI`；旧窗口置前台成功；旧 PID 59736 关闭；新应用 PID 75980 从 HEAD `95c0f30` 启动；Provider=MOCK。窗口标题 `未打开作品` 是设计内动态标题（无作品时），不是 gate 失败。一次“新建作品”后 computer-use MCP 返回 `ECONNRESET`、工具列表消失，记为**工具阻塞**，G1–G8 全 NOT RUN；未继续点击/伪造结果。
  **18:?? 二次工具 gate**：重试时 `mcp_list` computer-use 0/14，无法 `list_windows`/`activate_window`，再次 HARD STOP；应用 PID 75980 仍 `Responding=True`，未做任何 UI 操作。
  **18:?? 三次稳定性 gate**：执行会话连续两次 `mcp_list` 0/14；规划层同一时刻 `mcp_list` **14/14**，`get_window_state(no screenshot)` 可读。判定为**执行会话级 MCP 连接失效**，不是应用/工具全局故障；需重开执行会话或重启 Qoder 重新挂载 plugin，随后重试。capture-lite 协议保留。
  **19:27 新会话复测**：MCP 稳定 14/14×2；9 部作品，`T-7-2 GUI 20261029-1808` 恰 1 个，DB/ProjectState 正常；所有作品行点击后弹层关闭但标题不变、无 toast；同弹层“新建作品…”/头部/主区按钮/输入/取消均正常；精确坐标 + 重启后复现。**G1–G8 未开始；转 O-45。**
- [x] `T-O45` 修复作品切换器行点击静默失败 — **DONE（2026-10-29，b703ea0）**
  根因：`main()` 根作用域未 `remember` `DesktopGraph`/`DesktopAppState`；打开作品写 `state.novel` 触发根重组合，重建新 state（novel=null）。修复 `Main.kt:123-127` 两处 remember；`Common.kt:575-592` null 显式 toast。
  验证：`DesktopNovelSwitchTest` 3/0；desktop **52/0**；手工切换 1808/长夜行舟、新建/导入/主区新建均正常。允许文件外无改动；临时日志已删除。提交后恢复 T-7-2 G1–G8。
- [x] `T-O46` 修复 toast 全局不渲染 — **DONE（2026-10-30，G1 PASS）**
  探针三路径均可见（原症状未复现）；加固 toastJob 取消旧计时器 + toast 层最终前景 zIndex。`DesktopToastTest` 2/0；T-7-2-FALLBACK G1 发送后 1s 内 toast 正常、输入保留、无任务卡。
- [x] `T-O47` 修复重启后失效候选 UI — **DONE（2026-10-30）**
  `candidateLost = isLoaded && waitingForUser && pendingProposal == null`；`canApprove` 要求候选存在，`canReject` 不依赖候选；失效时显示“拒绝失效提案”+ 文案，Gate 关闭后才出现“去 Agent 重新发起”。`RestoreFailure` 映射为候选失效文案。
  实测：`DesktopWriterCandidateLostTest` 6/0、desktop **60/0**；手工 ch3 stale Gate：无通过确认、拒绝成功（Gate REJECTED、commits 不变），ch4 正常 Gate 通过确认新增 1 Commit；证据 `t-o47-20261030-1017`。
- [ ] `T-O48` 修复双击拒绝穿透到继续写 — **PARTIAL（2026-10-30；代码/测试完成，手工 G8 待解锁）**
  已实现：`WriterController` 500ms 决议防抖（`decisionCooldownUntil` + `decisionCooldownJob`，可注入时钟）；approve/reject 成功后 `decisionLocked=true`；`generate` 窗口内直接 return；`canGenerate`/`canRewrite` 加入锁；WritePage 按钮自动禁用。未改 Workflow/Commit/D8。
  测试：desktop **63/0**（+3：UI 锁、拒绝后立即继续被拦 + 501ms 恢复、批准同样冷却）；`DesktopWriterTest` 在 continue 前加 600ms 等待，断言未改。
  **待办**：解锁后真实双击拒绝：仅一次 REJECTED、无新 PENDING Gate；500ms 后继续写正常；再双击批准 1 Commit。通过后 T-G9 提交。
- [x] `T-O39` variant 词条读取归属校验（O-39 读侧）— **DONE（2026-10-30）**
  `findEntriesByVariant(novelId, variantId)` + SQL JOIN `NovelVariant`；ContextSources/PlanningContextAssembly/Android fake 同步；storage 测试真实 Novel+Variant，跨作品读取返回空。
  实测：storage **167/0**、application **1163/0**；android/desktop 编译通过；写侧 FK/写入校验残留记 O-39b。
- [x] `T-O39b` variant 词条写入归属校验 — **DONE（2026-10-30，应用层）**
  `VocabularyUseCases` 注入 `NovelRepository`；`saveEntry` 先 `requireVariantOwnership`（novelId 必填→InvalidOperation；variant 不存在→EntityNotFound；归属不符→VariantMismatch）；`confirmCandidate` 正式写入改走本类 `saveEntry`；DI `VocabularyUseCases(vocabularyRepository, novelRepository, errorMapper)`。
  测试：合法 variant 成功、跨作品拒绝不落库、缺失 variant、novelId=null、confirmCandidate 跨作品 5 条；application **1168/0**、storage **167/0**；android/desktop 编译通过。**残余**：无 Schema FK，底层 `repo.saveEntry` 仍可绕过，记 O-39b-FK。
- [x] `T-O44` 将续步后复评纳入 Activity（O-44）— **DONE（2026-10-29）**
  目标：`reviewRevisedContent` 增加一条 `OrchestrationPhase.REVIEW` Activity（start/complete/fail），成功记 `passed/ERROR/issues` 计数，失败记 `INVALID_OUTPUT`/`CALL_FAILED`；仍不改 critique=2、revision=1、WAITING_HUMAN 与 Gate 语义。
  实测：`NovelAgent.kt:1194` best-effort start/complete/fail；`:1196` `state.activityIds += activityId`；V2/V4 断言 `result.activityIds.contains(...)`；V1 无复评；`DecisionPointAnalysisFollowUpTest` 4/4，application **1156/0**，XML 15:32 晚于源码 15:31；Activity 总数 8，critique/revision 次数不变。
- [x] `T-D8` 移除 `allowUnboundGate`，强制 Gate 绑定 stepId — **DONE（2026-10-29）**
  `CommitApproval` 只剩 `gateId`；`requireApprovalBinding` 在 `step == null` 时无条件 `GATE_TARGET_MISMATCH`，复用检查仍在最前；无运行时豁免。
  62 处 `approvedGate`/`approvedCommitGate` 通过 bound 夹具迁移（WORKING + CONFIRMATION step）；新增 `steplessApprovedGate` 仅用于负向测试；原兼容复用测试替换为 bound gate 复用不变量。
  实测：代码 `grep allowUnboundGate` = 0；application **1156/0**、desktop **49/0**；XML 15:55/15:56 晚于源码 15:40–15:47；`CommitGateBindingTest` 仍 8 条，未删负向断言。
- [x] `T-O38` Critic/Revision 别名漂移检查（O-38）— **DONE（2026-10-29，D-38）**
  `PlanningContextAssembly.approvedVocabulary` 复用 `ContextSources.mergedApprovedEntries`（NOVEL→VARIANT 覆盖、仅 APPROVED）；Critic 追加 `【词库规范】` 并按别名确定性追加 WARNING（最多 20，不改 passed）；Revision prompt 加入同一词库与 canonical 优先指令；NovelAgent 分析/改写/复评三处接线，空词库零变化。
  实测：`VocabularyAliasDriftTest` **7/0**；application **1163/0**（1156+7）、storage **166/0**；XML 17:52/17:50 晚于源码 17:44–17:51；未新增 Agent/Rule/表/UI。
- [x] `T-G6` 提交 T-O44/T-O38 + T-D8 两批 — **DONE（2026-10-29）**
  `6ce9a00`(8, +453/−22, O-44+O-38) → `95c0f30`(8, +242/−51, D8)。HEAD `95c0f30`；staged 空；工作区仅任务树 M；两批 staged 集合/数量、`--check`、敏感扫描 0、commit 后 staged 空均通过。
  clean-code 全量：root **1632/0/0/0** + desktop **49/0/0/0** = **1681/0/0/0**；XML 18:02–18:04 晚于源码；未 push、未 tag。
- [x] `T-G7` 提交 T-O45 修复 — **DONE（2026-10-29）**
  `b703ea0`(3, +118/−4)：`Main.kt` remember graph/state、`Common.kt` null toast、新增 `DesktopNovelSwitchTest`。staged 集合 3、`--check` 0、敏感扫描仅类型名、commit 后 staged 空；desktop **52/0**；HEAD `b703ea0`，领先 3，未 push。
- [x] `T-G8` 提交 T-O46 + T-O47 UI 修复 — **DONE（2026-10-30）**
  `e3b581f`(7, +485/−25)：toast job/zIndex、candidateLost/拒绝失效提案、2 个新测试。staged 7、`--check` 0、敏感扫描仅类型名、staged 空；desktop **60/0**；HEAD `e3b581f`，领先 4，未 push。
- [x] `T-G10` 提交 T-O39 读侧归属校验 — **DONE（2026-10-30）**
  `7af66c5`(7, +72/−12)：repository 双参 + SQL JOIN + callers + storage 测试。staged 7、`--check` 0、敏感扫描 0；storage **167/0**、application **1163/0**；HEAD `7af66c5`，领先 5，未 push。
- [x] `T-8-0` 17 段现状只读重审 — **DONE（2026-10-28）**
  按当前代码重判：✓ 1/2/3/6/8/9/10/14；△ 4/5/7/11/12/13/15/17；✗ 16。**5 个真实断点**：5→7（ContextPack 有 DRAFT 内容，但 `PlanningContext` 无当前稿字段，Writer 只看到 `rawText=userIntent`）；6→7（`get_latest_draft` 只取 draftId，正文不进 Context）；10→11（桥接条件性，需显式重试）；14→15（WM Event/CharacterState 不进下一 PlanningContext，WRITING 未允许 `get_world_context`）；15→16（无第二次 Agent 编排）；16→17（无 Proposal 回流契约）。已连接：7→8→9→10→11→12→13→14。
- [x] `T-8-A` **5→7 胶水（P0 正确性）** — **DONE（2026-10-28）**
  `PlanningContext.currentDraft: CurrentDraftLite?`（与 `sourceFinalDraft` 语义分开）；`CurrentDraftLite.toPromptSection()` 共享渲染；`PlanningContextAssembly.kt:103-109` **只从 Pack 的 DRAFT item** 取（`source==DRAFT && ref.chapterId==focus`），无 item → null 不回退；Planner :151 / Writer :154 同函数渲染。V-4 用"pack 旧稿 ≠ 仓库最新稿"夹具证明无回退。application **1128/0**（+4）、root **1597/0**、desktop 47/0。已知成本：当前稿同时进 Planner 与 Writer 两次请求。
- [x] `T-8-C` **14→15 胶水** — **DONE（2026-10-28）**
  `PlanningContext.worldModelPromptSection()`（:113-190）统一渲染【人物档案】【人物状态】【世界规则】【近期事件】；Planner :145 / Writer :135 共用；上限各 20 + `truncated=true; total=N`；排序确定（Characters `(name,id)`、States `(characterId,stateId)`、Rules `(category,ruleId)`、Events `createdAt DESC, eventId`）；只消费 Assembly 已解析的同一 `worldContext`（V-6 用"assemble 后新增 Event B 但 prompt 不含 B"证明无二次 resolve）。原 Planner【相关人物】内容（name+goals）无损迁移到【人物档案】。**V-1 证明 14→15**：同 DB 第二次 assemble 的 prompt 含第一轮 Commit 写入的 Event。application **1134/0**（+5）、root **1603/0**、desktop 47/0。
- [x] `T-8-D` **15→16 编排（D-35）** — **DONE（2026-10-28）**
  `ConvergenceReviewUseCase`（70 行）：`committedByArtifact` 校验 + novel/chapter 归属校验（失败 `EntityNotFound`）；构造**不带 sessionId** 的新请求（`preferredSkillId=skill.character-consistency`），只调既有 `novelAgent.run`（不重建 Pack / 不自行 resolve）；结果 `ConvergenceReviewResult(sourceCommitId, sourceArtifactId, round=2, result)`。DI +10 行。Desktop 最小入口：`launchConvergenceReview()`（commitId/artifactId/request/novel 逐级门控 + 作品一致性）+ `AgentPage` 按钮仅在 `commitId != null` 可见 + 两行摘要；无布局改版。
  测试：`ConvergenceReviewUseCaseTest` 4/4（V-1 第二轮 prompt 含第一轮 Event；V-2 四类计数 + Canonical 不变；V-3 未提交先拒且 0 次 LLM；V-4 新会话且首轮 COMPLETED 不被改写）；`DesktopConvergenceReviewUiTest` 1/1（源码结构断言，仓内无 Compose 点击夹具）。
- [x] `T-8-E` **16→17 契约（D-35）** — **DONE（2026-10-28）**
  `ConvergenceOutcome` 四态（CONSISTENT/ISSUES_FOUND/INSUFFICIENT_EVIDENCE/REVIEW_FAILED，注释明写"REVIEW_FAILED 不得折叠成 CONSISTENT"）；`ConvergenceReviewResult` 扁平化 11 字段（身份 5 + 结论 4 + summary + 保留原始 `result`）；`convergenceOutcomeOf` 单一映射入口：仅 `COMPLETED` 且 `characterConsistency != null` 才进三业务态，其余一律 REVIEW_FAILED。测试 8/8（含"COMPLETED+null → REVIEW_FAILED"与 `assertNotEquals(CONSISTENT)`）。Desktop 摘要改读扁平字段，Common/AgentPage 均不再出现 `review.result.characterConsistency`。application **1142/0**（+4）、root **1611/0**、desktop 49/0。
- [x] `T-8-1A` 最小可信 E2E（1–13 + WM 触发）— **DONE（2026-10-28）**
  `Phase8PartialLoopE2ETest`（190 行，1 test）：S2/S3 userIntent+CONTINUE+keyword；S4 `skill.writing` + WAITING_HUMAN + BRIDGED；S5 pack DRAFT item 内容 == seed，Planner/Writer prompt 均含【本章当前稿】+ seed 正文；S6 ToolCallLog 有 `get_latest_draft`；S7/S9/S10 workingDraftId / artifactId / `diff.hasChanges`；S11 Gate PENDING；S12 history +1；S13 `canonicalRead` == resultingDraftId 且内容 == writerContent；S14 WM `replay=true` + ACCEPTED + resultingFactId。**测试名自带边界**（"leaves rounds 15 to 17 unproven"）。application **1129/0**、root **1598/0**、desktop 47/0。**不得据此宣称 Phase 8。**
- [x] `T-8-1` 17 段完整纵切 E2E — **DONE（2026-10-28）**
  `Phase8FullLoopE2ETest`（403 行，单测试按 S1→S17 顺序）：S1 作者偏好进两处 prompt；S2 userIntent 原样且进 rawText；S3 CONTINUE+关键词；S4 writing/WAITING_HUMAN/BRIDGED；S5 pack DRAFT item == seed 且两处含当前稿；S6 ToolCallLog 有 `get_latest_draft` 且在 white list；S7/S8 WorkingDraft 内容==writer；S9 diff.hasChanges；S10 Artifact 绑 WorkingDraft；S11 Gate PENDING；S12 history +1；S13 canonical == resultingDraftId 且内容==writerContent；S14 WM `replay=true` + ACCEPTED + resultingFactId；S15 第二次 assembly 的 prompt 含 S14 Event；S16 收敛轮 CONSISTENT（≠REVIEW_FAILED）+ 新 session；S17 扁平字段全可读。**R1** history 恰 +1、Canonical 只前进一次；**R2** S16 前后四类计数相等；**R3** 两次 pack 的 packId 与 items 均不同。application **1143/0**、root **1612/0**、desktop 49/0（合计 1661，与逐模块求和一致）。
- [x] `T-8-2` Phase 8 验收（§12 四条）— **DONE：GATE_PASS（T-8-2R，2026-10-28）**
  首次审查 GATE_FAIL（段 4 △ + P7 slice PARTIAL）→ T-7-3/D-36 补齐 → 复审：①17 段 17✓/0△/0✗；②三边界绿；③禁止项零引入；④P0–P7 slice 全绿。规划层亲验：V1/V2 同为 `request(w, analysisIntent)` 仅换 `PASS_JSON`/`ERROR_JSON`；`Phase8FullLoopE2ETest` XML 18:30:32 > `NovelAgent.kt` 18:28:47。限制见 O-29(2)/O-30/31/37/38/39/42/43 与"不证明"清单。
  首次 FAIL 的判据保留追溯：①段 4 为 △（缺 Result 驱动）、④P7 slice 为 PARTIAL（O-11）——两者已由 D-36/T-7-3 关闭。复审要点：**段 4 purpose 说明**——续步由 `skillRegistry.get(...)` 直接取，不经 `purposes` 选择，属 D-36 既定设计且仍过 Guard（V5 反证），不影响 ✓；**Phase8FullLoopE2ETest 未被改写**（mtime 17:53:09）且在 T-7-3 之后的 18:30:32 XML 中 1/0。**GUI 的 T-7-2 不属 §21.2 P7 slice，也不属 17 段**，故不降级为 WITH_LIMITATIONS，但仍在"不证明"中保留。
- [x] `T-7-3` **O-11 决策点（D-36）** — **DONE（2026-10-28）**
  `NovelAgent.kt:238-292`：`SkillOutcome.Analysis` 后按 `errorCount` 决策——0 → `COMPLETED`（只读，`analysis` 入结果）；>0 → 取 `skill.rewrite-revision`、校验 `enabled`、记 `followUpSkillId`，**再走同一个 `executeSkill`**（含 `SkillExecutionGuard`）执行一次续步，要求 `Draft` 否则类型化失败；随后沿用既有 REVIEW → ActionPolicy → Bridge/Gate。有界 1 次、无循环。结果新增最小字段：`NovelAgentAnalysisSummary(passed/issueCount/errorCount)`、`analysis`、`followUpSkillId`。
  测试：`DecisionPointAnalysisFollowUpTest` 4/4（V1 PASS→COMPLETED 且只读；V2 同一请求 ERROR→WAITING_HUMAN + WorkingDraft/Artifact + `followUpSkillId`；V3 EXECUTE 恰 2 次；V5 空 bindings → `SKILL_NOT_EXECUTABLE` 反证守卫未被绕过）；既有 `DecisionPointBehaviorTest.A7` 按 D-36 强化（null→notNull，summary 三条断言保留）。application **1147/0**、root **1616/0**、desktop 49/0（合计 1665）。

- [x] `T-42` **O-42 跨章续写** — **DONE（2026-10-28）**
  `NovelAgent.resolveContinuation`：同 Novel+同 Variant 取紧邻前章 → `canonicalRead.committedDraft` → 仅 CONFIRMED 构造 `ContinuationReference` → 复用 `ContinuationResolver` → 4 参 `assemble`；Planner 1200 / Writer 4000 摘录由 2A 原样生效。**NL 降级不静默**：四种原因写入 `continuationIncluded` / `continuationSourceChapterId` / `continuationAbsentReason`。5 新测试 V1–V5；V-6 回归 `WorkflowContinuationE2ETest` 7/7、`ContinuationReferenceTest` 13/13（工作流硬拒未改）。application **1152/0**（+5）、标准聚合 root **1621/0**、desktop 49/0。

### 4.6 并行决策 / 治理
- [x] `T-V0` 词库现状只读盘点 — **DONE（2026-10-28）**
  16 项缺口（E1–E16）+ 渲染矩阵 + §11A/v0.1 差异 + V1-A/B 成本表。关键：`scope_level` 不是查询键（`Vocabulary.sq:12-16` 只按 variant_id/novel_id 过滤）；VARIANT 不合并 NOVEL（`ContextSources.kt:309-313`）；`VocabularyRule` 无生产消费者；`search_vocabulary` 查的是候选而非正式词条。
- [x] `T-V1` 词库归属 — **DONE：V1-A**（承认现行独立域，修订 §11A；见 D-34 与 `qianyan-vocabulary-ownership-decision.md`）
- [x] `T-V2` 作用域映射 — **DONE：V2-①**（NOVEL + VARIANT 两轴；VARIANT 继承 NOVEL；`scope_level` 降为标注；GLOBAL/TASK 未启用；不做 BOOK/SERIES/AUTHOR）
- [x] `T-V3` MVP 范围 — **DONE：V3-A**（只做别名收敛进 prompt + 预算一致性；Rule/Critic/TEMPLATE/UI 不做）
- [x] `T-V3-A` 实施：Planner/Writer 渲染别名 + 修 Context 预算不一致 + 测试 — **DONE（2026-10-28）**
  共享 `VocabularyLite.toPromptLine()`（`PlanningContext.kt:99-105`：别名保持顺序、过滤空串、两处逐字一致）；`PlannerAgent.kt:155-158` 与 `WriterAgent.kt:157-160` 都改用它。TDD 先红（`VocabularyPromptIntegrationTest` 1 failed）后绿；新增集成测试（Planner/Writer 别名格式、无别名旧格式、空别名过滤、content vs prompt 一致）。application **1118/0**（+1）、root **1587/0**、desktop 47/0。
- [x] `T-V2-A` 实施：`VocabularySource` 的 VARIANT 分支合并 NOVEL — **DONE（2026-10-28）**
  `ContextSources.kt:307-339`：`activeVariantId == null` 保持原逻辑；非空走 `mergedApprovedEntries`（两边只取 APPROVED，NOVEL 先入、VARIANT 同 canonical 覆盖，最终 `(canonical, entryId)` 排序）。legacy `PlanningContextAssembly` 仅加注释（无生产调用方，`NoPackAssemblyGuardTest` 3/0 佐证）。TDD 先红（V-2 缺 NOVEL 基座 / V-4 集合不符）后绿；新增 `VocabularyVariantInheritanceTest` 6 测试。application **1124/0**（+6）、root **1593/0**、desktop 47/0。
- [x] `T-W1` 四个工作树：冻结归档（manifest + patch）— **DONE（2026-10-28，仅归档，未清理）**
  归档目录 `D:/qwenyan-v4/backup/w1-20261028-192312/`：4 patch + 4 untracked tar.gz + 4 空 log + MANIFEST（12 个导出文件哈希 12/12 一致，规划层抽验 4 个哈希吻合）。四树 HEAD 均 `be1b755`、**无 HEAD 之外新 commit**；实测未提交 **59 个展开文件**（A 21 / B 9 / C 22 / D 7）——控制层旧记的"52"是历史统计，以此为准。31 个与 MAIN 同名不同内容的文件已在 MANIFEST 列为高风险，声明"不得直接 apply"。
  **未删除 / 未移动 / 未合并 / 未复制 / 未 prune**；MAIN 与四树前后状态逐字节一致。
- [x] `T-G1` 提交拆分清单（只读，不执行）— **DONE（2026-10-28）**
  盘点：152 porcelain → 展开 **172 文件**（106 tracked 变更 + 66 新文件）；无 .sq/.sqm、无密钥、无 build/IDE 产物、无 >1MiB 新文件。
  拆分：提交 0（纯视觉 4 文件，可选）→ **提交 1（141 文件，必须单批）** → 提交 2（Desktop U 5 文件，可选）→ 提交 3（`docs/audit` 7 文件，建议）→ 提交 4（docs 8 文件）→ 提交 5（`docs/project-control` 7 文件，HOLD 已解除）。
  必须单批的强耦合：`ApplicationError` ↔ Android 两处穷尽 mapper；`NovelAgentOutcome.CANDIDATE_UNBRIDGED` ↔ `Common.kt`；Gateway 签名 ↔ Android fakes；legacy 删除 ↔ TaskRunner/WriterUseCases/CommitUseCases + 5 测试；共享 hub 单文件混合多阶段。
- [x] `T-G1-S` 提交前修 Desktop 候选操作 sessionId 缺口 — **DONE（2026-10-28）**
  `Common.kt:347`：`lastAgentRequest = result.sessionId?.let { request.copy(sessionId = it) } ?: request`（null 不编造）；`DesktopAgentApprovalUiTest` 新增行为级测试（`DesktopOfflineLlmGateway` + 真实 NovelAgent + 临时 DB，逐一断言 bridgeCandidate/refreshCandidate/reject 不再 `SESSION_REQUIRED`）+ 源码行断言。桌面 47/0、root 1576/0，XML 00:24–00:26Z > 源码 00:24:41Z；porcelain 仍 152、staged=0。
  注：报告称"本轮未新增代码改动、仅复核"，与同报告的"修复前 2 failed → 修复后 5/5、旧赋值断言已同步"自相矛盾（表述问题，不影响结论）；另源码文本断言较脆，属辅助手段。
- [x] `T-G1-C` 执行提交 — **DONE（2026-10-28）**
  6 批全部落本地：`c2c3681`(4) → `0530e2b`(141) → `77c5718`(5) → `5466f98`(7，D-32 接受) → `0919b86`(8) → `e3be34c`(7)。**六批文件数合计 172 = 清单全量**（独立一致性证明）；porcelain 0、staged 空、HEAD `e3be34c`、本地领先 `5b047b0` **8 commits**、**未 push / 未 tag / 未 amend / 未 reset**。
  提交 4/5 的 gate 均为 `set -e` + 短路，`--check` exit 0、敏感扫描 0 命中、staged 集合 MATCH；控制层四处状态一致（README / roadmap / feature-status / 任务树）。
  残留：远端 live 状态因网络不可达未复核（`ls-remote` exit 128），仅能证明本地未 push。
- [x] `T-G2` 提交第二批（本批 13 项）— **DONE（2026-10-28）**
  4 批：`2ab6da3`(3，T-7-1 测试) → `cdbbf14`(4，V3-A 别名进 prompt) → `dd9fb41`(3，V2-A 变体继承) → `42320c3`(3，D-34 文档+任务树)。**13 文件全覆盖**；HEAD `42320c3`、本地领先 **12**、porcelain 0、staged 空；`§11A` 仅 6 行新增（无删除）；**远端 live 复核成功 = `5b047b0`**（网络已恢复），未 push。
- [x] `T-G3` 提交第三批（Phase 8 收口 19 项）— **DONE（2026-10-28）**
  6 批：`fee163d`(6，当前稿+世界模型进 prompt) → `e549410`(1，Partial E2E) → `c69e7cb`(6，显式收敛评审轮) → `c8e5c89`(1，十七段 E2E) → `e0a0926`(4，analysis 结果驱动续步) → `cfff011`(1，任务树)。**19 文件全覆盖**；HEAD `cfff011`、领先 **18**、porcelain 0、staged 空。
  **关键改进**：前 5 批是"近似验证"（工作树仍脏）；第 6 批后执行 **clean-tree `--rerun-tasks` 全量**（root 1616/0 + desktop 49/0，合计 1665）——这是本轮唯一一次对 HEAD 的真验证。
  残留：远端 live 未复核（网络不可达），只能说本地领先 18，不能断言远端未变。
- [x] `T-G4` 提交 O-42 本批（7 项）— **DONE（2026-10-28）**
  2 批：`af4b0bd`(6，O-42 代码+测试) → `5237033`(1，任务树)。HEAD **`5237033`**、porcelain 0、staged 空、领先 **2**；clean-tree `--rerun-tasks` 全量 **1621/0** + desktop **49/0**（合计 1670）。未 push。
  **口径更正**：`runtime/api` / `runtime:dsh` **不在 `settings.gradle.kts`**（只有 `:agent:runtime`、`:runtime`），此前执行的1703混入了 10-06 的遗留 XML —— 此后总数只算聚合模块，遗留 XML 不得引用。
- [x] `T-43` **O-43 续步后复评** — **DONE（2026-10-28）**
  `NovelAgent.kt:1189-1205` `reviewRevisedContent`：续步 Draft 后 `critic.critiqueContent(修订稿)` 一次，
  写 `postRevisionAnalysis`；失败写 `postRevisionCheckFailure`（INVALID_OUTPUT / CALL_FAILED）且任务继续。
  **D-37 副产物**：为守住"critique ≤ 2"，续步不再重复 critique 同一基线，初次 analysis 的 `ValidationResult`
  直接作为 Revision 输入（`executeSkill(..., critiqueOverride)` → `reviseExistingContent` `:1140`）。
  三条路径次数：无续步 = 1 critique / 0 revision；续步 = 2 / 1；复评失败 = 2 / 1 且任务继续。
  新增 `PostRevisionReviewTest` 4/4；既有 `DecisionPointAnalysisFollowUpTest` 4/4（仅两条说明文字改为"复评"，计数不变）。
  application **1156/0**（+4）、聚合 root **1625/0**、desktop 49/0（合计 1674）。
- [x] `T-G5` 提交 T-43 本批（5 文件）— **DONE（2026-10-29）**
  2 批：`2124635`(4，复评 + 模型字段 + 测试；+259/−7) → `d9aa385`(1，任务树；+20/−5)。HEAD **`d9aa385`**、porcelain 0、staged 空、`origin...HEAD = 0 4`。
  clean-tree `--rerun-tasks`：root **1625/0** + desktop **49/0** = **1674/0/0/0**；XML 00:17:03–00:19:08，晚于 commit 00:15:30/00:16:23 与源码 mtime。
  `:app:android` 120 = `testDebugUnitTest` 60 + `testReleaseUnitTest` 60（两变体，均 0 fail）；只算 settings 模块，未引用 `runtime/api` / `runtime:dsh`。
  远端 live 复核：规划层审计时 `ls-remote` 成功 = **`cfff011`**（执行层报告为两次 Connection reset），本轮未 push、tag 0。
- [x] `T-PUSH` 推送 18 个本地 commit — **DONE（2026-10-28）**
  推送输出 `5b047b0..cfff011  feature/novel-ide -> feature/novel-ide`（fast-forward，非 force）；第 1 次网络失败、第 2 次成功。**远端 live = cfff011**（执行层 ls-remote 独立确认；规划层本轮 ls-remote 因网络未复核，但本地 tracking ref 已被成功 push 更新为 cfff011 + `rev-list origin...HEAD = 0 0`）。未 force / 未打 tag（前后均为 0）/ 未改配置；任务树 M 未被夹带（推送后仍是未提交 M）。
- [x] `T-PUSH-2` 推送 G4/G5 四个本地 commit — **DONE（2026-10-29）**
  命令 `git push origin feature/novel-ide`，**第 1 次即成功**：`cfff011..d9aa385`（fast-forward，非 force）。推送后 HEAD `d9aa385`、`origin/feature/novel-ide = d9aa385`、`0 0`、tag 0；工作区仍仅任务树 M（未夹带）。
  规划层复核：本地 `git ls-remote` 两次网络失败（Empty reply / 连接超时），改用 **GitHub API** 独立确认 `feature/novel-ide` head = `d9aa385`、该 commit 仅 1 文件 +20/−5、远端 tag 列表为空。
- [x] `T-LEGACY` `commitApprovedDraft`：删除或补齐 Gate 绑定 — **DONE（2026-10-29，已删除）**
  `CommitUseCases` 中已无 `commitApprovedDraft`；`LegacyDraftWriteBoundaryTest:144` 断言不得恢复；AI 提交只能走正式 `commit(...)`。本单元仅收口任务树，未改代码。

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
| 2026-10-28 | **G1 收口**：T-G1-C → DONE；6 批全部落本地（`c2c3681`/`0530e2b`/`77c5718`/`5466f98`/`0919b86`/`e3be34c`），工作树 clean，本地领先 8，未 push；下一单元 Phase 7 / T-7-1 | 规划层独立复核（非自报）：六批 `--numstat` 文件数 4+141+5+7+8+7 = **172**，与 G1 清单全量相等；porcelain 0、staged 空、`rev-list origin...HEAD = 0 8`；控制层四处（README:28/30、roadmap:24、feature-status:112-117、任务树 Phase 6 行）均含 GATE_PASS_WITH_LIMITATIONS；远端 live 未复核（网络 128），仅证明未 push |
| 2026-10-28 | T-7-1-0 审计通过 → DONE；裁 D-33（7 项语义）、新增 O-34/O-35；T-7-1 拆为 A/B/C；确认工作树 `M 任务树` 是规划层更新非异常 | 规划层复核执行层枚举：40 个分支对（S/C/E/R/G/D 六组）；已覆盖 19 / 缺一支 9 / 完全无 6；`ActionPolicyUseCases.kt:44` 确为 final class（"Denied/Allowed 不可达"成立）；`DecisionPolicySource` 无 PREFERENCE（上轮提示词口径错误，已勘误）；`git status` 仅 `M docs/project-control/qianyan-task-tree.md` +9/−8 = 规划层本轮更新 |
| 2026-10-28 | T-7-1-A/B/C 全部 DONE → **T-7-1 收口**；新增 O-36（R3/R4 无可注入缝隙）；application 1117/0（+10）、root 1586/0、desktop 47/0 | 规划层复核：新增 10 测试均为行为级（A4 断言 CommitHistory 条数不变 + Canonical 内容不变；A6 断言无新增 Commit 且 session 未 CANCELLED；A5b 断言 CANDIDATE_UNBRIDGED + CANDIDATE_APPROVED_NOT_COMMITTED + 0 commits；A7 断言 COMPLETED + summary 含 passed=false/问题1项/ERROR 1 + 不写 Canonical；A1–A3 断言 outcome + RULE_MATRIX；A8 断言 PASS + 空 findings/missingInputs）；`git status` 仅 3 测试文件 + 规划层任务树 M；生产零改动；A9 无 seam 属实（`WorkingDraftUseCases`/`ChangeUseCases` 非接口非 open） |
| 2026-10-28 | 用户选定下一单元为 **V1–V3 词库裁决**；新增 `T-V0` 只读盘点作为裁决前置；T-7-1 收口（A/B/C 全 DONE） | 规划层初查（待 T-V0 补全）：`VocabularyModels.kt` 有 `VocabularyScopeLevel{GLOBAL,NOVEL,VARIANT,TASK}`、`VocabularyEntry.aliases`、`VocabularyRule`；`ContextSources.kt:119-120` 渲染 aliases，但 `PlannerAgent.kt:158-159` 与 `WriterAgent.kt:158-159` 只渲染 `canonical -> replacement` ⇒ 别名在 Agent prompt 被丢弃；`VocabularyRule` 无生产消费者 |
| 2026-10-28 | T-V0 审计通过：T-V0/V1/V2/V3 → DONE；裁 **D-34**（V1-A / V2-① / V3-A，FINALIZED）；新增 `docs/planning/qianyan-vocabulary-ownership-decision.md` 并在 §11A 加指针（仅追加）；新增 O-37/O-38；下一步 T-V3-A 实施 | 规划层复核 16 项缺口：`Vocabulary.sq:12-16` 不按 `scope_level` 过滤（标签非查询键）；`ContextSources.kt:309-313` VARIANT 不合并 NOVEL；`VocabularyRule` 仅模型+备份读取、无 application 消费者；`ProductTools.kt:249-270` `search_vocabulary` 查候选不筛状态；`CritiqueAgent`/`RevisionAgent` 无 vocabulary 输入。文档冲突属实：§11A:496 禁止 vs FD-6:64-66 / J-1:67 / AC-30:129-136 → 按"后形成的冻结/裁决优先于规划条文"裁 V1-A |
| 2026-10-28 | T-V3-A 审查通过 → DONE：共享 `toPromptLine()`，Planner/Writer 均渲染别名；预算不一致消除；application 1118/0（+1）、root 1587/0、desktop 47/0 | 规划层实测：`PlanningContext.kt:99-105` 空串过滤 + 别名原序 + `、` 分隔；无别名 `- 词 -> 替`、有别名 `- 词（别名：a、b）-> 替`，与 brief 逐字一致；`PlannerAgent.kt:157` 与 `WriterAgent.kt:159` 均 `appendLine(it.toPromptLine())`；`VocabularyPromptIntegrationTest` 4/4（新增 :114）；`VocabularySource.content` 未改动（`ContextSources.kt:117-132`）；TDD 先红后绿属实 |
| 2026-10-28 | T-V2-A 审查通过 → DONE；**词库线收口**（V1/V2/V3/V3-A/V2-A 全 DONE，仅余 O-37/38/39 登记）；新增 O-39；application 1124/0（+6）、root 1593/0、desktop 47/0 | 规划层实测：`ContextSources.kt:322-339` `mergedApprovedEntries`（NOVEL 先入 → VARIANT 同 canonical 覆盖 → `(canonical, entryId)` 排序，两边均 APPROVED）；`activeVariantId==null` 分支与改前一致；legacy `PlanningContextAssembly.kt:202-204` 仅注释且 `NoPackAssemblyGuardTest` 3/0 佐证无生产调用；V-2/V-4 修复前为红（TDD 诚实）；`VocabularyVariantInheritanceTest` 6/6；未改 SQL/scope_level/Schema |
| 2026-10-28 | **G2 收口**：T-G2 → DONE；4 批提交（`2ab6da3` 3 / `cdbbf14` 4 / `dd9fb41` 3 / `42320c3` 3），13 文件全覆盖；HEAD `42320c3`、领先 12、工作树 clean、staged 空 | 规划层独立复核：`git show --name-only` 四批集合与预期逐一相等（3/4/3/3）；`git status --porcelain` 空；`rev-list origin...HEAD = 0 12`；`§11A` staged diff 仅 6 行新增、无删除；**远端 live 复核成功**：`git ls-remote origin refs/heads/feature/novel-ide` = `5b047b0`（exit 0，网络已恢复），确认未 push。注：批 3 的全量/桌面为 cache/UP-TO-DATE——因其内容与提交前已验收的工作树完全一致，属有效证据，但不是 fresh rerun |
| 2026-10-28 | T-8-0 重审通过 → DONE；Phase 8 → PARTIAL（✓8/△8/✗1，5 个断点）；新增 T-8-A/C/D/E 与 T-8-1A；下一单元 T-8-A（5→7 当前稿进 prompt） | 规划层复核关键事实：`PlanningContext` **无当前稿字段**（`PlanningContext.kt:32-60`），`NovelAgent.writingRequest:1243` 的 `rawText = request.userIntent`（用户指令，不是正文）⇒ NL 写作路径看不到本章现有正文；`get_latest_draft` 只取 draftId（`:1190-1223`）；9/10 确为生产链（`ChangeUseCases.computeChange:131`、`WriterFacade.proposalOf:90-98`、`CommitUseCases:176/557`）；14 已由 Commit 触发（`:758-765`）；两条入口（NovelAgent vs 旧 Workflow）并存但不互斥，单条 17 段 E2E 必须选 NovelAgent 作 2–4 段入口 |
| 2026-10-28 | T-8-A 审查通过 → DONE：当前稿经 Pack 进 Planner/Writer prompt；application 1128/0（+4）、root 1597/0、desktop 47/0；下一单元 T-8-1A | 规划层实测：`PlanningContext.kt:49` `currentDraft` 与 `sourceFinalDraft` 分开；`CurrentDraftLite.toPromptSection()`（:111）单一格式；`PlanningContextAssembly.kt:102-109` 仅 `source==DRAFT && ref.chapterId==focus` 取 pack item、无则 null 不回退；Planner:151 / Writer:154 同函数；V-4 夹具 pack=旧稿 / repo=新稿，prompt 只含旧稿（来源纪律成立）；`CurrentDraftPromptTest` 4/4；未动 O-32/sourceFinalDraft/Schema |
| 2026-10-28 | T-8-1A 审查通过 → DONE（部分闭环，非 Phase 8）：S2–S14 全 PASS，段 14 WM replay=true + ACCEPTED；application 1129/0（+1）、root 1598/0、desktop 47/0；下一单元 T-8-C | 规划层实测 `Phase8PartialLoopE2ETest`（190 行）：S5 断言 pack DRAFT item 内容 == seed 且两处 prompt 含该正文（T-8-A 回归锁）；S12 `historyBefore + 1`；S13 `canonicalRead.committedDraft.draftId == mainHistory.resultingDraftId` 且内容 == writerContent；S14 `outcome.replay==true` + accepted single + resultingFactId 非空；测试名自带 "leaves rounds 15 to 17 unproven"；XML 1/0/0/0 |
| 2026-10-28 | T-8-C 审查通过 → DONE（14→15 打通）；新增 O-40（第二次 Agent 产品语义未定义）→ T-8-D BLOCKED；application 1134/0（+5）、root 1603/0、desktop 47/0 | 规划层实测：`worldModelPromptSection()`（PlanningContext:113-190）四类节 + 各 20 上限 + `truncated=true; total=N`；Planner:145 / Writer:135 同函数；**原【相关人物】内容（name+goals）无损失**（仅标题改为【人物档案】）；V-6 用"assemble 后新增 Event B、prompt 不含 B"证明单快照；V-1 第二轮 prompt 含第一轮 Commit 的 Event（14→15 成立）。T-8-D 因 §21.1 仅一句"基于新 Context 收敛"、无输入/触发/输出定义 → 按"不用现状反推动机"停，交用户裁决 |
| 2026-10-28 | 用户裁决段 16 = 显式"收敛评审轮" → 新增 D-35、O-40 RESOLVED；T-8-D/T-8-E 解锁（只读第二轮 + 结构化复审结果） | 用户选择：Commit 后显式触发；第二轮只读、用新 Context 复审已提交正文，输出 findings/suggestions，不写正文、不自动进 Gate、轮次上限 1；实现为一次新的 `NovelAgent.run`（新会话 + 只读 Skill）并与 `sourceCommitId` 绑定 |
| 2026-10-28 | T-8-D 审查通过 → DONE（15→16 显式收敛评审轮）；application 1138/0、root **1607/0**、desktop 48/0；下一单元 T-8-E | 规划层实测：`ConvergenceReviewUseCase.review` 先验 `committedByArtifact` + novel/chapter 归属（`EntityNotFound`），再以**无 sessionId** + `preferredSkillId=character-consistency` 调 `novelAgent.run`，不重建 Pack；Desktop `launchConvergenceReview` 四重门控 + 按钮仅 `commitId != null`；5 个新测试名义与内容一致。**勘误执行报告**：其"总 1425"只加了 application+storage+runtime+android，漏 core/model 66、core/engine 41、provider 35、agent 39、e2e 1 ⇒ 正确 root = **1607/0**（逐模块 XML 实测） |
| 2026-10-28 | T-8-E 审查通过 → DONE（16→17 契约：四态 + 扁平化 + 单一映射）；**O-32 改判**为命名问题（R3 允许当前章用 draft），真实缺口另立 O-42（NL 多章续写缺前章 Canonical）；application 1142/0、root **1611/0**、desktop 49/0；下一单元 T-8-1 十七段 E2E | 规划层实测：`convergenceOutcomeOf` 仅 `COMPLETED && characterConsistency!=null` 进三业务态，其余 REVIEW_FAILED；Result 11 字段扁平；8 个测试含 `assertNotEquals(CONSISTENT)` 与 null 映射；UI 不再访问嵌套 result。**再次勘误执行报告**："总计 1478" 仍漏 core/model 66 + core/engine 41 + provider 35 + agent 39 + e2e 1 = 182 ⇒ 正确 root = **1611/0**。R3 复核结论：当前章基线用 latest draft 合规（"当前章 Draft"），故 O-32 非缺陷 |
| 2026-10-28 | T-8-1 审查通过 → DONE：十七段 E2E 单测试 S1–S17 全 PASS + R1/R2/R3；application 1143/0、root 1612/0、desktop 49/0（合计 1661，逐模块求和一致，本次数字口径正确）；下一单元 T-8-2 Gate 验收 | 规划层实测 `Phase8FullLoopE2ETest`（403 行）：S16 `assertEquals(CONSISTENT)` + `assertNotEquals(REVIEW_FAILED)`；R2 在 review 前后取四类计数；R3 双严格（packId 与 items 均不同）；R1 history 恰 +1 且 Canonical 只前进一次；S14 `replay=true` + 单候选 ACCEPTED + resultingFactId；S15 第二次 pack 的 prompt 含 S14 Event。**不得以"E2E 绿"替代 T-8-2 验收** |
| 2026-10-28 | **T-8-2 Gate 审查完成 → GATE_FAIL**（审查本身 DONE）；Phase 8 → GATE_FAIL；O-11 重新确认 PARTIAL 并写明判据；新增 T-7-3；**规划层自纠**：T-7-1 的 10 个测试不满足 v3.1 §20-12 的"同一 Task Result X→A / Result Y→B"，T-7-1 收口 ≠ O-11 关闭 | 执行层只读 Gate 报告 + 规划层复核原文：v3.1:841 四条；v3.1:796 "Agent Decision Point 存在且可测——至少包含同一 Task，Result X → A / Result Y → B 行为测试一条；**缺少该测试即视为 P7 未完成**（防止旧固定 Pipeline 换名）"；v3.1:965 `P7 → P8` 硬依赖。段 4 现状仍是固定七相位 + 确定性 Skill 偏好表（`NovelAgent.kt:191-360`、`:805-849`），T-7-1 测试只覆盖持久化 Gate 状态分支 ⇒ 条件①④均不满足，②③满足。**GATE_FAIL 判定正确，不得用 O-11 登记替代 Gate** |
| 2026-10-28 | 用户裁决 O-11 形态 = 真实结果驱动 → 新增 D-36；T-7-3 定义为"analysis Result（PASS/ERROR）决定是否再执行一次 rewrite-revision，有界 1 次"；Phase 8 待 T-7-3 后重 Gate | 用户选择：不采用"既有数据分支交差"，也不改 v3.1 范围；决策点必须是 Result 驱动且可写 X→A / Y→B 两条行为测试；复用既有 ANALYSIS_CRITIQUE（保持只读）与 REWRITE_REVISION（写 WorkingDraft），不新增 Skill |
| 2026-10-28 | T-7-3 审查通过 → DONE；**O-11 → DONE**（D-36 真实结果驱动已落地）；Phase 7 → PARTIAL（仅剩 T-7-2 GUI）；application 1147/0、root 1616/0、desktop 49/0（合计 1665）；下一单元 **重跑 T-8-2 Gate** | 规划层实测：`NovelAgent.kt:238-292` 按 `errorCount` 分支，续步走**同一个 `executeSkill`**（含 Guard）且要求 `Draft`；`NovelAgentModels.kt:129/158/160` 三字段最小；`DecisionPointAnalysisFollowUpTest` V1/V2 使用**同一请求**只换 critic Result（X→A / Y→B 成立）；V3 `EXECUTE` 恰 2 次（有界）；V5 空 bindings → `SKILL_NOT_EXECUTABLE`（守卫反证）；A7 适配为强化（null→notNull，summary 三断言保留）。记录：续步不经 `purposes` 选择，是 D-36 既定设计 |
| 2026-10-28 | **T-8-2R 复审 → GATE_PASS；Phase 8 → DONE**：①17 段全 ✓（段 4 由 D-36 补齐）；②三边界绿；③禁止项零引入；④P0–P7 slice 全绿（O-11 DONE）。新增 O-43（续步后无对修订稿复评，质量增强不阻塞） | 规划层亲验：V1/V2 均为 `request(w, analysisIntent)`，仅 fixture 由 `PASS_JSON`→`ERROR_JSON`（同一请求只换 Result）；`Phase8FullLoopE2ETest` XML 18:30:32 > `NovelAgent.kt` 18:28:47、`NovelAgentModels.kt` 18:27:47（证据时效成立）；17 段 E2E 未被改写（mtime 17:53:09）且在 T-7-3 后运行。**判定边界**：GUI（T-7-2）不属 §21.2 P7 slice 也不算 17 段，故不降级；"再评审"仅指续步路径内对基线的 critique，**不是**对修订稿复评 → 记 O-43 |
| 2026-10-28 | **G3 收口**：T-G3 → DONE；6 批提交（`fee163d`/`e549410`/`c69e7cb`/`c8e5c89`/`e0a0926`/`cfff011`），19 文件；HEAD `cfff011`、领先 **18**、工作树 clean；**clean-tree `--rerun-tasks` 全量 1665/0 + 桌面 49/0（真 HEAD 验证）** | 规划层独立复核：`git show --numstat` 六批 6+1+6+1+4+1 = **19**；批 1 文件清单与预期逐字一致；`porcelain=0`、`staged=0`、`rev-list origin...HEAD = 0 18`；逐模块 XML 求和 root 1616 + desktop 49 = 1665。远端 `ls-remote` 网络失败 → **明确记为未复核**，不得推断远端未变 |
| 2026-10-28 | **W1 收口**：T-W1 → DONE（仅归档，未清理）；产出 `backup/w1-20261028-192312/`（12 导出 + MANIFEST）；四树未提交实测 **59** 展开文件（旧记 52 作废）；31 个高风险同名文件已登记 | 规划层独立复核：目录 13 个文件、字节数逐一相符；抽验 `tracked-a.patch` / `untracked-c.tar.gz` / `worktree-d.log` / `MANIFEST.md` 的 SHA-256 与报告一致；四树 HEAD 均 `be1b755`、pending 21/9/22/7；MAIN porcelain 1（规划层任务树 M），HEAD `cfff011` 未变。**清理工作树仍需用户逐条授权** |
| 2026-10-28 | **PUSH 完成**：T-PUSH → DONE；`feature/novel-ide` 已推送至远端 `cfff011`（18 个 commit 全部上云）；执行层 ls-remote 独立确认、`0 0`、tag 0、未 force/未改配置；第 1 次网络失败、第 2 次成功 | 规划层复核：本地 tracking ref `origin/feature/novel-ide = cfff011`（由成功 push 更新）+ `rev-list 0 0`；规划层本轮 ls-remote 因网络（Empty reply）未独立复核，**以执行层 push 输出 `5b047b0..cfff011` + 其 ls-remote 为准**，待网络稳定可再抽验。任务树 M（规划层更新）仍在工作区，随下次文档批提交 |
| 2026-10-28 | **O-42 收口**：T-42 → DONE、O-42 → DONE；NL 跨章续写接入前章 Canonical（降级不静默，工作流硬拒未改）；application 1152/0（+5）、标准聚合 root **1621/0**、desktop 49/0 | 规划层实测：`NovelAgent.kt:1066-1112` 按四种原因降级；`:1502-1504` 三字段写入 Result、`NovelAgentModels.kt:188-192`；5 个新测试 V1–V5；V-6 回归 7/7 + 13/13。**数字口径提醒**：执行层"1703"把 `runtime/api`(5) 与 `runtime/dsh`(28) 也加进来了——这两个模块**不在 `./gradlew test` 聚合**，标准口径应为 root **1621** + desktop 49；后续报告统一用标准聚合，非聚合模块单列 |
| 2026-10-28 | **G4 收口**：T-G4 → DONE；两批 `af4b0bd`(6) / `5237033`(1)；HEAD `5237033`、porcelain 0、领先 2；clean-tree 全量 **1621/0** + 桌面 **49/0**（1670） | 规划层实测：两批 `--numstat` 6+1 = 7；porcelain=0、staged=0、`0 2`；标准聚合逐模块求和 1621 + 49 = 1670。**口径更正**：`settings.gradle.kts` 只有 `:agent:runtime` 与 `:runtime`，**不存在 `runtime/api` / `runtime:dsh`**——此前"1703"混入 10-06 遗留 XML，此后不得引用非聚合模块的旧 XML |
| 2026-10-28 | **T-43 收口**：O-43 → DONE；新增 D-37（analysis 续步的 Revision 复用初次 critique）与 O-44（复评未进 Activity，低优先）；application 1156/0（+4）、聚合 root **1625/0**、desktop 49/0（1674） | 规划层实测：`reviewRevisedContent`（:1189-1205）用 `critic.critiqueContent`；`critiqueOverride` 贯通 `executeSkill`(:968) → `reviseExistingContent`(:1132,:1140)，仅 analysis 续步生效；Result 字段 :197/:199、RunState :1561/1562。**设计变更需记住**：该改法去掉了对同一基线的重复 critique（原为 2 次），否则复评会变成 3 次、违反我 brief 里"≤2"的硬界——这是我 brief 措辞欠精确导致的必要取舍，已由 D-37 固化 |
| 2026-10-29 | **G5 收口**：T-G5 → DONE；两批 `2124635`(4) / `d9aa385`(1)；HEAD `d9aa385`、porcelain 0、领先 4；clean-tree **1674/0/0/0**（root 1625 + desktop 49）；远端 live 复核成功 = `cfff011` | 规划层独立复核：`5237033..HEAD` 恰 2 commit、均单父（无 amend/merge）、tag 0；`--numstat` 4+1=5 文件、+259/−7 与 +20/−5；逐模块求和 1674（`app:android` 120 = 60 debug + 60 release，application 1156；不含 `runtime/api`/`runtime:dsh`）；XML 00:17:03–00:19:08 晚于 commit 00:15:30/00:16:23 与源码；`git ls-remote` 本轮成功，返回 `cfff011`，证明远端未变且本轮未 push |
| 2026-10-29 | **T-PUSH-2 完成**：G4/G5 四个 commit 已推送，`cfff011..d9aa385`（第 1 次成功）；HEAD `d9aa385`、`0 0`、tag 0；任务树 M 未夹带 | 规划层复核：本地 `ls-remote` 两次网络失败，改用 GitHub API 独立确认 `feature/novel-ide` head = `d9aa385`；commit 仅任务树 1 文件 +20/−5；远端 tag 空；工作区仍仅任务树 M（规划层 T-G5/T-PUSH-2 记录，待文档批） |
| 2026-10-29 | **T-7-2 HARD STOP（环境阻塞）**：GUI 验收停在 G1 前置；Session 7 被 `LogonUI.exe` 锁定，`CopyFromScreen` invalid handle，13:23 后点击无效；T-7-2 → BLOCKED（环境），不是 UI FAIL | 规划层复核：HEAD `d9aa385`、`0 0`；工作区仅任务树 M；证据目录 `D:/qwenyan-v4/backup/t-7-2-20261029-0049/` 存在（00:50–13:20 截图）；Provider=MOCK 已确认；3 个重复测试作品待解锁后核实。**恢复条件**：用户解锁并保持前台，回复“已解锁” |
| 2026-10-29 | **T-7-2-RESUME 再次 HARD STOP**：`LogonUI` PID 64620 仍在 Session 7；Qoder/java 均在 Session 7；`activate_window` 仍失败（`foregroundHwnd=263396` ≠ 千言 `591072`）；G1–G8 全 NOT RUN | 规划层复核：`Get-Process LogonUI` 显示 Session 7；Qoder 67960 / java 59736 均 Session 7；HEAD `d9aa385`、`0 0`、工作区仅任务树 M；未点击、未截图、未改数据。**仍待用户本机解锁** |
| 2026-10-29 | **T-O44 立项并进入执行**：GUI 继续 BLOCKED，转入唯一 ready 的非 GUI 项 O-44（复评纳入 Activity）；T-7-2 保持 BLOCKED，不并行 | 任务树选择依据：O-37/O-38 需新契约，O-39 需 Schema，D8 为 62 处测试迁移；O-44 已由 T-43 锁定 `reviewRevisedContent`，允许文件仅 `NovelAgent.kt` + `PostRevisionReviewTest.kt`。HEAD `d9aa385`、`0 0`、工作区仅任务树 M |
| 2026-10-29 | **T-O44 审查 → PARTIAL**：Activity 已落库且 V1/V2/V4、application 1156/0 全绿；但 `state.activityIds` 未追加该 REVIEW id，`NovelAgentResult.activityIds` 缺记录，需一行挂接后再 DONE | 规划层实测：`NovelAgent.kt:1194` start/complete/fail 与 best-effort 均符合；V2/V4 通过 `listBySession` 断言；application XML 1156/0、15:26 晚于源码 15:25；缺口：`step()` 在 `:1486` 会追加 `state.activityIds`，复评分支未追加；`grep` 显示 `NovelAgentResult.activityIds` 是唯一 run 级相位清单，故记 PARTIAL |
| 2026-10-29 | **T-O44 收口**：O-44 → DONE；复评 Activity 已挂接 `state.activityIds`；V1/V2/V4 + DecisionPoint 4/4；application 1156/0；Activity 总数 8，critique/revision 未变 | 规划层实测：`NovelAgent.kt:1196` 紧随 start 追加；V2/V4 均断言 `result.activityIds.contains(...)`；XML 15:32 晚于源码 15:31；`git diff` 仅 2 生产/测试文件，任务树 M 为规划层自身；未 commit/push |
| 2026-10-29 | **T-D8 收口**：O-20 的 D8 → DONE；`allowUnboundGate` 从契约/实现/测试夹具移除；无 stepId Gate 无条件拒绝；代码 grep 0；application 1156/0、desktop 49/0 | 规划层实测：`CommitModels.kt` 仅剩 `gateId`；`CommitUseCases.kt` step==null 直接 `GATE_TARGET_MISMATCH`，复用检查仍在最前；`CommitGateBindingTest` 8/8（stepless 负向 + bound 复用替换）；62 处调用经 bound 夹具；XML 15:55/15:56 晚于源码 15:40–15:47；未 commit/push |
| 2026-10-29 | **T-O38 收口**：O-38 → DONE（D-38）；Critic 别名 WARNING + Revision canonical 偏好；空词库不变；VocabularyAliasDriftTest 7/0；application 1163/0、storage 166/0 | 规划层实测：`approvedVocabulary` 复用唯一合并函数；Critic WARNING 不改 passed、不自动替换；Revision prompt 有/无词库两分支；NovelAgent 三处接线；XML 17:52/17:50 晚于源码 17:44–17:51；未改 Agent/Rule/表/UI；未 commit/push |
| 2026-10-29 | **T-LEGACY 收口**：`commitApprovedDraft` 已删除，无生产入口；任务树登记 DONE（无代码改动） | 规划层实测：`grep commitApprovedDraft` 仅 `LegacyDraftWriteBoundaryTest:144` 的禁止恢复断言；`CommitUseCases` 无该函数；application 1163/0 |
| 2026-10-29 | **T-G6 收口**：两批本地提交 `6ce9a00`(8)/`95c0f30`(8)；HEAD `95c0f30`、staged 空、仅任务树 M；clean-code **1681/0**（root 1632 + desktop 49）；未 push | 规划层复核：`--numstat` 8+8 文件、+453/−22 与 +242/−51；staged 空；`0 2`（相对 d9aa385）；XML 18:02–18:04 晚于源码；任务树按授权未提交；T-7-2 转 READY（桌面已解锁） |
| 2026-10-29 | **T-7-2 18:08 重试 HARD STOP（工具阻塞）**：桌面/HEAD/Provider gate 通过，应用从 `95c0f30` 重启；一次新建后 computer-use MCP `ECONNRESET`，G1–G8 全 NOT RUN | 规划层复核：`LogonUI` 已消失；旧 PID 59736 已关闭；新 PID 75980 窗口存在；`mcp_list` 审计时已恢复、窗口截图可读；`未打开作品` 是动态标题非缺陷；重复创建数量仍未确认，不判 UI 失败 |
| 2026-10-29 | **T-7-2 二次工具 gate HARD STOP**：重试时 `mcp_list` computer-use 0/14；应用 PID 75980 仍运行；未做 UI 操作 | 规划层复核：审计时 `mcp_list` 恢复 14/14；`get_window_state(include_screenshot=false)` 可读空态；判定为 MCP 间歇性崩溃，不是应用缺陷；改走 capture-lite（少截图、PrintWindow 取证） |
| 2026-10-29 | **T-7-2 三次稳定性 gate HARD STOP**：执行会话连续两次 0/14；规划层同一时刻 14/14；判定为**会话级 MCP 连接失效**，非应用/工具全局故障 | 规划层实测：`mcp_list(keyword=computer-use)` 14 工具；`get_window_state(no screenshot)` 成功；应用 PID 75980 空态可读。恢复方式：重开执行会话或重启 Qoder 重新挂载 plugin；不得继续用同一会话重试 |
| 2026-10-29 | **新 P0 O-45**：MCP 已稳定，但作品切换器 9 行点击均无效果；目标作品数据正常；“新建作品…”等对照正常；G1–G8 被阻塞 | 规划层判定：不是 MCP/数据/坐标；根因在 `ProjectSwitcher` 行点击 → `openNovel` 链路或 Compose 命中。新增 T-O45 修复单元；T-7-2 保持 BLOCKED |
| 2026-10-29 | **T-O45 收口**：O-45 → DONE；根因为 main 根作用域未 remember graph/state，写 state.novel 触发重组合重建 state；修复两处 remember + null toast；desktop 52/0 | 规划层实测：`Main.kt` remember；`Common.kt` null toast；`DesktopNovelSwitchTest` 3/0；XML 12:31；手工双作品切换/新建/导入正常；修复未提交（T-G7 待授权）；T-7-2 转 READY |
| 2026-10-29 | **T-G7 收口**：提交 `b703ea0`(3, +118/−4)；HEAD `b703ea0`、staged 空、仅任务树 M；desktop **52/0**；领先 3，未 push | 规划层复核：`--numstat` Main 4/2、Common 7/2、新测试 107/0；`CommitApproved` 未涉及；敏感扫描仅 `FileProviderCredentialStore` 类型名；T-7-2 可在 HEAD `b703ea0` 重启后执行 |
| 2026-10-29 | **T-7-2 首次真实 GUI → PARTIAL**：O-45 回归 PASS；G2–G5/G7 PASS；G1 FAIL（toast 不渲染，O-46）；G6 PARTIAL（失效候选仍可批准、通用错误，O-47）；G8 部分（丢弃/切页未跑，MCP 第二次消失） | 规划层实测：HEAD `b703ea0`、status 仅任务树 M、`0 3`；G2 BRIDGE/任务卡、G3 候选+Diff+按钮、G4 第二候选 UNBRIDGED/1 Gate、G5 retry→拒绝→BRIDGED、G7 双击 1 Commit+历史 1；G6 Gate PENDING/commits=0；未改代码/DB；新增 O-46/O-47 |
| 2026-10-29 | **T-O46 审查 → PARTIAL（加固）**：原“toast 全局不渲染”在 HEAD `b703ea0` 无法复现；三条路径 state/常驻 Text/底部 toast 均显示。保留两项确定性加固 + 2 测试；desktop 54/0 | 规划层实测：`Common.kt` toastJob 取消旧计时器；`Main.kt` toast 层移到浮层之后 + zIndex；`DesktopToastTest` 2/0；手工三路径可见、点击穿透正常；G1 需在最终构建复测 |
| 2026-10-30 | **T-O47 审查 → PARTIAL**：代码/测试完成（candidateLost、RestoreFailure 文案、6/0、desktop 60/0），但 `canReject` 被误加 `pendingProposal != null`，stale PENDING Gate 无法关闭；“去 Agent 重新发起”被 Gate 阻塞 | 规划层实测：`ChapterWorkflowFacade.reject` 不依赖 artifact，G7 已证明重启后可拒绝；`abandonRound` 只接受 RESOLVED+REJECTED，不适用于 PENDING；需改为“只禁批准、保留拒绝”，拒绝后再重新发起 |
| 2026-10-30 | **T-O47-FIX 收口**：O-47 → DONE；只禁批准、保留“拒绝失效提案”；拒绝后普通编辑态才出现“去 Agent 重新发起”；desktop 60/0 | 规划层实测：`canReject = isLoaded && waitingForUser && !busy`；WritePage 失效态按钮/文案；手工 ch3 stale Gate 拒绝成功（REJECTED、commits=1 不变），ch4 正常 Gate 批准新增 1；证据 `t-o47-20261030-1017`；未提交（T-G8 待授权） |
| 2026-10-30 | **T-G8 收口**：提交 `e3b581f`(7, +485/−25)；HEAD `e3b581f`、staged 空、仅任务树 M；desktop **60/0**；领先 4，未 push | 规划层复核：`--numstat` 7 文件；toast job/zIndex + candidateLost/拒绝失效提案 + 2 测试；staged 集合 7、`--check` 0、敏感扫描仅类型名；T-7-2 可在最终 HEAD 重启后完整复跑 |
| 2026-10-30 | **T-7-2-FALLBACK 部分通过（HARD STOP G8）**：Switch 到 PowerShell 真实输入；G1–G7 PASS（含 O-46 G1 toast、O-47 G6 stale reject）；G8 双击拒绝穿透到“继续写”，新建第二 Gate 2688f236 → FAIL | 规划层实测：新建作品恰 1（9→10）；G2–G5/G7 证据与 DB 一致；G8 双击批准/丢弃/切页通过，唯重复拒绝 FAIL；HEAD `e3b581f`、status 仅任务树 M、`0 4`；新增 O-48/T-O48；未改代码/DB（除测试操作） |
| 2026-10-30 | **T-O48 代码/测试完成 → PARTIAL**：500ms 决议防抖 + decisionLocked；双击第二击不再触达 gateway；desktop 63/0 | 规划层实测：`WriterController` nowMillis/cooldown、`generate` 早退、`canGenerate/canRewrite` 锁；测试 63/0；手工 G8 被 LogonUI/输入法 CoreWindow 阻塞，fallback 拒绝输入；待解锁复验 |
| 2026-10-30 | **工作树记录 + 转 O-39**：T-O48 3 M + 1 ?? 快照保存；HEAD `e3b581f`、领先 4、任务树 M；桌面未解锁，T-O48 手工 G8 暂缓 | 快照 `D:/qwenyan-v4/backup/t-o48-worktree-20261030-160600/`（tracked.patch + 未跟踪测试 + status + SHA256 + MANIFEST）；未建议直接 apply。下一单元 O-39 读侧归属校验 |
| 2026-10-30 | **T-O39 收口（读侧）**：O-39 → PARTIAL（read-side DONE）；新增 O-39b 写侧残留；storage 167/0、application 1163/0 | 规划层实测：`findEntriesByVariant(novelId, variantId)` + JOIN `NovelVariant`；ContextSources/PlanningContextAssembly/Android fake 同步；跨作品测试返回空；未提交（T-G10 待授权）；写侧 saveEntry 仍不校验 |
| 2026-10-30 | **T-G10 收口**：提交 `7af66c5`(7, +72/−12)；HEAD `7af66c5`、staged 空、仅任务树 M；storage 167/0、application 1163/0；领先 5，未 push | 规划层复核：7 文件集合与 gate 一致；跨作品测试真实 NovelVariant；android/desktop 编译通过；T-O48 4 文件仍未提交，转 O-39b 写侧 |
| 2026-10-30 | **T-7-2-FINAL 再次 HARD STOP（工具触发点）**：MCP 14/14 启动；应用从 e3b581f 重启；第一次“新建作品”无响应，第二次点击 ECONNRESET → 0/14；G1–G8 全 NOT RUN，未建作品 | 规划层复核：规划层同一时刻 14/14；应用 PID 85148 仍运行；novels=9/PENDING=1/commits=3 与启动基线一致；**触发点收敛为“新建作品弹窗动作”**，非应用缺陷；建议 PowerShell 真实输入 fallback 或换 Qoder 会话 |
| 2026-10-30 | **T-O39b 收口**：O-39b 应用层 DONE；`saveEntry` 归属守卫 + `confirmCandidate` 统一入口 + DI；新增 5 条写入归属测试；application 1168/0、storage 167/0 | 规划层实测：`VocabularyUseCases.kt:30` 注入 NovelRepository、`:45-47` 先守卫后落库、`:96` confirmCandidate 走 saveEntry、`:145-158` 三类错误；`ApplicationContainer.kt:518`；测试行 227-319；无 Schema/.sqm 变更；底层 repo.saveEntry 仍可绕过，记 O-39b-FK |
| 2026-10-30 | **主仓库目录丢失 → 原始历史恢复**：17:36–18:43 之间 `D:/qwenyan-v4/qianyan-v4` 目录级消失（delete-pending 幽灵）；执行层旧视图仍持有原始仓库（HEAD `7af66c5` + 5 提交 + 10 项）；已用 `git bundle --all` + worktree patch + untracked 导出并重建，原始 5 个 SHA 全部保留 | 规划层实测：bundle 27 refs/verify okay（`ffa4392d…`）、worktree patch 9 文件 +357/−27（`b16866fa…`）、untracked 测试 `e96ceef9…`；重建树 application **1168/0**、storage **167/0**、desktop **63/0**；真实路径已从 bundle 克隆为 `7af66c5` + 10 项（ahead 5，未 push）；fallback `D:/qwenyan-v4/qianyan-v4-recovery-clone`（`d9aa385` + 36 项）；恢复包 `C:/Users/ZhuanZ1/Documents/qianyan-original-20261030/`；未 reset/未 commit/未 push；`local.properties` 由 fallback 克隆原样复制（printf 反斜杠折叠问题，gitignore 内）；测试日志 `D:/qwenyan-v4/t-restore-tests.log`（仓库外） |
| 2026-10-31 | **T-PUSH-3 收口**：fast-forward push `d9aa385..7af66c5`；远端 = `7af66c5`、ahead 0、tag 0；未 force/amend/rebase | 规划层实测：本地 HEAD `7af66c5`、status 10、staged 0、diff 9 files +358/−27；GitHub API 独立确认 `feature/novel-ide` head = `7af66c5`；5 个原始提交上云；工作区 10 项仍未提交（T-O48 4 + T-O39b 5 + 本文件 M）；任务树未随 push |
| 2026-10-31 | **T-G11 收口**：提交 `c80e2e8`(5, +207/−10)；HEAD `c80e2e8`、ahead 1、staged 空；application 1168/0、storage 167/0 | 规划层复核：5 文件集合精确匹配；提交身份 `32145687 <a13435942003@163.com>` 与历史 6 提交一致；无任务树/T-O48 夹带；XML 00:15 晚于源码 23:50；远端仍 `7af66c5`（GitHub API 确认；live ls-remote 网络失败，未据此推断） |
| 2026-10-31 | **T-G9 收口**：提交 `a56b04b`(4, +280/−5)；HEAD `a56b04b`、ahead 2、staged 空；desktop 63/0 | 规划层复核：4 文件集合精确匹配；无任务树/T-O39b 夹带；提交身份 `32145687 <a13435942003@163.com>`（repo-local 已配置）；XML 00:21 晚于源码 23:50；仅剩任务树 M；未 push；远端仍 `7af66c5`（GitHub API） |
