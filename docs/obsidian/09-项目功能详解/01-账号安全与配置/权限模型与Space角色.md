---
title: 权限模型与 Space 角色
type: feature-detail
status: maintained
updated: 2026-07-16
tags:
  - ylcloud
  - authorization
  - rbac
  - space
  - security
  - backend
---

# 权限模型与 Space 角色

## 产品目标

权限系统既要支持个人文件私有性，也要支持团队 Space 协作和平台管理。用户看见某个 ID 不代表有权访问它；下载、版本、RAG 查询和画像配置都必须按资源重新授权。

## 三层权限

1. 身份层：JWT 确认当前 userId。
2. 平台层：users.role=ADMIN 才能访问平台管理接口。
3. Space 资源层：space_member 中 OWNER、ADMIN、MEMBER 决定操作范围。

个人文件以 user_file.userId 为所有权边界。公开分享以有效 shareCode 为显式授权边界。平台 ADMIN 不应被自动等同为每个 Space 的 OWNER，除非业务规则明确允许。

## Space 角色

| 能力 | OWNER | ADMIN | MEMBER |
|---|---:|---:|---:|
| 查看/使用 Space | 是 | 是 | 是 |
| 上传与管理文件 | 是 | 是 | 按当前规则允许的协作操作 |
| 修改 RAG/画像配置 | 是 | 是 | 否 |
| 管理成员与角色 | 是 | 是，受 OWNER 保护 | 否 |
| 删除/转移 Space | 是 | 否 | 否 |

具体判断集中在 SpacePermissionService、SpaceMemberService、AdminPermissionService，避免各 Controller 自行复制角色字符串。

## 请求校验顺序

推荐顺序是：认证 → 资源是否存在 → 当前用户与资源关系 → 所需角色 → 业务状态。对外错误需要兼顾准确性和信息泄露，例如跨用户原文件可返回“资源不存在或已失效”，避免证明目标 ID 存在。

## 核心代码与数据

- SpacePermissionService：requireMember、requireAdmin 等权限守卫。
- SpaceMemberService/Controller：成员列表、添加、改角色和移除。
- AdminPermissionService：平台管理员守卫。
- space、space_member：Space 与成员角色。
- user_file、space_file：资源所有权与空间归属。
- SpaceMemberController、SpaceFileController、SpaceRagController、KnowledgePipelineController：受保护入口。

## 为什么 Service 仍要校验

前端隐藏按钮只是体验，不是安全；Controller 注解也无法验证“这个 spaceFileId 是否属于传入 spaceId”。业务 Service 必须在实际读写前验证资源关系，Mapper 查询最好同时带 spaceId/userId 条件，防止 IDOR。

## 错误语义

- 401：没有有效身份。
- 403：身份有效但没有所需角色。
- 404：资源不存在或不应向调用者暴露。
- 409：资源状态冲突，如重名、活动任务或所有者保护。
