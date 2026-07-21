---
id: TASK-20260722-001
type: implementation-task
status: pending
priority: P0
created: 2026-07-22
updated: 2026-07-22
owner: unassigned
version: 5
tags: [workflow, api, schema]
---
# Workflow API 与 Schema 契约
- 目标：固化 Run、结构化 Result、Callback/ACK、Tool Invoke、Intent Plan、Task Graph、Snapshot Draft 和 Retrieval Trace JSON Schema，所有契约带版本；Workflow Result 不包含权威最终回答。
- 依赖/输入：无；以 ADR-20260722-001 和已确认意图枚举为输入。
- 范围/产物：创建唯一权威目录 `ylcloud/schemas`；先盘点并迁移 `new_project/schemas/workflow.schema.json` 与 `workflow-v2.schema.json`，再补齐中立格式契约、错误码、状态枚举、`requestContextHash/snapshotHash`、知识库选择与实际使用披露、`ALLOW_ONCE/ALLOW_SIMILAR` 确认凭证、删除墓碑/Outbox、Java Generate 重试语义，以及 Python/Java DTO 生成或对照规则；`new_project` 只消费生成或同步产物。
- 不包含：HTTP 服务实现和业务召回。
- 验收：双端契约测试能拒绝缺字段、未知枚举、超限预算和版本不兼容；合法样例双向序列化等价；回调 JWT audience 和 delivery ACK 有固定契约；同一 runId 可关联多个递增 execution epoch；`requestContextHash` 与 `snapshotHash` 不可混用或覆盖；两级状态映射完整；仓库检查能阻止 `new_project` 出现第二份可独立演进的权威 Schema。
- 风险/回滚：迁移期间双份 Schema 漂移；先建立 hash/契约一致性门禁并切换消费者，再移除或明确标记旧目录为非权威生成物，旧版 Schema 保留读兼容，新版未完成本地验证前不改默认。
- 回写：记录 schema version、兼容矩阵、生成器/手写 DTO 决策和测试证据。
- 测试：[[10-规划与实施/测试/TEST-20260722-001-Workflow服务集成测试要求]]
