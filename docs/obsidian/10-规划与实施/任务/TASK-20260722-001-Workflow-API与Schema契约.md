---
id: TASK-20260722-001
type: implementation-task
status: pending
priority: P0
created: 2026-07-22
updated: 2026-07-22
owner: unassigned
version: 1
tags: [workflow, api, schema]
---
# Workflow API 与 Schema 契约
- 目标：固化 Run、Result、Callback、Tool Invoke、Intent Plan、Task Graph、Snapshot Draft 和 Retrieval Trace JSON Schema，所有契约带版本。
- 依赖/输入：无；以 ADR-20260722-001 和已确认意图枚举为输入。
- 范围/产物：`schemas/` 中立格式契约、错误码、状态枚举、Python/Java DTO 生成或对照规则。
- 不包含：HTTP 服务实现和业务召回。
- 验收：双端契约测试能拒绝缺字段、未知枚举、超限预算和版本不兼容；合法样例双向序列化等价。
- 风险/回滚：契约漂移；旧版 Schema 保留读兼容，新版未灰度前不改默认。
- 回写：记录 schema version、兼容矩阵、生成器/手写 DTO 决策和测试证据。
- 测试：[[10-规划与实施/测试/TEST-20260722-001-Workflow服务集成测试要求]]

