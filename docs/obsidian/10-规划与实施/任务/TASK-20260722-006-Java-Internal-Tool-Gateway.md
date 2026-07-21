---
id: TASK-20260722-006
type: implementation-task
status: pending
priority: P0
created: 2026-07-22
updated: 2026-07-22
owner: unassigned
version: 1
tags: [workflow, java, tools, security]
---
# Java Internal Tool Gateway
- 目标：以单一内部 Invoke 契约向 Workflow 暴露受控业务 Tool，保持 Java 数据所有权。
- 依赖：TASK-001/004/005。
- 产物：Tool Registry；`conversation.verify_context`、`memory.search`、`memory.load_versions`、`memory.enqueue_conflict_cleanup`；Tool 幂等记录。
- 不包含：任意 Controller/Service 反射调用，任意 SQL/Qdrant 访问。
- 验收：Qdrant 搜索前强制 userId+ACTIVE；MySQL 再验证版本；未注册/超 scope/重复副作用安全拒绝。
- 风险/回滚：通用端点成为越权面；显式 handler 映射和 deny-by-default，关闭 Workflow Tool scope 回滚。
- 回写：Tool 清单、Schema、风险等级、幂等语义和跨用户负例。
- 测试：[[10-规划与实施/测试/TEST-20260722-001-Workflow服务集成测试要求]]

