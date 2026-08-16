---
id: TEST-20260722-001
type: test-requirement
status: pending
priority: P0
created: 2026-07-22
updated: 2026-07-22
owner: unassigned
version: 5
tags: [workflow, testing, integration, security]
---
# Workflow 服务集成测试要求

## 交接要求

实现 Agent/LLM 必须按 TASK 编号逐项工作。每项功能在编码后立即另建或增量更新完整测试文档，包含测试目标、前置条件、数据、步骤、预期、实际结果、证据和结论；随后执行适用测试，完成正确性审查与安全审查。失败时先完善当前功能，不进入下一 TASK。全部通过后，才在受影响仓库分别创建包含同一 TASK 编号的规范提交并记录跨仓库关联。未执行项必须标记“未执行”和原因，不得记为通过。

## 必选范围

1. 单元：Schema、枚举、TaskGraphValidator、环检测、复杂度、重试规则、RRF、冲突胜出、双 Hash、幂等键、两级状态映射、状态机、知识需求选择、知识库范围和保留边界。
2. 契约：以 `ylcloud/schemas` 为唯一权威的 Python/Java 双向 JSON Schema，API version，错误码，未知字段/枚举和大小上限；检查 `new_project` 不存在可独立演进的第二份权威 Schema。
3. 集成：FastAPI+MySQL 8.3 Store 的持久化后 `202` 与重启恢复，Java Client+Workflow，Workflow+model-service 双向鉴权与结构化输出，Workflow+Tool Gateway，Compose 内部 Callback JWT+幂等 ACK+Result pull，Snapshot/Trace 分域唯一落库，Java 基于结构化 Workflow Result 生成最终回答；Workflow 成功但 Java Generate 失败后复用 Snapshot 只重跑 Generate。
4. 端到端：同一 Assistant 页面和知识会话可混合普通任务、知识问答及外部 Tool；当前 Context 足够不召回；与知识无关的外部任务不因关键词召回文档；未显式选择时默认个人知识库，必要时仅自动补充 1–2 个授权知识库并披露；显式选择后不得扩展；权限撤销后后续 Tool 调用拒绝；历史不足多 Query 召回；跨项目多结果；无记忆正常回答；过期快照重建提示；Mock Web/SMTP/CalDAV 和知识库文件 Tool 完整执行。
5. 安全：伪造 user/session/message，过期/错 audience/超 scope JWT，回调 audience 混用，未认证 model-service 调用，任意 callback URL，未注册 Tool，`ALLOW_ONCE` 跨 invocation 复用、`ALLOW_SIMILAR` 跨用户/Tool/参数边界/有效范围复用，业务 Schema 权限，跨用户 Qdrant/知识库，候选记忆正文不进入 Retrieval Trace，敏感原文日志/Trace，任意 import/Shell/知识库边界外文件 Tool。
6. 并发/故障：Ready 节点乱序完成，State Patch 冲突，同幂等键并发，`202` 后立即杀进程并恢复，迟到 epoch，MySQL lock/deadlock，model/tool 超时，Workflow 重启，Callback 丢失/重复。
7. 降级：意图超限、Rerank null/空/低分/模型未加载、语义校验空、Workflow 不可用、Tool Gateway 不可用、高风险操作不可绕过；生产环境不得使用离线 Rerank fallback 冒充有效结果。
8. 保留/删除：Trace 7 天、Snapshot 15 天、running 保护、删除墓碑与 Outbox 原子提交、前端立即隐藏、后台取消与两端幂等物理删除、部分失败重试/告警、迟到结果拒绝、长期记忆保留。
9. Docker/Linux：以 Ubuntu 22.04、4 核/16GB、无 GPU、MySQL 8.3 为目标基线执行本地 Compose；验证非 root、只读根文件系统、健康/就绪、启停/重启、无循环 depends_on、内部端口、资源上限。`isolated_process` 在未完成 Linux 验证前必须确认为禁用。
10. 前端/回归：保留同一 Assistant 页面、API 和表，现有 2 秒轮询可完整展示两级状态；覆盖短期 Context、长期记忆管理、知识库 RAG、model-service 和 Compose smoke，不新增 SSE/token 流式协议。

## RAG 评估集

- 快速集 20–100 条，每次修改后执行。
- 正式集 500+ 条，来源包含真实历史问题、人工构造和边界输入。
- 指标：Recall@5/10、MRR、错误记忆率、冲突率、跨用户泄漏率、空召回率、意图重试率、分阶段 P50/P95、token 和模型成本。
- 跨用户泄漏必须为 0。任何优化必须对比优化前后，不得只展示成功案例。

## 最终通过门禁

- Python 全量测试、Java 单元/集成测试、前端回归、契约测试和 Compose E2E 全部通过。
- 正确性审查与安全审查各一次，P0/P1 问题清零。
- 本地 Compose 指标形成首轮基线，Workflow 不可用降级和一键回滚已演练；明确标记真实生产灰度、OAuth/真实外部账号和生产部署未执行，不将其计为本轮失败。
- 实现与测试结果已回写需求、ADR、任务和独立测试文档。
