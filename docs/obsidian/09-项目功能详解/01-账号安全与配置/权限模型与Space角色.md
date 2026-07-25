---
title: 权限模型与 Space 角色
type: feature-detail
status: maintained
updated: 2026-07-26
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

## 统一主体与三层权限

`AccessSubject` 统一表示 Web 用户、API Key 和系统任务；API Key/系统任务始终绑定一个当前有效用户，其额外 Scope、预算或任务约束只能收紧，不能扩大用户资源权限。

1. 身份层：JWT、API Key 或服务 Token 确认主体及绑定 userId。
2. 平台层：`users.role=ADMIN` 只授予平台管理接口能力，不授予私人数据访问权。
3. 资源层：个人数据所有权、`space_member` 关系或 `admin_resource_grant` 显式授权决定具体动作。

个人文件以 `user_file.userId` 为所有权边界。公开分享以有效 `shareCode` 为显式授权边界。普通 ADMIN 默认不能读取任意个人文件或 Space；部署所有者可以越权访问，但每次统一授权判定都会触发独立审计钩子。

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

- `AuthorizationService`：统一主体、资源类型和动作的 deny-by-default 判定。
- `AdminResourceGrantService/Controller`：用户或实际 Space OWNER 创建、查询和即时撤销普通 ADMIN 的 READ/DOWNLOAD 授权。
- `admin_resource_grant`：按管理员、资源和动作保存显式授权；并发重复授权由唯一约束收敛。
- SpacePermissionService：requireMember、requireAdmin 等权限守卫。
- SpaceMemberService/Controller：成员列表、添加、改角色和移除。
- AdminPermissionService：平台管理员守卫。
- space、space_member：Space 与成员角色。
- user_file、space_file：资源所有权与空间归属。
- SpaceMemberController、SpaceFileController、SpaceRagController、KnowledgePipelineController：受保护入口。

## 为什么 Service 仍要校验

前端隐藏按钮只是体验，不是安全；Controller 注解也无法验证“这个 spaceFileId 是否属于传入 spaceId”。业务 Service 必须在实际读写前验证资源关系，Mapper 查询最好同时带 spaceId/userId 条件，防止 IDOR。

## 错误语义

## 用户组与细粒度权限

V25 增加用户组、组授权、用户归组、单用户三态覆盖和权限审计。V33 删除普通 ADMIN 的权限解析豁免：部署所有者可以绕过平台能力开关，其他账号按“单用户覆盖 → 用户组授权 → 默认拒绝”计算；新用户和历史未归组用户自动进入默认用户组。首批受控能力包括上传、下载、知识库使用和向知识库添加文件。管理员可以创建用户与非系统用户组、调整归组、配置组权限，并对单个用户设置继承/允许/禁止。

权限判断由 `UserPermissionInterceptor` 和 Service 端资源校验共同完成，前端禁用控件不构成安全边界。权限管理弹窗使用 `minmax(0, 1fr) 150px` 双列 Grid，窄屏切换单列，避免通用表单选择框宽度把中文说明压缩成逐字竖排。

- 401：没有有效身份。
- 403：身份有效但没有所需角色。
- 404：资源不存在或不应向调用者暴露。
- 409：资源状态冲突，如重名、活动任务或所有者保护。

## 资源授权矩阵

| 主体 | 自有个人数据 | 他人个人数据 | 已加入 Space | 未加入 Space |
|---|---|---|---|---|
| USER | 允许 | 拒绝 | 按 Space 角色 | 拒绝 |
| 普通 ADMIN | 允许 | 默认拒绝；用户显式 READ/DOWNLOAD 后允许相应动作 | 按 Space 角色 | 默认拒绝；实际 OWNER 显式授权后只读 |
| 部署所有者 | 允许 | 允许并审计 | 允许并审计或按成员关系 | 允许并审计 |
| API Key / 系统任务 | 按绑定用户当前权限并叠加自身限制 | 不扩大绑定用户权限 | 不扩大绑定用户权限 | 不扩大绑定用户权限 |

## 受保护入口清单

- 个人文件：普通 `/api/file/**` 始终使用当前用户；跨用户只读使用 `/api/admin/resource-access/**`，Controller 和 `FileService` 双重调用统一授权。
- Space、知识和 Tool：`SpacePermissionService` 是成员、管理员资源授权和部署所有者越权的统一入口；Workflow 内置 Tool 复用该服务。
- 授权管理：`/api/resource-grants` 只允许用户授权自己的私人资源，或实际 Space OWNER 授权该 Space；撤销后下一次数据库判定立即生效。
- 平台能力：`UserPermissionInterceptor` 只处理云盘/上传/下载/知识能力开关，不能替代具体资源授权。
