---
title: Query 规划、多路召回与融合
type: feature-detail
status: maintained
updated: 2026-07-16
tags:
  - ylcloud
  - rag
  - query-rewrite
  - hyde
  - step-back
  - retrieval
  - bm25
  - rrf
---

# Query 规划、多路召回与融合

## 产品目标

用户问题可能口语化、过短、包含代词或与文档措辞不同。单一路由容易漏召回：向量擅长语义，BM25/关键词擅长专有名词和精确短语，Metadata 擅长结构过滤。系统通过查询规划和多路融合提高覆盖率。

## Query 规划

QueryRewriteService 根据原问题生成 QueryPlan，可能包含：

- rewrittenQuery：去歧义和规范表达；
- multiQueries：从不同角度扩展多个问题；
- HyDE：生成假设答案，用其 embedding 接近真实文档表述；
- StepBack：抽象为背景问题，召回概念性材料。

这些查询只是召回工具，最终回答仍必须以真实知识库 chunk 为依据，不能把 HyDE 文本当作引用来源。

## 多路召回

RagMultiRouteRetriever 协调：

- Qdrant 向量召回；
- Bm25KeywordRetriever 的 BM25；
- IkRagKeywordTokenizer 的中文关键词；
- Metadata/结构匹配；
- 多个 QueryPlan 分支的结果。

每条路线产生 RagCandidate，包含 chunk 身份、路线和分数。RagCandidateMerger 以稳定 chunk ID 去重并融合排序，常见思想是 RRF：按各路线名次贡献而非直接比较不可同尺度的原始分数。

## 流程

~~~mermaid
flowchart LR
  Q[用户问题] --> P[QueryPlan]
  P --> V[Vector]
  P --> B[BM25]
  P --> K[Keyword]
  P --> M[Metadata]
  V --> F[去重与融合]
  B --> F
  K --> F
  M --> F
  F --> N[邻接上下文扩展]
  N --> R[Rerank 候选]
~~~

## 核心代码

- query/QueryRewriteService、QueryPlan。
- retriever/RagMultiRouteRetriever。
- Bm25KeywordRetriever、IkRagKeywordTokenizer。
- RagCandidate、RagCandidateMerger。
- SpaceRagService.searchChunks、expandContextChunks、looksLikeCodeQuestion。

## 权限与有效性

每条路线都必须受 spaceId 和 active 状态约束。不能先全库召回再由前端过滤，因为分数、内容和时序都可能泄露未授权数据。

## 取舍

多路召回提高 recall，但增加模型调用、延迟和噪声。topK、候选数和扩展路由应由评测集驱动，不能只追求更多结果。当前仍需补 Recall/MRR/NDCG 与阶段级日志。
