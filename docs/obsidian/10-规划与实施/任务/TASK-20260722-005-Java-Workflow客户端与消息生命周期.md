---
id: TASK-20260722-005
type: implementation-task
status: pending
priority: P0
created: 2026-07-22
updated: 2026-07-22
owner: unassigned
version: 1
tags: [workflow, java, chat]
---
# Java Workflow 客户端与消息生命周期
- 目标：在 `cloud-server` 增加 Workflow HTTP Client，把 Assistant 任务绑定 `runId/executionId`，不阻塞前端请求。
- 依赖：TASK-001/004。
- 产物：客户端配置/超时/连接池/状态映射，消息与 Run 绑定字段，幂等创建，查询/取消/重试/结果拉取。
- 不包含：Tool Gateway 和召回算法。
- 验收：重复提交仅一 Run；旧 epoch 不能更新消息；Workflow 超时/不可用进入明确降级/错误状态。
- 风险/回滚：远程调用扩大延迟；以 feature flag 切回现有 Java Generate 链。
- 回写：超时/重试矩阵、错误映射和消息状态证据。
- 测试：[[10-规划与实施/测试/TEST-20260722-001-Workflow服务集成测试要求]]

