---
title: Space 生命周期、成员与权限
type: feature-detail
status: maintained
updated: 2026-07-16
tags:
  - ylcloud
  - space
  - collaboration
  - rbac
  - authorization
---

# Space 生命周期、成员与权限

## 产品目标

Space 是团队协作、知识库配置和授权的统一边界。用户注册后可以拥有个人 Space，也可以被邀请进入团队 Space；不同角色拥有可解释的管理能力。

## 生命周期

SpaceController 与 SpaceService 提供创建、查询、更新、删除和版本设置。创建者成为 OWNER，并在 space_member 建立成员关系。删除 Space 前要处理文件引用、RAG 文档、向量、画像、会话作用域和活动任务，不能只删一行 space。

## 成员管理

SpaceMemberController/SpaceMemberService 提供列表、添加、改角色和移除。关键规则包括：

- 操作者必须是 OWNER 或 ADMIN；
- 不能无意移除最后 OWNER；
- ADMIN 不能越权修改受保护 OWNER；
- 重复成员使用唯一约束并返回冲突；
- MEMBER 只能使用获授权功能。

## 为什么 Space 是知识库边界

RAG 检索必须按 spaceId 过滤，知识画像开关和 topK/temperature 也按 Space 保存。这样团队权限直接约束文档、向量、引用和会话，不需要再维护一套容易漂移的“知识库成员”关系。

## 核心代码与表

- SpaceController、SpaceMemberController。
- SpaceService、SpaceMemberService、SpacePermissionService。
- SpaceMapper、SpaceMemberMapper。
- space、space_member、space_rag_config。
- 前端 SpacesView、KnowledgeBaseView、KnowledgeSettingsView。

## 失败处理

资源不存在 404，非成员或角色不足 403，重复成员/活动名称冲突 409。涉及删除的操作应先阻止新任务，再异步清理派生资源，并保留可恢复审计。
