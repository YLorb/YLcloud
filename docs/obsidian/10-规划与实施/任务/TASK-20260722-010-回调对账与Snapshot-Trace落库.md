---
id: TASK-20260722-010
type: implementation-task
status: pending
priority: P0
created: 2026-07-22
updated: 2026-07-22
owner: unassigned
version: 3
tags: [workflow, callback, snapshot, trace]
---
# 回调对账与 Snapshot/Trace 落库
- 目标：建立至少一次终态回调、Java 补偿对账和当前 epoch 结果持久化。
- 依赖：TASK-003/005/006/009。
- 产物：固定 Compose 内部目标的 Callback/Result API，独立 callback JWT audience，delivery retry/幂等 ACK，Java reconciler，包含 `snapshotHash` 的 Context Snapshot v2 扩展，只保存候选引用/分数而不保存正文的 Retrieval Trace 新表/诊断 API，过期重建标记。
- 不包含：普通用户 Trace UI。
- 验收：回调丢失/重复/乱序可恢复；Snapshot 能在 15 天内冻结实际注入文本；Trace 记录候选 `memoryId/version/hash`、Query、分数、排序和淘汰原因且不含候选正文；ACK 后 Workflow 临时正文删除或短 TTL 清理；旧 epoch 拒绝更新；Java 只在当前 epoch 下生成并写入最终回答。
- 风险/回滚：大 JSON 和重复写；哈希+幂等约束+大小上限，关闭回调改为主动查询。
- 回写：schema version、大小分布、对账证据和降级案例。
- 测试：[[10-规划与实施/测试/TEST-20260722-001-Workflow服务集成测试要求]]
