# 《Qianyan PC UI 功能与后端能力契约》

> 依据：qwenyan-v4 真实源码（目录 `d:\qwenyan-v4\qwenyan-v4`）。本文档所有类名/方法名/参数/返回值均来自源码，未猜测。
> 说明：后端契约以 `application/di/ApplicationContainer` 暴露的能力为核心。PC 侧编排由 `app/desktop` 的
> Desktop Adapter 完成，**不修改任何现有模块**。
> 版本基线：**P20-PC2（`feature/p14-f`）**；schema **v17**；本文件同步至 PC Foundation + Desktop Writer 落地后的真实状态。
>
> **P20-PC1 同步记录**：本文档已从 P14 基线（`7d7736b`）同步至当前主线，更新内容仅限
> ① 当前已存在的真实能力 ② P19 Decision Model ③ P20-P2 Controlled Markdown v1
> ④ P20-P3 Writer seam ⑤ P20-P4 Reader seam ⑥ 已完成/未完成状态。未据此设计任何新功能。
>
> **P20-PC2 同步记录**：仅更新状态 —— 「04 正文创作」已由骨架变为**真实接线**
> （`writerGateway` + `workflowFacade`）；`WriterGateway.rewrite` 无「修改方向」参数（未改契约）。

---

## 第一部分：PC UI 功能地图

### 1. 作品
- 新建作品：`container.novels.createOriginal(...)`
- 查看作品：`container.novels.getNovel(novelId)`
- 作品列表：`container.novels.listOriginals()`
- 新建变体：`container.novels.createVariant(context, name)`
- 作品状态：领域 `Novel.status: ProjectStatus`；`Novel.scope: VariantScope`；`Novel.genre: List<String>`
- **修改作品**：不存在独立“更新 Novel”UseCase（Original 只读；Variant 元数据可经 `saveVariantData`，但 NovelUseCases 未暴露 update）。

### 2. TXT
- 导入：`container.txts.importTxtAsOriginal(source: TxtSource, title: String = ""): TxtImportOutput`
- 文档查询：`container.txts.findDocumentsByNovel(novelId): List<TxtDocument>`
- 分析：`container.analysis.analyzeTxtOriginal(documentId, vocabularyId, variantContext): AnalysisOutput`
- 导入→后续：导入即创建 Original Novel 并绑定 document/chapters/blocks；分析产出 PENDING `VocabularyCandidate`。

### 3. 章节
- 列表：`container.chapters.listByNovel(novelId, variantId?): List<Chapter>`
- 创建：`container.chapters.createNextChapter(title, novelId, variantId?): Chapter`
- 查询单章：`container.chapters.findById(chapterId): Chapter?`
- 章节内容（正文 Draft）：**读写经 P20-P3 Writer seam**（`container.writerGateway` / `container.writerUseCases`）；阅读经 P20-P4 Reader seam（`container.reading`）。UI **不得**直连 `DraftRepository`。
- 状态：`Chapter.status: ChapterStatus`（PLANNED / WRITTEN 等）。

### 4. 词库
- 查询词库：`container.vocabularies.query(scopeLevel): List<Vocabulary>`、`getOrCreateNovelVocabulary(novelId)`
- 候选：`container.vocabularies.findCandidatesByNovel(novelId): List<VocabularyCandidate>`
- AI 分析：词法候选来自 `container.analysis`（默认 Mock）。
- **候选→正式词条确认流程：未实现（DEFER）**；`saveEntry`（写正式词条）可用，但无“候选确认 UI 物流”。

### 5. 记忆 / 知识
- Memory：`container.memories.query(context): List<MemoryEntry>`、`saveEntry(...)`
- Story World Context：`container.storyWorldContextResolver.resolve(novelId, variantId, scope): StoryWorldContext`（只读全量视图）
- Story State 六类：Character / CharacterState / WorldRule / Event / TimelineEntry / Foreshadow（经 `container.storyState` 读 / `container.storyStateVariant` 写 Variant）。

### 6. 规划
- `container.planning` = `PlanningExecutionUseCase.execute(taskId, request, continuationReference?, targetChapterId?): ChapterPlan`
- Agent：`container.planner`（PlannerAgent，经 AgentRuntime→LLMGateway）。当前默认 **Mock**。

### 7. 写作
- `container.writingExecution.execute(taskId, request, plan): Draft`（或 `container.taskRunner.executeWriting`）
- 当前默认 **Mock**；MiMo 后处理为 `WritingPostProcessor` seam，默认 `Passthrough`（算法未实现）。

### 8. 审校
- Critique：`container.critique.execute(taskId, draft): ValidationResult`
- Revision：`container.revision.execute(taskId, currentDraft, critiqueResult): Draft`（revisionCount≤3）
- Knowledge Update：`container.knowledgeUpdate.execute(taskId, draft): KnowledgeUpdateOutcome`（需 CONFIRMED Final）

### 9. 人工确认（HITL）
- 定稿确认：`container.confirmations.confirmFinalDraft(draftId, novelId, variantId?)`
- Workflow 门闸审批：`container.workflowOrchestrator.approveGate(gateId): GateOutcome`
- Facade 层：`container.workflowFacade.approve(chapterId): ChapterWorkflowProgress`

### 10. Workflow / Task
- Task：`container.tasks`（create/start/pause/resume/cancel/complete/fail/saveCheckpoint/restoreCheckpoint/findCheckpoints）
- TaskRunner：`container.taskRunner`（execute / executePlanning / executeWriting / executeCritique / executeRevision / executeKnowledgeUpdate）
- Workflow：`container.workflowOrchestrator`（runForward / approveGate / continueToNextWorkflow）+ `container.workflowService` + `container.workflowFacade`（ChapterWorkflowGateway：startChapter/advance/resume/getChapterProgress/approve/continueToNextChapter）
- 能力：Durable Workflow、Retry(Attempt)≠Revision、Human Gate 幂等、Recovery、Continuation（Ch1→Ch2）。

### 11. 故事连续性
- Story State 六类 + Override：`container.storyStateVariant`（add/override/remove/inherit，Variant-only）
- Narrative State：`container.narrativeState`（appendNarrativeDelta / projectNarrativeState / getNarrativeState / listNarrativeDeltas）
- Foreshadow 生命周期：`container.foreshadowLifecycle.transitionForeshadow(...)`
- Reveal：`container.reveals`（createReveal / getRevealById / listByScope）
- Chapter Context Pack：`container.chapterContextPack.compileChapterContext(novelId, variantId, chapterId, windowSize, budget): ChapterContextPack`
- Rolling Horizon：`container.rollingHorizon`（proposeCandidates / storeCandidates / candidatesFrom）

### 12. Provider / Model
- `LLMGateway`（契约，`provider:api`）；实现 `DeepSeekLLMGateway` / `MiMoLLMGateway` / `MockLLMGateway`（`provider:impl`）
- 装配：`DefaultProviderAssembler`；`ModelProfile`（MOCK / DEEPSEEK_V4_FLASH / MIMO_V2_5）
- API Key：经 `ProviderCredentialStore`（Android 用 `AndroidKeystoreProviderCredentialStore`）
- **PC（P20-PC1 已交付）**：`FileProviderCredentialStore`（明文落盘，加密见 PC-8）+ `DesktopProviderAssembler`（MOCK → `DesktopOfflineLlmGateway`；真实 Provider 透传）

### 13. P19 Decision Model（已实现 · PC 侧未接线）
- 契约：`core/model/.../decision/{DecisionPolicy,DecisionType,DecisionOutcome}.kt`（stateless：仅 `decisionType` / `outcome` / `source`）
- 用例：`container.decisionModelUseCases`（`DecisionModelUseCases`）；seam：`container.decisionModelGateway`
- 快照：`DecisionPolicySnapshot`（FD-4：随 Checkpoint 持久化，Resume 只解码不重算）
- 约束：Planner / Writer 消费**同一份**政策；UI 不得自行 decide

### 14. P20-P2 Controlled Markdown v1（已实现 · PC 侧未接线）
- 引擎：`core/engine/.../markdown/{ControlledMarkdown,MarkdownModel,MarkdownRenderer}.kt`（确定性；非法结构安全降级）
- 正文格式：`ChapterDraft.format`（`null` = legacy 纯文本；`markdown:controlled:v1`）
- 约束（FD-1）：新产生的 Draft 打标；已有 format 不回溯改写；legacy 不做 Markdown 解析

### 15. P20-P3 Writer seam（已实现 · **PC 已于 PC-2 接线**）
- `container.writerGateway`（`WriterGateway`）：`loadContext` / `saveContent` / `continueWriting` / `rewrite`
- `container.writerUseCases`（`WriterUseCases`）：Draft 读写 + `stampControlledMarkdown`（由 `WriterFacade` 内部复用）
- 约束：正文读写经该 seam，**不得 UI → DraftRepository 直连**
- PC-2 状态：Desktop `WriteScreen → WriterController → WriterGateway`；HITL 与阶段投影经 `workflowFacade`
  （`getChapterProgress.waitingForUser` / `approve`）；保存只改 `content`+`updatedAt`；
  `rewrite` **无修改方向参数**（未改契约，UI 不提供无效输入）；单步推进 `continueWriting` 语义未改

### 16. P20-P4 Reader seam（已实现 · PC 侧接线属 PC-3）
- `container.reading`（`ReadingUseCases`）：`openChapter` / `savePosition` / `position`，返回 `ReaderChapter` DTO
- `container.readingProgressRepository`（`ReadingProgressRepository`，schema v17 的 `ReadingProgress` 表）
- 约束（FD-7 / FD-9）：正文只来自既有 `Draft`；不新建第二套正文模型；位置职责最小化

---

## 第二部分：逐项功能契约（真实签名）

> 统一格式。输入/输出类型为真实 Kotlin 类型。

### 1. 新建作品
```text
UI功能：新建小说
用户操作：输入标题（可选 genre/synopsis）
UI展示：创建成功 → Novel 跳详情
真实后端入口：模块 :application · 类 com.qianyan.application.usecase.novel.NovelUseCases · 方法 createOriginal
输入：title: String；genre: List<String> = []; synopsis: String = ""；source: ProjectSource = ORIGINAL_NOVEL；novelId: NovelId?；projectId: ProjectId?
返回：NovelId
状态/异常：ApplicationError（EntityNotFound 等）
当前状态：可直接使用
UI建议：表单 → novels.createOriginal
```

### 2. 作品列表 / 查看
```text
UI功能：作品列表 / 查看详情
UI展示：卡片列表 / 详情（title/genre/synopsis/status）
真实后端：类 NovelUseCases · listOriginals(): List<Novel> / getNovel(novelId): Novel?
当前状态：可直接使用；需 Desktop Adapter 只读展示
```

### 3. 新建变体
```text
UI功能：从 Base 派生变体
后端：NovelUseCases.createVariant(context: VariantContext, name: String, variantId?: VariantId?): VariantId
状态：可直接使用（Variant-only）
```

### 4. TXT 导入
```text
UI功能：导入 TXT
用户操作：选 TXT 文件 → 导入
展示：导入结果（文档/章节/块计数、是否重复）
真实后端：com.qianyan.application.usecase.txt.TxtUseCases.importTxtAsOriginal(source: TxtSource, title: String = ""): TxtImportOutput
输入：TxtSource（平台无关字节源）+ title
返回：TxtImportOutput(documentId, novelId, variantContext, isDuplicate, contentHash, encoding, charCount, chapterCount, blockCount)
异常：TxtImportFailed / UnsupportedEncoding / EmptyDocument / InvalidText / ParseFailed
当前状态：可直接使用（确定性引擎）；文件选择需 Desktop Adapter（替代 SAF）
```
### 5. TXT 分析（词法候选）
```text
UI功能：词库 AI 分析
真实后端：AnalysisUseCases.analyzeTxtOriginal(documentId: TxtDocumentId, vocabularyId: VocabularyId, variantContext: VariantContext): AnalysisOutput
产出：VocabularyCandidate（PENDING）落库（transient AnalysisResult）
状态：✅ 可接入，但当前默认 Mock provider
UI建议：分析页可设计完整；正式数据由 Trae 替换 Mock
```

### 6. 章节列表 / 创建 / 查询
```text
后端：ChapterUseCases.listByNovel(novelId, variantId?): List<Chapter>；createNextChapter(title, novelId, variantId?): Chapter；findById(chapterId): Chapter?
状态：可直接使用
```

### 7. 词库查询 / 候选
```text
后端：VocabularyUseCases.query(scopeLevel): List<Vocabulary>；getOrCreateNovelVocabulary(novelId): VocabularyId；findCandidatesByNovel(novelId): List<VocabularyCandidate>
状态：可直接使用（候选转正式流程未实现 → 见第四部分）
```

### 8. Memory
```text
后端：MemoryUseCases.saveEntry(...) / query(context: VariantContext): List<MemoryEntry>
状态：可直接使用（展示）
```

### 9. Story World Context（只读）
```text
后端：StoryWorldContextResolver.resolve(novelId, variantId, scope): StoryWorldContext
状态：可直接使用（世界观面板；全量视图）
```

### 10. Story State 六类 + Variant 写入
```text
后端（新增/覆盖）：StoryStateVariantUseCases.addCharacter(ctx, entity)/addCharacterState/addWorldRule/addEvent/addTimelineEntry/addForeshadow；
override*(ctx, entity) → OverrideId；remove/inherit(ctx, kind, id) → OverrideId
状态：可直接使用（Variant-only；Original 只读）；实体读取经 container.storyState（StoryStateRepository）
```

### 11. 规划
```text
UI功能：为章节生成 ChapterPlan
后端：PlanningExecutionUseCase.execute(taskId, request: UserWritingRequest, continuationReference?: ContinuationReference?, targetChapterId?: ChapterId?): ChapterPlan
状态：✅ 接口完整，当前默认 Mock
```

### 12. 写作
```text
UI功能：由 plan 生成正文 Draft
后端：WritingExecutionUseCase.execute(taskId, request: UserWritingRequest, plan: ChapterPlan): Draft；或 TaskRunner.executeWriting(taskId, request, plan): Draft
状态：✅ 可接入，当前默认 Mock；MiMo 后处理 = Passthrough（未实现算法）
```

### 13. Critique / Revision / KU
```text
Critique：CritiqueExecutionUseCase.execute(taskId, draft: Draft): ValidationResult
Revision：RevisionExecutionUseCase.execute(taskId, currentDraft: Draft, critique: ValidationResult): Draft（revisionCount≤3）
KU：KnowledgeUpdateExecutionUseCase.execute(taskId, draft: Draft): KnowledgeUpdateOutcome（需 CONFIRMED Final）
状态：✅ 接口完整，默认 Mock
```

### 14. 定稿确认（HITL）
```text
后端：ConfirmationExecutionUseCase.confirmFinalDraft(draftId: DraftId, novelId: NovelId, variantId?: VariantId?): Draft
状态：✅ 可直接使用（幂等、不触发 LLM）
```

### 15. Task 管理
```text
后端：TaskManagerUseCases.create(type: TaskType, taskId: TaskId = next): TaskId；start/pause/resume/cancel/complete/fail(taskId, ...)：Task；saveCheckpoint(taskId, stage, snapshot?: JsonObject): Checkpoint；restoreCheckpoint(taskId): Checkpoint；findCheckpoints(taskId): List<Checkpoint>；findById(taskId): Task
状态：✅ 可直接使用
```

### 16. TaskRunner 受管执行
```text
后端：TaskRunner.execute(taskId, source: TxtSource, title): Task；executePlanning(...)；executeWriting(taskId, request, plan): Draft；executeCritique(taskId, draft): ValidationResult；executeRevision(...)；executeKnowledgeUpdate(taskId, draft): KnowledgeUpdateOutcome
状态：✅ 可直接使用（编排层）
```

### 17. Durable Workflow / Facade
```text
Orchestrator：WorkflowOrchestrator.runForward(workflowId: WorkflowId): WorkflowRunResult；approveGate(gateId: WorkflowHumanGateId): GateOutcome；continueToNextWorkflow(sourceWorkflowId): WorkflowId
Facade（ChapterWorkflowGateway）：container.workflowFacade.startChapter(novelId, variantId?, chapterId, kind: WorkflowKind = WRITE_NOVEL): ChapterWorkflowProgress；advance(chapterId)；resume(chapterId)；getChapterProgress(chapterId)；approve(chapterId)；continueToNextChapter(chapterId)
Service：container.workflowService（WorkflowService）
状态：✅ 可直接使用（HITL 幂等、Recovery、Retry≠Revision、Continuation）
```

### 18. Narrative State
```text
后端：NarrativeStateUseCases.appendNarrativeDelta(delta: NarrativeDelta, expectedVersion?: Long?): Long；projectNarrativeState(novelId, variantId?): NarrativeState；getNarrativeState(novelId, variantId?): NarrativeState?；listNarrativeDeltas(novelId, variantId?): List<NarrativeDelta>
状态：✅ 可直接使用（Original 只读；append 需 Variant）
```

### 19. Foreshadow 生命周期
```text
后端：ForeshadowLifecycleUseCases.transitionForeshadow(ctx: VariantContext, foreshadowId: ForeshadowingId, targetState: ForeshadowLifecycleState, reason: String?, occurredAt: Instant, payoffChapterId?: ChapterId?): Foreshadow
状态：✅ 可直接使用（Variant-only；非法过渡/同态拒绝）
```

### 20. Reveal
```text
后端：RevealUseCases.createReveal(ctx: VariantContext, entity: Reveal): Reveal；getRevealById(id: RevealId): Reveal?；listByScope(ctx: VariantContext): List<Reveal>
状态：✅ 可直接使用（Reader-only；Variant-only）
```

### 21. Chapter Context Pack
```text
后端：ChapterContextCompileUseCases.compileChapterContext(novelId: NovelId, variantId?: VariantId?, chapterId?: ChapterId?, windowSize: Int = DEFAULT, budget: Int = DEFAULT): ChapterContextPack；invalidatePack(old: ChapterContextPack, windowSize): Boolean
状态：✅ 可直接使用（确定性、有界）
```

### 22. Rolling Horizon
```text
后端：RollingHorizonUseCases.proposeCandidates(pack: ChapterContextPack): List<RollingHorizonCandidate>；storeCandidates(taskId, candidates, stage): Unit；candidatesFrom(checkpoint): List<RollingHorizonCandidate>?
状态：✅ 可直接使用（有界、Workflow-local）
```

### 23. Genre Taxonomy（P14-A，受控 seam）
```text
后端：GenreTaxonomyUseCases.availableGenres(): List<Genre>；isKnown(genreId): Boolean；validate(ids: List<GenreId>): List<GenreId>
状态：⚠️ seam 可用；Confirmed-Genre 写入 = BLOCKER（缺 NovelVariant.genre 槽，未交付，见第六部分）
```

---

## 第三部分：接口真实、AI 内容当前为 Mock 的功能
> 这些能力的**接口与持久化真实存在**，仅"AI 生成内容"当前为 Mock；其余（Task/Checkpoint/Draft/记忆落库）为真实。
1. 词法 AI 分析（`container.analysis`，默认 MockLLMGateway）—产出 PENDING 候选。
2. 规划 `container.planning` / `container.planner`（默认 Mock）→ ChapterPlan。
3. 写作 `container.writingExecution` / `container.writer`（默认 Mock）→ Draft。
4. Critique `container.critique` / Revision `container.revision` / Knowledge Update `container.knowledgeUpdate`（默认 Mock）。
5. MiMo 后处理 = `PassthroughWritingPostProcessor`（算法未实现）。
> **PC 侧补充（P20-PC1）**：桌面 `ProviderType.MOCK` 走 `DesktopOfflineLlmGateway`——上游自带
> `MockLLMGateway` 的默认响应只覆盖词汇分析，Planner/Writer/Critic/KU 的结构不在其中；
> PC 侧用 Adapter 补齐确定性示意响应，未改共享核心。真实 Provider（DeepSeek/MiMo）透传。

## 第四部分：能力缺口（**P20-PC1 同步后的真实状态**）
| 能力 | 当前行为 | 后端现状 | 归属阶段 | PC-1 处理 |
|---|---|---|---|---|
| 提案式「剧情方向选择 / 滚动地平线」 | 有候选列表设计 | 仅有 `storeCandidates`（Workflow-local 落 Checkpoint），无「HumanGate 选方向→进入规划」编排 | 部分 P14/P15 | 未接线（骨架） |
| 词法候选确认转正式词条 | 有确认按钮设计 | `vocabularyUseCases.confirmCandidate / rejectCandidate / editCandidate` **已实现**（FD-6：复用既有模型） | 已交付（P1/P6） | 未接线（后续阶段） |
| 作品详情编辑（改简介 / tag） | 有编辑表单设计 | 无 Novel 更新 UseCase（Original 只读，P2.4 写保护） | 后续 | 不实现 |
| Story Foundation / 故事方向 | 有设定页设计 | **已实现**（P14-F：`foundationDecisions` / `ideaFirstGateway` / `storyIntentUseCases`） | 后端已交付 | 骨架（PC-4 接线） |
| Author 智能 | 有风格设置设计 | **已实现**（P16–P19：`authorPreferenceUseCases` / `authorCoreUseCases` / `authorDnaUseCases` / `decisionModelUseCases`） | 后端已交付 | 骨架（PC-6 接线） |
| 受控 Markdown 正文展示 | 有稿纸设计 | **已实现**（P20-P2：`ControlledMarkdown` + `Draft.format`） | 已交付 | 骨架（PC-2/PC-3 接线） |
| 阅读位置 / 续读 | 有阅读设计 | **已实现**（P20-P4：`ReadingUseCases` + `ReadingProgress` 表，schema v17） | 已交付 | 骨架（PC-3 接线） |
| Story State 六类**只读读取** | 有观察面板设计 | 只有仓储接口 `container.storyState`，**Application 层无只读 UseCase** | PC-5 前置 | 骨架（PC-5 先补 UseCase） |
| 富文本编辑器 | 有富文本稿纸 | 仅文本 Draft + 受控 Markdown v1；无富文本模型 | 后续（FD-10 排除） | 不实现 |
| 作品删除 / 归档 | 旧原型有删除按钮 | Original 受 `novel_original_delete_protect` 保护（不可物理删除） | PC-7 单独决策 | **不提供入口**（R2 决策） |

## 第五部分：后端能力缺少 UI 入口（PC 侧待接）
- Foreshadow 状态机操作（PLANTED→…→RESOLVED/ABANDONED）：无 UI（Variant-only）。
- Reveal 记录浏览：无 UI。
- Narrative State / 叙事账本 / Chapter Context Pack 预览：无 UI（UseCase 已具备，PC-5 可直连 UseCase）。
- Rolling Horizon 候选展示：无 UI。
- Story State 六类实体管理：无 UI（需先补 Application 层只读 UseCase）。
- Backup / 库管理（`container` 有 `BackupStore` 能力）无 UI。
- Provider 设置：Android 有；**PC 已交付**（P20-PC1：`ProviderSettingsDialog` + Desktop Adapter）。
- 正文写作（读 / 存 / 继续写作 / 改写 / 人工门）：Android 有（P20-P3）；**PC 已交付**（P20-PC2）。
- 定稿确认（`confirmations.confirmFinalDraft`）与知识更新结果展示：两平台 UI 均未接线（后续阶段）。
- Reader 阅读位置续读：Android 已交付（P20-P4）；PC 待接（PC-3）。

## 第六部分：阶段状态（P20-PC2 同步后的真实状态）
- **P14（Genre / Story Foundation / Idea Intelligence）**：后端**已交付**（`genres`、`storyIntentUseCases`、`foundationDecisions`、`ideaFirstGateway`）。PC UI 接线属 PC-4。
- **P15（User Creative Decision Loop）**：后端**已交付**（`foundationDecisions` / `ideaFirstGateway`）。PC UI 接线属 PC-4。
- **P16–P19（Author Intelligence / Decision Model）**：后端**已交付**（`authorPreferenceUseCases` / `authorCoreUseCases` / `authorDnaUseCases` / `decisionModelUseCases` / `decisionModelGateway`）。PC UI 接线属 PC-6。
- **P20-P2（Controlled Markdown v1）**：**已交付**（FD-1）；PC 展示接线属 PC-2 / PC-3。
- **P20-P3（Writer seam）**：**已交付**（`writerGateway` / `writerUseCases`）；**PC 已接线（PC-2）**。
- **P20-P4（Reader seam）**：**已交付**（`reading` / `ReadingProgress`，schema v17）；Android 已接线，PC 接线属 PC-3。
- **P20-PC1（PC Foundation）**：**已落地**——桌面应用可运行、真实 SQLite v17、Provider 设置可用。
- **P20-PC2（Desktop Writer）**：**已落地**——「04 正文创作」真实接线（`writerGateway` + `workflowFacade`：
  读 / 存 / 继续写作 / 改写 / 人工门）；未引入第二套 Writer、未改 Writer 契约、未做 Decision。
- **尚未实现（不得作为 UI 可调后端）**：富文本编辑器、全文搜索、章节重排 / 批量管理、后台调度器、面向用户的 Error Recovery UX、凭证加密、RAG / Vector / Embedding、MCP、Cloud、Multi-Agent、自动学习。
> 原则：**已交付 ≠ PC 已接线**。PC-1/PC-2 只交付已完成的部分，其余骨架页不得被当作已接入能力。

## 第七部分：《PC UI 功能说明》（简版 · P20-PC2 同步）

```text
功能：新建小说
用户可：输入标题（可选题材/简介）→ 创建 → 进入章节列表
后端：已实现（ApplicationContainer.novels.createOriginal）
状态：PC 已接线（P20-PC1 首页）

功能：TXT 导入
用户可：选 TXT → 导入 → 查看（章节/字数/编码、是否重复）
后端：已实现（确定性引擎；不调用 AI）
状态：PC 已接线（P20-PC1 首页；文件选择由 DesktopFilePicker 提供）

功能：章节列表 + 流程状态
用户可：查看章节、看到每章的 ChapterPhase 与人工门等待
后端：已实现（chapters.listByNovel + workflowFacade.getChapterProgress）
状态：PC 已接线（P20-PC1 小说规划页 · 只读）

功能：词库 AI 分析
用户可：对已导入 TXT 触发分析 → 查看候选词 → 确认/拒绝/编辑
后端：接口已实现（含 confirmCandidate/rejectCandidate/editCandidate）；【AI 内容当前 Mock】
状态：PC 未接线（后续阶段）

功能：正文写作（规划→写作→审校→修订→定稿→确认→知识更新）
用户可：查看/编辑正文、保存、AI 继续写作（逐步推进）、AI 改写、通过人工门
后端：已实现（writerGateway = WriterFacade + workflowFacade）；【AI 内容当前 Mock】
状态：PC 已接线（P20-PC2）；定稿确认（confirmFinalDraft）与知识更新入口属后续阶段

功能：阅读（章节正文 + 阅读位置续读）
后端：已实现（reading = ReadingUseCases + ReadingProgress，schema v17）
状态：Android 已接线（P20-P4）；PC 未接线（PC-3）

功能：故事连续性面板（叙事账本 / 上下文包 / 伏笔 / 揭示 / 人物 / 世界规则 / 事件 / 时间线）
用户可：查看与轻量操作
后端：NarrativeState / ChapterContextPack / Reveal / Foreshadow 有 UseCase；
      Story State 六类实体只有仓储接口（Application 层无只读 UseCase）
状态：PC 未接线（PC-5；需先补 Story State 只读 UseCase）

功能：故事创作 / 作者智能
后端：已实现（P14–P19）
状态：PC 未接线（分别属 PC-4 / PC-6）

功能：Provider / API Key 设置
用户可：选择 MOCK / DeepSeek / MiMo、填 Key 与模型 ID、保存并切换
后端：Provider 装配已实现；PC 凭证 = FileProviderCredentialStore（明文落盘）
状态：PC 已接线（P20-PC1）；凭证加密待 PC-8
```

---

## 关键结论（接线边界）
- **入口**：全部走 `ApplicationContainer`（`:application`，纯 JVM）；Desktop 用 `ApplicationContainer.fromDriver(driver, assembler, configuration)`。
- **不改**：`ApplicationContainer` 既有契约、`:application`、`:core`、`:agent`、`:provider`、`:storage`。
- **UI 硬约束**：UI 只经 ApplicationContainer 暴露的 **UseCase / Gateway** 访问能力；
  禁止 `UI → Repository`（含 `draftRepository` / `storyState`）、`UI → SQLDelight`、`UI → WorkflowOrchestrator`。
- **Desktop Adapter 已交付（P20-PC1）**：JVM DB 初始化（schema v1→v17）、PC Provider 凭证存储、
  文件选择、桌面协程编排（`DesktopAppState`）、错误→UI 提示。
- **Desktop Adapter 待办**：凭证加密（PC-8）、导出/批量等（PC-7）。

---

*本文件于 P20-PC2 同步至当前主线真实状态（`feature/p14-f`，schema v17）。*