---
id: TASK-20260722-005
type: implementation-task
status: pending
priority: P0
created: 2026-07-22
updated: 2026-07-22
owner: unassigned
version: 4
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
