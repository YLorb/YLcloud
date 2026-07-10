
# RAG
  - embed/rerank 当前返回 offline-fallback:*，所以连通性通过，但按文档定义不算真实 embedding/rerank 质量验证。
  - 无关问题返回了正确 no-answer：无法从当前知识库回答。，但响应里仍带了 5 个 citations。
    这不完全符合 checklist 的“no unrelated recent chunks are used”。建议后续增加相似度/相关性阈值过滤：当最终判断 no-answer 时，不返回 citations，或在检索阶段过滤低相关 chunk。
  - RAG 检索 ”第一节 + 顺变电磁法.pdf“ 时，无法提取有效信息（所提问题：什么是顺变电磁法？）。推测问题在：1、LLM被设定为“严格回答”；2、Chuck切分逻辑存在严重问题。

# 后端

关于知识库：
- 检查权限部分是否已经实现；
- 对于知识库管理者&所有者，允许他们修改知识库的相关设置，包括Top-k等。前端要留出一个显式的页面供后的端修改
- **重点**：管理者&所有者可以设置温度，温度越高，回复越具有创造性；温度越低， LLM的回复更依赖于上下文。相关设置的调控需要被日志记录。

# 前端

智能问答页面：

1. 要求与Chatgpt/Deepseek等网页平台的样式一致；
2. 默认选择的知识库为自己的知识库；
3. 允许多知识库同时询问。
4. **重点修复内容**：对话内容会丢失。需要修改成点击曾经的会话，能够恢复对话内容。

Cloudreve 风格重构后的前后端未对齐点（2026-07-09）：

1. 左侧栏“存储空间”卡片已补用户级容量汇总接口：`GET /api/storage/quota`。当前后端仍使用默认 10GB 策略名 `default-10gb`，后续如果要支持管理员配置容量策略，需要新增 quota policy 表或接入已有用户套餐字段。
2. 左侧栏中的“与我共享”“我的分享”“连接与挂载”“离线下载”是 Cloudreve 风格所需的信息架构入口，但当前前端没有找到完整对应 API。现阶段只能作为占位说明或复用非常有限的分享能力，后续需要明确这些模块的后端模型和接口。
3. Knowledge Base / Chat 已补多知识库聚合问答入口：`POST /api/knowledge/rag/query`，支持一次最多 5 个 `spaceId`，并在结果、引用中返回 `spaceId` / `spaceName`。当前实现是逐个知识库调用现有单空间 RAG 后聚合答案，不是跨库统一召回后统一生成；如后续需要更自然的融合回答，需要新增跨空间 retriever/generator。
4. Knowledge Base / Chat 已补后端会话持久化基础接口：`/api/knowledge/chat/sessions`，支持会话列表、详情、创建、重命名、删除、追加消息。前端仍需要接入这些接口，才能实现跨设备恢复对话。
5. Knowledge Base / Analytics 已补基础检索分析接口：`/api/space/{spaceId}/rag/analytics/summary`、`queries`、`no-answer`、`config-logs`。当前查询日志没有保存召回分数明细、用户反馈、低分阈值判定，因此“低分查询”和“用户反馈分析”仍需扩展日志结构。
6. Knowledge Base 设置项后端已有 `GET/PUT /api/space/{spaceId}/rag/config`，Top-k、temperature 调整会写入配置修改日志；本轮新增了配置日志查询接口。前端仍需要在 Knowledge Base 设置页接入保存与日志展示。
7. `/spaces` 中仍保留了轻量 RAG Console，而完整知识库体验已迁移到 `Knowledge Base`。后续需要决定空间页的 RAG 区域是保留为快捷入口，还是彻底跳转到 `Knowledge Base / Chat`，避免同一能力出现两套交互。

后端补全记录（2026-07-10）：

- 新增 `GET /api/storage/quota`，用于 Cloudreve 风格侧边栏存储空间卡片。
- 新增知识库会话持久化表与接口，覆盖会话列表、详情、创建、重命名、删除、消息追加。
- 新增 `POST /api/knowledge/rag/query`，用于多知识库同时问答。
- 新增 RAG Analytics 查询日志、无答案问题、配置变更日志与摘要接口。
- 已通过 `mvn test`，共 29 个测试通过。

# 其他

一些.env变量我希望也能够在前段调整，前段提供相关调整页面，后段负责修改相关文件。
