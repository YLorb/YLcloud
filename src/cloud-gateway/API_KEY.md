# API Key Gateway 与 Token 计量

本次接入现有 `/api/v1/**` 和兼容别名 `/api/open/**`。浏览器继续使用 Session；外部调用方继续发送 `Authorization: Bearer ylk_...`，不增加客户端 JWT，不新增公共模型代理接口，不扣金额。

## 身份和执行授权

Gateway 使用已有 BCrypt 哈希核验 API Key。密码计算在 4 个有界工作线程执行，每线程最多排队 32 项，避免占用 WebFlux 事件线程。认证总超时 3 秒，数据库故障/队列不可用返回 503；无效、过期、撤销、停用账户返回 401。每次读取当前状态，无身份缓存。

入口按请求方法和路径显式授权，新增接口必须同步加入 `ApiKeyRoutePolicy`，未列出的操作返回 404：

| 操作 | 所需 Scope |
| --- | --- |
| 列出文件 | DRIVE_READ |
| 上传、建目录、移动、删除 | DRIVE_WRITE |
| Space 知识查询、创建 Assistant 会话、提交 Assistant 查询 | KNOWLEDGE_RETRIEVE |
| 提交/读取 Agent 查询 | KNOWLEDGE_AGENT |
| Space 列表、Assistant 结果兼容入口 | RETRIEVE 或 AGENT，结果另按原任务范围复核 |
| capabilities、usage、usage/records | 有效 Key |

Gateway 删除原始 Authorization，覆盖客户端伪造的内部身份头，签发独立 `ak1` 域 HMAC 证明，绑定 Key、用户、服务端请求 UUID、方法、原始路径/查询和 15 秒时限。后端在强制网关模式下拒绝缺失/伪造证明，只重新读取当前 Key 状态与权限，不再次计算 BCrypt。浏览器身份证明与 API Key 证明不可互换。沿用每 IP 任意连续 60 秒最多 60 次及 Redis 故障 503。

后端继续校验目录祖先关系、Space 当前成员关系、原任务范围、管理员权限以及高风险操作授权。异步任务从新增的服务端数据库列恢复用量身份，执行前、每次外部文本模型调用前重新检查 Key 和 Space 权限；客户端 `apiKeyId` 不能指定扣账用户。

## 计量和配额

用户共享 Asia/Shanghai 自然月额度；Key 只作为明细维度。`quota_usage_period.model_tokens` 是已结算量，`model_tokens_reserved` 是在途/待核对预留量。可用额度为月度上限减去这两项，既有 0 上限继续表示无限制。

每次 `/chat`、`/generate` 调用单独建立 `api_key_token_usage` 流水，覆盖模型改写、多查询扩展、HyDE、Step-back、上下文摘要、分类、回答生成和校验。记录用户、Key、服务端请求 ID、异步消息 ID、模型、输入/输出/总 Token、状态和时间。账本不保存 Key 明文或提示词。向量化和本地 rerank 没有 provider Token usage，不虚构文本模型 Token。

1. 调用前：按序列化请求 UTF-8 字节数的两倍、4096 包装余量和输出上限估算预留；使用原子条件 UPDATE，跨 Key/实例共享额度。不足返回 429，未请求模型。输出限制为 1–32768，未指定时显式设为 1024。估算只是准入依据，不计为实际用量。
2. 调用后：只接受 provider 返回的非负整数 input/prompt 与 output/completion，核对 total；未返回 total 时模型服务可以由两个真实计数求和。缺失、不合法或不一致的 usage 不是零。
3. 结算：独立事务锁定调用流水，扣真实 Token 并释放该次预留。重复相同结算不重复扣减；冲突结算拒绝。业务失败、事务回滚、结果被版本栅栏拒绝，都不抹去已经发生的模型用量。结算沿用预留的月份。
4. 不确定结果：超时、响应丢失、usage 缺失、数据库结算失败保留预留；待核对流水标为 UNRESOLVED。崩溃遗留的 RESERVED 超过 24 小时由定时任务标为 UNRESOLVED，不自动退款。
5. 同一请求发生计量/权限错误后，后续文本模型调用停止，RAG 的降级逻辑不能吞掉该错误。异步任务存在未决流水时不盲目重试；核对后才可继续。API Key 的查询扩展在同一显式上下文串行调用，避免线程池丢失归属。

若 provider 实际用量超过保守预留，仍据实结算并阻止后续超额调用。任意外部模型的 tokenizer 和输出限制不能由本地估算作绝对担保；异常超量不会被截断或隐去。

## 查询和核对

- `GET /api/v1/usage`：所属用户本月总量，新增 `modelTokensReserved`。
- `GET /api/v1/usage/records?page=1&pageSize=20`：仅当前 Key 的明细，pageSize 1–100，按创建时间/调用 ID 倒序；支持 `/api/open` 别名。
- `GET /api/admin/quota/token-usage/unresolved?limit=50`：管理员待核对流水。
- `PUT /api/admin/quota/token-usage/{callId}/resolve`：管理员提供 `promptTokens`、`completionTokens`、`evidence`，核对实际供应商记录后结算；保存操作人和依据，沿用 Session、管理员鉴权和 CSRF。普通 API Key 无法通过该入口自报用量。确证未发出/未产生费用的调用可用真实 0/0 核对，不能凭超时自动判零。

模型记录的 `request_id` 与 Gateway `X-Request-Id` / Open API `X-Trace-Id` 对应。同一异步任务保留原始提交请求 ID，重试产生的新模型调用有新的 call_id；已经发生的新调用按真实 usage 计量，提交幂等回放不会重建任务。

## 部署与边界

先部署后端应用 V65（新增字段、流水表），再更新 Gateway 和 model-service。无需新的 Redis；限流仍使用现有独立 Redis，计量账本在 MySQL。新模型服务用 null 表示未知 usage，必须与新后端同时升级，不能继续使用将缺失 usage 映射为 0 的旧模型服务。

本阶段不接入 Workflow 计量。Workflow 启用时新的 API Key Assistant/Agent 执行返回 503；已有带计量身份的排队任务也禁止被恢复流程转交给未计量的 Workflow。浏览器路径和内部 Service JWT 保留原协议。未接入钱包、单价、供应商账单自动拉取、核对管理页面；管理员核对 API 已可用。

历史任务/历史已消费量不追溯伪造 provider usage；本次准确计量针对新 Open API 调用，浏览器既有估算路径保持原行为，但会尊重 API Key 在途预留。需要统一浏览器真实计量时应单独迁移归属链路。

回滚应用时保留 V65 和流水，不删除未决记录或预留额度；旧后端不理解预留额度，不能继续接受模型请求，否则可能绕过新额度约束。

## 验证

真实 MySQL 并发账本测试：显式设置 `YLCLOUD_TOKEN_MYSQL_TEST=true`、`YLCLOUD_TOKEN_TEST_DB_URL`、`YLCLOUD_TOKEN_TEST_DB_USER`、`YLCLOUD_TOKEN_TEST_DB_PASSWORD`，仅允许名称含 `gateway_test` 或 `token_test` 的隔离库。首次可将 `scripts/tests/token-metering-schema.sql` 与生产 V65 依次应用于空测试库。测试用独立负数用户标识清理自己的流水，模型响应来自本地 HTTP 合约桩，不调用真实付费模型。

CI 后端任务启用该真实 MySQL 套件并执行模型 usage/服务鉴权 Python 合约；网关任务包含真实 BCrypt/MySQL 与 Redis 测试。具体执行结果见 [VALIDATION.md](VALIDATION.md)。
