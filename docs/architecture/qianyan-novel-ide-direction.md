# Qianyan Novel IDE — 产品方向与架构基线（PC FIRST）

> **状态**：`DESIGN PHASE`（本轮只建立方向与基线，**未实现**任何新架构代码）
> **生效日期**：2026-09-28
> **取代关系**：本文档为 PC 后续阶段（旧 PC-3 … PC-9）的**上位方向文档**；旧阶段路线标记为 `PAUSED / SUPERSEDED BY NEW ARCHITECTURE`
> **不取代的内容**：已封存的工程契约（P19 Decision Model、P20 Architecture Freeze FD-1…FD-10、受控 Markdown v1、ReadingProgress / schema v17、Original 只读与写保护触发器、HITL）**全部继续有效**
>
> **状态图例（全文严格区分）**
> `IMPLEMENTED` 已在仓库中实现并有测试 · `PROTOTYPE` 已有可运行原型/迁移来源 · `DESIGNED` 已定稿设计但未实现 · `PLANNED` 已列入规划但未设计定稿 · `PAUSED` 暂停 · `SUPERSEDED` 已被新方向取代

---

## 1. Product Direction（产品方向）

### 1.1 定位变更

```text
以前：Qianyan = “让 AI 一次次生成小说”的工具
现在：Qianyan = “像 Codex 一样，通过 Project + Workspace + Agent + Tool + 大上下文
                 持续开发一部小说”的 AI 创作环境
```

**产品定位：AI Novel IDE。**

对照关系（借用但不照搬软件工程范式）：

| 软件工程 | Qianyan |
|---|---|
| Repository | **Project**（一部小说的长期事实） |
| Working tree / Editor | **Workspace**（创作环境） |
| Code search / LSP | **Project Index**（快速定位） |
| Coding agent | **Agent**（理解任务、调度能力） |
| 工具链 / build | **Tool**（Agent 操作 Project 的能力） |
| LLM 补全 | **LLM**（推理 / 规划 / 创作） |
| 编译器 / 静态检查 | **Deterministic Engine**（确定性检查） |

### 1.2 核心原则（本方向的第一性表述）

> Project 保存长期信息。
> Workspace 提供创作环境。
> Index 提供快速定位。
> Agent 负责理解任务和调度。
> Tool 是 Agent 操作 Project 的能力。
> LLM 负责推理、规划、创作。
> 程序和规则负责确定性检查。

### 1.3 不变的产品底线（从既有架构继承）

- **Local-first**：数据在本地 SQLite / 本地文件，不引入云依赖。
- **受控创作**：AI 不越权、不直接改原文；人工门（HITL）不可绕过。
- **Original 只读**：`Original` 的 update/delete 由物理写保护触发器强制（不可用 UI 或代码绕过）。
- **确定性优先**：能由程序确定检查的，不交给 LLM（见 §11）。
- **凭证边界**：API Key 不进入 SQLite / Domain / Task / Checkpoint / Memory / Story State / Prompt / Log。

---

## 2. PC First Strategy（PC 优先）

```text
PC
 ↓
完整 Novel IDE
 ↓
验证新的小说创作范式
 ↓
再重新定义 Android Companion
```

- **PC = PRIMARY**：接下来的架构与产品实现集中在 PC（首选 `:app:desktop`，Compose Desktop，见 `docs/architecture/p20-architecture-freeze.md` FD-5）。
- **Android = PAUSED**：暂停继续产品开发；**不是删除**。现有 `:app:android` 与全部已交付 Android 功能（Novel / Chapter / Writer / Reader / Provider Settings 等）**保留并继续编译**，回归测试保持通过。
- **Android 未来定位**：移动端 Companion，只生成适合移动任务的精简 Context（见 §12）。
- **判定标准**：只有 PC 上验证了新的创作范式（Project + Workspace + Agent + Tool + Context），才重新设计 Android 形态。

---

## 3. Project Model（Project 模型）

### 3.1 产品层不再把小说理解成 Novel→Chapters

```text
旧（产品理解）：Novel └── Chapters
```

```text
新（Qianyan Project）
Qianyan Project
│
├── Project State          // 项目元状态（进度 / 当前工作区 / 最近活动）
│
├── Workspace              // 创作环境（见 §4）
│
├── Project Index          // 快速定位（见 §5）
│
├── 小说世界
│   ├── 世界规则
│   ├── 人物
│   ├── 人物关系
│   ├── 时间线
│   └── 事件
│
├── 小说规划
│   ├── 分卷
│   ├── 大纲
│   ├── 剧情
│   └── 章节
│
├── 伏笔
│
├── 创作规则
│
├── Author / Writing Profile
│
└── 正文
    ├── Chapter 001
    ├── Chapter 002
    └── ...
```

**状态**：`DESIGNED`（产品方向设计）。本轮**不实现**新的 Project 文件系统 / 新的 schema / 新的存储层。

### 3.2 与既有存储的映射（复用关系，非替换）

| Project 概念 | 既有工程载体 | 状态 |
|---|---|---|
| Novel / Variant（scope 隔离） | `Novel` / `NovelVariant` / `EntityOverride` / 写保护触发器 | `IMPLEMENTED` |
| 小说世界（世界规则 / 人物 / 人物关系 / 时间线 / 事件） | Story State 六类表（Character / CharacterState / WorldRule / Event / TimelineEntry / Foreshadow）+ `StoryWorldContextResolver` | `IMPLEMENTED`（关系图与部分派生视图 `DESIGNED`） |
| 伏笔 | `Foreshadow` + `ForeshadowLifecycleRules`（PLANTED→ACTIVE→RESOLVED/ABANDONED）+ `Reveal` | `IMPLEMENTED` |
| 小说规划 | `StoryArc` / `Act` / `Chapter` / `ChapterPlan` / `Scene` / `ScenePlan` / `Beat`（domain）+ Durable Workflow | `IMPLEMENTED`（domain 层；**分卷 Volume `DESIGNED`**，尚无模型） |
| 正文 | `ChapterDraft`（`format` = 受控 Markdown v1）+ lineage（`previousDraftId`） | `IMPLEMENTED` |
| 创作规则 / Writing Policy | Story Foundation / Writing Policy / DecisionPolicy 快照 | `IMPLEMENTED`（部分） |
| Author / Writing Profile | Author Preference / Core / DNA / Decision Model（P16–P19） | `IMPLEMENTED`（后端）；产品层编排 `DESIGNED` |
| Project State / Workspace / Project Index | —— | `DESIGNED` / `PLANNED` |

> 原则：**不是推倒重来**。新的 Project 模型是**产品层与 Agent/Context/Workspace 层的升级**，底层已封存能力尽可能复用（见 §13）。

---

## 4. Workspace（创作环境）

**定义**：Workspace 是"人机共同编辑同一部小说"的界面与运行时状态容器，不是新的数据真源。

职责（`DESIGNED`）：

```text
Workspace
├── 当前工作对象       // 正在写的分卷 / 章节 / 场景
├── 当前 Context       // 本任务所需的检索结果投影（见 §6）
├── 编辑缓冲           // 未提交的正文修改
├── Agent 会话         // 当前任务、Agent 做了什么、读了什么、建议什么
├── 确定性检查结果     // 一致性 / 顺序 / 状态冲突（见 §11）
└── 变更确认（HITL）   // 写回 Project 前的人工门
```

不变式：

- Workspace **不拥有**业务事实；长期事实永远在 Project（Project State / Story State / Planning / 正文）。
- Workspace 的写回必须经既有 Application UseCase / Gateway（不允许 UI → Repository / SQLDelight）。
- 人工门（HITL）仍然强制：AI 的写回必须可确认、可拒绝、可回滚语义明确。

---

## 5. Project Index（快速定位）

**目的**：让 Agent 能够"按需定位"而不是"把整本书塞进上下文"。

索引维度（`DESIGNED`）：

```text
Project Index
├── 人物索引        // 人物 → 出场章节 / 状态变化 / 关系
├── 事件索引        // 事件 → 章节 / 参与人物 / 时间点
├── 时间线索引      // 章节 / 事件 → 时间顺序
├── 伏笔索引        // 伏笔 → 埋设点 / 回收点 / 状态
├── 设定索引        // 世界规则 → 引用章节
├── 章节索引        // 章节 → 摘要 / 关键要素 / 引用关系
└── 语义检索        // 关键词 / 摘要匹配（检索层，不依赖向量库）
```

边界（本轮明确不做）：

- **不引入 RAG / Vector / Embedding**（既有 `LATER / DEFERRED` 约束继续有效）。
- 索引是**派生数据**，可从 Project 重建；不成为新的业务真源。
- 索引实现方式（表 / 文件 / 内存投影）属 §15 待设计。

**状态**：`DESIGNED`（当前仓库无 Project Index 实现）。

---

## 6. Context Architecture（上下文架构）

### 6.1 核心原则

> **不再依赖一个无限增长的"小说记忆"。**

```text
Project
 ↓
Index
 ↓
Agent 判断任务
 ↓
检索相关信息
 ↓
Context Builder / Context Resolver
 ↓
当前工作 Context
 ↓
LLM
```

### 6.2 示例：用户说"写第 89 章"

Agent 按需读取（而不是全量注入）：

```text
总纲 · 当前分卷 · 当前剧情 · 第88章
相关人物 · 相关事件 · 相关伏笔 · 时间线 · 创作规则 · 必要的前文
```

→ 组装为**当前任务所需**的 Context。

### 6.3 与既有能力的衔接

| 环节 | 既有载体 | 状态 |
|---|---|---|
| 世界/人物/事件/时间线的分层组装 | `StoryWorldContextResolver`（canon-first 分层） | `IMPLEMENTED` |
| 有界上下文包 + token 预算 | `ChapterContextPack` / `TokenBudgetGuard` / `ChapterContextCompileUseCases` | `IMPLEMENTED` |
| 叙事账本（长线状态压缩） | `NarrativeState` / `NarrativeDelta` / `NarrativeStateFold` | `IMPLEMENTED` |
| 滚动地平线候选 | `RollingHorizonCandidate` / `RollingHorizonProjector` | `IMPLEMENTED` |
| 任务级 Context Projection（本方向新增诉求） | —— | `DESIGNED` |

> 关键判断：既有 Context 能力是**章节级**的；新方向要求**任务级**的按需检索与投影。二者是叠加关系，不是替换关系。

---

## 7. Agent Architecture（Agent 架构）

### 7.1 从"固定流水线"到"任务驱动"

```text
旧：Planner → Writer → Critique → Revision → Knowledge Update（固定顺序，所有任务都走）
新：User Task → Agent → 判断任务 → 选择需要的 Tool / Skill / 能力 → 读取 Project → 执行 → 检查 → 写回 Project
```

### 7.2 五 Agent 的处置（不删除）

```text
Planning · Writing · Critique · Revision · Knowledge Update
        ↓ 逐步转化为 Agent 可调用的专业能力（capability）
Planning capability / Writing capability / Critique capability /
Revision capability / Knowledge Update capability
```

- 五个 Agent 的**既有实现保留**（`IMPLEMENTED`，见 `:agent:agents` / `:application` writing 包）。
- 其**编排约束保留**：RevisionGate（revision ≤ 3）、DecisionPolicy 快照（FD-4：Planning 决定一次、Writing 复用同一份，Resume 不 re-decide）、HITL 人工门。
- 变化点在**编排层**：不再要求每个用户任务都按固定五段执行；Agent 按任务选择能力组合。
- **本轮不改任何 Agent 代码。**

**状态**：Agent Runtime + 五 Agent `IMPLEMENTED`；任务驱动选择能力的编排层 `DESIGNED`。

---

## 8. Tool Architecture（Tool 架构）

### 8.1 原则

> Agent 不直接"凭记忆"操作小说，而是通过 **Tool** 读取和修改 Project。

### 8.2 Tool 规划（`DESIGNED`）

```text
文件/结构类     read_file · search_file · edit_file · create_file
检索类          search_character · search_chapter · search_timeline · search_foreshadowing
检查类          check_consistency · check_character · check_world_rule · check_plot
分析类          analyze_style · extract_events · update_story_state
```

（未来可继续扩展。）

### 8.3 现状与边界

| 项 | 状态 |
|---|---|
| Tool 契约 / ToolRegistry / ToolExecutor / ToolResult | `IMPLEMENTED`（`:agent:tool`） |
| Agent Runtime 的 LLM↔Tool 循环（`maxSteps` 防护、类型化错误） | `IMPLEMENTED`（`:agent:runtime`） |
| 上述**产品级 Tool 实现** | `PLANNED`（当前**未注册任何产品级 Tool**；仓库中只有测试用 stub） |
| Tool 是否可见/可写、权限与 HITL 交互 | `DESIGNED`（属 §15 待设计） |

---

## 9. Novel Planning Model（小说规划模型）

### 9.1 两层结构（不冲突，明确区分）

```text
用户可见结构（产品层）        ≠        AI 内部规划结构（引擎层）
分卷                                   Arc
 ↓                                     ↓
大纲                                   Act
 ↓                                     ↓
剧情                                   ChapterPlan
 ↓                                     ↓
章节                                   Scene
 ↓                                     ↓
正文                                   Beat
                                       ↓
                                      Draft
```

> **用户可见结构 ≠ AI 内部规划结构。**
> 用户主要操作：**分卷 / 大纲 / 剧情 / 章节**。
> AI 内部继续使用：**Arc / Act / ChapterPlan / Scene / Beat / Draft**。

### 9.2 既有底层规划模型不删除

原底层链 `Novel → Arc → Act → ChapterPlan → Scene → Beat → Draft` **保留**（`IMPLEMENTED` 于 domain / storage / workflow）。

| 层 | 状态 |
|---|---|
| `StoryArc` / `Act` / `Chapter` / `ChapterPlan` / `Scene` / `ScenePlan` / `Beat` / `StoryNodeKind` | `IMPLEMENTED`（`core:model`） |
| 分卷（Volume）模型 | `DESIGNED`（**尚无模型**） |
| 用户可见「大纲 / 剧情」的产品层聚合视图 | `DESIGNED` |

---

## 10. Long-form Writing Model（长篇写作模型）

Qianyan **不追求**"每次把整本百万字小说全部塞给 LLM"，而追求：

```text
长期信息 → Project Workspace → Index → 检索 → Context Projection
        → 当前工作 Context → LLM
```

因此：

> **大上下文是 Agent 当前工作的能力，不是小说长期记忆的替代品。**

长篇小说稳定性同时依赖（缺一不可）：

```text
Project State + Knowledge + Timeline + Character State + Foreshadowing
+ Planning + Context + Agent + Deterministic Checks
```

**状态**：`DESIGNED`（原则定稿）。其中 Knowledge / Timeline / Character State / Foreshadowing / Planning / Context 的**底层载体 `IMPLEMENTED`**（见 §3.2、§6.3），产品层编排与 Index 检索 `DESIGNED`。

---

## 11. Consistency Model（一致性模型）

### 11.1 分工

能够由程序确定检查的，**不全部交给 LLM**：

```text
程序 / 规则负责（确定性）         LLM 负责（语义）
人物是否死亡                      理解
人物当前状态                      推理
时间顺序                          规划
章节顺序                          创作
人物位置                          语义分析
世界规则                          复杂问题判断
伏笔状态
章节引用关系
```

> 最终形态：**LLM + Deterministic Engine**，而不是 **Everything by LLM**。

### 11.2 既有确定性能力（可复用）

| 能力 | 既有载体 | 状态 |
|---|---|---|
| 伏笔状态机 | `ForeshadowLifecycleRules` | `IMPLEMENTED` |
| 修订门控 | `RevisionGate`（≤3，不调 LLM） | `IMPLEMENTED` |
| Knowledge 候选确定性校验 | KnowledgeUpdate Validator / Applicator（LLM 只提候选） | `IMPLEMENTED` |
| 受控 Markdown 解析/降级 | `ControlledMarkdown`（非法结构安全降级） | `IMPLEMENTED` |
| TXT 解析 / 章节识别 | `TxtPipeline`（纯确定性） | `IMPLEMENTED` |
| 跨要素一致性检查（人物状态 / 时间线 / 位置 / 引用关系） | —— | `DESIGNED`（新方向新增；见 §8 `check_*` Tool） |

---

## 12. PC / Android Context Strategy

```text
同一个 Project
        │
        ├── PC Context
        │
        └── Android Context
```

**不是两套小说记忆**，而是：

> **同一个 Project / Story State，根据设备和任务生成不同的 Context Projection。**

| 平台 | Context 特征 | 可承担 |
|---|---|---|
| PC（PRIMARY） | 更长上下文、更完整项目读取 | 长篇规划、多章节分析、复杂 Agent 工作、Project Index 全量检索 |
| Android（PAUSED） | 精简 Context（只取适合移动任务的部分） | 阅读、轻量编辑、状态查看、简单操作（未来 Companion） |

**状态**：`DESIGNED`。本轮**不实现** Android Context。

---

## 13. Existing Architecture Reuse（既有架构复用）

> 表述纪律：**旧架构完成了 Qianyan 的核心工程基础；新的 Novel IDE 是产品层和 Agent/Context/Workspace 层的升级**——不是"之前架构全部错误"。

```text
P0～P20 已有工程基础
        ↓
PC-1 Desktop Foundation / PC-2 Desktop Writer / PC-2.1 writer 收口
        ↓
新的 Novel IDE Architecture（本文档）
        ↓
重新规划 PC 后续阶段
```

### 13.1 已验证工程基线（本次核实）

| 内容 | 状态 / 证据 |
|---|---|
| schema **v17**（含 `ReadingProgress`）；迁移只做 additive（FD-9） | `IMPLEMENTED` |
| P19 Decision Model | `IMPLEMENTED` / `SEALED` |
| P20 Architecture Freeze **FD-1…FD-10** | `IMPLEMENTED`（`docs/architecture/p20-architecture-freeze.md`） |
| P20 P1 Vocabulary Confirmation（复用既有模型，FD-6） | `IMPLEMENTED` |
| P20 P2 Controlled Markdown v1（FD-1） | `IMPLEMENTED` |
| P20 P3 Android Writer / P20 P4 Android Reader（ReadingProgress） | `IMPLEMENTED` |
| P20 P5 / P5-fix DecisionPolicy Writing Integration（FD-4） | `IMPLEMENTED` |
| PC-1 Desktop Foundation（`:app:desktop` = 真实 Compose Desktop + SQLite v17 + Provider 设置） | `IMPLEMENTED`（commit `ac70148`） |
| PC-2 Desktop Writer（`writerGateway` + `workflowFacade`：读 / 存 / 继续写作 / 改写 / HITL） | `IMPLEMENTED`（commit `57fab38`） |
| PC-2.1 Writer 边界收口（无新 Draft 时不回溯迁移 legacy `format`） | `IMPLEMENTED`（commit `8db807e`） |
| Durable Workflow / Task / Checkpoint / Human Gate / Recovery | `IMPLEMENTED` |
| Story State 六类 + NarrativeState + ContextPack + Foreshadow + Reveal | `IMPLEMENTED` |
| Author（Preference / Core / DNA / Decision Model，P16–P19） | `IMPLEMENTED`（后端） |
| `ui/desktop` 分支（P14 时期 Compose Desktop 原型，`27a8408`） | `PROTOTYPE`（**保留**；PC-1 已按新架构迁移其有效部分，未 merge/rebase/cherry-pick） |

### 13.2 复用映射（一句话结论）

- **Project 的长期事实载体**：复用既有 Novel/Variant/Story State/Planning/Draft/Workflow —— 不新建第二套。
- **Context**：复用 `StoryWorldContextResolver` + `ChapterContextPack` + `NarrativeState`，在其上增加**任务级** Projection。
- **Agent**：复用 Agent Runtime 与五个 Agent，把"固定流水线"改为"能力选择"。
- **Tool**：复用 Tool 契约/注册表/执行器，补齐 product 级 Tool 实现。
- **确定性检查**：复用既有 Gate / Rules / Validator，补齐跨要素一致性检查。
- **平台**：复用 Compose Desktop（FD-5）；Android 保留但 PAUSED。

---

## 14. Deprecated / Paused Roadmap（暂停与取代）

### 14.1 旧 PC 路线（保留历史记录）

```text
PC-3 Reader            PAUSED / SUPERSEDED BY NEW ARCHITECTURE
PC-4 Story & Plan      PAUSED / SUPERSEDED BY NEW ARCHITECTURE
PC-5 Knowledge/Manage  PAUSED / SUPERSEDED BY NEW ARCHITECTURE
PC-6 Author            PAUSED / SUPERSEDED BY NEW ARCHITECTURE
PC-7 Book/Export       PAUSED / SUPERSEDED BY NEW ARCHITECTURE
PC-8 Provider/Background  PAUSED / SUPERSEDED BY NEW ARCHITECTURE
PC-9 E2E               PAUSED / SUPERSEDED BY NEW ARCHITECTURE
```

> 这些阶段的**历史记录与既有设计不删除**；其未交付项将在新的 Novel IDE 架构下**重新规划**（不机械续做）。
> 注：PC-1 / PC-2 / PC-2.1 = `IMPLEMENTED`，且作为新架构的 PC 基础**继续有效**。

### 14.2 其他状态

| 项 | 状态 |
|---|---|
| `:app:android` 及全部既有 Android 功能 | `PAUSED`（**保留**，非删除、非取消） |
| Android 后续阶段 | `PAUSED`（待 PC Novel IDE 完成后重新设计 Companion） |
| `ui/desktop` 分支 | `PROTOTYPE`（保留参考，不删除、不再作为主线） |
| P20 其余 Productization 项（富文本 / 全文搜索 / 后台调度 / Error Recovery UX / 凭证加密 / 章节重排批量） | `PAUSED`（FD-10 边界不变） |
| RAG / Vector Memory / Multi-Agent / MCP / Cloud / 微调 | `DEFERRED`（保持 Local-first） |

---

## 15. Next Architecture Design Phase（下一设计阶段）

本方向落地需要**先完成设计**，再进入实现（顺序建议）：

```text
D1  Project 模型与 Project State 定义（含 Volume/大纲/剧情的产品层结构）
D2  Workspace 运行时定义（编辑缓冲 / Agent 会话 / HITL 写回）
D3  Project Index 定义（索引维度、重建策略、与既有 Story State 的关系）
D4  Task-level Context Projection（检索 → Context Builder 的确定性契约）
D5  Agent 任务编排（任务分类 → 能力选择 → 检查 → 写回；保留 DecisionPolicy / HITL 约束）
D6  Product 级 Tool 清单与权限（read/search/edit/check/analyze）
D7  Consistency Engine（跨要素确定性检查清单与优先级）
D8  PC Novel IDE UI 结构（Project / Workspace / AI Agent 三栏）
```

### 15.1 下一阶段的硬约束（不可违反）

- 不改 P19 / P20 Freeze 既有契约；不删 Android；不删 `ui/desktop`。
- Storage 演进继续 **additive only**（FD-9）；不引入新 schema 除非经设计阶段决策。
- 不引入 RAG / Vector / Embedding / 云服务。
- 不新建第二套业务逻辑；UI 不得直连 Repository / SQLDelight / WorkflowOrchestrator。
- DecisionPolicy 仍为**单次决定**（FD-4）；HITL 不可绕过；Original 只读与写保护触发器不变。
- 五 Agent / 既有 Pipeline 保留，转化为能力，不删除。

### 15.2 本轮（文档同步轮）明确不做

不重构 Agent / Workflow / Context / Storage / Provider；不修改 P19 / P20；不实现 Project Workspace / Project Index / 新 Tool / 新 Agent；不重新设计 UI；不引入新 schema / 云服务 / RAG；不修改任何现有业务行为。**本轮只建立方向文档与 Git 基线。**

---

## 附：文档索引

| 文档 | 作用 | 状态 |
|---|---|---|
| **本文档** `docs/architecture/qianyan-novel-ide-direction.md` | 产品方向与架构基线（上位方向文档） | `DESIGN PHASE` |
| `docs/architecture/qianyan-novel-ide-architecture.md` | **Novel IDE 总架构设计**（Project / Workspace / Index / Context / Agent / Skill / Tool / Session / Working Draft / Validation / Diff / Canonical / Commit / History + 迁移矩阵 + 重写范围结论） | `DESIGN COMPLETE / NEEDS REVIEW` |
| `docs/architecture/p20-architecture-freeze.md` | FD-1…FD-10 冻结规则 | `FROZEN`（继续有效） |
| `docs/planning/qianyan-pc-ui-contract.md` | PC UI ↔ 后端能力契约（真实接线状态） | 现行（PC-3 起阶段已 `PAUSED`） |
| `docs/planning/qianyan-master-plan.md` | 总体架构设计（历史规划） | 参考（见文首状态横幅） |
| `docs/planning/qianyan-implementation-plan.md` | 实施计划（旧阶段编号） | 参考（见文首状态横幅） |
| `docs/planning/qianyan-v4.2-architecture-review.md` | 架构评审（历史） | 参考（见文首状态横幅） |
| `docs/status/qianyan-project-status.md` | 历史状态快照 | 历史档案 |
| 各 `docs/P*-completion-report.md` | 阶段完成报告 | 历史记录（不删除） |