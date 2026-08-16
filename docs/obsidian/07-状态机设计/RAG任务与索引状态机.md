---
title: RAG 任务与索引状态机
type: state-machine
status: maintained
updated: 2026-07-16
tags:
  - ylcloud
  - state-machine
  - rag
  - embedding
  - qdrant
  - consistency
---

# RAG 任务与索引状态机

RAG 同时存在任务状态、文档业务状态和向量资源状态。

## 任务状态

~~~mermaid
stateDiagram-v2
  [*] --> PENDING
  PENDING --> RUNNING: CAS 抢占
  RUNNING --> SUCCESS: 所有目标文档达到硬门槛
  RUNNING --> FAILED: 失败数或异常
  RUNNING --> FAILED: 超时回收且仍为 RUNNING
  FAILED --> PENDING: 用户重试创建新任务
~~~

任务保存 totalCount、successCount、failedCount、progress 和 errorMessage。Space 级任务允许部分文档失败，但最终结果必须准确反映计数。

## 文档与向量状态

~~~mermaid
stateDiagram-v2
  [*] --> CLEAN
  CLEAN --> BUILDING: 开始索引
  BUILDING --> ACTIVE: 向量计数校验 + MySQL 原子激活
  BUILDING --> CLEANUP_PENDING: 任一步失败
  ACTIVE --> BUILDING: 重建新版本
  ACTIVE --> CLEANUP_PENDING: 删除或对账不一致
  CLEANUP_PENDING --> CLEANING: 清理执行者 CAS
  CLEANING --> CLEAN: ref 与向量均清理
  CLEANING --> CLEANUP_PENDING: 清理失败待重试
~~~

文档业务 status 的 SUCCESS/FAILED 与 vector_state 配合：只有 status=SUCCESS 且 vector_state=ACTIVE 的文档可用。BUILDING、CLEANUP_PENDING、CLEANING、CLEAN 均不能进入检索。

## ACTIVE 守卫

- 解析正文非空；
- 结构化 chunk 非空且内容非空白；
- embedding 数量等于 chunk 数；
- 每个向量维度匹配且数值有限；
- Qdrant 按 documentId 精确 point count 等于有效 chunk 数；
- MySQL 同一事务启用全部 chunk ref、写 chunk_count、status=SUCCESS、vector_state=ACTIVE；
- 提交使用文档版本/CAS，拒绝过期任务覆盖新结果。

## 对账转移

RagIndexConsistencyService 比较 document.chunk_count、active ref count 和 Qdrant point count。ACTIVE 文档任一不匹配，先转 CLEANUP_PENDING 隔离，再删除 ref/vector。这样异常数据不会继续被 BM25、关键词、向量、Metadata 或引用链路使用。

## 与知识画像的边界

RAG 达到 ACTIVE 后基础流程已经成功。随后读取知识画像开关，可能创建独立画像任务，也可能记录 PROFILE_SKIPPED_DISABLED。画像结果不能反向修改 RAG 状态。
