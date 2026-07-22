---
id: TASK-20260722-007
type: implementation-task
status: completed
priority: P0
created: 2026-07-22
updated: 2026-07-22
owner: unassigned
version: 3
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

## 实施回写（2026-07-22）

- 权威契约新增 `schemas/intent-plan.schema.json`，固定 `schemaVersion=intent-plan/1.0`、`promptVersion=intent-plan-prompt/1.0`、完整意图枚举、唯一主任务、最多 6 个任务和独立 Context/不确定性状态。
- model-service 新增 `POST /plan`，强制 `model.plan` scope 和 runId 绑定；单次请求只生成一个完整 Plan，输出经严格 Pydantic/Schema 校验，非法上游正文不回显。
- `PLAN_MOCK_ENABLED=true` 仅提供本地确定性契约/E2E Mock；默认关闭，不冒充真实意图模型。真实调用复用独立 Plan 模型配置。
- Workflow 固定置信阈值 `0.7`，默认 4 次、可配置 1–10 次。低置信且无关的子任务直接丢弃且不重试；关键路由/安全不确定触发重试。
- 重试耗尽时返回明确降级：安全不确定任务及依赖它的下游任务标记 `SKIPPED_UNCERTAIN`；结构化输出持续损坏时只编译 `UNKNOWN` 的当前 Context 路径，不生成 Tool 路由。
- `TaskGraphValidator` 拒绝重复、未知依赖、环、超过 6 个任务以及不能到达主任务的活动子任务；通过后确定性编译为现有 Workflow v2 IR，Workflow 仍不生成最终用户回答。
- 详细测试：[[10-规划与实施/测试/TEST-20260722-008-TASK-007-意图Plan与Task-Graph测试记录]]。
