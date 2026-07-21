---
id: TEST-20260722-001
type: test-requirement
status: pending
priority: P0
created: 2026-07-22
updated: 2026-07-22
owner: unassigned
version: 1
tags: [workflow, testing, integration, security]
---
# Workflow 服务集成测试要求

## 交接要求

实现 Agent/LLM 必须在编码后另建完整测试文档，包含测试目标、前置条件、数据、步骤、预期、实际结果、证据和结论。未执行项必须标记“未执行”和原因，不得记为通过。

## 必选范围

1. 单元：Schema、枚举、TaskGraphValidator、环检测、复杂度、重试规则、RRF、冲突胜出、幂等键、状态机、保留边界。
2. 契约：Python/Java 双向 JSON Schema，API version，错误码，未知字段/枚举和大小上限。
3. 集成：FastAPI+MySQL Store，Java Client+Workflow，Workflow+model-service，Workflow+Tool Gateway，Callback+Result pull，Snapshot/Trace 落库。
4. 端到端：当前 Context 足够不召回；历史不足多 Query 召回；跨项目多结果；无记忆正常回答；过期快照重建提示。
5. 安全：伪造 user/session/message，过期/错 audience/超 scope JWT，未注册 Tool，业务 Schema 权限，跨用户 Qdrant，敏感原文日志/Trace，任意 import/Shell/文件 Tool。
6. 并发/故障：Ready 节点乱序完成，State Patch 冲突，同幂等键并发，迟到 epoch，MySQL lock/deadlock，model/tool 超时，Workflow 重启，Callback 丢失/重复。
7. 降级：意图超限、Rerank null/空/低分、语义校验空、Workflow 不可用、Tool Gateway 不可用、高风险操作不可绕过。
8. 保留/删除：Trace 7 天、Snapshot 15 天、running 保护、会话删除级联、长期记忆保留。
9. Docker/Linux：非 root、只读根文件系统、健康/就绪、启停/重启、无循环 depends_on、内部端口、资源上限。`isolated_process` 在未完成 Linux 验证前必须确认为禁用。
10. 回归：现有 Assistant、短期 Context、长期记忆管理、知识库 RAG、model-service 和 Compose smoke。

## RAG 评估集

- 快速集 20–100 条，每次修改后执行。
- 正式集 500+ 条，来源包含真实历史问题、人工构造和边界输入。
- 指标：Recall@5/10、MRR、错误记忆率、冲突率、跨用户泄漏率、空召回率、意图重试率、分阶段 P50/P95、token 和模型成本。
- 跨用户泄漏必须为 0。任何优化必须对比优化前后，不得只展示成功案例。

## 最终通过门禁

- Python 全量测试、Java 单元/集成测试、前端回归、契约测试和 Compose E2E 全部通过。
- 正确性审查与安全审查各一次，P0/P1 问题清零。
- 灰度指标达到基线，Workflow 不可用降级和一键回滚已演练。
- 实现与测试结果已回写需求、ADR、任务和独立测试文档。

