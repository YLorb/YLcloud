---
title: Problems 收集箱
type: issue-inbox
status: maintained
updated: 2026-07-17
tags:
  - ylcloud
  - problems
  - inbox
  - triage
  - assistant
  - memory
  - context
  - rag
---

# Problems 收集箱

此页只用于快速记录新发现、尚未分析和尚未分类的问题。它不是待完成任务总览，也不保存已经完成的问题历史。

## 使用规则

每条问题至少记录：

- 发现时间与环境；
- 用户可见现象；
- 最小复现步骤；
- 预期结果与实际结果；
- 日志、请求 ID、任务 ID、文档 ID 或截图位置；
- 初步影响范围，但不要在证据不足时直接写根因。

完成分析后：

- 需要编码的工作移动到“待完成任务”；
- 已开发只差验证的工作移动到“待验收任务”；
- 已开始处理的工作移动到“进行中任务”；
- 已修复并验证的工作移动到“已完成任务”；
- 具体设计与解决方案写入对应功能文档，不在本页长期堆积。

## 未分类问题

### AI Assistant 缺少长期、短期与情景三层对话记忆

- 发现时间：2026-07-17。
- 类型：新增功能需求；已完成现状审查和方案设计，待确认后进入开发任务。
- 用户需求：对话 context 必须由服务端保存和恢复，并明确分为长期记忆、短期记忆和情景记忆。长期记忆复用现有 RAG 的 embedding、Qdrant、融合、Rerank 与一致性能力；短期记忆负责当前上下文；情景记忆按对话框/会话保存完整交互过程。
- 当前能力：`knowledge_chat_session` 与 `knowledge_chat_message` 已保存会话、消息、任务状态、引用和可恢复问答，具备情景记忆的原始事件基础。`AssistantPage.tsx` 目前在浏览器截取最后 10 条成功消息写入 `KnowledgeChatQueryCreateDTO.history`；`KnowledgeChatQueryService` 将这段客户端 history 原样持久化并传给 RAG。后端没有按 token 预算重建上下文，也没有长期记忆实体、记忆向量、用户级检索入口、冲突合并和删除补偿。
- 核心问题：客户端 history 可缺失、被裁剪或被篡改，不能作为权威短期记忆；完整消息历史不等于可直接送入模型的上下文；如果把用户记忆直接写入现有 Space 文档向量集合，会污染知识库证据、破坏 Space 权限边界，并存在跨用户召回风险。

#### 三层记忆定义

| 层级 | 权威数据 | 生命周期 | 进入模型的方式 | 主要用途 |
|---|---|---|---|---|
| 短期记忆 Working Memory | 服务端从当前会话最近成功消息和滚动摘要构建的 context snapshot | 当前问答与活跃会话 | 按 token 预算保留最近轮次，不做全量向量化 | 代词消解、连续追问、保持当前任务状态 |
| 情景记忆 Episodic Memory | 当前 `session/message` 事件流，加会话摘要和分段 episode | 与对话框/会话一致 | 当前会话先使用滚动摘要，再按需展开原始消息 | 恢复某次对话的目标、决策、待办、未解决问题和引用 |
| 长期记忆 Long-term Semantic Memory | 从已完成对话中提取、去重并可由用户管理的稳定事实/偏好/约束 | 跨会话，直到过期、被覆盖或删除 | 使用现有 embedding 和 RAG 检索链召回，再作为“用户记忆”独立注入 | 跨会话记住用户偏好、长期项目背景和已确认决策 |

“情景记忆”按一个会话线程定义一个 episode，不等同于前端 Dialog 弹窗组件。长会话可按主题变化或固定消息窗口拆成多个 episode，但原始消息顺序仍以数据库事件流为准。

#### 推荐架构

1. 新增 `ConversationContextService`，由后端根据 `sessionId/userId` 构建上下文。内部 Assistant 提交接口只接收问题、知识库范围和幂等键，不再信任前端上传的 history。短期上下文排除 `QUEUED/RUNNING/FAILED` 占位消息，使用 tokenizer 计算预算，而不是固定“10 条”；必须保留当前问题和最近有效轮次，较旧内容压缩进滚动摘要。
2. 为每次助手任务持久化不可变的 `context_snapshot_json/context_hash/context_version`，至少记录所用消息 ID、episode ID、长期记忆 ID、知识库 chunk ID、各层 token 数和构建策略。快照用于重试复现、质量分析和审计，不把密码、Token 或完整敏感字段写入日志。
3. 扩展情景记忆：会话保存 `rolling_summary`、`summary_upto_message_id`、`summary_version`；消息增加会话内单调 `sequence_no`。可增加 `knowledge_chat_episode`，记录消息起止范围、主题、摘要、决策、待办、未解决问题和版本。摘要失败不能阻塞原始消息持久化，恢复时始终以原始事件为事实源。
4. 新增长期记忆主表 `user_memory_item`，字段至少包括：`user_id`、来源 session/episode/message 范围、memory type、正文、标准化 key、内容 hash、置信度、用户确认状态、有效期、版本、状态、embedding 状态、Qdrant point ID、创建/更新时间。记忆状态采用 `CANDIDATE -> INDEXING -> ACTIVE`，并支持 `FAILED_RETRYABLE/SUPERSEDED/EXPIRED/DELETE_PENDING/DELETED`。
5. 长期记忆不直接复用 Space 文档记录和 `file_rag_chunk`，而是复用现有 `RagModelClient/EmbeddingModel`、Qdrant 适配、候选融合、Rerank、阈值过滤和对账机制。使用独立 memory collection，或至少强制 payload 包含 `corpusType=user_memory`、`userId`、`memoryId`、`status`；推荐独立 collection，避免用户记忆参与普通知识库引用与统计。
6. 长期记忆写入在回答成功后异步执行：从新增的用户表达和已确认决策中提取候选，拒绝保存助手自行推测的事实；按 `userId + normalizedKey + sourceHash` 幂等，先查重，再执行新增、合并、覆盖或冲突保留。新旧记忆冲突时不静默覆盖，旧记录转为 `SUPERSEDED` 并保留来源链；低置信度和敏感候选不激活。
7. 长期记忆召回链路为 `当前问题/Query Rewrite -> 用户级向量召回 + 可选关键词召回 -> 融合去重 -> Rerank -> scoreThreshold -> Top-K`。任何查询必须在 Qdrant 侧先按 `userId + ACTIVE` 过滤，禁止召回后再做用户过滤。记忆候选与知识库文档候选分别评分和预算，不允许用户记忆伪装成文档 citation。
8. Prompt/context 组装顺序明确为：系统规则与安全约束、当前问题、授权知识库证据、短期最近轮次、当前 episode 摘要、长期用户记忆。记忆只能作为数据，不能覆盖系统指令；长期记忆应显示来源和更新时间，知识事实仍以有引用的 RAG 文档为准。

#### 一致性、隐私与生命周期

- MySQL 是记忆权威源，Qdrant 是可重建派生索引。数据库事务只提交 memory/outbox 的待索引状态；提交后执行 embedding 和 upsert，严格验证 point ID、数量、维度与 payload，再 CAS 激活。失败进入可重试状态，不能出现“数据库 ACTIVE 但向量不存在”。
- 删除或编辑会话时同步失效短期快照和 episode；默认级联删除由该会话派生的长期记忆并验证 Qdrant 清理完成。只有用户显式“固定”的记忆可以在二次确认后脱离来源会话保留。
- 长期记忆、短期快照和情景记录均按 `userId` 隔离。共享 Space 成员不能看到彼此的个人记忆；管理员管理配额和开关，但普通管理接口不返回记忆正文。
- 用户需要“记忆管理”页面：总开关、分类筛选、查看来源、编辑、固定、忘记单条、清空全部和导出。短期/情景记忆作为对话连续性的基础默认开启；长期记忆默认开启但首次使用明确提示，可随时关闭，关闭后停止写入和召回，既有记忆由用户选择保留或清空。
- 默认不提取密码、Token、密钥、验证码、身份证件、支付信息和医疗等高敏内容；错误摘要与观测日志只记录 memory ID、阶段、耗时和计数。

#### 接口与代码影响

- 调整 `AssistantPage.tsx`、`KnowledgeChatQueryCreateDTO` 和 `KnowledgeChatQueryService`：前端不再组装权威 history，后端在 claim 任务后按数据库状态构建 context snapshot。
- 扩展 `KnowledgeChatSessionService/Mapper` 与消息模型，增加 sequence、滚动摘要、episode 和上下文快照读取。
- 新增 `ConversationContextService`、`ConversationEpisodeService`、`UserMemoryService`、memory mapper/entity/VO、异步索引执行器和启动/定时对账。
- 扩展 Qdrant 层为 memory 提供严格的 user filter、upsert/delete/count/verify 接口；不要把 `spaceId=0` 当作个人记忆的隔离方案。
- 增加 `/api/assistant/memories` 的查询、编辑、固定、删除、清空和开关接口；问答结果可以返回本次使用的 memory 摘要 ID，但不得把它混入知识库 citations。

#### 分阶段实施

- P0：服务端权威短期上下文、消息 sequence、token 预算、context snapshot、会话滚动摘要和删除语义。完成后刷新/换设备/重试必须得到同一上下文输入。
- P1：长期记忆候选提取、独立数据模型、现有 embedding 复用、用户级 Qdrant 检索、冲突/过期/删除状态机和跨存储补偿。
- P2：记忆管理 UI、episode 导航、用户反馈、评测集、监控与成本优化。

#### 验收标准

- 连续追问、代词指代和跨设备恢复不再依赖浏览器 history；超长会话始终满足 token 上限，摘要前后的关键事实不丢失。
- 新会话能召回已确认长期偏好，不能召回其他用户记忆；关闭长期记忆后写入和召回均停止。
- 知识库文档 citation 与用户记忆来源在响应和 UI 中严格分离；无文档证据时不能因“记忆”生成虚假的文档引用。
- 删除会话、删除单条记忆、清空记忆、并发更新、重复任务、embedding 失败、Qdrant 超时和进程退出均有故障注入，最终 MySQL/Qdrant 一致。
- 建立 20～100 条快速集和 500+ 条正式集，分别评估短期指代正确率、episode 摘要事实保持率、长期记忆 Recall@5/Recall@10、MRR、错误记忆率、矛盾率、跨用户泄漏率、上下文 token、P95 延迟和成本；跨用户泄漏必须为 0，且不能只用最终回答观感代替检索分阶段评估。

## 问题模板

### 标题

- 发现时间：
- 环境与版本：
- 现象：
- 复现步骤：
- 预期：
- 实际：
- 证据：
- 影响：
- 初步判断：
