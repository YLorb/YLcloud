---
title: Qdrant 向量索引与一致性
type: feature-detail
status: maintained
updated: 2026-07-16
tags:
  - ylcloud
  - rag
  - qdrant
  - vector
  - indexing
  - consistency
  - distributed-transaction
---

# Qdrant 向量索引与一致性

## 产品目标

Qdrant 提供低延迟语义召回，但它只是可重建索引。系统必须保证只有完整、属于当前 Space、状态有效的点才能进入检索，部分写入和孤儿点不能对用户可见。

## 点模型

每个有效 chunk 对应一个 point，vector 为 embedding，payload 保存 spaceId、documentId、spaceFileId、chunkId、fileUuid/hash、chunkIndex 等过滤与定位字段。QdrantCollectionInitializer 创建固定维度、距离度量和必要 payload index。

## 写入协议

1. 文档进入 BUILDING。
2. 生成全部合法向量。
3. QdrantVectorStoreService 按文档清理旧候选并 upsert 新 points。
4. 按 documentId 精确 count，必须等于有效 chunk 数。
5. RagIndexTransactionService 在单个 MySQL 事务启用 ref，并提交 document SUCCESS/ACTIVE。
6. 任一失败转 CLEANUP_PENDING。

## 检索过滤

向量查询按授权 spaceId 过滤；返回 point 后仍映射到 MySQL 有效 chunk。其他 BM25/Keyword/Metadata 路由的 SQL 也要求 document SUCCESS/ACTIVE、ref/chunk/file 启用，因此 Qdrant 孤儿点和数据库缓存 chunk 不会绕过业务状态。

## 对账与补偿

RagIndexConsistencyService：

- reconcileOnStartup 在应用启动后检查；
- reconcile 按周期运行并防止重入；
- validateActiveDocuments 比较 chunk_count、active ref 和 point count；
- invalidateAndCleanup 先隔离异常文档；
- cleanupDocumentIfPending/cleanupDocument 幂等删除向量和 ref。

## 为什么精确计数

upsert API 成功只能说明请求被接受，不能证明 N 个点全部满足预期 payload。按 documentId 精确计数是提交 ACTIVE 前的外部事实校验；在高并发重建中还需配合文档版本/CAS，防止旧任务通过计数后覆盖新结果。

## 版本风险

Java client 1.17.0 与 server 1.15.4 当前存在兼容警告。升级数据卷必须按 Qdrant 支持路径迁移并备份，不能让新 server 直接跨多个版本打开旧数据。模型变化也应使用新 collection 或全量重建。
