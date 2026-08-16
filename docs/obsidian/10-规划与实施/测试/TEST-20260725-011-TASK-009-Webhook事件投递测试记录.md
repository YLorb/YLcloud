---
id: TEST-20260725-011
type: test-record
status: completed
priority: P1
created: 2026-07-26
updated: 2026-07-26
owner: codex
version: 1
tags: [webhook, outbox, ssrf, signature, retry, mysql]
---

# TASK-009 Webhook 事件投递测试记录

## 测试目标与环境

验证文件、知识、Agent 和 Space 事件的持久 Outbox、至少一次投递、事件幂等、签名轮换、重试/死信、API Key Scope 正文控制、SSRF 与部署所有者私网例外。真实环境为 Win10 身份、Docker MySQL 8.3、打包后的 Spring Boot 服务和本机 TCP HTTP 接收器。

## 自动测试

```powershell
mvn -pl cloud-server -am test '-Dtest=WebhookTargetPolicyTest,WebhookSecretAndSignatureTest,WebhookDeliveryWorkerTest,WebhookSubscriptionServiceTest,WebhookEventServiceTest,AdminDeploymentOwnerPermissionTest,AdminSettingControllerWebhookPolicyTest,KnowledgeChatQueryServiceTest#reconcilesMissingTerminalAgentWebhookEvent' '-Dsurefire.failIfNoSpecifiedTests=false'
mvn -pl cloud-server -am test
mvn -pl cloud-server -am package -DskipTests
Set-Location cloud-frontend
npm test -- --run
npm run build
```

- TASK-009 专项：17 个测试，0 失败、0 错误。
- 后端全量：235 个测试，0 失败、0 错误、0 跳过。
- 前端全量：12 个测试，0 失败；TypeScript/Vite 生产构建成功。
- Spring Boot 可执行包构建成功。

## 安全与故障矩阵

| 场景 | 预期与实际 |
|---|---|
| 回环、链路本地、私网、CGNAT | 默认 400 拒绝 |
| DNS 首次公网、再次解析私网 | 第二次解析拒绝，阻断 rebinding |
| 已允许入口重定向到组播地址 | 跟随前重新解析并拒绝；连接固定至已校验 IP |
| 普通 ADMIN 修改私网策略 | 403；控制器额外要求 deployment owner |
| API Key Scope 不支持事件目录 | 403，不创建订阅 |
| Key 撤销/过期 | 待投递任务立即转 DEAD，不再发请求 |
| 文件/Space 实时 Scope 已移除 | 仍可发送最小事件，业务正文被剥离 |
| 网络超时、408、429、5xx | 指数退避后重试 |
| 非重试 4xx或达到次数上限 | DEAD |
| 业务事件重复 | 复用同一 eventId，Delivery 唯一键去重 |
| 资源版本乱序 | 均持久化，接收方按 eventId/资源版本处理 |
| Secret 轮换 | 过渡期发送当前和旧签名 |

## Win10 Docker/MySQL 与真实 HTTP 验收

- Flyway V41 最终成功；创建 `webhook_subscription`、`webhook_event`、`webhook_delivery` 三表和五项站点策略。
- 默认向 `127.0.0.1` 创建订阅返回 400；部署所有者临时启用私网策略后创建成功。
- Secret 仅创建/轮换响应出现一次，格式为 `whsec_...`；数据库当前与旧 Secret 均为 AES-GCM 密文且不含明文。
- 文件夹创建与 Webhook Event/Delivery 在同一事务形成；接收器首次返回 500，Delivery 进入 `PENDING:1`。
- Agent/Workflow 终态事件若首次 Outbox 写入失败，会由终态消息对账任务补发；事件唯一键防止重复建单。
- 重试前轮换 Secret；第二次响应 204，最终状态 `SUCCEEDED:2:204`。
- 两次请求头和正文使用同一 eventId；首次旧签名、第二次新签名、第二次旧 Secret 过渡签名均按原始字节校验成功。
- includeContent=true 且 Key 仍覆盖目录时，正文包含文件名；验收后订阅为 DISABLED、Key 为 REVOKED、私网策略恢复 false。
- 当前打包产物以 `desktop-vmocljt\\win10` 身份再次连接真实 MySQL，并通过固定至已校验 `127.0.0.1` 的 Socket 投递一次 204；数据库记录 `SUCCEEDED:1:204`，订阅/Key/私网设置随后恢复安全状态。Agent 终态缺失事件查询也在 MySQL 8.3 实库执行通过。

## 性能基线

本机接收器连续接收 10 个 204 事件：全部成功，总耗时 3119 ms，吞吐 3.21 次/秒，端到端 P50 986.838 ms、P95 1459.94 ms。该结果是单 Worker、1 秒轮询和本机网络的验收基线，不代表跨地域容量承诺。

## 结论

事件目录、唯一 eventId、乱序语义、至少一次重试、死信、签名轮换、正文 Scope、Key 即时撤销、DNS rebinding、重定向和私网所有者策略均通过，TASK-20260725-009 状态回写为 `completed`。
