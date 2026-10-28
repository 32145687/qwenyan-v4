# Qianyan｜Phase 4B.1 决策固化记录（Decision Record）

> - 性质：**决策与授权边界固化文档**；不是实现报告，不代表 Phase 4B.1 已验收。
> - 依据：`docs/audit/2026-10-18-be1b755/` 的工作树审计（HEAD `be1b755`）、`qianyan-phase4a-context-contract-freeze.md`、`qianyan-phase4a-context-contract-freeze-addendum-payload.md`、`qianyan-phase4c-acceptance-criteria-draft.md`、`qianyan-phase3-closure-decision-record.md`。
> - 基线：`feature/novel-ide` @ `be1b75582322a43a114fdc174597e29cfe2de87c`；工作树 12 modified + 8 untracked（审计新增 `docs/audit/` 后为 9 untracked）。
> - 本轮**零业务代码/测试/Schema/migration 修改**；不改 README / status doc；不运行构建/测试。
> - 术语：**4B.1a** = 原授权范围内的占位实现与验收；**A2** = 真实 ContextPack 贯通的独立子阶段。

---

## A. 当前阶段基线

| 项目 | 事实 |
|---|---|
| 当前工作树阶段 | **v3.1 Phase 4B.1 — ContextPack 统一入口 / Context + Retrieval 实现** |
| Phase 4A | 契约冻结文档已提交（`be1b755`） |
| Phase 4B.1 | 代码/测试/文档修订在工作树未提交；A/B 验收未闭合 |
| Phase 4C | 验收草案存在；多项仍标注“需先实现后验证” |
| README 阶段 | 仍写 `Current Phase = P13 Long-form Continuity Layer`，与工作树 v3.1 Phase 4B.1 冲突 |
| Inspector | 当前独立调用 `ContextEngineUseCases.build`；不是 Agent 任务实际 Pack |
| Agent Pack 生命周期 | `NovelAgent.run` 局部对象；`NovelAgentResult` 只含 id/version；UI 不保留 |
| Vocabulary | J-1.1 / J-2.1 分层已写入 addendum；AC-30A/AC-30B 证据不足 |
| 测试基线 | 243 个 fresh XML，raw 1444 tests / 0 failures / 0 errors / 0 skipped；本轮未重跑 |
| DSH | `runtime/api`、`runtime/dsh` 不在 Gradle settings；XML 为 2026-10-06 陈旧结果 |

审计证据与节点状态见 `docs/audit/2026-10-18-be1b755/qianyan-project-worktree.json` 与 `qianyan-audit-evidence.md`。

---

## B. D-1～D-9 决策表

| ID | 原问题 | 选定方案 | 决策理由 | 授权范围 | 明确不授权 | 影响阶段 | 验收标准 | 状态 |
|---|---|---|---|---|---|---|---|---|
| **D-1** | Inspector 真实任务 Pack 尚未实现；4B.1 是否继续包含真实 Pack？ | **A：拆为 4B.1a（占位）+ A2（真实 Pack）两段** | 现有生命周期没有可复用 Pack；真实 Pack 需要新共享状态/结果扩展/持久化，超出原 4B.1 冻结边界 | 4B.1a 仅做占位与 no-build 边界；A2 另立范围 | 4B.1a 内新建 Store、扩展 Agent 结果、持久化、第二套 Context 模型 | 4B.1a、A2、Phase 4C | 4B.1a 四状态占位 + no-build + 测试 + 独立回归；A2 独立验收 | **已决定** |
| **D-2** | 真实 Pack 如何保存？ | **A2 优先评估进程内 Store；暂不授权持久化** | 当前只需进程内展示；避免 Schema/Migration 与长期维护；进程重启可诚实显示失效 | A2 可在独立范围确认后评估/实现进程内 Store | SQLDelight/SQLite 新表、Migration、持久化快照、通用上下文平台 | A2 | A2 定义 key/生命周期/失败/淘汰/重启/测试；无 Migration | **已决定** |
| **D-3** | Inspector 如何关联任务上下文？ | **使用 `sessionId`** | 每次 Agent run 都有 session；`taskId` 可选，当前 UI 发起 Agent 任务不传 taskId；`AgentSession` 带 projectId/novelId，便于 Project 隔离 | A2 使用 sessionId 查询；校验 Project | 仅用 taskId 作为唯一键；跨 Project 查询；无归属 Pack | A2 | Inspector 展示的 Pack 可追溯 session；跨 Project 不可见 | **已决定** |
| **D-4** | 当前小说事实与任务快照如何展示？ | **分开：当前事实独立只读展示；任务快照只来自实际 Pack（4B.1a 为占位）** | 二者语义不同；混排会误导作者；当前事实不应触发 ContextEngine.build | 4B.1a 分离两者；当前事实走现有 Repository/read API；任务上下文走占位 | 用当前事实冒充任务快照；为当前事实调用 ContextEngine.build；把二者混成一个列表 | 4B.1a、A2 | UI/投影明确标识“当前事实”与“任务上下文”；no-build 断言 | **已决定** |
| **D-5** | J-1.1 / J-2.1 分层是否生效？ | **保留分层生效方向：Source Dataset 与 Model Input Subset 分开；先核对再验收** | 分层解决 J-1“数量不减少”与 J-2“允许省略”的歧义；代码已有 APPROVED + 确定性排序 + 两阶段选择 | 作为 B 验收的契约方向；B 测试独立执行 | 改写 J-1/J-2 历史原文；把分层存在当作 B 已验收；用预算后结果证明来源数据集 | B 验收、4B.1 收口 | J-1.1/J-2.1 与代码一致；AC-30A/30B 证据齐备 | **已决定（附核对结论）** |
| **D-6** | AC-30A 如何证明来源数据集等价？ | **旧/新 A/B + 非批准词条负向排除** | 现有 `VocabularyDatasetEquivalenceTest` 只比较新 Source 与 APPROVED 查询，不能证明旧/新等价 | B 测试任务按此计划补测 | 仅用新 Source=APPROVED 证明；用预算裁剪后的 Pack 证明；忽略非批准 Entry 行 | B 测试、4B.1 收口 | 同夹具/同查询范围 A/B；字段一致；pending/non-approved 排除；确定性排序；无静默漏读 | **已决定** |
| **D-7** | 当前阶段以哪套口径为准？ | **以 v3.1 Phase 4B.1 为当前基线；README 旧标记在 4B.1a 后独立同步** | 工作树/提交/契约均指向 v3.1；README P13 为旧路线；本轮禁止改 README/status doc | 决策基线；doc sync 单独任务 | 本轮修改 README/status doc；把 P13 继续当作当前阶段 | 4B.1a 后文档同步 | 4B.1a 完成后 README/status doc 与 v3.1 阶段一致 | **已决定** |
| **D-8** | DSH Runtime 是否纳入 4B.1/A2？ | **暂缓；保留后续候选** | `runtime/api`/`runtime/dsh` 不在 settings；陈旧 XML；无当前生产调用链/产品动机 | 仅保留历史目录与候选说明 | 4B.1a/A2 接入 DSH；改 Runtime 适配器；用 DSH 替代现有链 | 后续（未定） | 未来单独范围确认与验收 | **已决定** |
| **D-9** | 何时运行完整回归？ | **4B.1a 与 A2 各一次独立完整回归；当前不运行** | 两段有不同验收对象；共用结果会混淆责任；当前是决策轮 | 用户逐阶段授权 | 当前运行；一次回归同时验收两段；用 B 定向测试替代完整回归 | 4B.1a、A2 | 每段报告引用自己的回归命令、HEAD、时间与结果 | **已决定** |

### D-5 核对结论（契约 / 代码）

| 核对项 | 结论 | 证据 |
|---|---|---|
| J-1.1 / J-2.1 是否存在 | 存在，且明确区分 Source Dataset 与 Model Input Subset | addendum `:345-418` |
| Source Dataset 是否按 APPROVED + 确定性排序 | 是：`findEntriesByVariant/ByNovel` → `APPROVED` → `(canonical, entryId)` | `ContextSources.kt:303-318` |
| 是否两阶段选择 | 是：`NOVEL → STORY_FOUNDATION → VOCABULARY`，普通来源后选 | `ContextPack.kt:218-279` |
| 选中词条是否进入 Assembly/prompt | 是：从 Pack 取 `VocabularyPayload`，Planner/Writer 遍历 `context.vocabulary` | `PlanningContextAssembly.kt:98-100`、`:143-145`；`PlannerAgent.kt:142-149`；`WriterAgent.kt:139-146` |
| 测试是否证明 AC-30A/30B | 否：现有测试只覆盖部分 Source 集合与通用 Selector；无旧/新 A/B、非 APPROVED Entry、Vocabulary prompt 证据 | `VocabularyDatasetEquivalenceTest.kt`、`ContextSelectorKeySourcesTest.kt` |
| 默认预算/estimator | 未改：1200 / `approx-chars-per-4` | `ContextPack.kt:127-135` |

**结论**：J-1.1 / J-2.1 的分层方向与当前代码一致，可作为后续 B 验收的生效方向；但 AC-30A/AC-30B 尚未验收，不能把“分层存在”写成“B 已完成”。

---

## C. 4B.1a 授权范围（占位实现与验收）

### C.1 目标

在**原 Phase 4B.1 冻结授权边界内**，让 Inspector 不再为任务上下文独立构建 Pack，并落实四状态占位与“当前事实 / 任务快照”分离。

### C.2 允许

1. 修改 Workspace / Inspector 相关的 Application 与 Desktop UI 代码，移除任务上下文的自动 `ContextEngineUseCases.build`。
2. 实现 Inspector 任务上下文四状态占位：
   - 无 Agent 任务 → `暂无任务上下文`
   - 存在任务但实际 Pack 不可获取 → `上次任务上下文已失效`
   - 新任务执行中且尚无 Pack → `当前任务尚未生成上下文`
   - 实际 Pack 可获取 → **4B.1a 不实现**；不得伪造；不得把上一任务 Pack 标为当前
3. 当前事实使用现有 Repository / read API 独立只读展示，并明确标注“当前事实”；不得调用 `ContextEngineUseCases.build`。
4. 更新必要的 `WorkspaceBoundaryTest`、Inspector 状态测试与 no-build 测试。
5. 允许使用现有 Task / AgentSession 读取能力判断状态；不新增 Store。

### C.3 明确不做

- 不新建 ContextPack Store / Session Map / 跨层共享状态。
- 不扩展 `NovelAgentResult` 或 Agent 内部契约。
- 不新增持久化、Schema、Migration。
- 不新增第二套 Context / Snapshot 模型。
- 不实现真实 Pack 展示；不把状态 3 写成已完成。
- 不实施 A2、AC-30A/30B 测试或 DSH。
- 不修改 README / status doc（doc sync 另立任务）。

### C.4 验收

- 四状态文案/判定可测试；状态 3 明确不可达，不伪造。
- 对象选择、章节浏览、Inspector 刷新/加载、Tab 切换不为任务上下文调用 `ContextEngineUseCases.build`。
- 当前事实与任务快照在投影/UI 上可区分；当前事实读取不 build。
- Project 隔离：不得显示其他 Project 的任务状态。
- `WorkspaceBoundaryTest` 更新后仍保留其他边界断言，不放宽无关约束。
- 4B.1a 完成后由用户授权一次完整回归；报告必须注明“4B.1a”且不得引用为 A2 结论。

---

## D. A2 预定范围与独立授权要求

### D.1 目标

让 Inspector 展示 Agent 任务**实际使用的 ContextPack**，并可追溯到 task/session。

### D.2 方向

- 关联键：`sessionId`。
- 优先评估**进程内 Store**；不默认增加 SQLDelight/SQLite Migration。
- 不扩展为通用 Context 平台；不接入 DSH。
- 不改变 4B.1a 的占位验收结论；A2 必须独立范围确认、独立实施、独立验收报告。

### D.3 A2 开始前必须单独确定

1. Store 接口与所在层（Application / DI）。
2. 保存完整 Pack 还是只读投影；是否 immutable。
3. 生命周期：成功、失败、取消、并发、Project 切换。
4. 淘汰/上限策略；避免无限增长。
5. 进程重启后的“不可获取”状态。
6. 与 Inspector/Task/Session 选择入口的关系（当前 UI Agent 任务无 taskId）。
7. 测试与验收标准；是否需要新增依赖注入。
8. 是否涉及 Agent 结果契约（默认不涉及；若涉及需另行授权）。

### D.4 明确不做

- 不持久化；不新增表/Migration。
- 不接入 DSH。
- 不把 A2 纳入 4B.1a 的完成声明。
- 不用“packVersion 相同”替代“同一任务 Pack”。

---

## E. AC-30A / AC-30B 测试计划

> 本测试计划是 B 验收任务；本轮不实现、不补测试。B 证据不得替代 4B.1a/A2 的回归结论。

### E.1 AC-30A：Vocabulary Source Dataset 等价

- 同一夹具、同一查询范围（Original 或 Variant）比较：
  - 旧 `PlanningContextAssembly.assembleLegacy` 的完整来源数据集；
  - 新 `VocabularySource` 的完整 APPROVED 来源数据集。
- 断言：词条集合、`canonical`、`aliases`、`replacement` 一致；确定性排序；无静默漏读/漏查。
- 负向：pending candidate 不得替代 approved；非 APPROVED `VocabularyEntry` 行不得进入新模型输入。
- **不得**使用经过 ContextSelector 预算裁剪的 ContextPack 结果证明本项。
- 旧路径无状态过滤时，明确数据前提：推荐两边都取 APPROVED 子集做 A/B，并另测非 APPROVED 排除；不得仅用“新 Source = APPROVED”证明。

### E.2 AC-30B：Model Input Vocabulary Subset

- 预算充足/紧张；关键来源优先；DRAFT 挤压；关键来源缺失；普通条目竞争；重复执行稳定性。
- `omittedItemIds` 可识别；selected + omitted 对唯一 itemId 应可对账。
- 捕获 Planner / Writer 实际请求，断言 Vocabulary 子集与选定 ContextPack payload 一致。
- 不得仅以 `ContextSelectorKeySourcesTest` 宣称 Planner/Writer 已验收。
- 默认 budget/estimator 保持 1200 / `approx-chars-per-4`；如需改变必须另立决策。

### E.3 现有测试差距

- `VocabularyDatasetEquivalenceTest` 只覆盖新 Source 与 APPROVED 查询。
- `ContextSelectorKeySourcesTest` 使用通用 item，不是真实 Vocabulary；无 prompt。
- `AgentContextPackPipelineTest` 只验证 NOVEL marker，且是“任一请求包含”。
- 无非 APPROVED Entry 行、无旧/新 A/B、无 Vocabulary prompt 证据。

---

## F. doc_lag 核实清单（只列清单，不改 README/status doc）

> 区分：**代码存在** ≠ **功能完整** ≠ **已验收**。以下仅为证据支持的冲突/滞后项；实际同步在 4B.1a 完成后作为独立文档任务执行。

| # | 文档声明 | 代码/测试事实 | 状态分类 | 归属/同步计划 |
|---|---|---|---|---|
| 1 | README `Current Phase = P13 Long-form Continuity Layer`（`README.md:147`） | 工作树在 v3.1 Phase 4B.1；Phase 4A 已提交，4B.1 未提交 | 文档滞后；当前阶段冲突 | 4B.1a 完成后独立 doc sync：改为 v3.1 阶段口径；保留旧 P13 历史 |
| 2 | README P14 Story Intent / Idea Intelligence 标 `PLANNED`（`:140`） | `foundation` 模块（StoryIntent/Idea/Foundation）与测试存在；Desktop 有只读展示，写路径未接 | 代码存在；功能部分；未按 P14 验收 | doc sync：标“后端基础存在、产品/验收待完成”，不得写 DONE |
| 3 | README P15 User Creative Decision Loop 标 `PLANNED`（`:141`） | StoryFoundationDecision / 人工提案与确认相关 UseCase/测试存在；UI 写路径未接 | 代码存在；功能部分；未验收 | doc sync：与 P14/P16 一并核实后标注 |
| 4 | README P16 Author Intelligence 标 `PLANNED`（`:142`），但同文 `:168` 写 Author 后端 `IMPLEMENTED` | author/decision 模块与测试存在；AuthorPage 只读；写路径未接 | README 内部冲突；后端存在、产品未完整 | doc sync：统一 README 内部表述；标后端/产品分层 |
| 5 | README/status doc 将 P13 LCL 标 `COMPLETE / SEALED` | LCL 后端与测试存在；部分 UI 写路径未接；`ForeshadowLifecycleUseCases` 未找到 UI/生产调用者 | 后端封存；产品写路径/消费链部分 | doc sync：区分“后端 sealed”与“产品接线/验收”；不得扩大为全部完成 |
| 6 | README/direction 称具体信息架构“尚未确定”（`:29-34`） | Desktop 已实现三栏 Workspace/Inspector/Center shell | 代码存在；临时 UI；IA 未最终裁决 | doc sync：标注“临时实现/未最终 IA”，不把代码存在当 IA 定案 |
| 7 | addendum §J-6 / AC-35 要求任务实际 Pack；4B.1 报告称相关子项完成 | Inspector 仍独立 rebuild；4B.1a 只做占位；A2 未实施 | 契约要求 vs 代码缺口；报告与验收证据不一致 | 4B.1a：占位边界；A2：真实 Pack；B/验收：更新报告状态，不得提前写完成 |
| 8 | status doc 为 2026-08-18 历史快照（P6） | 当前工作树已远超 P6；文档自述历史 | 历史快照，不是当前事实源 | 保留历史；doc sync 仅加“历史/现行指针”，不覆盖原文 |

**尚未处理**：以上 1–8 均未在本轮修改；README/status doc 的实际修改统一延后到 4B.1a 完成后的独立文档同步任务。
**归属**：1/2/3/4/5/6/8 → 4B.1a 后 doc sync；7 → 4B.1a 占位 + A2 真实 Pack + B 验收。

---

## G. 回归测试授权门槛

| 阶段 | 允许运行 | 授权方式 | 验收使用 | 禁止 |
|---|---|---|---|---|
| 当前决策固化轮 | **否** | — | — | 运行 `./gradlew test` / 完整回归 |
| 4B.1a 完成 | **一次完整回归** | 用户明确授权 | 仅用于 4B.1a | 与 A2 共用；提前运行 |
| A2 完成 | **一次完整回归** | 用户明确授权 | 仅用于 A2 | 复用 4B.1a 结果；自动运行 |
| B 测试 | 定向测试 | 独立 B 验收任务 | B 证据 | 替代 4B.1a/A2 完整回归 |

**规则**：两次完整回归不合并、不共用验收结论；每次报告必须记录命令、HEAD、时间、测试总数与失败数。

---

## H. 未解决问题与后续负责人（角色）

| 问题 | 负责人（角色） | 触发时点 |
|---|---|---|
| A2 接口、Store 生命周期、失败/淘汰/重启、测试与验收 | 架构负责人 + 实施者 | A2 范围确认前 |
| AC-30A 的 A/B 数据前提与夹具 | B 验收负责人 | B 测试前 |
| README/status doc 的 doc_lag 实际同步 | 文档同步负责人 | 4B.1a 完成后 |
| DSH Runtime 是否仍属项目范围 | 用户/架构负责人 | 后续阶段 |
| 4B.1a / A2 的完整回归授权 | 用户 | 各阶段完成时 |
| 当前阶段口径统一 | 用户/架构负责人 | 4B.1a 后 doc sync |

---

## I. 下一步唯一允许执行的任务

**4B.1a 实施（占位改造 + 验收）。**

- 只有用户下达 **4B.1a 实施提示词**后才能开始。
- 4B.1a 不得包含 A2、Store、结果扩展、持久化、AC-30A/30B 测试或 README/status doc 修改。
- 4B.1a 完成后，需用户授权一次独立完整回归，方可报告 4B.1a 通过。
- A2 未实施、未验收前，不得宣称 Inspector 已展示真实任务快照。

**当前状态：HARD STOP。未实施 4B.1a，未开始 A2，未运行测试，未 commit/push。**
