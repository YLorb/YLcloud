---
id: TASK-20260722-007
type: implementation-task
status: pending
priority: P0
created: 2026-07-22
updated: 2026-07-22
owner: unassigned
version: 2
tags: [workflow, intent, task-graph]
---
# 意图 Plan 与 Task Graph 编译
- 目标：一次结构化模型调用生成唯一主任务、必要子任务和独立状态，经服务端校验后编译 Workflow IR。
- 依赖：TASK-001/002。
- 产物：model-service 结构化输出契约、Prompt/schema version，重试判定，TaskGraphValidator，无环依赖编译器，`UNKNOWN/SKIPPED_UNCERTAIN` 降级。
- 不包含：记忆写入判断和任意动态 Tool。
- 验收：model-service 按版本化 JSON Schema 返回结构化 Plan；0.7 阈值和 1–10 次上限生效；低置信无关子任务丢弃不重试；关键路由/安全不确定重试；循环/重复/超 6 任务拒绝。
- 风险/回滚：模型格式漂移；固定 Schema+修复重试，超限跳过长期记忆。
- 回写：Prompt/model/schema version、枚举兼容及失败分布。
- 测试：[[10-规划与实施/测试/TEST-20260722-001-Workflow服务集成测试要求]]
