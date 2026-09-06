---
title: RAG 配置、分析与修复
type: feature-detail
status: maintained
updated: 2026-07-16
tags:
  - ylcloud
  - rag
  - configuration
  - analytics
  - observability
  - repair
---

# RAG 配置、分析与修复

## 产品目标

知识库管理员需要调整检索和生成参数、查看任务与文档状态、理解查询质量，并在外部存储异常后触发安全修复，而不是直接操作数据库或 Qdrant。

## 配置

SpaceRagController 的 GET/PUT /api/space/{spaceId}/rag/config 返回和更新 chunkSize、chunkOverlap、topK、temperature、模型/端点相关选择及 knowledgeProfileEnabled。updateConfig 需要 OWNER/ADMIN，校验范围并通过 saveConfigChangeLog 保存操作者、前后快照、变化字段和时间。

影响索引结构的 chunk 配置变更应提示重建；只影响查询的 topK/temperature 可用于后续请求。画像开关默认 true，但只影响 RAG 完成后的增强任务。

### topK 配置

topK 控制送入 LLM 生成器的 context chunk 数量。当前默认值为 10，可通过 API 配置。

**检索流程**：
1. 向量检索返回 topK 个候选 chunk
2. 为每个候选 chunk 获取其左右相邻 chunk（同一文档内）
3. 合并去重后进行 ReRank 精排
4. 最终取 topK 个 chunk 送入生成器 context

**配置建议**：
- 简单事实问答：topK=5~8 足够
- 复杂跨文档问答：topK=10~15
- 最大值建议不超过 20，避免 context 过长导致生成质量下降

## 任务与修复

- documents：查看文档 status、vectorState、chunkCount 和错误。
- tasks/retry：查看和重试单任务。
- retry-failed：批量重试失败。
- rebuild/file rebuild：重新建立索引。
- vector repair：按文件或 Space 进入一致性修复。
- search：文档/索引检索管理入口。

修复不是把数据库状态强制改为 SUCCESS，而是隔离旧索引、重新生成或清理，并重新通过硬门槛。

## 分析

SpaceRagAnalyticsController/Service 提供 summary、queries、no-answer、config-logs。前端 KnowledgeBaseView/KnowledgeSettingsView 展示任务、文档、参数和分析。

## 关键指标

当前可观察成功率、失败、无答案和配置变化；后续应增加：

- 各检索路线命中与融合贡献；
- Rerank 前后名次；
- scoreThreshold 分布；
- p50/p95 阶段耗时；
- embedding/LLM 成本；
- 用户正负反馈；
- Recall、MRR、NDCG。

## 安全与审计

普通 MEMBER 可以查询和查看获授权内容，但不能修改管理配置或执行破坏性重建。配置日志不能保存 API Key 等 Secret。修复任务必须持久化并支持幂等，避免管理员重复点击导致并发重建。
