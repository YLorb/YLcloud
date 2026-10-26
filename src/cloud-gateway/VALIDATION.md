# Gateway 验证记录

日期：2026-10-26。数据全部为合成测试数据；未部署生产、未发送外部消息。

## API Key 与 Token 计量（最新追加）

- 后端完整回归 515 项：499 通过、16 项既有外部环境测试跳过，无失败。包含新增真实 MySQL Token 套件 8/8；验证多 Key 并发共享额度、实际 usage 结算、幂等、业务回滚、跨月、未决保留/核对、Key 撤销、真实 HTTP 模型合约桩与模型标识。
- 网关完整测试 32/32，无跳过：真实 Redis 双实例限流与 HTTP、真实 MySQL Session/BCrypt/Key 生命周期，以及显式操作授权、凭据移除、身份签名绑定和故障拒绝。
- 模型服务 pytest 19/19，另 13 个 subtest 通过；覆盖真实/缺失/非法 usage、两种 provider 计数格式、服务 JWT 和模型端点合约。未连接真实付费模型，不将合约桩结果宣称为供应商账单对账。
- V65 已在隔离完整业务库 `gateway_test` 成功执行；当前后端和 Gateway JAR 运行于真实 Nginx → Gateway → 后端 → MySQL/MinIO 链路。
- 18 个真实 HTTP 断言通过：无 Session 的 Key 调用、服务端 trace、响应不回显 Key、last-used 审计、Scope 写入拒绝、不误扣被入口拒绝的 API 调用次数、用量与明细两个版本入口、未知操作拒绝、禁止后端直连、WRITE 目录操作、根目录越界、过期、停用账户和即时撤销。测试创建的 Key 已撤销。
- 代码检查发现查询扩展线程池可能丢失计量上下文，API Key 路径改为显式上下文内串行执行；新增回归验证降级逻辑不能吞掉配额错误。异步执行不再把上下文估算重复计为实际 Token。
- 收尾补充同一任务并发预留保护和切换 Workflow 后的恢复防绕过；以 CI 最小测试库初始化脚本加生产 V65 建立空 `token_test`，9 项真实 MySQL 测试和 2 项 Workflow 工厂测试全部通过（合计 11/11），结果见 `output/api-key-final-checks.log`。新增 2 个用例未包含在前述完整回归计数内。
- 最终 JAR 重启后补充 5 个真实 HTTP 断言通过：Session 保留、普通用户禁止查看/核对账本、管理员可访问核对列表、不能对不存在流水虚构结算。见 `output/api-key-admin-http-tests.log`，本轮真实 HTTP 共 23 个断言。最终六个验收容器均 healthy，CI YAML 解析通过；未执行远端 GitHub Actions。

本轮证据：`output/api-key-backend-full.log`、`output/api-key-gateway-full.log`、`output/api-key-model-tests.log`、`output/api-key-http-tests.log`、`output/gateway-container-acceptance/api-key-results.json`。全部依赖/源码/日志在 D 盘；Docker 沿用 E 盘数据。用户误关闭后，六个验收容器已恢复 healthy。

Workflow 不在本阶段计量范围，API Key 执行入口在 Workflow 启用时返回 503；新计量任务禁止被恢复流程送入未计量的 Workflow。现有 16 个环境测试本轮未补跑，既往真实 MinIO/分享验收见下方记录。上线仍需实际供应商 usage/超时行为及部署现场容量验证。

## 首轮路由与限流验收

- Java 17；网关 Boot 4.0.9 / Cloud 2025.1.4，独立构建通过。
- 首轮使用 Redis 7.4.7 Windows MSYS2 发行包（校验发行页 SHA-256），仅监听 `127.0.0.1:16379`。后续以 Windows 用户 Win10 启动 Docker Desktop，使用独立 Docker Redis 7.4.7、MySQL 8.3 和 MinIO 补充真实环境验证，见下方记录。
- 首轮后端完整回归：494 项，478 通过，16 因环境条件跳过。Docker 启动后补跑 15 项 ShareLinkIntegrationTest 和 1 项 ShareMigrationTest，全部通过、无跳过；两轮合计覆盖全部 494 个不同用例。
- 前端构建通过；原测试集 91 项通过，新限流/下载测试 2 项及后台设置交互 1 项通过，总计 94 个不同用例。
- 网关测试：13 项全部通过，其中 8 项连接真实 Redis 并启动真实网关 HTTP 服务。
- 本地与镜像两份 Compose `config --quiet` 均通过。
- Edge 浏览器验证管理员下载模式的默认值、切换、保存确认和回显；使用与本项目 API 结构一致的合成响应，不等同于真实数据库持久化验收。

## 网关测试

前置：独立 Redis，环境变量 `GATEWAY_TEST_REDIS_PORT=16379`。测试使用随机命名空间，只清理自身键，不执行 FLUSHDB。

| 测试 | 数据与步骤 | 预期及实际 |
|---|---|---|
| 跨实例、跨路由并发 | 两个真实 Gateway，100 并发请求，轮换文件/轮询路径并伪造转发头 | 恰好 60 个 200、40 个 429；上游只收到 60 次，已通过 |
| 滑动窗口 | 放行 60 次；确认第 61 次拒绝；将一条记录置于窗口外后再次访问 | 仅新增一个名额，其他 IP 额度独立，已通过 |
| Redis 历史丢失 | 删除测试命名空间初始化标记后请求 | 503，未访问上游，已通过 |
| Redis 超时 | 对独立测试 Redis 执行短暂 CLIENT PAUSE | 503，上游未收到被拒绝的上传请求，已通过 |
| Cookie/错误/上传 | 多个 Set-Cookie、Session Cookie、伪造地址证明、401、2 MiB 二进制上传 | Cookie 保留，地址证明被覆盖，401 透传，字节一致，已通过 |
| 内部入口 | 请求 internal、actuator、model、workflow、parser 路径 | 均 404，不访问上游，已通过 |
| 流式响应 | 上游先发首块，等待客户端收到后才发尾块 | 首块在上游完成前到达，已通过 |
| 上游断连 | 上游直接关闭连接 | 502，不误报 Redis 503，已通过 |

地址规范化、可信代理头、429 Retry-After 和限流失败不调用下游另有单元测试。

## 下载、鉴权与界面

后端新增测试覆盖：

- 可信网关证明与伪造证明；缺少配置时不信任来源头。
- 代理字节传输与流关闭；直链签名外部 Host、对象版本、过期上限。
- AJAX 获取导航地址；未配置公共存储地址时拒绝直链。
- 分享已批准的不可变版本清单、剩余有效期、撤销与过期拒绝、目录 ZIP 保留代理。
- 数据导出跨用户和过期拒绝、按当前设置交付下载、不返回数据库中旧的内部 URL。
- 设置值校验与直链前置配置；已有 Session、文件授权、管理员权限回归。

前端新增测试验证：429 后跨路由遵循 Retry-After，不立即重复提交；直链使用浏览器导航；管理员选择直链后必须经现有保存确认再调用设置 API。

浏览器证据（本地生成，不随源码提交）：`output/playwright/gateway/download-mode.png`。截图已人工视觉检查，控件和说明没有遮挡或溢出。

## 复现

```powershell
$env:TEMP = 'D:\document\javaproj\ylcloud\output\gateway-tmp'
$env:TMP = $env:TEMP
$env:MAVEN_OPTS = '-Djava.io.tmpdir=D:/document/javaproj/ylcloud/output/gateway-tmp'
$env:GATEWAY_TEST_REDIS_PORT = '16379'
mvn -B -ntp -f src/cloud-gateway/pom.xml '-DargLine=-Djava.io.tmpdir=D:/document/javaproj/ylcloud/output/gateway-tmp' verify
mvn -B -ntp -pl :cloud-server -am '-DargLine=-Djava.io.tmpdir=D:/document/javaproj/ylcloud/output/gateway-tmp' test
```

前端在 `src/cloud-frontend` 执行 `npm run build` 和 `npm test`。本机 Maven 缓存位于 `D:/others/Repo_maven`；Redis 包、日志、npm 缓存、浏览器配置和后续测试临时文件均在项目 D 盘目录。

## Docker 与真实环境补充验收（同日）

以当前 Windows 用户 Win10 启动 Docker Desktop。原本 `LOCALAPPDATA/Docker` 是指向 `E:/AppData/Docker` 的 Junction，直接使用原路径启动会遇到 `dockerInference` 套接字绑定错误。本次仅为启动进程指定真实的 `LOCALAPPDATA=E:/AppData` 后，引擎 29.6.1 正常启动；没有重置、迁移或删除既有 Docker 数据。

独立 Compose 项目 `ylcloud-gateway-acceptance`，MySQL/MinIO/Redis 数据均挂载到 `D:/document/javaproj/ylcloud/output/gateway-container-acceptance`，只使用合成账号及测试数据库。网关和前端均通过正式 Dockerfile 构建新镜像；后端使用已有运行时镜像挂载本次重新打包的 JAR，未重新构建其完整运行时镜像。未启动与本次入口验收无关的模型、解析、Workflow、Qdrant 或 RabbitMQ 服务。

真实 Nginx → Gateway → 后端 → MySQL/MinIO 链路已验证：

- 前端资源、Session 登录/拒绝未登录、CSRF、普通用户不能改管理员设置或下载他人私有文件。
- V64 默认代理，管理员可改直链；MySQL 中实际值正确，后端重启后 Session 和下载模式仍有效。
- 约 2 MiB 二进制文件上传、私有文件代理下载、公开分享代理下载字节一致。
- 私有文件和公开分享的签名 URL 由真实 MinIO 接受，分享包含对象版本；篡改签名返回 403。
- 原生下载返回 303 指向签名 GET，撤销分享后不能获取新链接，切回代理后文件字节一致，注销后 Session 失效。
- 实际停止 Redis 时入口返回 503，静态前端仍可访问，之后恢复 Redis。
- 窗口自然过期后，通过 Nginx 发送 100 个混合公开/会话 API 并发请求并伪造不同来源头：恰好 60 个请求到达业务响应、40 个 429。以上 HTTP 验收共 26 个断言通过。

已发现并修复两项部署配置问题：前端固定 IP 被先启动的网关动态分配占用（两份 Compose 均增加独立动态地址池）；首次 MySQL 初始化超过原健康检查时间（两份 Compose 增加 300 秒启动宽限期）。修复后网关/前端网络和容器启动通过，Nginx 配置校验通过。

另外记录既有 V55 迁移前置条件：新建应用库在普通数据库账户、binary log 开启时创建触发器触发 MySQL 1419。本次仅对隔离 MySQL 设置 `log_bin_trust_function_creators=1` 并恢复新建空库的失败迁移，再成功升级至 V64；正式数据库需由管理员确认迁移权限和触发器创建策略，未改正式权限。

本地证据：`output/gateway-docker-build.log`、`output/gateway-frontend-docker-build.log`、`output/gateway-share-container-tests.log`、`output/gateway-share-migration-test.log`、`output/gateway-container-acceptance/results.jsonl`。隔离环境定义及 HTTP 验收脚本也在该 output 目录，不随源码提交。

## 尚未验证的环境项

- 后端完整 Dockerfile 的重新构建，以及包含模型、解析、队列、向量库等所有服务的生产 Compose 整套启动。
- 部署现场的 HTTPS 证书、TLS 终止器真实地址、共享 NAT 用户负载，以及接近 2 GiB 的文件与容量测试。

以上仍属于生产环境验收边界。CI 已加入 Linux Redis 集成测试和网关镜像构建，但本次尚未推送触发。

## Session 前移与审查修复（同日追加）

用户最终选择只使用 Session，不新增用户 JWT。Gateway 使用 R2DBC 校验现有 MySQL 会话，后端验证请求绑定的 HMAC 身份证明后使用该身份；API Key、CSRF、资源权限和会话创建/撤销继续由后端管理。

本阶段验证结果：

- 网关完整测试 23 项通过，无跳过；包括真实 Redis 双实例 HTTP 验证和真实 MySQL Session 状态/撤销/过期/活动时间验证。测试库 `gateway_session_test` 独立于运行库。
- 后端完整回归 498 项中 482 项通过、16 项因环境跳过；随后 16 项真实 MySQL/MinIO 分享与迁移测试全部通过。另补充并通过身份过滤器 5 项测试（其中 4 项已包含在完整集内），累计覆盖 499 个不同用例。
- 前端完整测试 97 项通过；正式 Dockerfile 构建通过，包含 TypeScript 检查与 Vite 生产构建。覆盖业务 429 不触发全局冷却，以及上传初始化、状态、分片、合并收到 429 后恢复和 503 不重试。
- Linux 非 root 容器中的发布脚本矩阵 12/12，通过新旧部署升级、网关/后端/前端/冒烟失败回滚、管理员 Session 重登、备份门禁、关键服务缺失、回滚失败和信号中断；deploy/backup/restore 的 Bash 语法检查通过。模拟响应使用恢复脚本实际的 `ISOLATED_DOCKER` 契约。未执行真实的破坏性数据回滚。
- Gateway 和前端的新镜像在真实 Nginx → Gateway → 后端 → MySQL/MinIO 链路运行，21 个新增 HTTP 断言通过：伪造身份、无 Session、重复 Cookie、有效 Session、禁止后端直连与直连登录、注销/全端撤销/停用/绝对过期、API Key 兼容及撤销、Session 数据库停机 503、数据库恢复及网关重启后旧 Session 保留、CSRF、私有代理下载、匿名分享、真实 MinIO 直链与资源撤销。

完整链路暴露并修复了真实迁移 `deployment_owner` 为数值 TINYINT 的驱动映射问题；Session 集成测试同步使用真实列类型，避免 BOOLEAN 测试表掩盖差异。前端在受限沙箱中首次遇到 Vitest `EPERM`，允许本地测试进程后完整集通过。以上环境问题未被计入通过结果。

本地证据：`output/gateway-session-final-tests.log`、`output/gateway-session-backend.log`、`output/gateway-session-share-tests.log`、`output/gateway-identity-verified-tests.log`、`output/gateway-session-frontend-final.log`、`output/gateway-session-frontend-image.log`、`output/gateway-deploy-matrix.log`、`output/gateway-container-acceptance/session-results.jsonl`。源码及日志在 D 盘，沿用 E 盘 Docker 数据位置。

复现 Session 集成测试需设置 `GATEWAY_TEST_DB_URL=r2dbc:mysql://<测试账户>:<测试密码>@127.0.0.1:<端口>/gateway_session_test`，并提供专用无密码 Redis 的 `GATEWAY_TEST_REDIS_PORT`。不要指向实际业务数据库。CI 已为这两项依赖配置独立服务；部署现场 HTTPS、完整服务栈及容量验收仍保留为上述环境项。

## 二次审查问题修复（同日追加）

修复生产镜像 Compose 两端 Secure Cookie 默认值不一致、分号路径绕过后端网关签名，以及 Nginx 在网关 IP 改变后仍连接旧地址三项问题。

- 后端定向安全回归 17/17 通过：GatewayIdentityFilterTest 7 项（新增分号和规范化路径两组）、SessionSecurityTest 4 项、BrowserSessionServiceTest 3 项、SessionLoginTest 3 项；后端重新打包成功。
- 真实后端 8 种无签名路径均返回 401，覆盖 `/api;review/session`、大小写编码分号、重复斜杠、点路径和编码 API 前缀。使用有效 Session，确保拒绝来自入口身份检查。
- `scripts/tests/gateway-edge-test.py` 两项测试通过：两份 Compose 共 6 组默认值/显式覆盖组合均保持两端一致；真实 Nginx 上游容器删除重建并强制更换 IP 后，前端无需重载便恢复 POST，原始编码查询参数与请求体保持一致。脚本使用随机命名的独立网络与合成 HTTP 上游，自动清理容器，已加入 CI。
- 正式前端 Dockerfile 构建通过；真实 Nginx → Gateway → 后端链路的 Session/API Key 12 个断言及 CSRF/分享/代理和直链下载 6 个断言再次通过。
- 读取生产 Compose 的实际 Cookie 默认值，为隔离入口配置本地测试 TLS；客户端信任该测试证书并执行正常证书校验。HTTPS 下 7 个断言通过：未登录拒绝、签发 host-only/Secure/HttpOnly Cookie、Gateway 接受该 Cookie、拒绝开发 Cookie 名称、管理员 API、分号直连拒绝、注销后旧 Cookie 失效。该验证不替代部署现场证书和负载分发验收。

本轮证据：`output/gateway-review-fix-backend.log`、`output/gateway-review-fix-edge-tests.log`、`output/gateway-review-fix-frontend-build.log`、`output/gateway-container-acceptance/review-fixed-path-results.json`、`output/gateway-container-acceptance/review-fixed-secure-results.json`、`output/gateway-container-acceptance/session-results.jsonl`。本轮执行相关定向回归，未重新运行全部历史测试。
