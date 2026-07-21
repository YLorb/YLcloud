---
id: TASK-20260722-001
type: implementation-task
status: completed
priority: P0
created: 2026-07-22
updated: 2026-07-22
owner: Codex
version: 6
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
- 测试记录：[[10-规划与实施/测试/TEST-20260722-002-TASK-001契约测试记录]]

## 2026-07-22 实施结果

- 已在 `ylcloud/schemas` 建立唯一权威目录，迁入 Workflow v1/v2，并新增契约版本 `1.0` 的 Run、Result、Callback/ACK、Intent Plan、Tool Invoke、Snapshot、Retrieval Trace、删除 Outbox、Java Generate Retry、错误码和两级状态映射 Schema。
- Java 采用手写 record DTO，并由字段一致性、共享样例、Bean Validation 和语义负例门禁对照权威 Schema；Python 采用 Pydantic DTO 与 Draft 2020-12 JSON Schema 双重校验。
- `new_project` 保留生成快照，通过固定白名单、SHA-256、符号链接拒绝和只读检查模式阻止独立漂移；共享样例同样纳入门禁。
- 正确性审查已覆盖缺字段、未知枚举、预算、双 Hash、终态、知识库封闭范围、Tool 成败互斥和状态映射；安全审查已覆盖高风险确认绑定、任意字段拒绝、符号链接目标、最终回答边界和 Trace 不保存正文。
- 适用测试全部通过，证据见 TEST-20260722-002；状态更新为 `completed`。
