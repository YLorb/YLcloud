---
title: Embedding 与模型服务
type: feature-detail
status: maintained
updated: 2026-07-16
tags:
  - ylcloud
  - rag
  - embedding
  - model-service
  - bge-m3
  - vector
---

# Embedding 与模型服务

## 产品目标

Embedding 把文本映射为可比较的向量，使语义相近但字面不同的问题和文档也能匹配。它是 RAG 索引的必要步骤，不是可忽略的增强项。

## 调用边界

Spring Boot 通过 RagModelClient 抽象模型能力，HttpRagModelClient 调用 model-service；BgeM3EmbeddingModel 适配 embedding 使用。model-service 根据模型类型选择 FlagModel/BGEM3FlagModel 等实现，并暴露 health/readiness。

输入是 chunk 文本列表，输出必须与输入一一对应。系统不因 HTTP 200 就认定成功，还需要验证：

- 返回向量数量等于文本数量；
- 每个向量维度等于配置和 Qdrant collection 维度；
- 每个数值有限，不含 NaN 或 Infinity；
- 模型 readiness 表明真实模型可用；
- 若配置禁止 offline fallback，不能静默使用伪向量。

## 流程

~~~mermaid
sequenceDiagram
  participant R as SpaceRagService
  participant C as RagModelClient
  participant M as model-service
  participant Q as Qdrant
  R->>C: embed(chunk contents)
  C->>M: /embed
  M-->>C: N x dimension vectors
  C-->>R: validated vectors
  R->>R: 数量/维度/有限值硬校验
  R->>Q: upsert points
~~~

## 配置契约

embedding 模型名、向量维度、模型服务 endpoint 和 Qdrant collection 必须一致。更换模型可能改变向量空间，即使维度相同也不能与旧点混用，应触发全量重建或使用新 collection。

## 核心代码

- RagModelClient、HttpRagModelClient。
- BgeM3EmbeddingModel。
- RagProperties 与 SiteSettingService 的运行配置。
- model-service 的 /embed、/rerank、/generate、/chat 和 readiness。
- QdrantCollectionInitializer。

## 失败处理

连接错误、超时、模型未就绪、数量/维度错误都使当前文档索引失败并进入向量清理状态。不能写入部分点后把文档标 SUCCESS。错误摘要用于用户重试和运维定位，模型堆栈留在服务日志。

## 设计原因与限制

模型独立服务便于使用 Python/GPU 和按资源扩缩；Java 只依赖稳定协议。当前真实功能链已验证，但仍需统一跨层配置并建立 CPU/GPU 延迟、吞吐、质量和降级基线。
