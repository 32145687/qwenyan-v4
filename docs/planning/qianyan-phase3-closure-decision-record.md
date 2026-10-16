# Qianyan｜Phase 3 Closure 决策记录（Decision Record）

> - 依据：`qianyan-dev-implementation-plan-v3.1.md` **§12**（Phase 3 — World Model Update，行 500–558）；辅以 §0 对齐输入第 7 条（行 25）、§28 v2→v3 修改清单第 7 条（行 1486）
> - 生成：2026-10-16（本轮含最小代码修正 + 测试补充，见 G 节；未 commit / push）
> - 性质：**决策登记文档**。不改变 v3.1 的阶段定义，不覆盖任何既有报告，**不新增业务模型**。
> - 术语纪律：本文件严格区分「**正式契约要求**」（§12 原文）/「**当前实现事实**」/「**当前阶段采用的实现选择**」/「**需负责人裁决的契约缺口**」。凡未在 §12 明写者，一律不表述为契约要求。
> - 本次未在 v3.1 §12 正文加指向本文件的指针（避免未经裁决修改契约文档）；如需可后续作为最小操作补上。

---

## A. 基线快照（本轮实测）

| 项 | 实测结果 |
|---|---|
| Branch | `feature/novel-ide` |
| HEAD | `5b047b0d6336b4957e819eebe6e080a8b5842053` |
| 暂存区 | 空 |
| 工作树 | **47 项 = 36 M + 11 ??**（Phase 3 新增/修改叠加在 Unit 2 与 P2 未提交改动之上） |
| Schema | **v28**（`27.sqm` 新增 `CandidateFact`；`.sq` 与 `.sqm` 18 列一致；纯 additive：`CREATE TABLE` + 2 `CREATE INDEX`，无 FK / 无 DROP / 无 ALTER 既有表） |
| 提交 / 推送 | 未 commit、未 push |
| 相关代码位置 | 挂载点 `CommitUseCases.kt:215 / 312 / 398 / 551`（helper `:738`）；流水线 `WorldModelUpdateUseCases.kt`（`onCommit:71`、事务 `:107`、裁决回写 `:113/118/119`、失败记录 `:145`、标记常量 `:214/217`）；确定性校验 `WorldModelFactValidator.kt`（`:56-62`） |

---

## B. §12 契约 ↔ 实现对照（要点）

| §12 条款（行号） | 原文要点 | 当前实现 | 性质 |
|---|---|---|---|
| 1 @502 | Commit 之后沉淀增量，**永不静默覆盖** | 四条提交路径事务后挂载；冲突不写行 | 契约要求，已满足 |
| 5 @514 | `Commit → Extract（规则 + Index + AI）→ Candidate Facts →【冲突生命周期】→ Validate → Update` | Index（`onCommit` 读既有事件身份）+ AI（`WorldModelUpdateAgent`）已接；"规则"腿见 U5 | 契约要求 + **语义歧义** |
| 6 @520 | 不在草稿阶段更新 World Model（边界二） | 挂载点结构保证（Draft/Proposal 无路径进入） | 契约要求，已满足 |
| 8 @528-532 | 四态 `Pending / Accepted / Rejected / Conflict` | `CandidateFactStatus` 四态同名 | 契约要求，已满足 |
| 8 @535 | 禁止静默覆盖 → 进入 `Conflict` | 同名判定 ⇒ `CONFLICT` + `conflict_with`，不增行不改旧行 | 契约要求，已满足 |
| 8 @536 | **高风险冲突 → Human Gate**（复用既有 Gate，不新建） | **未接入**（见 U2） | 契约要求存在，**未实现** |
| 8 @537 | 每次 Update 必须携带来源引用，否则不可写入 | `history_id` + `commit_id`（系统注入）+ `source_excerpt` 必填且为提交后正文逐字子串 | 契约要求，已满足（本轮前修正了"excerpt 可为空"的偏差） |
| 8 @538 | 复用 `KnowledgeValidator` 范式，不新建第二套校验 | `WorldModelFactValidator` 同型对象（无 IO、确定性、accepted/rejected 划分） | 契约要求，已满足 |
| 9 @540 | 候选事实表 additive，承载四态与来源 | `27.sqm` / `WorldModelCandidate.sq` | 契约要求，已满足 |
| 10 @542-547 | 五条测试 | 五条均有对应测试（见 E 节） | 契约要求，已满足 |
| 12 @555 | Completion Gate 四项 | 见 E 节 | 契约要求，已满足 |
| 13 @557 | 下一 Phase 输入：可增量更新、可追溯、有冲突状态 | 可追溯 ✓／冲突状态 ✓／"增量"受 U3 限制 | 部分 |

---

## C. U1–U10 决策

| 项目 | 正式契约证据 | 当前实现（事实） | 最终裁决 | 是否改代码 | 剩余风险 |
|---|---|---|---|---|---|
| **U1** 旁路失败归属 | §12 **无**失败/回滚文本（全节 `失败`/`回滚` 0 命中） | 逐类见 D 节；B 类被挂载点捕获并记录、C/D 类上抛 | **契约缺口 → 需负责人裁决**（选项见 F-1）。当前行为作为**临时实现选择**保留，不表述为契约要求 | 否（仅 KDoc 精确化 + 补测试） | 若裁定为上抛，需同步处理 3 个既有测试观察到的异常类型 |
| **U8** 失败标记载体 | §12.8 `Rejected = 已拒绝（记录理由，不写入）`（@531）**部分**支持；表用途 §12.9 只写"候选事实" | 一条 REJECTED 行：`fact_name` 显式非候选、`source_excerpt` 为空、`resulting_fact_id` 为空、不写任何事实行 | **契约缺口 → 与 U1 绑定裁决**；同时**已加固**：数据层显式声明非候选 | 是（标记名 `:214` + 测试断言） | 仍占用候选表；若未来做人审 UI，须按 `fact_name` 前缀过滤 |
| **U2** Human Gate | §12.8 @536 要求"高风险冲突 → Human Gate"；§0 对齐输入 @25 为"**可**入"；§12.12 @555 **未列** | CONFLICT 仅入候选队列；`listByStatus` 无生产消费方 | **未决契约项 → 本轮不接入**（理由见 F-2） | 否 | "高风险"无判据；独立 Gate 无批准消费方 ⇒ 现在接线会产生悬挂 Gate |
| **U3** EVENT / ADD | §12 **未**限定 kind / operation；§12.13 @557 提"可增量更新" | 仅 `EVENT` + `ADD` 可落地；其余 REJECTED 留痕 | **当前实现范围**（非契约边界）；措辞已修正；§12.13 解释**仍需确认** | 是（上一轮措辞 + 本轮沿用） | "既有事实变化"目前只能表现为 CONFLICT 或另名 ADD |
| **U5** §12.5"规则"腿 | §12.5 @514 写 `Extract（规则 + Index + AI）`；§12.7 @523 列 `core/engine（确定性抽取）` | 只有 AI 腿 + Index 腿；确定性部分在 `WorldModelFactValidator`（校验，非抽取） | **契约缺口（两种合理解释）→ 不新建规则引擎**（见 F-3） | 否 | 若解释为"必须存在独立规则式抽取"，则抽取段不完整 |
| **U4** ErrorMapper | §12 无错误映射要求 | `ErrorMapper.map` 无 `WorldModelUpdateException` 分支（知识更新有 2 个，`ErrorMapper.kt:58-59`） | **无需修改**：当前无路径把该异常交给 `map()`（挂载点显式 catch；`guard {}` 只包仓储调用） | 否 | 潜在隐患：若未来有调用方用 `guard { onCommit() }` 包装，会被误标为 `UnknownStorage` |
| **U6** participants / evidence | §12.9 @540 只要求"落库依赖 Phase 2 补列"（v27 已具备） | 恒为默认 `'[]'`；`ExtractedFact` 无对应字段 | **无需修改**（无可靠来源，不得凭空填充） | 否 | 事件事实信息密度低于 §12.9 预期（完成度说明） |
| **U7** §12.7 文件清单 | §12.7 @523 列出 6 处文件/模块 | 触及 `CommitUseCases`、`story/*`；未触及 `StoryWorldContextResolver`、`core/engine`、`StoryState.sq`、`CommitHistory.sq` | **已登记**：读取经既有 `StoryStateRepository`，确定性校验在 Application 层（与 `KnowledgeValidator` 同层）⇒ 职责由既有能力完成，非真实遗漏 | 否 | 无功能风险；仅口径差异 |
| **U9** `commitApprovedDraft` | Phase 3 要求"挂载点";生产链路由 Phase 1 提供 | 挂载点存在（`:398`），但该路径**无生产调用者**（Phase 1 遗留） | **既有遗留 → 仅登记**，不新增生产调用路径 | 否 | 该路径触发逻辑仅由测试覆盖 |
| **U10** 候选队列消费 | §12 未要求本阶段消费；§12.8 若接 Gate 才需要 | 仅有 `listByHistory` / `listByStatus` 查询，无 UI / 无 Worker | **当前范围外（后续阶段）** | 否 | 候选（含 CONFLICT）暂无人工处理入口 |

---

## D. 四类失败语义（当前实现事实）

| 类别 | 触发 | 当前行为 | 契约状态 |
|---|---|---|---|
| **A. 小说 Commit 本身失败** | Commit 事务异常 / STALE / DUPLICATE | 流水线**不执行**（挂载点在事务成功之后，`:215` 等）；零候选、零事实 | 由 §12"只在 Commit 之后"+Phase 1 Commit 契约确定；**测试锁定**：`failed commit produces no world model update at all` |
| **B. 抽取 / 解析 / 校验失败** | LLM 输出不可用、Parser 非法、单条候选不合格 | `onCommit` 对直接调用方**保持类型化异常**；挂载点捕获后记一条 REJECTED 追溯行（非候选、不写事实） | **§12 未定义 → U1**；不可复用 I4 日志（`ActivityUseCases.start` 拒绝悬挂引用，Commit 路径无 session） |
| **C. CandidateFact 持久化失败** | 候选 insert/update 异常 | 事务内抛出、**不被捕获**、原样上抛；整体回滚 | 不得伪装成"模型输出被拒绝" ✓ |
| **D. Event 持久化失败** | World Model 事实写入异常 | 同上；`updateStatus(ACCEPTED)`（`:119`）在 `saveEvent`（`:118`）之后同一事务内 ⇒ 不存在"ACCEPTED 但事实未写" | ✓；**测试锁定**：`event persistence failure rolls back candidates and never fakes accepted` |

事务一致性：候选 PENDING 落库 → 状态迁移 → 事实写入全部在 `storyState.inTransaction`（`:107`）内；幂等重放按 `history_id` 去重（`:73`）。Original 写保护不受影响：流水线只 `INSERT`，冲突路径零写入。

---

## E. §12.12 Completion Gate 核对

| Gate 项 | 结论 | 证据 |
|---|---|---|
| `Commit → World Model Update` 回路闭合 | 满足 | 4 处挂载点（`:215/312/398/551`）+ 端到端测试（artifact 路径、作者手改 same-row 路径） |
| 冲突零静默覆盖（断言通过） | 满足 | `conflict with existing fact becomes conflict and never overwrites it`：既有 `Event.what` 不变、行数不增、`conflict_with` 指向既有事实 |
| 抽取结果 100% 可追溯到 Commit | 满足 | 每行必带 `history_id` / `commit_id`；ACCEPTED 另带 `resulting_fact_id`；测试逐字段断言 |
| 草稿阶段 World Model 零变化 | 满足 | `draft and change preparation without commit leave world model untouched`（0 候选 / 0 事实 / 0 次 LLM 调用） |

§12.10 五条测试映射：未 Commit→草稿测试；Commit 增量+来源→`commit triggers…`；冲突→冲突测试；校验拒绝不污染→`forged source excerpt…`、`non add operation…`；幂等→`replaying the same commit is idempotent`。

---

## F. 未决缺口（需产品 / 架构负责人裁决）

### F-1 · U1 + U8：Commit 成功后的旁路失败（最重要）

- **条文**：§12 无旁路失败语义条文（唯一相关表述是 §12.1 @502"永不静默覆盖"与 §12.8 @537 来源要求）。
- **行为影响**：当前实现下，B 类失败被吸收为一条非候选 REJECTED 记录；C/D 类失败上抛给 Commit 调用方（此时 Commit 已持久化）。
- **选项**：
  1. **保留现状**（B 吸收+记录；C/D 上抛）：提交语义不被污染、失败可追溯到 Commit；代价 = 标记行占用候选表（§12 未明文）、与 C/D 处理不一致。
  2. 统一上抛（含 B 类）：语义统一、无特殊载体；代价 = 把已成功提交报告为失败（可能诱发误重试），且需同步调整 3 个既有测试所观察到的异常类型。
  3. 新增专用诊断载体（如失败事件表 / 复用 I4 需先给 Commit 路径定义 session 语义）：语义最干净；属新增持久化模型，需明确授权。
  4. 若裁定"B 类不属于失败"（即"输出不可用 ⇒ 本次无增量"，与"无差异"同级）：则不需任何载体，但需接受 LLM 异常输出不外显。
- **当前不替裁决**：保留选项 1 作为**临时实现选择**，代码注释与本文均已标注。

### F-2 · U2：Human Gate 的强制性与接入点

- **条文**：§12.8 @536 陈述式要求；§0 @25 为"可入"；§12.12 @555 未列入 Completion Gate。
- **前置缺口**：①"高风险"无判据；②缺少独立 Gate（`workflowId = null`）的批准/恢复消费路径（既有批准面 `WriterController.approveGate()` 只覆盖 chapter workflow gate）。
- **建议**：若判定为 Phase 3 必需，需同时给出风险判据与消费方；否则保持"冲突入候选队列"为当前阶段终态，并把接线列入后续阶段。

### F-3 · U5：§12.5"规则"腿的解释

- 解释 A：`规则` = 既有确定性能力（Index 查询 + 确定性校验）⇒ 当前实现已覆盖。
- 解释 B：`规则` = 独立规则式抽取路径（与 §12.7 @523 的 `core/engine（确定性抽取）` 对应）⇒ 当前缺失。
- 建议：由负责人确认取哪一解释；本轮**不新建规则引擎**。

### F-4 · U3：§12.13"可增量更新"的边界

- 当前仅"新增"语义可落地；UPDATE / REMOVE 的旧事实失效、历史追溯与 Original 只读交互语义未定。
- 建议：确认"增量"是否包含更新既有事实；若包含，需先定义失效语义（且不得削弱 Original 写保护）。

---

## G. 本轮变更与验证

**变更（最小）**
- `WorldModelUpdateUseCases.kt`：失败标记 `fact_name` 由 `(候选抽取失败)` 改为 `(非候选事实：抽取流程失败标记)`，并在常量 KDoc 声明其非候选性质；`recordFailure` KDoc 增加"任何读取方不得把它当作候选事实处理"。
- `WorldModelUpdatePipelineTest.kt`：失败标记测试新增两条断言（名称非候选、不得指向落地事实）。

**验证**
- 定向 Phase 3 三类测试：`WorldModelUpdatePipelineTest` 13 / `WorldModelUpdateParserTest` 12 / `WorldModelFactValidatorTest` 8，全部通过。
- 全量：`./gradlew test --continue --rerun-tasks`、`./gradlew :storage:test --rerun-tasks`、`./gradlew :app:desktop:jvmTest --rerun-tasks`、`git diff --check`（结果见配套 Completion Report；`runtime/api` 与 `runtime/dsh` 的测试任务未被执行，不得声称通过）。

---

## H. 正式裁决（2026-10-16）

> 本节记录 Phase 3 的四项正式架构裁决，是 F 节「未决缺口」的正式结论。
> 裁决只确定当前阶段的实施选择，不改变 v3.1 §12 的目标描述。

### H.1 · D-1 / U1 + U8：抽取失败记录

**正式裁决：采用方案①，阶段性保留现有实现。**

- B 类抽取、解析、校验失败继续由 Commit 挂载点吸收，并记录特殊 `REJECTED` 标记。
- C/D 类候选或事实持久化失败继续向上抛出，由现有事务机制保证回滚。
- 特殊失败标记不是正常候选事实，不得作为正常候选事实接受或写入 World Model。
- 该方案是阶段性实现裁决，不代表 §12 已完整定义此类失败的正式语义。
- 候选表语义纯净性、独立失败诊断载体列为后续工作项。
- 本次不新增诊断模型，不修改现有代码。

### H.2 · D-2 / U2：Human Gate

**正式裁决：采用方案①，本阶段暂不接入 Human Gate。**

- 保留当前 `CONFLICT` 候选记录行为。
- 本次不新增 Gate、Workflow、审批 UI 或恢复代码。
- 后续若实施，必须先定义：
  1. 可执行、可测试的高风险冲突判据；
  2. 明确的审批消费方；
  3. 审批后的状态迁移、事实写入与恢复语义。
- 该事项列为后续阶段工作项，不作为当前 §12.12 Completion Gate 的阻断项。

### H.3 · D-3 / U3：增量更新边界

**正式裁决：采用方案①，本阶段维持 ADD-only 过渡语义。**

- 当前 World Model Update 仅支持 `EVENT + ADD`。
- 非 ADD 操作继续按当前实现记录为 `REJECTED`，不得自行实现 UPDATE 或 REMOVE。
- 既有事实的更新、失效、替代与移除语义列为后续独立契约工作项。
- 后续设计必须明确 Original 与 Variant 的作用域边界、历史追溯及旧事实失效语义。
- 不修改现有 P2 Original 只读契约。

### H.4 · D-4 / U5：§12.5 中「规则」的解释

**正式裁决：采用解释 A，即现有确定性能力解释。**

- 本次 Phase 3 的「规则 + Index + AI」解释为现有确定性能力、Index 查询与 AI 抽取的组合。
- 不新增独立规则式抽取器。
- `core/engine` 在 §12.7 文件清单中的口径差异保留为参考项，不据此扩张当前实现范围。
- 本裁决只确定当前 Phase 3 的解释，不代表项目永久禁止未来引入规则式抽取。

### H.5 · Completion Gate 与正式封存状态

1. **§12.12 Completion Gate：已满足。** 依据现有四项可执行断言及已报告的测试证据。
2. **四项架构裁决：已完成。** D-1 至 D-4 均已有明确决定。
3. **Phase 3 正式封存：可以完成。** 表示本阶段实现范围、验证结果与架构裁决已记录，不代表所有后续工作均已实现。
4. **后续工作项仍然存在。** 至少包括：
   - 失败诊断载体与候选表语义纯净性；
   - Human Gate 完整链路；
   - UPDATE / REMOVE 契约；
   - 候选队列的生产消费方。

明确写入：

> Completion Gate 满足，不等于所有架构契约均已实现。

不得将后续工作项描述成已完成，也不得把阶段性实现选择误写为正式计划原文。
