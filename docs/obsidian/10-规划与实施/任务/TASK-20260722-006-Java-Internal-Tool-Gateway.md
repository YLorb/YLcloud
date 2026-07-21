---
id: TASK-20260722-006
type: implementation-task
status: pending
priority: P0
created: 2026-07-22
updated: 2026-07-22
owner: unassigned
version: 4
tags: [workflow, java, tools, security]
---
# Java Internal Tool Gateway
- 目标：以单一内部 Invoke 契约向 Workflow 暴露受控业务 Tool，保持 Java 数据所有权。
- 依赖：TASK-001/004/005。
- 产物：可扩展 Tool Registry；首批至少包括 `conversation.verify_context`、`knowledge.list_accessible_spaces`、`knowledge.search`、`knowledge.load_chunks`、`memory.search`、`memory.load_versions`、`memory.enqueue_conflict_cleanup`、`memory.save`、`memory.update`、`memory.delete`、`memory.clear`，以及 Web 搜索、SMTP 邮件、CalDAV 日历和知识库文件 Tool。外部服务首阶段使用可配置 Mock Provider，文件操作仅限 ylcloud 知识库；Tool 风险分级、`ALLOW_ONCE/ALLOW_SIMILAR` 用户确认凭证及幂等记录。
- 不包含：任意 Controller/Service 反射调用，任意 SQL/Qdrant 访问。
- 验收：Qdrant 搜索前强制 userId+ACTIVE；MySQL 再验证版本；每次知识库调用重新检查用户权限；记忆写操作复用现有业务判定而非在 Workflow 重写；`ALLOW_ONCE` 不能跨 invocation 复用，`ALLOW_SIMILAR` 只能命中同一用户、Tool、规范化参数边界和有效范围且可撤销；未注册/超 scope/重复副作用安全拒绝；Mock Web/SMTP/CalDAV 和知识库文件 Tool 有完整契约与端到端证据即计入首阶段完成。
- 风险/回滚：通用端点成为越权面；显式 handler 映射和 deny-by-default，关闭 Workflow Tool scope 回滚。
- 回写：Tool 清单、Schema、风险等级、幂等语义和跨用户负例。
- 测试：[[10-规划与实施/测试/TEST-20260722-001-Workflow服务集成测试要求]]
