---
id: TEST-20260722-008
type: test-record
status: passed
created: 2026-07-22
updated: 2026-07-22
task: TASK-20260722-007
tags: [workflow, intent, model-service, graph]
---
# TASK-007 意图 Plan 与 Task Graph 测试记录

## 已验证场景

- 权威 Schema 的请求/响应样例同时通过 JSON Schema、model-service Pydantic 与 Workflow Pydantic 校验。
- model-service `/plan` 无 Token 401、错误 scope 403；合法调用返回版本化 Plan，非法模型正文返回脱敏 502。
- 唯一主任务、重复 taskId、未知/重复依赖、环、超过 6 个任务和无关活动子任务均被拒绝。
- 低置信无关子任务不触发重试并直接移除；关键路由不确定重试后可恢复。
- 置信阈值固定 0.7，调用上限 1–10 生效；结构化响应持续失败时按当前 Context 降级。
- 安全不确定主/子任务及依赖下游不会编译成可执行任务；`SKIPPED_UNCERTAIN` 和降级原因进入 IR 元数据。
- DAG 确定性编译为 Workflow v2 IR，节点 Prompt 明确只产出中间 Context，不产出最终用户回答。

## 执行结果

- model-service 定向：`6 passed`（unittest）。
- Workflow TASK-007 定向：`10 passed`。
- Workflow 全量：`298 passed, 7 skipped`；跳过项为未配置 MySQL 的条件集成测试。
- YLcloud Maven 全量：`165 passed`，`BUILD SUCCESS`。

## 正确性审查

- Schema/prompt/model 版本在两端严格匹配；模型不能改变阈值、任务上限或枚举。
- 依赖方向固定为 prerequisite → dependent，所有活动子任务必须能到达唯一主任务。
- 结构化失败、语义不确定和无关低置信分别处理，不用同一种重试掩盖业务差异。

## 安全审查

- Plan 客户端使用独立 audience、最小 `model.plan` scope、runId 绑定、固定端点并拒绝重定向。
- 请求/响应均有字节上限；异常不包含 Token、用户问题或模型原始输出。
- 安全不确定无法通过降级绕过为 Tool 调用；持续失败只保留无 Tool 的 Context 路径。
