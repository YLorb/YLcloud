# 第一层 API Gateway

API Key 的接口权限、用量查询、异常核对与部署顺序详见 [API_KEY.md](API_KEY.md)。

实现范围：现有对外 API 路由、每 IP 共享滑动窗口限流、浏览器 Session 与 Open API Key 入口校验、可信身份传递及下载模式配置。Open API 支持真实模型 Token 流水和用户月度配额，不扣金额。Workflow 不接入，服务间 HTTP 地址保持直连，不新增用户 JWT。

## 架构与版本

- Java 17 / Spring Boot 4.0.9 / Spring Cloud 2025.1.4，独立 parent/BOM；现有后端仍为 Boot 3.3.5。
- 前端 Nginx → Gateway `8088` → cloud-server `8080`。
- `/api/**` 保留路径和请求方法。`/s/:code`、`/share/:code`、`/files` 为前端页面，仍由 Nginx 提供。
- 模型、解析、Sandbox 和后端 `/internal/**` 等内部接口没有公网代理路由；管理 Actuator 只监听本机 `8089`。
- Gateway 校验浏览器 Session 和 API Key；后端负责会话/Key 生命周期、CSRF、资源授权与模型用量结算。内部 Service JWT 保持原协议。

## Session 与下游身份

Gateway 使用 R2DBC 直接访问已有 MySQL `user_login_session` 和 `users` 表，按 Cookie 的 SHA-256 查找会话，同时检查撤销、用户 Session 版本、账户状态、绝对到期时间和最近活动。每 10 分钟更新一次活动时间，不延长原有 15 天绝对有效期。没有本地身份缓存，后续请求立即读取注销、停用和全端撤销结果。数据库查询或连接超过 2 秒、连接失败均返回 503，不转发该请求。

登录、注册、公开站点设置、旧分享及公开分享入口保留公开访问；公开分享可附带有效 Session，其密码、强制登录和资源授权仍由分享服务检查。`/api/v1/**`、`/api/open/**` 保留现有 API Key 协议。其他 API 在 Gateway 缺少有效 Session 时返回 401。

网关会覆盖外部 `X-YLCloud-Identity`，生成内部 HMAC-SHA256 身份证明，绑定方法、原始路径/查询、Session Cookie 摘要和 15 秒有效期。它只用于网关与后端通信，不是客户端 JWT。后端校验证明后使用该身份，不重复查询 Session。双方必须同步时钟与独立的 `gateway_identity_secret`；该密钥与客户端地址证明密钥分开，至少 32 字节，不随普通发布轮换。

两份 Compose 均设置后端 `YLCLOUD_GATEWAY_AUTH_REQUIRED=true`，拒绝直接访问 `/api/**`（仅 GET 公开站点设置保留健康检查例外）。内部 `/internal/**` 保留原有认证。手工独立启动后端时该开关默认为 false，以兼容单服务开发；正式接入必须启用。浏览器 Session Cookie 的 Secure 设置必须在网关和后端一致。

镜像部署 Compose 两端默认 `YLCLOUD_SESSION_COOKIE_SECURE=true`，读取 `__Host-ylcloud_session`；本地开发 Compose 两端默认 false，读取 `ylcloud_session`。显式覆盖该变量会同时作用于两端。强制网关认证时，后端拒绝路径中的分号参数（含编码形式），并将容器规范化后的 API 路径纳入验签范围；签名仍绑定原始 URI 和查询参数。

## 限流契约

每个可信客户端 IP 在所有受限 API 间共享同一个额度：任意连续 60 秒最多放行 60 个 HTTP 请求。轮询、上传分片、登录及失败的业务请求均计入；被网关限流拒绝的请求不计入已放行记录。静态前端资源和独立管理端口不消耗 API 额度。

`sliding-window.lua` 使用 Redis TIME、ZSET 和 Lua 原子操作，执行窗口清理、判断与写入。相同 IP 切换路由、Cookie、伪造转发头或请求另一个网关实例均不能获得额外额度。IPv4 映射地址会规范化。

- 正常超限：HTTP 429，返回 `Retry-After` 秒数和 `X-YLCloud-RateLimit: gateway`。
- Redis 连接错误、超时、脚本错误：HTTP 503，不向上游发出请求。
- Redis 数据首次创建或丢失：从首次请求开始拒绝 60 秒，避免历史丢失立即重置额度。持久化历史正常的 Redis 重启不要求额外等待；网关进程重启不重置额度。
- Redis 使用 AOF、`appendfsync always` 和 `noeviction`。持久化及限流开销须在目标机器上容量验证；本地并发测试不是吞吐承诺。
- 相同 NAT 出口用户共享额度。前端仅对带网关标记的 429 进入全局冷却；登录等业务 429 不阻塞其他 API。上传初始化、状态查询、分片及合并均按 `Retry-After` 有限重试，503 不自动重试。

## 客户端地址与网络

网关默认只信任 TCP 对端；配置 `YLCLOUD_GATEWAY_TRUSTED_PROXIES` 后，仅列出的入口代理可通过单值 `X-Real-IP` 传入真实 IP。入口必须覆盖该头，不能拼接用户输入。

Compose 为前端分配独立 edge 网络地址 `172.31.241.2`，动态地址池限定为 `172.31.241.128/25`，避免先启动的网关抢占前端固定地址。网关只信任前端地址；前端不接入后端 default 网络。调整网络时需同时检查 `YLCLOUD_EDGE_SUBNET`、`YLCLOUD_EDGE_DYNAMIC_RANGE` 与 `YLCLOUD_EDGE_FRONTEND_IP`，固定地址必须在子网内且在动态地址池外。网关没有宿主机映射端口，后端与基础设施的调试端口仅绑定 `127.0.0.1`。

Nginx 使用 Docker DNS `127.0.0.11` 动态解析网关，缓存有效期 5 秒；网关重建改变 IP 后无需重启前端。地址切换期间的在途请求可能失败，后续请求刷新解析后恢复。变量形式的代理保留原始 URI、查询参数和请求体，配置语义见 [Nginx proxy_pass](https://nginx.org/en/docs/http/ngx_http_proxy_module.html#proxy_pass) 与 [resolver](https://nginx.org/en/docs/http/ngx_http_core_module.html#resolver)。

网关清理客户端身份和转发头，向后端写入地址与独立共享凭据。后端 `GatewayClientAddressFilter` 只在凭据验证通过后覆盖 `remoteAddr`，以保留原登录来源限流与分享访问记录。该凭据只证明地址来自网关，不替代用户认证。

HTTPS 在部署入口终止，内部 HTTP 通信。若 TLS 终止器在前端 Nginx 之前，挂载专用配置到 `/etc/nginx/trusted-proxy.conf`，只信任其实际地址：

```nginx
set_real_ip_from 192.0.2.10;
real_ip_header X-Forwarded-For;
real_ip_recursive on;
```

这里的地址仅为示例，必须替换。公网 Cookie 保持 Secure；不用为了内部 HTTP 关闭 Secure。上游重写 URL、TLS 证书和真实代理地址需在目标部署环境验证。

## 下载模式

Flyway `V64__download_delivery_mode.sql` 增加设置 `storage.downloadMode`，默认 `proxy`。管理员通过现有后台设置页面与 `/api/admin/settings` 修改，全站生效；普通用户不能修改。

- `proxy`：授权后通过既有文件 API 流式传输，外部请求经过网关，不向客户端交付对象存储直链。
- `direct`：授权后签发短期 MinIO 链接，最长 300 秒。单文件分享使用已批准清单中的对象版本，签名时限不超过分享剩余有效期；数据导出先校验归属、状态和过期时间。
- 前端 AJAX 下载先取得签名 URL 再交给浏览器导航，避免跨域 fetch 文件；原生下载入口使用 303 转向签名 GET。
- 文件夹 ZIP 是动态生成内容，仍经原授权接口流式传输。预览与内部解析使用的存储请求保持现有行为。
- 已签发直链在到期前可能继续有效，切回代理或撤销分享不会立即撤回该链接；直链文件传输不经过网关限流。

开启 `direct` 前在后端配置 `YLCLOUD_MINIO_PUBLIC_ENDPOINT`，例如 `https://objects.example.com`，以及正确的 `YLCLOUD_MINIO_REGION`（默认 `us-east-1`）。外部地址必须是独立 HTTP(S) origin，不能带路径前缀、查询参数或用户信息。签名直接使用外部 Host，不能事后字符串替换内部 URL。后台会拒绝在未配置外部地址时启用直链。

## 配置与启动

先生成独立凭据，不覆盖已有文件：

```powershell
./scripts/init-gateway-secrets.ps1
```

在 Linux 上可使用：

```sh
umask 077
test -s config/secrets/redis_password || openssl rand -hex 32 > config/secrets/redis_password
test -s config/secrets/gateway_forwarding_secret || openssl rand -hex 32 > config/secrets/gateway_forwarding_secret
test -s config/secrets/gateway_identity_secret || openssl rand -hex 32 > config/secrets/gateway_identity_secret
```

两份 Compose 均已接入 gateway 与 gateway-redis；CI 增加独立网关测试和镜像构建。原有其他部署凭据继续按项目说明准备。

身份数据库参数为 `YLCLOUD_GATEWAY_SESSION_DB_URL`（R2DBC URL）、`YLCLOUD_GATEWAY_SESSION_DB_USER`、`YLCLOUD_GATEWAY_SESSION_DB_PASSWORD`，Session 与 API Key 共用连接池。Compose 默认使用现有应用账户；专用网关账户需 SELECT `users`、`user_login_session`、`user_api_key`、`api_key_scope`、`site_setting`，以及 UPDATE `user_login_session.last_seen_at`。API Key 时间字段沿用后端应用本地时间语义，网关与后端须保持相同 JVM 时区（Compose 均为 UTC），避免使用 MySQL 会话时区解释期限。非 Compose 可通过 `/run/secrets/gateway_session_db_password` 挂载密码文件。网关不执行 Flyway，先由后端完成迁移。

全新 MySQL 数据目录首次初始化较慢，Compose 为 MySQL 保留 300 秒启动宽限期。现有 V55 分享迁移需要创建触发器：启用 binary log 时，仅拥有应用数据库权限的账户可能遇到 MySQL 1419。首次部署前应由数据库管理员确认迁移账户和触发器创建策略；本次隔离验收通过专用测试实例的 `log_bin_trust_function_creators=1` 执行，未修改正式数据库权限。已有 V55 成功记录的数据库不需要重跑该迁移。

```sh
docker compose --env-file config/.env -f config/docker-compose.yml up -d --build
```

Redis 默认持久化到项目 `.data/gateway-redis`，在本机即 D 盘目录；可通过 `YLCLOUD_GATEWAY_REDIS_DATA_DIR` 指定其他目录。Docker 自身镜像及磁盘镜像的位置由 Docker Desktop 设置决定，本实现未移动已有 Docker 数据。

本地无 Docker 开发时，Redis 启动在独立端口；后端和网关设置同一个 `YLCLOUD_GATEWAY_FORWARDING_SECRET`。网关的 Redis 地址/端口/密码分别由 `YLCLOUD_REDIS_HOST`、`YLCLOUD_REDIS_PORT`、`YLCLOUD_REDIS_PASSWORD` 设置。Vite 代理目标已改为 `127.0.0.1:8088`；使用 Vite 时可将 `YLCLOUD_GATEWAY_TRUSTED_PROXIES` 设为 `127.0.0.1,::1`，Vite 会覆盖客户端地址头。

同时配置网关 Session 数据库、双方相同的 `YLCLOUD_GATEWAY_IDENTITY_SECRET` 和 `YLCLOUD_SESSION_COOKIE_SECURE`，并在后端启用 `YLCLOUD_GATEWAY_AUTH_REQUIRED=true`。仅限显式兼容调试时，可同时将网关 `YLCLOUD_GATEWAY_SESSION_ENABLED=false` 和后端认证开关设为 false，恢复原后端 Session 校验。

```sh
mvn -f src/cloud-gateway/pom.xml package
java -jar src/cloud-gateway/target/cloud-gateway-1.0-SNAPSHOT.jar
```

上传大小限制由 Nginx 与后端协同执行；Nginx 设置为 2064 MiB 以覆盖原 2 GiB 文件加 multipart 开销。网关不读完整请求体，不自动重试有副作用的业务请求，Nginx 关闭请求及响应缓冲。

## 发布与回滚

`scripts/deploy.sh` 拉取 Gateway/Redis 镜像，首次启动 Redis，依次升级后端、网关、前端并检查健康与公开 API；冷 Redis 的 60 秒保护期纳入就绪等待。已有 Redis 保留数据和容器，Redis 镜像升级需单独安排。

部署脚本通过统一入口调用管理员 API。配置 `YLCLOUD_DEPLOY_FRONTEND_URL`、`YLCLOUD_DEPLOY_COOKIE_FILE`（管理员 curl Cookie jar）和 `YLCLOUD_DEPLOY_LOGIN_FILE`（含 `username`、`password` 的 JSON 文件，权限 600）。Session 过期或数据库回滚使其失效时，脚本从该文件重新登录一次；不要把密码放在命令行或提交到仓库。网关 429 按 Retry-After 有限重试，业务失败不盲目重放。备份和隔离恢复验证脚本也通过入口和 Cookie 发布状态，运行前提供有效管理员 Cookie。既有备份与恢复验证门禁继续适用。

失败时恢复原后端、网关、前端镜像；从尚未部署网关的版本升级失败时，自动给旧前端恢复后端网络并停止本次新建的网关/Redis，保留数据。脚本的破坏性真实备份恢复未在本次验收中执行，已覆盖模拟失败与回滚路径。

手工移除网关时，先将下载模式切回 `proxy`；将前端 Nginx 与 Vite 的 API 上游恢复为后端地址，让前端重新接入后端网络，并显式设置后端 `YLCLOUD_GATEWAY_AUTH_REQUIRED=false` 恢复原 Session 校验，再停止网关。保留 Redis 数据和新增设置记录，不删除其他服务数据。

验证结果与生产环境验收边界见 [VALIDATION.md](VALIDATION.md)。
