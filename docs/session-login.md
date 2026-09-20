# Browser Session 登录

## 已实现的约定

- 同源 Web 使用 Cookie Session，不再返回或接受原有浏览器 JWT；升级后所有用户需要重新登录。API Key 和内部 Service JWT 不变。
- 登录时生成 256 位随机凭据，浏览器仅通过 HttpOnly Cookie 保存，MySQL 仅存 SHA-256 摘要。无需 Redis 或 OAuth2。
- 绝对有效期为登录起 15 天；空闲有效期也为 15 天。两者取较早截止时间，因此持续使用也不能超过登录后的 15 天。
- 勾选“记住登录”时 Cookie 持久化 15 天；未勾选不设置 Max-Age，但服务端仍有 15 天上限。浏览器的会话恢复功能可能保留非持久 Cookie，不能保证关窗立即退出。
- 多设备独立登录。退出撤销当前 Session；“退出所有设备”、修改密码、管理员强制退出撤销全部会话。通过现有管理接口禁用/注销账号会递增会话版本，重新启用不会恢复旧会话。
- 权限与账号状态每次从服务端读取，不信任浏览器角色或身份头。已开始处理的请求不保证被随后发生的撤销中断。
- 活跃时间最多每 10 分钟更新一次；后台轮询也视为请求，但不能延长绝对有效期。不需要服务器轮询客户端。

## 接口与防护

| 接口 | 作用 |
| --- | --- |
| POST /api/login | username、password、rememberMe；用户信息 JSON + Set-Cookie，无 token 字段 |
| GET /api/session | 恢复当前身份 |
| POST /api/logout | 撤销当前会话 |
| POST /api/logout-all | 撤销自己的全部会话 |
| POST /api/session/password | currentPassword、newPassword；新密码 8–72 字符且不超过 72 UTF-8 字节；成功后重新登录 |
| POST /api/admin/users/{userId}/sessions/revoke | 管理员撤销指定用户全部会话 |

Web 写请求（含登录、注册）必须带 `X-YLCloud-Request: 1`，浏览器 `Sec-Fetch-Site` 不得是 cross-site 或 same-site。此自定义头防护依赖同源部署和不开放跨域凭据 CORS；前端与 API 由同一入口反代。SameSite=Lax 是额外保护。公开分享凭据、Open API、内部接口保持各自鉴权边界。

401 表示失效，前端清除身份并返回登录流程；403 不抹除身份；数据库错误返回 503，不删除有效 Cookie。退出请求失败也不能假装已退出。

登录限流由 MySQL 共享固定窗口计数：每 15 分钟同账号 20 次、同连接来源 200 次（包括成功请求），超限返回 429。来源取 remoteAddr，不信任客户端 X-Forwarded-For。代理后可能共享来源预算；上线前应结合可信代理和网关限流调整，不要直接信任任意转发头。

参考：[OWASP Session Management](https://cheatsheetseries.owasp.org/cheatsheets/Session_Management_Cheat_Sheet.html)、[OWASP CSRF Prevention](https://cheatsheetseries.owasp.org/cheatsheets/Cross-Site_Request_Forgery_Prevention_Cheat_Sheet.html)。

## 部署与迁移

1. 备份数据库，先在测试环境执行 Flyway V57：新增 users.session_version、user_login_session、login_rate_bucket。不要手工修改历史迁移。
2. 前后端一起升级；旧版 localStorage 凭据会被清除。旧测试脚本若读取 login.data.token 或发送 Bearer JWT，必须改为 Cookie 容器，并给写请求加上述自定义头；本次新增独立 Session 冒烟脚本，未将旧 JWT 验收脚本全部迁移。
3. 生产 HTTPS 必须设置 `YLCLOUD_SESSION_COOKIE_SECURE=true`：使用 `__Host-ylcloud_session`、Secure、HttpOnly、Path=/、无 Domain。基础配置和 hub Compose 默认 true；dev 配置、本地 Compose 默认 false，仅用于 HTTP 开发。远程部署使用 dev profile 时也必须显式覆盖为 true。
4. 未来网关须透传 Cookie/Set-Cookie、自定义请求头，保持外部同源；剥离外部身份头。暂不把权限判断移到网关。
5. 每小时批量清理绝对到期超过 30 天的会话和旧限流窗口；每批上限 1000。高吞吐环境需评估容量和清理积压。

## 验证

```powershell
mvn -pl :cloud-server -am '-Dtest=BrowserSessionServiceTest,SessionLoginTest,SessionSecurityTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
# 在 src/cloud-frontend 下运行 npm run test 和 npm run build
# 以下只针对专用测试账号，会撤销该账号所有设备会话：
./scripts/session-smoke.ps1 -BaseUrl https://test.example.com -Credential (Get-Credential) -ConfirmTestAccount
```

还需在真实 MySQL/浏览器环境验收：V57 迁移、15 天绝对/空闲边界、数据库重启后会话保留、禁用再启用不恢复旧 Cookie、修改密码跨设备失效、服务故障恢复、Cookie Secure/HttpOnly 与无 localStorage 凭据。可在隔离测试库调整会话时间模拟边界，不要修改生产数据。

本次本地 Docker 引擎未运行，真实数据库迁移、上述冒烟脚本及浏览器联调尚未执行；单元测试不能替代这些验收。

本次执行结果：后端编译通过；Session 专项 10/10 通过；前端测试 74/74 通过、生产构建通过；PowerShell 冒烟脚本语法检查通过。后端全量 380 项中 363 通过、16 跳过、1 失败：AdminSearchWiringTest 的测试上下文缺少 OfficePreviewService，该测试和 SpaceFileService 均与 HEAD 一致、未由本次修改。全量测试因此不能宣称通过。
