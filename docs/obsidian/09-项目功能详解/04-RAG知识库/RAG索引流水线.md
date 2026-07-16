---
title: RAG 索引流水线
type: feature-detail
status: maintained
updated: 2026-07-16
tags:
  - ylcloud
  - rag
  - indexing
  - document-parsing
  - chunking
  - embedding
  - qdrant
  - state-machine
---

# RAG 索引流水线

## 产品定义

文件加入知识库的基础处理链是“文档 → 解析 → 切分 → embedding → 索引”。只有全部成功并完成一致性校验，文档才是 RAG_READY。知识画像位于该链路之后，是默认开启但可关闭的增强功能，不决定基础 RAG 成功。

## 触发方式

- Space 文件上传或导入后，SpaceRagService.handleFileImported 自动创建文档和任务。
- rebuildFile/rebuildSpace 显式重建。
- retryTask/retryFailedTasks 重试失败任务。
- repairFileVectors/repairSpaceVectors 修复向量一致性。
- 删除 Space 文件触发独立删除索引任务。

RagTaskExecutorService 使用 ragTaskExecutor 异步执行文件、空间、修复和删除任务；dispatchAfterCommit 保证数据库事务提交后才启动线程。

## 索引步骤

~~~mermaid
flowchart TD
  A[创建 PENDING task/document] --> B[CAS task RUNNING]
  B --> C[读取 Space 文件与物理对象]
  C --> D[DocumentParser 解析]
  D --> E{正文与质量合格?}
  E -->|否| X[FAILED + CLEANUP_PENDING]
  E -->|是| F[StructuredChunker 切分]
  F --> G{chunk 非空?}
  G -->|否| X
  G -->|是| H[Embedding]
  H --> I{数量/维度/有限值合格?}
  I -->|否| X
  I -->|是| J[Qdrant upsert]
  J --> K{精确 point count 一致?}
  K -->|否| X
  K -->|是| L[MySQL 事务启用 refs + SUCCESS/ACTIVE]
  L --> M[RAG_READY]
  M --> N{画像开关最新值}
  N -->|开启| P[提交独立画像任务]
  N -->|关闭| S[记录 PROFILE_SKIPPED_DISABLED]
~~~

## 核心函数

- SpaceRagService.executeFileRagTask、executeSpaceRagTask。
- rebuildDocument：单文档主编排。
- ensureFileChunks：解析、切分和内容缓存。
- RagIndexTransactionService：begin/build success/cleanup 的事务转移。
- QdrantVectorStoreService：upsert、count、delete、search。
- RagIndexConsistencyService：启动/定时对账。
- submitKnowledgeProfileTask/submitKnowledgeProfileSpaceTask：RAG 后可选增强。

## 成功硬门槛

SUCCESS/ACTIVE 必须同时满足正文非空、chunk 非空、embedding 与 chunk 一一对应、向量维度正确、无 NaN/Infinity、Qdrant 点数精确一致、active ref 与 chunk_count 一致。任何 metadata fallback 或空白 chunk 都不得绕过。

## 为什么缓存 chunk

file_rag_chunk 按物理内容缓存解析切片，多个 Space 可复用；space_rag_chunk_ref 决定某个 Space 文档当前是否启用这些 chunk。缓存存在不代表可检索，检索必须经过 active ref 与文档状态过滤。

## 失败语义

解析、模型或 Qdrant 失败会让本次任务 FAILED，文档进入不可检索/清理路径。失败不会返回伪 success，也不会启动画像。重试先清理或隔离旧中间状态，再重新构建。
