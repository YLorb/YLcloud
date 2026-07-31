# YLcloud Java Tool Gateway

TASK-006 在 Workflow 仓库中的交付是受控出站客户端
`mini_agent_flow.tools.JavaToolGatewayClient`。它只调用固定的
`POST /internal/v1/tools/invoke`，不会直接访问 YLcloud MySQL、Qdrant 或业务服务。

运行时配置：

- `WORKFLOW_TOOL_GATEWAY_URL`：Compose 中应设为 Java 服务根地址，例如
  `http://ylcloud-server:8080`。不配置时不得启用 Gateway 客户端。
- `WORKFLOW_SERVICE_JWT_ACTIVE_SECRET`：与 Java 服务共享的 HS256 Secret。
- `WORKFLOW_TOOL_GATEWAY_TIMEOUT_SECONDS`：默认 10 秒。
- `WORKFLOW_TOOL_GATEWAY_MAX_REQUEST_BYTES`：默认 1 MiB。
- `WORKFLOW_TOOL_GATEWAY_MAX_RESPONSE_BYTES`：默认 1 MiB。
- `WORKFLOW_TOOL_GATEWAY_MAX_ATTEMPTS`：默认 3，范围 1–5。

安全边界：

- Tool 名称、风险等级和最小 scope 来自代码内不可变目录，Plan 不能覆盖。
- JWT 使用独立 `ylcloud-tool-gateway` audience，并绑定 user/session/run/execution/node/invocation。
- 高风险 Tool 必须携带 Java 签发并存储的 `ALLOW_ONCE` 或 `ALLOW_SIMILAR` Grant。
- 网络失败和 5xx 仅使用同一 invocation ID 重试；Java 以该 ID 防止重复副作用。
- 客户端不跟随重定向，不在异常中回显 Token、参数或下游正文。
- 请求在发送前完成 JSON 序列化和大小校验；响应按流量计数，超过上限立即中止。
