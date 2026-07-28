---
id: TEST-20260722-007
type: test-record
status: passed
created: 2026-07-22
updated: 2026-07-22
task: TASK-20260722-006
tags: [workflow, tools, security, mysql]
---
# TASK-006 Tool Gateway 测试记录

## 已验证场景

- Registry 拒绝重名和未注册 Tool；风险等级必须与服务端定义一致。
- Service JWT 错 audience/scope/user 绑定均被拒绝，合法全绑定 Token 才能调用。
- 同 invocation 只执行 handler 一次并回放结果；改绑参数/身份产生 409。
- 高风险调用必须经过数据库 Grant；`ALLOW_ONCE` 首次消费 1 行、复用 0 行。
- `ALLOW_SIMILAR` 仅允许持久化参数范围内的变化，跨收件人负例被拒绝。
- 知识 chunk 跨用户负例在 Mapper 查询前即被权限服务拒绝。
- MySQL 8.3 V31 建表成功，确认消费 CAS 通过。
- Mock Web/SMTP/CalDAV 返回确定性结果，副作用由 invocation 表幂等保护。

## 正确性审查

- 业务 handler 全部显式注册，并复用现有权限、知识、文件及记忆服务。
- 搜索与正文加载分离；Memory Trace 所需 ID/version/hash 可独立返回。
- 外部/删除操作在执行前完成确认消费，执行结果持久化后才能安全回放。

## 安全审查

- `/internal/**` 只跳过浏览器 JWT，内部 Controller 仍强制 Service JWT；不存在匿名业务调用。
- 未授权知识库不会触发 Qdrant/MySQL chunk 加载。
- 参数统一规范化哈希；一次性确认不可跨 invocation 使用，类似确认可撤销且有短 TTL。
- 未知 handler 异常不回显内部堆栈、Token、用户正文或 Secret。
