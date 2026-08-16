---
id: TASK-20260813-002
type: implementation-task
status: completed
priority: P0
created: 2026-08-13
updated: 2026-08-14
owner: unassigned
version: 2
tags: [sandbox, workflow, trace]
---

# Workflow Sandbox 执行模式接入

扩展 `ToolSpec.execution_mode` 为 `sandbox`，增加固定远端 Tool 描述、Sandbox Client 和 Handler 分支；结果 Schema 继续由 Workflow 校验，Sandbox Span 写入 Tool Node Trace 的 `safe_metadata`。`inline` 和 `isolated_process` 完全保持原义；没有 Sandbox Client 时显式失败。测试要求见 [[10-规划与实施/测试/TEST-20260813-001-Sandbox实施测试要求]]。

## 实施回写

已新增 `SandboxToolDescriptor`、强制幂等校验、服务 Client、Handler 分支、安全错误映射与父 Trace 子 Span 元数据。成功、失败和超时响应的 Sandbox 子 Span 均会进入父 Trace；禁用或不可达时显式失败，不调用本地 callable。2026-08-14 Workflow 全量测试 302 项通过、7 项条件跳过。
