# Qianyan｜Phase 0 基线登记（Baseline Register）

> - 依据：`qianyan-dev-implementation-plan-v3.1.md` §9（Phase 0 出口门）、§24（A/B/C/D 分类）、§27（第一阶段实施入口）
> - 生成：2026-10-13（本轮为**只读审计 + 登记**；未修改任何业务代码，未 commit / push）
> - 后续更新：2026-10-13 —— 记录已采纳的 **多 TXT 规则 R1–R5**（附录 B）与 **Phase 0 有条件事后追认**（A4）；`revert` legacy 格式影响另见
>   [`qianyan-revert-legacy-format-impact-note.md`](qianyan-revert-legacy-format-impact-note.md)
> - 性质：**登记性文档**。不改变 v3.1 的阶段定义；不覆盖任何既有报告。v3.1 §9 / §10 / §27 已加指向本文件的指针。
> - 已知文档偏差：v3.1 §5 / §9.3 记录的"当前代码真实状态"（HEAD `b738086`、工作树 38 项）已陈旧；**以本文件实测为准**。

---

## A1. 基线清单（本轮实测，2026-10-13）

| 项 | 实测结果 |
|---|---|
| Branch | `feature/novel-ide` |
| HEAD | `fa70ac7592c07f97d0d80759a2bd08b85e699c18` |
| 暂存区 | **空**（`git diff --cached` 无输出） |
| 工作树 | **123 项**：M 80 / D 7 / ?? 36（其中 ?? 有 9 条是目录条目；展开后未跟踪**文件** 51 个） |
| Schema | `QianyanDb.Schema.version = 25`（生成代码 `QianyanDbImpl.kt` 实测）；迁移文件最大 `24.sqm`；`DatabaseInitializer` 常量至 `V24 = 24L` |
| 全量测试 | `./gradlew test --continue --rerun-tasks` → **exit 0**；**1361 tests / 0 failures / 0 errors**（本机 07:00–07:17 实测，14 个模块结果 XML 全部为本次时间戳） |
| Desktop | `./gradlew :app:desktop:jvmTest --rerun-tasks` → **exit 0**；**27 tests / 0 failures** |
| 格式检查 | `git diff --check` → **exit 0**（仅 CRLF 转换警告，无空白错误） |
| 与上一份报告差异 | **无差异**：123（M 80 / D 7 / ?? 36）、HEAD、schema 25、1361/0/0、desktop 27、diff --check 0 全部复现 |
| 未复验项 | 无（本轮所有关键项均为当次实测）。既有完成报告中的**定向**测试未单独重跑，但均包含在本轮全量结果内 |

---

## A2. 未提交工作归属登记

**统计口径（实测）**：80 个 M 中，**69 个有文本 diff**（`git diff --numstat` 69 M + 7 D = 76 行）、**11 个仅 stat / 换行差异（无内容差异）**；7 个 D；51 个未跟踪文件。

**归属依据**：既有完成报告与裁决文档 + 本轮 diff 事实（numstat 与盘面逐项核对）。无法确证者标 **待确认**（不按文件名猜测用途）。

**覆盖 / 冲突风险（全局）**：本工作树为多轮未提交工作的叠加，**任何后续批次的 `git checkout` / `reset` / `clean` / 批量格式化都会破坏这些工作**；跨批次改同一文件时（下表标「前序已 M + 本链追加」）必须叠加式编辑，禁止整体替换。

### U-A · P1-02 系列（AI 主写作链 + Gate / Commit / Workflow + 迁移阶梯）——前序会话，有完成报告

| 文件 | 状态 | 模块 | 目的 | 风险 | 归属 |
|---|---|---|---|---|---|
| `core/model/.../model/workflow/WorkflowModels.kt` | M | core:model | `WorkflowStep.roundNo`（轮次级预算） | 前序已 M，勿整体替换 | 已确认 |
| `core/model/.../model/commit/CommitModels.kt` | M | core:model | `CommitHistoryEntry.gateId`（Gate↔Commit 关联） | 同上 | 已确认 |
| `storage/.../db/Workflow.sq` / `CommitHistory.sq` | M | storage | `round_no` / `gate_id` 列、索引、查询 | 与 23/24.sqm 同步 | 已确认 |
| `storage/.../db/22.sqm` / `23.sqm` / `24.sqm` | ?? | storage | v21→v25 迁移阶梯（22/23/24 未跟踪，`.sqm` 新增文件） | 迁移编号语义=编号最大值+1 | 已确认 |
| `storage/.../db/DatabaseInitializer.kt` / `QianyanDbFactory.kt` | M | storage | 列存在性分支（`gate_id` / `round_no`） | 改动即影响所有迁移测试期望 | 已确认 |
| `storage/.../repository/{CommitHistoryRepository,SqliteCommitHistoryRepository,SqliteWorkflowRepository,StorageMappers}.kt` | M | storage | `getByGate` / `round_no` 读写映射 | 同上 | 已确认 |
| `application/.../usecase/workflow/{WorkflowOrchestrator,ChapterWorkflowFacade,WorkflowService}.kt` | M | application | 轮次作废、phaseKey `:W<n>`、Gate 批准/恢复 | 前序已 M，勿整体替换 | 已确认 |
| `application/.../usecase/writing/{WriterFacade,WritingExecutionUseCase,WritingSnapshot}.kt`、`writing/critique/*`、`writing/revision/*` | M | application | AI 产出→WorkingDraft、Critique/Revision 内容入口 | 同上 | 已确认 |
| `application/.../usecase/commit/CommitUseCases.kt` | **M（前序已 M + 本链追加）** | application | Gate 关联 + 本链新增 `commitImportedCanonical` | 叠加式修改 | 已确认 |
| `application/.../error/ApplicationError.kt` | **M（前序已 M + 本链追加）** | application | `ChapterSourceConflict`、`TxtCanonicalInitIncomplete` | 叠加式修改 | 已确认 |
| `application/.../di/ApplicationContainer.kt` | **M（前序已 M + 本链追加）** | application | `txtCanonicalInitialization` / Planner 桥装配 | 叠加式修改 | 已确认 |
| `application/.../usecase/agent/NovelAgent.kt`、`writing/planning/PlannerAgent.kt` | **M（本链）** | application | planner 传 `activityId`（工具作用域） | 见 U-B | 已确认 |
| `application/.../usecase/reading/ReadingUseCases.kt` | M | application | Reader 章节序 / Canonical 读取（R3） | 与本链无关 | 待确认（疑 P1-01b/R3） |
| `application/.../usecase/foundation/StoryFoundationDecisionUseCases.kt` | M | application | Story Foundation 决定（P14） | 与本链无关 | 待确认（疑 P14） |
| `application/src/test/.../{action/ActionPolicyUseCasesTest,commit/CommitTransactionTest,workflow/*,writing/WriterGatewayIntegrationTest}.kt`（9 个） | M | application:test | P1-02 系列定向测试 | 断言与生产同批演进 | 已确认 |
| `application/src/test/.../workflow/{AbandonRoundTest,ApprovalRecoveryTest,ConsecutiveAbandonRoundsTest,GateReentryTest,RevisionBudgetNullEquivalenceTest,RoundScopedRevisionBudgetTest}.kt`、`commit/CommitGateLinkTest.kt` | ?? | application:test | 前序系列新增测试 | 未跟踪，勿删 | 已确认 |
| storage 迁移测试 17 个（`AgentLog/AgentSession/AuthorCore/AuthorObservation/AuthorPreference/CommitHistory/DatabaseInitializer/Draft/Foreshadow/NarrativeState/ProjectState/ReadingProgress/Reveal/StoryFoundation/StoryState/Task/TaskQueue` 等 MigrationTest） | M | storage:test | 迁移阶梯版本号与 fixture 同步（23→24→25） | 与 `.sqm` 强耦合 | 已确认（数量 17 与既有报告一致） |
| `storage/src/test/.../{CommitHistoryGateIdMigrationTest,WorkflowStepRoundNoMigrationTest}.kt` | ?? | storage:test | 前序新增迁移测试 | 未跟踪，勿删 | 已确认 |
| `app/desktop/src/jvmTest/.../{DesktopGraphSmokeTest,DesktopWriterTest}.kt` | M | app:desktop:test | schema 断言同步 + 写作链桌面测试 | 与迁移版本耦合 | 已确认 |

### U-B · Phase 1 · TXT 导入 → Canonical + Agent↔Tool（**本链会话**）

| 文件 | 状态 | 模块 | 目的 | 风险 | 归属 |
|---|---|---|---|---|---|
| `application/.../usecase/txt/TxtUseCases.kt` | M | application | F1 闭环（导入后自动初始化）+ U2 失败态包装 | 前序亦改，叠加 | 已确认 |
| `application/.../usecase/txt/TxtCanonicalInitializationUseCases.kt` | ?? | application | TXT→Chapter→Canonical 初始化（F2/F3/F4） | 未跟踪，勿删 | 已确认 |
| `application/.../usecase/tool/AgentToolBridge.kt` | ?? | application | Agent↔Tool 桥（经 ProductToolService 调度链） | 未跟踪，勿删 | 已确认 |
| `application/src/test/.../TxtCanonicalInitializationTest.kt`（16 例） | ?? | application:test | F1–F4 + U2 验收 | 未跟踪，勿删 | 已确认 |
| `application/src/test/.../agent/{AgentTaskClosedLoopTest,AgentToolChainTest}.kt` | ?? | application:test | NL 闭环 + 工具链（6 例） | 未跟踪，勿删 | 已确认 |
| `app/desktop/.../ui/Common.kt` | M | app:desktop | `launchAgentTask` / `launchTxtImport` / `launchTxtAnalysis` / `launchCandidateDecision` / `agentTaskToast` | 该文件前序有大重构（785/168），叠加式修改 | 已确认 |
| `app/desktop/.../ui/write/WriterController.kt` | M | app:desktop | 错误文案分支（冲突 / 初始化未完成） | +2 行 | 已确认 |
| `app/desktop/.../ui/pages/{AgentPage,MaterialPages}.kt` | ?? | app:desktop | NL 任务入口接线 + 词库页分析/确认/拒绝接线 | 未跟踪；同文件属 U-E 页面族 | 已确认 |
| `app/desktop/.../ui/workspace/CommandPalette.kt`、`ui/settings/SettingsWindow.kt` | ?? | app:desktop | 导入调用点改走 `launchTxtImport` | 未跟踪 | 已确认 |
| `app/android/.../ui/{novel/NovelListViewModel,analysis/AnalysisViewModel}.kt` | M | app:android | U2 文案（可识别失败态） | +1/+2 行 | 已确认 |
| `app/android/src/test/.../ui/novel/NovelListViewModelTest.kt` | M | app:android:test | U2 文案断言（+3 例） | +36 行 | 已确认 |
| `app/desktop/src/jvmTest/.../{TxtImportStatusMessageTest,AgentTaskToastTest}.kt` | ?? | app:desktop:test | 导入状态文案 / 任务结果文案（7 例） | 未跟踪，勿删 | 已确认 |

### U-C · P1-01b / R3 · CanonicalRead 与 Reading

| 文件 | 状态 | 模块 | 目的 | 风险 | 归属 |
|---|---|---|---|---|---|
| `application/.../usecase/canonical/CanonicalReadUseCases.kt` | ?? | application | Canonical 唯一读取入口（R3/§23B） | 未跟踪，勿删 | 已确认（P1-01b） |
| `application/src/test/.../canonical/CanonicalReadTest.kt` | ?? | application:test | 同上测试 | 未跟踪 | 已确认 |
| `application/src/test/.../reading/ReadingUseCasesCanonicalTest.kt` | ?? | application:test | Reader 经 Canonical 读正文 | 未跟踪 | 已确认 |

### U-D · P20 · Workspace（投影 / Inspector / 选择）

| 文件 | 状态 | 模块 | 目的 | 风险 | 归属 |
|---|---|---|---|---|---|
| `application/.../usecase/workspace/{WorkspaceUseCases,WorkspaceProjections}.kt` | ?? | application | 工作区只读投影 + Inspector | 未跟踪，勿删 | 待确认（疑 P20） |
| `core/model/.../model/workspace/{WorkspaceModels,WorkspaceInspectorModels}.kt` | ?? | core:model | 工作区模型 | 未跟踪 | 待确认（疑 P20） |
| `app/desktop/.../ui/workspace/{WorkspaceController,ContextInspector}.kt`、`ui/pages/{WritePage,ReaderPage,ListPages,StoryExtraTabs,StoryWorldPages,AuthorPage}.kt` | ?? | app:desktop | 工作区 UI | 未跟踪 | 待确认（疑 P20） |
| `application/src/test/.../workspace/*`（5 个）+ `app/desktop/src/jvmTest/.../DesktopWorkspaceTest.kt` | ?? | tests | 工作区测试 | 未跟踪 | 待确认（疑 P20） |

### U-E · 桌面 UI 重构（**v3.1 未登记**）

| 文件 | 状态 | 模块 | 目的 | 风险 | 归属 |
|---|---|---|---|---|---|
| 7 个已删除屏：`ui/{author/AuthorScreen,book/BookScreen,home/HomeScreen,manage/ManageScreen,plan/PlanScreen,story/StoryScreen,write/WriteScreen}.kt` | **D** | app:desktop | 旧页面被 `ui/pages/*` 取代 | **删除状态，勿恢复亦勿提交** | 待确认（v3.1 无此单元） |
| `app/desktop/.../Main.kt`（423/136）、`ui/theme/QianyanTheme.kt`（120/131）、`ui/settings/ProviderSettings.kt`（172/109）、`ui/write/RewriteDialog.kt` | M | app:desktop | 桌面重构配套 | 大 diff，勿整体替换 | 待确认 |
| `app/desktop/.../ui/theme/QianyanTokens.kt`、`app/desktop/src/jvmMain/resources/fonts/NotoSansSC-VF.ttf` | ?? | app:desktop | 设计令牌 + 字体资源 | 二进制/未跟踪，勿删 | 待确认 |

### U-F · Android 侧其余

| 文件 | 状态 | 模块 | 目的 | 风险 | 归属 |
|---|---|---|---|---|---|
| `app/android/src/test/.../ui/chapter/ChapterWritingViewModelTest.kt`（1/0）、`.../ui/reader/ReaderViewModelTest.kt` | M | app:android:test | 章节写作 / 阅读 ViewModel 测试 | 小改动 | 待确认（疑 P12/P20） |

### U-G · 文档

| 文件 | 状态 | 模块 | 目的 | 风险 | 归属 |
|---|---|---|---|---|---|
| `docs/planning/qianyan-dev-implementation-plan-v3.1.md` | M | docs | 主规划（本轮不改其阶段定义） | 前序已 M | 已确认 |
| `docs/planning/Qianyan_开发总规划_连接优先架构.md` | M | docs | 架构总图 | 同上 | 已确认 |
| `docs/planning/qianyan-dev-implementation-plan-v2.md` / `-v3.md` | ?? | docs | 历史版本留档 | 未跟踪 | 已确认 |
| `docs/architecture/pre-dsh-audit.md` | ?? | docs | DSH 前置审计 | 未跟踪 | 已确认 |

### U-H · 仅 stat / 换行差异（**无内容差异**，无需归属）

`agent/runtime/.../AgentError.kt`、`agent/tool/.../ToolExecutor.kt`、`application/.../usecase/analysis/AnalysisUseCases.kt`、`core/model/.../model/agent/AgentModels.kt`、`core/model/.../model/tool/ToolModels.kt`、`storage/src/test/.../{DraftRepositoryTest,StorageRepositoryTest,StorageSmokeTest,TaskRepositoryTest,TxtRepositoryP5QueryTest,TxtRepositoryTest}.kt` —— 共 **11 个**（`git status` 标 M，`git diff` 无文本差异；与项目已知的 CRLF/autocrlf 现象一致）。

---

## A3. 失败项分类（v3.1 §24 口径）

| 类 | 数量（本轮实测） | 说明 |
|---|---|---|
| **A（本阶段引入的失败）** | **0** | 全量 1361/0/0；本轮未改业务代码 |
| **B（历史遗留失败）** | **0** | 当前无红测；**不代表无缺陷**——已知缺陷/缺口单列于下 |
| **C（环境问题）** | **0** | 历史上曾出现 8 例 Windows 临时 DB 句柄占用；**本轮未复现**（标注为"历史 C 类，当前未复现"） |
| **D（测试自身问题）** | **0** | 本链会话曾出现 1 例测试脚手架缺陷（脚本网关把工具调用包进 answer 信封）与若干断言问题，**均已修复并全绿** |

**已知但非"失败"的问题（不计入 A/B/C/D，单独登记）：**

1. **`revert` 对 legacy 正文的格式戳记**：`WriterUseCases.stampControlledMarkdown` 对 `format = null` 的内容无条件改写为 `CONTROLLED_MARKDOWN`（`CommitUseCases.revert` 调用它）⇒ 回退 TXT 导入章会改写格式标记。代码事实已确认；**对 Reader 的实际影响未复验**。归属：Phase 1 收口。**裁决（2026-10-13）：暂不修改生产行为**，影响分析与待补验证见 [`qianyan-revert-legacy-format-impact-note.md`](qianyan-revert-legacy-format-impact-note.md)。
2. **多 TXT 全书顺序规则未裁决**：现为 `order = ordinal + 1` + F2 拒绝混排；见附录 B。归属：Phase 1。
3. **v3.1 文档基线陈旧**（§5/§9.3 仍写 HEAD `b738086` / 38 项）。归属：Phase 0 收尾（本文件已给出实测基线）。
4. **§11A 词库三层口径漂移**：v3.1 要求 AUTHOR/SERIES/BOOK，代码为 `GLOBAL/NOVEL/VARIANT/TASK`。归属：Phase 2 口径对齐。
5. **P2 字段缺口**：`Event` 等表缺 evidence/participants 等字段；`characters` 投影恒为空。归属：Phase 2。
6. **写作资产来源引用缺失**：`VocabularyCandidate.source` 仅为类别枚举。归属：Phase 2/3。

**暂无证据项（不得视为 0）**：P8 完整 17 段闭环、Skill 执行生命周期、DSH 往返——均**未验证**（无实现，亦无测试）。

---

## A4. Phase 0 出口判断（v3.1 §9.12 六项）

| # | 出口条件 | 状态 | 证据 |
|---|---|---|---|
| 0-1 | 基线确认（branch / HEAD / 远程 / 工作树清单） | **PASS** | 本文件 A1（远程一致性未复核：**NOT VERIFIED**，本轮未执行网络查询） |
| 0-2 | 已有未提交工作登记（不覆盖） | **PASS** | 本文件 A2（U-A…U-H） |
| 0-3 | 编译 / schema 阻塞处理（A 类 = 0） | **PASS** | 全量构建成功；`Schema.version = 25` 与 `24.sqm` 语义一致；迁移测试全绿 |
| 0-4 | 测试基线明确 | **PASS** | 1361 / 0 / 0（07:00–07:17 实测）+ desktop 27 / 0 |
| 0-5 | 失败责任归属明确 | **PASS** | A3（A/B/C/D = 0 且有登记；已知缺陷单列） |
| 0-6 | **不开始任何新的产品能力** | **FAIL（条件已失效）** | Phase 1 及部分 P5/P7 能力已落地：TXT 导入即 Canonical 初始化、桌面 NL→NovelAgent、AgentToolBridge ⇒ 该条在事实上不可追认 |

**结论（2026-10-13 已决议）**：Phase 0 **5/6 PASS，第 0-6 项条件失效**。经确认，Phase 0 以 **有条件的事后追认（conditional retroactive closure）** 关闭：

- 可确认：0-1…0-5 的收尾材料已形成（基线实测、未提交工作登记、编译与 schema 检查、测试基线、失败分类）。
- **不可声称**：0-6「本阶段不开始产品能力」在历史上被严格满足——Phase 1 及部分 P5/P7 能力已提前落地（本文件 A2 的 U-B 即偏离证据）。
- 因此本阶段**不改变原始门禁记录**（v3.1 §9.12 六项原文保留），只在其后追加本条追认说明；v3.1 §9 / §27 已加指针指向本文件。
- 仍**未核实**的事项继续标未验证 / 待确认（见下第 3、4 条）。

**最小补齐任务（不含产品功能）**
1. ✅ 本登记文件落盘（A1–A4）。
2. ✅ 在 v3.1 §9 / §10 / §27 处加指针（指向本文件；**未改写 v3.1 任何历史基线数字**）。
3. ⏳ U-D / U-E / U-F 中"待确认"项：由工作树作者确认归属（本轮无充分证据，不猜测）。
4. ⏳ 远程一致性（`git ls-remote`）复核：本轮未执行（避免网络操作），列为一句话待办。
5. ✅ `revert` legacy 格式戳记：已裁决暂不改行为，影响分析落盘于 `qianyan-revert-legacy-format-impact-note.md`。

---

## 附录 B：多 TXT 章节顺序规则（R1–R5 **已裁决** 2026-10-13；多 TXT 合并功能**未实现**）

### B1. 真实实现（本轮复核）
- 导入入口唯一：`TxtUseCases.importTxtAsOriginal`；它**每次都 `createOriginal` 新建 Novel**，且 `txtRepository.saveImport` 仅由它调用 ⇒ **生产上不存在"把第二份 TXT 绑定到已有 Novel"的入口**。
- 章节身份：`ChapterId(tc.chapterId.value)`，TXT 侧为确定性 ID；`order = ordinal + 1`（每份文档独立从 1 起）。
- `UNIQUE(novel_id, variant_id, order_no)` 对 `variant_id IS NULL` **SQLite 不强制**；`listChaptersByNovel` 为 `ORDER BY order_no ASC, chapter_id ASC`。
- F2 现行语义：目标 Novel 存在**任何**不属于本次目标集合的章节 ⇒ `ChapterSourceConflict`（写入前拒绝）。
- 其他 order 写入方：`ChapterRepository.createNextChapter`（MAX+1）、`WorkflowOrchestrator` / `PlanningExecutionUseCase`。

### B2. 方案比较

| 维度 | A 自动接尾 | B 来源隔离 | C 显式合并 |
|---|---|---|---|
| 改变既有 Chapter ID | 否 | 否 | 否（若只追加） |
| 重写既有 order | 否（只对新章分配 max+1…） | 否 | 否（但需合并决策） |
| 幂等/重试 | **弱**：order 依赖写入时的 DB 状态（非文档固有），失败重试可得到不同 order | 强（各来源序列独立） | 强（显式决策可重放） |
| 破坏 CommitHistory | 否（History 不记 order） | 否 | 否 |
| 影响 Reader / 查询 | 追加序可读，但整书序由导入时机决定 | 需新增"按来源分组"查询 | 需新增合并后视图 |
| 两来源同章重复 | **存在风险**：同一本书两份不同 hash 的 TXT 会各成一来源 | 由来源隔离天然区分 | 需冲突处理策略 |
| 现有 Domain 支持 | order 可写，无需新模型 | **不支持**（Chapter 无来源列，需 additive 字段或跨表映射） | 不支持（需新 UseCase / UI） |
| 是否扩大 Phase 1 范围 | 会（改 `order` 语义 + F2 判据） | 会（新模型/字段，需 migration 授权） | 会（新 API + UI，属 P10） |
| 现成入口 | **无**（无第二份文档绑定 API） | 无 | 无 |

### B3. 规则（**R1–R5 已于 2026-10-13 采纳**；仅冻结边界与扩展约束，多 TXT 合并功能未实现）

**采纳规则 = "本阶段明确拒绝自动合并，冻结未来的显式合并扩展点"**，理由：生产上还没有"多来源"的入口（`importTxtAsOriginal` 每次 `createOriginal`；`TxtRepository.saveImport` 仅被它调用，不存在"第二份文档绑定同一 Novel"的 API），任何合并规则目前都没有真实使用者；而 A 方案的 order 依赖写入时机（非文档固有）会削弱"章节顺序稳定"与"幂等"两条已验收语义。

- **R1（保持）** 一个 Novel 的 Canonical 章节序列来自**唯一来源**（一份 TXT 文档，或 AI 写作链），系统**不自动混合**来源。
- **R2（拒绝语义保持并明确）** 目标 Novel 已存在任何不属于本次目标集合的 domain Chapter ⇒ `ChapterSourceConflict`（现行实现，禁止放宽）。
- **R3（幂等/原子保持）** 单份文档的初始化=单事务全有或全无；重试按确定性 ID 幂等跳过（现行实现与测试）。
- **R4（未来扩展点，需再次裁决 + additive 字段）** 若产品决定支持多来源，必须经**显式合并 UseCase**：携带（a）全书顺序决策、（b）来源引用；并必然需要 additive 字段（如 `Chapter.source_document_id` 或每文档 `base_order`）——**属 Phase 2/3+ 的独立裁决，本轮不实现、不建字段**。
- **R5（禁止）** 不实现"自动接尾"（A 方案）作为默认行为；任何来源混合都必须显式。

**边界与限制**：本规则不提供多来源能力；它只保证"当前不会被静默混合"，并把扩展点契约写清楚。测试现状已锁定 R2/R3（`TxtCanonicalInitializationTest` 的 F2 两例 + 幂等两例）。

**裁决影响**：R1–R5 为**边界冻结**，不改变任何生产实现（本轮未改代码）；v3.1 §10（Phase 1）已加同源指针。多 TXT 合并仍属未实现能力。

---

## 附录 C：Phase 1 剩余范围（状态标记）

| # | 项 | 状态 | 证据 / 说明 |
|---|---|---|---|
| 1 | 单 TXT 导入 → Chapter → Canonical 闭环 | ✅ 已完成，有测试证据 | F1 链路 + `TxtCanonicalInitializationTest` |
| 2 | Reader 读导入章节标题与正文 | ✅ 有测试证据 | `ReadingUseCasesCanonicalTest` |
| 3 | 重复导入 / 重试幂等 | ✅ 有测试证据 | contentHash 去重、确定性 ID 跳过 |
| 4 | 作者修改不被重复导入覆盖 | ✅ 有测试证据 | `user modified canonical is not overwritten…` |
| 5 | 跨来源混排拒绝 | ✅ 有测试证据 | F2 两例（外来章 / 目标 ID 被占用） |
| 6 | 多 TXT 规则的清晰可测契约 | ✅ **规则已裁决（R1–R5，2026-10-13）**；⚠️ 合并功能未实现 | 附录 B（R1–R5 已采纳；现行 F2/幂等测试即该规则的锁定） |
| 7 | 两事务之间的部分成功状态 | ✅ 已完成（有测试证据） | F1 分阶段 + `TxtCanonicalInitIncomplete` + 重导补齐测试 |
| 8 | `revert` 对 legacy `format=null` 的影响 | ✅ **已裁决：暂不修改行为，仅记录**（代码事实已确认；正文与 Reader 影响**未复验**） | `stampControlledMarkdown` + `revert`；影响分析见 [`qianyan-revert-legacy-format-impact-note.md`](qianyan-revert-legacy-format-impact-note.md) |

**不属于 Phase 1（后续阶段）**：故事事实抽取（P2/P3）、写作资产来源引用（P2/P3）、Context 收敛（P4）、Skill 执行化（P6）、产品 UI 收口（P10）。
