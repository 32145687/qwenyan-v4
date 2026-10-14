# Qianyan 开发实施计划 v2

> 状态：规划基线（实施规划层，**不修改目标架构**）
> 性质：把「目标架构」转换为「可执行开发计划」。本文档**不含代码改动**。
> 目标架构基线：[Qianyan_开发总规划_连接优先架构.md](Qianyan_开发总规划_连接优先架构.md) @ `b738086`
> 代码基线：`feature/novel-ide` @ `b738086` + 工作树未提交工作（P1-01 / P0-3 / I14，本计划**不修改**它们）
> 角色：实施规划工程师 —— 架构总图已确定，本计划只负责拆解与排序。

---

## 1. 架构总图 → 实施计划的映射

| 架构总图章节 | 目标 | 对应 Phase |
|---|---|---|
| §2 连接优先原则 / §5 连接骨架 | 先契约、先接口、先最小连接、再填能力 | **贯穿全阶段**（每个 Phase 的第 5 项 Connection 即此原则落地） |
| §3 既有基础（不重建） | 复用 core / engine / provider / storage / app | Phase 0 基线 |
| §4 最终总体连接图 | 全链目标 | Phase 8 验收对象 |
| §6 World Model | Entity / Relationship / State / Event / Timeline / KnowledgeBoundary / Foreshadowing | **Phase 2** |
| §6.3 + §13 World Model Update | Canonical Change → Extract → Candidate → Validate → Update | **Phase 3**（依赖 Phase 1 的 Commit 事件） |
| §7 Context Engine | 「当前任务需要什么」→ ContextSnapshot | **Phase 4** |
| §8 Retrieval / Index | 两阶段：结构化 Index → Text / Semantic | **Phase 4**（仅第一阶段；第二阶段需裁决，见 §11 R1） |
| §9 Novel Tool Layer | 8 个核心 Tool，只暴露业务能力 | **Phase 5** |
| §10 Novel Skill | 9 个 Skill + 统一结构 | **Phase 6**（首落 2 个） |
| §11 Novel Agent | Task→Intent→Context→Skill→Tool→Result → 渐入自主 Loop | **Phase 7** |
| §12 Change Layer | Draft→Diff→Change→Validation→Gate→Commit→Canonical | **Phase 1** |
| §14 DSH Integration | RuntimeAdapter 契约 → 单 Tool → Loop → Session → Permission / Resume / Trace | **Phase 9** |
| §15 UI / Client | 两端只吃 Application API | **Phase 10**（Prototype 自 Phase 0 起并行） |
| §16 UI Prototype | 验证「客户端感觉」 | **并行轨**（不占 Phase 序号） |
| §18 状态总表 | 现状口径 | 本计划 §2 / §3 已按真实代码重算 |
| §19 开发阶段顺序 | 参考顺序 | **本计划调整了一个位置**（见 §1 末与 §13） |
| §20 统一连接规则 | 新功能必须能说明连接路径 | Phase 0 的「准入模板」（每 Phase 第 7 项 Contract 复用） |
| §21 三条边界 | Agent 不碰 Storage / 草稿≠Canonical / DSH 非业务层 | 三条作为**每 Phase 的 Completion Gate 固定项** |

### 与总图 §19 的唯一顺序偏离（需裁决）

总图把 **World Model 定为 Phase 1**。本计划把 **Canonical + Change Layer 提到 Phase 1**，World Model 顺延为 Phase 2。

**理由（来自真实代码 + 总图 §13 自身约束）**：§13 明确「World Model 不能在 AI 草稿阶段直接更新」，即 Update 管道的**唯一触发源是 Commit（Canonical Change）**。而当前代码里 **Canonical 等价于 `latestByChapter`（`Draft.sq:43-44`），不存在 Commit 事件** —— 若先做 World Model，其写入路径将**没有合法挂载点**，只能继续空转（正是过去「六张表零写入者」的成因）。因此 Phase 1 必须先造出「Commit 事件」这一事实源。

---

## 2. 当前真实代码状态

以「是否存在**生产调用者**」为准（逐条验证，非类名存在性）。

| 层 | 代码存在 | 生产可达 | 真实结论 |
|---|---|---|---|
| `core:model` | 全量（含 40+ 强类型 ID、19 种 `OverridableKind`） | 是 | 复用；仅 9 种 kind 有表 |
| `core:engine` | TxtPipeline / ControlledMarkdown / AnalysisInputBuilder | 是 | 复用；确定性能力良好 |
| `provider`（api + impl） | 4 网关（Mock / DeepSeek / MiMo / Offline） | 是 | **真实可用**；`stream=false` 写死、`usage` 无消费者、retry 死配置 |
| `storage` | 21 表族 + 迁移 1–21（+ 未提交 `24.sqm`） | 是 | 主体可复用；**Event / CharacterState / Character 共 17 字段持久化丢失**；迁移编号断裂 |
| `application` | 165 文件 / 17 子包 | 部分 | 大量 UseCase **无生产 caller**（Change / Commit / WorkingDraft / ActionPolicy / StoryState / Override / Reveal / Foreshadow / NarrativeState / Backup / Index…） |
| Change Layer | 七段全实现 | 否 | 唯一驱动者 `NovelAgent` 在 `app/*/src/main` **零调用**；**7 条直写 `DraftRepository`** |
| Canonical | 隐式 | 部分 | = `latestByChapter`，**无 isCommitted、无 Commit 记录** |
| World Model（六表） | 表 + 仓储 + mapper | 否 | **写侧零调用者**；读侧被 prompt 边界丢弃（`characters = emptyList()`） |
| Context | **四套并存** | 部分 | `ContextEngineUseCases`（只服务 Inspector）/ `PlanningContextAssembly`（写作真链路）/ `ChapterContextCompileUseCases`（仅 UI）/ `WriterGateway.loadContext` |
| Retrieval | 内存 index | 否 | `ProjectIndexUseCases` 是 `LinkedHashMap`、不索引正文、`find()` 无 caller；`.sq` 中 `LIKE/GLOB/FTS` = **0** |
| Novel Tool | 7 个只读 Tool + registry / executor | 否 | 唯一 invoke 点在不可达的 `NovelAgent.kt:651`；结果无回流 |
| Novel Skill | 硬编码 5 个 | 否 | `Skill` 的 `requiredContext / expectedActions / bindings / capabilities` **无读取者**；`KNOWLEDGE_WORLD_MODEL` 无执行绑定 |
| Novel Agent | 七相位 + 真实 while-loop | 否 | 生产链是固定 pipeline（`intentType` 硬编码 CONTINUE）；`AgentRuntime` 被空注册表废成单次调用 |
| Runtime / DSH | `:runtime` 空壳 | 否 | 适配器只在 `feature/runtime-dsh@9a6f7c0`；**工作树内不存在**双 Runtime |
| Android | Writer / Reader | 是 | **Writer = 当前唯一完整受控闭环**（含真实 approve 按钮）；Reader 读不到 TXT 导入章节 |
| Desktop | 部分（I14 未提交） | 部分 | 写作链真实可用；**P0-3 已修**（「通过确认」→ `approveGate`，3 测试通过） |

---

## 3. 当前生产链路

**唯一真正在跑的写作链（桌面）：**

```text
WritePage「继续写」→ WriterController.continueWriting() → WriterFacade
  → ChapterWorkflowFacade.advance → WorkflowOrchestrator.runForward:90（guard<16 固定流水线）
      ├─ executePlanning:151 → PlanningContextAssembly.assemble → PlannerAgent
      ├─ executeWriting:165  → WritingExecutionUseCase:52 → PlanningContextAssembly → WriterAgent → LLM
      │                        → DraftParser → DraftRepository.save:71（status=WRITTEN）
      ├─ executeCritique:199 → CritiqueAgent（布尔 PASS/REVISION 驱动分支）
      ├─ createPendingGate:272 + WAITING_HUMAN:273（真实阻断）
      └─ 「通过确认」→ approveGate:294 → confirmFinalDraft → gate RESOLVED + Draft CONFIRMED  ← P0-3 已接通
```

进入 prompt 的真实内容：**Novel 元信息 · 当前 Draft 正文 · 词库 · Author · Foundation · DecisionPolicy · Memory 字符串**。

不进 prompt：**前文（`assemble(request, null, null)`）· 人物（`characters = emptyList()`）· 世界 / 事件 / 时间线 / 伏笔（`worldContext` 无消费者）**。

**唯一真正闭环的领域写入**：KU 提案 → `WorkflowOrchestrator:285` → `KnowledgeUpdateExecutionUseCase:76-83` → `MemoryEntry` → 下一轮 `orderedVisible` → prompt。

**另一条完整但不可达的链（TEST-ONLY）**：`NovelAgent` 七相位 → WorkingDraft → Validation → Diff / Change / Artifact → ActionPolicy → Commit → CommitHistory → Canonical。

---

## 4. 当前与目标架构的差距

| # | 目标（总图） | 当前 | 差距性质 |
|---|---|---|---|
| D1 | §12 只有 Commit 后才成为 Canonical | Canonical = latest Draft；7 条直写路径 | **事实源缺失**（阻塞 D4/D5） |
| D2 | §5.6 Agent → Change Layer 调用链打通 | 链有实现，入口不可达；桌面确认按钮刚接通 | 接线 |
| D3 | §6 World Model 是「当前世界状态」 | 六表存在但**零写入者**、17 字段丢失、读侧不进 prompt | 连接 + migration |
| D4 | §6.3 / §13 Canonical Change → WM Update | **完全没有** Extract / Validate / Update | 新建（依赖 D1） |
| D5 | §7 Context Engine 是唯一「决定需要什么」入口 | **四套并存**、互不一致、无 Snapshot 持久化 | 收敛 |
| D6 | §8 Retrieval 两阶段 | 无正文检索；index 是内存 map | 新建（第二阶段与 FD-10 冲突，见 R1） |
| D7 | §9 Novel Tool 只暴露业务能力 | 7 只读 Tool 存在但无人调用；`AgentRuntime:84` 绕过 ActionPolicy | 接线 + 收口 |
| D8 | §10 Skill 统一结构 | 硬编码 5 个、字段无读取者、9 项能力多不存在 | 新建契约 |
| D9 | §11 自然语言任务入口 | **不存在**（意图硬编码；`analyzeIntent` 不可达） | 新建 |
| D10 | §21 边界二（草稿 ≠ Canonical） | 被 D1 破坏 | 由 Phase 1 修复 |
| D11 | §3.3 禁止 UI → Repository | 桌面 **8 处直连仓储** | 收口 |
| D12 | §18 Storage 修 migration / schema | 迁移编号 21→24 断裂、12 个版本期望红测试、8 个 Windows DB lock 失败 | 基线修复（Phase 0） |
| D13 | 用户路径完整性 | **TXT 导入不生成 Chapter** ⇒ 导入即数据黑洞 | 连接缺失（Phase 0） |
| D14 | §14 DSH 是 Runtime | 适配器不在工作树；产品动机仍无锚点 | **最后做**（Phase 9） |

---

## 5. Phase 总览

| Phase | 名称 | 目标架构落点 | 依赖 | 并行 | 出口门（摘要） |
|---|---|---|---|---|---|
| **0** | 连接基础与基线恢复 | §2 原则 + §3 基线 + §5 骨架 | — | Prototype 轨 | 构建 / 测试基线可信 + 三条边界无新增违例 |
| **1** | Canonical + Change Layer | §12、§13 前提、§21 边界二 | P0 | — | AI 产出**只能**经 Commit 成为 Canonical |
| **2** | World Model（读侧 + 字段补全） | §6.1 / §6.2 | P1（非必需，建议） | 与 P1 可部分并行 | Agent 能经 Tool 读到真实 WM，且不再丢字段 |
| **3** | World Model Update Pipeline | §6.3、§13 | **P1 + P2** | — | Commit 后 WM 增量更新，且草稿阶段绝不更新 |
| **4** | Context Engine + Retrieval | §7、§8（一阶段） | P2 | — | 四套 Context 收敛为一，Snapshot 可复现 |
| **5** | Novel Tool Layer | §9 | P4 | — | 8 Tool 有生产 caller，结果回流 LLM |
| **6** | Novel Skill Layer | §10 | P5 | — | ≥2 Skill 端到端跑通（契约统一） |
| **7** | Natural Language + Novel Agent | §11 | P6 | — | 一句话任务 → 结果（先固定顺序，后自主） |
| **8** | 完整写作 Vertical Slice（验收） | §4、§22 | P1–P7 | — | §9 的 15 步垂直切片全绿 |
| **9** | Runtime Adapter / DSH | §14 | P8 + 动机裁决 | — | 契约先于 DSH；单 Tool 先于 Loop |
| **10** | Desktop / Android 产品化 | §15 | P8 | 两端并行 | 两端只经 Application API |

**并行轨（不占序号）**：UI Prototype（§16）自 Phase 0 起可跑，出口 = 只验证「感觉」，**不产出信息架构**。

---

## 6. Phase 0 — 连接基础与基线恢复

**1. Goal**：让后续每个 Phase 都能把「真实生产链验证」作为可信判据：构建 / 测试基线自洽 + 三条边界无新增违例 + 两条已存在能力之间补齐缺失连接。

**2. Architecture Target**：§2（先连接）+ §3.3（禁止 UI → Repository）+ §21（三边界）。

**3. Current State**：
- P0-3 已完成（`WritePage.kt` 3 处 `onApprove`；`DesktopWriterTest` 3/3 PASSED）。
- P1-01 的 storage 闭合已提交（`d75bf34`）；3 处 `WorkflowId?` 收尾在工作树（`storyFoundation / orchestrator / service`，`?: throw` 收口）。
- **未提交的 `24.sqm` 使 `Schema.version` 从 22 变为 25**，导致 12 个测试期望失败（10 `:storage:test` + 2 `DesktopGraphSmokeTest`）；另有 8 个 `:storage:test` 因 Windows 临时 DB 句柄未关而失败。
- **TXT 导入只写 `TxtDocument / TxtChapter / TextBlock`，不建 `Chapter`**（桌面三处导入还丢弃返回值）。
- 桌面 8 处 UI 直连仓储（`ListPages` / `StoryWorldPages` / `MaterialPages`）。
- `SqliteBackupStore.restoreBackup` 因 `DELETE FROM Novel` 撞 `novel_original_delete_protect` **必然失败**。

**4. Gap**：基线不可信（12 + 8 红测试）；两条既有数据模型之间缺连接；两处边界违例。

**5. Connection**：

```text
现在：TxtChapter → （断） → Chapter          本 Phase：补 Chapter 提升
现在：UI → Repository（8 处）                本 Phase：改经 Application API
现在：Schema v22 期望 vs v25 实际（红）       本 Phase：期望与迁移编号归位
```

**6. Files / Modules**（仅列已验证存在的）：
- `storage/src/main/sqldelight/com/qianyan/storage/db/*.sqm`（`24.sqm` 编号归位）、`storage/src/main/kotlin/com/qianyan/storage/db/DatabaseInitializer.kt`、`storage/src/test/java/com/qianyan/storage/*MigrationTest.kt`（期望同步）
- `application/src/main/kotlin/com/qianyan/application/usecase/txt/TxtUseCases.kt`（提升点）、`usecase/chapter/ChapterUseCases.kt`、`storage/.../repository/SqliteTxtRepository.kt`、`ChapterRepository.kt`
- `app/desktop/src/jvmMain/kotlin/com/qianyan/app/desktop/ui/pages/{ListPages,StoryWorldPages,MaterialPages}.kt`（去直连）
- `storage/.../repository/SqliteBackupStore.kt`
- `app/desktop/src/jvmTest/kotlin/com/qianyan/app/desktop/DesktopGraphSmokeTest.kt`

**7. Contract**：**新增 0 个接口**。TXT 提升复用既有 `ChapterRepository.createNextChapter` 语义（或新增一个 `TxtPromotionUseCases`，需在 Phase 0 内裁决其一，**不得两套**）。`24.sqm` 的意图（`WorkflowHumanGate.workflow_id` 放宽为可空）保留，只修编号 / 期望。

**8. Storage**：**需要**。`24.sqm` 编号归位（与 `Schema.version` 语义一致，实测 SQLDelight 版本 = 编号最大值 + 1）；同步 12 处版本期望。**不新增表族、不改语义**。

**9. Tests**：
- `:storage:test` 全绿（含 8 个 DB lock 用例，需先判定是既有 Windows 卫生问题还是 P1-01 引入的句柄泄漏）。
- `:app:desktop:jvmTest` 全绿（含 2 个版本期望）。
- 新增：`导入 TXT → 章节列表可见 → Reader 有内容`（1 个端到端断言）。
- 新增：`桌面 UI 无 Repository 直连`（沿用既有 `desktop writer ui has no repository…` 守卫测试范式，扩展到 `pages/`）。

**10. Risks**：① `24.sqm` 已随工作树演进，改编号可能影响未提交的 P1-01（需确认改法）；② DB lock 若判定为既有问题，则「全绿」门不可达，需降级为「仅版本类必须绿」；③ TXT 提升的幂等（重复导入不得重复建章）。

**11. Completion Gate**：
- `./gradlew test` 与 `:app:desktop:jvmTest` 除**已登记豁免项**外 0 failures；
- TXT → Chapter 端到端断言通过；
- UI 直连仓储数 = 0；
- 三条边界：无新增违例（本条在后续每个 Phase 重复检查）。

---

## 7. Phase 1 — Canonical + Change Layer

**1. Goal**：让「Commit 事件」成为**唯一**的正典变更事实源 ⇒ 为 Phase 3 提供挂载点，修复 §21 边界二。

**2. Architecture Target**：§12 全链 + §13 前提 + §21 边界二（「Canonical 不再等价于 latest Draft」）。

**3. Current State**：七段实现齐全；`NovelAgent` 是唯一驱动者但零 UI caller；7 条直写；`saveContent` 原地改正典行；`resultingDraftId` 已可用（`d75bf34` 补上 `getByResultingDraftId`）；人工确认在 Android 可用、桌面刚接通。

**4. Gap**：入口（谁调用）；判定（哪条 Draft 是正典）；`saveContent` 未纳管。

**5. Connection**：

```text
现在：AI 产出 → DraftRepository.save → latestByChapter（隐式正典）
本 Phase：AI / 人工产出 → WorkingDraft → Validation → Diff → Change / Artifact
         → ActionPolicy / Human Gate → Commit → CommitHistory → Canonical（显式判定）
```

**6. Files / Modules**：
- `application/.../usecase/draft/WorkingDraftUseCases.kt`、`change/ChangeUseCases.kt`、`commit/CommitUseCases.kt`、`action/ActionPolicyUseCases.kt`
- `application/.../usecase/writing/WriterUseCases.kt`（`saveContent` 纳管）、`writing/WritingExecutionUseCase.kt`、`writing/revision/RevisionExecutionUseCase.kt`、`writing/confirmation/ConfirmationExecutionUseCase.kt`、`chapter/ChapterWritingUseCases.kt`
- `application/.../usecase/workflow/{WorkflowOrchestrator,WorkflowService,ChapterWorkflowFacade}.kt`
- `storage/.../repository/{DraftRepository,CommitHistoryRepository}.kt`、`sqldelight/.../{Draft,CommitHistory}.sq`
- `app/desktop/src/jvmMain/.../ui/pages/WritePage.kt`、`ui/write/WriterController.kt`

**7. Contract**：改动 `WorkingDraft / Change / Commit` 的**入口接线**；`saveContent` 语义升级（沿用已裁决的**定义 C + 方案 i + (i-b)：保存即批准**，不新增第二道确认）；`getByResultingDraftId` 作为「是否已提交」的唯一判据（**不新增 `isCommitted` 字段**）。

**8. Storage**：**不需要新表**。用已提交的 `CommitHistory.resulting_draft_id` 反查即可。若要「正典 = 已 Commit 的 Draft」作为独立判定，只需**只读**查询（已有）。

**9. Tests**：① `AI 产出 → 人工确认 → CommitHistory 有记录 → Canonical == 该 Draft`；② `saveContent 后若该 Draft 已 Commit，必须产生新的 Commit 记录（而非原地改）`；③ `未 Commit 的 Draft 不得被任何「正典读取者」读到`；④ `重复 Commit 幂等`。

**10. Risks**：① 这是全项目最贵的接线（7 条写入路径都要收口）；② 收口后 `latestByChapter` 的既有消费者（Reader / ListPages / Android Reading）会读到「非正典」内容，需同时定义「读者应看哪一版」；③ 与未提交的 P1-01（Gate 解耦 / `WorkflowModels` nullable）耦合，需先合流。

**11. Completion Gate**：直写 `DraftRepository` 的生产路径 = **0**（除 Commit 内部）；`CommitHistory` 在生产中有真实写入；`Draft = 隐式 Canonical` 消失；Android / 桌面双端确认路径统一到同一 Gate。

---

## 8. Phase 2 — World Model（读侧连接 + 字段补全）

**1. Goal**：让 World Model 从「六张空表」变成「Agent 可读、字段不丢、可裁决的正典世界状态」。

**2. Architecture Target**：§6.1 七个核心对象 + §6.2（**不直接服务 UI**）。

**3. Current State**：`StoryState.sq` 六表 + `StoryStateRepository` + `StoryWorldContextResolver`（合并算法正确且确定性）；但 `addXxx / overrideXxx` **零生产调用者**，`PlanningContextAssembly:111 characters = emptyList()`，`worldContext` 无 prompt 消费者；17 字段丢失（Event 7 / CharacterState 7 / Character 3）；`KnowledgeEntry / StoryConflict / Location / Relationship` **有 ID 无表**；`OverridableKind` 19 种仅 9 种落表。

**4. Gap**：写侧无入口；读侧到 prompt 断；字段丢失；悬空引用。

**5. Connection**：

```text
现在：Resolver → worldContext → （丢弃）
本 Phase：Resolver → NovelTool.getCharacter / getCharacterState / getRecentEvents…
        → ContextEngine → prompt（人物 / 世界 / 事件 / 时间线 / 伏笔）
```

**6. Files / Modules**：`core/model/.../{character,world,timeline,story,knowledge}/*.kt`；`storage/.../StoryState.sq` + `StorageMappers.kt` + `StoryStateRepository.kt`；`application/.../usecase/story/StoryStateVariantUseCases.kt`、`override/OverrideUseCases.kt`、`writing/context/StoryWorldContextResolver.kt`、`writing/planning/PlanningContextAssembly.kt`；`agent/tool/*`（读侧契约）。

**7. Contract**：**读侧**先固化 7 个 Tool 契约（§9 的 `getCharacter*` / `getRecentEvents` / `getTimeline` / `getForeshadowing` / `getKnowledgeBoundary`）；**写侧**入口保持 `StoryStateVariantUseCases`（不新建第二套），但只允许被 Phase 3 的 Update 管道与人工编辑调用。

**8. Storage**：**需要 additive migration** —— 补 `Event`（what / where / cause / consequence / participants / evidence / factLevel）、`CharacterState`（location / currentMotivation / relationships / knownFacts / unknownFacts / abilities / source）、`Character`（goals / fears / description）；并按裁决决定 `KnowledgeEntry / StoryConflict / Location / Relationship` 是否落表（当前是悬空 ID）。

**9. Tests**：① 六类实体「存 → 读」往返无损（逐字段断言，覆盖本次新增列）；② `resolve()` 结果进入 prompt（断言 prompt 含人物块）；③ Original 不可改写（既有触发器 + 域校验）；④ Variant 合并四情形（INHERIT / OVERRIDE / REMOVE / ADD）保持确定。

**10. Risks**：① **`Memory` 与 `World Model` 谁 canonical 仍未裁决** —— 不裁决就动手会再造一套孤儿（本计划**不替产品决定**）；② 19 种 kind 收敛不当会破坏 Variant 隔离；③ 补列后若写入者仍缺，等于把「空表」变成「半空表」。

**11. Completion Gate**：Agent 侧能经 Tool 读到真实人物 / 世界 / 事件 / 时间线 / 伏笔；往返无损测试全绿；World Model **不被 UI 直连**（§6.2）。

---

## 9. Phase 3 — World Model Update Pipeline

**1. Goal**：Commit 之后自动把「正文变化」沉淀为 World Model 增量。

**2. Architecture Target**：§6.3（规则 / Index / AI 三方分工 + Validation 防污染）+ §13（**草稿阶段绝不更新**）。

**3. Current State**：**完全不存在**。可复用的原语：`KnowledgeUpdateExecutionUseCase`（Memory 的 Extract → Validate → Persist 已跑通，含 `KnowledgeValidator`、`deactivateByTarget` + `saveEntry` 单事务、Workflow 人门）；`TextDiffer`；`ControlledMarkdown`；`AnalysisInputBuilder`。

**4. Gap**：Extract / Candidate Facts / Validate / Update 四段全缺（且缺「由 Commit 触发」的挂载点）。

**5. Connection**：

```text
Phase 1 产出：Commit 事件（含 resultingDraftId + previousContent / resultingContent）
本 Phase：Commit → 差异 → Extract（规则 + Index + AI）→ CandidateFacts
        → Validate → World Model Update → （可追溯来源）
```

**6. Files / Modules**：`application/.../usecase/writing/knowledgeupdate/*`（范式来源）、`commit/CommitUseCases.kt`（触发点）、`writing/context/StoryWorldContextResolver.kt`（读取面）、`story/*`（写入面）、`core/engine`（确定性抽取）、`storage/.../{StoryState.sq, CommitHistory.sq}`。

**7. Contract**：新增 `WorldModelUpdatePipeline`（Extract / Validate / Update 三段各一接口）；**复用** `KnowledgeValidator` 的校验范式；Update 必须携带**来源引用**（哪个 Commit / 哪段正文），否则不可写入。

**8. Storage**：可能需一张「候选事实」暂存（additive）；`Event.participants / evidence / factLevel` 的落库依赖 Phase 2 的补列。

**9. Tests**：① `未 Commit 的 Draft 变化 → WM 不变`（**边界二的可执行断言**）；② `Commit → WM 增量 + 来源可追溯`；③ `Validation 拒绝错误事实 → WM 不被污染`；④ 幂等（同一 Commit 重放不重复写入）。

**10. Risks**：① AI 抽取的不确定性会污染 WM（必须落地「候选 + 校验 + 人工门」，沿用 KU 的成熟范式）；② 全文检索缺失时「定位相关正文」只能靠规则 + 结构化 Index（§8 第一阶段）；③ 若 Phase 2 的 canonical 裁决未做，Update 目标不明确。

**11. Completion Gate**：§13 回路闭合（Commit → WM Update）；**回归证明**：草稿阶段 WM 零变化；抽取结果 100% 可追溯到 Commit。

---

## 10. Phase 4 — Context Engine + Retrieval

**1. Goal**：把「当前任务需要什么」变成一个**唯一入口**，并让 Snapshot 可复现、可审计。

**2. Architecture Target**：§7（Context Engine）+ §8 第一阶段（结构化 Index）+ §5.1 / §5.2（Agent → Context → Tool 骨架）。

**3. Current State**：四套并存（`ContextEngineUseCases`（只服务 Inspector）、`PlanningContextAssembly`（写作真链路）、`ChapterContextCompileUseCases`（仅 UI）、`WriterGateway.loadContext`）；`packVersion` / `isStale` 只保护没人消费的产物；Snapshot 不持久化；Retrieval 不存在（`ProjectIndexUseCases` 是内存 map、不索引正文、`find()` 无调用者）。

**4. Gap**：收敛为一条；Snapshot 落库；结构化 Retrieval。

**5. Connection**：

```text
现在：WriterAgent ← PlanningContextAssembly（绕开 I6）
本 Phase：所有入口 → ContextEngine（唯一）→ ContextSnapshot（落库）→ Agent
Retrieval：NovelTool → Retrieval → Index → Canonical / World Model
```

**6. Files / Modules**：`application/.../usecase/context/{ContextEngineUseCases,ContextSources}.kt`、`usecase/writing/planning/PlanningContextAssembly.kt`、`usecase/lcl/ChapterContextCompileUseCases.kt`、`usecase/index/ProjectIndexUseCases.kt`、`core/model/.../{context,lcl,projectindex}/*`、`usecase/workspace/WorkspaceUseCases.kt`（Inspector 消费方）。

**7. Contract**：**保留** `ContextRequest → ContextEngine → ContextSnapshot` 为唯一契约；`PlanningContextAssembly` 与 `ChapterContextCompileUseCases` **降级为 ContextEngine 的 Source / 视图**，不得再各自拼 prompt；Snapshot 必须持久化（§5.1「ContextSnapshot」）。

**8. Storage**：**需要** additive：ContextSnapshot 落库（当前 `packId / packVersion` 只进内存 `RunState`）；Index 若要跨进程可用需落表（否则维持内存 + 每次重建）。

**9. Tests**：① `同一输入 → 同一 Snapshot（确定性）`；② 写作链与 Inspector 得到**同一** Context（消除四套不一致）；③ `作者可读「AI 这次看到了什么」`；④ 检索：`按 chapter / character / event / timeline 结构化取回`。

**10. Risks**：① **§8 第二阶段（Text / Semantic Search）与 FD-10 封存（全文搜索 / RAG / Vector）冲突** → 见 R1；② 收敛过程不得引入第三套 Context；③ Snapshot 落库需 budget（避免无限增长）。

**11. Completion Gate**：Context 入口数 = **1**；Snapshot 可持久化且可复现；结构化 Retrieval 覆盖 §8 第一阶段四类 Index；budget / 优先级机制（既有 `ContextPriorities`）仍生效。

---

## 11. Phase 5 — Novel Tool Layer

**1. Goal**：Agent 通过**受控接口**进入小说世界（§9 八个 Tool）。

**2. Architecture Target**：§9 + §5.2 / §5.3（Tool 不暴露 SQL / Repository，不绕过 Domain）。

**3. Current State**：`agent/tool` 有契约 / Registry / Executor / 类型化错误（**通用运行时已存在，勿重建**）；`application/.../usecase/tool/ProductTools.kt` 7 个只读工具；唯一 invoke 点在不可达的 `NovelAgent.kt:651`；`AgentRuntime.kt:84` 有一条**绕过 ActionPolicy 与 ToolCallLog** 的执行路径。

**4. Gap**：Tool 集合不全（缺 `getCharacterState / getRecentEvents / getTimeline / getForeshadowing / getKnowledgeBoundary`）；结果无回流；旁路未收口。

**5. Connection**：

```text
现在：Tool 注册表非空但那侧不可达；旁路 execute 绕过策略
本 Phase：Agent → ToolRegistry（允许集）→ ToolExecutor → ActionPolicy → ProductTool → UseCase → Repository
         → ToolResult → 回填 LLM
```

**6. Files / Modules**：`agent/tool/src/main/kotlin/com/qianyan/agent/tool/*`、`core/model/.../tool/ToolModels.kt`、`application/.../usecase/tool/{ProductTools,ProductToolService,ReadOnlyProductTool}.kt`、`agent/runtime/.../AgentRuntime.kt`、`application/.../usecase/action/ActionPolicyUseCases.kt`。

**7. Contract**：Tool 契约**保持不动**（已冻结式设计）；只**补实现** + **单一路径**（关闭 `AgentRuntime:84` 的旁路，统一经 `ProductToolService`）。

**8. Storage**：**不需要**（只读 Tool；写操作属 Change Layer，不在本 Phase）。

**9. Tests**：① 每个 Tool 有生产 caller 且结果进入 prompt / 决策；② `ToolCallLog` 在有生产调用后可在 Inspector Activity 看到（当前该 Tab 恒空）；③ 旁路断言：`ToolExecutor.execute` **只能**经 `ProductToolService` 被调用（守卫测试）。

**10. Risks**：① 补齐 Tool 后若 Context 未收敛（Phase 4），会强化「两套取值路径」；② 写类 Tool（`prepareChange` / `createProposal`）**必须等 Phase 1**，否则又绕开 Change Layer。

**11. Completion Gate**：§9 第一版 8 个 Tool 全部有生产 caller；工具结果回流 LLM；ActionPolicy 覆盖全部 Tool 调用；无旁路。

---

## 12. Phase 6 — Novel Skill Layer

**1. Goal**：把「小说方法论」变成可复用、可组合、可测试的 Skill 契约。

**2. Architecture Target**：§10（9 个 Skill + 统一结构 Task → Skill → Context → Tools → Execution → Result）。

**3. Current State**：`CoreSkills` 硬编码 5 个；`SkillRegistry.selectSkill` 用静态偏好表；`Skill.requiredContext / expectedActions / bindings / capabilities` **无执行读取者**；9 项能力中「长篇续写 / 改写 / 剧情规划 / 小说分析」是 PARTIAL / DUPLICATED，「人物一致性 / 伏笔管理 / 场景设计 / 节奏控制」是 ORPHAN，「文风保持」实际靠 prompt 注入。

**4. Gap**：Skill 契约未被消费；能力与 Skill 未对齐（能力散落在 Agent 里）。

**5. Connection**：

```text
现在：Task → （硬编码 when）→ 4 个 Agent 方法
本 Phase：Task → Skill Selection（读 Skill 契约）→ Skill → ContextEngine → NovelTools → Result
```

**6. Files / Modules**：`core/model/.../skill/SkillModels.kt`、`agent/agents/.../{CoreSkills,SkillRegistry}.kt`、`application/.../usecase/agent/NovelAgent.kt`（`selectSkill` / `executeSkill`）、`usecase/writing/{WriterAgent,PlannerAgent}.kt`、`usecase/writing/{critique,revision,knowledgeupdate}/*`。

**7. Contract**：**固化 Skill 契约**（`purposes / allowedTools / requiredContext / expectedActions / bindings` 必须**被读取**，否则字段无意义）；首落 **2 个**：`LongContinuationSkill`、`NovelAnalysisSkill`（它们已有最接近的实现可复用）；其余 7 个先**只在契约层声明**，不实现。

**8. Storage**：**不需要**（Skill 是编排，不是数据）。

**9. Tests**：① Skill 声明与执行绑定一致（`when` 覆盖全部可匹配 Skill，消除 `SKILL_NOT_EXECUTABLE` 陷阱）；② `requiredContext` 被真正读取（守卫测试）；③ 2 个落地 Skill 端到端。

**10. Risks**：① 若把 9 个都做完，会变成「能力堆叠」（§20 要求每个都能说清连接路径）；② Skill 与 Agent 方法职责重叠（当前 `executeSkill` 已是派发点）。

**11. Completion Gate**：Skill 契约字段全部有读取者；≥2 Skill 端到端；新增 Skill 必须自带「连接说明」（§20）。

---

## 13. Phase 7 — Natural Language + Novel Agent

**1. Goal**：把产品定位的第一条变成代码：**作者说一句话 → Agent 完成**。

**2. Architecture Target**：§11（Task → Intent → Plan → Context → Skill → Tool → 执行 → Validation → Result）+ §22 上半环。

**3. Current State**：**无自然语言入口**；意图硬编码 `IntentType.CONTINUE`（`WorkflowOrchestrator:637`）；`NovelAgent.analyzeIntent` 是关键词规则且不可达；生产链是固定 pipeline（`guard<16`、相位硬编码、后继表硬编码）；`AgentRuntime` while-loop 被空注册表废成单次调用。

**4. Gap**：入口 + Intent + 能力选择 + （后期）自主 loop。

**5. Connection**：

```text
本 Phase：作者输入 → Intent（可达）→ ContextEngine → Skill 选择 → Tool 调用 → 执行 → Result
后期：+ 多步 loop / 自主决定下一步（§11「再逐步加入」）
```

**6. Files / Modules**：`application/.../usecase/agent/NovelAgent.kt`、`usecase/workflow/WorkflowOrchestrator.kt`、`usecase/writing/planning/*`、`agent/{runtime,agents}`、UI 侧 `app/desktop/.../ui/pages/*`（入口，属 Prototype 轨）。

**7. Contract**：新增「任务入口」契约（`NaturalLanguageTask → Intent → AgentRun`）；`NovelAgent` 从 `TEST-ONLY` 转为生产可用**或**明确废弃（二选一，不得两条并行）。

**8. Storage**：需要 Task / Session 承载（`Task` / `Checkpoint` / `AgentSession` 已存在，可复用）；Run 的可审计记录复用 `Activity`。

**9. Tests**：① 5 类真实语句（继续写 / 修改 / 查询 / 检查 / 规划）各自走到正确 Skill；② 意图不可达的旧路径已消除（守卫测试）；③ `Activity` 有真实写入（Inspector 不再恒空）。

**10. Risks**：① 与 Phase 5 / 6 的顺序耦合（Tool / Skill 未就绪时 Agent 无能力可选）；② 意图识别若用 LLM，需明确「不确定时回问作者」（不得静默猜）；③ 权限：Agent 动作必须经 ActionPolicy（`UPDATE_WORLD_MODEL` 等 NeedsHuman 分支当前从未被触发）。

**11. Completion Gate**：一句话任务在生产链上跑通至少 2 类；无固定硬编码意图；每次运行可审计（Activity + ContextSnapshot）。

---

## 14. Phase 8 — 完整写作 Vertical Slice（验收）

**1. Goal**：以单条真实用户路径端到端验收 §22 闭环。

**2. Architecture Target**：§4 总体连接图 + §22 最终闭环。

**3. Current State**：见 §9（下节 MVP Vertical Slice）的逐段标记。

**4. Gap**：见 §9 的缺口统计。

**5. Connection**：整链一次跑通，且**每一跳都有真实生产 caller**。

**6. Files / Modules**：横跨 `app/desktop`、`application`、`agent/*`、`storage`、`core/*`（本 Phase **只做端到端验收与必要收口**，不开新功能）。

**7. Contract**：全部既有契约；本 Phase 不得新增顶层契约（§2 原则的反向检验）。

**8. Storage**：不需要新迁移（验收）。

**9. Tests**：§9 的 15 步切片做成 1 个端到端测试 + 每步的「断点回归」断言（防回退）。

**10. Risks**：① 端到端测试涉及 LLM，需用 Mock / Offline 网关保证确定性（`DesktopOfflineLlmGateway` 已存在）；② 若禁止项（§12）被顺手引入，验收即失效。

**11. Completion Gate**：§9 切片 15 步**全部**为 ✓（无 △ / → / ✗）；三边界测试全绿；禁止项零引入。

---

## 15. Phase 9 — Runtime Adapter / DSH

**1. Goal**：让 Agent 跑在外部 Runtime 上，而**不改小说业务层**。

**2. Architecture Target**：§14（RuntimeAdapter + 10 步接入顺序）+ §21 边界三。

**3. Current State**：适配器（`runtime/api` 契约 + `runtime/dsh` ACP 实现 + `runtimeintegration` + `RuntimeSessionRef`）**只在 `feature/runtime-dsh@9a6f7c0`，不在工作树**；`:runtime` 在工作树是空壳；真实 DSH 链路曾 PASS（`DSH_POC_OK`）；但**DSH 的产品动机在正式文档中仍无锚点**（此前追溯结论）。

**4. Gap**：契约未落地到主线；能力协商缺失（`sessionCapabilities{close,list,resume}`、`mcpCapabilities` 被丢弃）；`embeddedContext=false` 与结构化上下文口径冲突；`shutdown` 无生产调用者（**接线即泄漏**）。

**5. Connection**：

```text
本 Phase：NovelAgent → RuntimeAdapter（Qianyan 自有契约）→ DSH
          → Tool Call → NovelTool → Tool Result → DSH Loop → NovelAgent
```

**6. Files / Modules**：`runtime/api/src/main/kotlin/com/qianyan/runtime/contract/AgentRuntimeGateway.kt`、`runtime/dsh/src/main/kotlin/com/qianyan/runtime/dsh/*`、`application/.../usecase/runtimeintegration/*`、`storage/.../RuntimeSessionRef.sq`、`settings.gradle.kts`、`app/desktop/.../di/DesktopGraph.kt`（当前在分支上直接 `new DshRuntimeClient()`，**应改为依赖 `runtime:api`**）。

**7. Contract**：**契约先行**（§14 十步）：① RuntimeAdapter ② Agent Session Contract ③ Tool Call Contract ④ Tool Result Contract ⑤ 单 Tool ⑥ Agent Loop ⑦ Session ⑧ Permission ⑨ Resume / Trace ⑩ 替换内部 Runtime。契约**必须 vendor-neutral**（含错误文案）。

**8. Storage**：`RuntimeSessionRef`（分支已有）；若采用能力协商需记录能力快照。

**9. Tests**：① 单 Tool 经 DSH 往返；② Permission 映射到 Qianyan 侧「只问一次」；③ Resume 语义（当前 `resume()` 无条件抛 `NotAvailable`）；④ `shutdown` 可达且无子进程泄漏。

**10. Risks**：① **前置**：Phase 9 只有在「Agent 自主执行」真实存在后才值得做（否则是给固定流水线套壳）；② 产品动机仍未裁决 —— 此前结论是「补齐动机前不定义 DSH-I2」，本 Phase **不构成动机**；③ 上下文口径：DSH 只收纯文本 prompt，结构化 WM 需序列化契约。

**11. Completion Gate**：Runtime Adapter 落地主线；单 Tool 与 Agent Loop 经 DSH 跑通；三边界（尤其边界三）测试全绿；**动机裁决已给出**（否则本 Phase 不得启动）。

---

## 16. Phase 10 — Desktop / Android 产品化

**1. Goal**：两端只消费稳定的 Application API。

**2. Architecture Target**：§15 + §3.3（禁止 UI → Repository）。

**3. Current State**：Android Writer = 完整受控闭环（含 approve）；Desktop 写作链可用但 I14 未提交；桌面 8 处直连仓储；Android Reader 读不到 TXT 导入章节；Prototype 的信息架构**尚未拍板**。

**4. Gap**：端到端入口（自然语言）、Diff / Proposal 展示、历史、按需内容。

**5. Connection**：`UI → Application API → Agent / UseCase → Context / Skill / Tool → Domain`（两端共用一套业务逻辑）。

**6. Files / Modules**：`app/desktop/src/jvmMain/**`、`app/android/src/main/**`、`application/.../di/ApplicationContainer.kt`。

**7. Contract**：UI 只允许依赖 `ApplicationContainer` 暴露的 UseCase / Gateway；**禁止**新增 UI 专用业务规则。

**8. Storage**：不需要（沿用）。

**9. Tests**：① 两端同一用例行为一致（同一断言跑两次）；② UI 直连仓储 = 0；③ Reader 能读 TXT 导入章节。

**10. Risks**：① 信息架构若被实现细节倒推会违反「尚未拍板」的约束；② 两端分叉（§15 明确禁止两套小说业务逻辑）。

**11. Completion Gate**：两端只经 Application API；无第二套业务逻辑；Prototype 结论已确认。

---

## 17. MVP Vertical Slice

**用户语句**：「继续写这一章 3000 字，保持人物状态和前文一致。」

| 步 | 环节 | 状态 | 说明 |
|---|---|---|---|
| 1 | Author | ✓ | 有界面（`WritePage`） |
| 2 | Natural Language | ✗ | **无入口**；`CommandPalette` 只过导航命令 |
| 3 | Intent | ✗ | 硬编码 `CONTINUE`；`analyzeIntent` 不可达 |
| 4 | NovelAgent | △ | 七相位存在但零 UI caller |
| 5 | ContextEngine | △ | 有实现，但写作链走 `PlanningContextAssembly`（§7 未收敛） |
| 6 | WorldModel | △ | `resolve()` 读到 6 类，**prompt 边界丢弃** |
| 7 | Retrieval | ✗ | 无正文检索 |
| 8 | NovelSkill | ✗ | 契约字段无读取者 |
| 9 | NovelTool | △ | 7 只读 Tool 存在，生产零执行 |
| 10 | LLM | ✓ | Provider 真实可达 |
| 11 | Draft | ✓ | 写入 `ChapterDraft` |
| 12 | Diff | ✗ | `TextDiffer` 不被调用 |
| 13 | Change | ✗ | 仅不可达路径 |
| 14 | Human Gate | ✓ | 创建 + 阻断 +（P0-3 后）桌面可决议 |
| 15 | Commit | ✗ | `CommitUseCases` 生产零调用 |
| 16 | Canonical | △ | 隐式 = latest Draft |
| 17 | World Model Update | ✗ | 不存在 |

**状态图例**：✓ 当前已有 · △ 部分已有 · → 需要连接 · ✗ 尚不存在

**缺口统计**：17 步中 **✓ 5 步 / △ 4 步 / → 0 步 / ✗ 8 步**。

按「连接工作量」排序：✗ 的 8 步里，4 步（Diff / Change / Commit / Canonical）由 **Phase 1** 解决；2 步（Retrieval / Skill）由 **Phase 4 / 6**；1 步（NL + Intent）由 **Phase 7**；1 步（WM Update）由 **Phase 3**。

⇒ 距该切片闭环还需 **Phase 0–8 中除 9 / 10 以外的全部阶段**，其中 **Phase 1 是唯一真正的地基**。

---

## 18. 架构依赖图

```text
Phase 0 连接基础 ──────────────────────────────┐（基线可信 + 边界无违例）
   │                                          │
   ├──→ Phase 1 Canonical + Change ──┐        │
   │         │                       │        │
   │         ↓                       ↓        │
   │    Phase 3 WM Update ←── Phase 2 World Model
   │         │                       │
   │         └──────┬────────────────┘
   │                ↓
   ├──→ Phase 4 Context + Retrieval ─┤（P1 与 P2 可部分并行，但 4 需要 2 的读侧）
   │                ↓
   │         Phase 5 Novel Tool
   │                ↓
   │         Phase 6 Novel Skill
   │                ↓
   │         Phase 7 NL + Novel Agent
   │                ↓
   └──→ Phase 8 Vertical Slice（验收门）
                    │
        ┌───────────┴───────────┐
        ↓                       ↓
  Phase 9 DSH             Phase 10 Desktop / Android
（前置：动机裁决）        （前置：Prototype 结论）

并行轨：UI Prototype（Phase 0 起）
```

**关键依赖（硬）**：Phase 3 ← Phase 1（Commit 事件）+ Phase 2（表 / 字段）；Phase 5 ← Phase 4（Context 收敛）；Phase 6 ← Phase 5；Phase 7 ← Phase 6；Phase 9 ← Phase 8 + 动机裁决。

**可并行**：Phase 2 与 Phase 1 在不同文件上无冲突；Prototype 与全部 Phase 并行（但不得倒推信息架构）。

---

## 19. 风险矩阵

| ID | 风险 | 概率 | 影响 | 缓解 | 需裁决 |
|---|---|---|---|---|---|
| **R1** | **总图 §8 第二阶段（Text / Semantic Search）与 FD-10 封存（全文搜索 / RAG / Vector）冲突** | 高 | 高 | 本计划只排 §8 第一阶段（结构化 Index）；第二阶段**冻结待裁决** | 必须 |
| **R2** | **Memory 与 World Model 谁 canonical 未裁决** | 高 | 高 | Phase 2 前给出裁决；否则 WM 只会从「空表」变「半空表」 | 必须（Phase 2 前置） |
| **R3** | Phase 1 收口后，`latestByChapter` 的既有消费者（Reader / ListPages / Android）会读到非正典内容 | 高 | 高 | Phase 1 内定义「读者看哪一版」 | 必须 |
| **R4** | 未提交工作（P1-01 / P0-3 / I14）与 Phase 0 / 1 强耦合 | 高 | 中 | 先合流再推进；**不得覆盖** | 必须 |
| **R5** | `24.sqm` 编号归位方式会影响未提交的 P1-01 | 中 | 中 | 只改编号与期望，不改 SQL 语义 | 必须 |
| **R6** | 8 个 Windows DB lock 失败若为既有问题，则「全绿」门不可达 | 中 | 中 | 先判定归因，再决定是否豁免 | 必须 |
| **R7** | DSH 的产品动机仍无锚点 | 高 | 中 | Phase 9 前置门 = 动机裁决 | 必须 |
| **R8** | 目标架构 §16 Prototype 被用来倒推 UI 信息架构 | 中 | 中 | 严格遵守「IA 尚未拍板」 | — |
| **R9** | Agent 自主 loop 引入后绕过 ActionPolicy | 中 | 高 | Phase 5 的旁路守卫测试 + Phase 7 权限测试 | — |
| **R10** | 收敛 Context 时引入第三 / 第四套 | 中 | 中 | Phase 4 出口门「入口数 = 1」 | — |
| **R11** | DSH 只收纯文本（`embeddedContext=false`）与结构化 WM 口径冲突 | 中 | 中 | Phase 9 前定「序列化契约」 | 必须 |

---

## 20. 明确禁止项（全阶段生效）

来自总图 §9 / §20 / §21 与既有产品裁决，**任何 Phase 都不得提前做**：

- DSH 业务层化 / MCP 产品化 / 云服务 / 多设备同步
- RAG / Vector / Embedding / 全文与语义检索（**待 R1 裁决**）
- AI Dashboard / Skill 页面 / Tool 页面 / Runtime 页面 / Sandbox 页面 / MCP 页面
- 第二套 Writer Document Model（第二套 Draft）· 第二套 Context · 第二套编排 · 第二套 Gate
- 重做 Storage / 重做 ApplicationContainer / 重做 Provider
- UI 直连 Repository / Agent 直连 SQLite / Agent 直连 Storage
- 在 AI 草稿阶段更新 World Model（§13 / 边界二）
- 把 Novel IDE 当作 UI 形态（产品定位已纠偏为「以 Agent 为核心交互方式的 AI 小说创作客户端」）
- 因「发现未完成项」而顺手实现（每次只做当前 Phase）

---

## 21. 最终实施顺序

```text
Phase 0  连接基础与基线恢复        ← 入口（含 TXT→Chapter、边界收口、迁移编号）
Phase 1  Canonical + Change Layer  ← 唯一地基（造出 Commit 事件）
Phase 2  World Model 读侧 + 字段补全   （与 Phase 1 可部分并行）
Phase 3  World Model Update Pipeline   （依赖 1 + 2）
Phase 4  Context Engine + Retrieval    （收敛四套 + 结构化 Index）
Phase 5  Novel Tool Layer              （8 Tool 生产化 + 关闭旁路）
Phase 6  Novel Skill Layer             （契约 + 2 个落地）
Phase 7  Natural Language + Novel Agent
Phase 8  完整写作 Vertical Slice（验收门）
Phase 9  Runtime Adapter / DSH          （前置：动机裁决）
Phase 10 Desktop / Android 产品化        （前置：Prototype 结论）

并行轨：UI Prototype（Phase 0 起，仅验证「感觉」）
```

**与总图 §19 的差异**：仅调整 World Model 与 Change Layer 的先后（理由见 §1）。其余同构。

---

## 22. 第一阶段实施入口

**Phase 0 的第一个可执行单元**（建议次序，每个单元独立可验收）：

| 单元 | 内容 | 为什么先做 | 完成判据 |
|---|---|---|---|
| **0-1** | **Storage 基线归位**：`24.sqm` 编号 + `DatabaseInitializer` 分支 + 12 处版本期望同步 | 一切「真实生产链验证」的前提；当前 12 红测试使任何验证不可信 | `:storage:test` 除豁免项外全绿；`Schema.version` 与实际迁移数一致 |
| **0-2** | **判定并处置 8 个 Windows DB lock 失败** | 决定「全绿」门是否可达 | 归因结论（既有 vs P1-01 引入）+ 处置或明确豁免 |
| **0-3** | **合流未提交工作**：把 P1-01（含 3 处 `?: throw`）、P0-3 按边界提交 | 它们与 Phase 0 / 1 强耦合；不合流则后续改动无干净基线 | 工作树只剩 I14 与本次无关内容 |
| **0-4** | **TXT → Chapter 提升** | 补上「已存在能力之间的缺失连接」（§2 原则的最小样本） | `导入 → 章节列表可见 → Reader 有内容`，且幂等 |
| **0-5** | **边界收口**：桌面 8 处 UI 直连仓储改走 Application API；`BackupStore.restoreBackup` 修触发器冲突 | §3.3 与 §21 的当前违例 | UI 直连仓储 = 0；备份 / 恢复有生产 caller 且可往返 |

**Phase 0 出口门**：以上 5 项完成 + §6 的三条边界检查全绿 ⇒ 才可进入 Phase 1。

**Phase 1 的第一个可执行单元**（预告，不启动）：把 `WritingExecutionUseCase` 的产出从「直写 `DraftRepository`」改为「入 `WorkingDraftUseCases` → `changes.prepare` → Gate → `CommitUseCases`」，即让 AI 产出**无法**再成为隐式正典。

---

## 附：文档纪律

- 本计划**不修改目标架构**（概念 / 模块 / 数据流 / 分层 / 命名均沿用总图）。
- 计划中所有 `module / package / 文件` 路径均来自**已实际验证存在**的路径或目录；未编造文件名。
- 6 项需裁决的开放问题（R1–R7）已在 §19 标出，本计划**不替产品决定**。
- 任何新功能若无法说明自己的连接路径（总图 §20 七问），不进入正式开发（总图 §23）。
