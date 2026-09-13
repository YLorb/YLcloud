# YL Cloud Design System v0.2

> 本文件是前端视觉与交互的权威规范。页面补充规则位于 `pages/`；补充规则不得改变本文件的 Token 语义和核心布局原则。可运行组件书地址：`/component-book`。

## 1. 产品方向

- 面向个人用户及中小团队的 AI 知识库管理 SaaS。
- 高信息密度、安静、清晰、工具化；不是营销 Landing Page。
- 文件、知识库、索引任务和 AI 问答共用一套工作台骨架。
- 禁止大面积渐变、过度圆角、大面积卡片、装饰性阴影和布局位移式 Hover。

## 2. Token 架构

代码必须按 `Primitive → Semantic → Component → Page` 四层消费，页面不得直接写 Hex。

- Primitive：`src/styles/tokens/primitives.css`
- Semantic：`src/styles/tokens/semantic.css`
- Component：`src/styles/tokens/components.css`
- 统一入口：`src/styles/tokens/index.css`

品牌主色为 `#1976D2`；AI 辅助色为 `#9C27B0`。紫色只用于 AI 入口和 AI 特征，不承担普通导航选中态。Light/Dark 必须通过语义 Token 完整映射。

## 3. 布局

- Header 高度：60px。
- Sidebar：展开 256px，收起 64px。
- 页面间距：24px；大屏 32px；移动端 16px。
- AI 浮层入口固定于右下角。普通页面的 AI 面板默认 400px，可扩展至 440px；AI 问答路由占满主内容区。
- Dialog：小 420px、中 540px、大 800px。

### Sidebar

- 始终优先使用单 Sidebar，不在功能页面内再嵌套第二列 Sidebar。
- 一级为主功能，二级为当前功能导航，最多三级。
- 分组靠垂直留白区分，不使用 Card、明显边框或大面积 Divider。
- Item 默认无背景；Hover 使用轻背景；Selected 使用中性背景。
- 图标统一 Lucide 单色；仅状态图标允许 Accent 色。
- 允许 Recent、置顶等动态区域；AI 页面中“新建问答”在上、会话历史在下。

## 4. 组件规格

### Buttons

- 高度：32 / 40 / 48px；圆角 6px；字号 14px；字重 500。
- Primary 使用主色实底；Secondary 使用 Surface；Ghost 透明；Danger 只用于破坏性操作。
- Hover 不位移、不缩放，默认无阴影。

### Inputs

- 高度 40px；圆角 6px；1px Border。
- Focus 使用主色边框和三像素柔和 Ring；错误态必须同时提供文本或图标信号。

### Tabs

- 使用下划线选中态，不使用胶囊或分段卡片式 Tabs。
- 高度 36px，Indicator 2px。

### Tables

- 优先于 Card Grid；表头 44px，普通行不低于 56px，详情行 64px。
- 行选中使用中性背景；仅可执行主动作使用品牌色。
- 批量操作顺序：下载、移动、复制、添加到知识库、删除。

### Status

- 文件状态固定为：等待处理、处理中、可检索、失败。
- 成功绿、信息蓝、警告琥珀、失败红；颜色不是唯一识别方式。
- 普通用户查看任务时显示步骤进度与可理解字段；所有者和管理员可额外查看 ID、节点、请求 ID、重试次数及原始记录。

## 5. 页面行为

- 文件系统只展示当前用户自己上传的文件。
- 知识库像文件一样在列表或紧凑网格中展示；双击后进入详情，不在页面头部重复铺陈上下文信息。
- 首次创建时可显示引导；之后 Empty State 只给出直接下一步。
- AI 问答最多选择 5 个知识库。普通页面由右下角圆形按钮开关 AI Drawer；AI 路由隐藏按钮并全宽展示。
- Header 不显示处理中任务，任务入口与状态归入 Sidebar 的“任务”。

## 6. 状态与可访问性

- Empty：说明当前为空并提供一个明确动作。
- Loading：使用稳定占位，避免布局跳动。
- Error：说明失败原因，提供重试；不暴露内部异常或敏感标识。
- Success：使用短 Toast 或行内确认，不中断工作流。
- 所有交互必须支持键盘、可见 Focus、语义标签与 `prefers-reduced-motion`。
- 文本对比度不低于 WCAG AA；点击目标优先不低于 40px。

## 7. 交付检查

- [ ] 页面只消费 Semantic/Component Token，无页面级 Hex。
- [ ] 单 Sidebar，层级不超过三级。
- [ ] 无大面积 Card、渐变或装饰阴影。
- [ ] Light/Dark、375/768/1024/1440px 已验证。
- [ ] Empty/Loading/Error/Success 状态齐全。
- [ ] 键盘焦点、Reduced Motion、文本对比度已检查。
- [ ] `npm run test` 与 `npm run build` 通过。
