# P20 Architecture Freeze — FD-1 ~ FD-10

> 状态：**FROZEN**（P20 Architecture Freeze 阶段冻结；P20-PC1 正式落库）。
> 本文档忠实记录 P20 已经冻结的十条规则，**不引入任何新的架构决策**。
> 落地位置一栏指向当前仓库中真实存在的代码/迁移；未在代码注释中出现的条目以「冻结于 P20 Freeze」标注。
>
> 🧭 **产品定位（2026-10-06 纠偏 · 现行）**：Qianyan = **一个以 Agent 为核心交互方式、面向小说作者的 AI 小说创作客户端**
> （自然语言 / Agent 是统一任务入口；小说内容是核心对象，按任务动态打开；**不是** IDE / 聊天软件+数据库 / 小说后台 / Runtime 控制台 / DSH 皮肤）；
> 「Novel IDE / Codex」仅作**历史口径与内部架构思想**。本条只统一文档表达，**不改变、不解除 FD-1…FD-10 任何一条冻结规则**。
>
> 🧭 **产品方向更新（2026-09-28）**：Qianyan 已进入新方向 **AI Novel IDE（PC FIRST）**，见
> [qianyan-novel-ide-direction.md](qianyan-novel-ide-direction.md)。**本文档 FD-1…FD-10 全部继续有效**
> （FD-5 Compose Desktop 与 PC FIRST 一致；FD-9 Storage additive-only、FD-10 Scope 边界在新的设计阶段同样适用；
> 新方向带来的 Project / Workspace / Index / Agent / Tool 变化属**产品层与编排层**，不解除本文档任何一条冻结规则）。

冻结顺序与编号保持原样，不得重排、合并或改写语义。

---

## FD-1 · 受控 Markdown v1（Controlled Markdown v1）

| 项 | 内容 |
|---|---|
| 冻结内容 | 正文格式的**唯一**受控标记为受控 Markdown v1；正文格式由 `ChapterDraft.format` 承载（`null` = legacy 纯文本）。受控范围：普通段落 / 空行分隔 / H1–H3 / `**bold**` / `*italic*` / `- item` / `> quote`。非法或不支持结构（表格、图片、HTML、代码块、Task List、4+ 级标题、嵌套列表等）**必须安全降级**为 Degraded 块并保留原文，不得破坏文档。解析与渲染**确定性**：无 LLM、无时间、无随机、无外部状态（同输入同输出）。新产生的 Draft 标记为 `markdown:controlled:v1`；已有 format 的 Draft 不回溯改写。 |
| 落地位置 | `core/engine/.../markdown/ControlledMarkdown.kt`、`MarkdownModel.kt`、`MarkdownRenderer.kt`；`core/model/.../writing/WritingModels.kt`（`DraftFormat`）；`storage/.../Draft.sq`（`format` 列）；`storage/.../15.sqm`（v15→v16 加列，additive）；`application/.../writing/WriterUseCases.kt`（`stampControlledMarkdown`） |
| 状态 | 已实现（P20-P2） |

## FD-2 · Task / Checkpoint 是业务事实来源；平台调度器只是 Scheduler

| 项 | 内容 |
|---|---|
| 冻结内容 | 任务与流程的**业务事实**一律持久化在 Task / Checkpoint（SQLite），是唯一事实来源。平台调度器（Android WorkManager、系统后台调度、任何平台定时器）**只能承担"何时被唤醒"的调度职责**，不得承载业务状态、不得成为进度/恢复的判定依据。业务状态机运行在 Application 层，与平台无关。 |
| 落地位置 | `application/.../usecase/task/TaskManagerUseCases.kt`、`TaskStateMachine.kt`、`TaskRunner.kt`；`storage/.../Task.sq`；`storage/.../1.sqm`（v1→v2） |
| 状态 | 已实现（P8.x 起）；后台调度器本体属后续阶段（本阶段不引入） |

## FD-3 · Checkpoint Resume 幂等

| 项 | 内容 |
|---|---|
| 冻结内容 | 从 Checkpoint 恢复必须**幂等**：同一 Checkpoint 重复恢复不得产生重复副作用（不重复创建/推进业务实体、不重复消费人工门、不重复写入知识）。恢复只读取已持久化的事实，不重新执行已经完成的外部调用。 |
| 落地位置 | `application/.../usecase/task/TaskManagerUseCases.kt`（`saveCheckpoint` / `restoreCheckpoint` / `findCheckpoints`）；`application/.../usecase/workflow/WorkflowOrchestrator.kt`（Recovery 路径） |
| 状态 | 已实现（P8.x / P12.2） |

## FD-4 · DecisionPolicy 确定性：快照持久化，Resume 不 re-decide

| 项 | 内容 |
|---|---|
| 冻结内容 | 同一次创作链中，Planning 决定的 `DecisionPolicy` 是**唯一一份**：快照随 Checkpoint 持久化，Writing 复用**同一份**（只解码、不重算）。Resume 时**恢复**政策而不是重新 `decide()`。P19 Domain Contract 不得修改（不加 `@Serializable`、不加 id/version/timestamp/reason/score 等字段）；编码在 Application 层用枚举 `.name` 承载。旧 Checkpoint 无该键 → 返回"无政策快照"，调用方**不得静默重新 decide**。 |
| 落地位置 | `application/.../usecase/decision/DecisionPolicySnapshot.kt`；`application/.../writing/planning/PlanningSnapshot.kt`、`PlannerAgent.kt`；`application/.../writing/WriterAgent.kt`、`WritingExecutionUseCase.kt`；`application/.../usecase/workflow/WorkflowOrchestrator.kt`；`core/model/.../decision/DecisionPolicy.kt`（P19 契约，只读消费） |
| 状态 | 已实现（P19 / P20-P5 / P20-P5-fix） |

## FD-5 · PC 客户端形态 = Compose Desktop

| 项 | 内容 |
|---|---|
| 冻结内容 | PC 客户端的技术形态冻结为 **Compose Desktop**（Kotlin Multiplatform，jvm target）。PC 不是从零新建的第二套系统，而是把既有 Compose Desktop 客户端迁移、适配、收口到当前主线；业务能力一律经 `ApplicationContainer` 访问。不引入 Electron / Tauri / Web 前端作为 PC 客户端。 |
| 落地位置 | `app/desktop/build.gradle.kts`（kotlin.multiplatform + kotlin.compose + compose.desktop）；`app/desktop/src/jvmMain/.../Main.kt`、`di/DesktopGraph.kt` |
| 状态 | 已落地（P20-PC1）；页面能力按 PC-2…PC-7 分阶段接线 |

## FD-6 · Vocabulary Confirm 复用既有模型

| 项 | 内容 |
|---|---|
| 冻结内容 | 词法候选的确认 / 拒绝 / 编辑**复用既有 Vocabulary / VocabularyCandidate 模型**，不新建第二套词库模型，不引入新的候选状态机。候选 → 正式词条的过程仍落在同一张 Vocabulary 表族内。 |
| 落地位置 | `application/.../usecase/vocabulary/VocabularyUseCases.kt`（`confirmCandidate` / `rejectCandidate` / `editCandidate`）；`storage/.../Vocabulary.sq` |
| 状态 | 已实现（P1 / P6 起） |

## FD-7 · Reader 复用既有正文事实，不新建第二套正文模型

| 项 | 内容 |
|---|---|
| 冻结内容 | Reader 的正文只来自既有 `Draft`（Chapter 不携带正文），阅读块来自 FD-1 受控 Markdown 的解析结果；相邻章节由既有 `ChapterRepository.listByNovel` 的 order 序确定性推导。**不新建第二套章节正文模型、不在 UI 侧维护第二套章节顺序**。legacy（`Draft.format = null`）不做 Markdown 解析，整段原文作为单个纯文本块展示。章节无 Draft → 空块，**不伪造正文**。Reader 不 Decision、不调 LLM、不写正文。 |
| 落地位置 | `application/.../usecase/reading/ReadingUseCases.kt`；`app/android/.../ui/reader/{ReaderViewModel,ReaderScreen,MarkdownAdapter}.kt` |
| 状态 | 已实现（P20-P4，Android）；PC 侧接线属 PC-3 |

## FD-8 · Error Recovery 分层

| 项 | 内容 |
|---|---|
| 冻结内容 | 错误恢复**按层**处理，各层职责不越界：Application/UseCase 层把错误映射为领域错误（`ErrorMapper`）并保持业务不变量；Workflow/Task 层负责可恢复状态（Retry ≠ Revision、Recovery、人工门幂等）；平台/UI 层只负责把错误转成用户可理解的语言与可执行的下一步。**不引入全局重试总线、不引入跨层自动重放**。 |
| 落地位置 | `application/.../error/{ErrorMapper,ApplicationError,ApplicationException}.kt`；`application/.../usecase/workflow/*`；`application/.../usecase/task/*`；UI 侧（Android `*UiState` / Desktop `DesktopAppState.toast`） |
| 状态 | 分层边界已实现；面向用户的 Error Recovery UX 属后续阶段（PC-9） |

## FD-9 · Storage 只做 additive 演进

| 项 | 内容 |
|---|---|
| 冻结内容 | 数据库演进**只允许 additive**：迁移只新增表 / 新增列 / 新增索引（必要时回填），不删除既有表、不改写既有列语义、不破坏旧数据可读性。每次建表/迁移在单事务内完成并同步 `PRAGMA user_version`。新能力通过新迁移追加（如 v15→v16 加 `ChapterDraft.format`、v16→v17 新增 `ReadingProgress`）。 |
| 落地位置 | `storage/.../db/DatabaseInitializer.kt`；`storage/.../db/1.sqm … 16.sqm`；`storage/.../db/ReadingProgress.sq` |
| 状态 | 已实现（schema v17） |

## FD-10 · Scope 限制（本阶段边界）

| 项 | 内容 |
|---|---|
| 冻结内容 | 明确排除、不得提前引入的范围：富文本编辑器（受控 Markdown v1 之外）、全文搜索、章节重排与批量管理、后台调度器实现、面向用户的 Error Recovery UX、凭证加密、RAG / Vector / Embedding、MCP、Cloud、Multi-Agent、Fine-tune / 自动学习。这些能力只能在后续阶段按各自范围单独决策与交付。 |
| 落地位置 | 各阶段范围以对应阶段文档为准；本条为总边界 |
| 状态 | 生效中（P20-PC1 严格遵守：未实现上述任何能力） |

---

## 与 P20-PC1 的一致性说明

- PC-1 未修改 FD-1（受控 Markdown v1）、FD-4（DecisionPolicy / P19 契约）、FD-7（Reader）、FD-9（Storage 语义）。
- PC-1 遵守 FD-5：`:app:desktop` 收口为 Compose Desktop（KMP jvm target），业务经 `ApplicationContainer`。
- PC-1 遵守 FD-10：未引入富文本、全文搜索、章节重排、批量管理、后台调度、Error Recovery UX、凭证加密、RAG/Vector/MCP/Cloud/多 Agent。
- PC-1 未新增任何架构决策。