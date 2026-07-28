---
id: TASK-20260725-019
type: implementation-task
status: completed
priority: P0
created: 2026-07-25
updated: 2026-07-27
owner: codex
version: 2
tags: [mq-v4, chat, workflow]
---

# mq-v4：知识 Chat 与 Workflow 任务迁移

## 目标

将知识 Chat 异步查询和 Workflow 消息生命周期的重型执行迁入 chat 队列，保持用户可见消息状态、顺序和失败详情一致。

## 依赖与输入

- TASK-20260725-018 已通过观察。
- TASK-20260725-006 已完成；Workflow 消费者复用账号/API Key 高风险总授权和统一审计，不读取已废弃的逐 Tool Grant。
- `KnowledgeChatQueryService`、`WorkflowMessageLifecycleService` 和相关消息状态表。

## 修改范围与产物

- 定义 Chat 查询、Workflow 派发/恢复任务类型和稳定业务 `task_key`。
- 以会话/消息为 `resource_key`，消息修订或取消推进 `resource_version`。
- 业务消息落库与 task/outbox 同事务；Consumer 调用同步的查询/Workflow 核心函数。
- 用户消息状态与统一任务状态建立明确映射；统一任务是执行事实源，领域表保留用户交互状态。
- `CANCELED` 不再接受迟到回答；失败/Retry 原因在任务详情展示，聊天界面只显示安全摘要。
- chat 默认并发 3，prefetch 1，可按压测配置。
- 定时 reconcile 只创建/修复任务，不直接请求模型/Workflow。

## 不包含

- 修改答案生成或 Workflow 协议设计。
- Knowledge Pipeline 和 RAG 索引迁移。

## 验收标准

1. 业务消息与 Outbox 不出现一边成功一边缺失。
2. 同一消息重复投递只生成一份最终回答/Workflow 结果。
3. 编辑、删除或取消消息后，旧回答不能覆盖新状态。
4. 失败、重试、人工重试和取消在消息状态与统一任务间一致。
5. Flag 切换不双跑、不丢已登记任务。

## 测试与通过

- 覆盖模型超时、Workflow 5xx/重复回调、进程崩溃、取消、会话删除、消息修订和越权操作。
- 验证事件顺序、回复唯一约束、日志脱敏和轮询停止条件。
- 满足 TEST-20260725-002 通用门禁和 `mq-v4` 场景。

## 风险与回滚

- 用户交互对延迟敏感：监控 ready age，必要时只调整 chat 并发。
- 回滚关闭 `async.mq.chat`；已由 MQ 接管的任务先排空或取消，不能直接让旧恢复器同时领取。

## Git 与回写

- 推荐提交：`feat(async): migrate chat and workflow jobs (mq-v4)`。
- 独立测试、commit、push；记录延迟分位数、失败率和 Flag 切换演练。
- 下一任务：[[10-规划与实施/任务/TASK-20260725-020-mq-v5知识流水线任务迁移]]
- 测试：[[10-规划与实施/测试/TEST-20260725-002-统一异步任务与消息队列测试要求]]

## 实施与验收记录（2026-07-27）

- 新增 `CHAT_QUERY`、`CHAT_WORKFLOW_RUN`、`CHAT_WORKFLOW_RETRY`，统一路由到 chat 队列；默认并发 3、prefetch 1。
- Chat 消息新增 `async_task_id`、`async_version`、`async_task_type`。提交时领域消息、统一任务与 Outbox 同事务写入；Payload 仅包含消息 ID。
- 重试、取消和会话删除推进资源版本并清除旧任务绑定；普通 Chat 与 Workflow 最终写入均校验消息、版本和任务 ID，迟到结果无法覆盖新状态。
- 定时恢复器在 Flag 开启时只登记/修复统一任务，不直接调用模型或 Workflow；关闭 `YLCLOUD_ASYNC_MQ_CHAT` 后恢复旧执行路径，实测未生成新的统一任务。
- 自动化结果：TASK-019 专项 13 项、相关 Chat/Workflow 回归合计 17 项通过；全量后端 270 项通过；前端 12 项通过且生产构建成功；两份 Compose 配置校验通过。
- 真实环境结果：隔离验收库由 V44 升级到 V45；正常投递 Outbox `SENT`；Rabbit 停机时任务保持 `PENDING_PUBLISH`、Outbox 保持 `PENDING` 并累计 7 次补发，恢复后第 8 次发送成功并被消费；领域错误摘要为安全固定文本。
- 回切结果：Flag=false 后同一消息由旧路径处理，统一任务数量保持不变（2），未发生双跑。
- 已知边界：Workflow 服务未作为本地 Compose 依赖启动，远端 Workflow 契约、单次受理、取消检查和结果版本栅栏由 7 项自动化测试覆盖；生产发布前仍需在包含 Workflow 服务的预发布环境做端到端演练。
