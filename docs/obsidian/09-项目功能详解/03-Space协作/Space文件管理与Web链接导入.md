---
title: Space 文件管理与 Web 链接导入
type: feature-detail
status: maintained
updated: 2026-08-16
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

团队可以像使用个人网盘一样按目录浏览和组织 Space 文件，也可以直接上传文件，或把个人网盘文件与 Web 内容导入当前目录。普通文件进入 Space 后默认进入版本和 RAG 生命周期，用户不需要手动重复创建知识库文档；文件夹只负责组织，不参与知识化。

个人网盘与 Space 保留两套逻辑模型。共享的是 File Explorer 的目录导航和操作体验，不是节点所有权与授权规则：个人网盘按用户所有权授权，Space 按成员角色授权。

## 三类来源

- Space 直接上传：对象写入 MinIO，创建 file_info、space_file 和初始版本。
- 个人文件导入：复用既有 file_info，创建新的 space_file 引用，不转移个人所有权。
- Web 链接导入：保存链接及抓取/抽取后的可索引内容，建立 Space 文件语义。

## 流程

SpaceFileController 接收 list/tree/upload/import/import-web、folder、delete、preview/download、versions/restore 等请求。SpaceFileService 首先通过 SpacePermissionService 校验，再维护目录、重名和物理引用；成功提交普通文件后调用 SpaceRagService.handleFileImported 创建 RAG 文档与异步索引任务。

前端以 `spaceId + parentId` 表示当前目录，展示当前层子节点和面包屑。创建文件夹、直接上传及个人文件导入都携带当前 `parentId`。共享 File Explorer 通过 Space 数据适配器调用这些接口，不能复用 Personal 的所有权判断代替 SpacePermissionService。

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

Space 文件数量增长后，全量平铺会丢失主题层级并增加浏览和定位成本。复用 Personal 的文件树交互可以保持产品一致性，而保留 `space_file` 则能继续承载多人协作、贡献者归因和独立知识生命周期。

当前阶段文件可见性仍按 Space 角色控制，不开放文件级 ACL。架构仅预留统一文件可见性判定边界；未来启用文件/文件夹 ACL 后，文件列表、知识检索、AI 问答与引用必须使用同一后端判定，不能只在前端隐藏文件。
