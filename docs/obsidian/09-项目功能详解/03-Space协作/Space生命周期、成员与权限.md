---
title: Space 生命周期、成员与权限
type: feature-detail
status: maintained
updated: 2026-07-26
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

SpaceController 与 SpaceService 提供创建、查询、更新、版本设置、所有权转让和退出。创建者成为 OWNER，并在 `space_member` 建立成员关系。

Space 显式使用 `ACTIVE → DISSOLVING → DISSOLVED` 状态机。仅剩 OWNER 的 TEAM Space 在退出时必须勾选解散确认并准确输入 Space 名称，事务内进入 `DISSOLVING` 并写入 `space_dissolution_outbox`；后续异步清理完成后才能进入 `DISSOLVED`。旧 `DELETE /api/space/{id}` 不能绕过高风险确认。

PERSONAL Space 是永久私有资源：每个有效用户只能有一个，不能邀请成员、转让、退出、删除或转换为 TEAM。成员列表、名称和知识设置仍可按所有者权限使用。

## 成员管理

SpaceMemberController/SpaceMemberService 提供列表、添加、改角色和移除。关键规则包括：

- 操作者必须是 OWNER 或 ADMIN；
- TEAM Space 的有效 OWNER 始终恰好一名；
- ADMIN 不能越权修改受保护 OWNER；
- 所有权转让对 Space 行加锁，并在同一事务内降级旧 OWNER、提升新 OWNER 和更新 `spaces.owner_id`；
- 数据库生成列唯一键阻止并发转让产生双 OWNER；
- 有其他成员时 OWNER 必须先转让才能退出，普通成员可直接退出；
- 重复邀请复用原成员记录并恢复为有效状态；
- MEMBER 只能使用获授权功能。

## 为什么 Space 是知识库边界

RAG 检索必须按 spaceId 过滤，知识画像开关和 topK/temperature 也按 Space 保存。这样团队权限直接约束文档、向量、引用和会话，不需要再维护一套容易漂移的“知识库成员”关系。

## 核心代码与表

- SpaceController、SpaceMemberController。
- SpaceService、SpaceMemberService、SpacePermissionService。
- SpaceMapper、SpaceMemberMapper。
- `spaces`、`space_member`、`space_dissolution_outbox`、`space_rag_config`。
- 前端 SpacesView、KnowledgeBaseView、KnowledgeSettingsView。

## 失败处理

资源不存在 404，非成员或角色不足 403，所有权/状态冲突和未完成解散确认返回 409。进入 `DISSOLVING` 后，成员授权入口立即停止暴露该 Space；Outbox 负责把派生资源清理交给后续可靠任务，失败不回退为可用状态。
