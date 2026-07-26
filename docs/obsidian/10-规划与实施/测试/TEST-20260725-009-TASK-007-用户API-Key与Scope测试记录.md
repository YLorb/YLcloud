---
id: TEST-20260725-009
type: test-record
status: completed
priority: P0
created: 2026-07-26
updated: 2026-07-26
owner: codex
version: 1
tags: [api-key, scope, security, bcrypt, mysql]
---

# TASK-007 用户 API Key 与 Scope 测试记录

## 测试目标

验证用户统一 API Key 的一次性明文、前缀与强哈希存储、云盘/知识独立 Scope、目录与 Space 范围、有效期策略、即时撤销、最后使用时间和 Key 级高风险 Agent 隔离。

## Key 格式与存储

- 明文格式：`ylk_<12 字符可见前缀>_<43 字符随机密钥>`，总长 60。
- 随机部分来自 `SecureRandom` 的 256 bit 随机值，经 Base64URL 无填充编码。
- 服务端只保存 16 字符可见前缀和 60 字符 BCrypt；认证先按前缀定位，再进行恒定成本哈希校验。
- 创建响应是唯一包含 `plaintext` 的契约；实体、列表 VO 和数据库均无明文字段。

## Scope 语义

| Scope | 语义 |
|---|---|
| `DRIVE_READ` | 读取绑定根目录及其当前子树 |
| `DRIVE_WRITE` | 写入绑定根目录及其当前子树，同时隐含 READ |
| `KNOWLEDGE_RETRIEVE` | 在固定 Space 快照内检索 |
| `KNOWLEDGE_AGENT` | 在固定 Space 快照内调用 Agent |

- 云盘根目录按用户文件稳定 ID 绑定；移动操作要求源和目标目录都在边界内，不能借移动扩大 Key 范围。
- Space “全选”只固化创建时可见集合；未来 Space 不自动加入。
- 每次使用仍检查账号 ACTIVE、`apiKey.enabled`、Key 状态/到期时间、当前文件归属或 Space 成员资格。
- 高风险开关仅为该 Key 创建 TASK-006 主体授权，且必须同时具备 `KNOWLEDGE_AGENT` 与风险确认。

## 自动测试

```powershell
mvn -pl cloud-server -am test '-Dtest=UserApiKeyServiceTest,AgentRiskAuthorizationServiceTest' '-Dsurefire.failIfNoSpecifiedTests=false'
mvn -pl cloud-server -am test
mvn -pl cloud-server -am package -DskipTests
Set-Location cloud-frontend
npm test -- --run
npm run build
```

- TASK-007 专项：10 个测试，0 失败、0 错误。
- 后端全量：210 个测试，0 失败、0 错误。
- 前端全量：12 个测试，0 失败；TypeScript/Vite 生产构建成功。
- 后端 Spring Boot 生产包构建成功。

## Win10 Docker/MySQL 验收

- 隔离数据库：`ylcloud_task004_acceptance`。
- Flyway：V39 `user api keys and scopes` 成功，最终版本 V39。
- 站点策略默认值：启用 Key；每用户最多 10 个；允许永不过期；最长有效期 365 天。
- 实际创建 Key：明文格式匹配、长度 60；数据库 hash 长度 60 且为 BCrypt；列表不包含创建时明文。
- 实际 Scope：DRIVE READ/WRITE、KNOWLEDGE RETRIEVE/AGENT 均落库，根目录 ID 为 1。
- 创建时可见 Space 为 1～4；之后新增可见 Space 5，既有 Key 快照仍为 4 条，Space 5 命中为 0。
- 撤销后 `user_api_key=REVOKED`，对应 `agent_risk_authorization=REVOKED`，列表高风险状态立即为 false。

## 负例矩阵

| 场景 | 结果 |
|---|---|
| 错误哈希、过期或撤销 Key | 401 |
| 账号停用或站点禁用 Key | 403 |
| 超过数量/有效期策略 | 创建拒绝 |
| 站点禁止永不过期 | 创建拒绝 |
| 目录源或目标越界 | 403 |
| Space 不在创建快照 | 403 |
| 创建后成员权限被撤销 | 403 |
| 仅 Web 高风险授权或其他 Key 授权 | 当前 Key 不能使用 |
| Key 撤销 | Key 认证与高风险 Agent 能力同步失效 |

## 结论

API Key 生成、一次性明文、BCrypt 存储、到期/撤销、目录边界、Space 快照、实时权限交集、数量策略和高风险隔离全部通过，TASK-20260725-007 状态回写为 `completed`。
