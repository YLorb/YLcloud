---
title: Rerank、回答与引用
type: feature-detail
status: maintained
updated: 2026-07-16
tags:
  - ylcloud
  - rag
  - rerank
  - llm
  - citations
  - grounding
---

# Rerank、回答与引用

## 产品目标

召回阶段追求不漏，回答阶段需要把最相关、可支持答案的 chunk 放入有限上下文。用户不仅要得到自然语言答案，还要能验证答案来自哪些文件。

## Rerank

RagRerankService 接收问题和融合候选，调用 RagModelClient 的 rerank 能力得到更可比的相关性顺序；失败时按配置执行明确降级，而不是把降级伪装成真实模型成功。RerankResult 保存候选身份与新分数。

候选规模通常大于最终 topK，先扩大召回再重排。之后 SpaceRagService.limitChunks 控制上下文，expandContextChunks 可补充命中片的邻接内容。

## 回答生成

SpaceRagService.query 先校验 Space 权限和配置，再构造 QueryPlan、召回、Rerank、上下文与 citations。RagChatService 使用 RagGenerateRequest/RagChatRequest 调用模型，prompt 要求仅依据给定上下文回答；KnowledgeRagQueryService 支持多 Space 的统一问答。

## 引用

buildCitations 把有效 chunk 映射为 SpaceRagCitationVO，包含文件名、spaceId/spaceName、spaceFileId、documentId、chunk 位置、previewUrl/downloadUrl。引用必须来自实际进入上下文的有效 chunk，并在访问链接时重新授权。

~~~mermaid
flowchart LR
  C[融合候选] --> R[Rerank]
  R --> T[TopK + 邻接上下文]
  T --> L[LLM 严格回答]
  T --> X[构建 citations]
  L --> O[答案]
  X --> O
~~~

## no-answer

当知识库不能支持答案时，RagChatResult 使用显式 noAnswer 标志，不再由 SpaceRagService 匹配单一中文文案。无召回、模型空答案、配置的 no-answer 文案和受控语义变体都会进入 no-answer；响应同步清空 citations、contexts、hitChunkIds，查询日志也不记录命中 chunk。

模型服务不可用与 no-answer 是不同状态：如果已经检索到资料但生成暂不可用，仍保留 citations，便于用户人工查看依据。

## 查询日志

saveQueryLog 记录 spaceId、userId、question、answer、hitChunkIds、模型、topK、temperature、成功与错误摘要。后续应增加路线分数、Rerank 前后变化、阈值和反馈，才能做质量分析。

## 核心代码

RagRerankService、RagChatService、RagModelClient、SpaceRagService.query/searchChunks/buildCitations/saveQueryLog、KnowledgeRagQueryService。
