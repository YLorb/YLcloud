---
title: Space 文件管理与 Web 链接导入
type: feature-detail
status: maintained
updated: 2026-07-16
tags:
  - ylcloud
  - space
  - file-storage
  - import
  - web-link
  - rag
---

# Space 文件管理与 Web 链接导入

## 产品目标

团队可以直接上传文件，也可以把个人网盘文件或 Web 内容导入 Space。导入后文件自动进入版本和 RAG 生命周期，用户不需要手动重复创建知识库文档。

## 三类来源

- Space 直接上传：对象写入 MinIO，创建 file_info、space_file 和初始版本。
- 个人文件导入：复用既有 file_info，创建新的 space_file 引用，不转移个人所有权。
- Web 链接导入：保存链接及抓取/抽取后的可索引内容，建立 Space 文件语义。

## 流程

SpaceFileController 接收 list/tree/upload/import/import-web、folder、delete、preview/download、versions/restore 等请求。SpaceFileService 首先通过 SpacePermissionService 校验，再维护目录、重名和物理引用；成功提交后调用 SpaceRagService.handleFileImported 创建 RAG 文档与异步索引任务。

~~~mermaid
flowchart LR
  Source[上传/个人文件/Web] --> SF[space_file]
  SF --> V[file_version v1]
  SF --> RD[space_rag_document]
  RD --> Task[RAG 索引任务]
~~~

## ID 语义

space_file.ID 是 Space 逻辑节点；fileId 指向 file_info 物理内容。RAG document 绑定 Space 逻辑文件，chunk 缓存可按物理内容复用。下载和删除入口必须使用正确 ID，避免把物理 ID 当作授权对象。

## 删除联动

移除 Space 文件先让 RAG 文档进入隔离/清理流程，删除 active ref 和 Qdrant point，再减少物理文件引用。若个人节点或版本仍引用内容，MinIO 对象必须保留；最后引用归零才入物理清理任务。

## 设计原因

把导入设计成“新增引用”而不是“复制所有字节”，降低容量和时间成本。把 RAG 触发放在事务提交后，避免索引线程读取尚未提交的 Space 文件；异步任务持久化，失败可在知识库页面重试。
