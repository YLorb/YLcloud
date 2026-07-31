# TASK-007 Intent Plan 与 Task Graph

Workflow 通过固定 `POST /plan` 调用 model-service，使用独立
`ylcloud-model-service` audience、`model.plan` scope 和 runId 绑定。唯一权威契约位于
YLcloud `schemas/intent-plan.schema.json`；本仓库 `schemas/` 仅保存白名单同步快照。

语义门禁：

- 置信阈值固定为 0.7，默认 4 次调用，配置范围 1–10。
- 低置信且无关的子任务直接丢弃，不消耗修复重试。
- 主任务低置信/UNKNOWN、关键路由或安全不确定会重试。
- 重试耗尽后，安全不确定及依赖下游标记为 `SKIPPED_UNCERTAIN`；持续无合法结构化输出时只保留 `UNKNOWN` 当前 Context 路径。
- Validator 拒绝重复、未知依赖、环、超过 6 个任务和不能服务主任务的活动子任务。

编译器只生成 Workflow v2 的结构化中间 Context 节点，不生成最终用户回答，也不会依据不确定 Plan 动态创建 Tool。
