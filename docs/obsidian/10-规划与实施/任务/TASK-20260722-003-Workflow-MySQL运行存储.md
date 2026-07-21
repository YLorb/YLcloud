---
id: TASK-20260722-003
type: implementation-task
status: pending
priority: P0
created: 2026-07-22
updated: 2026-07-22
owner: unassigned
version: 1
tags: [workflow, mysql, persistence]
---
# Workflow MySQL 运行存储
- 目标：用 MySQL Store Adapter 替代生产 SQLite Store，在 `ylcloud_workflow` Schema 持久化 Run/Execution/Plan/Invocation/Event/Idempotency/Delivery。
- 依赖：TASK-001；输入为现有 SQLite Store 行为和 CAS/lease 契约。
- 产物：独立 migration runner、MySQL Store、短事务/CAS/lease、批量 Event 写入、最小权限创建说明。
- 不包含：YLcloud 业务数据表。
- 验收：迁移幂等/checksum，CAS 单胜者，lease 接管可恢复，账号无法查询业务 Schema。
- 风险/回滚：与业务库争用连接/锁；限制连接池、独立 Schema，回滚恢复升级前备份而不自动 down。
- 回写：migration version、权限审计和故障注入结果。
- 测试：[[10-规划与实施/测试/TEST-20260722-001-Workflow服务集成测试要求]]

