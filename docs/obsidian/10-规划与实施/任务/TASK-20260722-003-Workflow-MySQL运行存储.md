---
id: TASK-20260722-003
type: implementation-task
status: completed
priority: P0
created: 2026-07-22
updated: 2026-07-22
owner: Codex
version: 3
tags: [workflow, mysql, persistence]
---
# Workflow MySQL 运行存储
- 目标：用 MySQL Store Adapter 替代生产 SQLite Store，在 `ylcloud_workflow` Schema 持久化 Run/Execution/Plan/Invocation/Event/Idempotency/Delivery。
- 依赖：TASK-001；输入为现有 SQLite Store 行为和 CAS/lease 契约。
- 产物：独立 migration runner、MySQL Store、Run/初始 Execution 原子受理事务、待调度 Run 查询与 lease、短事务/CAS、批量 Event 写入、临时结果正文 ACK/短 TTL 清理、最小权限创建说明。
- 不包含：YLcloud 业务数据表。
- 验收：迁移幂等/checksum，`202` 前数据已提交，进程重启可扫描并恢复已受理 Run，CAS 单胜者，lease 接管可恢复；同一数据不跨 Schema 长期重复；Workflow 账号无法查询业务 Schema。
- 风险/回滚：与业务库争用连接/锁；限制连接池、独立 Schema，回滚恢复升级前备份而不自动 down。
- 回写：migration version、权限审计和故障注入结果。
- 测试：[[10-规划与实施/测试/TEST-20260722-001-Workflow服务集成测试要求]]

## 实施回写

- migration version：`0001_workflow_runtime.sql`，由独立向前 migration runner 管理；应用版本、文件名、SHA-256 与时间写入 `schema_migrations`，脚本被修改或数据库版本超前时拒绝启动。
- 权威 Schema：`ylcloud_workflow`，包含 Run、Execution、Plan、NodeInvocation、Event、Idempotency、Delivery 与短 TTL Result；没有 YLcloud 业务表依赖。
- 可靠受理：Run、首个 Execution、24 小时幂等记录在同一短事务提交；只有 commit 完成才返回 `202`。相同 key+request 返回原响应，不同 request 返回 409。
- 调度恢复：`FOR UPDATE SKIP LOCKED` 领取 QUEUED 或 lease 过期的运行；Run/Execution 同事务推进，版本 CAS 保证单胜者；新 Store 实例已验证可接管进程重启前遗留的运行。
- 结果交付：临时 Result 按契约 `resultHash` 精确 ACK 幂等删除，并提供 TTL 清理；Event 使用批量写入。
- 最小权限：Workflow 账号仅获独立 Schema 的 `SELECT/INSERT/UPDATE/DELETE/CREATE/ALTER/INDEX/REFERENCES`，部署说明见 Workflow `docs/MYSQL_RUNTIME.md`；不授予任何 YLcloud 业务 Schema 权限或全局管理权限。
- Docker MySQL 基准：官方 `mysql:8.3`，实际服务器 `8.3.0`，镜像 digest `sha256:9de9d54fecee6253130e65154b930978b1fcc336bcc86dfd06e89b72a2588ebe`。
- 测试证据：[[10-规划与实施/测试/TEST-20260722-004-TASK-003-MySQL运行存储测试记录]]。
