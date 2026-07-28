---
title: 用户认证与 JWT
type: feature-detail
status: maintained
updated: 2026-07-25
tags:
  - ylcloud
  - authentication
  - jwt
  - security
  - backend
  - frontend
---

# 用户认证与 JWT

## 产品目标

认证负责回答“请求是谁发出的”，并为个人网盘、Space、知识库和管理功能提供统一身份。注册后用户应能立即登录、获得个人 Space，并且任何错误都不能泄露密码或内部堆栈。

## 原理与流程

注册入口 Sign 接收必要字段，SignService 对用户名等业务规则进行校验，密码使用 BCrypt 单向哈希后写入 users。V20 的注册锁串行化所有用户创建路径；V32 为首个普通注册者建立唯一、不可转移的部署所有者身份，避免并发首注册产生多个所有者。

登录入口 Login 调用 LoginService 校验 BCrypt hash，成功后 JwtUtil 签发包含用户身份与过期信息的 token。前端 AuthPage 保存会话并在 api.ts 请求中添加 Authorization。JwtTokenInterceptor 在受保护路径验证签名、过期时间和用户身份，把当前用户传给 Controller。

~~~mermaid
sequenceDiagram
  participant UI as AuthPage
  participant API as Login/Sign
  participant S as LoginService/SignService
  participant DB as users
  UI->>API: 注册或登录
  API->>S: 校验输入
  S->>DB: 写入 BCrypt hash / 查询用户
  S-->>API: 用户身份
  API-->>UI: JWT
  UI->>API: Authorization: Bearer token
  API->>API: JwtTokenInterceptor 校验
~~~

## 核心代码

| 职责 | 类/函数 |
|---|---|
| 注册协议 | controller.Sign |
| 登录协议 | controller.Login |
| 当前用户 | UserController |
| 注册业务 | SignService |
| 登录校验 | LoginService |
| Token | JwtUtil |
| 请求拦截 | JwtTokenInterceptor、WebConfig |
| 部署所有者 | SignService、V32 所有者约束 |
| 前端 | features/auth/AuthPage.tsx、api.ts |

## 设计原因

JWT 适合当前单体 API 和前端部署，不需要每次读取服务端 Session；但它只解决身份，不解决资源权限。密码永远不写入 JWT、响应 DTO 或日志。首管理员规则放到数据库可串行化范围，而不是用进程内 synchronized，因为多实例和重启不能共享 Java 锁。

## 错误与安全

## 部署所有者初始化

空库中的第一个成功注册用户在注册守卫锁保护下获得 `ADMIN` 和唯一部署所有者身份，后续用户为 `USER`。用户、根目录、默认个人空间和所有者标识写入处于同一事务，避免并发注册产生多个“首用户”。存量环境由 V32 选择最小 `user_id`，恢复为启用状态并设为所有者。系统不再接受 Bootstrap 管理员环境变量。

- 参数错误返回 400，未认证或 token 无效返回 401。
- 登录失败使用通用提示，避免枚举用户名。
- 日志只记录 username 等非敏感关联字段。
- 生产必须使用足够强的 JWT Secret、文件型注入和轮换计划。
- 用户被禁用或权限变化时，仍需在业务访问处重新读取必要状态，不能无限信任旧 token。
