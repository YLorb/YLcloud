# 前端 Cloudreve 风格改造临时参考

整理日期：2026-07-09

本文档用于后续前端页面改造参考，目标是把 YLcloud 当前界面调整为更接近 Cloudreve 的现代云盘/后台风格，同时保留本项目的文件管理、团队空间、知识库和 RAG 运维能力。

## 1. 风格适配结论

当前项目与参考风格高度匹配。

原因：

- 项目定位是个人云盘、团队空间、知识库和 RAG 管理系统，和 Cloudreve 的云盘/后台气质一致。
- 前端已有 `DriveApp`、`SettingsPanel`、`SpacesView`、`KnowledgeOpsView` 等功能视图，具备改造成统一 App Shell 的基础。
- 当前技术栈是 React + TypeScript + Vite + lucide-react，适合构建轻量、圆角、低阴影、蓝白灰的工具型界面。
- 当前样式已经使用 CSS variables，适合先统一主题 token，再逐页调整视觉。

当前主要差异：

- 主色偏青绿色，参考风格偏 Cloudreve 蓝。
- 文件页当前以表格列表为主，参考风格更强调文件夹块和文件预览卡片网格。
- 侧边栏当前偏窄、偏工程化，参考风格更宽松，选中态是浅蓝胶囊。
- 设置页当前是侧向分组，参考风格更像大白面板内的横向 Tabs + 表单。
- 部分页面信息密度较高，需要在保持效率的同时统一卡片、按钮、输入框和状态色。

## 2. 建议技术栈

保留现有技术栈，不建议大换框架。

推荐：

- React 18：继续作为组件层。
- TypeScript：继续作为类型约束。
- Vite：继续作为构建和开发工具。
- lucide-react：继续作为主要图标库。
- CSS variables + 普通 CSS：继续作为主题和组件样式方案。

可选增强：

- TanStack Query：后续接口状态复杂后，用于统一 loading、error、cache、refetch。
- Framer Motion：只在需要更细腻的 Tab、侧栏折叠、卡片入场动画时引入。

不建议：

- 不建议引入 Ant Design、Element、MUI 等强风格组件库。它们会带来明显组件库气质，和参考图的轻量云盘风格不完全一致。
- 不建议把文件页做成复杂 dashboard。文件管理页应优先像工作台，而不是数据分析页面。

## 3. 设计目标

关键词：

- 蓝白灰
- 轻量
- 宽松
- 圆润
- 低阴影
- 浅灰块面
- 明确当前状态
- 工具型效率

一句话目标：

在浅灰画布上放置柔和的白色工作台，用 Cloudreve 蓝表达品牌、按钮和选中态；文件页更像网盘，知识库和后台页保持管理效率。

## 4. 主题 Token 建议

建议先替换或扩展 `cloud-frontend/src/styles.css` 中的全局变量。

```css
:root {
  --bg: #f5f5f5;
  --surface: #ffffff;
  --surface-soft: #f3f3f3;
  --surface-hover: #eeeeee;
  --text: #111827;
  --muted: #6b7280;
  --line: #dddddd;
  --line-strong: #cfd5dc;

  --primary: #1976d2;
  --primary-strong: #1565c0;
  --primary-soft: #d7eafe;
  --primary-softer: #edf6ff;

  --danger: #ef4444;
  --danger-soft: #fff1f1;
  --success: #16a34a;
  --success-soft: #ecfdf3;

  --radius-card: 14px;
  --radius-inner: 10px;
  --radius-pill: 999px;

  --card-pad: 20px;
  --card-gap: 18px;
  --shadow: none;
  --shadow-soft: 0 6px 18px rgba(15, 23, 42, 0.04);
}
```

使用原则：

- 大部分区域用边框和浅灰背景分层，不依赖强阴影。
- 主按钮、选中 Tab、选中导航使用蓝色。
- 普通图标默认灰色，只有当前项或关键操作使用蓝色。
- 页面背景使用浅灰，主内容面板使用白色。

## 5. 总体布局

建议统一为以下 App Shell：

```txt
AppShell
  Sidebar
    Brand
    NavItem[]
    SecondaryNav / StorageInfo

  Main
    Topbar
      PrimaryAction
      SearchBox
      IconButtonGroup
      UserAvatar

    PageSurface
      PageHeader / Breadcrumb / Tabs
      PageContent
```

桌面端：

- Sidebar 宽度建议 `280px` 左右。
- Main 区域内边距建议 `20-24px`。
- 主内容白色面板圆角 `14-16px`。
- 顶部工具栏和内容面板之间保持 `12-16px` 间距。

移动端：

- Sidebar 可改为顶部横向导航或抽屉。
- 文件卡片网格降为单列或双列。
- 工具栏按钮允许换行，但主操作按钮保持明显。

### 5.1 Knowledge Base 顶层入口

将 Knowledge Base 作为与网盘“我的文件”平级的一级入口是合理的。

理由：

- “我的文件”解决文件存放、浏览、上传、下载、预览等资产管理问题。
- “Knowledge Base”解决文档导入、结构化、索引、问答、检索质量分析等知识使用问题。
- 两者共享文件资产，但用户心智不同：一个是网盘，一个是知识工作台。
- 将知识库能力集中到一个一级入口下，可以避免“智能问答”“知识库运维”“空间 RAG”分散在多个入口里。

推荐一级导航：

```txt
App Sidebar
  我的文件
    图片
    视频
    音乐
    文档
    与我共享
    回收站
    我的分享
    连接与挂载
    后台任务
    离线下载
  Knowledge Base
  团队空间
  异步任务
  系统设置
```

Knowledge Base 内部结构：

```txt
Knowledge Base
│
├── 左侧导航 Sidebar
├── 顶部 Header
└── 主区域
      |
      ├── 知识库概览 Dashboard
      |
      ├── 文档管理 Documents
      |
      ├── 索引任务 Pipeline
      |
      ├── 智能问答 Chat
      |
      └── 检索分析 Analytics
```

设计原则：

- 全局 Sidebar 负责一级入口，“我的文件”和 “Knowledge Base” 平级。
- Knowledge Base 内部需要二级导航，但不要和全局 Sidebar 形成两个同权重的侧栏。
- 桌面端可以采用“全局 Sidebar + Knowledge Base 内部窄侧栏”的结构。
- 如果页面空间紧张，也可以将 Knowledge Base 二级导航放到顶部 Tabs 中。
- 移动端 Knowledge Base 二级导航应折叠为顶部下拉、分段控件或抽屉。

推荐桌面布局：

```txt
AppShell
  GlobalSidebar
    我的文件
    Knowledge Base
    团队空间
    异步任务
    系统设置

  Main
    KnowledgeHeader
      Title
      Space / KnowledgeBase Selector
      Global Search
      Actions

    KnowledgeWorkspace
      KnowledgeSubnav
        Dashboard
        Documents
        Pipeline
        Chat
        Analytics

      KnowledgeContent
```

模块职责：

- Dashboard：展示知识库总览，包括文档数、已索引数、失败任务、待审核、最近活动、质量概况。
- Documents：管理进入知识库的文档，包括导入、分类、标签、索引状态、画像状态、引用来源。
- Pipeline：展示索引、解析、向量化、画像生成等任务队列和执行历史。
- Chat：提供 ChatGPT 式知识库问答入口，是面向日常使用的主入口之一。
- Analytics：分析检索效果，包括召回片段、命中来源、低分查询、无答案问题、引用覆盖率。

路由建议：

```txt
/files
/knowledge
/knowledge/dashboard
/knowledge/documents
/knowledge/pipeline
/knowledge/chat
/knowledge/analytics
/spaces
/async
/settings
```

迁移建议：

- 当前 `ChatView` 后续应迁移到 `/knowledge/chat`。
- 当前 `KnowledgeOpsView` 可拆分为 Dashboard、Documents、Pipeline、Analytics 几个页面。
- 当前 `SpacesView` 中的 RAG 问答区域可以保留轻量入口，但完整体验应跳转到 Knowledge Base。
- 全局导航中的“智能问答”不再作为独立一级入口，而是归入 Knowledge Base。

### 5.2 Cloudreve 式全局 Sidebar

全局 Sidebar 应参考 Cloudreve 的左侧导航：品牌在顶部，导航项以图标 + 文本纵向排列，当前项使用浅蓝色胶囊高亮；一级入口可以展开，展开项缩进显示。

推荐结构：

```txt
Sidebar
  Brand
    Logo
    CloudName
    CollapseButton

  NavTree
    MyFilesEntry(active, expanded)
      Images
      Videos
      Music
      Documents
      SharedWithMe
      RecycleBin

    MyShares
    Mounts
    BackgroundTasks
    OfflineDownloads

    KnowledgeBaseEntry
    SpacesEntry
    AdminEntry

  StorageCard
    存储空间
    ProgressBar
    Used / Total
```

视觉要求：

- Sidebar 宽度建议 `280px` 左右，背景使用浅灰或接近 `#f5f5f5`。
- Brand 区顶部固定，Logo 与站点名左对齐，右侧可放折叠按钮。
- 一级导航高度约 `40px`，圆角 `16-18px` 或胶囊形态。
- 当前一级入口使用浅蓝底，例如 `--primary-soft`，图标和文字使用深色。
- 可展开项左侧使用小三角或 chevron 表示展开/收起。
- 二级项缩进 `28-36px`，图标使用灰色线性图标，文字保持正常权重。
- 二级项 hover 使用浅灰背景，不必全部使用蓝色。
- 分组之间留出更大垂直间距，例如文件域、知识库域、系统域之间。
- StorageCard 固定在 Sidebar 底部，使用白底、浅边框、圆角 `12-14px`。

“我的文件”展开内容：

```txt
我的文件
  图片
  视频
  音乐
  文档
  与我共享
  回收站
```

这些项属于文件域筛选或文件相关视图，不应该再和 Knowledge Base、系统设置等一级模块混排。

文件相关独立入口：

```txt
我的分享
连接与挂载
后台任务
离线下载
```

这些能力仍属于文件/传输/分享域，可以放在“我的文件”分组下方，但视觉上与展开的类型筛选保持区分。

Knowledge Base 入口：

- `Knowledge Base` 保持为全局一级入口，和“我的文件”同级。
- 不放在“我的文件”的展开内容中。
- `Knowledge Base` 入口是硬性保留项，任何 Sidebar 改造都不能删除、隐藏或降级为“我的文件”下的二级项。
- 进入后再显示 Knowledge Base 内部二级导航：Dashboard、Documents、Pipeline、Chat、Analytics。

管理入口：

- 管理员用户显示“管理面板”或“系统设置”。
- 普通用户隐藏管理员入口。
- 如果后续同时存在“后台任务”和“管理面板”，需要明确区分：后台任务是用户任务/传输任务，管理面板是系统管理员配置。

StorageCard：

```txt
存储空间
[progress bar]
7.1 GB / 10.0 GB
```

要求：

- 卡片位于 Sidebar 底部。
- 进度条使用蓝色进度和浅灰轨道。
- 文案简洁，不加入复杂统计。
- 后续可点击进入容量详情，但默认只作为状态展示。

## 6. 文件页布局

文件页应最接近参考图，是第一优先级。

推荐结构：

```txt
FilesPage
  Topbar
    New / Upload
    Search
    Refresh
    ViewMode
    Sort

  FilePanel
    BreadcrumbBar

    SectionTitle: 文件夹
    FolderGrid
      FolderTile

    SectionTitle: 文件
    FileGrid
      FileCard
```

FolderTile：

- 浅灰背景 `--surface-soft`
- 圆角 `12px`
- 高度 `60px`
- 横向排列：图标 + 名称
- hover 时背景略深，或边框变浅蓝

FileCard：

- 外层浅灰背景
- 内部白色预览区
- 顶部文件名和类型图标
- 中央放大文件类型图标
- hover 时轻微上移 `translateY(-1px)` 或边框变蓝灰

保留列表视图：

- 建议增加网格/列表切换。
- 默认使用网格视图，更贴近 Cloudreve。
- 列表视图保留给大量文件、批量选择、详细信息场景。

## 7. 设置页布局

设置页应接近参考图中的后台参数设置页。

推荐结构：

```txt
SettingsPage
  PageSurface
    H1: 参数设置
    HorizontalTabs
      站点信息
      文件系统
      存储策略
      邮件
      外观
      服务器

    FormSection
      FormField
      FormField
      Actions
```

设计要点：

- Tabs 放在面板内标题下方，横向排列。
- 激活 Tab 使用蓝色文字 + 蓝色下划线。
- 表单宽度控制在 `560-640px`，不要铺满整页。
- Label 加粗，说明文字使用灰色小字号。
- 输入框高度 `46-48px`，圆角 `10-12px`。
- 保存按钮放在表单底部，右侧或左侧均可，但要一致。

## 8. 团队空间页面布局

团队空间页面需要兼顾云盘感和管理感。

推荐结构：

```txt
SpacesPage
  LeftPanel
    SpaceList
    CreateSpaceMiniForm

  SpaceWorkspace
    SpaceHeader
    MetricTiles
    ContentGrid
      SpaceFilesCard
      KnowledgeSettingsCard
      RagDocumentsCard
      MembersCard
    RagConsole
```

设计要点：

- `SpaceList` 可以保留左侧局部面板，但视觉上和 Sidebar 区分。
- 指标卡使用浅灰底，不要强阴影。
- RAG 问答区域可以作为底部大卡片，类似工作台插件。
- 空间文件卡可向文件页的 FolderTile/FileRow 靠拢。

## 9. 知识库运维页面布局

知识库运维页不宜完全网格化，因为它需要扫描状态和处理异常。

推荐结构：

```txt
KnowledgeOpsPage
  Header
    SpaceSelect
    Refresh
    RebuildActions

  StatGrid
    DocumentCount
    IndexedCount
    NeedsReview
    AverageQuality

  Workbench
    FacetSidebar
      All
      Review
      Failed
      Categories
      Tags

    DocumentTable
      DocumentRow

  DetailDrawer
```

设计要点：

- 使用 Cloudreve 的蓝白灰和圆角，不强行做成文件卡片。
- 表格行要更轻，减少边框压迫感。
- 状态用 badge 或状态点，不只依赖颜色。
- Drawer 使用白底、浅边框、轻遮罩，保持工具型体验。

## 10. 知识库问答页面布局

知识库问答页应参考 ChatGPT 的对话式工作台，而不是后台卡片或普通表单页。

当前项目中对应页面是 `cloud-frontend/src/features/chat/ChatView.tsx`。它已有左侧范围选择、消息列表、输入框和 RAG 调用逻辑，但视觉目标应调整为更接近“会话应用”。

设计定位：

- 面向用户提问和检索知识，不面向管理员配置。
- 默认状态要像一个干净的 AI 对话入口。
- 运维信息、索引状态、质量评分等不要出现在主问答界面里。
- 知识库范围选择可以保留，但要轻量化，不能压过输入框。

推荐结构：

```txt
KnowledgeChatPage
  ChatSidebar
    Brand / PageTitle
    NewChatButton
    SearchChatButton
    KnowledgeLibraryEntry
    Project / SpaceEntry
    RecentChats

  ChatCanvas
    TopRightActions
      LoadingState / Refresh
      UserAvatar

    EmptyConversation
      GreetingTitle
      CenterComposer
        AddButton
        TextInput
        KnowledgeScopeSelect
        Voice / SendButton
      QuickActions
        查找资料
        摘写或编辑
        生成摘要

    MessageThread
      AssistantMessage
      UserMessage
      CitationChips
      ComposerDock
```

空会话首屏：

- 主区大面积留白。
- 居中显示一句简短提示，例如“今天想查什么资料？”或“要从知识库里找什么？”。
- 输入框居中，宽度建议 `720-780px`，高度 `54-60px`。
- 输入框是胶囊/大圆角形态，白底、浅边框、轻阴影。
- 输入框内左侧放 `+` 或附件按钮，右侧放知识库范围、语音/发送按钮。
- 输入框下方放 2-4 个轻量快捷按钮，如“查找资料”“总结文档”“提取要点”“对比版本”。

对话中状态：

- 消息区域最大宽度建议 `760-860px`，居中。
- 用户消息靠右或与 ChatGPT 类似使用窄气泡。
- 助手消息靠左或自然文本块展示，避免每条都做重卡片。
- 引用来源用小型 chips 展示在回答下方，例如文件名、片段号、页码。
- 输入框从首屏居中变为底部固定 dock，保持同样视觉语言。

左侧栏：

- 宽度建议 `260px` 左右。
- 采用浅灰背景，与当前 Cloudreve 风格 Sidebar 保持一致。
- 包含“新对话”“搜索对话”“知识库”“项目/空间”“最近”。
- 最近对话列表只显示标题，不显示复杂元信息。
- 当前会话或入口使用浅灰/浅蓝胶囊高亮。

知识库范围选择：

- 不建议使用大表单 label + select 占据主视觉。
- 可以放在输入框右侧的小下拉中，例如“全部知识库 / 当前空间 / 某个知识库”。
- 也可以在左侧栏提供“知识库”入口，主输入框只显示当前范围。
- 未选择知识库时，应给出轻提示，而不是大面积错误态。

### 10.1 知识库对话记忆

知识库问答需要具备“对话记忆”，让用户可以回到历史会话中继续追问，而不是每次进入页面都重新开始。

实现归属：

- 当前前端可以保留 `localStorage` 临时会话记忆，用于刷新恢复和单浏览器内切换历史会话。
- 会话搜索、重命名、删除、跨设备恢复、审计日志、长对话摘要、多知识库 scope 严格绑定等增强能力归入后端需求。
- 后续前端只负责对接后端会话 API，并在接口不可用时保持本地临时方案可用。

核心目标：

- 用户在 `Knowledge Base / Chat` 中创建的每个对话都应被保存。
- 点击左侧“最近”中的历史会话，可以恢复该会话的完整上下文。
- 用户继续追问时，应把当前会话历史作为上下文传给 RAG/LLM，而不是只发送最后一个问题。
- 对话记忆应与知识库范围绑定，历史会话需要记录当时选择的知识库、空间或多知识库 scope。
- 会话标题可以先由首个问题生成，后续支持用户重命名。
- 会话应支持新建、切换、搜索、重命名、删除。

推荐数据结构：

```txt
KnowledgeChatSession
  id
  title
  scope
    mode: single-space | multi-space | all
    spaceIds
  messages
    id
    role: user | assistant | system
    content
    citations
    createdAt
  createdAt
  updatedAt
```

前端行为：

- 新会话首屏显示居中输入框。
- 发送第一条消息后自动生成会话，并出现在左侧“最近”列表顶部。
- 切换会话时恢复消息流、引用 chips、知识库范围和输入框状态。
- 如果后端会话接口暂未完成，可以先用 `localStorage` 做临时持久化，但 UI 和代码结构要预留后端同步入口。
- 本地缓存只作为过渡方案，最终应以后端会话为准，保证跨设备、跨浏览器可恢复。

后端需求：

- 提供会话列表接口，支持分页、搜索、按更新时间排序。
- 提供会话详情接口，返回完整消息和引用来源。
- 提供创建会话、追加消息、更新标题、删除会话接口。
- RAG 查询接口需要支持传入当前会话历史或会话 id，由后端决定如何截断、摘要和注入上下文。
- 对话记忆应记录知识库 scope，避免用户在 A 知识库的上下文被误用于 B 知识库。
- 后续可增加会话摘要，解决长对话上下文过长的问题。

推荐视觉 token：

```css
--chat-sidebar-bg: #f7f7f7;
--chat-canvas-bg: #ffffff;
--chat-composer-bg: #ffffff;
--chat-composer-border: #dedede;
--chat-composer-shadow: 0 10px 30px rgba(15, 23, 42, 0.08);
--chat-chip-bg: #f3f3f3;
```

组件拆分建议：

```txt
features/chat/
  ChatView.tsx
  ChatSidebar.tsx
  ChatCanvas.tsx
  EmptyConversation.tsx
  ChatComposer.tsx
  MessageThread.tsx
  MessageBubble.tsx
  CitationChips.tsx
```

实现优先级：

1. 先调整布局：左侧会话栏 + 右侧全屏对话画布。
2. 再调整空状态：居中标题 + 居中输入框 + 快捷按钮。
3. 再调整消息流：居中最大宽度、回答引用 chips、底部 composer。
4. 补基础对话记忆：本地会话新建、保存、切换、恢复上下文。
5. 后端完成后再补搜索会话、重命名、删除、跨设备同步等增强能力。

验收标准：

- 用户进入“智能问答”时，第一感觉应接近 ChatGPT，而不是后台配置页。
- 空会话时主视觉焦点只有一句提示和输入框。
- 已有 RAG 问答能力仍可用，`api.queryRag` 调用逻辑不丢失。
- 选择知识库范围的能力保留，但变成轻量控件。
- 对话刷新后不会丢失，点击历史会话可以恢复完整消息和引用来源。
- 继续追问时能携带当前会话上下文，回答能理解前文指代。
- 移动端输入框固定在底部，左侧栏折叠为抽屉或顶部入口。

## 11. 动画规范

动画要轻、快、安静。

推荐：

- 按钮 hover：`background-color 160ms ease`
- 卡片 hover：`transform 160ms ease`, `border-color 160ms ease`
- Tab 切换：下划线或内容 `fade-in 160ms`
- Modal/Drawer：`opacity + translate`，时长 `180-220ms`
- loading：保留现有旋转或替换为 skeleton

避免：

- 大幅缩放
- 弹跳动画
- 强 3D
- 大面积渐变和装饰光斑
- 过多滚动动画

## 12. 组件拆分建议

当前 `DriveApp.tsx` 较大，后续改造时建议逐步拆分。

建议组件：

```txt
components/app-shell/
  AppShell.tsx
  Sidebar.tsx
  SidebarNavTree.tsx
  SidebarStorageCard.tsx
  Topbar.tsx
  PageSurface.tsx

components/ui/
  Button.tsx
  IconButton.tsx
  SearchBox.tsx
  Tabs.tsx
  Card.tsx
  EmptyState.tsx
  LoadingState.tsx

features/knowledge-base/
  KnowledgeBaseLayout.tsx
  KnowledgeBaseHeader.tsx
  KnowledgeBaseSubnav.tsx
  KnowledgeDashboard.tsx
  KnowledgeDocuments.tsx
  KnowledgePipeline.tsx
  KnowledgeChat.tsx
  KnowledgeAnalytics.tsx

features/files/
  FilesPage.tsx
  FileToolbar.tsx
  FolderGrid.tsx
  FolderTile.tsx
  FileGrid.tsx
  FileCard.tsx
  FileTable.tsx

features/settings/
  SettingsPanel.tsx
  SettingsTabs.tsx
  SettingField.tsx

features/chat/
  ChatView.tsx
  ChatSidebar.tsx
  ChatCanvas.tsx
  ChatComposer.tsx
  MessageThread.tsx
  CitationChips.tsx
```

拆分原则：

- 先不改变业务逻辑，只拆展示组件。
- 保持 API 调用和状态管理稳定。
- 一个阶段只改一个主要页面，避免视觉和逻辑同时大面积变动。

## 13. 分阶段改造建议

第一阶段：统一视觉基础

- 替换主题 token。
- 调整 Sidebar 宽度、品牌区、导航选中态。
- 将 Sidebar 改为 Cloudreve 式导航树：`我的文件` 可展开，文件类型和文件相关入口收纳在文件域下。
- 增加 Sidebar 底部 StorageCard，展示容量进度。
- 调整 Topbar、按钮、输入框、面板圆角。
- 保持现有页面结构不变。

第一点五阶段：统一信息架构

- 将 “Knowledge Base” 提升为与“我的文件”平级的一级入口。
- 将当前“智能问答”从一级入口迁入 `Knowledge Base / Chat`。
- 明确 Knowledge Base 内部二级导航：Dashboard、Documents、Pipeline、Chat、Analytics。
- 验证全局 Sidebar 中始终可见 `Knowledge Base` 一级入口，不能在改造中丢失。
- 保留旧入口跳转或重定向，避免用户已有路径失效。

第二阶段：文件页 Cloudreve 化

- 增加文件夹网格和文件卡片网格。
- 保留列表视图。
- 增加视图切换按钮。
- 统一空状态、加载态、错误态。

第三阶段：设置页后台化

- 设置页改为大白面板 + 横向 Tabs + 中宽表单。
- 统一表单字段、说明文字和按钮区域。

第四阶段：空间和知识库页面统一

- 空间页指标卡、局部面板和 RAG Console 统一圆角/色彩。
- 知识库相关页面统一到 Knowledge Base 工作台中。
- Documents、Pipeline、Analytics 保持表格和分析效率，但视觉 token、状态 badge、Drawer 统一。

第四点五阶段：知识库问答页 ChatGPT 化

- 调整 Knowledge Base / Chat 为左侧会话栏 + 右侧对话画布。
- 空会话首屏改为居中输入框。
- 输入框支持知识库范围选择和快捷动作。
- 消息回答下方增加引用 chips。

第五阶段：交互细节

- 加 hover、focus、active、reduced-motion。
- 检查 320px、768px、1024px、1440px 响应式。
- 用浏览器截图验证文件页和设置页。

## 14. 验收标准

视觉：

- 第一屏明显像现代云盘，而不是普通后台模板。
- 智能问答页明显像对话应用，而不是后台表单。
- Sidebar 明显接近 Cloudreve：Logo + 站点名、浅蓝胶囊选中态、可展开“我的文件”、底部存储空间卡片。
- Sidebar 始终保留 `Knowledge Base` 一级入口，且与“我的文件”平级。
- 页面主色为蓝白灰，青绿色不再作为品牌主色。
- Sidebar 选中态、Tab 选中态、按钮主色统一。
- 卡片圆角、边框、浅灰背景一致。

功能：

- 登录、文件浏览、上传、新建文件夹、预览、下载、删除仍可用。
- 设置页保存、刷新仍可用。
- 知识库问答页提问、回答、引用来源展示仍可用。
- 空间、知识库、异步任务页面不丢功能。

工程：

- `npm run build` 通过。
- 不引入重量级 UI 框架。
- 不进行无关后端改动。
- 大组件逐步拆分，但每步都保持可运行。

## 15. 关键文件

- `cloud-frontend/package.json`：确认前端技术栈。
- `cloud-frontend/src/styles.css`：主题 token 和全局组件样式。
- `cloud-frontend/src/App.tsx`：登录后进入主应用壳。
- `cloud-frontend/src/features/files/DriveApp.tsx`：当前主壳、文件页和导航逻辑。
- `cloud-frontend/src/features/chat/ChatView.tsx`：知识库问答页，后续应改造成 ChatGPT 式对话工作台。
- `cloud-frontend/src/features/settings/SettingsPanel.tsx`：系统设置页。
- `cloud-frontend/src/features/spaces/SpacesView.tsx`：团队空间和 RAG 问答页。
- `cloud-frontend/src/features/knowledge/KnowledgeOpsView.tsx`：知识库运维页。
- `cloud-frontend/src/features/knowledge-base/`：建议新增目录，用于承载 Knowledge Base 一级入口下的 Dashboard、Documents、Pipeline、Chat、Analytics。
