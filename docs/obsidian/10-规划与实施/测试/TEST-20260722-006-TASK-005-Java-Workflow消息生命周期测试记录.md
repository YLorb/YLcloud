---
id: TEST-20260722-006
type: test-record
status: passed
created: 2026-07-22
updated: 2026-07-22
task: TASK-20260722-005
tags: [workflow, java, frontend, mysql]
---
# TASK-005 Java Workflow 消息生命周期测试记录

## 自动化结果

- `mvn ... test`：157 passed，0 failed。
- `npm test`：12 passed，0 failed。
- `npm run build`：TypeScript 与 Vite 生产构建成功。
- Workflow retry 幂等真实 MySQL 集成：7 passed（Workflow 仓库提交 `cfff900`）。

## MySQL 8.3 验证

- 在官方 MySQL 8.3.0 临时容器中对最小现有消息表执行 V30 成功。
- `workflow_run_id` 唯一索引与调和复合索引创建成功。
- 旧 execution/epoch 更新：0 行。
- 同 epoch 状态倒退：0 行。
- 首次终态冻结：1 行；重复终态冻结：0 行。

## 验收场景

- 创建 Run 使用稳定幂等键；503 后以相同键重试并仅接受 202。
- retry execution 使用稳定 retry key；503 后重放得到同一新 execution。
- Workflow DEGRADED 结果先冻结，再由 Java 生成最终答案。
- 旧 epoch 的 SUCCEEDED 状态不会拉取结果或更新消息。
- Java 生成失败重试只读取冻结结果，不调用 Workflow retry。
- Workflow 失败重试绑定更高 epoch；取消映射为 Java `FAILED`/红色。
- 前端一级 Java 状态先展示，Workflow 二级状态随后展示；DEGRADED 使用黄色标签。

## 正确性审查

- 所有异步终态、生成和失败写入均受当前 execution epoch 限制。
- 结果冻结 SQL 只允许活动状态进入一次，避免并发调和重复生成。
- 生成任务用 `PENDING -> RUNNING -> SUCCESS/FAILED` CAS；进程中断后只恢复超时的 RUNNING。
- Workflow 成功与 Java 生成成功分离，消息不会提前进入一级 SUCCESS。

## 安全审查

- Workflow 调用使用 audience/scope/binding JWT；浏览器不接触服务凭证。
- Java 客户端限制 base URL、连接池、超时和最大响应体；4xx 不自动重试。
- 未知解析异常不持久化原始异常文本，避免 Context/JSON 片段进入错误回显。
- 用户取消和重试均先校验会话及消息归属；旧轮次无法越过 CAS 更新。
