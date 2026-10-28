# P0-3B · Human Approval Architecture Design（审批架构设计）

> **版本状态：v2 — Architecture Decision Freeze（冻结版）。**
> 本文档在 v1 审计报告基础上追加 §17「Architecture Decision Freeze」：9 项架构决策（D1–D9）、Desktop UI 契约、下一阶段实施顺序与验收标准已**正式冻结**，构成下一阶段可执行的实施契约。
> v1 正文中与冻结决策冲突的"待决/倾向"表述已同步修订（§3 处置、§6 场景 14、§8 拒绝语义、§14 未决问题与实施顺序），冲突清点见 §17.6。
>
> 审计对象：`feature/novel-ide` @ `be1b75582322a43a114fdc174597e29cfe2de87c` 主工作树（含未提交的 4A / 4B.1 / 4B.1a / P0-2.1 三条工作线改动）。
> 审计方式：只读代码审计 + 架构设计。**未修改任何生产代码、测试、Schema、Migration。**
> 证据等级标注：〔代码〕= 已由代码逐行确认；〔测试〕= 由既有测试确认；〔推断〕= 基于代码结构推断；〔待验证〕= 需实施/测试后确认。
> 前置事实：A / B / C 三条隔离工作线已分别独立编译通过（见 P0-3B 编译验证报告）；本文引用行号以主工作树当前内容为准。

---

## 1. 当前代码架构与真实调用链

### 1.1 链路一：P0-2.1 受控改写链（✅ 完整、已闭环）

```
WritePage（改写按钮）
  → WriterController.rewrite                    app/desktop/.../ui/write/WriterController.kt:108 区域
  → WriterFacade.rewrite(novelId, variantId, chapterId)       WriterFacade.kt:139
      → workingDrafts.create(..., sessionId=null?...)         WriterFacade.kt:164（内存 WorkingDraft）
      → workflow.proposeRewrite(chapterId, workingDraftId)    WriterFacade.kt:188
  → ChapterWorkflowFacade.proposeRewrite                      ChapterWorkflowFacade.kt:140
  → WorkflowOrchestrator.proposeRewrite                       WorkflowOrchestrator.kt:185-287
      → 目标/作用域/待决 Gate/未完成 Step 四重校验             :189-217
      → workingDrafts.validate（未通过 → 拒绝入闸）            :219-226
      → 建 REVISION Step(COMPLETED, resultReference=WORKING:<id>)  :247-259
      → 建 CONFIRMATION Step(PENDING)                          :262-273
      → createPendingGate(wf, confirmation, workingRef)        :276 → :1156-1169（draftId=null）
      → Workflow.status=WAITING_HUMAN, pendingGateId=<gate>    :277-284
  → 用户批准：WriterController.approveGate → ChapterWorkflowFacade.approve :103
      → WorkflowOrchestrator.approveGate :596
          → resolveApprovalTarget :650（committedByGate 优先 → RestoreFailure/DISCARDED/INVALID 显式失败）
          → resolveGatePending（事务内 PENDING→APPROVED，竞态仅一方成功）:196-209(WorkflowService)
          → commitApproval :693 → commits.commit(projectId, artifactId, CommitApproval(gateId)) :699
              → CommitUseCases.commit :151（@Synchronized + 双重事务内重检）
              → requireApprovalBinding :706（Step 绑定 / WORKING 引用 / Gate 复用检查）
  → 用户拒绝：WriterController.reject :146 → ChapterWorkflowFacade.reject :118
      → WorkflowService.rejectGate :160（PENDING→RESOLVED+REJECTED，幂等）
      → 新轮次仅由显式 advance → reopenRejectedRound :306
```

### 1.2 链路二：NovelAgent 链（⚠️ 候选生成完整，但审批桥接与 UI 对接断裂）

```
AgentPage（文本输入）
  → DesktopAppState.launchAgentTask(text)                     Common.kt:261-286
      → container.novelAgent.run(NovelAgentRequest(projectId, userIntent))   :274
          （❌ 未传 activeChapterId；❌ 返回的 sessionId/workingDraftId/artifactId 全部丢弃，仅 toast）
  → NovelAgent.run                                            NovelAgent.kt:163-300
      INTENT(确定性) → SKILL → requireVariantScope(J-3) → CONTEXT → EXECUTE
      → review :608-640：workingDrafts.create(sessionId=<本会话>) + validate + changes.prepare
      → Artifact READY → evaluateCanonicalWrite → ActionPolicy 恒判 COMMIT_CANONICAL=NeedsHuman
      → sessions.pause + 返回 WAITING_HUMAN                    :259-267
          （❌ 注释明示"不创建 Gate"——候选从未进入正式审批）
  → toast("…在写作页批准或拒绝")                              Common.kt:377
      （❌ 写作页的批准/拒绝操作的是章节 Workflow Gate——该 Gate 从未为本候选创建）
  → NovelAgent.resume / reject：全仓库 **无任何 Desktop/Android 调用点**（grep 已确认）
```

**两条链的实质差异**：WritePage 的改写走链路一（有 Gate、可批准/拒绝/提交）；AgentPage 的任务走链路二（候选悬空）。用户被 Toast 指向写作页后，看到的将是章节 Workflow 自己的状态——若该章节有无关 Workflow，approve/reject 会作用于**无关 Gate**；若无 Workflow，`requireWorkflowByChapter` 抛 `EntityNotFound`。〔代码〕

### 1.3 持久性事实（决定失效语义）

- `ChangeUseCases.store: MutableMap<ChangeArtifactId, ChangeArtifact>` —— **进程内存**（ChangeUseCases.kt:56）。〔代码〕
- `WorkingDraftUseCases.store: MutableMap<WorkingDraftId, WorkingDraft>` —— **进程内存**（WorkingDraftUseCases.kt:52）。〔代码〕
- `Workflow / WorkflowStep / WorkflowHumanGate / CommitHistory / AgentSession / Activity` —— SQLite 持久化。〔代码〕
- 结论：候选（WorkingDraft+Artifact）跨重启必失；Gate/Step 持久但其 `resultReference=WORKING:<id>` 指向的候选重启后失效（Orchestrator 已按 `RestoreFailure` 显式处理，WorkflowOrchestrator.kt:644-646 注释 + resolveApprovalTarget 实现）。〔代码〕

## 2. NovelAgent 与 ChapterWorkflow 的职责差异

| 维度 | NovelAgent（执行者） | ChapterWorkflow / HumanGate（审批权威） |
|---|---|---|
| 定位 | 一次自然语言任务的七相位编排（INTENT→…→COMMIT） | 章节级 durable 工作流 + 人工决议轮次 |
| 会话/状态 | AgentSession（CREATED/ACTIVE/PAUSED/…），轻量身份 | Workflow + WorkflowStep（durable、跨重启、round/replay） |
| 候选载体 | 内存 WorkingDraft → 内存 ChangeArtifact | Step.resultReference=`WORKING:<id>`（durable 引用） |
| 审批 | **不创建 Gate**（NovelAgent.kt:260 注释明示）；WAITING_HUMAN=候选就绪+会话 PAUSED | Gate 创建（proposeRewrite:276）/批准（approveGate:596）/拒绝（rejectGate:160）/重开轮（reopenRejectedRound:306） |
| Canonical 写入 | 仅经 `commits.commit`（resume 路径 :335） | `commitApproval :693 → commits.commit :699`（同一入口） |
| 与 Session 绑定 | WorkingDraft 创建时携带 `sessionId`（NovelAgent.kt:623） | Gate 不感知 AgentSession（绑定经 Step/WORKING 引用） |

## 3. A / B / C 方案比较

| 维度 | A：ChapterWorkflow 唯一审批权威（桥接） | B：NovelAgent 保留独立审批链 | C：统一审批应用服务 |
|---|---|---|---|
| 核心动作 | NovelAgent 在 NeedsHuman 时经**既有** `ChapterWorkflowGateway.startChapter + proposeRewrite` 把 WorkingDraft 送入章节 Workflow；批准/拒绝/提交全部走既有链 | `resume/reject` 保持入口，但必须补齐：候选↔Gate 协调、CommitApproval↔候选↔Session↔Chapter 一一对应、与 HumanGate 的双权威裁决 | 新增薄应用服务（如 `AgentApprovalUseCases`）包装 gateway，UI 只调它 |
| 双权威风险 | **无**：Gate 只有 Workflow 一处创建（proposeRewrite / WorkflowService.createPendingGate），NovelAgent 不再持有审批语义 | **有**：resume 直接接受任意 `CommitApproval`，与 orchestrator.approveGate 平行；必须额外规定“同一候选只能经其一”，否则两个入口都能收敛同一 WorkingDraft | **无**（服务内部仍走 gateway） |
| 所需新代码 | 极少：NovelAgent 注入 `ChapterWorkflowGateway`（已存在的用户层 seam），NeedsHuman 分支改为“桥接 + 显式失败语义”；resume/reject 补绑定校验或退役 | 多：需要新的候选-Gate 映射、双入口互斥规则、resume 幂等收敛、新增状态 | 中：一个新 UseCase + ApplicationContainer 装配 |
| 依赖既有代码 | ✅ 复用 proposeRewrite 的全部四重校验（:189-226）与批准收敛（approveGate:596 / convergeApprovedGate） | ⚠️ 复制 proposeRewrite 已承担的校验责任 | ✅ 复用 |
| 持久化/恢复 | ✅ 候选→Gate 绑定天然持久（Step.resultReference + Gate 行）；重启失效已有 `RestoreFailure` 显式路径 | ❌ 绑定仍要新增持久化载体或依赖内存 | 同 A |
| Desktop UI 收敛 | ✅ WAITING_HUMAN 即 Workflow `WAITING_HUMAN`，写作页/Agent 页可用同一 `getChapterProgress` 事实源 | ❌ UI 需同时理解两套状态 | ✅ 但收益与 A 相同 |
| 主要风险 | 章节已有进行中 Workflow 时桥接被拒（in-flight 校验）→ 需显式失败语义（见 §6-14）；首章等无 Workflow 章节需先 `startChapter` | 与架构原则 1/5 冲突（第二套审批权威）；测试面最大 | 薄服务可能退化为“多一层无状态转发”；改变 ApplicationContainer 装配面 |

**结论：推荐方案 A。** 依据（均基于代码）：① 审批权威唯一性由现有代码天然支持——Gate 的创建只有 `proposeRewrite`（AI 候选路径）与 `WorkflowService.createPendingGate`（作者路径）两处，二者都落 `WorkflowHumanGate` 表并由同一 `requireApprovalBinding` 把关；② C 线已实现并验证了"WorkingDraft → proposeRewrite → approveGate → commit"的完整闭环，方案 A 只是把 NovelAgent 的候选接入同一入口；③ 方案 B 需要重建 proposeRewrite 已有的四重校验与恢复语义，且违反原则 1；④ 方案 C 的全部收益（UI 不碰内部状态）可由方案 A + 既有 `ChapterWorkflowGateway` 直接达成，无需新抽象层。

方案 A 下 `NovelAgent.resume/reject` 的处置：**退役为内部兼容入口**（保留方法与既有测试），Desktop/后续 UI 一律不再调用；同时必须补上 §7 列出的绑定校验（防止残留入口成为越权面）。【冻结 Decision 1/6：UI 不得经 resume 完成正式提交；reject 不得成为绕过正式 Gate 的第二套拒绝入口——见 §17-D1/D6】

## 4. 审批状态机（方案 A 目标态）

```
                       ┌─────────────── NovelAgent.run ───────────────┐
 用户意图 ──► INTENT/SKILL/CONTEXT/EXECUTE
                       │
        ┌──────────────┼──────────────────┐
        ▼              ▼                  ▼
   分析/规划任务    正文候选(Draft)     Validation 失败
        │              │                  │
   COMPLETED     REVIEW: WD+Artifact   Artifact INVALID → session FAILED
   (无审批)           │
                GATE: ActionPolicy(COMMIT_CANONICAL)
                      │ 恒 NeedsHuman 〔代码：ActionPolicyUseCases.kt:73-75〕
                      ▼
            ┌── 桥接（方案 A 新增，唯一新增逻辑）──┐
            │ startChapter(若该章节无 Workflow)   │
            │ proposeRewrite(chapterId, wdId)    │
            └───┬──────────────┬─────────────┘
             桥接成功        桥接失败(in-flight / 作用域不符)
                │                │
   Workflow WAITING_HUMAN    Agent 候选挂起：session PAUSED + Activity 显式记录
   + PENDING Gate            "候选未进入正式审批：<原因>"（不伪装成待审批）
        │
   ┌────┴─────────┐
   ▼ 批准          ▼ 拒绝
 approveGate      rejectGate (幂等)
   │ resolveApprovalTarget（重启失效→RestoreFailure 显式失败）
   │ resolveGatePending（事务竞态仅一方）
   │ commitApproval → commits.commit(CommitApproval(gateId))
   │   └ requireApprovalBinding（Step/WORKING 引用/Gate 复用）
   ▼                                  ▼
 COMMIT 完成,          Workflow 保持 WAITING_HUMAN（pendingGate=REJECTED）
 Workflow→RUNNING→KU   显式 advance → reopenRejectedRound 新轮（不复用旧 Gate）
   ▼
 COMPLETED（session complete；重复 commit 同 Artifact → ALREADY_COMMITTED）
```

NovelAgent 的 `WAITING_HUMAN` 语义（方案 A 后）＝“候选已进入章节 Workflow 正式人工门”，与 `ChapterWorkflowProgress.waitingForUser=true`（ChapterWorkflowFacade.kt:175）同源。**当前代码不满足此等价**（候选无 Gate）——UI 在方案 A 落地前必须区分表达（见 §9）。

## 5. 候选身份与绑定设计

```
AgentSession(sessionId)                      I3 持久化
   │ 1:N（一次会话一候选；重试→新会话或复用 ACTIVE）
   ▼
WorkingDraft(workingDraftId, sessionId, target{novel,chapter,variant})   内存
   │ 1:1
   ▼
ChangeArtifact(artifactId, workingDraftId, change.target)                内存
   │ proposeRewrite 后（方案 A）
   ▼
WorkflowStep(REVISION, resultReference="WORKING:<workingDraftId>")       持久化
   │
WorkflowStep(CONFIRMATION, PENDING) ──► WorkflowHumanGate(stepId, draftId=null) 持久化
   │                                        │ gateKey = wf:step:WORKING:<id>
   ▼                                        ▼
CommitUseCases.commit(projectId, artifactId, CommitApproval(gateId))
   └ requireApprovalBinding：step.phase=CONFIRMATION ∧ chapter/novel/variant 一致
                             ∧ activeWorkingRef==WORKING:<artifact.workingDraftId>
                             ∧ getByGate(gate) 未被其它 Artifact 使用
```

绑定校验责任分层：
- **候选→Session**：WorkingDraft 创建时写入 `sessionId`（已存在，NovelAgent.kt:623）；**resume/reject 目前未校验**（缺口，见 §7-2）。
- **候选→Workflow/Step/Gate**：proposeRewrite 落库时建立，持久化（已存在）。
- **Gate→Commit**：`CommitApproval(gateId)` + `requireApprovalBinding`（已存在；无 stepId Gate 存在跳过缺口，见 §7-3）。

## 6. 状态与边界：20 个场景

| # | 场景 | 当前代码行为（证据） | 预期行为（方案 A） | 验证层 | UI 可见状态 | 需实施 |
|---|---|---|---|---|---|---|
| 1 | 分析任务 | `analyzeChapter` 只读，COMPLETED，无审批（NovelAgent.kt:588-605,206-213）〔代码〕 | 同现状 | NovelAgent | “只读评审，未写入 Canonical” | 否 |
| 2 | 规划任务 | `planChapter` 不落库，COMPLETED（:574-585,196-204）〔代码〕 | 同现状 | NovelAgent | “规划就绪” | 否 |
| 3 | 正文修改候选 | review→Artifact READY→pause+WAITING_HUMAN，**无 Gate**（:218-267）〔代码〕 | 候选→桥接→正式 Gate | NovelAgent+Gateway | “候选已进入正式审批”（当前 toast 误导） | **是** |
| 4 | 批准候选 | 链一 approveGate 全链可用〔代码〕；链二 resume 需调用方自备 CommitApproval，Desktop 无调用点〔代码〕 | UI 只调 gateway.approve | Orchestrator | 进度投影 waitingForUser→RUNNING | UI 接线 |
| 5 | 拒绝候选 | 链一 rejectGate 幂等〔代码〕；链二 reject=discard+cancel（:369-395），但 cancel 的可能不是产候选的会话（见 #13）〔代码〕 | gateway.reject；session 由 NovelAgent 依据 gate 结果收敛 | Gateway+NovelAgent | “已拒绝，可显式开新轮” | **是** |
| 6 | 要求修改后重生成 | 无“改候选”操作；拒绝→discard→重新 run 生成**新** WorkingDraft；同章节 Workflow 新轮由 `advance→reopenRejectedRound` 开启（:306）〔代码〕 | 拒绝即丢弃；重生成=新 run；不复活旧候选 | Gateway | 新轮次进度 | 否（语义已有） |
| 7 | 重复批准 | approveGate→AlreadyApproved（:598-604）〔代码〕；resume 重复→`getByArtifact` → ALREADY_COMMITTED（CommitUseCases.kt:164）〔代码〕 | 同现状（幂等收敛） | Orchestrator/Commit | AlreadyApproved 无副作用 | 否 |
| 8 | 重复拒绝 | rejectGate 对 RESOLVED no-op（:162）；Facade.reject 对 RESOLVED+REJECTED 直接返回（:124-130）；已 APPROVED 再拒→抛错（:125-128）〔代码〕；NovelAgent.reject 重复（discard 已 DISCARDED 的 WD）〔待验证〕 | 同现状 | Gateway | 无副作用 | 补 1 个待验证点 |
| 9 | 候选属另一 Project | `artifactOf` 仅比对 projectId→ARTIFACT_NOT_FOUND（:727-729）；`resolveSession` projectId 不符→SESSION_NOT_FOUND（:435-437）〔代码〕 | 同现状（越界统一不存在） | NovelAgent | 类型化失败 | 否 |
| 10 | Chapter 已变化 | commit 前 `requireCurrentCanonical`：基线 Draft/Chapter 状态不符→STALE（CommitUseCases.kt:640-672）；proposeRewrite 要求 `wf.activeChapterId==chapterId`（:189）〔代码〕 | 同现状 | Commit/Orchestrator | “基线已变化” | 否 |
| 11 | 候选过期/失效 | 重启后 `changes.get`→ARTIFACT_NOT_FOUND（:721-726）〔代码〕；链一 `resolveApprovalTarget`→RestoreFailure（:644-646）〔代码〕；**无持久“失效标记”**（Activity 无对应记录）〔代码〕 | 失效必须显式：Activity/进度投影标记“候选已失效（进程重启）” | Orchestrator/NovelAgent | “候选已失效，请重新生成” | **是**（原则 8） |
| 12 | Session 与 Artifact 不匹配 | **未校验**：resume/reject 经 `artifactOf` 只查 projectId，不查 WorkingDraft.sessionId〔代码〕 | resume/reject 必须 `request.sessionId` 非空且 == WD.sessionId | NovelAgent | 类型化拒绝 | **是** |
| 13 | sessionId 缺失/错误/被替换 | `resolveSession`：null→**静默新建会话**（:421-428），原 PAUSED 会话悬挂；错误→SESSION_NOT_FOUND（终态→SESSION_STATE_INVALID，:438-450）〔代码〕 | resume/reject 禁止 null sessionId（无会话可恢复时类型化失败） | NovelAgent | “请提供原会话” | **是** |
| 14 | Workflow/Step/Gate 绑定缺失 | 无 stepId Gate→requireApprovalBinding **整体跳过**（CommitUseCases.kt:713 `?: return`，含 Gate 复用检查也被跳过）〔代码〕；NovelAgent 候选完全无绑定〔代码〕 | 无 stepId 的 Gate 不得用于生产提交（已冻结为 §17-D8；测试兼容的处理方式见 D8 注意事项） | Commit | —（服务端拒绝） | **是**（方向已冻结） |
| 15 | 并发批准同一候选 | `commit @Synchronized`（:150）+ 事务内 `getByArtifact` 重检（:163-166）；`resolveGatePending` 事务内仅一方 resolve（WorkflowService.kt:196-209）；批准后未提交窗口由 `convergeApprovedGate` 幂等收敛（Orchestrator:717+）〔代码〕 | 同现状 | Commit/Orchestrator | 一方 Approved、另一方 AlreadyApproved | 否 |
| 16 | 提交成功但 UI 未收到 | commit 事务原子，成功事实持久（CommitHistory）；Workflow 链 UI 重新 `getChapterProgress/latestByChapter` 即见真值〔代码〕；resume 路径 session 已 complete（持久）〔代码〕 | UI 一律以投影事实为准，不以 toast 为准 | Commit | 重开页面即正确 | 否 |
| 17 | 进程重启候选丢失 | WorkingDraft/Artifact 内存丢失（§1.3）〔代码〕；resume→ARTIFACT_NOT_FOUND；链一 approveGate→RestoreFailure（显式，不回退 latestByChapter、不重跑 LLM）〔代码〕 | 同现状 + UI 显式“已失效”（配合 #11） | Orchestrator | “候选已失效” | **是**（UI 表达） |
| 18 | 首/末章边界 | 审批链不含章节顺序概念；`continueToNextChapter` 独立于审批（ChapterWorkflowFacade.kt:162）〔代码〕 | 审批与章节边界解耦，不混入 | — | 无影响 | 否 |
| 19 | Validation 失败 | review→INVALID→session FAILED（NovelAgent.kt:222-233）；proposeRewrite validate 未过→拒绝入闸（:219-226）〔代码〕 | 同现状 | NovelAgent/Gateway | “未通过校验” | 否 |
| 20 | Commit 失败/部分失败 | 事务原子（:162-212）；**收尾窗口**：事务成功后 `workingDrafts.discard`（:216）与 `runWorldModelUpdate`（:218）在事务外，若 discard 抛错→调用方收到失败但 commit 已成功〔代码，推断影响〕 | 调用方以 CommitHistory/getByGate 为准重试收敛（convergeApprovedGate 已支持） | Commit | “以历史记录为准” | 记录，暂不改 |

## 7. 关键安全审查

| 问题 | 结论 | 证据等级 |
|---|---|---|
| 伪造 artifactId 越权操作其他候选？ | **跨 Project 不可**（projectId 强校验）；**同 Project 内可行**：知道 artifactId 即可对其 resume/reject——当前无 session 绑定校验，唯一屏障是 UUID 不可预测性 | 〔代码〕跨 Project；〔代码+推断〕同 Project |
| 同 Project 任意 Session 绑定另一 Artifact？ | **可以**。resume/reject 不校验 `WorkingDraft.sessionId`；且 `sessionId=null` 时会静默新建会话再操作 | 〔代码〕NovelAgent.kt:721-734, 419-428 |
| 缺 stepId 的 Gate 绕过绑定校验？ | **是**。`requireApprovalBinding` 首行 `gate.stepId?.let{...} ?: return`——不仅跳过 Step 绑定，**连 Gate 复用检查（getByGate）也被跳过**：一个无 stepId 的已批准 Gate 可提交多个不同 Artifact。缓解事实：现有两条生产 Gate 创建路径（WorkflowService.createPendingGate:177、Orchestrator.createPendingGate:1156）均**必带 stepId**，无 stepId Gate 目前仅见于测试夹具/历史兼容 | 〔代码〕跳过逻辑；〔代码+推断〕生产不可达 |
| 无正式 Gate 写 Canonical 的可达路径？ | AI 路径**不可**：`requireApproval` 对 NeedsHuman 强制 RESOLVED+APPROVED Gate（CommitUseCases.kt:684-695）。合法无 Gate 入口均为作者/导入语义：`commitAuthorSave`（:245，“保存即批准”）、`commitImportedCanonical`（:262）、`revert`（凭据=原 Commit）。NovelAgent.run 的 `approval=null` 分支仅在 ActionPolicy=Allowed 时可达，而 COMMIT_CANONICAL 恒 NeedsHuman→**当前不可达**（死分支） | 〔代码〕 |
| WAITING_HUMAN == 正式审批？ | **不等价**。当前=候选 READY+session PAUSED，无 Gate 行；不等价期间 UI 不得显示“可批准”，应显示“候选已生成，未进入正式审批（或按方案 A 桥接后统一）” | 〔代码〕 |
| 批准/拒绝幂等？ | Workflow 链：批准重复→AlreadyApproved（收敛幂等 convergeApprovedGate）；拒绝重复→no-op；交叉（批后拒/拒后批）→类型化拒绝 | 〔代码〕WorkflowService.kt:113-175, Orchestrator:596-627 |
| NovelAgent.resume 幂等？ | **非幂等成功**：重复 resume→ALREADY_COMMITTED 类型化失败（会话被 settleInterrupted 收敛）；语义可接受但不优雅 | 〔代码〕 |
| 并发双重提交？ | 同一 `CommitUseCases` 实例上 `@Synchronized` + 事务内重检 + Gate 复用检查（stepId Gate）→ 已防；`commits` 为容器 val 单例 | 〔代码〕 |

## 8. 批准 / 拒绝 / 要求修改 的操作语义（目标态）

- **批准**（唯一入口 `gateway.approve(chapterId)`）：前置可收敛判定（resolveApprovalTarget）→ 事务置 APPROVED → commit(CommitApproval(gateId)) → 载体 CONFIRMED → Workflow RUNNING → advance 完成 KU→COMPLETED。中断窗口由 convergeApprovedGate 幂等收敛。
- **拒绝**（`gateway.reject(chapterId)`）：PENDING→RESOLVED+REJECTED（幂等）；Canonical 不变；旧 Gate 决议永不改写。**方案 A 增补【冻结 Decision 6】**：正式拒绝**必须先**经 `gateway.reject` 完成 Gate 决议；随后由一个**仅做协调**的应用层入口收敛 Agent Session（PAUSED→CANCELLED）与候选（discard WD，携带正确 sessionId）；`NovelAgent.reject` **不得**成为绕过正式 Gate 的第二套拒绝入口（保留为内部兼容入口并补绑定校验）。
- **要求修改**：= 拒绝 + 重新发起。旧候选丢弃，新一轮由显式 `advance`（reopenRejectedRound）或新的 `novelAgent.run` 产生**新** WorkingDraft/新 Gate。不存在“原地修改候选”。

## 9. Desktop UI 需要修正的状态与调用链

| # | 位置 | 问题 | 修正方向 |
|---|---|---|---|
| 1 | `Common.kt:261-286 launchAgentTask` | 丢弃 `sessionId/workingDraftId/artifactId`；未传 `activeChapterId`（写作/改写任务会 MISSING_FOCUS_CHAPTER） | 传入当前章节；保留 result 关键 ID（供审计与失败恢复提示） |
| 2 | `Common.kt:377 agentTaskToast` | “在写作页批准或拒绝”——但候选无 Gate，写作页 approve/reject 作用于无关 Gate 或抛错 | 方案 A 落地后：候选已桥接→文案改“已进入《章节》审批（写作页/Agent 页均可处理）”；桥接失败→显式“候选未进入审批：<原因>” |
| 3 | `AgentPage.kt` | 仅文本框；无候选详情/章节上下文/待审批列表 | 展示 `getChapterProgress` 投影（waitingForUser/phase），与写作页同源 |
| 4 | `WritePage.kt:135 onApprove / WriterController:124` | 批准目标=章节 Workflow pendingGate——正确权威，但需保证 Agent 候选已桥接为该 Gate | 方案 A 后天然一致；落地前 UI 需禁止对“非 Gate 候选”显示批准按钮 |
| 5 | 待审批状态来源 | 应统一为 `ChapterWorkflowProgress`（durable、跨重启），而非内存 NovelAgentResult | UI 事实源=投影，不以 toast/内存结果为准 |

## 10. Android 当前边界

- **无 NovelAgent 生产调用链**（grep `app/android` 仅命中 `ChapterWorkflowGateway` 的消费与测试 Fake）。〔代码〕
- **可复用审批 UI**：`ChapterWritingViewModel` 已依赖 `ChapterWorkflowGateway`（approve/reject/proposeRewrite 语义齐备，Fake 已覆盖 proposeRewrite）——若未来接 Agent 任务，Android 无需新审批组件。〔代码〕
- 结论：本轮不扩展 Android；后续接入时按方案 A 复用同一 gateway 即可，无 Android 专属审批设计。

## 11. 需要修改的模块与文件清单（实施阶段用，本轮不动）

| 层 | 文件 | 变更 |
|---|---|---|
| application | `usecase/agent/NovelAgent.kt` | ①NeedsHuman 分支桥接（注入 `ChapterWorkflowGateway`：startChapter+proposeRewrite；失败→显式挂起语义）②`artifactOf` 增加 session 绑定校验 ③`resolveSession` 在 resume/reject 路径禁止 null sessionId ④resume 幂等收敛（ALREADY_COMMITTED→返回既有事实而非 FAILED） |
| application | `usecase/commit/CommitUseCases.kt` | 决策点：无 stepId Gate 的生产禁用策略（见 §14 未决 1） |
| application | （可选）`usecase/agent/`新增桥接私有逻辑或注入点 | 保持 NovelAgent 七相位不变 |
| desktop | `ui/Common.kt`（launchAgentTask/agentTaskToast） | 传章节 ID；保留 result ID；文案与真实状态一致 |
| desktop | `ui/pages/AgentPage.kt` | 章节上下文 + 进度投影展示 |
| desktop | `ui/pages/WritePage.kt` / `ui/write/WriterController.kt` | 待审批 UI 与 Agent 候选对齐（经 getChapterProgress） |
| desktop 测试 | `DesktopWriterTest.kt` / 新增 Agent 桥接测试 | 见 §12 |

不修改：Storage/Schema、P19/P20 Freeze、ApplicationContainer 结构（仅 NovelAgent 构造参数可能新增 gateway 依赖，属既有装配面）、Android。

## 12. 测试计划（不执行）

1. `NovelAgentApprovalBridgeTest`：run(WRITING) → 候选 → 桥接后该章节 Workflow=WAITING_HUMAN 且 pendingGate 的 CONFIRMATION step.resultReference==WORKING:<wdId>；无 Workflow 章节→自动 startChapter。
2. 桥接失败语义：章节 Workflow 有 in-flight step → run 返回显式“未进入审批”结果（非 FAILED 伪装、非静默）。
3. 幂等：approve→approve（AlreadyApproved，单次 commit）；approve 后重复 resume 同 artifact→返回既有 commit 事实。
4. 拒绝：reject→WD DISCARDED + gate REJECTED + session CANCELLED；重复 reject no-op；拒后显式 advance→新 Gate（旧 Gate 决议不变）。
5. 绑定安全：resume/reject 传错误 sessionId→类型化拒绝；传另一 Project artifactId→NOT_FOUND；同 Project 跨 session→拒绝（新增校验）。
6. 无 stepId Gate 决策验证：构造 stepless 已批准 Gate 提交两个不同 Artifact——实施前记录当前放行行为，实施后按决策断言。
7. 重启失效：重建容器后 approveGate→RestoreFailure；resume→ARTIFACT_NOT_FOUND；UI 投影显示“候选已失效”。
8. 并发：双线程 approveGate→恰好一次 commit（getByArtifact/getByGate 双保险）。
9. Desktop 状态一致性：agentTaskToast(WAITING_HUMAN) 文案与 `getChapterProgress().waitingForUser` 同真。

## 13. Schema / Migration 是否必要

**当前不需要。** 依据：候选→Gate 绑定完全由既有持久化实体承载（`WorkflowStep.resultReference` + `WorkflowHumanGate(stepId)` + `CommitHistory.gateId`，均已落库）；Session/Activity 持久化已覆盖审计需要。唯一内存实体是 WorkingDraft/Artifact——按原则 8，其重启失效以**显式失败 + UI 标记**表达（RestoreFailure/ARTIFACT_NOT_FOUND 已类型化），不需要新表。
**未来若要求“跨重启恢复审批候选”**，才需要持久化 WorkingDraft（新表 + 迁移），列为独立决策（§14 未决 4），本轮明确不做。

## 14. 风险、未决问题与实施顺序

**未决问题（v1 提出 → v2 冻结裁定）**

| v1 未决 | 冻结裁定 |
|---|---|
| 1. 无 stepId Gate 的生产策略 | **已冻结 → §17-D8**：缺少必要 WorkflowStep 绑定的 Gate 不得用于正式 Commit；实施阶段审查 `requireApprovalBinding` 缺失 stepId 时不提前跳过其余校验；既有测试兼容行为不得直接删除，应在实施阶段调整夹具或显式标注兼容场景，不得降低生产校验要求 |
| 2. 拒绝后 session 收敛归属 | **已冻结 → §17-D6**：正式拒绝先经 Workflow/Gate 链路；随后由仅做协调的应用层入口收敛 Agent Session 与候选；`NovelAgent.reject` 不得成为第二套拒绝入口 |
| 3. 桥接失败时的产品行为 | **已冻结 → §17-D5**：不静默丢弃、不建第二竞争 Workflow、不伪装成待审批；显式"未接入正式审批"+原因；进程内可保留候选供查看/显式重试；重启丢失则显示失效并要求重新生成 |
| 4. 是否未来支持跨重启恢复候选 | **已冻结 → §17-D7**：本阶段不扩展；不新增表/Migration/恢复系统；如需恢复能力必须作为单独决策阶段处理 |

**实施顺序（已冻结，供下一阶段执行；实施阶段必须继续保护现有 dirty worktree，不得 reset / clean / checkout 覆盖 / restore / stash）**

1. **身份校验加固**：审查 `NovelAgent.artifactOf`、`resolveSession`、`resume`、`reject`，补足会话和候选绑定（D3）。
2. **正式 Workflow 桥接**：通过现有 `ChapterWorkflowGateway` 接入候选，明确冲突、重复调用和失败语义（D2/D4/D5）。
3. **Gate / Commit 安全检查**：审查 `requireApprovalBinding` 的无 `stepId` 路径及 Gate / Artifact / Step 绑定（D8）。
4. **批准后状态收敛**：处理重复调用、Commit 成功后清理失败等场景（D9）。
5. **Desktop UI 对齐**：让 AgentPage、WritePage 和 UI 状态使用统一正式审批事实源（§17-UI 契约）。
6. **专项测试**：覆盖身份不匹配、重复桥接、冲突 Workflow、正式拒绝、候选失效、无 stepId Gate、并发批准、重复批准、批准后状态收敛。
7. **A+B+C 集成验证**：分别完成单线验证后，验证组合后的实际编译与测试结果。

**风险（保留自 v1，仍有效；不影响已冻结决策）**
- NovelAgent 引入 `ChapterWorkflowGateway` 依赖后，`NovelAgentBoundaryTest` 的禁用 token 清单需复核（gateway 属 workflow 包，不在禁用列表；`WorkflowStatus`/`StateMachine` 等 token 不受影响）——〔待验证〕。
- Desktop 写作页与 Agent 页双入口展示同一 Gate，需防“批准按钮双处并发”——幂等已兜底，但 UI 层建议单一入口。
- `proposeRewrite` 的 `MAX_REVISIONS=3` 上限（Orchestrator:1198）会约束同一章节的可审批轮次，Agent 高频重生成可能触顶——产品语义需确认。

## 15. 关键代码位置索引

| 主题 | 位置 |
|---|---|
| NovelAgent.run 主流程 / WAITING_HUMAN | `application/.../agent/NovelAgent.kt:163-300`（WAITING_HUMAN=:259-267） |
| NovelAgent.resume / reject | 同文件 `:308-363` / `:369-395` |
| artifactOf（仅 projectId 校验） | 同文件 `:721-734` |
| resolveSession（null→新建会话） | 同文件 `:419-451` |
| review（WD 创建含 sessionId + validate + prepare） | 同文件 `:608-640` |
| evaluateCanonicalWrite | 同文件 `:642-648` |
| ActionPolicy COMMIT_CANONICAL=NeedsHuman | `application/.../action/ActionPolicyUseCases.kt:73-75` |
| CommitUseCases.commit（@Synchronized+双重重检） | `application/.../commit/CommitUseCases.kt:150-220` |
| requireApproval | 同文件 `:675-697` |
| requireApprovalBinding（stepId 缺失即跳过） | 同文件 `:706-760`（跳过点=:713） |
| proposeRewrite（桥接目标实现） | `application/.../workflow/WorkflowOrchestrator.kt:185-287` |
| Orchestrator.approveGate / resolveApprovalTarget / commitApproval | 同文件 `:596-627` / `:650-` / `:693-714` |
| AI 候选 Gate（draftId=null, workingRef 绑定） | 同文件 `:1151-1172` |
| WorkflowService.approveGate / rejectGate / createPendingGate | `application/.../workflow/WorkflowService.kt:114-191` |
| ChapterWorkflowFacade.approve/reject/proposeRewrite/project | `application/.../workflow/ChapterWorkflowFacade.kt:102-187` |
| WriterFacade.rewrite（C 线受控链） | `application/.../writing/WriterFacade.kt:139-188` |
| 内存存储（失效语义根源） | `ChangeUseCases.kt:56`；`WorkingDraftUseCases.kt:52` |
| Desktop launchAgentTask / toast | `app/desktop/.../ui/Common.kt:261-286, 375-381` |
| Desktop 唯一批准/拒绝链 | `WriterController.kt:124-146` → `ChapterWorkflowFacade` |
| Android 现状 | `app/android/.../chapter/ChapterWritingViewModel.kt:41`（仅 ChapterWorkflowGateway） |

## 16. 证据等级汇总

- 〔代码〕逐行确认：§1 全部调用链、§3 方案事实、§5 绑定现状、§6 中 1-10/12-20 的“当前行为”列、§7 全部结论、§9 断链。
- 〔测试〕：WorkflowService 幂等语义（GateReentryTest 等既有用例覆盖 approve/reject/reopen）；ChapterWritingViewModelTest 覆盖 gateway seam。
- 〔推断〕：无 stepId Gate 生产不可达；commit 收尾窗口（discard 失败）的实际影响面；UUID 不可预测性作为唯一屏障的充分性。
- 〔待验证〕：NovelAgent.reject 对已 DISCARDED WorkingDraft 的重复调用行为；NovelAgentBoundaryTest 对新依赖的兼容性；方案 A 桥接在首章（无 Workflow）路径的 startChapter 幂等组合。

---

*本报告为 P0-3B 唯一设计产出；未修改任何生产代码、测试、Schema、Migration；未运行测试或构建；未 commit/push。*

---

## 17. Architecture Decision Freeze（v2 · 冻结版）

> 本节为 P0-3B 的**正式架构决策冻结**，是下一阶段实施契约。冻结 = 除非经新的显式决策阶段，实施阶段不得偏离；标注〔实施审查〕的细节允许实施阶段在**不违反决策语义**的前提下按真实模型落实。

### 17.1 冻结决策 D1–D9

**D1 · 正式审批权唯一归属** 〔冻结〕
`ChapterWorkflow / WorkflowHumanGate` 是**唯一正式审批 authority**。NovelAgent 负责生成候选、分析和执行任务，不得另建独立正式审批体系。所有正式批准、拒绝和提交都必须进入既有 ChapterWorkflow / Human Gate / CommitUseCases 链路。
禁止：Agent 自行批准并直接写入 Canonical Story；Desktop UI 直接调用独立的 NovelAgent 审批入口（`resume`）完成正式提交；为接通 AgentPage 而新建第二套 Gate / Commit 体系。

**D2 · Agent 候选接入正式审批的桥接方案** 〔冻结〕
采用方案 A：NovelAgent 生成候选，通过**现有** `ChapterWorkflowGateway` 进入正式 ChapterWorkflow，由正式 Workflow 创建 Gate 并驱动审批和提交。
概念链路：`Agent Session → WorkingDraft → ChangeArtifact → ChapterWorkflowGateway → WorkflowStep → WorkflowHumanGate → CommitUseCases`。
**注意：此为概念链路，具体 API 与参数以当前仓库实际定义为准**（现状：`startChapter` / `proposeRewrite` / `approve` / `reject` 已存在于 `ChapterWorkflowGateway`，见 ChapterWorkflowFacade.kt:56/140/103/118；`proposeRewrite` 已实现候选入闸，见 WorkflowOrchestrator.kt:185-287）。
不得引入独立的 AgentApprovalService 重复实现 Gate、Validation、Diff 或 Commit；如需应用层协调入口，只允许**协调现有服务**，不得成为第二个审批 authority。

**D3 · 候选身份绑定** 〔冻结〕
桥接及审批必须校验候选身份，至少覆盖：projectId、chapterId、当前有效的工作流/任务身份、sessionId、`WorkingDraft.sessionId`、ChangeArtifact↔WorkingDraft 关联、Gate↔WorkflowStep/Artifact 关联。
硬性要求：①候选所属会话不匹配→拒绝继续；②不得经 `sessionId == null` 静默新建 Session 继续审批旧候选；③不得只校验 projectId 而忽略 WorkingDraft 会话归属；④不得允许一个 Gate 审批与其绑定对象无关的 Artifact；⑤身份校验失败必须返回明确、可诊断的失败结果。
具体字段与校验位置〔实施审查〕：依据现状，`artifactOf`（NovelAgent.kt:721）需增补 `WorkingDraft.sessionId` 校验，`resolveSession`（:419-451）需禁止 resume/reject 路径的 null-session 静默新建；Gate 侧绑定由既有 `requireApprovalBinding`（CommitUseCases.kt:706）承载（其缺口按 D8 处理）。

**D4 · 重复桥接与幂等性** 〔冻结〕
相同候选被重复桥接时，不得创建多个互相竞争的正式审批流程。若现有状态能证明同一候选已成功进入正式 Workflow，应返回或定位已有流程；无法安全识别时必须明确拒绝或返回可诊断错误，不能猜测。不得仅靠 UI 禁用按钮保证幂等——服务端/应用层必须具备身份与状态校验。
幂等键及存储方案〔实施审查〕：不得臆造字段；优先利用既有天然键（`gateKey = workflow:step:WORKING:<workingDraftId>`，WorkflowOrchestrator.kt:1157/1171 与 `getGateByKey` 的幂等返回，:1158-1159）与 `proposeRewrite` 的"已存在 PENDING Gate→拒绝"前置（:199-204）。

**D5 · 桥接失败的处理** 〔冻结〕
Agent 候选无法进入正式 Workflow（如章节存在冲突的进行中 Workflow）时：不得静默丢弃候选；不得擅自创建第二个竞争 Workflow；不得将候选展示成"等待批准"或"可提交"；必须向 UI 返回明确的**未接入正式审批**状态及原因。当前进程仍持有候选时可保留供查看或显式重试，但它不构成正式审批；重试必须是显式动作并重新校验身份与当前 Workflow 状态；候选因进程重启丢失时必须显示已失效并要求重新生成，不得伪造恢复成功。

**D6 · 拒绝与 Session 生命周期协调** 〔冻结〕
正式拒绝操作必须**先**通过正式 Workflow / Gate 链路完成（`gateway.reject`）；之后再协调 NovelAgent Session 与候选生命周期。如需统一应用层入口，它只能负责协调：①正式 Gate 拒绝；②对应 Agent Session 状态收敛；③候选生命周期处理；④返回一致最终结果。`NovelAgent.reject` 不得成为绕过正式 Gate 的第二套拒绝入口。
必须区分三种情形：拒绝当前候选（Gate 已建立）；要求修改并生成新候选（拒绝旧候选后重新生成新候选，不得偷偷修改或复用已拒绝 Artifact）；当前候选未进入正式审批（不存在可拒绝的 Gate——只能丢弃候选/取消会话，并如实标注）。

**D7 · 进程重启与候选失效** 〔冻结〕
本阶段不扩展跨进程候选恢复能力。正式 Workflow / Gate 的持久化状态仍由现有 Storage 负责；仅存于内存的 WorkingDraft / ChangeArtifact 进程重启后若无法找回，视为候选失效；持久化 Workflow / Gate 引用的候选不存在时必须明确返回失效状态（既有 `RestoreFailure` / `ARTIFACT_NOT_FOUND` 路径已符合方向）；不得将失效候选显示为仍可批准；**不增加**新的数据库表、Migration 或恢复系统；后续如需恢复能力必须作为单独决策阶段处理。

**D8 · 无 stepId 的 Gate** 〔冻结〕
正式生产路径安全策略：**缺少必要 WorkflowStep 绑定的 Gate，不得用于正式 Commit。**
下一阶段实施时审查 `CommitUseCases.requireApprovalBinding`（CommitUseCases.kt:706-760），确保缺失 `stepId` 时（:713 `?: return`）不会提前跳过其余安全校验（尤其 Gate 复用检查 getByGate，:752）。
注意：不修改该函数于本阶段；不未经审查就删除既有测试兼容行为；必须区分正式生产 Gate 契约与可能存在的历史测试辅助构造；若旧测试依赖无 stepId 的 Gate，实施阶段调整夹具或显式标注兼容场景，**不得降低生产校验要求**。

**D9 · 批准后的状态收敛与重复调用** 〔冻结〕
正式 Commit 成功后，Agent Session / Gate / Workflow 的状态应能收敛到一致结果。下一阶段审查：①`NovelAgent.resume` 的重复调用行为（现状：重复 resume→ALREADY_COMMITTED 类型化失败，非幂等成功，CommitUseCases.kt:163-166〔代码〕）；②Commit 已成功但后续候选清理或 World Model 更新失败时的结果（现状：discard/runWorldModelUpdate 在事务外，CommitUseCases.kt:216-218〔代码〕）；③调用方能否经既有 CommitHistory / Gate 关联查询确认提交实际结果（现状：`committedByGate`，:432〔代码〕）；④重复批准是否产生重复 Commit（现状：事务内 getByArtifact 重检 + D8 修复后的 getByGate 双保险〔代码〕）。
**优先复用现有 `convergeApprovedGate`、CommitHistory 与事务校验能力；不得自行引入新事务框架或重做 Storage。**

### 17.2 Desktop UI 契约 〔冻结〕

下一阶段 AgentPage 与 WritePage 必须遵循同一审批事实源：
1. AgentPage 必须传递当前有效的 Project / Chapter 上下文。
2. UI 必须保留候选标识与必要会话身份，不得丢弃 `sessionId`、`workingDraftId`、`artifactId`。
3. UI 是否显示批准操作，以正式 ChapterWorkflow / Human Gate 状态为准。
4. 仅生成候选但未成功桥接的结果，不得显示成待审批。
5. 桥接失败时必须展示明确原因和状态。
6. 候选失效时必须展示失效状态，并允许用户显式重新生成。
7. UI 操作不得自动创建第二个 Workflow、自动 Commit 或绕过 Human Gate。
8. WritePage 现有正式受控改写链路必须保持兼容。
9. 本阶段不改 Desktop UI 代码。

Android：目前没有已确认的 NovelAgent 生产调用链；本阶段及下一阶段均不得顺手扩展 Android 审批路径；若后续发现事实变化，先记录证据再单独决定。

### 17.3 下一阶段实施顺序

见 §14"实施顺序（已冻结）"（7 步：身份校验加固 → 正式 Workflow 桥接 → Gate/Commit 安全检查 → 批准后状态收敛 → Desktop UI 对齐 → 专项测试 → A+B+C 集成验证）。

### 17.4 验收标准（下一阶段每步的完成判据）

| 实施步 | 验收标准（均需真实命令/测试输出，不得推测） |
|---|---|
| 1 身份校验加固 | resume/reject 对错误/缺失 sessionId、跨 session 候选返回类型化失败；null-session 不再新建会话；既有测试全部仍通过 |
| 2 桥接 | run(WRITING) 候选进入章节 Workflow：WAITING_HUMAN + pendingGate 的 CONFIRMATION Step.resultReference==`WORKING:<wdId>`；重复桥接返回既有流程或可诊断错误；桥接失败返回显式"未接入"结果 |
| 3 Gate/Commit 安全 | 无 stepId Gate 不再绕过绑定校验（或按 D8 显式豁免且仅限非生产路径）；Gate 复用检查对全部生产 Gate 生效 |
| 4 状态收敛 | 重复批准→AlreadyApproved 且仅一次 Commit；重复 resume→返回既有提交事实；CommitHistory/committedByGate 可判定真实结果 |
| 5 Desktop UI | AgentPage/WritePage 的待审批展示同源于 `ChapterWorkflowProgress`；未桥接候选不显示批准按钮；桥接失败/候选失效均有显式文案 |
| 6 专项测试 | §12 测试计划全部落地并通过；0 failures |
| 7 集成验证 | A+B+C 组合编译 + 测试 0 failures / 0 errors；`git diff --check` 通过 |

### 17.5 已冻结 vs 待实施阶段验证

| 事项 | 状态 |
|---|---|
| D1–D9 决策语义 | **已冻结** |
| Desktop UI 契约（9 条） | **已冻结** |
| 实施顺序（7 步）与验收标准 | **已冻结** |
| `WorkingDraft.sessionId` 校验的精确插入点、桥接幂等键的最终实现、无 stepId Gate 的兼容豁免机制、resume 幂等收敛的实现方式 | 〔实施审查〕——在 D3/D4/D8/D9 语义边界内由实施阶段按真实模型落实 |
| NovelAgentBoundaryTest 对 gateway 依赖的兼容性；NovelAgent.reject 对已 DISCARDED WD 的重复行为；A+B+C 集成结果 | 〔待验证〕——实施阶段以测试证据确认 |

### 17.6 v1→v2 矛盾清点（已全部消除）

| v1 表述 | v2 裁定 | 修订位置 |
|---|---|---|
| §8 拒绝语义"实施时二选一，倾向前者" | D6 冻结：先正式 Gate 拒绝，再由仅做协调的入口收敛 | §8 |
| §14 未决 1–4 四项开放问题 | 分别冻结为 D8/D6/D5/D7 | §14 |
| §14 实施顺序 5 步"建议" | 冻结为 7 步（新增 Gate/Commit 安全检查、专项测试、A+B+C 集成验证） | §14 |
| §6 场景 14"决策点" | 方向冻结为 D8 | §6-14 |
| §3 resume/reject"退役为内部兼容入口"未提 UI 禁用 resume | 补充 D1 明确 UI 不得经 resume 正式提交 | §3 |

**遗留检查结论：未发现未解决的文档内部矛盾。** §12 测试计划与 §14 冻结顺序的第 6 步一致；§13 Schema 结论与 D7 一致；§4 状态机与 D2/D5/D6 语义一致。
