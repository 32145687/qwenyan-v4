# 千言 PC（Qianyan Desktop）

Kotlin Multiplatform（jvm target）+ **Compose Desktop** 桌面客户端。业务能力全部来自 `qwenyan-v4` 的
`ApplicationContainer`；PC 侧只提供 **Desktop Adapter**（装配、文件选择、凭证）与 UI，不重写任何后端能力。

> 迁移来源：分支 `ui/desktop`（P14 时期原型）。P20-PC1 以当前主线 HEAD 为基准逐项迁移、适配、收口，
> **未 merge / rebase / cherry-pick 该分支**，也未修改其依赖的任何共享核心模块。

## 运行

```bash
./gradlew :app:desktop:run          # 启动桌面应用
./gradlew :app:desktop:jvmTest      # 运行测试（编译 / 装配 / 持久化 / schema v17）
./gradlew :app:desktop:packageMsi   # 打包 Windows 安装包（nativeDistributions 已配置 Msi + Exe）
```

要求：JDK 17、Windows（当前唯一验证平台）。

## 架构

```
Main.kt（Compose 窗口 + 侧边栏导航）
   └── DesktopAppState（UI 状态 + 协程编排；错误统一转用户语言）
         └── DesktopGraph（组合根）
               ├── QianyanDbFactory.open("jdbc:sqlite:%APPDATA%/Qianyan/qianyan.db")
               │     └── DatabaseInitializer（schema v1 → v17，含 ReadingProgress）
               ├── FileProviderCredentialStore        // PC 凭证（明文落盘；加密见 PC-8）
               ├── DesktopProviderAssembler           // MOCK → 离线网关；真实 Provider 透传
               └── ApplicationContainer.fromDriver(...)   // 与 Android 同一装配链
```

数据目录：`%APPDATA%\Qianyan\`（`qianyan.db` + `credentials.properties` + `provider-selection.properties`）。

边界（硬约束）：**UI 只经** **`ApplicationContainer`** **暴露的 UseCase 访问能力**，
不得出现 `UI → Repository` / `UI → SQLDelight 驱动` / `UI → WorkflowOrchestrator` 直连。

## 页面与接线状态（P20-PC1 · PC Foundation）

| 页面          | 接线到的真实能力                                                                                                                                                          | 状态                                                              |
| ----------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------- |
| 01 首页       | `novels.createOriginal / listOriginals / getNovel`；`txts.importTxtAsOriginal`；`chapters.listByNovel`；`workflowFacade.getChapterProgress`；`genres.availableGenres` | 真实（无作品删除入口，见下）                                                  |
| 02 故事创作     | ——                                                                                                                                                                | 骨架（PC-4 接线：`storyIntentUseCases` / `foundationDecisions`）       |
| 03 小说规划     | `chapters.listByNovel` + `workflowFacade.getChapterProgress`（只读）                                                                                                  | 真实（章节列表与流程状态投影）                                                 |
| 04 正文创作     | `writerGateway`（`loadContext` / `saveContent` / `continueWriting` / `rewrite`）+ `workflowFacade`（阶段投影 / `approve` 人工门）                                              | 真实（PC-2，见下）                                                      |
| 05 故事管理     | ——                                                                                                                                                                | 骨架（PC-5；需先补 Application 层 Story State 只读 UseCase）               |
| 06 成书       | ——                                                                                                                                                                | 骨架（PC-3 接线：`ReadingUseCases` / `ReadingProgress` / 受控 Markdown） |
| 07 作者智能     | ——                                                                                                                                                                | 骨架（PC-6 接线：`authorPreferenceUseCases` 等）                        |
| Provider 设置 | `ProviderConfiguration` + `ProviderCredentialStore` + `ProviderAssembler`（切换即重建容器，同一 SQLite 文件）                                                                   | 真实                                                              |

骨架页一律显示「当前阶段未接入」并写明届时应接线的 UseCase，**不提供假按钮、不写死虚假数据**。

## Desktop Writer（PC-2）

「04 正文创作」已接入既有 Writer，接线严格单向：

```
WriteScreen → WriterController（UI 状态编排）→ WriterGateway（WriterFacade）
                                          → ChapterWorkflowGateway（阶段投影 / approve）
```

- **读取**：`WriterGateway.loadContext` → 章节标题 / 章节号 / 最新 Draft（正文、status、format）。
- **保存**：`WriterGateway.saveContent(draftId, content)` —— 只改 `content` + `updatedAt`；
  `draftId` / `chapterId` / `status` / `format` / `previousDraftId` 全部不变，**不产生第二个 Draft**。
- **继续写作**：`WriterGateway.continueWriting` —— 驱动既有 durable Workflow（PLANNING → WRITING → …）。
  **每次调用推进一个流程步骤**（既有 seam 语义，UI 不改写）；状态与人工门由 `workflowFacade` 投影，前端不自建状态机。
- **AI 改写**：`WriterGateway.rewrite` —— 复用既有 Critique → Revision，产出新 `draftId` + `status=REVISED`，
  原稿留在版本链（`previousDraftId`）上。
- **HITL**：`workflowFacade.getChapterProgress().waitingForUser` → 显示「需要确认」与「通过闸门（人工确认）」，
  按钮调用既有 `workflowFacade.approve`。**不自动批准、不绕过 Gate**。
- **DecisionPolicy**：Desktop 不引用 DecisionModel / DecisionPolicy，不重新决定 —— 决策由既有 Application
  orchestration 在 PLANNING 一次性产生并落 Checkpoint（FD-4）；`WriterController` 无任何决策能力。
- **编辑器**：受控 Markdown v1 纯文本编辑（无 RichText / HTML / WebView / 第三方编辑器）。
- **格式语义（FD-1）**：AI 新产生的 Draft = `markdown:controlled:v1`；legacy（`format=null`）**不被**普通保存静默迁移。

## 作品删除（R2 决策）

```text
R2 Decision:
保留 novel_original_delete_protect。
PC-1 不实现 Original 真删除。
作品删除/归档方案后续在 PC-7 单独决策。
```

- 当前 HEAD 的 `DatabaseInitializer` 守卫 DDL 保留三条触发器：`novel_original_update_protect`、
  `novel_original_delete_protect`、`variant_base_must_be_original`（PC-1 未做任何修改）。

- 旧 `ui/desktop` 的 `DesktopNovelDeletion`（物理级联删除 + 删除前备份）与首页「删除」入口
  **未被迁移**；旧 `DesktopDeletionTest` 被改写为 `DesktopOriginalProtectionTest`，
  断言「删除 / 改写均被触发器拦截、数据完好」。

- 首页书架因此只有一个动作：打开作品。

## AI Provider 边界（重要）

- `ProviderType.MOCK` → `DesktopOfflineLlmGateway`：**离线示意稿**。
  原因：上游自带的 `MockLLMGateway` 默认响应只覆盖 P6 的词汇分析，Planner / Writer / Critic /
  KnowledgeUpdater 期望的结构不在其中，直接用会在 PLANNING 阶段抛 `InvalidPlanningOutput`。
  本类在 PC 侧补齐确定性响应，**未修改任何共享核心**。

- `ProviderType.DEEPSEEK` / `MIMO` → 透传真实网关（需在设置中配置 API Key）。

无论哪种 Provider：**Task / Workflow / Checkpoint / Draft / Repository / SQLite 全部是真实实现**，
Mock 只影响 LLM 生成的内容本身。

## 测试

| 测试                              | 覆盖                                                                                  |
| ------------------------------- | ----------------------------------------------------------------------------------- |
| `DesktopGraphSmokeTest`         | 容器装配 + 作品/章节读写；全新库 = schema v17（含 ReadingProgress + 三条守卫触发器）；v16 旧库自动迁移到 v17 且旧数据保留 |
| `DesktopFlowE2ETest`            | 建作品 → 建章节 → Workflow 推进到人工门 → approve → **重开库验证持久化** → 规划产出 ChapterPlan             |
| `DesktopOriginalProtectionTest` | R2：Original 的 DELETE / UPDATE 均被数据库触发器拦截，数据完好                                       |
| `DesktopWriterTest`             | PC-2：Writer Load / Save（身份 & format 不变）/ Continue Writing（驱动既有 Workflow）/ AI Draft 格式 / Rewrite 版本链 / legacy 不被静默迁移 / **UI 层无 Repository·Orchestrator·Decision·SQLDelight 直连（可执行守卫）** |

## 已知限制（PC-1 / PC-2 记录，不在本阶段解决）

- **改写无「修改方向」参数**：当前 `WriterGateway.rewrite(novelId, variantId, chapterId)` 不接收指令文本，
  故改写对话框**不提供**「修改要求」输入框（不伪造无效输入，也不擅自改动已冻结的 Writer 契约）。
- **继续写作一次推进一步**：`continueWriting` 是既有 seam 的语义（每次一个 Workflow 步骤），
  UI 不改写为「一键到底」。
- **定稿确认（`confirmations.confirmFinalDraft`）未接入**：属知识更新前置链路，不在 PC-2 范围。
- **Desktop 凭证加密**：API Key 当前明文落盘于 `%APPDATA%\Qianyan\credentials.properties`。

  ```text
  Known limitation:
  Desktop credential encryption is deferred to PC-8.
  ```

- **UI 只读展示缺口**：Story State 六类实体读取目前只有仓储接口（`container.storyState`），
  Application 层尚无只读 UseCase —— PC-5 需先补 UseCase 再接面板（否则会出现 `UI → Repository`）。

- **旧 UI 能力迁移状态**（部分已重做，部分属后续阶段）：
  旧 Write 页的 workflow 自驱动与旧 `RewriteDialog` **已按新架构重做为 PC-2 Desktop Writer**（走 Writer seam，
  不再自行组合 Planning / Writing）；Book 页正文阅读（PC-3）、Manage 只读面板（PC-5）、Author 面板（PC-6）、
  导出 / 章节批量管理 / 作品删除（PC-7）仍待后续阶段。
