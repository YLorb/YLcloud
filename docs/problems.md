
# P1：版本兼容（待完成）

- [ ] **P1 / 待完成**：统一 Qdrant Java client `1.17.0` 与 server `1.15.4` 的版本并回归。当前继续使用 server `1.15.4`；升级到 `1.17.x` 时必须先备份数据，并按照 Qdrant 官方要求经过 `1.16.x` 中间版本迁移，禁止直接跨版本打开原数据卷。
- [ ] **P1 / 待完成**：升级 Flyway 或将 MySQL 固定到已验证版本，消除 Flyway `10.10.0` 对 MySQL `8.3` 的支持警告，并完成空库迁移、存量库迁移、重启幂等和回滚验证。

# 文件与存储

## P0：大文件分片上传故障注入 E2E（已完成，2026-07-16）

- [x] 缺片合并：只上传部分分片时必须拒绝合并，任务保持可续传，禁止创建最终对象和文件元数据。
- [x] 错误摘要：合并对象以流式单次读取复核 MD5、SHA1 和规范 SHA256；不匹配时回滚数据库并清理最终对象全部版本。
- [x] 并发合并：同一 `uploadId` 并发请求只允许一个合并提交结果，其他请求只能返回同一结果或 409，禁止重复 `file_info/user_file`。
- [x] 断电/进程退出：首片提交后强制终止应用容器，重启后必须保留断点并完成后续上传和合并。
- [x] 元数据回滚：MinIO compose 成功后注入 `file_info` 唯一键冲突，验证上传任务状态与 `user_file` 原子回滚；移除冲突后复用已合并对象重试成功。
- [x] 新增 `scripts/multipart-fault-injection-e2e.ps1`、JSON 报告和严格的验收数据清理；未加入生产故障开关。
- [x] 真实 Compose 验收通过，报告：`outputs/multipart-fault-injection/multipart-fi-1784131822-f7d965.json`。结果：缺片 `1,1,0,0`；错误摘要 `1,0,0/最终对象版本 0`；8 路并发 `1 success + 7 conflict/2,1,1`；进程退出恢复 `2,1,1`；元数据回滚 `1,1,0 -> 2,1,1`；清理无错误。

# RAG

## P0：RAG 检索召回不足与 topK 配置（2026-08-20，待修复）

### 问题描述

课程 RAG 回归测试（19 题）暴露检索链路的系统性缺陷：

| 题号 | 问题 | 失败类型 | 证据 |
|---|---|---|---|
| Q02 | 相对年代通常用哪些方法确定？ | 检索覆盖不足 | 4 chunks 检索到，但"构造地质学方法"所在 chunk 未进入 top-4 |
| Q05 | 相对年代方法有什么局限？ | 测试集标注问题 | 正则 `无.*化石\|没有.*化石` 无法匹配原文"尚未发现明显化石" |
| Q10 | 内力地质作用的主要类型有哪些？ | 源文档遗漏 | 检索到的 chunks 只包含 3 种类型，测试集期望 4 种 |
| Q11 | Linux 大作业使用什么虚拟化软件？ | 检索完全失败 | `retrievedChunkIds=[815,780,821,806]` 但 `hitChunkIds=[], contexts=[], citations=[]`，全部被过滤 |
| Q15 | 对比地质作用和 Linux 软件安装 | 跨文档检索失败 | `retrievedChunkIds=[2420,2421,2375,2364]` 但全部被过滤，无法覆盖两个文档 |

### 根因分析

1. **topK 过小**：当前 topK 默认值过小（约 4），导致高相关 chunk 被截断
2. **相邻 chunk 未召回**：切片边界处的语义关联被切断，相关上下文分散在相邻 chunk 中
3. **过滤阈值过严**：rerank 后的 scoreThreshold 导致有效 chunk 被丢弃（Q11、Q15）
4. **跨文档查询改写不足**：单次查询难以同时覆盖多个文档

### 解决方案（最高优先级）

**召回步骤修改**：在召回但未精排前，再召回所有相关 Chunk 的左右相邻 Chunk，并进行 ReRank；最终送入 Context 的 Chunk 数量（topK）取 topK=10 并允许用户配置。

具体实现：
1. 向量检索返回 topK 个候选 chunk 后，为每个候选 chunk 获取其左右相邻 chunk（同一文档内）
2. 合并去重后进行 ReRank 精排
3. 最终取 topK=10（可配置）送入生成器 context
4. 确保跨文档查询时，每个文档至少有 chunk 进入最终 context

### 验收标准

- [ ] Q02、Q11、Q15 在回归测试中通过
- [ ] topK 可通过 API 配置，默认值为 10
- [ ] 相邻 chunk 召回逻辑有单元测试覆盖
- [ ] 全量后端测试无回归

---

## P0：RAG 索引与知识画像状态解耦（已完成，2026-07-15）

### 已确认问题

- [x] `model-service` 已按模型类型选择 `FlagModel` / `BGEM3FlagModel`，并锁定 `FlagEmbedding`、`transformers`、`tokenizers` 与 `torch` 版本；真实 `/embed` 返回 512 维向量。
- [x] RAG 编排已拒绝空正文、metadata fallback、空切片和空白切片，禁止写入 `SUCCESS/chunkCount=0`。
- [x] embedding/Qdrant 失败时会禁用 document ref 并严格清理该文件向量；物理 chunk 仅作为可重试缓存，不会在无 active ref 时参与检索。
- [x] 空间级 `PROFILE_SPACE` 零符合条件文档改为 `SKIPPED/NO_ELIGIBLE_DOCUMENTS`，全部失败改为 `FAILED`，不再产生新 `SUCCESS/0/0`。
- [x] 前端已把 RAG 索引构建与知识画像任务分区展示。

### 目标处理链路与成功条件

```text
加入知识库
  -> 创建异步 RAG 索引任务并返回 ACCEPTED + taskId
  -> 文档解析
  -> 非空切分
  -> embedding
  -> 向量索引写入与数量校验
  -> RAG_READY
  -> 检查 knowledgeProfileEnabled
       -> false: PROFILE_SKIPPED_DISABLED，不再执行画像步骤
       -> true: 执行知识画像任务并独立汇总结果
```

“加入知识库成功”只表示文件记录和异步任务创建成功。只有同时满足以下条件，文档才能标记为 `RAG_READY`：

1. 解析结果有效且正文非空；
2. 有效 chunk 数大于 0，且 chunk 内容非空；
3. embedding 返回数量与待索引 chunk 数一致，向量维度与当前 collection 一致；
4. Qdrant 写入成功并完成必要的数量/引用校验；
5. MySQL 中的文档、chunk、ref 与 Qdrant payload 状态一致。

知识画像是 RAG 完成后的可选增强能力。画像失败不得把已经可检索的 RAG 文档改回失败，也不得删除已经成功建立的基础索引。

### 新需求：知识画像开关与完成通知

- [x] V21 为每个知识库增加 `knowledgeProfileEnabled`，数据库默认值为 `true`，已有知识库迁移后统一开启。
- [x] `GET/PUT /api/space/{spaceId}/rag/config` 已返回并保存该字段；更新沿用 `requireAdmin` 与配置变更日志，记录操作人、前后快照和时间。
- [x] Knowledge Base 设置页已增加显式开关及关闭影响说明。
- [x] 每次 RAG 完成后读取数据库最新值；关闭时只持久化终态跳过记录 `PROFILE_SKIPPED_DISABLED`，不执行画像、不创建画像版本或画像事件。
- [x] 关闭不删除历史画像；重新开启只影响新完成或显式重建任务。
- [x] 画像批次持久化 `total/success/failed`，前端轮询终态并显示 `知识画像完成：成功 X 个文档、失败 Y 个文档`。
- [x] 关闭与零符合条件文档均显示明确跳过原因，不再显示伪造的 `SUCCESS/0/0`。
- [x] 空间重建只提交一个 `PROFILE_SPACE` 批次；自动批次具备 active 幂等保护，通知从服务端批次记录恢复，不读取历史累计值。

### 验收标准

- [x] 空正文或零 chunk 文档进入 `RAG_FAILED`，不允许 `RAG_READY/chunkCount=0`。
- [x] embedding 数量、维度、有限值与 Qdrant 精确 payload 校验均为成功硬门槛，失败清理 ref/vector 后可重试。
- [x] 知识画像关闭不影响基础 RAG，且不会执行画像或新增画像版本/事件。
- [x] 只有 `RAG_READY` 文档进入画像阶段，并展示本批次成功/失败数。
- [x] 零符合条件文档为 `SKIPPED/NO_ELIGIBLE_DOCUMENTS`。
- [x] 画像失败不反向修改 RAG 状态，前端分别展示两类状态。
- [x] 后端 84 项测试、模型服务 3 项测试和前端生产构建通过；真实容器重试 5/5 文档成功，609 个 chunk 与 609 个 active ref 对账一致。

### 2026-07-15 运行态验收证据

- `model-service /ready`：`dense / BAAI/bge-small-zh-v1.5 / dimension=512 / offlineFallback=false`。
- `model-service /embed`：真实请求返回 `count=1, dimension=512`，日志中批量请求均为 HTTP 200，未再出现 `list.keys` 异常。
- Flyway V21 已成功应用，`knowledge_profile_enabled tinyint not null default 1`。
- space 40 原 5 个失败文档重试后全部 `SUCCESS`，文档 `chunk_count` 合计 609，active ref 合计 609。
- 画像批次持久化终态示例：`SUCCESS total=5 success=5 failed=0`。

## P0：RAG 跨存储事务一致性（已完成，2026-07-15）

- [x] V22 为 `space_rag_document` 增加 `vector_state` 状态机：`CLEAN -> BUILDING -> ACTIVE`，失败进入 `CLEANUP_PENDING -> CLEANING -> CLEAN`。
- [x] MySQL 中“启用 chunk ref + 文档进入 SUCCESS/ACTIVE”由同一个本地事务提交；文档删除、状态变化或并发任务会通过 CAS 拒绝过期提交。
- [x] Qdrant 每次文件写入和删除后执行精确 point count 校验；只有数量与有效 chunk 完全一致才能提交数据库成功状态。
- [x] 检索 SQL 强制要求 document `SUCCESS/ACTIVE`、ref/chunk/file 均启用，失败或清理中的历史数据不能进入 Vector、BM25、Keyword、Metadata 或 Citation 链路。
- [x] 启动及定时对账逐文档比较 active ref、`chunk_count` 和 Qdrant point count；异常文档先隔离，再通过持久化状态继续幂等补偿。
- [x] RAG 超时任务不再无条件覆盖其他任务结果，只能将仍处于 `BUILDING` 的文档转为失败。
- [x] 知识画像活动任务增加数据库唯一约束、`PENDING -> RUNNING` CAS 和超时回收；画像数据事务仍与基础 RAG 成功状态解耦。
- [x] 修复存量数据：无效 active ref `66 -> 0`、孤儿向量 `4 -> 0`、缺失向量的假成功文档自动隔离。
- [x] 最终全库对账：数据库有效引用 `617`、Qdrant point `617`、逐文档不一致 `0`、未完成向量状态 `0`、活动 RAG/画像任务 `0`。
- [x] 后端 100 项测试全部通过；P0 真实端到端验收通过，空间文件删除前后 Qdrant `2 -> 0`，数据库 `file_info/active ref/active chunk = 0/0/0`。

  - embed/rerank 当前返回 offline-fallback:*，所以连通性通过，但按文档定义不算真实 embedding/rerank 质量验证。
  - [x] 无关问题的 no-answer 结果改用显式语义标志；响应清空 citations、contexts、hitChunkIds 和查询日志命中 ID，并由自动化测试覆盖。模型不可用但已有检索依据时仍保留引用。
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
4. Knowledge Base / Chat 已补后端会话持久化接口：`/api/knowledge/chat/sessions`，支持会话列表、详情、创建、重命名、删除、追加消息和查询范围更新；前端已接入核心会话流程，可跨设备恢复对话内容与知识库范围。
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

## 前后端补齐记录（2026-07-10）

本轮已完成：

- 前端侧栏已接入 `GET /api/storage/quota`，存储空间不再按当前目录文件大小估算。
- Knowledge Base 智能问答已接入服务端会话列表、详情、创建、删除和消息追加接口；历史会话可跨设备恢复，旧本地会话保留兼容迁移能力。
- 智能问答支持同时选择最多 5 个知识库，并接入 `POST /api/knowledge/rag/query`；引用中展示来源知识库。
- 新增会话知识库范围更新接口 `PUT /api/knowledge/chat/sessions/{sessionId}/scope`，恢复会话时同步恢复查询范围。
- 检索分析页已接入摘要、最近查询、无答案问题和配置变更日志接口。
- 后端判定为“无法从当前知识库回答”时，不再返回无关 chunk、context 和 citation，同时查询日志不再把这些 chunk 计为有效引用。

仍需后续完成：

- 当前跨知识库问答仍是逐库生成后聚合，不是统一跨库召回、统一重排和单次融合生成。
- 查询日志尚未保存召回分数明细、用户反馈和回答质量评价，因此低分查询、反馈分析仍无法实现。
- 默认 10GB 配额仍是固定策略，尚无管理员可配置的容量策略或用户套餐模型。
- “与我共享”“我的分享”“连接与挂载”“离线下载”仍缺少完整后端模型和 API。
- RAG 对复杂 PDF 的解析、分块和召回质量仍需专项调优；真实 embedding/rerank 服务也需要在目标环境继续验证。
- 前端依赖审计当前报告 1 个 moderate、1 个 high 风险项，需要单独评估升级影响后处理，不应直接执行破坏性版本升级。

## 管理员应用设置（2026-07-10）

本轮已完成：

- 管理员设置页使用规范路径 `/admin/setting`，旧 `/settings` 会自动跳转；非管理员访问 `/admin/*` 会返回文件页，后端接口仍执行独立管理员权限校验。
- 页面按“站点与访问、文件与分享、AI 与知识库”分域展示，包含运行状态、未保存计数、分组重置、敏感值掩码与显隐、URL/数字校验、离开页面提醒。
- 保存时只提交发生变化的可编辑项，避免只读项和空白密钥被误覆盖；保存成功后会刷新公开站点信息。
- 管理接口返回配置创建时间与更新时间，页面可显示当前分组最近更新时间。
- `site.allowRegister` 已用于注册入口控制；`upload.maxFileSize` 已接入普通上传、分片上传、空间上传和文件版本上传；`llm.enabled` 已接入 RAG 问答总开关；`rag.modelServiceBaseUrl` 由模型服务客户端动态读取。
- 已完成桌面与 390px 窄屏浏览器检查，未发现布局溢出，浏览器控制台无错误或警告。

仍需后续完成：

- `llm.provider`、`llm.baseUrl`、`llm.apiKey`、`llm.model` 当前保存在主应用数据库，但真实外部 LLM 调用由独立模型服务负责；要让这些配置即时生效，需要为模型服务增加安全的动态配置同步接口。
- `rag.parserServiceBaseUrl` 与 Qdrant 连接参数仍有部分由启动配置管理，不能在运行中安全重建客户端；后续应采用配置中心或受控重载机制，而不是直接由前端修改 `.env` 文件。
- 管理设置目前只记录最新修改时间，尚未建立包含操作人、变更前后值和回滚能力的全局配置审计日志。

## 代码审查修复（2026-07-10）

- 管理设置已增加服务端最终校验：必填项、HTTP/HTTPS URL、布尔值和数字类型均不能仅依赖前端校验；布尔值会统一保存为 `true` / `false`。
- 未保存设置的离开保护已提升到应用导航层，覆盖侧栏跳转、浏览器前进后退、刷新关闭和退出登录。
- 检索分析入口按当前知识库角色显示，仅 `OWNER` / `ADMIN` 可见；普通成员直接访问分析路径会返回知识库概览。
- 会话知识库范围保存增加互斥状态；保存期间禁止再次切换范围、切换会话和发送问题，避免异步请求覆盖。
- LLM 供应商选择器会保留后端返回的未知当前值，不再因前端固定选项导致显示为空。
- 管理设置服务测试扩展到 6 项，覆盖非法 URL、零上传限制和布尔值规范化；项目后端测试总数为 35 项。

## AI Assistant 页面重构（2026-07-10）

- 智能问答已从 `KnowledgeBaseView` 拆分为独立一级工作台，规范路径为 `/assistant/chat`；旧 `/chat`、`/knowledge/chat` 会兼容跳转。
- 页面采用会话栏、对话区和 composer 三段式布局；Knowledge Base 回归文档、索引任务和检索分析管理。
- 会话栏支持搜索、按今天/昨天/最近 7 天/更早分组、重命名和删除；点击服务端会话后按需加载完整消息与知识库范围。
- composer 使用多行输入，支持 Enter 发送、Shift + Enter 换行，并在输入区内选择最多 5 个知识库；新对话默认选择当前用户拥有的知识库。
- RAG 引用改为可展开来源列表，显示知识库、文件名、内容摘要和可用的原文链接。
- 会话详情请求增加竞态保护；AI 回答已生成但消息写入失败时保留本地副本，并在后续提问前尝试再次同步。
- 窄屏下会话栏改为抽屉，移动端隐藏完整应用侧栏，同时保留会话入口和返回文件入口。
- 前端生产构建已通过；受本机 Playwright 使用额度限制，本轮未完成带模拟数据的浏览器动态截图验收。
