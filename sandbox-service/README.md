# YLcloud Sandbox Service

独立控制面负责校验平台预置 Tool、调用固定镜像并返回与父 Trace 关联的 Sandbox Span。它只接受服务令牌，不执行用户提供的命令、镜像、entrypoint 或 Shell 字符串；Workflow 侧失败时也不会回退到宿主机 callable。

## 配置

1. 将 `.secrets/sandbox_service_token.example` 复制为 `.secrets/sandbox_service_token`，替换为至少 32 个随机字节并禁止提交。
2. 复制 `tool-catalog.example.json` 为 `tool-catalog.local.json`，只登记已扫描并固定到 `@sha256:<digest>` 的生产镜像。本机 Win10 验证可使用 Docker 返回的不可变 `sha256:<image-id>`。
3. 独立 Linux Runner 设置 `YLCLOUD_ROOTLESS_DOCKER_SOCKET=/run/user/<uid>/docker.sock`，然后执行：

   ```powershell
   docker compose -f docker-compose.sandbox-runner.yml config --quiet
   docker compose -f docker-compose.sandbox-runner.yml up -d --build
   ```

4. Workflow 配置：

   ```text
   MINI_AGENT_FLOW_SANDBOX_ENABLED=true
   MINI_AGENT_FLOW_SANDBOX_BASE_URL=http://sandbox-service:8004
   MINI_AGENT_FLOW_SANDBOX_TOKEN_FILE=/run/secrets/sandbox_service_token
   ```

Catalog 是 JSON 数组；每项至少包含 `name`、`version`、固定 `image`、固定 `entrypoint`、`input_schema` 与 `output_schema`。资源上限放在 `limits` 中，字段为 `cpus`、`memory_bytes`、`pids`、`tmpfs_bytes`、`timeout_seconds` 和 `max_result_bytes`。

## 验证

单元测试运行 `pytest -q`。Win10 Docker Desktop 的完整 API→一次性容器验收运行 `scripts/run-sandbox-docker-validation.ps1`；脚本会构建示例 `json-echo` Tool、校验幂等与主体历史、检查容器安全参数，并清理临时容器和进程。

生产发布前还必须完成 [[../docs/obsidian/10-规划与实施/测试/TEST-20260813-001-Sandbox实施测试要求]] 中的 Linux Rootless、AppArmor/SELinux、恶意文件、超时进程树、MinIO 和 RabbitMQ 门禁。
