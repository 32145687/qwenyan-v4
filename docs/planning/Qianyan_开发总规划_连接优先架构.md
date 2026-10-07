# Qianyan 开发总规划：连接优先架构与后续实现路径

> 状态：规划基线\
> 目的：在继续开发功能之前，先把已经存在的模块、未来模块、Runtime、UI、Storage
> 之间的连接路径确定下来，避免后续"功能做出来了但接不起来"。\
> 原则：**先连接，再开发；先确定契约，再实现能力。**

------------------------------------------------------------------------

# 1. 总原则 {#1-总原则}

Qianyan 是：

> **面向小说作者的 AI 小说创作客户端。**

不是 IDE、不是 AI SaaS Dashboard、不是数据库后台，也不是 DSH 的 UI。

核心分层：

``` text
作者
  ↓
Qianyan UI / Client
  ↓
Application API
  ↓
Novel Agent
  ↓
Context Engine / Skills / Novel Tools
  ↓
Novel Domain
  ├── Canonical Story
  ├── World Model
  ├── Retrieval / Index
  └── Project Data
  ↓
Change Layer
  ├── Draft
  ├── Diff
  ├── Validation
  ├── Human Gate
  └── Commit
  ↓
Canonical
  ↓
World Model Update
```

Agent Runtime 独立：

``` text
Novel Agent
  ↓
Runtime Adapter
  ↓
DSH
  ├── Agent Loop
  ├── Tool Calling
  ├── Session
  ├── Permission
  ├── Sandbox
  ├── Subagent
  ├── Trace
  ├── Resume
  └── LLM Runtime
```

**Qianyan 决定小说业务是什么；DSH 决定 Agent 如何运行。**

------------------------------------------------------------------------

# 2. 连接优先原则 {#2-连接优先原则}

今后的开发不能再按照：

``` text
功能 A 做完
↓
功能 B 做完
↓
功能 C 做完
↓
最后想办法连接
```

而应该：

``` text
定义契约
↓
建立接口
↓
建立最小连接
↓
验证连接
↓
再开发真实能力
```

例如 World Model 不能先做成一套孤立的人物数据库，再考虑 Agent 怎么读取。

应该先确定：

``` text
Agent
↓
ContextEngine
↓
NovelTool
↓
WorldModel
```

然后再逐步把 World Model 的真实能力填进去。

------------------------------------------------------------------------

# 3. 当前已经存在的基础 {#3-当前已经存在的基础}

## 3.1 基础工程 {#31-基础工程}

已存在：

-   `:core:model`
-   `:core:engine`
-   `:agent:tool`
-   `:agent:runtime`
-   `:agent:agents`
-   `:agent:orchestration`
-   `:provider`
-   `:storage`
-   `:runtime`
-   `:app:android`
-   `:app:desktop`
-   `:test:e2e`

这些是后续工作的基础，不重建。

## 3.2 Provider {#32-provider}

已存在：

-   LLM Gateway
-   DeepSeek Provider
-   MiMo Provider
-   Mock Provider

连接目标：

``` text
Novel Agent
↓
Provider / LLM Gateway
↓
DeepSeek / MiMo / Mock
```

后续不重新设计 Provider。

## 3.3 Application / Gateway {#33-application--gateway}

现有 Application / Gateway / UseCase 层继续作为 PC / Android
的业务入口。

目标：

``` text
Desktop
   ↓
Application API
   ↓
Domain / Agent

Android
   ↓
Application API
   ↓
Domain / Agent
```

禁止：

``` text
Desktop → 直接 Repository
Android → 直接 Repository
Agent → 直接 SQLite
UI → 直接 World Model Storage
```

------------------------------------------------------------------------

# 4. 最终总体连接图 {#4-最终总体连接图}

``` text
                         ┌──────────────┐
                         │    作者      │
                         └──────┬───────┘
                                ↓
                    ┌─────────────────────┐
                    │ Qianyan UI / Client │
                    │ PC / Android        │
                    └──────────┬──────────┘
                               ↓
                    ┌─────────────────────┐
                    │ Application API     │
                    └──────────┬──────────┘
                               ↓
                    ┌─────────────────────┐
                    │     Novel Agent     │
                    └──────┬───────┬──────┘
                           │       │
                    Context│       │Skill
                           ↓       ↓
                 ┌────────────┐ ┌────────────┐
                 │ContextEngine│ │Novel Skills│
                 └─────┬──────┘ └──────┬─────┘
                       │               │
                       └───────┬───────┘
                               ↓
                    ┌─────────────────────┐
                    │   Novel Tool Layer  │
                    └──────────┬──────────┘
                               ↓
              ┌────────────────┼────────────────┐
              ↓                ↓                ↓
        World Model      Canonical Story    Retrieval
              │                │                │
              └────────────────┼────────────────┘
                               ↓
                            Context
                               ↓
                         Novel Agent
                               ↓
                         Draft / Proposal
                               ↓
                       Change Layer
                  ┌────────────┼────────────┐
                  ↓            ↓            ↓
                Diff       Validation   Human Gate
                  └────────────┼────────────┘
                               ↓
                            Commit
                               ↓
                           Canonical
                               ↓
                      World Model Update
                               │
                               └────→ 下一次任务

Novel Agent
    ↓
Runtime Adapter
    ↓
DSH
    ├── Agent Loop
    ├── Tool Calling
    ├── Session
    ├── Permission
    ├── Sandbox
    ├── Subagent
    ├── Trace
    ├── Resume
    └── LLM Runtime
```

------------------------------------------------------------------------

# 5. 第一优先级：先建立"连接骨架" {#5-第一优先级先建立连接骨架}

在开发真正能力之前，先把以下接口链建立起来。

## 5.1 Agent → Context {#51-agent--context}

目标：

``` text
NovelAgent
  ↓
ContextEngine
```

统一入口：

``` text
Task
→ ContextRequest
→ ContextEngine
→ ContextSnapshot / ContextResult
```

Context Engine 负责决定本次任务需要什么。

## 5.2 Context → Novel Tool {#52-context--novel-tool}

目标：

``` text
ContextEngine
  ↓
NovelTool
```

Context Engine 不知道 SQLite / SQLDelight 细节，只请求业务能力：

``` text
getCharacter(...)
getChapter(...)
searchStory(...)
getRecentEvents(...)
getTimeline(...)
getForeshadowing(...)
getKnowledgeBoundary(...)
```

## 5.3 Novel Tool → Domain / Repository {#53-novel-tool--domain--repository}

目标：

``` text
NovelTool
  ↓
Application / Domain API
  ↓
Repository
  ↓
Storage
```

Tool 不能绕过 Domain/Application 直接操作数据库。

## 5.4 Agent → Skill {#54-agent--skill}

目标：

``` text
NovelAgent
  ↓
Skill Selection
  ↓
NovelSkill
```

Skill 通过 ContextEngine / NovelTool 获取小说事实。

## 5.5 Agent → Runtime Adapter {#55-agent--runtime-adapter}

目标：

``` text
NovelAgent
  ↓
RuntimeAdapter
  ↓
DSH
```

先建立 Qianyan 自己的 Runtime Contract，不让 Novel Agent 直接依赖 DSH
类型。

## 5.6 Agent → Change Layer {#56-agent--change-layer}

目标：

``` text
NovelAgent
  ↓
Draft / Proposal
  ↓
Diff
  ↓
Validation
  ↓
HumanGate
  ↓
Commit
```

先把调用链打通，再完善每个环节。

------------------------------------------------------------------------

# 6. World Model 开发路径 {#6-world-model-开发路径}

## 6.1 World Model 的职责 {#61-world-model-的职责}

World Model = Qianyan 对"小说当前世界状态"的结构化理解。

第一版核心对象：

``` text
WorldModel
├── Entity
├── Relationship
├── State
├── Event
├── Timeline
├── KnowledgeBoundary
└── Foreshadowing
```

## 6.2 World Model 不直接服务 UI {#62-world-model-不直接服务-ui}

正确：

``` text
UI
↓
Application API
↓
Context / Novel Tool
↓
World Model
```

错误：

``` text
UI
↓
World Model Repository
```

## 6.3 World Model 更新路径 {#63-world-model-更新路径}

采用增量更新：

``` text
Canonical Change
↓
Changed Text / Changed Artifact
↓
Extraction
↓
Candidate Facts
↓
Validation
↓
World Model Update
```

提取机制：

``` text
规则 / 关键词
    +
正文索引
    +
AI语义理解
```

分工：

-   规则 / 关键词：快速发现候选位置
-   Index：快速找到相关正文
-   AI：理解语义、提取结构化事实
-   Validation：防止错误事实直接污染 World Model

------------------------------------------------------------------------

# 7. Context Engine 开发路径 {#7-context-engine-开发路径}

Context Engine 负责：

> **"当前这个任务到底需要什么？"**

例如"继续写第89章"，Context Engine 查询：

``` text
当前章节
+
当前章节附近正文
+
相关人物状态
+
最近事件
+
当前时间线
+
相关关系
+
相关伏笔
+
知识边界
```

最终形成：

``` text
ContextSnapshot
```

再交给 Agent。

------------------------------------------------------------------------

# 8. Retrieval / Index 开发路径 {#8-retrieval--index-开发路径}

Retrieval 是 Context Engine 的底层能力之一。

第一阶段：

``` text
Chapter Index
Character Index
Event Index
Timeline Index
```

第二阶段：

``` text
Text Search
Semantic Search
Hybrid Search
```

连接：

``` text
NovelTool
↓
Retrieval
↓
Index
↓
Canonical / World Model
```

禁止 Agent 自己扫描整本小说。

------------------------------------------------------------------------

# 9. Novel Tool Layer 开发路径 {#9-novel-tool-layer-开发路径}

第一版核心 Tool：

``` text
NovelTool
├── getChapter
├── searchStory
├── getCharacter
├── getCharacterState
├── getRecentEvents
├── getTimeline
├── getForeshadowing
└── getKnowledgeBoundary
```

后续增加：

``` text
getWorldState
searchSemantic
analyzeConsistency
prepareChange
createProposal
```

Tool 只暴露业务能力，不暴露 SQL、SQLite、Repository、DSH 内部对象。

------------------------------------------------------------------------

# 10. Novel Skill 开发路径 {#10-novel-skill-开发路径}

第一批 Skill：

``` text
LongContinuationSkill
CharacterConsistencySkill
PlotPlanningSkill
ForeshadowingSkill
RewriteSkill
SceneDesignSkill
PacingSkill
StylePreservationSkill
NovelAnalysisSkill
```

统一结构：

``` text
Task
↓
Skill
↓
ContextEngine
↓
NovelTools
↓
Execution
↓
Result / Draft / Proposal
```

Skill 不直接操作 Storage。

------------------------------------------------------------------------

# 11. Novel Agent 开发路径 {#11-novel-agent-开发路径}

最终 Agent：

``` text
用户任务
↓
Intent
↓
Plan / Decision
↓
Context
↓
Skill
↓
Tool
↓
执行
↓
Validation
↓
Result / Change
```

第一阶段先实现：

``` text
Task
→ Intent
→ Context
→ Skill
→ Tool
→ Result
```

再逐步加入真正的 Agent Loop。

------------------------------------------------------------------------

# 12. Change Layer 开发路径 {#12-change-layer-开发路径}

最终：

``` text
Draft
↓
Diff
↓
Change / Proposal
↓
Validation
↓
Human Gate
↓
Commit
↓
Canonical
```

Canonical 不再等价于 latest Draft。

> **只有 Commit 后才成为 Canonical。**

------------------------------------------------------------------------

# 13. World Model 与 Change Layer 的连接 {#13-world-model-与-change-layer-的连接}

这是核心回路：

``` text
Agent
↓
Draft
↓
Diff
↓
Change
↓
Human Gate
↓
Commit
↓
Canonical
↓
World Model Update
```

World Model 不能在 AI 草稿阶段直接更新。

------------------------------------------------------------------------

# 14. DSH Integration 开发路径 {#14-dsh-integration-开发路径}

Qianyan：

``` text
NovelAgent
NovelSkill
NovelTool
ContextEngine
WorldModel
ChangeLayer
```

DSH：

``` text
Agent Loop
Tool Calling
Session
Permission
Sandbox
Subagent
Trace
Resume
LLM Runtime
```

连接：

``` text
NovelAgent
↓
Qianyan RuntimeAdapter
↓
DSH
↓
Tool Call
↓
NovelTool
↓
Tool Result
↓
DSH Agent Loop
↓
NovelAgent
```

## DSH 接入实际顺序

``` text
1. 定义 RuntimeAdapter
2. 定义 Agent Session Contract
3. 定义 Tool Call Contract
4. 定义 Tool Result Contract
5. 接入单个 NovelTool
6. 接入 Agent Loop
7. 接入 Session
8. 接入 Permission
9. 接入 Resume / Trace
10. 替换现有内部 Runtime
```

------------------------------------------------------------------------

# 15. UI / Client 连接路径 {#15-ui--client-连接路径}

UI 不直接连接 DSH。

正确：

``` text
PC / Android UI
↓
Application API
↓
Novel Agent / UseCase
↓
Context / Skill / Tool
↓
Domain
```

Agent Activity：

``` text
DSH Runtime Trace
+
Qianyan Project Activity
↓
Application API
↓
UI
```

但：

``` text
DSH Session History
≠
Qianyan Project History
```

------------------------------------------------------------------------

# 16. UI Prototype 路径 {#16-ui-prototype-路径}

UI Prototype 与后端核心并行。

使用 Mock：

``` text
Mock World Model
Mock Context
Mock Agent
Mock DSH
Mock Change
```

模拟：

``` text
自然语言任务
↓
Agent Working
↓
Context
↓
Tool
↓
Result
↓
Draft
↓
Diff
↓
Human Gate
↓
Commit
```

目的：验证作者是否真的愿意这样使用 Qianyan。

确认后再进入 Desktop 正式 UI。

------------------------------------------------------------------------

# 17. PC / Android 最终连接 {#17-pc--android-最终连接}

## PC

``` text
Desktop UI
↓
Application API
↓
Novel Agent
↓
Context / Skill / Tool
↓
Change Layer
```

## Android

``` text
Android UI
↓
Application API
↓
Novel Agent
↓
Context / Skill / Tool
↓
Change Layer
```

两端共享：

-   Domain
-   World Model
-   Context
-   Tools
-   Skills
-   Agent
-   Change Layer
-   Repository

不允许为 Android / Desktop 建两套小说业务逻辑。

------------------------------------------------------------------------

# 18. 已完成 / 未完成总表 {#18-已完成--未完成总表}

  模块                 状态    下一步
  -------------------- ------- ---------------------------------------
  Core Model           ✅      复用
  Core Engine          🟡      按新架构收敛
  Provider             ✅      复用
  Storage 基础         🟡      修 migration / schema
  Android Writer       ✅      后续接 Agent
  Android Reader       🟡      后续完善
  Desktop Writer       🟡      接 Change Layer
  Application API      🟡      作为统一入口
  World Model          ❌      **优先正式设计**
  World Model Update   ❌      Canonical→Extraction→Update
  Context Engine       🟡      收敛为统一入口
  Retrieval            ❌/🟡   建立真实 Index
  Novel Tool           🟡      建立生产 Tool Layer
  Novel Skill          🟡      建立 Skill Contract
  Novel Agent          🟡      固定 Pipeline → Agent Loop
  Change Layer         🟡      打通生产链
  Validation           🟡      接入 Change
  Human Gate           🟡      统一 PC/Android
  Commit               🟡      接 Canonical
  Canonical            🟡      与 Draft 解耦
  Project History      🟡      Commit 后真实写入
  Activity             🟡      Runtime Trace 与 Project History 分离
  DSH                  🟡/❌   已审查，未生产接入
  Runtime Adapter      ❌      DSH 前置契约
  UI Prototype         🟡      与核心并行验证
  Desktop 正式 UI      ❌      Prototype 确认后
  Android Agent UI     ❌      Agent 链稳定后

------------------------------------------------------------------------

# 19. 开发阶段顺序 {#19-开发阶段顺序}

## Phase 0 --- Connection Foundation {#phase-0--connection-foundation}

**先做，不开发大功能。**

目标：

``` text
UI
↓
Application
↓
Agent
↓
Context
↓
Tool
↓
Domain
↓
Storage
```

同时：

``` text
Agent
↓
RuntimeAdapter
↓
DSH Contract
```

以及：

``` text
Agent
↓
Change Layer Contract
```

目标只有：

> **所有未来模块都有正确的连接位置。**

## Phase 1 --- World Model {#phase-1--world-model}

实现：

-   Entity
-   Relationship
-   State
-   Event
-   Timeline
-   Knowledge Boundary
-   Foreshadowing

建立：

``` text
Canonical
↓
Extraction
↓
Validation
↓
World Model
```

## Phase 2 --- Retrieval + Context {#phase-2--retrieval--context}

``` text
Index
↓
Retrieval
↓
ContextEngine
```

形成：

``` text
Task
↓
ContextRequest
↓
Context
```

## Phase 3 --- Novel Tool Layer {#phase-3--novel-tool-layer}

先让 Agent 能真正读取：

``` text
Chapter
Character
State
Event
Timeline
Foreshadowing
KnowledgeBoundary
```

## Phase 4 --- Skills {#phase-4--skills}

建立第一批小说 Skill。

## Phase 5 --- Agent {#phase-5--agent}

``` text
Natural Language
↓
Intent
↓
Context
↓
Skill
↓
Tool
↓
Result
```

再逐步加入自主 Agent Loop。

## Phase 6 --- Change Layer {#phase-6--change-layer}

``` text
Draft
↓
Diff
↓
Proposal
↓
Validation
↓
Human Gate
↓
Commit
↓
Canonical
```

## Phase 7 --- DSH Integration {#phase-7--dsh-integration}

``` text
RuntimeAdapter
↓
DSH
```

先单 Tool，再 Agent Loop，再 Session，再 Permission / Resume / Trace。

## Phase 8 --- UI Prototype Validation {#phase-8--ui-prototype-validation}

这一阶段从 Phase 0 就可以并行。

最终验证：

-   自然语言入口
-   Agent 工作状态
-   正文体验
-   Context 按需出现
-   Proposal / Diff
-   Human Gate
-   History
-   Reader
-   Project

## Phase 9 --- Desktop / Android 正式整合 {#phase-9--desktop--android-正式整合}

最后让两个客户端消费稳定的 Application API。

------------------------------------------------------------------------

# 20. 每个功能的统一连接规则 {#20-每个功能的统一连接规则}

以后开发任何新功能，都必须回答：

``` text
1. 它属于哪一层？
2. 谁调用它？
3. 它调用谁？
4. 输入是什么？
5. 输出是什么？
6. 是否经过 Application / Domain 边界？
7. 是否需要进入 World Model / Change Layer？
```

如果回答不了：

> **不要开始开发这个功能。**

------------------------------------------------------------------------

# 21. 三条绝对不能破坏的边界 {#21-三条绝对不能破坏的边界}

## 边界一：Agent 不直接碰 Storage

``` text
Agent
↓
Tool / Application
↓
Domain
↓
Repository
↓
Storage
```

## 边界二：AI 草稿不等于 Canonical

``` text
AI
↓
Draft
↓
Diff
↓
Human Gate
↓
Commit
↓
Canonical
```

## 边界三：DSH 不成为小说业务层

``` text
Qianyan
负责：小说

DSH
负责：Agent Runtime
```

------------------------------------------------------------------------

# 22. 最终闭环 {#22-最终闭环}

``` text
                    ┌──────────────┐
                    │    作者      │
                    └──────┬───────┘
                           ↓
                      自然语言任务
                           ↓
                     Novel Agent
                           ↓
                    “我需要什么？”
                           ↓
                    Context Engine
                           ↓
                     Novel Tools
                           ↓
             ┌─────────────┼─────────────┐
             ↓             ↓             ↓
        World Model      正文        Retrieval
             └─────────────┼─────────────┘
                           ↓
                        Context
                           ↓
                         Skill
                           ↓
                      Novel Agent
                           ↓
                         Draft
                           ↓
                    Diff / Validation
                           ↓
                       Human Gate
                           ↓
                         Commit
                           ↓
                       Canonical
                           ↓
                   World Model Update
                           │
                           └──────────────→ 下一次任务
```

底层：

``` text
Novel Agent
↓
RuntimeAdapter
↓
DSH
↓
Agent Loop / Tool Calling / Session / Resume
```

------------------------------------------------------------------------

# 23. 当前开发口令 {#23-当前开发口令}

> **先连接，后开发。**
>
> **先契约，后实现。**
>
> **先验证链路，后扩展能力。**
>
> **Qianyan 负责小说，DSH 负责运行 Agent。**
>
> **Canonical 是事实源，World Model 是可计算的小说世界状态，Context
> Engine 决定当前任务需要什么，Novel Tool 是 Agent
> 进入小说世界的受控接口。**

这份文档作为后续开发基线。

任何新功能如果无法说明自己的连接路径，不进入正式开发。
