---
id: TASK-20260722-005
type: implementation-task
status: completed
priority: P0
created: 2026-07-22
updated: 2026-07-22
owner: unassigned
version: 5
tags: [workflow, java, chat]
---
# Java Workflow 客户端与消息生命周期
- 目标：在 `cloud-server` 增加 Workflow HTTP Client，把 Assistant 任务绑定 `runId/executionId`，不阻塞前端请求。
- 依赖：TASK-001/004。
- 产物：客户端配置/超时/连接池、一级 Java 状态与二级 Workflow 状态字段、`degraded` 标志和标签颜色映射，消息与 Run/Execution 绑定字段，幂等创建，查询/取消/重试/结果拉取；继续复用同一 Assistant 页面、知识会话 API 和 `knowledge_chat_session/message` 表，保留现有 2 秒轮询；Java 使用 Workflow 返回的结构化 Context 生成最终回答。
- 不包含：Tool Gateway 和召回算法。
- 验收：同一会话可混合普通任务、知识问答和外部 Tool；重复提交仅一 Run；用户重试复用同一 assistantMessageId/runId 并创建新 execution epoch；旧 epoch 不能更新消息；`QUEUED/RUNNING/SUCCESS/FAILED` 一级状态与 Workflow 二级状态、颜色映射一致；`DEGRADED` 显示为黄色降级成功；`CANCELLED` 映射为红色 `FAILED`；Workflow 结果本身不作为最终回答直接写入消息；Workflow 已成功但 Java 最终生成失败时一级状态为 `FAILED`，重试复用已冻结 Snapshot，只重跑 Java Generate。
- 风险/回滚：远程调用扩大延迟；以 feature flag 切回现有 Java Generate 链。
- 回写：超时/重试矩阵、错误映射和消息状态证据。
- 测试：[[10-规划与实施/测试/TEST-20260722-001-Workflow服务集成测试要求]]

## 实施回写（2026-07-22）

- Java 消息表已增加 `workflow_run_id / workflow_execution_id / workflow_execution_epoch`、二级状态、降级标志、冻结结果与 Java 生成状态；Run ID 唯一，异步更新使用 `executionId + epoch` CAS。
- 提交仍立即返回持久化的 `QUEUED` Assistant 消息；事务提交后异步创建 Run，Java 每 2 秒调和未终态 Run。进程重启后恢复未受理消息、活动 Run 和中断的 Java Generate。
- Workflow `SUCCEEDED/DEGRADED` 后先冻结 `resultHash/snapshotHash/resultJson`，再由 Java 模型服务生成并写入最终回答；Workflow 结构化结果不会直接成为用户回答。
- Java Generate 失败时一级状态为 `FAILED`。用户重试复用同一 `runId/executionId/epoch` 与冻结结果，仅重跑 Java Generate；Workflow 自身失败时复用 `assistantMessageId/runId` 并创建更高 execution epoch。
- 同一页面同时展示 Java 一级标签和 Workflow 二级标签：`DEGRADED` 黄色成功，`CANCELLED` 红色失败；活动 Run 支持取消。
- Feature flag：`YLCLOUD_WORKFLOW_ENABLED=false` 可切回原 Java RAG 执行链。

### HTTP 超时与重试矩阵

| 操作 | 成功码 | 最大尝试 | 幂等门禁 | 4xx |
|---|---:|---:|---|---|
| 创建 Run | 202 | 2 | 同一 `Idempotency-Key` | 不重试 |
| 查询 Run | 200 | 3 | GET | 不重试 |
| 拉取结果 | 200 | 3 | GET | 不重试 |
| 取消 Run | 200 | 2 | `cancel:{runId}` | 不重试 |
| 重试 execution | 202 | 2 | 同一 retry key，服务端原子幂等 | 不重试 |

仅传输错误、429、502、503、504 重试；连接和响应均使用短超时、受限连接池及 2 MiB 响应上限。

### 验证

- Java 全量：157 passed。
- 前端全量：12 passed；生产构建成功。
- MySQL 8.3：V30 迁移成功；旧 epoch、状态倒退、重复终态写入均影响 0 行。
- 详细记录：[[10-规划与实施/测试/TEST-20260722-006-TASK-005-Java-Workflow消息生命周期测试记录]]。
